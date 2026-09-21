import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Checks a built Vela plugin jar the way the IDE would when it loads it.
 *
 * Nine layers, each reported separately and each able to fail the run:
 *
 *   1. structure   the jar contains META-INF/plugin.xml, the icons, and a
 *                  .class file for every FQN plugin.xml names in *any* of the
 *                  attributes that name a class (implementationClass, instance,
 *                  factoryClass, class, implementation, serviceImplementation)
 *   2. bytecode    every .class is Java 21 (major 65) or lower
 *   3. platform    the installed IDE's own descriptors are read and indexed  -- 
 *                  plugin/module ids it provides, the extension points it
 *                  declares, the action ids it defines  --  because three of the
 *                  checks below are questions only those descriptors can answer
 *   4. extensions  every extension this plugin registers names an extension point
 *                  the installed platform actually declares (or that some other
 *                  installed plugin registers an extension under); an unknown
 *                  extension point is a *silent* no-op, which is the same class
 *                  of bug as 0.1.0's `.vela`-only file type
 *   5. depends     every <depends> module is one the installed platform provides
 *   6. file type   the file type claims `vel`  --  both in plugin.xml's
 *                  extensions="..." and in the loaded class  --  and the loader
 *                  function in the plugin recognises `x.vel`; and every
 *                  language="..." attribute equals the id of the language the
 *                  type is bound to
 *   7. actions     every add-to-group group-id resolves to a *group*: one this
 *                  plugin declares, or one the installed platform defines. An id
 *                  that is an <action>  --  not a group  --  is the 0.1.0 failure
 *                  (SEVERE ... should be instance of DefaultActionGroup)
 *   8. linkage     every class loads through a class loader built from [plugin
 *                  jar + the IDE's own lib/*.jar], and implements the type its
 *                  extension point requires  --  the requirement is taken from the
 *                  platform's own <with attribute=... implements=...> declaration
 *                  where there is one
 *   9. behaviour   headless: the language registers, a .vel snippet lexes, the
 *                  compiler-diagnostic reader turns what `vm.exe check` really
 *                  writes into problems with the right line numbers, and a real
 *                  tests/build/*.vel file survives the whole path end to end
 *  10. compiler    smoke / parity / contract, by running vm.exe: a program builds
 *                  and runs, the two backends agree, every `vela_*` symbol the
 *                  emitted C calls exists in the runtime header
 *  11. run path    what the class files themselves say about the console, the
 *                  output handoff and the runtime directory
 *  12. versions    plugin.xml, build.gradle.kts, the dist file name and
 *                  CHANGELOG.md carry one number
 *  13. inert       class -> plugin.xml, the direction nothing else reads: a class
 *                  implementing a contract the platform registers must be named by
 *                  a registration or reached from another class, never both absent
 *
 * Usage: VerifyPlugin <plugin.jar> <platform-classpath-file> <repo-root>
 *
 * The third argument is the Vela checkout that holds `selfhost/build/vm.exe` and
 * `tests/build/`.  Without it the two end-to-end checks that need a compiler are
 * reported as unverified rather than silently skipped.
 *
 * "Against the platform" here means: the installed IDE's own descriptors,
 * read from <ide>\lib\*.jar, <ide>\modules\module-descriptors.jar and
 * <ide>\plugins\**\*.jar.  The platform's ActionManager and extension registry
 * need a booted application and cannot be asked headlessly, so the group and
 * extension-point questions are answered from the same descriptors those
 * services are populated from  --  a descriptor scan, not a live ActionManager.
 */
public final class VerifyPlugin {

    private static final int EXPECTED_MAJOR = 65; // Java 21

    /** Attributes that name a class the plugin jar must contain. */
    private static final List<String> CLASS_ATTRIBUTES = List.of(
            "implementationClass", "instance", "factoryClass", "class",
            "implementation", "serviceImplementation");

    /**
     * Last-resort expectations, keyed by extension tag: which attribute carries
     * the implementation, and what it must implement.  Preferred answer is the
     * platform's own <with attribute=... implements=...>; this is the safety net
     * for extension points declared with interface= instead.
     */
    private static final Map<String, String[]> FALLBACK_TYPE = new LinkedHashMap<>();

    static {
        FALLBACK_TYPE.put("action", new String[]{"class", "com.intellij.openapi.actionSystem.AnAction"});
        // A <group class="..."> must be an ActionGroup: the platform shows it as a submenu,
        // and an AnAction that is not a group throws when the menu is built.
        FALLBACK_TYPE.put("group", new String[]{"class", "com.intellij.openapi.actionSystem.ActionGroup"});
        FALLBACK_TYPE.put("fileType", new String[]{"implementationClass", "com.intellij.openapi.fileTypes.FileType"});
        FALLBACK_TYPE.put("lang.syntaxHighlighterFactory", new String[]{"implementationClass", "com.intellij.openapi.fileTypes.SyntaxHighlighterFactory"});
        FALLBACK_TYPE.put("externalAnnotator", new String[]{"implementationClass", "com.intellij.lang.annotation.ExternalAnnotator"});
        FALLBACK_TYPE.put("applicationConfigurable", new String[]{"instance", "com.intellij.openapi.options.Configurable"});
        FALLBACK_TYPE.put("parserDefinition", new String[]{"implementationClass", "com.intellij.lang.ParserDefinition"});
        FALLBACK_TYPE.put("lang.parserDefinition", new String[]{"implementationClass", "com.intellij.lang.ParserDefinition"});
        FALLBACK_TYPE.put("lang.psiStructureViewFactory", new String[]{"implementationClass", "com.intellij.lang.PsiStructureViewFactory"});
        FALLBACK_TYPE.put("lang.structureViewBuilder", new String[]{"implementationClass", "com.intellij.ide.structureView.StructureViewBuilder"});
        FALLBACK_TYPE.put("structureViewBuilder", new String[]{"factoryClass", "com.intellij.ide.structureView.StructureViewBuilderProvider"});
        FALLBACK_TYPE.put("documentationProvider", new String[]{"implementationClass", "com.intellij.lang.documentation.DocumentationProvider"});
        FALLBACK_TYPE.put("lang.documentationProvider", new String[]{"implementationClass", "com.intellij.lang.documentation.DocumentationProvider"});
        FALLBACK_TYPE.put("completion.contributor", new String[]{"implementationClass", "com.intellij.codeInsight.completion.CompletionContributor"});
        FALLBACK_TYPE.put("codeInsight.parameterInfo", new String[]{"implementationClass", "com.intellij.lang.parameterInfo.ParameterInfoHandler"});
        FALLBACK_TYPE.put("configurationType", new String[]{"implementation", "com.intellij.execution.configurations.ConfigurationType"});
        FALLBACK_TYPE.put("toolWindow", new String[]{"factoryClass", "com.intellij.openapi.wm.ToolWindowFactory"});
        FALLBACK_TYPE.put("runConfigurationProducer", new String[]{"implementation", "com.intellij.execution.actions.RunConfigurationProducer"});
        // The semantic annotator, go-to-declaration and references.  Each of these was
        // missing from plugin.xml at some point while its class existed and compiled:
        // an unregistered extension point is how a finished class becomes dead code.
        FALLBACK_TYPE.put("annotator", new String[]{"implementationClass", "com.intellij.lang.annotation.Annotator"});
        FALLBACK_TYPE.put("gotoDeclarationHandler", new String[]{"implementation", "com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler"});
        FALLBACK_TYPE.put("psi.referenceContributor", new String[]{"implementation", "com.intellij.psi.PsiReferenceContributor"});
        FALLBACK_TYPE.put("lang.elementManipulator", new String[]{"implementationClass", "com.intellij.psi.ElementManipulator"});
    }

    /** One extension attribute that names a class, and where it was written. */
    private record Ref(String epId, String localTag, String fqn, String fieldName, String where) {
    }

    private final List<String> failures = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    /** The plugin surface, one fact per line: printed and written to build\logs\surface.txt. */
    private final List<String> surface = new ArrayList<>();
    private Path surfaceFile = null;
    /** Scratch for the compiler-tree checks: <plugin>\build\verify\ . */
    private Path verifyDir = null;

    /**
     * The parity probe.  Subtraction is deliberately first: `vela_sub_range` and
     * `vela_wrapped_too_low` were wrong in the runtime for a while, so every
     * subtraction in *compiled* code panicked while the interpreter was right, and
     * nothing compared the two.  Indexing and a loop with `+=` follow, because the
     * bounds-check symbol and the accumulator lowering are the other two places the
     * two backends can drift apart.
     */
    private static final String PARITY_PROGRAM = """
            def main() -> None {
                mut a: int = 1
                mut b: int = 5
                print(a - b)
                print(0 - 9)
                mut xs: Array[int, 3] = [4, 5, 6]
                print(xs[0] - xs[2])
                mut t: int = 0
                for i in range(0, 4) {
                    t += i
                }
                print(t)
            }
            """;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: VerifyPlugin <plugin.jar> <platform-classpath-file> [<repo-root>]");
            System.exit(2);
        }
        Path repoRoot = args.length > 2 ? Path.of(args[2]) : null;
        System.exit(new VerifyPlugin().run(Path.of(args[0]), Path.of(args[1]), repoRoot) ? 0 : 1);
    }

    private boolean run(Path jar, Path classpathFile, Path repoRoot) throws Exception {
        long started = System.currentTimeMillis();
        System.out.println("plugin jar : " + jar.toAbsolutePath());
        System.out.println("jar size   : " + Files.size(jar) + " bytes");
        System.out.println("classpath  : " + classpathFile.toAbsolutePath());
        System.out.println("repo root  : " + (repoRoot == null ? "(not given)" : repoRoot.toAbsolutePath()));
        System.out.println("checked at : " + java.time.LocalDateTime.now().withNano(0));
        // the inventory lands beside the log, derived from the artifact under test
        this.surfaceFile = jar.toAbsolutePath().getParent().getParent().getParent().getParent()
                .resolve("build").resolve("logs").resolve("surface.txt");
        this.verifyDir = surfaceFile.getParent().getParent().resolve("verify");
        System.out.println();

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                if (!e.isDirectory()) {
                    try (InputStream in = zip.getInputStream(e)) {
                        entries.put(e.getName(), in.readAllBytes());
                    }
                }
            }
        }

        // ---------- 1. structure ----------
        section("1. structure");
        requireEntry(entries, "META-INF/plugin.xml");
        requireEntry(entries, "META-INF/pluginIcon.svg");
        requireEntry(entries, "icons/vela.svg");

        byte[] xmlBytes = entries.get("META-INF/plugin.xml");
        Element root = null;
        if (xmlBytes != null) {
            root = parseXml(xmlBytes);
            System.out.println("    plugin.xml root element: <" + root.getTagName() + ">"
                    + " id=" + attr(root, "id") + " version=" + textOf(root, "version"));
            descriptorFreshness(jar, xmlBytes);
        }
        if (root == null) {
            fail("plugin.xml could not be parsed; every descriptor check below is unverified");
            return verdict(started);
        }

        List<Ref> refs = new ArrayList<>();
        collectRefs(root, refs, null);

        boolean allPresent = true;
        for (Ref ref : refs) {
            String path = ref.fqn.replace('.', '/') + ".class";
            if (!entries.containsKey(path)) {
                allPresent = false;
                fail("plugin.xml " + ref.where + " -> " + ref.fqn + " : NO CLASS FILE (" + path + ")");
            } else {
                System.out.println("    OK  " + pad(ref.where + " = " + ref.fqn, 62) + " in the jar");
            }
        }
        if (allPresent && !refs.isEmpty()) {
            System.out.println("    -> every class named by plugin.xml is in the jar (" + refs.size()
                    + " attribute(s) checked: " + String.join(", ", CLASS_ATTRIBUTES) + ")");
        }

        List<String> iconPaths = new ArrayList<>();
        collectAttribute(root, "icon", iconPaths);
        for (String icon : new LinkedHashSet<>(iconPaths)) {
            String entry = icon.startsWith("/") ? icon.substring(1) : icon;
            if (entries.containsKey(entry)) {
                System.out.println("    OK  " + pad("icon " + icon, 62) + " -> " + entry);
            } else {
                fail("icon " + icon + " is not in the jar (looked for " + entry + ")");
            }
        }

        // ---------- 2. bytecode level ----------
        section("2. bytecode level (IDEA 2024.2+ wants Java 21 compatible: major <= 65)");
        int classes = 0;
        int worst = 0;
        String worstName = "";
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (!e.getKey().endsWith(".class")) {
                continue;
            }
            byte[] b = e.getValue();
            if (b.length < 8 || b[0] != (byte) 0xCA || b[1] != (byte) 0xFE) {
                fail(e.getKey() + " is not a class file");
                continue;
            }
            int major = ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
            classes++;
            if (major > worst) {
                worst = major;
                worstName = e.getKey();
            }
            if (major > EXPECTED_MAJOR) {
                fail(e.getKey() + " targets class file major " + major + " > " + EXPECTED_MAJOR);
            }
        }
        System.out.println("    " + classes + " class files, highest major version = " + worst
                + " (" + worstName + ") -> Java " + (worst - 44));

        // ---------- class loader from plugin jar + IDE libs ----------
        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        List<Path> platformClasspath = new ArrayList<>();
        for (String line : Files.readAllLines(classpathFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String cpf = line.charAt(0) == '\uFEFF' ? line.substring(1) : line;
            for (String entry : cpf.split(java.io.File.pathSeparator)) {
                if (!entry.isBlank()) {
                    platformClasspath.add(Path.of(entry));
                    urls.add(Path.of(entry).toUri().toURL());
                }
            }
        }
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), null);

        // ---------- 3. the installed platform's own descriptors ----------
        section("3. the installed platform, read from its own descriptors");
        PlatformIndex index = buildPlatformIndex(platformClasspath, loader);
        if (index == null) {
            fail("could not find the installed IDE's descriptors; extension point, depends and "
                    + "action-group checks are unverified");
        } else {
            System.out.println("    IDE home      : " + index.ideHome);
            System.out.println("    descriptors   : " + index.descriptorCount + " XML descriptor(s) scanned"
                    + " (" + index.scannedJars + " jar(s), " + index.millis + " ms)");
            System.out.println("    provides      : " + index.providedIds.size() + " plugin/module id(s)");
            System.out.println("    declares      : " + index.epDeclared.size() + " extension point id(s), "
                    + index.epBareName.size() + " by bare name");
            System.out.println("    defines       : " + index.actionKind.size() + " action/group id(s)");
            notes.add("action-group ids and extension points are resolved from the installed IDE's own "
                    + "descriptors (a descriptor scan).  The platform's ActionManager needs a booted "
                    + "application and cannot be asked headlessly.");
        }

        // ---------- 4. extension points ----------
        section("4. extension points this plugin registers under");
        String namespace = null;
        for (Element exts : descendants(root, "extensions")) {
            if (attr(exts, "defaultExtensionNs") != null) {
                namespace = attr(exts, "defaultExtensionNs");
            }
        }
        List<Element> extensionTags = new ArrayList<>();
        for (Element exts : descendants(root, "extensions")) {
            NodeList kids = exts.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i) instanceof Element child) {
                    extensionTags.add(child);
                }
            }
        }
        if (extensionTags.isEmpty()) {
            fail("plugin.xml registers no extensions at all");
        }
        if (index != null) {
            for (Element tag : extensionTags) {
                String epId = epIdOf(namespace, tag.getTagName());
                surf("extension point  : " + epId + "  <" + tag.getTagName() + ">  " + attrsOf(tag));
                String[] where = index.locateEp(epId, tag.getTagName());
                if (where != null) {
                    System.out.println("    OK  " + pad("<" + tag.getTagName() + ">", 34) + " -> " + epId
                            + " (" + where[0] + ")");
                } else if (tag.getTagName().indexOf('.') < 0 && namespace != null
                        && index.epDeclared.containsKey(namespace + ".lang." + tag.getTagName())) {
                    // `<parserDefinition>` is the one case where the platform's own
                    // declaration carries the `lang.` prefix: the id the IDE looks up is
                    // com.intellij.lang.parserDefinition, so the un-prefixed tag names an
                    // extension point that does not exist and the extension never fires.
                    fail("plugin.xml registers <" + tag.getTagName() + "> but the installed platform "
                            + "declares that extension point as \"" + namespace + ".lang."
                            + tag.getTagName() + "\"  --  <" + tag.getTagName() + "> names the id \""
                            + epId + "\", which no installed descriptor declares.  Registration is a "
                            + "silent no-op: the IDE logs nothing useful and the feature never fires.");
                } else {
                    fail("plugin.xml registers <" + tag.getTagName() + "> (extension point \""
                            + epId + "\"), which no installed descriptor declares or uses. "
                            + "Registration is a silent no-op.");
                }
            }
            registrationAttributes(root, namespace, index);
        }

        // ---------- 5. depends ----------
        section("5. <depends> modules");
        if (index != null) {
            for (Element d : descendants(root, "depends")) {
                String id = d.getTextContent().trim();
                if (id.isEmpty()) {
                    continue;
                }
                String extra = attr(d, "optional") != null ? " (optional=\"" + attr(d, "optional") + "\")" : "";
                surf("depends          : " + id);
                if (index.providedIds.contains(id)) {
                    System.out.println("    OK  " + pad("<depends>" + id + "</depends>", 62)
                            + " provided by the installed platform" + extra);
                } else {
                    fail("<depends>" + id + "</depends>: the installed platform provides no plugin or "
                            + "module with that id  --  the plugin would not load at all");
                }
            }
        }

        // ---------- 6. file type and language ----------
        section("6. file type suffixes and language ids");
        Class<?> fileTypeCls = Class.forName("dev.vela.plugin.VelaFileType", true, loader);
        Object fileType = fileTypeCls.getField("INSTANCE").get(null);
        List<Element> fileTypeTags = descendants(root, "fileType");
        if (fileTypeTags.isEmpty()) {
            fail("plugin.xml declares no <fileType>; a .vel file would have no language and no editor");
        }
        Set<String> xmlExtensions = new LinkedHashSet<>();
        for (Element ft : fileTypeTags) {
            String list = attr(ft, "extensions");
            if (list == null) {
                fail("<fileType ...> has no extensions=\"...\" attribute: the file type claims nothing");
                continue;
            }
            for (String part : list.split("[;, ]+")) {
                if (!part.isBlank()) {
                    xmlExtensions.add(part.trim());
                }
            }
            System.out.println("    plugin.xml <fileType> claims: " + xmlExtensions);
            surf("file type        : name=\"" + attr(ft, "name") + "\" language=\"" + attr(ft, "language")
                    + "\" extensions=\"" + list + "\"");
            if (!xmlExtensions.contains("vel")) {
                fail("<fileType extensions=\"" + list + "\"> does not claim \"vel\".  Every Vela file in "
                        + "this repository ends in .vel, so the plugin does nothing on a real file  --  this "
                        + "is exactly what shipped in 0.1.0 (extensions=\"vela\" only).");
            } else {
                System.out.println("    OK  " + pad("<fileType extensions=...>", 34) + " claims vel");
            }
            if (!xmlExtensions.contains("vela")) {
                notes.add("<fileType extensions=\"" + list + "\"> does not claim \"vela\"; harmless, but "
                        + "the README and the description promise both spellings.");
            }
        }
        String defaultExtension = (String) fileTypeCls.getMethod("getDefaultExtension").invoke(fileType);
        if ("vel".equals(defaultExtension)) {
            System.out.println("    OK  " + pad("VelaFileType.getDefaultExtension()", 34) + " = vel");
            surf("default extension: " + defaultExtension);
        } else {
            fail("VelaFileType.getDefaultExtension() = \"" + defaultExtension + "\", expected \"vel\"");
        }
        // The class's own list, if this platform's FileType still has one.  262 has
        // no getExtensions(); the plugin keeps a plain `EXTENSIONS` list instead.
        try {
            Object list = fileTypeCls.getMethod("getEXTENSIONS").invoke(fileType);
            System.out.println("    note: VelaFileType.EXTENSIONS = " + list);
            if (list instanceof List<?> l && !(l.contains("vel") && l.contains("vela"))) {
                fail("VelaFileType.EXTENSIONS = " + list + " does not contain both vel and vela");
            }
        } catch (NoSuchMethodException ignored) {
            System.out.println("    note: VelaFileType has no EXTENSIONS list (fine on this platform)");
        }

        // The loader function is what the annotator and the actions ask; 0.1.0's bug
        // lived here too: "is this a Vela file?" answered for the wrong suffix.
        try {
            Class<?> kt = Class.forName("dev.vela.plugin.VelaLanguageKt", true, loader);
            Method isVela = kt.getMethod("isVelaFileName", String.class);
            Object[][] cases = {{"hello.vel", true}, {"hello.vela", true}, {"hello.py", false}, {"vel", false}};
            for (Object[] c : cases) {
                boolean got = (Boolean) isVela.invoke(null, c[0]);
                if (got == (Boolean) c[1]) {
                    System.out.println("    OK  " + pad("isVelaFileName(\"" + c[0] + "\")", 34) + " = " + got);
                } else {
                    fail("isVelaFileName(\"" + c[0] + "\") = " + got + ", expected " + c[1]);
                }
            }
        } catch (Throwable t) {
            notes.add("could not probe isVelaFileName: " + t);
        }

        // Every language="..." must name the language the file type is bound to.
        String registeredLangId = null;
        try {
            Object lang = fileTypeCls.getMethod("getLanguage").invoke(fileType);
            registeredLangId = (String) lang.getClass().getMethod("getID").invoke(lang);
            System.out.println("    OK  " + pad("VelaFileType.getLanguage()", 34) + " -> " + registeredLangId);
            surf("language         : " + registeredLangId);
        } catch (Throwable t) {
            fail("could not read the language the file type is bound to: " + t);
        }
        List<String> langAttrs = new ArrayList<>();
        collectAttribute(root, "language", langAttrs);
        if (registeredLangId != null) {
            for (String l : new LinkedHashSet<>(langAttrs)) {
                if (registeredLangId.equals(l)) {
                    System.out.println("    OK  " + pad("language=\"" + l + "\"", 34)
                            + " is the language this plugin registers");
                } else {
                    fail("language=\"" + l + "\" is not the language this plugin registers (\""
                            + registeredLangId + "\"): the IDE would look up a language that never "
                            + "matches and the extension would never fire.");
                }
            }
        }

        // ---------- 7. action group wiring ----------
        section("7. <add-to-group> wiring (the 0.1.0 SEVERE: an action named as a group)");
        Set<String> ownGroups = new LinkedHashSet<>();
        Set<String> ownActions = new LinkedHashSet<>();
        for (Element g : descendants(root, "group")) {
            if (attr(g, "id") != null) {
                ownGroups.add(attr(g, "id"));
            }
        }
        for (Element a : descendants(root, "action")) {
            if (attr(a, "id") != null) {
                ownActions.add(attr(a, "id"));
            }
        }
        for (Element g : descendants(root, "group")) {
            surf("group            : id=\"" + attr(g, "id") + "\" class=" + attr(g, "class"));
        }
        if (ownGroups.isEmpty()) {
            surf("group            : (none)");
        }
        for (Element a : descendants(root, "action")) {
            surf("action           : id=\"" + attr(a, "id") + "\" text=\"" + attr(a, "text")
                    + "\" class=" + attr(a, "class"));
        }
        List<Element> addToGroups = descendants(root, "add-to-group");
        if (addToGroups.isEmpty()) {
            notes.add("plugin.xml has no <add-to-group>; nothing drives a menu");
        }
        for (Element atg : addToGroups) {
            String id = attr(atg, "group-id");
            if (id == null) {
                fail("<add-to-group> without group-id");
                continue;
            }
            surf("add-to-group     : group-id=\"" + id + "\" anchor=" + attr(atg, "anchor"));
            String owner = "";
            for (Element g : descendants(root, "group")) {
                if (children(g, "add-to-group").contains(atg) && attr(g, "id") != null) {
                    owner = " (owner <group id=\"" + attr(g, "id") + "\">)";
                }
            }
            if (ownGroups.contains(id)) {
                System.out.println("    OK  add-to-group group-id=\"" + id + "\" -> group declared in this "
                        + "plugin.xml" + owner);
            } else if (ownActions.contains(id)) {
                fail("add-to-group group-id=\"" + id + "\" names an <action> in this plugin.xml, not a "
                        + "<group>.  The platform resolves it to an ActionStub and logs \"SEVERE ... should "
                        + "be instance of DefaultActionGroup\", and the action never reaches a menu  --  the "
                        + "0.1.0 bug, exactly.");
            } else if (index == null) {
                fail("add-to-group group-id=\"" + id + "\" could not be resolved (no platform descriptors)");
            } else {
                String[] found = index.locateAction(id);
                if (found == null) {
                    fail("add-to-group group-id=\"" + id + "\" is not an action or group id the installed "
                            + "platform defines, and this plugin does not declare it: the platform resolves "
                            + "it to an ActionStub, which is what 0.1.0 did with \"Vela.BuildAndRun\".");
                } else if ("group".equals(found[0])) {
                    System.out.println("    OK  add-to-group group-id=\"" + id + "\" -> platform group ("
                            + found[1] + ")" + owner);
                } else {
                    fail("add-to-group group-id=\"" + id + "\" resolves to an *action* (" + found[1]
                            + "), not a group: the platform logs \"SEVERE ... should be instance of "
                            + "DefaultActionGroup\" and the entry never appears.");
                }
            }
        }
        for (String g : ownGroups) {
            List<Element> inner = new ArrayList<>();
            for (Element el : descendants(root, "group")) {
                if (g.equals(attr(el, "id"))) {
                    inner.addAll(children(el, "action"));
                }
            }
            System.out.println("    " + pad("<group id=\"" + g + "\">", 42) + " declares "
                    + inner.size() + " action(s)");
        }

        // ---------- 8. linkage ----------
        section("8. linkage against the installed platform");
        Map<String, Class<?>> loaded = new LinkedHashMap<>();
        for (Ref ref : refs) {
            try {
                Class<?> c = Class.forName(ref.fqn, false, loader);
                loaded.put(ref.fqn, c);
                String expected = null;
                String source = null;
                if (index != null) {
                    String[] with = index.implementedType(ref.epId, ref.localTag);
                    if (with != null && with.length == 2 && ref.where.endsWith("/" + with[0])) {
                        expected = with[1];
                        source = "the platform's own <with attribute=\"" + with[0] + "\" implements=\""
                                + with[1] + "\">";
                    }
                }
                if (expected == null && index != null
                        && (ref.where.endsWith("/implementation") || ref.where.endsWith("/implementationClass"))) {
                    String iface = index.interfaceType(ref.epId, ref.localTag);
                    if (iface != null) {
                        expected = iface;
                        source = "the platform's own <extensionPoint ... interface=\"" + iface + "\">";
                    }
                }
                if (expected == null) {
                    String[] fb = FALLBACK_TYPE.get(ref.localTag);
                    if (fb != null && ref.where.endsWith("/" + fb[0])) {
                        expected = fb[1];
                        source = "<" + ref.localTag + "> accepts " + fb[0];
                    }
                }
                if (expected != null) {
                    try {
                        Class<?> base = Class.forName(expected, false, loader);
                        if (base.isAssignableFrom(c)) {
                            System.out.println("    OK  " + pad(ref.fqn, 46) + " is a " + simple(expected)
                                    + "   [" + source + "]");
                        } else {
                            fail(ref.fqn + " is NOT a " + expected + " (required by " + source
                                    + "): the class loads, but the platform would reject the extension "
                                    + "at registration time and the feature would never fire");
                        }
                    } catch (ClassNotFoundException cnfe) {
                        notes.add("expected type " + expected + " is not on the platform classpath; "
                                + "type check skipped for " + ref.fqn);
                        System.out.println("    OK  " + pad(ref.fqn, 46) + " loaded");
                    }
                } else {
                    System.out.println("    OK  " + pad(ref.fqn, 46) + " loaded");
                }
                if (ref.fieldName != null) {
                    Field f = c.getField(ref.fieldName);
                    if (!Modifier.isStatic(f.getModifiers())) {
                        fail(ref.fqn + "." + ref.fieldName + " is not static");
                    } else if (!f.getType().equals(c)) {
                        fail(ref.fqn + "." + ref.fieldName + " has type " + f.getType().getName()
                                + ", expected " + ref.fqn);
                    } else {
                        Object v = f.get(null); // triggers class initialisation, as the IDE's own lookup would
                        System.out.println("    OK  " + pad(ref.fqn + "." + ref.fieldName, 46)
                                + " -> " + v.getClass().getName() + " instance");
                    }
                }
            } catch (Throwable t) {
                fail(ref.fqn + " did not load: " + t);
            }
        }

        // ---------- 9. behaviour ----------
        section("9. behaviour, headless (no IDE application instance)");

        // 9a. the language ID plugin.xml refers to
        try {
            Class<?> languageCls = Class.forName("com.intellij.lang.Language", true, loader);
            Method findByID = languageCls.getMethod("findLanguageByID", String.class);
            Method getID = languageCls.getMethod("getID");
            for (String langId : new LinkedHashSet<>(langAttrs)) {
                Object lang = findByID.invoke(null, langId);
                if (lang == null) {
                    fail("plugin.xml uses language=\"" + langId + "\" but no Language with that ID is registered");
                } else {
                    System.out.println("    OK  language=\"" + langId + "\" -> " + lang.getClass().getName()
                            + " (id=" + getID.invoke(lang) + ")");
                }
            }
        } catch (Throwable t) {
            fail("language registration probe failed: " + t);
        }

        // 9b. the lexer, on real Vela text
        try {
            Class<?> lexerCls = Class.forName("dev.vela.plugin.VelaLexer", true, loader);
            Object lexer = lexerCls.getDeclaredConstructor().newInstance();
            String sample = "# comment\nmut x = 0x1F + 3.5e2 ** 2 // 2\n"
                    + "def f(s: str) -> Int:\n    if s != \"a\\tb\" and True:\n        return 42\n";
            Method start = lexerCls.getMethod("start", CharSequence.class, int.class, int.class, int.class);
            Method advance = lexerCls.getMethod("advance");
            Method getTokenType = lexerCls.getMethod("getTokenType");
            Method getTokenStart = lexerCls.getMethod("getTokenStart");
            Method getTokenEnd = lexerCls.getMethod("getTokenEnd");
            Method getBufferEnd = lexerCls.getMethod("getBufferEnd");
            start.invoke(lexer, sample, 0, sample.length(), 0);
            List<String> tokens = new ArrayList<>();
            int guard = 0;
            while (getTokenType.invoke(lexer) != null && guard++ < 10_000) {
                tokens.add(getTokenType.invoke(lexer).toString() + "[" + getTokenStart.invoke(lexer)
                        + "," + getTokenEnd.invoke(lexer) + ")");
                advance.invoke(lexer);
            }
            if (guard >= 10_000) {
                fail("lexer never reached end of buffer");
            } else if ((int) getBufferEnd.invoke(lexer) != sample.length()) {
                fail("lexer reported buffer end " + getBufferEnd.invoke(lexer) + " != " + sample.length());
            } else if (tokens.isEmpty()) {
                fail("lexer produced no tokens");
            } else {
                System.out.println("    OK  VelaLexer produced " + tokens.size() + " tokens; first 16:");
                System.out.println("        " + String.join(" ", tokens.subList(0, Math.min(16, tokens.size()))));
            }
        } catch (Throwable t) {
            fail("lexer probe failed: " + t);
        }

        Class<?> compilerCls = null;
        Object compiler = null;
        Class<?> problemCls = null;

        // 9c. the diagnostic reader, on a synthetic stderr
        try {
            compilerCls = Class.forName("dev.vela.plugin.VelaCompiler", true, loader);
            compiler = compilerCls.getField("INSTANCE").get(null);
            problemCls = Class.forName("dev.vela.plugin.VelaProblem", true, loader);
            String stderr = "vela: safety error: borrow of `x` while `y` holds it\n"
                    + "  at C:\\tmp\\a.vel:12\n"
                    + "vela: type error: expected Int, found Str\n"
                    + "  at C:\\tmp\\a.vel:7\n"
                    + "vela: parse error: expected `)`\n"
                    + "  at line 3\n"
                    + "vela: panic: the driver gave up\n";
            List<String> seen = problems(compilerCls, compiler, problemCls, stderr);
            System.out.println("    parsed " + seen.size() + " problem(s) from a 4-diagnostic sample:");
            seen.forEach(s -> System.out.println("        " + s));
            List<String> expected = List.of(
                    "safety error@12 \"borrow of `x` while `y` holds it\"",
                    "type error@7 \"expected Int, found Str\"",
                    "parse error@3 \"expected `)`\"");
            if (!seen.equals(expected)) {
                fail("diagnostic reader produced " + seen + ", expected " + expected);
            } else {
                System.out.println("    OK  kinds, messages, path:line and \"at line N\" all read correctly; "
                        + "the bare \"vela: panic:\" line was correctly ignored");
            }
        } catch (Throwable t) {
            fail("diagnostic reader probe failed: " + t);
        }

        // 9d. the whole diagnostics path, once, on real .vel files and the real compiler
        diagnosticsEndToEnd(jar, repoRoot, compilerCls, compiler, problemCls);

        // 10. the compiler the plugin drives: does running a program work at all?
        //     Three of the last four defects in this repository were "no program
        //     runs", not "the plugin is wrong", and from the user's seat those are
        //     the same failure.
        compilerTreeChecks(repoRoot, verifyDir);

        // 11. the run path, read out of the class files rather than the sources
        runPathBytecodeChecks(entries);

        // 12. the number that has to match in four places
        versionDiscipline(jar, root);

        // 13. the direction nothing checked: class -> plugin.xml
        unregisteredImplementations(entries, refs, index, loader);

        // Deliberately *not* loader.close(): the platform's process runner leaves
        // reader threads behind, and closing the loader makes them die with
        // NoClassDefFoundError on the way out, which buries the verdict in stack
        // traces.  This is a short-lived process; the jars are closed by exit.
        return verdict(started);
    }

    // ------------------------------------------- 10/11. the tree and the bytecode

    /**
     * The user's standing rule: every plugin update bumps the version, and the number
     * is the same everywhere it appears.  `build-offline.ps1` reads it out of
     * plugin.xml and names the dist zip from it, so those two cannot drift --
     * `build.gradle.kts` and `CHANGELOG.md` were kept in step by hand, and a number
     * that lives in four places drifts in the three nobody reads.  Each line prints
     * the value it read, so a disagreement names both sides.
     */
    private void versionDiscipline(Path jar, Element root) {
        section("12. version discipline (one number, four places)");
        String version = textOf(root, "version");
        System.out.println("    plugin.xml       <version>" + version + "</version>  (read from the jar's descriptor)");
        if (version == null || version.isBlank() || "(none)".equals(version)) {
            fail("plugin.xml has no <version>: the dist name is derived from it and nothing else can agree");
            return;
        }
        Path pluginRoot = jar.toAbsolutePath().getParent().getParent().getParent().getParent();

        // build.gradle.kts.  The regex needs the `=`: the plugin id's own version
        // ("2.1.0") is a dependency version and must not be mistaken for this one.
        try {
            Path gradle = pluginRoot.resolve("build.gradle.kts");
            if (!Files.isRegularFile(gradle)) {
                notes.add("no build.gradle.kts at " + gradle + ": the Gradle path's version is unverified");
            } else {
                Matcher m = Pattern.compile("(?m)^\\s*version\\s*=\\s*\"([^\"]+)\"")
                        .matcher(Files.readString(gradle, StandardCharsets.UTF_8));
                if (!m.find()) {
                    fail("build.gradle.kts has no `version = \"...\"` line, so nothing keeps the Gradle path "
                            + "in step with plugin.xml");
                } else if (!m.group(1).equals(version)) {
                    fail("version drift [check versionDiscipline]: plugin.xml says \"" + version
                            + "\" and build.gradle.kts says \"" + m.group(1) + "\".  The rule is one number, "
                            + "bumped in every place it appears.");
                } else {
                    System.out.println("    OK  " + pad("build.gradle.kts", 34) + "version = \"" + m.group(1)
                            + "\" (same as plugin.xml)");
                }
            }
        } catch (Exception e) {
            notes.add("could not read build.gradle.kts: " + e);
        }

        // the dist file name
        try {
            Path dist = pluginRoot.resolve("dist");
            Path expected = dist.resolve("vela-idea-plugin-" + version + ".zip");
            List<String> zips = new ArrayList<>();
            if (Files.isDirectory(dist)) {
                try (var stream = Files.list(dist)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted()
                            .forEach(p -> zips.add(p.getFileName().toString()));
                }
            }
            if (Files.isRegularFile(expected)) {
                System.out.println("    OK  " + pad("dist file name", 34) + expected.getFileName() + " ("
                        + Files.size(expected) + " bytes)");
            } else {
                fail("no dist file named for this version [check versionDiscipline]: expected "
                        + expected.getFileName() + " and dist holds " + zips + ".  A zip whose name disagrees "
                        + "with <version> is one someone installs believing it is a different build.");
            }
        } catch (Exception e) {
            notes.add("could not inspect dist\\: " + e);
        }

        // the changelog entry
        try {
            Path changelog = pluginRoot.resolve("CHANGELOG.md");
            if (!Files.isRegularFile(changelog)) {
                fail("no CHANGELOG.md beside the build: version " + version + " has no entry");
            } else {
                String heading = null;
                for (String line : Files.readAllLines(changelog, StandardCharsets.UTF_8)) {
                    String t = line.strip();
                    if (t.startsWith("#") && t.contains(version)) {
                        heading = t;
                        break;
                    }
                }
                if (heading == null) {
                    fail("CHANGELOG.md has no heading for version " + version + " [check versionDiscipline]: "
                            + "the bump is recorded nowhere, which is the entry the user asked for.");
                } else {
                    System.out.println("    OK  " + pad("CHANGELOG.md", 34) + "\"" + heading + "\"");
                }
            }
        } catch (Exception e) {
            notes.add("could not read CHANGELOG.md: " + e);
        }
    }

    /**
     * The descriptor inside the jar must be the descriptor in the source tree.
     *
     * Every other check here reads the *artifact*, which is right for "what will the
     * IDE load", and wrong for "is this artifact current".  One round of work was
     * verified against a `dist\` jar whose META-INF/plugin.xml still registered 13
     * extension points while the source registered 17: a PASS that described an
     * older plugin than the one in the tree.  Comparing the two texts and naming the
     * difference costs nothing and removes that whole failure mode.
     */
    private void descriptorFreshness(Path jar, byte[] jarXml) {
        try {
            Path pluginRoot = jar.toAbsolutePath().getParent().getParent().getParent().getParent();
            Path source = pluginRoot.resolve("src").resolve("main").resolve("resources")
                    .resolve("META-INF").resolve("plugin.xml");
            if (!Files.isRegularFile(source)) {
                notes.add("no source plugin.xml at " + source + "; the artifact cannot be compared with the tree");
                return;
            }
            String jarText = new String(jarXml, StandardCharsets.UTF_8);
            String srcText = Files.readString(source, StandardCharsets.UTF_8);
            if (jarText.equals(srcText)) {
                System.out.println("    OK  " + pad("descriptor is current", 34)
                        + " the jar's META-INF/plugin.xml is byte-identical to src\\main\\resources\\META-INF\\plugin.xml");
                return;
            }
            Set<String> jarTags = extensionTagsOf(jarText);
            Set<String> srcTags = extensionTagsOf(srcText);
            Set<String> onlyInSource = new LinkedHashSet<>(srcTags);
            onlyInSource.removeAll(jarTags);
            Set<String> onlyInJar = new LinkedHashSet<>(jarTags);
            onlyInJar.removeAll(srcTags);
            fail("the artifact is STALE [check descriptorFreshness]: the jar's META-INF/plugin.xml differs from "
                    + "src\\main\\resources\\META-INF\\plugin.xml.  Extension points only in the source: "
                    + onlyInSource + "; only in the jar: " + onlyInJar + ".  Every other check below describes "
                    + "the jar, so a PASS here would be a PASS about an older plugin: rebuild before believing it.");
        } catch (Exception e) {
            notes.add("could not compare the jar's descriptor with the source: " + e);
        }
    }

    /** The distinct extension tags a descriptor registers, without parsing it. */
    private static Set<String> extensionTagsOf(String xml) {
        Set<String> tags = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?s)<extensions\\b[^>]*>(.*?)</extensions>").matcher(xml);
        while (m.find()) {
            Matcher tag = Pattern.compile("<([A-Za-z][A-Za-z0-9_.]*)[\\s/>]").matcher(m.group(1));
            while (tag.find()) {
                tags.add(tag.group(1));
            }
        }
        return tags;
    }

    /**
     * The attribute a registration uses must be the attribute the platform's own
     * declaration binds.  `<psi.referenceContributor implementationClass="..."/>`
     * reads correctly and is a no-op: the point is declared
     * `<with attribute="implementation" implements="...">`, so the class is never
     * read, no reference provider is registered, and rename and find-usages are dead
     * while Ctrl+Click (a different extension point) keeps working.
     */
    private void registrationAttributes(Element root, String namespace, PlatformIndex index) {
        for (Element exts : descendants(root, "extensions")) {
            NodeList kids = exts.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (!(kids.item(i) instanceof Element tag)) {
                    continue;
                }
                String epId = epIdOf(namespace, tag.getTagName());
                Set<String> accepted = index.acceptedAttributes(epId, tag.getTagName());
                if (accepted == null) {
                    continue;   // nothing declared for this point: the existence check has spoken
                }
                boolean bad = false;
                for (String attribute : CLASS_ATTRIBUTES) {
                    String v = attr(tag, attribute);
                    if (v == null || v.isBlank() || accepted.contains(attribute)) {
                        continue;
                    }
                    bad = true;
                    fail("plugin.xml registers <" + tag.getTagName() + " " + attribute + "=\"" + v.trim()
                            + "\"/> but the platform's declaration of " + epId + " names the class with "
                            + accepted + ", not \"" + attribute + "\" [check registrationAttributes].  The "
                            + "attribute is not bound to anything, so the class is never read: the extension "
                            + "loads, does nothing, and reports no error.");
                }
                if (!bad) {
                    System.out.println("    OK  " + pad("<" + tag.getTagName() + "> attribute", 34)
                            + attrsOf(tag) + "  (the platform binds " + accepted + ")");
                }
            }
        }
    }

    /**
     * Dead-class check: every concrete top-level class in the jar must be named by a
     * registration in plugin.xml, or named in some *other* class file's constant pool.
     * Neither is the failure this plugin has shipped three times -- a finished,
     * compiling feature class that nothing reaches, so the IDE never asks it anything
     * and nothing is reported anywhere.  Sections 1 and 8 read plugin.xml -> class;
     * nothing read class -> plugin.xml, which is why deleting a registration line was
     * invisible to every check in this file.
     *
     * The contract set exists for the *message*, not for the verdict.  It is the
     * `implements="..."` of the installed platform's own `<with>` declarations, its
     * `interface="..."` declarations, and the fallback table above; when a dead class
     * matches one of them the failure says which contract it was written to implement,
     * because "implements InlayHintsProvider and is registered nowhere" names the
     * feature that will not run.  `java.lang.Object` is a declared contract too (the
     * platform uses it for service-implementation points) and matches everything, so
     * it is dropped here: it would make every class look like an extension.
     *
     * The tolerance is a byte-level name match against every other class file, which
     * is lenient in the safe direction: it can miss a dead class that mentions itself,
     * it cannot invent a failure for a live one.  Legitimate helpers reached through a
     * registered factory stay legal this way -- `VelaStructureViewBuilder` is handed
     * back by the registered `VelaPsiStructureViewFactory`, and
     * `VelaParameterNameInlayHintsCollector` is built by the registered provider.
     *
     * "Every other class file" excludes the class's own nested classes, and that
     * exclusion is load-bearing: Kotlin's `Foo$Companion` names `Foo` back, so without
     * it every class with a `companion object` was immune to this check -- including
     * `VelaParameterNameInlayHintsProvider`, the class this check was written for.
     */
    private void unregisteredImplementations(Map<String, byte[]> entries, List<Ref> refs,
                                             PlatformIndex index, URLClassLoader loader) {
        section("13. dead classes (every concrete top-level class must be registered or reached)");
        Set<String> contracts = new LinkedHashSet<>();
        if (index != null) {
            for (Map<String, String> m : index.epWith.values()) {
                contracts.addAll(m.values());
            }
            for (String v : index.epInterface.values()) {
                contracts.add(v);
            }
        }
        for (String[] fb : FALLBACK_TYPE.values()) {
            contracts.add(fb[1]);
        }
        contracts.remove(null);
        contracts.remove("");
        contracts.remove("java.lang.Object");
        List<String> contractNames = new ArrayList<>(contracts);
        contractNames.sort(String::compareTo);
        Map<String, Class<?>> contractTypes = new LinkedHashMap<>();
        for (String contract : contractNames) {
            try {
                contractTypes.put(contract, Class.forName(contract, false, loader));
            } catch (Throwable ignored) {
                // a contract whose own type is not on this classpath cannot be asked
            }
        }
        System.out.println("    contracts : " + contractTypes.size() + " type(s) the installed platform's"
                + " extension points require (read from its own descriptors, plus the fallback table)");

        Set<String> registered = new LinkedHashSet<>();
        for (Ref r : refs) {
            registered.add(r.fqn);
        }

        List<String> order = new ArrayList<>();
        for (String name : entries.keySet()) {
            // A nested class is never a registration target; Kotlin puts the bodies of
            // lambdas and every `object`'s members in those.
            if (name.endsWith(".class") && !name.contains("$")) {
                order.add(name);
            }
        }
        order.sort(String::compareTo);

        int concrete = 0;
        int registeredCount = 0;
        int reachedCount = 0;
        List<String> rescued = new ArrayList<>();
        for (String name : order) {
            String internal = name.substring(0, name.length() - ".class".length());
            String fqn = internal.replace('/', '.');
            Class<?> c;
            try {
                c = Class.forName(fqn, false, loader);
            } catch (Throwable t) {
                notes.add("could not load " + fqn + " while looking for dead classes: " + t);
                continue;
            }
            if (c.isInterface() || c.isEnum() || Modifier.isAbstract(c.getModifiers())) {
                continue;
            }
            concrete++;
            if (registered.contains(fqn)) {
                registeredCount++;
                continue;
            }
            String user = null;
            String ownNested = internal + "$";
            for (Map.Entry<String, byte[]> other : entries.entrySet()) {
                String otherName = other.getKey();
                if (otherName.equals(name) || !otherName.endsWith(".class")) {
                    continue;
                }
                // A class's OWN nested classes are not users of it, and leaving them in
                // made this check blind to the one failure it exists for.  Kotlin emits
                // `Foo$Companion.class` for every `companion object`, and that file names
                // `Foo` back through its outer-class reference, so the rescue below found
                // "a user" that was the class itself.  Measured, not reasoned: the
                // `classRegisteredNowhere` mutant -- the inlay provider's
                // `<codeInsight.inlayProvider>` registration deleted, which is the third
                // time this plugin has shipped an inert feature -- made this section print
                // `OK dev.vela.plugin.VelaParameterNameInlayHintsProvider implements
                // InlayHintsProvider, reached from ...VelaParameterNameInlayHintsProvider$Companion.class`
                // and the whole verifier print RESULT: PASS.
                String otherInternal = otherName.substring(0, otherName.length() - ".class".length());
                if (otherInternal.equals(internal) || otherInternal.startsWith(ownNested)) {
                    continue;
                }
                if (containsAscii(other.getValue(), internal)) {
                    user = otherName;
                    break;
                }
            }
            String hit = mostSpecificContract(c, contractTypes);
            if (user != null) {
                reachedCount++;
                if (hit != null) {
                    rescued.add("    OK  " + pad(fqn, 46) + " implements " + simple(hit)
                            + ", reached from " + user + " (no registration of its own is the design)");
                }
                continue;
            }
            String what = hit == null ? "no class in this plugin" : "no registration and no class file";
            fail(fqn + (hit == null ? "" : " implements " + hit + ", a contract the installed platform's"
                            + " own extension points register,")
                    + " and " + what + " names it other than itself "
                    + "[check unregisteredImplementations].  The class compiles, it ships in the jar, and "
                    + "nothing can call it: the feature it implements never runs and the platform reports "
                    + "nothing.  This is the failure this plugin has already shipped three times -- "
                    + "VelaSemanticAnnotator, VelaReferenceContributor and "
                    + "VelaParameterNameInlayHintsProvider were each finished, correct and left "
                    + "unreferenced until a registration line was added.");
        }
        rescued.forEach(System.out::println);
        if (concrete == 0) {
            fail("the jar holds no concrete top-level class at all, so the dead-class check proved "
                    + "nothing");
            return;
        }
        System.out.println("    " + concrete + " concrete top-level class(es): " + registeredCount
                + " named by plugin.xml, " + reachedCount + " reached from another class file, "
                + (concrete - registeredCount - reachedCount) + " dead");
    }

    /**
     * The most derived of the platform contracts a class satisfies, or null when it
     * satisfies none.  `java.lang.Object` is out of the map already; this is what turns
     * "it is dead" into "it implements InlayHintsProvider and is dead".
     */
    private static String mostSpecificContract(Class<?> c, Map<String, Class<?>> contractTypes) {
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, Class<?>> e : contractTypes.entrySet()) {
            if (e.getValue().isAssignableFrom(c)) {
                matches.add(e.getKey());
            }
        }
        matches.sort(String::compareTo);
        String best = null;
        for (String m : matches) {
            if (best == null || contractTypes.get(best).isAssignableFrom(contractTypes.get(m))) {
                best = m;
            }
        }
        return best;
    }

    /**
     * Facts about the compiler tree the plugin drives, read without an IDE and
     * without a C compiler:
     *
     *   smoke     examples/hello.vel builds (exit 0), runs, and prints its two fixed
     *             lines.  Three of the last four defects in this repository were
     *             "no program runs at all" -- an emitter calling a symbol the runtime
     *             header had stopped defining, and a subtraction that panicked in
     *             compiled code only.  From the user's seat that is a plugin failure,
     *             because running this compiler is the plugin's whole job.
     *   parity    the same program, compiled and interpreted, must print the same
     *             bytes.  "Compiled disagrees with interpreted" is the signature
     *             those defects left, and nothing was comparing the two.
     *   contract  every `vela_*` symbol the emitted C *calls* must be defined in
     *             runtime/vela_runtime.h (or in the emitted C itself).  A
     *             header-only rename breaks the other end of an interface nothing
     *             was checking, and this reads both texts, so it holds before any C
     *             compiler is involved.
     */
    private void compilerTreeChecks(Path repoRoot, Path scratch) {
        section("10. the compiler the plugin drives (no C compiler, no IDE)");
        if (repoRoot == null || scratch == null) {
            System.out.println("    --  not verified: no repo root argument");
            notes.add("compiler tree checks NOT RUN: no repo root argument");
            return;
        }
        Path vm = repoRoot.resolve("selfhost").resolve("build").resolve("vm.exe");
        if (!Files.isRegularFile(vm)) {
            fail("the compiler " + vm + " does not exist: nothing the plugin is asked to run can be checked");
            return;
        }
        try {
            Files.createDirectories(scratch);
            Path helloSrc = repoRoot.resolve("examples").resolve("hello.vel");
            if (!Files.isRegularFile(helloSrc)) {
                fail("examples/hello.vel is missing: the build-and-run smoke test cannot run");
                return;
            }
            Path hello = scratch.resolve("hello.vel");
            Files.copy(helloSrc, hello, StandardCopyOption.REPLACE_EXISTING);

            // --- smoke: build it, run it, and require the program's own words
            //
            // A FAILED BUILD MUST NOT HIDE A STATIC CHECK.  This used to `return` here,
            // which aborted the rest of the section -- including `emitterHeaderContract`
            // below, which compares two texts and needs no C compiler at all.  Measured:
            // `negative-tests.ps1` case I4 renames `vela_bounds_check` in
            // runtime/vela_runtime.h, the build does fail (correctly), and the contract
            // check that exists for exactly that drift never ran; the case was reported
            // MISSED with the verifier having exited 1 for a different reason.  So the
            // build result is remembered and the checks that genuinely need a built
            // executable are skipped, while the static ones still run.
            String[] build = runVm(repoRoot, vm, scratch, "hello-build", "build", hello.toString());
            boolean smokeOk = "0".equals(build[0]);
            if (!smokeOk) {
                fail("vm.exe build examples/hello.vel exits " + build[0] + ": " + firstLine(build[2])
                        + "  [check smoke: read from the compiler's stderr; while this fails, no program in "
                        + "the repository can be run from the IDE either]");
            }
            Path exe = scratch.resolve("hello.exe");
            if (smokeOk && !Files.isRegularFile(exe)) {
                fail("vm.exe build examples/hello.vel exited 0 but wrote no " + exe.getFileName()
                        + " beside the source");
                smokeOk = false;
            }
            String[] run = new String[]{"-1", ""};
            boolean said = false;
            if (smokeOk) {
                run = runVm(repoRoot, exe, scratch, "hello-run");
                said = run[1].contains("hello from Vela") && run[1].contains("sum 0..99 = 4950");
                if (!"0".equals(run[0]) || !said) {
                    fail("the compiled examples/hello.vel exits " + run[0] + " and printed " + quote(run[1])
                            + "; expected exit 0 and the lines \"hello from Vela\" and \"sum 0..99 = 4950\" "
                            + "[check smoke]");
                } else {
                    System.out.println("    OK  " + pad("build+run examples/hello.vel", 46)
                            + " exit 0, ran, printed both fixed lines");
                }
            } else {
                System.out.println("    --  " + pad("build+run examples/hello.vel", 46)
                        + "SKIPPED: the build failed, so there is no executable to run"
                        + " (the static emitter/header contract below still runs)");
            }

            // --- parity: compiled output == interpreted output, byte for byte
            if (smokeOk) {
                String[] interp = runVm(repoRoot, vm, scratch, "hello-interpreted", "run", hello.toString());
                if (!run[1].equals(interp[1])) {
                    fail("compiled and interpreted examples/hello.vel disagree [check parity]: compiled "
                            + quote(run[1]) + " interpreted " + quote(interp[1]));
                } else {
                    System.out.println("    OK  " + pad("hello.vel compiled == interpreted", 46)
                            + " same " + interp[1].length() + " byte(s) of stdout");
                }
            }

            Path parity = scratch.resolve("parity.vel");
            Files.writeString(parity, PARITY_PROGRAM, StandardCharsets.UTF_8);
            String[] pBuild = runVm(repoRoot, vm, scratch, "parity-build", "build", parity.toString());
            if (!"0".equals(pBuild[0])) {
                fail("the parity program does not build: " + firstLine(pBuild[2])
                        + "  [check parity: the fixture is subtraction, indexing and a += loop]");
            } else {
                String[] pRun = runVm(repoRoot, scratch.resolve("parity.exe"), scratch, "parity-run");
                String[] pInterp = runVm(repoRoot, vm, scratch, "parity-interpreted", "run", parity.toString());
                if (!pRun[1].equals(pInterp[1])) {
                    fail("arithmetic parity [check parity]: compiled " + quote(pRun[1]) + " vs interpreted "
                            + quote(pInterp[1]) + " -- 1-5, 0-9, xs[0]-xs[2] and a += loop");
                } else {
                    System.out.println("    OK  " + pad("arithmetic parity (4 cases)", 46)
                            + "compiled == interpreted: " + quote(pInterp[1]));
                }
            }

            // --- contract: every vela_* the emitted C calls must be defined
            //
            // Static, and therefore last and unconditional: it reads the emitted C and
            // the runtime header as text, so it is the one check in this section that is
            // still answerable when the toolchain is broken -- which is when it matters.
            emitterHeaderContract(repoRoot, vm, scratch, hello, "hello");
            emitterHeaderContract(repoRoot, vm, scratch, parity, "parity");
        } catch (Exception e) {
            fail("compiler tree probe failed: " + e);
        }
    }

    /**
     * The emitter calls a symbol; the runtime header must define it.  Read from the
     * two texts, so it holds with no C compiler present and before anything links.
     */
    private void emitterHeaderContract(Path repoRoot, Path vm, Path scratch, Path source, String label) {
        Path header = repoRoot.resolve("runtime").resolve("vela_runtime.h");
        if (!Files.isRegularFile(header)) {
            fail("runtime/vela_runtime.h is missing: the emitter/header contract cannot be checked");
            return;
        }
        try {
            String[] emit = runVm(repoRoot, vm, scratch, label + "-emit", "emit-c", source.toString());
            Path cFile = scratch.resolve(label + ".c");
            String c;
            String readFrom;
            if (Files.isRegularFile(cFile)) {
                c = Files.readString(cFile, StandardCharsets.UTF_8);
                readFrom = cFile.getFileName().toString();
            } else if (!emit[1].isBlank()) {
                c = emit[1];                       // emit-c may print to stdout instead
                readFrom = "the output of vm.exe emit-c " + source.getFileName();
            } else {
                fail("vm.exe emit-c " + source.getFileName() + " produced no C (exit " + emit[0] + ": "
                        + firstLine(emit[2]) + ")");
                return;
            }
            String h = Files.readString(header, StandardCharsets.UTF_8);

            Set<String> called = new LinkedHashSet<>();
            Matcher m = Pattern.compile("\\b(vela_[A-Za-z0-9_]+)\\s*\\(").matcher(c);
            while (m.find()) {
                called.add(m.group(1));
            }
            List<String> fromHeader = new ArrayList<>();
            List<String> fromEmitted = new ArrayList<>();
            Set<String> missing = new LinkedHashSet<>();
            for (String sym : called) {
                if (declaredInHeader(h, sym)) {
                    fromHeader.add(sym);
                } else if (definedIn(c, sym)) {
                    fromEmitted.add(sym);
                } else {
                    missing.add(sym);
                }
            }
            if (called.isEmpty()) {
                notes.add("the emitted C for " + source.getFileName() + " calls no vela_* symbol at all; "
                        + "that is suspicious for " + readFrom);
            }
            if (missing.isEmpty()) {
                System.out.println("    OK  " + pad(readFrom + " -> runtime/vela_runtime.h", 46)
                        + called.size() + " vela_* symbol(s) called, all defined (" + fromHeader.size()
                        + " in the header, " + fromEmitted.size() + " in the emitted C)");
                System.out.println("        " + String.join(" ", fromHeader));
            } else {
                fail("the emitted C for " + source.getFileName() + " calls " + missing
                        + ", which runtime/vela_runtime.h does not define [check emitterHeaderContract: read "
                        + "from " + readFrom + " and " + header.getFileName() + "].  This is the LNK2019 "
                        + "waiting to happen, and it needs no C compiler to see.");
            }
        } catch (Exception e) {
            fail("could not read the emitter/header pair for " + label + ": " + e);
        }
    }

    /** Is `sym` defined -- not merely called -- in this C text? */
    private static boolean definedIn(String c, String sym) {
        return Pattern.compile("(?m)^[^\\n;]*\\b" + Pattern.quote(sym) + "\\s*\\([^;\\n]*\\)\\s*\\{")
                .matcher(c).find();
    }

    /**
     * Does the runtime header declare *this* symbol, with the whole identifier spelled
     * out?
     *
     * THIS WAS `h.contains(sym)`, AND THAT IS A DIFFERENT AND WEAKER QUESTION.  A
     * substring test answers "does this text contain these characters anywhere", so
     * renaming a symbol by *appending* to it -- `vela_bounds_check` becoming
     * `vela_bounds_check_renamed` in runtime/vela_runtime.h -- still contains the name
     * the emitter calls, and the check reported
     * `vela_bounds_check ... all defined`.  The rename is exactly the drift this check
     * exists for (the emitter's call and the header's declaration drifting apart), and
     * the verifier called it fine.
     *
     * Found by `negative-tests.ps1` case I4, not by reading: the case renames the
     * symbol and requires `[check emitterHeaderContract]` in the output; with the
     * substring test it reported MISSED while the verifier exited 0 for that check.
     * The fix is a whole-word match, so a prefix or a suffix is not a declaration.
     */
    private static boolean declaredInHeader(String header, String sym) {
        return Pattern.compile("\\b" + Pattern.quote(sym) + "\\b").matcher(header).find();
    }

    /**
     * Three structural facts about the run path, read out of the built class files
     * rather than the sources, so a refactor that reintroduces the same behaviour by
     * another route is still caught:
     *
     *   no private console  nothing in the jar may mention TextConsoleBuilderFactory.
     *                       The console the user sees is the one the platform makes
     *                       for the handler the run configuration returns; a second
     *                       console built here and never shown is exactly why "the
     *                       IDEA console shows nothing when I run".
     *   pushes its output  some *ProcessHandler class must mention notifyTextAvailable,
     *                       or the child's output never reaches any console at all.
     *   runtime directory  the build command must resolve the runtime directory
     *                       instead of assuming the process cwd: from the user's own
     *                       project (vela\tests) `vm.exe build <abs path>` exits 2 with
     *                       C1083 "cannot open include file: 'vela_runtime.h'", which
     *                       is why every run they attempted failed.
     */
    private void runPathBytecodeChecks(Map<String, byte[]> entries) {
        section("11. the run path, read from the class files");

        List<String> consoles = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().endsWith(".class") && containsAscii(e.getValue(), "TextConsoleBuilderFactory")) {
                consoles.add(e.getKey());
            }
        }
        if (consoles.isEmpty()) {
            System.out.println("    OK  " + pad("no private console", 46)
                    + "no class mentions TextConsoleBuilderFactory (read: every class's constant pool)");
        } else {
            fail("these classes mention TextConsoleBuilderFactory [check runPathBytecodeChecks]: " + consoles
                    + ".  A console built there is not the one the Run toolwindow shows, so the visible console "
                    + "stays blank even when the program prints.");
        }

        List<String> handlers = new ArrayList<>();
        List<String> pushing = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (!name.endsWith(".class") || name.contains("$")) {
                continue;
            }
            String simple = name.substring(name.lastIndexOf('/') + 1);
            if (!simple.contains("ProcessHandler")) {
                continue;
            }
            handlers.add(name);
            if (containsAscii(e.getValue(), "notifyTextAvailable")) {
                pushing.add(name);
            }
        }
        if (handlers.isEmpty()) {
            notes.add("no *ProcessHandler class in the jar; if the run path uses no such handler, that is a "
                    + "different design and this check cannot speak to it");
        } else if (pushing.isEmpty()) {
            fail("these classes are process handlers, and none mentions notifyTextAvailable "
                    + "[check runPathBytecodeChecks]: " + handlers + ".  The child prints and no console is ever "
                    + "told, which is the blank console the user reported.");
        } else {
            System.out.println("    OK  " + pad("pushes its output", 46) + pushing
                    + " mentions notifyTextAvailable (read: constant pool)");
        }

        List<String> markers = List.of("runtimeDir", "velaRoot", "vela_runtime.h", "selfhost");
        String found = null;
        String where = null;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (!e.getKey().endsWith(".class")) {
                continue;
            }
            for (String marker : markers) {
                if (containsAscii(e.getValue(), marker)) {
                    found = marker;
                    where = e.getKey();
                    break;
                }
            }
            if (found != null) {
                break;
            }
        }
        if (found == null) {
            fail("no class mentions any of " + markers + " [check runPathBytecodeChecks]: the build command "
                    + "looks like it assumes a working directory instead of resolving the compiler's runtime "
                    + "directory.  Measured: `vm.exe build <abs path>` from vela\\tests exits 2 with C1083 "
                    + "'cannot open include file: vela_runtime.h', and that is the project the user opens.");
        } else {
            System.out.println("    OK  " + pad("runtime directory in the command", 46) + where + " mentions \""
                    + found + "\" (read: constant pool; the enclosing method is not extracted)");
        }
    }

    /** True when the exact byte sequence appears in a class file (constant pool). */
    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + n.length <= haystack.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Runs a program with the repository root as the working directory and returns
     * {exit code, stdout, stderr}, captured to files so nothing is lost to a pipe.
     * The cwd matters: the whole point of one of these checks is that the compiler's
     * behaviour depends on it.
     */
    private static String[] runVm(Path workDir, Path program, Path captureDir, String name, String... args)
            throws Exception {
        Path out = captureDir.resolve(name + ".out");
        Path err = captureDir.resolve(name + ".err");
        List<String> cmd = new ArrayList<>();
        cmd.add(program.toString());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.redirectOutput(out.toFile());
        pb.redirectError(err.toFile());
        Process p = pb.start();
        int code = p.waitFor();
        String stdout = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        String stderr = new String(Files.readAllBytes(err), StandardCharsets.UTF_8);
        return new String[]{String.valueOf(code), stdout, stderr};
    }

    private static String firstLine(String text) {
        String t = text.strip();
        if (t.isEmpty()) {
            return "(no output)";
        }
        int nl = t.indexOf('\n');
        return nl < 0 ? t : t.substring(0, nl).strip();
    }

    private static String quote(String text) {
        String t = text.strip();
        if (t.length() > 300) {
            t = t.substring(0, 300) + "...";
        }
        return "\"" + t.replace("\n", "\\n") + "\"";
    }


    /**
     * The path the user actually sees: a real .vel file on disk  ->  `vm.exe check`
     *  ->  its stderr  ->  the plugin's reader  ->  "problem on line N".
     *
     * Three files, because each answers a different question and none of them is
     * answerable from a synthetic string:
     *
     *   clean.vel     a real tests/build/*.vel that the compiler accepts: the
     *                 plugin must produce *no* problems.  This is the half that
     *                 catches a reader that invents diagnostics.
     *   refused.vel   a real tests/build/check_cases/*.vel that the compiler
     *                 refuses: exactly one problem, at the line the compiler
     *                 named, with the message the compiler wrote.
     *   shifted.vel   the same refusals with four comment lines prepended: the
     *                 problem must move to line 7.  Without this, a reader that
     *                 always returned line 3 would pass.
     *
     * The compiler is copied first: another team rebuilds selfhost\build\vm.exe,
     * and a build that reads a binary while it is being replaced is a flaky test.
     *
     * THE COPY HAS TO CARRY THE COMPILER'S DLLs TOO, and this is not defensive
     * coding: since the LLVM shim became the compiler's ninth part, `vm.exe` imports
     * `LLVM-C.dll` from the directory it sits in.  A bare copy of `vm.exe` into
     * build\verify\ is therefore a copy that cannot start at all, and it failed as
     * `exit -1073741515` (0xC0000135, STATUS_DLL_NOT_FOUND) with no stderr -- which
     * this check reported as "expected exactly 1 problem from truthiness.vel, got 0",
     * a message that names the wrong thing entirely.  So the copy is
     * `vm.exe` plus every `*.dll` beside it in `selfhost\build`, and a file that is
     * already there with the same size and modified time is left alone (that
     * `LLVM-C.dll` is 74 MB, and the mutation test runs this check a dozen times).
     */
    private void diagnosticsEndToEnd(Path jar, Path repoRoot, Class<?> compilerCls, Object compiler,
                                     Class<?> problemCls) {
        if (repoRoot == null) {
            notes.add("end-to-end diagnostics check NOT RUN: no repo root was passed "
                    + "(call: VerifyPlugin <jar> <classpath> <repo-root>)");
            System.out.println("    --  end-to-end diagnostics path: NOT VERIFIED (no repo root argument)");
            return;
        }
        Path vm = repoRoot.resolve("selfhost").resolve("build").resolve("vm.exe");
        if (!Files.isRegularFile(vm)) {
            fail("the compiler " + vm + " does not exist: the whole diagnostics path is unverified");
            return;
        }
        if (compilerCls == null || compiler == null || problemCls == null) {
            fail("VelaCompiler could not be loaded, so the end-to-end diagnostics path cannot be checked");
            return;
        }
        try {
            Path pluginRoot = jar.toAbsolutePath().getParent().getParent().getParent().getParent();
            Path scratch = pluginRoot.resolve("build").resolve("verify");
            Files.createDirectories(scratch);
            Path vmCopy = scratch.resolve("vm.exe");
            copyForRun(vm, vmCopy);
            // The compiler's own directory is the DLL search path, so every library it
            // sits beside has to come along; `LLVM-C.dll` is the one that exists today.
            int dlls = 0;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(vm.getParent(), "*.dll")) {
                for (Path lib : stream) {
                    copyForRun(lib, scratch.resolve(lib.getFileName().toString()));
                    dlls++;
                }
            }
            System.out.println("    compiler  : " + vm + " -> copied to " + vmCopy
                    + (dlls > 0 ? " with " + dlls + " sibling .dll file(s), because the compiler"
                            + " imports LLVM-C.dll from its own directory" : ""));

            Path cleanSrc = repoRoot.resolve("tests").resolve("build").resolve("arith_basics.vel");
            if (!Files.isRegularFile(cleanSrc)) {
                cleanSrc = firstVel(repoRoot.resolve("tests").resolve("build"));
            }
            Path refusedSrc = repoRoot.resolve("tests").resolve("build").resolve("check_cases").resolve("truthiness.vel");
            if (cleanSrc == null || !Files.isRegularFile(cleanSrc) || !Files.isRegularFile(refusedSrc)) {
                fail("no usable fixtures: looked for tests/build/arith_basics.vel, any tests/build/*.vel and "
                        + "tests/build/check_cases/truthiness.vel under " + repoRoot);
                return;
            }

            Path clean = scratch.resolve("clean.vel");
            Path refused = scratch.resolve("refused.vel");
            Path shifted = scratch.resolve("shifted.vel");
            Files.copy(cleanSrc, clean, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(refusedSrc, refused, StandardCopyOption.REPLACE_EXISTING);
            List<String> lines = Files.readAllLines(refusedSrc, StandardCharsets.UTF_8);
            List<String> padded = new ArrayList<>(List.of("# pad 1", "# pad 2", "# pad 3", "# pad 4"));
            padded.addAll(lines);
            Files.write(shifted, padded, StandardCharsets.UTF_8);


            System.out.println("    fixtures  : " + cleanSrc.getFileName() + " (accepted), "
                    + refusedSrc.getFileName() + " (refused), +4 lines (shifted)");

            String cleanOut = runCompiler(vmCopy, clean);
            List<String> cleanProblems = problems(compilerCls, compiler, problemCls, cleanOut);
            if (!cleanProblems.isEmpty()) {
                fail("the compiler accepts " + cleanSrc.getFileName() + " but the plugin's reader produced "
                        + cleanProblems + "; the editor would show errors on a legal program");
            } else {
                System.out.println("    OK  " + pad(cleanSrc.getFileName() + " (accepted)", 46)
                        + " -> 0 problem(s), which is what the editor would draw");
            }

            List<String> refusedProblems = problems(compilerCls, compiler, problemCls, runCompiler(vmCopy, refused));
            System.out.println("        " + refusedProblems);
            if (refusedProblems.size() != 1) {
                fail("expected exactly 1 problem from " + refusedSrc.getFileName() + ", got "
                        + refusedProblems.size() + ": " + refusedProblems);
            } else if (!refusedProblems.get(0).startsWith("type error@3 ")) {
                fail("expected the refusal to read \"type error@3 ...\" for " + refusedSrc.getFileName()
                        + ", got " + refusedProblems.get(0));
            } else if (!rawStderrContains(vmCopy, refused, "vela: type error: ")) {
                fail("the parsed kind and message do not appear verbatim in the compiler's stderr");
            } else {
                System.out.println("    OK  " + pad(refusedSrc.getFileName() + " (refused)", 46)
                        + " -> 1 problem on line 3, kind \"type error\"");
            }

            List<String> shiftedProblems = problems(compilerCls, compiler, problemCls, runCompiler(vmCopy, shifted));
            if (shiftedProblems.size() != 1) {
                fail("shifted fixture: expected exactly 1 problem, got " + shiftedProblems.size()
                        + ": " + shiftedProblems);
            } else if (!shiftedProblems.get(0).startsWith("type error@7 ")) {
                fail("shifted fixture: the refusal must move to line 7, got " + shiftedProblems.get(0)
                        + "  --  the reader is not following the compiler's line number");
            } else {
                int lineCount = padded.size();
                if (7 > lineCount) {
                    fail("shifted fixture has " + lineCount + " lines but the problem claims line 7");
                }
                System.out.println("    OK  " + pad("same refusal, 4 lines lower", 46)
                        + " -> problem moves to line 7 (line numbers are read, not guessed)");
            }

            // The production call the annotator makes, for the record: it needs the
            // platform's process runner, which may or may not come up headlessly.
            try {
                Method run = compilerCls.getMethod("run", String.class, String.class, java.io.File.class);
                Object pair = run.invoke(compiler, vmCopy.toString(), "check", refused.toFile());
                Object exit = pair.getClass().getMethod("getFirst").invoke(pair);
                Object text = pair.getClass().getMethod("getSecond").invoke(pair);
                List<String> viaPlugin = problems(compilerCls, compiler, problemCls, (String) text);
                if (viaPlugin.equals(refusedProblems)) {
                    System.out.println("    OK  " + pad("VelaCompiler.run+parse", 46)
                            + " -> exit " + exit + ", same 1 problem (the annotator's own call path)");
                } else {
                    fail("VelaCompiler.run produced " + viaPlugin + " where the raw stderr gave "
                            + refusedProblems + "  --  the annotator's call path disagrees with the compiler");
                }
            } catch (Throwable t) {
                notes.add("VelaCompiler.run could not be exercised headlessly (" + rootCause(t) + "); the "
                        + "end-to-end check above used the compiler directly instead.  The plugin's parse "
                        + "of the real stderr is still verified.");
                System.out.println("    --  VelaCompiler.run: not exercised headlessly (" + rootCause(t) + ")");
            }
        } catch (Throwable t) {
            fail("end-to-end diagnostics probe failed: " + t);
        }
    }

    private static Path firstVel(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path[] found = new Path[1];
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".vel")).sorted().findFirst()
                    .ifPresent(p -> found[0] = p);
        }
        return found[0];
    }

    /**
     * Copy a file unless the destination is already the same size and age.
     *
     * The compiler's directory holds `LLVM-C.dll`, 74 MB, and the mutation test runs
     * the verifier a dozen times: re-copying it every time would make the verifier
     * slower than the thing it verifies, for a file that never changes between
     * builds.  Size and modified time are what `Copy-Item` preserves and what a
     * rebuild changes, so they are enough to tell "the same file" from "a new one".
     */
    private static void copyForRun(Path from, Path to) throws IOException {
        if (Files.isRegularFile(to)) {
            try {
                if (Files.size(to) == Files.size(from)
                        && Files.getLastModifiedTime(to).equals(Files.getLastModifiedTime(from))) {
                    return;
                }
            } catch (IOException ignored) {
                // cannot compare it: copy it
            }
        }
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
    /** Runs `vm.exe check <file>` byte-exactly: streams to files, then decoded as UTF-8. */
    private static String runCompiler(Path vm, Path file) throws Exception {
        Path out = file.resolveSibling(file.getFileName() + ".out");
        Path err = file.resolveSibling(file.getFileName() + ".err");
        ProcessBuilder pb = new ProcessBuilder(vm.toString(), "check", file.toString());
        pb.directory(file.getParent().toFile());
        pb.redirectOutput(out.toFile());
        pb.redirectError(err.toFile());
        Process p = pb.start();
        int code = p.waitFor();
        String stderr = new String(Files.readAllBytes(err), StandardCharsets.UTF_8);
        String stdout = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        System.out.println("        vm.exe check " + file.getFileName() + " -> exit " + code
                + ", stderr " + stderr.strip().replace('\n', '|').length() + " chars");
        // VelaCompiler.run returns stderr + stdout, and that is what the annotator reads.
        return stderr + stdout;
    }

    private static boolean rawStderrContains(Path vm, Path file, String needle) throws Exception {
        Path err = file.resolveSibling(file.getFileName() + ".err");
        if (!Files.isRegularFile(err)) {
            // keep an independent re-run: the .err file belongs to the last run of this file
            runCompiler(vm, file);
        }
        return new String(Files.readAllBytes(err), StandardCharsets.UTF_8).contains(needle);
    }

    @SuppressWarnings("unchecked")
    private static List<String> problems(Class<?> compilerCls, Object compiler, Class<?> problemCls, String text)
            throws Exception {
        Object list = compilerCls.getMethod("parse", String.class).invoke(compiler, text);
        Method getKind = problemCls.getMethod("getKind");
        Method getLine = problemCls.getMethod("getLine");
        Method getMessage = problemCls.getMethod("getMessage");
        List<String> seen = new ArrayList<>();
        for (Object p : (List<Object>) list) {
            seen.add(getKind.invoke(p) + "@" + getLine.invoke(p) + " \"" + getMessage.invoke(p) + "\"");
        }
        return seen;
    }

    private static String rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : ": " + c.getMessage());
    }

    // ------------------------------------------------- platform descriptor index

    /**
     * What the installed IDE says about itself, read from its descriptors.
     *
     * The IDE keeps plugin/module ids, extension points and action ids in XML that
     * ships with it: `<ide>\lib\*.jar` (META-INF/*.xml, and the action files the
     * platform's own descriptor includes), `<ide>\modules\module-descriptors.jar`
     * (one `<plugin id=...>` per module) and `<ide>\plugins\**\*.jar`.  Indexing
     * them is the closest thing to ActionManager.getAction() that does not need a
     * booted application, and it answers the same question: does this id exist?
     */
    private static final class PlatformIndex {
        Path ideHome;
        int descriptorCount;
        int scannedJars;
        long millis;
        /** plugin ids and module ids a <depends> may name. */
        final Set<String> providedIds = new HashSet<>();
        /** fully qualified extension point id -> where it is declared. */
        final Map<String, String> epDeclared = new HashMap<>();
        /** extension point local name -> where it is declared (declarations without an id). */
        final Map<String, String> epBareName = new HashMap<>();
        /** extension point id or local name -> where an extension uses it. */
        final Map<String, String> epUsed = new HashMap<>();
        /** extension point id or local name -> attribute -> the type it must implement. */
        final Map<String, Map<String, String>> epWith = new HashMap<>();
        /** extension point id or local name -> the interface its implementation must implement. */
        final Map<String, String> epInterface = new HashMap<>();
        /** action id -> "group" or "action", and where it is defined. */
        final Map<String, String> actionKind = new HashMap<>();
        final Map<String, String> actionWhere = new HashMap<>();

        String[] locateEp(String id, String localName) {
            if (epDeclared.containsKey(id)) {
                return new String[]{"declared by the installed platform at " + epDeclared.get(id)};
            }
            if (epDeclared.containsKey(localName)) {
                return new String[]{"declared by the installed platform at " + epDeclared.get(localName)};
            }
            if (epBareName.containsKey(localName)) {
                return new String[]{"declared by the installed platform at " + epBareName.get(localName)};
            }
            if (epUsed.containsKey(localName)) {
                return new String[]{"used by an installed plugin at " + epUsed.get(localName)};
            }
            return null;
        }

        String[] locateAction(String id) {
            String kind = actionKind.get(id);
            return kind == null ? null : new String[]{kind, actionWhere.get(id)};
        }

        /** For EPs declared with interface="X", the implementation attribute must be an X. */
        String interfaceType(String id, String localName) {
            String v = epInterface.get(id);
            return v != null ? v : epInterface.get(localName);
        }

        /**
         * The attributes a registration may use to name its class, taken from the
         * platform's own declaration.  `<with attribute="implementation" .../>` binds
         * exactly one spelling; an unbound attribute is silently ignored by the
         * platform, which is how a registration that looks right does nothing.  Null
         * means the index knows nothing about this point -- then the existence check
         * has already spoken, and guessing here would invent failures.
         */
        Set<String> acceptedAttributes(String id, String localName) {
            Map<String, String> m = epWith.get(id);
            if (m == null) {
                m = epWith.get(localName);
            }
            if (m != null && !m.isEmpty()) {
                return new LinkedHashSet<>(m.keySet());
            }
            if (interfaceType(id, localName) != null) {
                // interface="T" points are registered with `implementation` only.  The
                // platform binds that one spelling; a descriptor that says
                // implementationClass instead is ignored, and the extension is a no-op
                // that looks perfectly healthy.  Deliberately *not* accepting the other
                // spelling as an alias here: that alias is how this verifier once passed
                // a `<psi.referenceContributor implementationClass="..."/>` whose
                // registerReferenceProviders therefore never ran, which silently killed
                // rename and find-usages.
                Set<String> only = new LinkedHashSet<>();
                only.add("implementation");
                return only;
            }
            return null;
        }

        String[] implementedType(String id, String localName) {
            Map<String, String> m = epWith.get(id);
            if (m == null) {
                m = epWith.get(localName);
            }
            if (m == null) {
                return null;
            }
            for (Map.Entry<String, String> e : m.entrySet()) {
                return new String[]{e.getKey(), e.getValue()};
            }
            return null;
        }
    }

    private static final Pattern EP_TAG = Pattern.compile("(?s)<extensionPoint\\b[^>]*>");
    private static final Pattern WITH_TAG = Pattern.compile("(?s)<with\\b[^>]*>");
    private static final Pattern GROUP_TAG = Pattern.compile("(?s)<group\\b[^>]*>");
    private static final Pattern ACTION_TAG = Pattern.compile("(?s)<action\\b[^>]*>");
    private static final Pattern ANY_TAG = Pattern.compile("<([A-Za-z][A-Za-z0-9_.]*)[\\s/>]");
    private static final Pattern ID_ELEMENT = Pattern.compile("<id>\\s*([A-Za-z0-9_.\\-]+)\\s*</id>");
    private static final Pattern PLUGIN_ID_ATTR = Pattern.compile("<plugin\\s+id=\"([A-Za-z0-9_.\\-]+)\"");
    private static final Pattern MODULE_VALUE = Pattern.compile("<module\\b[^>]*\\bvalue=\"([A-Za-z0-9_.\\-]+)\"");

    private PlatformIndex buildPlatformIndex(List<Path> classpath, URLClassLoader loader) {
        Path ideHome = null;
        for (Path p : classpath) {
            Path lib = p.getParent();
            if (lib != null && "lib".equals(lib.getFileName() == null ? null : lib.getFileName().toString())
                    && lib.getParent() != null && Files.isRegularFile(lib.getParent().resolve("build.txt"))) {
                ideHome = lib.getParent();
                break;
            }
        }
        if (ideHome == null) {
            String prop = System.getProperty("idea.home.path");
            if (prop != null && Files.isDirectory(Path.of(prop))) {
                ideHome = Path.of(prop);
            }
        }
        if (ideHome == null) {
            return null;
        }

        PlatformIndex ix = new PlatformIndex();
        ix.ideHome = ideHome;
        long start = System.currentTimeMillis();
        try {
            // 1. the platform's own jars: every XML entry, which includes both the
            //    extension point declarations and the action files they include.
            try (var stream = Files.list(ideHome.resolve("lib"))) {
                for (Path p : stream.sorted().toList()) {
                    if (p.getFileName().toString().endsWith(".jar")) {
                        scanJar(p, 3_000_000, ix);
                        ix.scannedJars++;
                    }
                }
            }
            // 2. the module registry: which plugin/module ids this IDE has at all.
            Path modules = ideHome.resolve("modules").resolve("module-descriptors.jar");
            if (Files.isRegularFile(modules)) {
                scanJar(modules, 3_000_000, ix);
                ix.scannedJars++;
            }
            // 3. the installed plugins: their ids, their own extension points and
            //    the extension points they use (evidence that an EP is real).
            Path plugins = ideHome.resolve("plugins");
            if (Files.isDirectory(plugins)) {
                List<Path> jars = new ArrayList<>();
                Files.walkFileTree(plugins, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.getFileName().toString().endsWith(".jar")) {
                            jars.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
                for (Path p : jars) {
                    scanJar(p, 1_500_000, ix);
                    ix.scannedJars++;
                }
            }
            // 4. any plugin installed in the user's config directory (none here, but
            //    a plugin may depend on one).
            Path userPlugins = Path.of(System.getProperty("user.home", "."), "AppData", "Roaming",
                    "JetBrains");
            if (Files.isDirectory(userPlugins)) {
                for (Path p : Files.walk(userPlugins, 6).filter(f -> f.getFileName().toString().endsWith(".jar"))
                        .toList()) {
                    scanJar(p, 1_500_000, ix);
                    ix.scannedJars++;
                }
            }
        } catch (Exception e) {
            notes.add("platform descriptor scan hit " + e + " after " + ix.descriptorCount + " descriptor(s)");
        }
        ix.millis = System.currentTimeMillis() - start;
        return ix;
    }

    private void scanJar(Path jar, long xmlLimit, PlatformIndex ix) {
        try (ZipFile zip = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".xml") || e.getSize() > xmlLimit
                        || e.getSize() <= 0) {
                    continue;
                }
                String text;
                try (InputStream in = zip.getInputStream(e)) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                boolean descriptor = text.contains("<idea-plugin") || text.contains("<plugin ")
                        || text.contains("<extensionPoint") || text.contains("<idea-plugin>");
                if (!descriptor) {
                    continue;
                }
                ix.descriptorCount++;
                String where = jar.getFileName() + "!" + e.getName();
                ingest(text, where, ix);
            }
        } catch (Exception ignored) {
            // an unreadable jar is not a verification failure: the ids it would have
            // contributed are additional evidence, never the only copy of it
        }
    }

    private void ingest(String text, String where, PlatformIndex ix) {
        Matcher id = ID_ELEMENT.matcher(text);
        if (id.find() && (text.contains("<idea-plugin") || text.contains("<plugin "))) {
            ix.providedIds.add(id.group(1).trim());
        }
        Matcher pid = PLUGIN_ID_ATTR.matcher(text);
        while (pid.find()) {
            ix.providedIds.add(pid.group(1));
        }
        Matcher mod = MODULE_VALUE.matcher(text);
        while (mod.find()) {
            ix.providedIds.add(mod.group(1));
        }

        Matcher ep = EP_TAG.matcher(text);
        while (ep.find()) {
            String tag = ep.group();
            String qualified = attributeOf(tag, "qualifiedName");
            String name = attributeOf(tag, "name");
            String iface = attributeOf(tag, "interface");
            if (qualified != null) {
                ix.epDeclared.putIfAbsent(qualified, where);
                if (iface != null) {
                    ix.epInterface.putIfAbsent(qualified, iface);
                }
            }
            if (name != null) {
                ix.epBareName.putIfAbsent(name, where);
                if (iface != null) {
                    ix.epInterface.putIfAbsent(name, iface);
                }
            }
            // The <with attribute="..." implements="..."> children *inside this
            // declaration's own body* state what the implementation must be -- the
            // platform's own type requirement.
            //
            // "Inside this declaration's own body" is meant literally: the body ends
            // at the first of `</extensionPoint>` and the next `<extensionPoint`, and
            // a self-closing `<extensionPoint .../>` has no body at all.  An earlier
            // version scanned a fixed 4000-character window and stopped after 400,
            // which swept the *following* declarations' <with> elements into this one:
            // `configurationType` (declared with interface= and no <with> of its own)
            // was credited with the next point's attribute, and eight registrations
            // that the platform's own bundled plugins spell exactly this way were
            // reported as unbound.
            if (!ep.group().trim().endsWith("/>")) {
                int bodyEnd = text.indexOf("</extensionPoint>", ep.end());
                int nextEp = text.indexOf("<extensionPoint", ep.end());
                int limit = bodyEnd < 0 ? text.length() : bodyEnd;
                if (nextEp >= 0 && nextEp < limit) {
                    limit = nextEp;
                }
                Matcher w = WITH_TAG.matcher(text.substring(ep.end(), limit));
                while (w.find()) {
                    String a = attributeOf(w.group(), "attribute");
                    String impl = attributeOf(w.group(), "implements");
                    if (a != null && impl != null) {
                        for (String key : new String[]{qualified, name}) {
                            if (key != null) {
                                ix.epWith.computeIfAbsent(key, k -> new LinkedHashMap<>()).putIfAbsent(a, impl);
                            }
                        }
                    }
                }
            }
        }

        for (Matcher m = ANY_TAG.matcher(text); m.find(); ) {
            String tag = m.group(1);
            if (tag.indexOf('.') > 0 && !tag.startsWith("xi:") && !tag.startsWith("com.intellij.modules")) {
                // dotted extension tags are how a plugin names a qualified extension
                // point: `<lang.psiStructureViewFactory .../>` (namespace com.intellij)
                ix.epUsed.putIfAbsent(tag, where);
            }
        }

        for (Matcher m = GROUP_TAG.matcher(text); m.find(); ) {
            String gid = attributeOf(m.group(), "id");
            if (gid != null) {
                // A group declaration wins outright: an id that is a group anywhere is a
                // group, whatever an `<action id="...">` of the same name says elsewhere.
                // The *site* recorded is the most specific one: the merged descriptors
                // (IntelliJ's own "customization" bundle contains every id in the product)
                // say that an id exists, but not where it belongs.
                String prev = ix.actionWhere.get(gid);
                if (!"group".equals(ix.actionKind.get(gid)) || isPreferred(where, prev)) {
                    ix.actionKind.put(gid, "group");
                    ix.actionWhere.put(gid, where);
                }
            }
        }
        for (Matcher m = ACTION_TAG.matcher(text); m.find(); ) {
            String aid = attributeOf(m.group(), "id");
            if (aid != null) {
                ix.actionKind.putIfAbsent(aid, "action");
                ix.actionWhere.putIfAbsent(aid, where);
            }
        }
    }

    /** True when `site` is a more specific place to cite than `prev` (or there is none). */
    private static boolean isPreferred(String site, String prev) {
        if (prev == null) {
            return true;
        }
        return specificity(site) > specificity(prev);
    }

    private static int specificity(String site) {
        if (site == null) {
            return -1;
        }
        if (site.contains("customization")) {
            return 0;   // the merged bundle: every id in the product appears here
        }
        if (site.contains("frontend-split") || site.contains("JetBrainsClientPlugin")) {
            return 1;   // a client-side mirror of the platform descriptors
        }
        return 2;       // the platform's own lib jar or plugin descriptor
    }

    private static String attributeOf(String tag, String name) {
        Matcher m = Pattern.compile("(?s)\\b" + name + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1).trim() : null;
    }

    // ---------------------------------------------------------------- helpers

    private static String epIdOf(String namespace, String tag) {
        return namespace == null ? tag : namespace + "." + tag;
    }

    /**
     * Every attribute in plugin.xml that names a class, with the extension point it
     * belongs to.  `localTag` keeps the tag as written (e.g. `lang.parserDefinition`)
     * so the requirement can be looked up in the platform's own declaration.
     */
    private void collectRefs(Element e, List<Ref> out, String namespace) {
        String tag = e.getTagName();
        String ns = namespace;
        if ("extensions".equals(tag) && attr(e, "defaultExtensionNs") != null) {
            ns = attr(e, "defaultExtensionNs");
        }
        String epId = "extension".equals(tag) && attr(e, "point") != null
                ? attr(e, "point")
                : epIdOf(ns, tag);
        for (String attribute : CLASS_ATTRIBUTES) {
            String v = attr(e, attribute);
            if (v != null && !v.isBlank()) {
                out.add(new Ref(epId, tag, v.trim(), attr(e, "fieldName"), tag + "/" + attribute));
            }
        }
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element child) {
                collectRefs(child, out, ns);
            }
        }
    }

    private static void collectAttribute(Element e, String name, List<String> out) {
        String v = attr(e, name);
        if (v != null && !v.isBlank()) {
            out.add(v.trim());
        }
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element child) {
                collectAttribute(child, name, out);
            }
        }
    }

    private static List<Element> descendants(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        collectDescendants(e, tag, out);
        return out;
    }

    private static void collectDescendants(Element e, String tag, List<Element> out) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element child) {
                if (child.getTagName().equals(tag)) {
                    out.add(child);
                }
                collectDescendants(child, tag, out);
            }
        }
    }

    private static List<Element> children(Element e, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element child && child.getTagName().equals(tag)) {
                out.add(child);
            }
        }
        return out;
    }

    private static String textOf(Element e, String tag) {
        List<Element> kids = children(e, tag);
        return kids.isEmpty() ? "(none)" : kids.get(0).getTextContent().trim();
    }

    private static String attr(Element e, String name) {
        return e.hasAttribute(name) ? e.getAttribute(name) : null;
    }

    private Element parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setValidating(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        return b.parse(new java.io.ByteArrayInputStream(bytes)).getDocumentElement();
    }

    private void requireEntry(Map<String, byte[]> entries, String name) {
        if (entries.containsKey(name)) {
            System.out.println("    OK  " + pad(name, 34) + " present (" + entries.get(name).length + " bytes)");
        } else {
            fail("jar is missing " + name);
        }
    }

    private void surf(String fact) {
        surface.add(fact);
    }

    /** Every class-carrying attribute of one registration, as it was written. */
    private static String attrsOf(Element e) {
        StringBuilder sb = new StringBuilder();
        for (String a : CLASS_ATTRIBUTES) {
            String v = attr(e, a);
            if (v != null && !v.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(a).append('=').append(v.trim());
            }
        }
        return sb.toString();
    }

    /**
     * Writes the inventory of what this plugin registers.  The summary a human hands
     * over can then quote a machine-written list instead of a transcription, and the
     * next change to plugin.xml has a diffable baseline: one sorted fact per line.
     */
    private void writeSurface() {
        if (surfaceFile == null) {
            return;
        }
        try {
            List<String> out = new ArrayList<>();
            out.add("# The Vela plugin surface, as VerifyPlugin reads it out of plugin.xml and the");
            out.add("# loaded classes.  Sorted, one fact per line, so a change to plugin.xml shows");
            out.add("# up as a diff against the last verified run.");
            out.add("# written " + java.time.LocalDateTime.now().withNano(0));
            surface.stream().sorted().forEach(out::add);
            out.add("result           : " + (failures.isEmpty()
                    ? "PASS" : "FAIL (" + failures.size() + " problem(s))"));
            Files.createDirectories(surfaceFile.getParent());
            Files.write(surfaceFile, out, StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("surface inventory: " + surfaceFile + " (" + surface.size() + " fact(s))");
        } catch (Exception e) {
            notes.add("could not write " + surfaceFile + ": " + e);
        }
    }

    private void section(String title) {
        System.out.println();
        System.out.println("--- " + title);
    }

    private void fail(String message) {
        failures.add(message);
        System.out.println("    FAIL " + message);
    }

    private static String pad(String s, int n) {
        return s.length() >= n ? s + " " : s + " ".repeat(n - s.length());
    }

    private static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    private boolean verdict(long started) {
        writeSurface();
        System.out.println();
        notes.forEach(n -> System.out.println("NOTE: " + n));
        System.out.println("checks took " + (System.currentTimeMillis() - started) + " ms");
        if (failures.isEmpty()) {
            System.out.println("RESULT: PASS - all structural, bytecode, platform, registration, "
                    + "linkage and behavioural checks passed");
            return true;
        }
        System.out.println("RESULT: FAIL - " + failures.size() + " problem(s)");
        failures.forEach(f -> System.out.println("  FAIL " + f));
        return false;
    }
}
