import com.intellij.codeInsight.hints.InlayHintsCollector;
import com.intellij.codeInsight.hints.InlayHintsSink;
import com.intellij.codeInsight.hints.NoSettings;
import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.lang.impl.PsiBuilderImpl;
import com.intellij.lexer.Lexer;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import dev.vela.plugin.VelaHints;
import dev.vela.plugin.VelaParameterNameInlayHintsProvider;
import dev.vela.plugin.VelaParserDefinition;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The parameter-name inlay hints, driven THE WAY THE PLATFORM DRIVES THEM.
 *
 * WHY THIS EXISTS
 *
 * Every hints tool in this directory (`HintDiff`, `HintShapes`, `HintTruth`,
 * `ParamNames`) measures `VelaHints.parameterHints(text, len)` -- the list.  Nothing
 * measured the object the platform actually calls: `VelaParameterNameInlayHintsCollector`,
 * reached through `VelaParameterNameInlayHintsProvider.getCollectorFor(file, editor,
 * settings, sink)`.  That gap shipped a defect nobody could see from the numbers: the
 * collector ignored the element it was handed, registered the WHOLE FILE's hint list on
 * every call, and returned `true` -- the platform's "keep walking into the children" --
 * so every hint was painted once per PSI element and a 57-line file filled its lines
 * with the same label repeated until it ran off the screen.  A list that is right and
 * painted N times is a screen that is wrong, and the list's own 0-wrong numbers could
 * not say so.
 *
 * WHAT IT MEASURES, PER FILE
 *
 *   1. `VelaHints.parameterHints(text, text.length())` -- how many hints the file has.
 *      This is the pure list, and it is the oracle for the count below.
 *   2. The walk.  The platform calls `collect(element, editor, sink)` starting at the
 *      file and continuing into the children while the call returns `true`.  That
 *      contract is `FactoryInlayHintsCollector.collect`'s own `Boolean`, and this tool
 *      drives it exactly so: the file's real PSI tree, built through the platform's own
 *      `PsiBuilderImpl` (the same replay `psi-tree-diff.ps1` uses), visited in pre-order,
 *      stopping the moment a call returns `false`.
 *   3. The sink.  A `java.lang.reflect.Proxy` for `InlayHintsSink` records every
 *      `addInlineElement(offset, precedes, presentation, atEndOfLine)` the collector
 *      registers, so the count is of *drawings*, not of intentions.
 *
 * THE INVARIANT, and it is the one the defect broke: registered == the list's size, no
 * matter how many elements the walk visits.  `wrong` counts files where they differ.
 *
 * WHAT IT DOES NOT MEASURE: a real editor.  The sink is a recording stand-in and the
 * editor is a proxy, so this is the headless half of the claim -- it says what the
 * collector registers, not what a running IDEA paints.  The screenshot that found the
 * defect is the editor half, and it is in the 0.1.12 changelog entry.
 *
 * Usage: InlayProbe <repoRoot> [--single <file>] [--verbose]
 */
public final class InlayProbe {

    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    private static boolean verbose = false;

