import dev.vela.plugin.VelaSyntaxDump;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxProblem;
import dev.vela.plugin.VelaSyntaxTree;

import java.io.IOException;
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
 * The acceptance test for the plugin's parser: does it agree with the compiler?
 *
 * This is the whole argument for having a parser in the plugin at all.  The
 * plugin used to build a flat tree "because the compiler decides what a program
 * means, so a second parser would be a second opinion".  That is true of
 * *meaning* and false of *shape* -- and the only way to show it is false is to
 * put the two side by side over every file the language has.
 *
 * For each file:
 *
 *   1. parse it with the plugin's parser ({@link VelaSyntaxParser}) and print
 *      the tree in the compiler's canonical format ({@link VelaSyntaxDump});
 *   2. run `selfhost\build\vm.exe parse <file>` -- the compiler printing its own
 *      tree in that same format;
 *   3. diff the two, line for line.
 *
 * A file where the two come out identical is a file where the plugin's tree
 * *is* the compiler's tree: not a second opinion, a mirror.  A file where they
 * differ is reported with the first differing line from each side, in full,
 * because the point of this tool is the evidence and not a verdict.
 *
 * The corpus is `tests/cases.txt` (every `.vel` path it names), every `*.vel`
 * under `tests/build`, `tests/probes`, `examples` and `bench`, and
 * `ide-demo/*.vel`.  Files the compiler refuses -- a syntax error, a lexer
 * error, a crash -- have no tree to compare against, so they are counted
 * separately; this parser's tree for them is still printed under `--verbose`,
 * because recovery is a requirement here and not a bonus.
 *
 * No framework, no booted IDE: plain Java, run on the JDK that
 * `build-offline.ps1` uses.
 *
 *   java -cp <plugin classes> AstDiff <repo-root> [--only-diffs] [--verbose]
 *   java -cp <plugin classes> AstDiff <repo-root> --single tests/build/hello.vel
 */
public final class AstDiff {

    /** Files larger than this are reported and skipped rather than timed out. */
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    /** Exit codes that mean the compiler itself died rather than refused a file. */
    private static final Set<Integer> CRASH_CODES = Set.of(
            -1073741675, // 0xC0000095 STATUS_INTEGER_OVERFLOW
            -1073741819, // 0xC0000005 STATUS_ACCESS_VIOLATION
            -1073741571  // 0xC00000FD STATUS_STACK_OVERFLOW
    );

    private final List<String> corpus = new ArrayList<>();
    /** rel -> the manifest line that named it, for corpus entries that are absent. */
    private final Map<String, String> provenance = new LinkedHashMap<>();
    private Path repoRoot;
    private Path vm;
    private Path scratch;
    private boolean onlyDiffs;
    private boolean verbose;
    private boolean shape;
    private String single;
    private String vmPath;
    private boolean selftest;

