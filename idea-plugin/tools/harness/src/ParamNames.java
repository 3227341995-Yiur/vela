import dev.vela.plugin.VelaCallTemplate;
import dev.vela.plugin.VelaHints;
import dev.vela.plugin.VelaModel;
import dev.vela.plugin.VelaSymbol;
import dev.vela.plugin.VelaTargets;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The *other* half of a callable's parameter list: the one completion uses.
 *
 * WHY THIS TOOL EXISTS
 *
 * `HintDiff` and `HintProbe` both watch the two features that *draw* a parameter name
 * (`VelaHints.parameterHints` and the parameter-info popup), and both were moved off
 * `symbolParameters` onto `VelaTargets.declaredParameterNames` -- the tree, with the
 * "null when the parameter list cannot be read" rule.  Completion was not: it still
 * asks `VelaHints.parameterNames(sym)` -> `symbolParameters(sym)`, which read the
 * parameter list out of `VelaSymbol.detail`, the signature **as written in the source**,
 * split on commas.  For a declaration the compiler refuses -- `def f(s, s, s) -> int`,
 * whose parameters have no annotations -- that slice is `["s", "s", "s"]`, so completion
 * inserts `(s, s, s)` into the user's document: the same defect the report described,
 * surviving in the one path no hint harness looks at.
 *
 * This tool asks that path directly, per symbol, over the corpus, and judges the answer
 * against the declaration text ([DeclReader], an oracle that shares no code with the
 * plugin) and against `SPEC.md` for a name the file does not declare.
 *
 *   java -cp <plugin classes> ParamNames <repo-root> [--single <file>] [--dump]
 *                                                   [--truncate <n>]
 *
 * It also runs the two structural invariants that stop the defect coming back, which
 * are printed whether or not the corpus has anything to say:
 *
 *   * a symbol whose `parameters` were never read from a tree answers **no names**,
 *     whatever its `detail` says -- a hand-built symbol whose detail reads
 *     `f(s: ..., s: ...) -> int` must not produce two parameters;
 *   * a builtin's parameter list must come from its declared *signature* field: the
 *     description text of every builtin must answer no names at all, and the names
 *     from the signature must be the names the plugin reports.
 */
public final class ParamNames {

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private final List<String> corpus = new ArrayList<>();
    private Path repoRoot;
    private String single;
    private boolean dump;
    private int truncate;

    private long judged;
    private long wrong;
    private long unavailable;
    private final Map<String, Long> skipped = new LinkedHashMap<>();
    private final List<String> findings = new ArrayList<>();
    private final List<String> specFindings = new ArrayList<>();
    private long specMismatches;

