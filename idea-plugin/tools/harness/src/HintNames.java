import dev.vela.plugin.VelaHints;
import dev.vela.plugin.VelaModel;
import dev.vela.plugin.VelaNodeKind;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaSymbol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Do the *two* sources of a callable's parameter names agree?
 *
 * The hint engine and the parameter-info popup take their names from
 * `symbolParameters(sym)`, which splits `sym.detail`.  The tree has the parameters
 * themselves -- the `param` nodes the compiler's own dump prints.  If the two ever
 * disagree, the first is guessing and the second is a fact, and every such place is
 * a place where a hint could name the wrong parameter.
 *
 * So: for every callable declared in every file, compare the names the model would
 * draw against the names the tree declares.  This is the search that has to find the
 * `s: s: s:` shape, if that shape exists at all in the corpus.
 *
 *   java -cp <plugin classes> HintNames <repo-root> [--truncate <n>]
 */
public final class HintNames {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /**
     * How long `vm.exe check` gets before this harness stops waiting for it.
     *
     * MEASURED, AND IT IS NOT PARANOIA.  `vm.exe check tests/safety/cases/strict_no_inferred_binding_type.vel`
     * -- a fifty-byte program -- takes 13 s on an idle machine and took 326 s while another
     * harness was running, while the same program with `mut a: int = 1` answers in 25 ms.  A
     * harness that waits for that forever is a harness that hangs.
     */
    private static final int CHECK_TIMEOUT_SECONDS = 30;

    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Path vm = null;
        int step = 0;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--truncate")) step = Integer.parseInt(args[++i]);
            else if (args[i].equals("--vm")) vm = Paths.get(args[++i]).toAbsolutePath();
            else root = Paths.get(args[i]).toAbsolutePath();
        }
        if (vm == null) vm = root.resolve("selfhost").resolve("build").resolve("vm.exe");
        Set<String> files = new LinkedHashSet<>();
        for (String dir : new String[]{"tests", "examples", "ide-demo", "selfhost/parts", "bench"}) {
            Path d = root.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            List<Path> found = new ArrayList<>();
            Files.walk(d)
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vel"))
                    .forEach(found::add);
            Collections.sort(found);
            for (Path f : found) files.add(root.relativize(f).toString().replace('\\', '/'));
        }
        for (String extra : new String[]{"selfhost/vela.vel", "selfhost/vm.vel"}) {
            if (Files.isRegularFile(root.resolve(extra))) files.add(extra);
        }

        System.out.println("comparing: parameterNames(VelaModel.symbols) against the tree's param nodes");
        System.out.println("compiler : " + vm + (Files.isRegularFile(vm)
                ? " (" + Files.size(vm) + " bytes, sha256 " + sha256(vm) + ")" : "  (NOT FOUND)"));
        // A FILE THE COMPILER REFUSES IS NOT REALLY VELA, SO A DISAGREEMENT INSIDE IT IS NOT A
        // DEFECT.  The acceptance corpus holds today's unimplemented features -- closures
        // (`closure_fn_parameter.vel`: syntax error) and nested functions
        // (`nested_fn_called.vel`: `type error: nested functions are not supported`) -- and this
        // tool used to record two of them as disagreements of the model against the tree.
        //
        // THE COMPILER IS ASKED ONLY WHERE A DISAGREEMENT WAS FOUND, AND THAT IS A MEASUREMENT
        // RATHER THAN A SHORTCUT.  180 of the 312 corpus files are refused by `check` *by
        // design* (`tests/safety/cases/**` are the refusals, `tests/build/check_cases/**` are
        // the errors, `selfhost/parts/**` are pieces of a linked program), so skipping every
        // refused file would move most of this harness's work to the skip side and call the
        // remainder green; and asking about all 312 files costs about 340 s, 326 s of it on one
        // fifty-byte file (see CHECK_TIMEOUT_SECONDS).  A file whose two sources agree is
        // therefore judged, which is a stronger statement than not looking at it.
        long compared = 0;
        long disagree = 0;
        long absent = 0;
        long tooLarge = 0;
        long threw = 0;
        long noModel = 0;
        long refusedUnits = 0;
        long shown = 0;
        long checkTimeouts = 0;
        List<String> refusedFiles = new ArrayList<>();
        List<String> refusedObservations = new ArrayList<>();
        for (String rel : files) {
            Path p = root.resolve(rel);
            if (!Files.isRegularFile(p)) {
                absent++;
                continue;
            }
            if (Files.size(p) > MAX_FILE_BYTES) {
                tooLarge++;
                continue;
            }
            String full = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            List<String> texts = new ArrayList<>();
            texts.add(full);
            if (step > 0) {
                for (int cut = 1; cut < full.length(); cut += step) texts.add(full.substring(0, cut));
            }
            // This file's judgement is buffered: whether it counts as judged or as refused by
            // the compiler cannot be known until the file is done.
            long fileCompared = 0;
            long fileDisagree = 0;
            List<String> fileFindings = new ArrayList<>();
            for (String text : texts) {
                VelaSyntaxTree tree;
                List<VelaSymbol> symbols;
                try {
                    tree = VelaSyntaxParser.parse(text);
                    symbols = VelaModel.INSTANCE.symbols(text);
                } catch (Throwable t) {
                    threw++;
                    System.out.println("  " + rel + ": THREW " + t);
                    continue;
                }
                List<VelaSyntaxNode> defs = new ArrayList<>();
                collectDefs(tree.root, defs);
                for (VelaSyntaxNode def : defs) {
                    List<String> declared = new ArrayList<>();
                    for (VelaSyntaxNode c : def.children) {
                        if (c.kind == VelaNodeKind.PARAM) declared.add(c.name);
                    }
                    int line = lineOf(text, def.startTok >= 0 && def.startTok < tree.toks.size()
                            ? tree.toks.get(def.startTok).start : 0);
                    List<String> model = null;
                    for (VelaSymbol s : symbols) {
                        if (!s.isCallable()) continue;
                        if (!s.getName().equals(def.name)) continue;
                        if (s.getLine() != line) continue;
                        model = VelaHints.INSTANCE.parameterNames(s);
                        break;
                    }
                    if (model == null) {
                        // No entry in the symbol model matched this def, so the two sources
                        // could not be compared at all.  Counted: a def that is silently
                        // skipped is the same omission as a crash counted as success.
                        noModel++;
                        continue;
                    }
                    fileCompared++;
                    if (!model.equals(declared)) {
                        fileDisagree++;
                        if (shown + fileFindings.size() < 30) {
                            fileFindings.add(rel + (text.length() < full.length()
                                    ? " [truncated at " + text.length() + "]" : "")
                                    + " line " + line + " `" + def.name + "`  tree=" + declared
                                    + "  model=" + model);
                        }
                    }
                }
            }
            if (fileDisagree > 0) {
                Check c = check(vm, p);
                if (c.timedOut) {
                    // The compiler did not answer, so whether this file is Vela is unknown and
                    // its disagreements stay disagreements.  Counted as a defect: a run that
                    // could not ask is not a clean run.
                    checkTimeouts++;
                    System.out.println("  " + rel + ": `vm.exe check` did not answer within "
                            + CHECK_TIMEOUT_SECONDS + "s, so this file's findings stand");
                } else if (c.exit != 0) {
                    refusedUnits += fileCompared;
                    refusedFiles.add(rel + "  (`vm.exe check` exit " + c.exit + ": " + oneLine(c.err) + ")");
                    for (String f : fileFindings) refusedObservations.add("  " + f);
                    // Not judged: not on the `ran` side, not `wrong`, and the exit code does not
                    // move for it.  Every unit it would have contributed is in the named class.
                    continue;
                }
            }
            compared += fileCompared;
            disagree += fileDisagree;
            for (String f : fileFindings) {
                System.out.println("  " + f);
                shown++;
            }
        }
        System.out.println();
        // WHAT THE COMPILER REFUSED IS PRINTED, WITH ITS OWN WORDS, BEFORE THE COUNTS.  A
        // skip class that a reader cannot see the members of is a place to hide work.
        if (!refusedFiles.isEmpty()) {
            System.out.println("== files `vm.exe check` refuses, so they are NOT judged ==");
            for (String f : refusedFiles) System.out.println("  " + f);
            if (!refusedObservations.isEmpty()) {
                System.out.println("  their disagreements, kept as observations rather than defects:");
                for (String f : refusedObservations) System.out.println("  " + f);
            }
            System.out.println("  (" + refusedUnits + " callable(s) moved from `wrong` to this named skip;"
                    + " the compiler was asked only about the files where a disagreement was found,"
                    + " because 180 of the 312 corpus files are refused by design and asking about"
                    + " every one costs ~340 s)");
            System.out.println();
        } else {
            System.out.println("== files `vm.exe check` refuses, so they are NOT judged ==");
            System.out.println("  (none: no file with a disagreement was refused by the compiler;"
                    + " every file whose two sources agreed is judged, not skipped)");
            System.out.println();
        }
        System.out.println("callables compared            : " + compared);
        System.out.println("where the two sources disagree: " + disagree);
        Coverage cov = new Coverage()
                .defectCategory("threw")
                .defectCategory("missing-corpus-file")
                .defectCategory("check-timed-out")
                // A RUN THAT JUDGED NOTHING IS NOT A CLEAN RUN.  Measured: this tool pointed at a
                // path that is not a repo root -- which is exactly what a `--vm` it did not
                // understand used to do to its argument parser -- printed `ran 0 / wrong 0` and
                // a PASS.  That is the same shape as the crashed oracle that reported a clean
                // pass, so it is a named defect category, and the exit code says so.
                .defectCategory("nothing-was-judged")
                .category("no-model-entry-for-this-def")
                .category("too-large")
                .category("compiler-refused-the-file")
                .ran(compared)
                .skipped("threw", threw)
                .skipped("missing-corpus-file", absent)
                .skipped("no-model-entry-for-this-def", noModel)
                .skipped("too-large", tooLarge)
                .skipped("compiler-refused-the-file", refusedUnits)
                .defect("check-timed-out", checkTimeouts)
                .defect("nothing-was-judged", compared == 0 ? 1 : 0)
                .wrong(disagree);
        cov.print();
        // THE CONCLUSION, FROM THIS RUN'S OWN NUMBERS.
        //
        // This tool used to print its counts and its coverage triple and nothing else, so
        // the only place its conclusion existed was the exit code -- and a driver that read
        // the *text* of the run had nothing to read, which is how it came to be summarised
        // as "the tool did not finish" while it had just exited 0 with `wrong 0`.
        //
        // The sentence can only be a PASS under the same condition that makes the exit code
        // 0, because both are computed from `failed` below: a verdict that cannot fail is
        // not a verdict.
        List<String> failed = new ArrayList<>();
        if (compared == 0) {
            failed.add("nothing was judged at all: not one callable was compared, so `wrong 0` here"
                    + " is the absence of a measurement rather than a clean one (is the repo root"
                    + " the wrong path, or the compiler missing?)");
        }
        if (disagree > 0) {
            failed.add(disagree + " of " + compared + " callable(s) name their parameters differently"
                    + " in the model than in the tree (each one listed above)");
        }
        if (cov.hasDefect()) {
            failed.add("the run is not clean either: " + cov.get("threw") + " threw, "
                    + cov.get("missing-corpus-file") + " corpus file(s) missing, "
                    + cov.get("check-timed-out") + " file(s) the compiler did not answer about"
                    + " within " + CHECK_TIMEOUT_SECONDS + "s");
        }
        // THE PASS LINE CARRIES WHAT IT DID NOT JUDGE, ALWAYS.  `wrong 0` over a corpus with
        // refused files must not read like `wrong 0` over the whole corpus.
        String scope = refusedUnits == 0
                ? "the compiler refused no file with a disagreement, so nothing was moved out of"
                        + " the verdict"
                : refusedFiles.size() + " file(s) the compiler refuses were not judged and are"
                        + " counted as `compiler-refused-the-file` " + refusedUnits + " callable(s)";
        boolean clean = failed.isEmpty();
        System.out.println("VERDICT: " + (clean
                ? "[PASS] the model and the tree give the same parameter names for all "
                        + compared + " callable(s) compared (wrong 0, no defect category tripped;"
                        + " " + scope + ")"
                : "[FAIL] " + String.join("; ", failed) + " -- and " + scope));
        // 1 is "the two sources disagree", 3 is "the harness or the corpus is at fault".
        System.exit(clean ? 0 : (disagree > 0 ? 1 : 3));
    }

    /** What `vm.exe check` said about one file. */
    private static final class Check {
        int exit;
        boolean timedOut;
        String err = "";
    }

    /**
     * Run the frozen compiler's own verdict over a file, with a bound.
     *
     * Non-zero means the compiler refuses the file -- a syntax error or a type error, which is
     * the language saying this is not a Vela program.  A file whose bytes are written to a
     * temporary `.vel` copy is what is asked about, exactly as the corpus file's own bytes
     * stand, so the answer is about the program and not about its path.
     */
    private static Check check(Path vm, Path file) throws IOException, InterruptedException {
        Check c = new Check();
        Path tmp = Files.createTempFile("vela-hintnames-check", ".vel");
        Path out = Files.createTempFile("vela-hintnames-check-out", ".txt");
        Path err = Files.createTempFile("vela-hintnames-check-err", ".txt");
        try {
            Files.write(tmp, Files.readAllBytes(file));
            ProcessBuilder pb = new ProcessBuilder(vm.toString(), "check", tmp.toString());
            pb.redirectOutput(out.toFile());
            pb.redirectError(err.toFile());
            Process proc = pb.start();
            if (!proc.waitFor(CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                c.timedOut = true;
                proc.destroyForcibly();
                proc.waitFor();
            } else {
                c.exit = proc.exitValue();
            }
            c.err = new String(Files.readAllBytes(err), StandardCharsets.UTF_8)
                    .replace("\r", " ").replace("\n", " ");
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
        return c;
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 130 ? t.substring(0, 130) + "..." : t;
    }

    private static String sha256(Path p) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append("0123456789abcdef".charAt((b >> 4) & 0xF));
                sb.append("0123456789abcdef".charAt(b & 0xF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "(could not hash)";
        }
    }

    private static void collectDefs(VelaSyntaxNode n, List<VelaSyntaxNode> out) {
        if (n.kind == VelaNodeKind.DEF) out.add(n);
        for (VelaSyntaxNode c : n.children) collectDefs(c, out);
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private HintNames() {
    }
}