    public static void main(String[] args) throws Exception {
        String repoRootArg = null;
        String single = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--single".equals(a) && i + 1 < args.length) {
                single = args[++i];
            } else if ("--verbose".equals(a)) {
                verbose = true;
            } else if (!a.startsWith("--") && repoRootArg == null) {
                repoRootArg = a;
            }
        }
        if (repoRootArg == null) {
            System.out.println("usage: InlayProbe <repoRoot> [--single <file>] [--verbose]");
            System.exit(2);
        }
        Path repoRoot = Paths.get(repoRootArg).toAbsolutePath().normalize();

        // A booted Application, because the tree below is built by the platform's own
        // `PsiBuilderImpl`: `MockApplication` is the platform's headless application,
        // the same one `psi-tree-diff.ps1` uses and for the same reason.
        Disposable rootDisposable = Disposer.newDisposable();
        MockApplication app = new MockApplication(rootDisposable);
        ApplicationManager.setApplication(app, rootDisposable);
        if (ApplicationManager.getApplication() == null) {
            System.out.println("ERROR: no Application: the platform builder cannot run");
            System.exit(2);
        }
        System.out.println("application : " + ApplicationManager.getApplication().getClass().getName()
                + " (the platform's own headless application; the builder and the collector are the real ones)");

        System.out.println("inlay    : the platform-called collector, not the list -- "
                + "VelaParameterNameInlayHintsProvider.getCollectorFor(file, editor, settings, sink)");
        System.out.println("walk     : collect(file), then children in pre-order while a call returns true "
                + "(FactoryInlayHintsCollector's own Boolean contract)");
        System.out.println("sink     : a recording InlayHintsSink -- every addInlineElement is counted");
        System.out.println("oracle   : VelaHints.parameterHints(text, text.length) -- the same list every"
                + " other hints tool measures");
        System.out.println("repo root: " + repoRoot);
        System.out.println();

        List<String> files = new ArrayList<>();
        if (single != null) {
            files.add(single.replace('\\', '/'));
        } else {
            // One file per shape the hints live in: methods with a receiver (and calls
            // with no argument at all), a plain call, calls inside a real program, and a
            // file whose call sites sit in a long inner loop.
            files.add("ide-demo/tour.vel");
            files.add("examples/hello.vel");
            files.add("tests/build/arith_basics.vel");
            files.add("tests/build/control_flow.vel");
            files.add("bench/matmul.vel");
        }

        VelaParserDefinition def = new VelaParserDefinition();
        Project project = (Project) Proxy.newProxyInstance(InlayProbe.class.getClassLoader(),
                new Class<?>[]{Project.class}, new Stub("project"));
        IElementType root = def.getFileNodeType();

        int ran = 0;
        int skipped = 0;
        int wrong = 0;
        int missing = 0;
        int tooLarge = 0;
        int threw = 0;
        int noHints = 0;
        long hintsTotal = 0;
        long registeredTotal = 0;
        long elementsTotal = 0;
        StringBuilder table = new StringBuilder();

        for (String rel : files) {
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p)) {
                missing++;
                table.append(pad(rel, 52)).append("  MISSING\n");
                continue;
            }
            if (Files.size(p) > MAX_FILE_BYTES) {
                tooLarge++;
                table.append(pad(rel, 52)).append("  SKIPPED (too large)\n");
                continue;
            }
            String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);

            int list;
            try {
                list = VelaHints.INSTANCE.parameterHints(text, text.length()).size();
            } catch (Throwable t) {
                threw++;
                table.append(pad(rel, 52)).append("  parameterHints THREW ").append(t).append('\n');
                continue;
            }

            Sink sink = new Sink();
            int visited = 0;
            try {
                PsiFile file = (PsiFile) Proxy.newProxyInstance(InlayProbe.class.getClassLoader(),
                        new Class<?>[]{PsiFile.class}, new Stub("file", text, project));
                Editor editor = (Editor) Proxy.newProxyInstance(InlayProbe.class.getClassLoader(),
                        new Class<?>[]{Editor.class}, new Stub("editor", project));

                VelaParameterNameInlayHintsProvider provider = new VelaParameterNameInlayHintsProvider();
                NoSettings settings = provider.createSettings();
                InlayHintsSink sinkProxy = sink.proxy();
                InlayHintsCollector collector =
                        provider.getCollectorFor(file, editor, settings, sinkProxy);

                // The file's own tree, through the platform's builder: the walk below is
                // over real PSI elements, not over a number this tool made up.
                Lexer lexer = def.createLexer(project);
                PsiBuilder b = new PsiBuilderImpl(project, null, def, lexer, null, text, null, null);
                PsiParser parser = def.createParser(project);
                parser.parse(root, b);
                ASTNode tree = b.getTreeBuilt();

                Deque<ASTNode> stack = new ArrayDeque<>();
                stack.push(tree);
                boolean go = true;
                while (!stack.isEmpty() && go) {
                    ASTNode node = stack.pop();
                    PsiElement element = node.getPsi();
                    // An `ASTNode` whose `getPsi()` is null is not an element the platform
                    // would hand the collector -- it walks PsiElements -- so it is left out
                    // of the walk, counted in `noPsi`, and not passed as `null`, which the
                    // collector's Kotlin non-null parameter would (correctly) reject.
                    if (element != null) {
                        visited++;
                        go = collector.collect(element, editor, sinkProxy);
                    }
                    List<ASTNode> children = new ArrayList<>();
                    for (ASTNode c = node.getFirstChildNode(); c != null; c = c.getTreeNext()) {
                        children.add(c);
                    }
                    for (int i = children.size() - 1; i >= 0; i--) {
                        stack.push(children.get(i));
                    }
                }
            } catch (Throwable t) {
                threw++;
                table.append(pad(rel, 52)).append("  the walk THREW ")
                        .append(t.getClass().getSimpleName()).append(": ").append(t.getMessage())
                        .append('\n');
                continue;
            }

            ran++;
            hintsTotal += list;
            registeredTotal += sink.inline;
            elementsTotal += visited;
            boolean bad = sink.inline != list;
            if (bad) {
                wrong++;
            }
            if (list == 0) {
                noHints++;
            }
            table.append(pad(rel, 52))
                    .append(pad("hints " + list, 10))
                    .append(pad("registered " + sink.inline, 16))
                    .append(pad("elements " + visited, 14))
                    .append(bad ? "WRONG" : "ok")
                    .append('\n');
        }

        System.out.println(pad("file", 52) + pad("the list", 10) + pad("registered", 16)
                + pad("walk reached", 14) + "verdict");
        System.out.println("-".repeat(52) + "  " + "-".repeat(8) + "  " + "-".repeat(14) + "  "
                + "-".repeat(12) + "  " + "-".repeat(7));
        System.out.print(table);

        int skippedTotal = missing + tooLarge;
        System.out.println();
        System.out.println("the invariant: what the collector registers == the list, "
                + "however many elements the walk visits");
        if (verbose) {
            System.out.println("  hints in the files     : " + hintsTotal);
            System.out.println("  hints registered       : " + registeredTotal);
            System.out.println("  elements walked        : " + elementsTotal);
        }
        System.out.println("COVERAGE: ran " + ran + " / skipped " + skippedTotal
                + " (missing-corpus-file " + missing + ", too-large " + tooLarge
                + ", walked-threw " + threw + ") / wrong " + wrong);
        boolean clean = ran > 0 && wrong == 0 && threw == 0 && missing == 0 && tooLarge == 0;
        if (clean && noHints > 0) {
            // A file with no hints at all cannot fail the invariant, and it cannot pass
            // it either: it is counted so a corpus of empty files cannot look green.
            System.out.println("note: " + noHints + " file(s) have no parameter-name hints to draw");
        }
        System.out.println("VERDICT: " + (clean
                ? "[PASS] every hint the file has was registered once by the walk the platform drives"
                  + " -- over " + ran + " judged file(s), " + hintsTotal + " hint(s), "
                  + elementsTotal + " element(s) visited, wrong 0"
                : "[FAIL] " + (threw > 0 ? "a walk threw; " : "")
                  + (wrong > 0 ? wrong + " file(s) registered a number of hints that is not the list's"
                        + " (that is the 0.1.11 defect: one paint per element) " : "")
                  + (missing > 0 ? missing + " corpus file(s) absent; " : "")
                  + (ran == 0 ? "nothing was judged" : "")));
        System.exit(clean ? 0 : 1);
    }

    // ------------------------------------------------------------------ the sink

    /** Records `addInlineElement`; everything else answers a default. */
    static final class Sink implements InvocationHandler {
        int inline = 0;
        final List<String> first = new ArrayList<>();

        InlayHintsSink proxy() {
            return (InlayHintsSink) Proxy.newProxyInstance(InlayProbe.class.getClassLoader(),
                    new Class<?>[]{InlayHintsSink.class}, this);
        }

        public Object invoke(Object p, Method m, Object[] a) {
            if ("addInlineElement".equals(m.getName())) {
                inline++;
                if (verbose && first.size() < 4) {
                    first.add(String.valueOf(a[0]));
                }
                return null;
            }
            return def(m);
        }
    }

    // ------------------------------------------------------- the two other stubs

    /**
     * A stand-in for the platform objects the collector only reads: the file (whose
     * text is the document under test), the editor, and a project that answers
     * `isDefault()` -- which is what makes the collector read `file.text` instead of
     * asking a `PsiDocumentManager` for a document that does not exist here.
     */
    static final class Stub implements InvocationHandler {
        private final String what;
        private final String text;
        private final Project project;

        Stub(String what) {
            this(what, null, null);
        }

        Stub(String what, Project project) {
            this(what, null, project);
        }

        Stub(String what, String text, Project project) {
            this.what = what;
            this.text = text;
            this.project = project;
        }

        public Object invoke(Object p, Method m, Object[] a) {
            switch (m.getName()) {
                case "getText":
                    if ("file".equals(what)) {
                        return m.getReturnType() == char[].class ? text.toCharArray() : text;
                    }
                    return def(m);
                case "getProject":
                    return project;
                case "isDefault":
                    return true;
                // `factory.text(label)` builds a presentation through the platform's own
                // `PresentationFactory(editor)`, and that constructor asks the editor for
                // its colour scheme and its font metrics.  A proxy for each keeps this
                // tool on the platform's real code path instead of a hand-built label --
                // the point of driving the collector at all -- and `getAttributes` answers
                // a real (empty) `TextAttributes`, because a presentation that cannot get
                // attributes is a presentation the platform will not build.
                case "getColorsScheme":
                    return Proxy.newProxyInstance(InlayProbe.class.getClassLoader(),
                            new Class<?>[]{com.intellij.openapi.editor.colors.EditorColorsScheme.class},
                            (q, mm, aa) -> {
                                switch (mm.getName()) {
                                    case "getAttributes":
                                        // `TextAttributes` lives in the markup package, not
                                        // the colors one: measured by javac refusing the
                                        // first spelling this line was written with.
                                        return new com.intellij.openapi.editor.markup.TextAttributes();
                                    case "toString":
                                        return "colorsScheme";
                                    case "hashCode":
                                        return System.identityHashCode(q);
                                    case "equals":
                                        return q == aa[0];
                                    default:
                                        return def(mm);
                                }
                            });
                case "getColorsScheme2":
                    return null;
                case "toString":
                    return what;
                case "hashCode":
                    return System.identityHashCode(p);
                case "equals":
                    return p == a[0];
                default:
                    return def(m);
            }
        }
    }

    /** The zero value of a method's return type: false, 0, null. */
    static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == double.class) return 0.0;
        if (r == float.class) return 0.0f;
        if (r == short.class) return (short) 0;
        if (r == byte.class) return (byte) 0;
        if (r == char.class) return (char) 0;
        return null;
    }

    static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) b.append(' ');
        return b.toString();
    }

    private InlayProbe() {
        throw new AssertionError("no instances");
    }
}