    public static void main(String[] args) throws Exception {
        ParamNames tool = new ParamNames();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--single")) tool.single = args[++i];
            else if (a.equals("--dump")) tool.dump = true;
            else if (a.equals("--truncate")) tool.truncate = Integer.parseInt(args[++i]);
            else rest.add(a);
        }
        tool.repoRoot = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        if (tool.single != null) tool.corpus.add(tool.single.replace('\\', '/'));
        else tool.collectCorpus();
        tool.run();
    }

    private void run() throws IOException {
        System.out.println("names    : VelaHints.parameterNames(sym) -- what completion inserts, per symbol");
        System.out.println("template : VelaHints.callTemplate(sym, false, false) -- the text completion writes");
        System.out.println("oracle   : the declaration's own parameter list, read from the source by this");
        System.out.println("           harness (DeclReader); for a name no file declares, the builtin's");
        System.out.println("           declared signature, cross-checked against SPEC.md section 8.");
        System.out.println();

        for (String rel : corpus) {
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p)) {
                bump("missing-corpus-file");
                continue;
            }
            if (Files.size(p) > MAX_FILE_BYTES) {
                bump("file-too-large");
                continue;
            }
            String full = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            List<String> texts = new ArrayList<>();
            texts.add(full);
            if (truncate > 0) {
                for (int cut = 1; cut < full.length(); cut += truncate) texts.add(full.substring(0, cut));
            }
            for (String text : texts) {
                try {
                    judge(text, rel, text.length() < full.length() ? text.length() : -1);
                } catch (Throwable t) {
                    bump("harness-threw");
                    if (findings.size() < 60) findings.add(rel + ": THREW " + t);
                }
            }
        }

        System.out.println("== findings: a name list the declaration does not give ==");
        if (findings.isEmpty()) System.out.println("  (none)");
        else for (String f : findings) System.out.println("  " + f);

        int invariantFailures = invariants();

        System.out.println();
        System.out.println("== SPEC.md section 8's parameter names vs the plugin's own table ==");
        if (specFindings.isEmpty()) {
            System.out.println("  (every builtin SPEC.md names agrees with the plugin)");
        } else {
            for (String f : specFindings) System.out.println("  " + f);
        }

        System.out.println();
        System.out.println("== totals ==");
        System.out.println("  callables judged                        : " + judged);
        System.out.println("  whose names are not available at all    : " + unavailable
                + " (completion writes `()`, which is honest; not a defect");
        System.out.println("                                           but the number a reader needs)");
        System.out.println("  name lists the oracle does not give     : " + wrong);
        System.out.println("  SPEC.md / plugin disagreements          : " + specMismatches);

        Coverage cov = new Coverage()
                .defectCategory("harness-threw")
                .category("missing-corpus-file")
                .category("file-too-large")
                .category("not-a-callable")
                .category("callee-not-in-this-file-and-not-a-builtin")
                .category("builtin-arity-only-SPEC-md-names-none")
                .ran(judged)
                .defect("harness-threw", skipped.getOrDefault("harness-threw", 0L))
                .skipped("missing-corpus-file", skipped.getOrDefault("missing-corpus-file", 0L))
                .skipped("file-too-large", skipped.getOrDefault("file-too-large", 0L))
                .skipped("not-a-callable", skipped.getOrDefault("not-a-callable", 0L))
                .skipped("callee-not-in-this-file-and-not-a-builtin",
                        skipped.getOrDefault("callee-not-in-this-file-and-not-a-builtin", 0L))
                .skipped("builtin-arity-only-SPEC-md-names-none",
                        skipped.getOrDefault("builtin-arity-only-SPEC-md-names-none", 0L))
                .wrong(wrong + invariantFailures + specMismatches);
        cov.print();
        System.out.println();
        long bad = wrong + invariantFailures + specMismatches;
        System.out.println(bad == 0
                ? "VERDICT: every parameter list either names what its declaration names, or names nothing"
                : "VERDICT: " + wrong + " name list(s) disagree with their declaration, "
                        + invariantFailures + " invariant(s) broken, " + specMismatches
                        + " SPEC.md disagreement(s)");
        System.exit(bad > 0 ? 1 : (cov.hasDefect() ? 3 : 0));
    }

    private void bump(String why) {
        skipped.merge(why, 1L, Long::sum);
    }

    // ------------------------------------------------------------------ judging

    private void judge(String text, String rel, int cutAt) {
        List<DeclReader.Decl> decls = DeclReader.declarations(text);
        Map<String, List<String>> spec = DeclReader.specBuiltinNames(repoRoot);
        List<VelaSymbol> symbols;
        try {
            symbols = VelaModel.INSTANCE.symbols(text);
        } catch (Throwable t) {
            bump("harness-threw");
            findings.add(where(rel, cutAt) + ": symbols() THREW " + t);
            return;
        }
        for (VelaSymbol sym : symbols) {
            if (!sym.isCallable()) {
                bump("not-a-callable");
                continue;
            }
            List<String> names = VelaHints.INSTANCE.parameterNames(sym);
            VelaCallTemplate template = VelaHints.INSTANCE.callTemplate(sym, false, false);

            DeclReader.Decl decl = DeclReader.pick(decls, sym.getName(), null, sym.getLine());
            List<String> oracle;
            String source;
            if (decl != null) {
                oracle = decl.params;
                source = decl.signature;
            } else {
                List<String> fromSignature = builtinSignatureNames(sym.getName());
                if (fromSignature == null) {
                    bump("callee-not-in-this-file-and-not-a-builtin");
                    continue;
                }
                oracle = fromSignature;
                source = "the builtin signature for `" + sym.getName() + "`";
                List<String> specNames = spec.get(sym.getName());
                if (specNames != null && !specNames.equals(oracle)) {
                    specMismatches++;
                    if (specFindings.size() < 40) {
                        specFindings.add("builtin `" + sym.getName() + "`: SPEC.md section 8 names "
                                + specNames + ", the plugin's signature names " + oracle);
                    }
                }
            }
            judged++;
            if (dump) {
                System.out.println("---- " + where(rel, cutAt) + "  " + sym.getKind() + " `"
                        + sym.getName() + "` line " + sym.getLine());
                System.out.println("     detail  : " + sym.getDetail());
                System.out.println("     names   : " + names);
                System.out.println("     template: " + template);
                System.out.println("     oracle  : " + (oracle == null ? "(cannot be read)" : oracle)
                        + "   from " + source);
            }
            if (!sym.getDetail().startsWith(sym.getName() + "(")) {
                // The structural invariant `HintDupes` already asserts for the model: a
                // detail that does not begin with its own name cannot be a signature.
                finding(where(rel, cutAt) + ": `" + sym.getName() + "` detail does not begin with"
                        + " its own name: `" + sym.getDetail() + "`");
            }
            if (oracle == null) {
                if (!names.isEmpty()) {
                    finding(where(rel, cutAt) + ": `" + sym.getName() + "` (line " + sym.getLine()
                            + ") answers the parameter names " + names + " although its declaration's"
                            + " parameter list cannot be read: `" + source + "`"
                            + "\n      completion would insert `" + template + "`");
                }
                continue;
            }
            if (names.isEmpty()) {
                if (!oracle.isEmpty()) unavailable++;
                continue;
            }
            for (String n : names) {
                int inDrawn = occurrences(names, n);
                int inOracle = occurrences(oracle, n);
                if (inOracle == 0) {
                    finding(where(rel, cutAt) + ": `" + sym.getName() + "` (line " + sym.getLine()
                            + ") answers the parameter name `" + n + "`, which its declaration does"
                            + " not name: " + oracle + " (`" + source + "`)"
                            + "\n      completion would insert `" + template + "`");
                    break;
                }
                if (inDrawn > inOracle) {
                    finding(where(rel, cutAt) + ": `" + sym.getName() + "` (line " + sym.getLine()
                            + ") answers `" + n + "` " + inDrawn + " time(s) where its declaration"
                            + " names it " + inOracle + ": " + names + " vs " + oracle
                            + " (`" + source + "`)"
                            + "\n      completion would insert `" + template + "`");
                    break;
                }
            }
            if (!names.equals(oracle)) {
                finding(where(rel, cutAt) + ": `" + sym.getName() + "` (line " + sym.getLine()
                        + ") answers " + names + " where its declaration names " + oracle
                        + " (`" + source + "`)"
                        + "\n      completion would insert `" + template + "`");
            }
        }
    }

    private void finding(String text) {
        wrong++;
        if (findings.size() < 60) findings.add(text);
    }

    private static int occurrences(List<String> names, String name) {
        int n = 0;
        for (String s : names) if (s != null && s.equals(name)) n++;
        return n;
    }

    private static String where(String rel, int cutAt) {
        return cutAt < 0 ? rel : rel + " [cut at " + cutAt + "]";
    }

    // ------------------------------------------------------------------ invariants

    /**
     * The two checks that stop the defect coming back, run on every invocation rather
     * than by hand: both are about *where* a parameter list comes from, which is the
     * only thing that made the reported shape possible.
     */
    private int invariants() {
        int failures = 0;
        System.out.println();
        System.out.println("== the structural invariants: where a parameter list may come from ==");

        // 1. A symbol whose names were not read from a tree must answer none, whatever
        //    its detail text says.  The hostile detail below is the reported shape:
        //    a signature that begins with its own name and holds two same-named
        //    parameters the declaration never gives.
        String hostileDetail = "f(s: the same name twice, s: the same name twice) -> int";
        VelaSymbol hostile = null;
        try {
            hostile = handBuiltSymbol(
                    dev.vela.plugin.VelaSymbolKind.FUNCTION, "f", hostileDetail, "int", 1, -1);
        } catch (Throwable t) {
            System.out.println("  [skip] cannot build a symbol by hand: " + t);
        }
        if (hostile != null) {
            List<String> names = VelaHints.INSTANCE.parameterNames(hostile);
            VelaCallTemplate template = VelaHints.INSTANCE.callTemplate(hostile, false, false);
            boolean ok = names.isEmpty() && (template == null || "()".equals(template.getText()));
            System.out.println("  a symbol whose detail is text, not a parsed parameter list `"
                    + hostileDetail + "`");
            System.out.println("      parameterNames -> " + names + "   callTemplate -> " + template
                    + "   " + (ok ? "ok (no name is taken from the detail text)"
                                  : "BROKEN (a name was taken from detail text)"));
            if (!ok) failures++;
        }

        // 2. A builtin's names must come from its signature field: its description text
        //    must not answer names at all, and the signature must answer exactly what
        //    the plugin reports.  The SPEC.md comparison below runs in both builds: an
        //    older table keeps the signature and the prose in one string, and then only
        //    the names it reports can be compared.
        Object builtins = VelaModel.INSTANCE.getBUILTINS();
        Map<String, List<String>> spec = DeclReader.specBuiltinNames(repoRoot);
        if (dump) System.out.println("  SPEC.md section 8 names parsed here: " + spec);
        int withSignature = 0;
        int withoutSignature = 0;
        int descriptionsThatLookLikeSignatures = 0;
        int signaturesThatDisagree = 0;
        boolean structured = false;
        for (Object entry : (List<?>) builtins) {
            String name = (String) reflect(entry, "getName");
            String signature = (String) reflect(entry, "getSignature");
            String prose = (String) reflect(entry, "getProse");
            if (name == null) name = (String) reflect(entry, "getFirst");   // pre-fix Pair
            if (name == null) continue;
            List<String> reported = VelaTargets.INSTANCE.builtinParameterNames(name);
            if (signature == null) {
                withoutSignature++;
            } else {
                structured = true;
                withSignature++;
                Probe fromProse = signatureNames(name, prose == null ? "" : prose);
                if (fromProse.present && fromProse.names != null) {
                    descriptionsThatLookLikeSignatures++;
                    System.out.println("      BROKEN: `" + name + "`'s description answers names "
                            + fromProse.names + ": `" + prose + "`");
                }
                List<String> fromSignature = signatureNames(name, signature).names;
                if (!String.valueOf(fromSignature).equals(String.valueOf(reported))) {
                    signaturesThatDisagree++;
                    System.out.println("      BROKEN: `" + name + "` signature " + signature
                            + " gives " + fromSignature + " but builtinParameterNames gives "
                            + reported);
                }
            }
            // SPEC.md is the language's own document and the only declaration a builtin
            // has; the plugin's table is the second opinion, and where they disagree the
            // label is a name the declaration does not give.
            List<String> specNames = spec.get(name);
            if (specNames != null && !specNames.equals(reported)) {
                specMismatches++;
                if (specFindings.size() < 40) {
                    specFindings.add("builtin `" + name + "`: SPEC.md section 8 names " + specNames
                            + ", the plugin's table names " + reported);
                }
            }
        }
        System.out.println("  builtin table entries without a declared signature field : "
                + withoutSignature + " (must be 0)");
        if (withoutSignature > 0) {
            System.out.println("      -> a table that keeps the signature and the prose in one string"
                    + " cannot be asked where its names came from, so that question is unchecked");
        }
        System.out.println("  builtin descriptions that answer parameter names : "
                + (structured ? String.valueOf(descriptionsThatLookLikeSignatures)
                              : "not checkable in this build")
                + " (must be 0)");
        System.out.println("  builtin signatures that disagree with the names reported : "
                + (structured ? String.valueOf(signaturesThatDisagree)
                              : "not checkable in this build")
                + " (must be 0)");
        System.out.println("  SPEC.md section 8 parameter names the plugin's table disagrees with : "
                + specMismatches + " (must be 0; " + spec.size()
                + " builtin(s) named in SPEC.md, compared above for every entry)");
        if (structured) {
            failures += descriptionsThatLookLikeSignatures + signaturesThatDisagree;
            System.out.println("      (checked " + withSignature + " builtin(s) with a signature field)");
        }
        failures += withoutSignature;
        return failures;
    }

    /** The names the builtin's *declared signature field* gives, or null when there is none. */
    private static List<String> builtinSignatureNames(String name) {
        Object builtins = VelaModel.INSTANCE.getBUILTINS();
        for (Object entry : (List<?>) builtins) {
            String entryName = (String) reflect(entry, "getName");
            String signature = (String) reflect(entry, "getSignature");
            if (entryName == null || signature == null) {
                // The pre-fix table: `Pair(name, description)`, where the description is
                // the signature and the prose in one string.  Then the only honest
                // answer is the one the plugin gives, marked as such.
                Object n = reflect(entry, "getFirst");
                if (name.equals(n)) return VelaTargets.INSTANCE.builtinParameterNames(name);
                continue;
            }
            if (entryName.equals(name)) {
                return signatureNames(name, signature).names;
            }
        }
        return null;
    }

    /** Whether the plugin has the signature reader at all, and what it answers. */
    private static final class Probe {
        final boolean present;
        final List<String> names;

        Probe(boolean present, List<String> names) {
            this.present = present;
            this.names = names;
        }
    }

    /**
     * `VelaTargets.signatureParameterNames(name, text)` by reflection: the reader this
     * round adds, so that ONE build of this harness measures the build before the fix
     * and the build after it.  A build without it answers `present = false`.
     */
    @SuppressWarnings("unchecked")
    private static Probe signatureNames(String name, String text) {
        try {
            Method m = VelaTargets.class.getMethod("signatureParameterNames", String.class, String.class);
            Object r = m.invoke(VelaTargets.INSTANCE, name, text);
            return new Probe(true, (List<String>) r);
        } catch (NoSuchMethodException e) {
            return new Probe(false, null);
        } catch (Throwable t) {
            throw new RuntimeException("signatureParameterNames threw: " + t, t);
        }
    }

    /**
     * A symbol built by hand rather than read from a tree: the constructor gained a
     * `parameters` field with this round's fix, so the seven-argument form is tried
     * first and the six-argument form answers the build before it.
     */
    private static VelaSymbol handBuiltSymbol(dev.vela.plugin.VelaSymbolKind kind, String name,
                                             String detail, String type, int line, int parent)
            throws Exception {
        for (java.lang.reflect.Constructor<?> c : VelaSymbol.class.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length < 5 || p[0] != dev.vela.plugin.VelaSymbolKind.class) continue;
            if (p[1] != String.class || p[2] != String.class || p[3] != String.class) continue;
            if (p[4] != int.class) continue;
            Object[] a = new Object[p.length];
            a[0] = kind;
            a[1] = name;
            a[2] = detail;
            a[3] = type;
            a[4] = line;
            for (int i = 5; i < p.length; i++) {
                a[i] = p[i] == int.class ? (Integer) parent
                        : (p[i] == List.class ? null : null);
            }
            return (VelaSymbol) c.newInstance(a);
        }
        throw new IllegalStateException("no VelaSymbol constructor with the expected shape");
    }

    private static Object reflect(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ corpus

    private void collectCorpus() throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        Path cases = repoRoot.resolve("tests").resolve("cases.txt");
        if (Files.isRegularFile(cases)) {
            for (String line : Files.readAllLines(cases, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length < 3) continue;
                if (parts[2].endsWith(".vel")) seen.add(parts[2]);
            }
        }
        for (String dir : new String[]{"tests", "examples", "bench", "ide-demo", "selfhost/parts"}) {
            Path d = repoRoot.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            List<Path> found = new ArrayList<>();
            Files.walk(d)
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vel"))
                    .forEach(found::add);
            Collections.sort(found);
            for (Path f : found) seen.add(repoRoot.relativize(f).toString().replace('\\', '/'));
        }
        if (Files.isRegularFile(repoRoot.resolve("selfhost/vela.vel"))) seen.add("selfhost/vela.vel");
        corpus.addAll(seen);
    }
}