    public static void main(String[] args) throws Exception {
        AstDiff tool = new AstDiff();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--only-diffs")) {
                tool.onlyDiffs = true;
            } else if (a.equals("--verbose")) {
                tool.verbose = true;
            } else if (a.equals("--shape")) {
                // Print this parser's own tree (kind and token range per node)
                // instead of the comparison.  This is how a difference is
                // investigated: the compiler's printer cannot lie about what it
                // was handed, but it also cannot show what the *parser* built.
                tool.shape = true;
            } else if (a.equals("--selftest")) {
                // Prove the comparison can reject a wrong tree, before trusting a
                // run of it that reports no differences.
                tool.selftest = true;
            } else if (a.equals("--single")) {
                tool.single = args[++i];
            } else if (a.equals("--vm")) {
                // The compiler to compare against.  The differential runs against a
                // frozen copy rather than selfhost\build\vm.exe in place: another
                // agent rebuilds that file while this runs, and a comparison whose
                // reference changes underneath it proves nothing.
                tool.vmPath = args[++i];
            } else {
                rest.add(a);
            }
        }
        Path root = rest.isEmpty()
                ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        tool.run(root);
    }

    private void run(Path root) throws Exception {
        repoRoot = root;
        // VELA_SELF is the repository root for everything that drives the
        // compiler, and it has to be absolute.
        String self = System.getenv("VELA_SELF");
        if (self != null && !self.isEmpty()) {
            repoRoot = Paths.get(self).toAbsolutePath();
        }
        vm = repoRoot.resolve("selfhost").resolve("build").resolve("vm.exe");
        if (vmPath != null) {
            vm = Paths.get(vmPath).toAbsolutePath();
        }
        if (!Files.isRegularFile(vm)) {
            System.out.println("cannot find the compiler: " + vm);
            System.out.println("pass the repository root as the first argument"
                    + " (or set VELA_SELF)");
            System.exit(2);
            return;
        }
        scratch = Files.createTempDirectory("vela-astdiff");
        Files.createDirectories(scratch.resolve("out"));
        Files.createDirectories(scratch.resolve("err"));

        if (single != null) {
            corpus.add(single.replace('\\', '/'));
        } else {
            collectCorpus();
        }

        System.out.println("compiler : " + vm);
        System.out.println("repo     : " + repoRoot);
        System.out.println("compiler size   : " + Files.size(vm) + " bytes");
        System.out.println("compiler sha256 : " + sha256(vm));
        System.out.println();
        System.out.println("== what is compared, and under what normalisation ==");
        System.out.println("  both sides: the compiler's own dump format, one node per line, indented by");
        System.out.println("  depth (selfhost/parts/dump.vel, d_node/d_body/d_chain).  The plugin's side is");
        System.out.println("  VelaSyntaxDump.dump, a transcription of that printer, run over the plugin");
        System.out.println("  parser's tree.");
        System.out.println("  comparison: exact string equality of the whole dump, line for line, so the");
        System.out.println("  nesting and the indentation are compared and not just the number of nodes.");
        System.out.println("  normalisation, and it is the only one: CRLF and lone CR in the compiler's");
        System.out.println("  stdout become LF (its writer runs in text mode; the source's own line");
        System.out.println("  endings are what the parser sees).  Nothing else is normalised: no node is");
        System.out.println("  dropped, no whitespace is skipped, no comment is ignored.  Comments and");
        System.out.println("  blank lines are absent from BOTH sides because the compiler's printer emits");
        System.out.println("  no line for them either -- this parser keeps them as tokens for the editor's");
        System.out.println("  benefit, but does not print them, so nothing is being loosened here.");
        System.out.println("  anti-tautology guard: an accepted file whose compiler dump is empty or does");
        System.out.println("  not begin with `module` is reported as SUSPECT, never as identical, so two");
        System.out.println("  empty strings cannot pass for agreement.");

        if (shape) {
            for (String rel : corpus) {
                Path p = repoRoot.resolve(rel);
                if (!Files.isRegularFile(p)) {
                    System.out.println(rel + ": not found");
                    continue;
                }
                String src = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                VelaSyntaxTree tree = VelaSyntaxParser.parse(src);
                System.out.println("==== " + rel);
                System.out.println("     complete=" + tree.complete
                        + " problems=" + tree.problems.size());
                for (VelaSyntaxProblem pr : tree.problems) {
                    System.out.println("     " + pr.message
                            + "  @" + pr.start + ".." + pr.end);
                }
                System.out.print(VelaSyntaxDump.INSTANCE.dumpShape(tree));
            }
            return;
        }

        if (selftest) {
            selfTest();
        }

        int identical = 0;
        int different = 0;
        int refused = 0;
        int refusedAndAgreed = 0;
        int crashed = 0;
        int missing = 0;
        int tooLarge = 0;
        int suspect = 0;
        long nodesCompared = 0;
        List<String> differing = new ArrayList<>();
        List<String> firstDiffs = new ArrayList<>();
        StringBuilder table = new StringBuilder(16384);
        StringBuilder detail = new StringBuilder(65536);
        table.append(pad("file", 58)).append(pad("nodes", 8)).append("  verdict\n");
        table.append("-".repeat(58)).append("  ").append("-".repeat(6)).append("  ")
                .append("-".repeat(40)).append('\n');

        for (String rel : corpus) {
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p)) {
                // A corpus entry that names a file which is not there is a defect of the
                // *corpus*, not of the plugin -- and it is named with its provenance so
                // whoever owns that manifest can fix it.  Measured today:
                // `tests/cases.txt:174` names `tests/build/lexer_error.vel`, which does
                // not exist, so this harness was FAIL on the tree with zero tree
                // differences.  That is why it gets its own category and its own exit
                // code: "the plugin disagrees with the compiler" and "the manifest
                // points at nothing" must not be the same red.
                String from = provenance.get(rel);
                table.append(pad(rel, 58)).append(pad("-", 8)).append("  MISSING")
                        .append(from == null ? "" : " (named by " + from + ")").append('\n');
                missing++;
                differing.add(rel);
                firstDiffs.add(rel + ": file not found"
                        + (from == null ? "" : ", named by " + from));
                continue;
            }
            long size = Files.size(p);
            if (size > MAX_FILE_BYTES) {
                table.append(pad(rel, 58)).append(pad("-", 8))
                        .append("  SKIPPED (too large: ").append(size).append(")\n");
                tooLarge++;
                continue;
            }
            // ISO-8859-1: one char per byte, so the parser's offsets and the
            // compiler's byte offsets are the same number.  That matters even
            // for a file whose comments contain non-ASCII characters -- which
            // most of this corpus's larger files do.  Nothing is ever decoded
            // as UTF-8: a comment's em dash becomes two characters, the scanner
            // throws them away inside the comment, and a non-ASCII *byte* in a
            // string literal is one character here and one byte there, which is
            // exactly what `d_str` escapes.
            byte[] raw = Files.readAllBytes(p);
            String src = new String(raw, StandardCharsets.ISO_8859_1);

            VelaSyntaxTree tree = VelaSyntaxParser.parse(src);
            String mine = VelaSyntaxDump.INSTANCE.dump(tree);
            CompilerRun run = runCompiler(rel);

            int mineNodes = nodeCount(mine);
            String nodesCell;
            String verdict;
            if (run.exit == 0) {
                int theirNodes = nodeCount(run.out);
                nodesCell = String.valueOf(theirNodes);
                // The guard that makes "identical" mean something: two dumps that
                // are equal and empty would otherwise be reported as agreement.
                if (!run.out.startsWith("module") || theirNodes == 0 || mineNodes == 0) {
                    verdict = "SUSPECT (a dump is empty or does not start with `module`:"
                            + " the comparison would prove nothing)";
                    suspect++;
                    differing.add(rel);
                    firstDiffs.add(rel + ": mine starts `" + firstLine(mine)
                            + "`, compiler starts `" + firstLine(run.out) + "`");
                } else if (mine.equals(run.out)) {
                    verdict = "match";
                    identical++;
                    nodesCompared += theirNodes;
                } else {
                    verdict = "DIFF (mine " + mineNodes + " nodes vs compiler " + theirNodes + ")";
                    different++;
                    differing.add(rel);
                    firstDiffs.add(firstDiff(rel, mine, run.out));
                }
            } else if (CRASH_CODES.contains(run.exit)) {
                nodesCell = String.valueOf(mineNodes);
                verdict = "compiler crashed (exit " + run.exit + ")";
                crashed++;
            } else {
                nodesCell = String.valueOf(mineNodes);
                verdict = "compiler refused (exit " + run.exit + ")"
                        + (tree.complete ? " -- but this parser accepted it" : "");
                refused++;
                if (!tree.complete) refusedAndAgreed++;
            }
            table.append(pad(rel, 58)).append(pad(nodesCell, 8)).append("  ").append(verdict).append('\n');

            if (verbose && !verdict.equals("match")) {
                detail.append("\n==== ").append(rel).append("  [").append(verdict).append("]\n");
                if (!tree.complete) {
                    detail.append("-- this parser reported ").append(tree.problems.size())
                            .append(" problem(s):\n");
                    for (VelaSyntaxProblem pr : tree.problems) {
                        detail.append("   ").append(pr.message)
                                .append("  @").append(pr.start).append("..").append(pr.end).append('\n');
                    }
                }
                detail.append("-- this parser's tree:\n");
                appendIndented(detail, mine);
                if (run.exit == 0) {
                    detail.append("-- the compiler's tree:\n");
                    appendIndented(detail, run.out);
                } else {
                    detail.append("-- the compiler produced no tree (exit ").append(run.exit).append(")\n");
                    if (!run.err.isEmpty()) {
                        detail.append("-- the compiler said:\n");
                        appendIndented(detail, run.err);
                    }
                }
            }
        }

        System.out.println();
        System.out.println("== corpus ==");
        System.out.println(table);
        if (differing.size() > 0) {
            System.out.println("== first line that differs, per file that is not a match ==");
            for (String s : firstDiffs) System.out.println("  " + s);
            System.out.println();
        }
        System.out.println("== totals ==");
        System.out.println("  files in the corpus       : " + corpus.size());
        System.out.println("  match (tree identical)    : " + identical);
        System.out.println("  nodes compared, in detail : " + nodesCompared + " node line(s) over "
                + identical + " file(s)");
        System.out.println("  different                 : " + different);
        System.out.println("  suspect (dump proves nothing): " + suspect);
        System.out.println("  missing                   : " + missing);
        System.out.println("  compiler refused          : " + refused
                + " (of which this parser also refused: " + refusedAndAgreed + ", accepted: "
                + (refused - refusedAndAgreed) + ")");
        System.out.println("  compiler crashed          : " + crashed);
        System.out.println("  files that are not a match: " + differing);
        System.out.println("  VERDICT                   : "
                + (different == 0 && missing == 0 && suspect == 0 ? "PASS" : "FAIL"));
        Coverage cov = new Coverage()
                // Declared even when zero: `ran + skipped` must reconcile with the
                // corpus, and a category that is missing from the line cannot be told
                // apart from a category that was never counted.
                .category("compiler-refused")
                .category("too-large")
                .defectCategory("missing-corpus-file")
                .defectCategory("compiler-crashed")
                .ran(identical)
                .skipped("compiler-refused", refused)
                .skipped("too-large", tooLarge)
                .defect("missing-corpus-file", missing)
                .defect("compiler-crashed", crashed)
                .wrong(different + suspect);
        cov.print();
        if (cov.accounted() != corpus.size()) {
            System.out.println("  NOTE: ran + skipped = " + cov.accounted()
                    + " but the corpus has " + corpus.size()
                    + " entry/entries: a file was neither compared nor categorised.");
        }
        System.out.println();
        if (verbose) {
            System.out.println("== detail ==");
            System.out.println(detail);
        }
        // The verdict used to be a sentence and nothing else, so FAIL exited 0 and a
        // build could not tell it from a clean run.  Now: 1 is "the plugin's tree
        // differs from the compiler's", 3 is "the corpus or the compiler is at fault",
        // 0 is a pass.
        int exit = (different > 0 || suspect > 0) ? 1 : (cov.hasDefect() ? 3 : 0);
        System.exit(exit);
    }

    // ---------------------------------------------------------------- self-test

    /**
     * Can this comparison fail?
     *
     * A differential that reports "match" on everything is worth nothing until it
     * has been shown to reject a wrong tree, and the one thing that would make it
     * silently toothless is a comparison that has been loosened (whitespace
     * ignored, nodes dropped) until it always agrees.  So: take the files the
     * compiler accepts, compare each one's plugin tree against its *own* compiler
     * tree (must be equal), and against the *next* file's compiler tree (must be
     * different, with a real first differing line).  A cross pair whose two files
     * genuinely have the same tree is reported as an unsuitable pair rather than
     * counted as a missed detection.
     */
    private void selfTest() throws Exception {
        final int want = 6;
        List<String> rels = new ArrayList<>();
        List<String> mineDumps = new ArrayList<>();
        List<String> theirDumps = new ArrayList<>();
        for (String rel : corpus) {
            if (rels.size() >= want) break;
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p) || Files.size(p) > MAX_FILE_BYTES) continue;
            String src = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            VelaSyntaxTree tree = VelaSyntaxParser.parse(src);
            CompilerRun run = runCompiler(rel);
            if (run.exit != 0 || run.out.isEmpty()) continue;
            rels.add(rel);
            mineDumps.add(VelaSyntaxDump.INSTANCE.dump(tree));
            theirDumps.add(run.out);
        }

        System.out.println();
        System.out.println("== self-test: the comparison must be able to fail ==");
        if (rels.size() < 3) {
            System.out.println("  NOT RUN: only " + rels.size()
                    + " accepted file(s) were available to cross");
            System.out.println("  VERDICT: the harness was not shown to fail");
            return;
        }
        int sameOk = 0;
        int crossDetected = 0;
        int skipped = 0;
        int missed = 0;
        for (int i = 0; i < rels.size(); i++) {
            if (mineDumps.get(i).equals(theirDumps.get(i))) {
                sameOk++;
            } else {
                System.out.println("  same-file pair FAILED to match: " + rels.get(i));
            }
            int j = (i + 1) % rels.size();
            if (theirDumps.get(i).equals(theirDumps.get(j))) {
                skipped++;
                System.out.println("  cross pair skipped (both files have the same compiler tree): "
                        + rels.get(i) + " / " + rels.get(j));
                continue;
            }
            if (mineDumps.get(i).equals(theirDumps.get(j))) {
                missed++;
                System.out.println("  cross pair NOT detected: mine(" + rels.get(i)
                        + ") == compiler(" + rels.get(j) + ") -- the comparison is toothless");
            } else {
                crossDetected++;
                System.out.println("  cross pair detected as different: mine(" + rels.get(i)
                        + ") vs compiler(" + rels.get(j) + ")");
                System.out.println("      " + firstDiff(rels.get(i), mineDumps.get(i), theirDumps.get(j)));
            }
        }
        System.out.println("  same-file pairs compared : " + rels.size() + ", agreed: " + sameOk);
        System.out.println("  cross pairs tested       : " + (crossDetected + missed + skipped)
                + ", detected as different: " + crossDetected + ", unsuitable: " + skipped
                + ", missed: " + missed);
        System.out.println("  VERDICT: " + (missed == 0 && sameOk == rels.size()
                ? "the comparison rejects a wrong tree, and accepts the right one"
                : "THE COMPARISON IS NOT TRUSTWORTHY"));
    }

    // ------------------------------------------------------------------ corpus

    private void collectCorpus() throws IOException {
        Set<String> seen = new LinkedHashSet<>();

        Path cases = repoRoot.resolve("tests").resolve("cases.txt");
        if (Files.isRegularFile(cases)) {
            int lineNo = 0;
            for (String line : Files.readAllLines(cases, StandardCharsets.UTF_8)) {
                lineNo++;
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                // <mode> <name> <path>, where mode may carry a suffix
                // (`refuse-interp`) and the path is the last field.
                String[] parts = t.split("\\s+");
                if (parts.length < 3) continue;
                if (parts[2].endsWith(".vel")) {
                    seen.add(parts[2]);
                    // Where a path came from, so a corpus entry that names a file which
                    // is not on disk can be reported with the line to fix rather than as
                    // an anonymous MISSING row.
                    provenance.putIfAbsent(parts[2], "tests/cases.txt:" + lineNo);
                }
            }
        } else {
            System.out.println("note: no tests/cases.txt under " + repoRoot);
        }

        // selfhost/parts is the largest real corpus the language has: the compiler's
        // own sources, written by the language's owner, not written to exercise a
        // parser.  It is included for exactly that reason -- a parser that agrees
        // with the compiler on `examples/hello.vel` and disagrees on `eval.vel` has
        // not been shown to agree with anything.
        //
        // `selfhost/vm.vel` is left out on purpose: it is the generated 400 KB
        // concatenation of these parts, rewritten by another agent's build while
        // this runs, so a difference in it would be a difference against a moving
        // file.  The parts are the source of that file and are compared here.
        for (String dir : new String[]{"selfhost/parts", "tests/build", "tests/probes",
                                       "examples", "bench", "ide-demo"}) {
            Path d = repoRoot.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            List<Path> found = new ArrayList<>();
            Files.walk(d)
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vel"))
                    .forEach(found::add);
            Collections.sort(found);
            for (Path f : found) seen.add(rel(f));
        }

        corpus.addAll(seen);
    }

    private String rel(Path p) {
        return repoRoot.relativize(p).toString().replace('\\', '/');
    }

    // ------------------------------------------------------------- the compiler

    private static final class CompilerRun {
        int exit;
        String out = "";
        String err = "";
    }

    /**
     * `vm.exe parse <file>`, with its streams written to files.
     *
     * Not through a pipe: this machine's shell turns a native command's stderr
     * into a terminating error when it is captured in a pipeline, and the
     * compiler writes its diagnostics there on purpose.
     */
    private CompilerRun runCompiler(String rel) throws Exception {
        CompilerRun r = new CompilerRun();
        String safe = rel.replace('/', '_');
        Path out = scratch.resolve("out").resolve(safe + ".out");
        Path err = scratch.resolve("err").resolve(safe + ".err");
        ProcessBuilder pb = new ProcessBuilder(vm.toString(), "parse", rel);
        pb.directory(repoRoot.toFile());
        pb.redirectOutput(out.toFile());
        pb.redirectError(err.toFile());
        Process proc = pb.start();
        r.exit = proc.waitFor();
        r.out = readIfPresent(out);
        r.err = readIfPresent(err);
        return r;
    }

    private static String readIfPresent(Path p) throws IOException {
        if (!Files.isRegularFile(p)) return "";
        byte[] raw = Files.readAllBytes(p);
        String text;
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            text = new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        } else if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF) {
            text = new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16BE);
        } else {
            text = new String(raw, StandardCharsets.UTF_8);
        }
        return normaliseNewlines(text);
    }

    /**
     * `\r\n` (and a lone `\r`) become `\n`.
     *
     * The compiler's own writer runs in text mode, so its stdout arrives with
     * Windows newlines; the parser's printer works on the file's characters,
     * which are the ones the source actually has.  Comparing them without this
     * would report a difference on every single line of every single file,
     * which is the kind of result that makes a harness worthless.
     */
    private static String normaliseNewlines(String s) {
        if (s.indexOf('\r') < 0) return s;
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ---------------------------------------------------------------- comparing

    private static String firstDiff(String rel, String mine, String theirs) {
        String[] a = mine.split("\n", -1);
        String[] b = theirs.split("\n", -1);
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            String x = i < a.length ? a[i] : "<end of tree>";
            String y = i < b.length ? b[i] : "<end of tree>";
            if (!x.equals(y)) {
                return rel + ": line " + (i + 1)
                        + "\n      this parser: " + quote(x)
                        + "\n      compiler   : " + quote(y);
            }
        }
        return rel + ": no differing line (identical up to concatenation)";
    }

    private static String quote(String s) {
        return s.isEmpty() ? "(blank)" : s;
    }

    /** How many node lines a dump has: the size of the tree, not of its text. */
    private static int nodeCount(String dump) {
        if (dump.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < dump.length(); i++) {
            if (dump.charAt(i) == '\n') n++;
        }
        // A dump ends with exactly one newline, so the last line is not a node.
        if (dump.charAt(dump.length() - 1) == '\n') n--;
        return n + 1;
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private static void appendIndented(StringBuilder sb, String text) {
        if (text.isEmpty()) {
            sb.append("   (empty)\n");
            return;
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) break;
            sb.append("   ").append(lines[i]).append('\n');
        }
    }

    private static String pad(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    /**
     * The hex SHA-256 of a file: the version of the compiler this run compared
     * against.  Printed, not asserted -- the point is that the number in the
     * report can be checked against the file that produced it.
     */
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
            return "(could not hash: " + e + ")";
        }
    }

    private AstDiff() {
    }
}
