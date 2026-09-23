import com.intellij.lexer.Lexer;
import dev.vela.plugin.VelaLexer;
import dev.vela.plugin.VelaNodeKind;
import dev.vela.plugin.VelaReferences;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaTok;
import dev.vela.plugin.VelaTokKind;
import dev.vela.plugin.VelaTokenTypes;
import dev.vela.plugin.VelaUsageSearch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The completeness table for find-usages and rename, with the compiler as the oracle.
 *
 * WHAT THE ORACLE IS, AND WHY IT IS NOT "DOES THE RENAMED FILE COMPILE"
 *
 * Two questions have one authority, and it is the compiler:
 *
 *   find usages : which occurrences in this file are uses of declaration D?
 *   rename      : which characters must a rename of D write, and no others?
 *
 * Both are answered here by *asking the compiler about one occurrence at a time*.
 * `vm.exe check` prints only a line number (`at <file>:<line>`), never a column, so
 * "which occurrence on that line is broken" cannot be read off a diagnostic.  The
 * protocol therefore never reads a diagnostic to decide what it means; it uses the
 * compiler as a difference oracle:
 *
 *   C'  the candidate occurrences: every occurrence of D's name in the file that is
 *       not itself a declaration's name, restricted to the lines the compiler
 *       complained about below.  This is the harness's only contribution -- where the
 *       candidates are -- and it is read from the plugin's own lexer, the token set
 *       `VelaReferenceContributor` registers its provider over.
 *
 *   lines  rename D's declaration only (its name becomes `namezzq`) and run `check`.
 *       The compiler now complains about the first line that bound the old name;
 *       rename the first remaining occurrence of the name on that line and run again;
 *       repeat until `check` exits 0.  The set of lines repaired along the way is a
 *       *verified* set: the file compiles again at the end, so no bound use of the
 *       old name is left anywhere -- which is what makes "every bound use is on one
 *       of these lines" a fact rather than a claim.
 *
 *   basis  rename D's declaration AND every candidate in C', and require the file to
 *       compile.  If it does not, the declaration is NOT judged (the candidate rules
 *       did not cover this name) -- counted, never rounded away.
 *
 *   membership  for each candidate o, rename D's declaration and every candidate
 *       EXCEPT o, and run `check`.  The file is refused exactly when o had to be
 *       renamed -- which is exactly when the compiler binds the old name at o to D.
 *       The proof is one line long: the basis compiles, so renaming candidates
 *       introduces no error of its own; every remaining error can only be a name that
 *       no longer resolves, and the only name that can fail to resolve is the one
 *       occurrence left unrenamed.  So the refused set IS the compiler's binding set
 *       for D, offset by offset.
 *
 * WHY NOT THE CHEAPER "RENAME THE PLUGIN'S SET AND SEE IF IT COMPILES"
 *
 * Because it is not the property that matters, and this is the defect the whole tool
 * exists to catch.  Renaming a variable that belongs to a *different* declaration of
 * the same name -- another scope's `n` -- leaves the program compiling as long as the
 * two have the same type.  "It compiles" is therefore a strictly weaker statement than
 * "every reference the compiler binds moved and nothing else did", and a harness that
 * measured the weaker one would report a wrong rename as clean.  `--explain` prints
 * the compiler's own verdict on the plugin's rename for exactly the rows that are
 * wrong, so the difference between the two questions is visible in the output.
 *
 * WHAT THE PLUGIN'S ANSWER IS
 *
 * The platform's find-usages search and its rename both walk identifier leaves and ask
 * each one where it resolves; the plugin's half of that is
 * `VelaReferences.referenceTargetAt`, and every answer below is that function, asked
 * once per identifier leaf, exactly as `ReferencesSearch` asks it:
 *
 *   claimed(D) = { leaf.start : referenceTargetAt(text, leaf) == D.start }
 *
 * For a rename, the platform renames the declaration element and calls
 * `handleElementRename` on every reference it found, so the characters a rename writes
 * are `{D.start} U claimed(D)`.  The tool rebuilds that text itself (a replacement of
 * one range with one string, which is what `document.replaceString` does) and hands it
 * to the compiler: that is the end-to-end column, and it is checked for every judged
 * declaration, not only for the ones that already differ.
 *
 * THE COVERAGE TRIPLE
 *
 * The unit of `ran`/`skipped` is the DECLARATION: one judged declaration is one
 * find-usages answer plus one rename.  The usage-level numbers are printed beside it
 * (`bound uses`, `claimed uses`, `missed`, `extra`), because a declaration with forty
 * uses is not one fact.  Every reason a declaration is not judged has its own counter
 * and is printed even when it is zero.
 *
 * Usage:
 *   java -cp <plugin classes> RenameOracle <repo-root> [--vm <vm.exe>] [--cache <file>]
 *       [--rebuild-oracle] [--single <file>] [--max-bytes <n>] [--threads <n>]
 *       [--max-candidates <n>] [--show] [--explain] [--debug] [--control]
 *
 * Exit codes: 0 = no finding, 1 = a wrong answer (missed / extra / unsearchable /
 * mislabelled), 3 = the harness or the corpus is at fault (a crashed file, a
 * self-contradiction, a corpus entry that is not there).
 */
public final class RenameOracle {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int THREADS = 8;
    /** Above this many candidates for one declaration the per-candidate sweep is skipped. */
    private static final int MAX_CANDIDATES = 64;
    private static final int REPAIR_GUARD = 64;
    private static final int MAX_WRONG_ROWS = 60;
    private static final int MAX_SKIP_ROWS = 30;
    private static final int MAX_UNPROVABLE_ROWS = 30;

    private Path repoRoot;
    private Path vm;
    private Path cache;
    private boolean rebuild;
    private boolean debug;
    private boolean show;
    private boolean explain;
    private boolean control;
    private String single;
    private int threads = THREADS;
    private long maxBytes = MAX_FILE_BYTES;
    private int maxCandidates = MAX_CANDIDATES;
    private String vmSha = "";
    private final List<String> corpus = new ArrayList<String>();

    /** The nine reasons a declaration is not judged.  Declared here so a zero is printed. */
    private static final String[] SKIP_KINDS = {
        "compiler-refused",          // `vm.exe check` refuses the file itself
        "too-large",                 // over --max-bytes
        "missing-corpus-file",       // a corpus entry that is not on disk
        "compiler-silent",           // renaming the declaration changes nothing `check` notices
        "not-attributable",          // the compiler's complaint is about the program, not a use
        "unverified-lines",          // the repair loop never came back to exit 0
        "unverifiable-basis",        // renaming D and every candidate still does not compile
        "too-many-candidates",       // more than --max-candidates occurrences of the name
        "new-name-refused",          // the write-back's gate refuses the name a rename writes
        "crashed",                   // this harness threw on the file
        "contradiction",             // the tool's own checks disagree with each other
    };

    public static void main(String[] args) throws Exception {
        RenameOracle tool = new RenameOracle();
        List<String> rest = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--vm")) tool.vm = Paths.get(args[++i]).toAbsolutePath();
            else if (a.equals("--cache")) tool.cache = Paths.get(args[++i]).toAbsolutePath();
            else if (a.equals("--rebuild-oracle")) tool.rebuild = true;
            else if (a.equals("--debug")) tool.debug = true;
            else if (a.equals("--show")) tool.show = true;
            else if (a.equals("--explain")) tool.explain = true;
            else if (a.equals("--control")) tool.control = true;
            else if (a.equals("--single")) tool.single = args[++i];
            else if (a.equals("--max-bytes")) tool.maxBytes = Long.parseLong(args[++i]);
            else if (a.equals("--threads")) tool.threads = Integer.parseInt(args[++i]);
            else if (a.equals("--max-candidates")) tool.maxCandidates = Integer.parseInt(args[++i]);
            else rest.add(a);
        }
        tool.repoRoot = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        if (tool.cache == null) {
            tool.cache = tool.repoRoot.resolve("idea-plugin").resolve("build").resolve("tools")
                    .resolve("harness").resolve("rename-oracle.txt");
        }
        tool.run();
    }

    // ------------------------------------------------------------------- the run

    private int compilerRuns;
    private int cacheHits;
    private long elapsedMs;

    private void run() throws Exception {
        if (vm == null) vm = repoRoot.resolve("selfhost").resolve("build").resolve("vm.exe");
        if (!Files.isRegularFile(vm)) {
            System.out.println("VERDICT: no compiler at " + vm + ", so nothing can be judged");
            System.out.println("COVERAGE: ran 0 / skipped 0 (" + allSkipCountersZero() + ") / wrong 0");
            System.exit(3);
        }
        vmSha = sha256(vm);
        if (single != null) corpus.add(single.replace('\\', '/'));
        else collectCorpus();

        System.out.println("== RenameOracle: find-usages and rename, against the compiler ==");
        System.out.println("oracle   : one `vm.exe check` per candidate occurrence, so which occurrence");
        System.out.println("           the compiler binds is measured rather than inferred from the line");
        System.out.println("compiler : " + vm + " (" + Files.size(vm) + " bytes, sha256 " + vmSha + ")");
        System.out.println("plugin   : VelaReferences.referenceTargetAt(text, leafStart, leafEnd), asked once");
        System.out.println("           per IDENTIFIER leaf -- the same call the reference provider makes");
        System.out.println("corpus   : " + corpus.size() + " file(s); skipped if over " + maxBytes + " bytes");
        System.out.println("threads  : " + threads);
        System.out.println("cache    : " + cache + (rebuild ? "   (--rebuild-oracle: ignored)" : ""));
        System.out.println();

        Map<String, String> cacheMap = loadCache();
        List<FileWork> works = new ArrayList<FileWork>();
        for (String rel : corpus) {
            Path p = repoRoot.resolve(rel);
            FileWork w = new FileWork(rel, p);
            if (!Files.isRegularFile(p)) {
                w.error = "not there";
            } else if (Files.size(p) > maxBytes) {
                w.error = "over " + maxBytes + " bytes";
            }
            works.add(w);
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        long start = System.currentTimeMillis();
        for (FileWork w : works) {
            futures.add(pool.submit(() -> {
                if (w.error != null) return;
                try {
                    analyse(w, cacheMap);
                } catch (Throwable t) {
                    w.error = "threw " + t;
                    w.crashed = true;
                }
            }));
        }
        // The cache is written from this thread only, one file at a time: eight threads
        // writing one file is the defect GotoOracle's `--probe-cache` was written to
        // measure, and the honest thing here is not to have that defect at all.
        for (Future<?> f : futures) {
            f.get();
            saveCache(cacheMap);
        }
        pool.shutdown();
        elapsedMs = System.currentTimeMillis() - start;

        report(works);
    }

    // --------------------------------------------------------------- a whole file

    private static final class FileWork {
        final String rel;
        final Path path;
        String text = "";
        String hash = "";
        List<Decl> decls = new ArrayList<Decl>();
        /** Every declaration name's start offset, so candidates can exclude them. */
        final Set<Integer> declStarts = new LinkedHashSet<Integer>();
        /** Every IDENTIFIER leaf: start, end. */
        final List<int[]> leaves = new ArrayList<int[]>();
        /** target declaration start -> the leaf starts that resolve to it (the plugin's table). */
        final Map<Integer, List<Integer>> claimed = new LinkedHashMap<Integer, List<Integer>>();
        final List<Row> rows = new ArrayList<Row>();
        boolean judgeable;
        boolean crashed;
        String error;
        String baseErr = "";

        FileWork(String rel, Path path) {
            this.rel = rel;
            this.path = path;
        }
    }

    private void analyse(FileWork w, Map<String, String> cacheMap) throws Exception {
        w.text = new String(Files.readAllBytes(w.path), StandardCharsets.ISO_8859_1);
        w.hash = sha256(w.path).substring(0, 16);
        VelaSyntaxTree tree = VelaSyntaxParser.parse(w.text);
        w.decls = declarations(w.text, tree);
        for (Decl d : w.decls) w.declStarts.add(d.start);
        collectLeaves(w);

        // THE PLUGIN'S TABLE.  One call per identifier leaf, which is what the platform
        // does once per leaf when it builds references for the file.
        for (int[] leaf : w.leaves) {
            Integer target = VelaReferences.INSTANCE.referenceTargetAt(w.text, leaf[0], leaf[1]);
            if (target == null) continue;
            List<Integer> uses = w.claimed.get(target);
            if (uses == null) {
                uses = new ArrayList<Integer>();
                w.claimed.put(target, uses);
            }
            uses.add(leaf[0]);
        }

        Path scratch = Files.createTempDirectory("vela-rename-oracle");
        try {
            Path original = scratch.resolve("orig.vel");
            Files.write(original, w.text.getBytes(StandardCharsets.ISO_8859_1));
            CompilerRun base = check(original);
            // An oracle that starts from a file the compiler already refuses measures
            // nothing: an error after an edit could not be attributed to the edit.
            w.judgeable = base.exit == 0;
            w.baseErr = base.err;
            if (!w.judgeable) return;
            Path current = scratch.resolve("t.vel");
            // TWO PASSES, BECAUSE ONE QUESTION NEEDS THE WHOLE FILE'S ANSWERS.
            //
            // Pass 1 asks the compiler about every declaration and builds `binderOf`: for
            // every candidate offset, which declarations the compiler binds it to.  Pass 2
            // judges the plugin against that table.  The second pass is what makes the
            // EXTRA direction decidable at all: "the plugin claims this occurrence for D,
            // and the compiler binds it to another declaration of the same name" is a fact
            // about two declarations, and without pass 1 it could not be told from "the
            // compiler never checks this occurrence" -- which is silence, not a denial.
            Map<Decl, Answer> answers = new LinkedHashMap<Decl, Answer>();
            Map<Integer, List<Decl>> binderOf = new LinkedHashMap<Integer, List<Decl>>();
            for (Decl d : w.decls) {
                Answer a = answerFor(w, d, current, cacheMap);
                answers.put(d, a);
                if (a.bound == null) continue;
                for (int o : a.bound) {
                    List<Decl> binders = binderOf.get(o);
                    if (binders == null) {
                        binders = new ArrayList<Decl>();
                        binderOf.put(o, binders);
                    }
                    binders.add(d);
                }
            }
            for (Decl d : w.decls) judge(w, d, answers.get(d), binderOf, current);
        } finally {
            deleteTree(scratch);
        }
    }

    /** One declaration's oracle answer, from the cache or measured now. */
    private static final class Answer {
        /** measured | compiler-silent | not-attributable | unverified-lines | unverifiable-basis
         *  | too-many-candidates */
        final String kind;
        /** The compiler's binding set, or null when the declaration was not measured. */
        final Set<Integer> bound;
        final String note;

        Answer(String kind, Set<Integer> bound, String note) {
            this.kind = kind;
            this.bound = bound;
            this.note = note;
        }
    }

    private Answer answerFor(FileWork w, Decl d, Path current, Map<String, String> cacheMap)
            throws Exception {
        String key = vmSha + "|" + w.hash + "|" + d.contentKey(w.text);
        String cached = rebuild ? null : cacheMap.get(key);
        String answer;
        if (cached != null) {
            cacheHits++;
            answer = cached;
        } else {
            answer = measure(w, d, current);
            cacheMap.put(key, answer);
        }
        if (answer.startsWith("S")) return new Answer("compiler-silent", null, "");
        if (answer.startsWith("X")) {
            return new Answer("too-many-candidates", null, answer.substring(2));
        }
        if (answer.startsWith("U")) {
            String kind = answer.substring(2);
            return new Answer(kind.isEmpty() ? "unverified-lines" : kind, null, "");
        }
        Set<Integer> bound = new LinkedHashSet<Integer>(
                parseCsv(between(answer, "B=", ";")));
        int at = answer.indexOf("C=");
        String candidates = at < 0 ? "" : answer.substring(at + 2);
        return new Answer("measured", bound, "candidates " + candidates);
    }

    private static final class CompilerRun {
        int exit;
        String out = "";
        String err = "";
    }

    private CompilerRun check(Path file) throws Exception {
        CompilerRun r = new CompilerRun();
        Path out = Files.createTempFile("vela-check-out", ".txt");
        Path err = Files.createTempFile("vela-check-err", ".txt");
        ProcessBuilder pb = new ProcessBuilder(vm.toString(), "check", file.toString());
        pb.redirectOutput(out.toFile());
        pb.redirectError(err.toFile());
        Process proc = pb.start();
        r.exit = proc.waitFor();
        r.out = read(out);
        r.err = read(err);
        Files.deleteIfExists(out);
        Files.deleteIfExists(err);
        compilerRuns++;
        return r;
    }

    /** One judged declaration's row, and every finding it produced. */
    private static final class Row {
        final Decl d;
        String outcome = "judged";
        Set<Integer> bound = new LinkedHashSet<Integer>();    // the compiler's answer
        Set<Integer> claim = new LinkedHashSet<Integer>();    // the plugin's answer
        final List<Integer> missed = new ArrayList<Integer>();
        final List<Integer> wrongScope = new ArrayList<Integer>();
        final List<Integer> notRequired = new ArrayList<Integer>();
        final List<String> findings = new ArrayList<String>();
        int boundUses;
        int claimedUses;
        boolean renameCompiled;
        String renameError = "";
        String note = "";

        Row(Decl d) {
            this.d = d;
        }
    }

    // ------------------------------------------------------------ one declaration

    /**
     * One declaration's verdict, in the three classes that are actually decidable.
     *
     * THE COMPILER'S SILENCE IS NOT A DENIAL, AND THIS IS WHERE THAT MATTERS MOST.
     *
     * `vm.exe check` does not resolve every position a name can stand in.  Measured on
     * this build, on `tests/build/struct_value_semantics.vel`:
     *
     *     mut a: P = P(1)      the struct's name in the annotation AND in the constructor
     *                          must both be renamed, or `check` refuses the file
     *     b: P = a             the same annotation on an immutable binding is not resolved
     *                          at all -- rename the struct and leave this `P` behind and
     *                          `check` still exits 0
     *
     * So "the compiler does not require this occurrence to be renamed" has two possible
     * causes, and only one of them is a plugin defect.  The three classes:
     *
     *   MISSED        the compiler requires the occurrence, the table has nothing there.
     *                 A defect, and a rename built from this table is refused -- which is
     *                 what the end-to-end column then shows.
     *   WRONG-SCOPE   the table claims the occurrence for D, and the compiler binds it to
     *                 a *different* declaration of the same name.  This is the defect the
     *                 whole tool exists for -- another scope's variable being renamed --
     *                 and it is decidable because pass 1 measured the other declaration
     *                 too.  Without that measurement it would be indistinguishable from
     *                 the third class.
     *   NOT-REQUIRED  the table claims it, D does not require it, and no other declaration
     *                 does either.  UNPROVABLE, counted separately and never called wrong:
     *                 the compiler never resolves this position at all, so it can say
     *                 nothing about it.  GotoOracle's unprovable member-write case is the
     *                 same fact about the same compiler.
     *
     * The fourth finding is not about a set at all: the text the platform's rename would
     * produce, handed to the compiler.  `{D.start} U claimed(D)` is exactly what
     * `document.replaceString` writes for the declaration element and every reference
     * `handleElementRename` is called on.
     */
    private void judge(FileWork w, Decl d, Answer answer, Map<Integer, List<Decl>> binderOf,
                       Path current) throws Exception {
        Row row = new Row(d);
        List<Integer> claimed = w.claimed.get(d.start);
        if (claimed != null) row.claim.addAll(claimed);
        row.claimedUses = row.claim.size();
        w.rows.add(row);

        row.outcome = answer.kind.equals("measured") ? "judged" : answer.kind;
        row.note = answer.kind.equals("measured") ? "" : answer.note;
        if (answer.bound == null) return;

        row.bound.addAll(answer.bound);
        row.boundUses = row.bound.size();
        row.missed.addAll(minus(answer.bound, row.claim));

        for (int o : row.claim) {
            if (answer.bound.contains(o)) continue;
            List<Decl> binders = binderOf.get(o);
            boolean otherScope = false;
            if (binders != null) {
                for (Decl other : binders) {
                    if (other != d && other.start != d.start) otherScope = true;
                }
            }
            if (otherScope) row.wrongScope.add(o);
            else row.notRequired.add(o);
        }

        String fresh = d.name + "zzq";
        if (!VelaReferences.INSTANCE.isWritableName(fresh)) {
            // The write-back's own gate refused the name this oracle renames to.  Not a
            // finding about find-usages -- a fact about a name that cannot be extended --
            // so it is a skip, and the sets above are not judged either.
            row.outcome = "new-name-refused";
            row.note = "`" + fresh + "` is not a name the write-back accepts";
            return;
        }
        Set<Integer> edits = new LinkedHashSet<Integer>(row.claim);
        edits.add(d.start);
        String pluginText = renameAll(w.text, edits, d.name.length(), fresh);
        Path renamed = current.resolveSibling("renamed-" + Math.abs(d.start) + ".vel");
        Files.write(renamed, pluginText.getBytes(StandardCharsets.ISO_8859_1));
        CompilerRun run = check(renamed);
        Files.deleteIfExists(renamed);
        row.renameCompiled = run.exit == 0;
        row.renameError = firstLine(run.err);

        // The two answers, and the label the results view would show for each name.
        if (!VelaUsageSearch.INSTANCE.canSearchAt(w.text, d.start, d.end)) {
            row.findings.add("NOT-SEARCHABLE: the platform would refuse Find Usages on this"
                    + " declaration's own name, so its usages cannot be looked for at all");
        }
        String wantLabel = label(d.kind);
        for (int o : row.bound) {
            if (!VelaUsageSearch.INSTANCE.canSearchAt(w.text, o, o + d.name.length())) {
                row.findings.add("NOT-SEARCHABLE: the usage on line " + lineOfOffset(w.text, o)
                        + " (offset " + o + ") cannot be searched for from its own name");
            }
            String got = VelaUsageSearch.INSTANCE.typeAt(w.text, o, o + d.name.length());
            if (!wantLabel.equals(got)) {
                row.findings.add("LABEL: the usage on line " + lineOfOffset(w.text, o) + " is a "
                        + d.kind + " but the results view would call it `" + got + "`");
            }
        }

        if (!row.missed.isEmpty()) {
            row.findings.add("MISSED " + row.missed.size() + " bound use(s): the compiler binds "
                    + offsets(w.text, row.missed) + " to this " + d.kind + " and the reference"
                    + " table has nothing there");
        }
        if (!row.wrongScope.isEmpty()) {
            row.findings.add("WRONG-SCOPE " + row.wrongScope.size() + " reference(s): the table"
                    + " claims " + offsets(w.text, row.wrongScope) + " for this " + d.kind
                    + ", and the compiler binds each of them to a DIFFERENT declaration of the"
                    + " same name -- a rename would rewrite another scope's variable");
        }
        if (!row.renameCompiled) {
            row.findings.add("RENAME-REFUSED: renaming this declaration and every reference the"
                    + " table has produces a program the compiler refuses: " + row.renameError);
        }
        // THE TOOL'S OWN CONTRADICTION, counted apart from a plugin defect.  With no missed
        // use and no wrong-scope claim, the plugin's text renames exactly the compiler's
        // binding set, which the basis guard already proved compiles; a refusal here would
        // mean this harness built the text wrongly.
        if (row.missed.isEmpty() && row.wrongScope.isEmpty() && !row.renameCompiled) {
            row.outcome = "contradiction";
        }
    }

    /**
     * The compiler's binding set for one declaration, offset by offset, verified.
     *
     * Returns the cache's encoding of the answer:
     *   `B=<csv>;C=<n>`  measured: the bound offsets, and how many candidates were tested
     *   `S`              renaming the declaration changes nothing `check` notices
     *   `U:<reason>`     not judged, with the reason in the same word list as the counters
     *   `X:<count>`      too many candidate occurrences
     */
    private String measure(FileWork w, Decl d, Path current) throws Exception {
        String fresh = d.name + "zzq";
        // 1. renaming the declaration only.  A file that still compiles has told the
        // compiler nothing about this name: a member access this build does not resolve,
        // or a declaration with no uses.  GotoOracle calls this case `invisible-member`.
        String onlyDecl = renameAll(w.text, Collections.singleton(d.start), d.name.length(), fresh);
        Files.write(current, onlyDecl.getBytes(StandardCharsets.ISO_8859_1));
        CompilerRun run = check(current);
        if (run.exit == 0) return "S";

        // 2. the repair loop: the verified set of lines.  Which line the compiler names is
        // read; what its message *means* is not.
        Set<Integer> lines = new LinkedHashSet<Integer>();
        String text = onlyDecl;
        boolean verified = false;
        for (int guard = 0; guard < REPAIR_GUARD; guard++) {
            Integer line = reportedLine(run.err);
            if (line == null) break;
            // A whole-file error, not one about a use: renaming `main` gives "no 'main'
            // function", whose position is the `def main` line itself.  Nothing can be
            // attributed to an occurrence when the complaint is about the program, so the
            // declaration is not judged rather than guessed at.
            if (!lineHasWord(text, line, d.name)) {
                return "U:not-attributable";
            }
            lines.add(line);
            String next = renameFirstUseOnLine(text, line, d.name, fresh);
            if (next == null || next.equals(text)) break;
            text = next;
            Files.write(current, text.getBytes(StandardCharsets.ISO_8859_1));
            run = check(current);
            if (run.exit == 0) {
                verified = true;
                break;
            }
        }
        if (!verified) return "U:unverified-lines";

        // 3. the candidates: occurrences of the name on those lines that are not a
        // declaration's name.  A declaration of the same name is a *different* thing and
        // renaming it would be the very mistake this tool measures.
        List<Integer> candidates = new ArrayList<Integer>();
        for (int[] leaf : w.leaves) {
            if (leaf[1] - leaf[0] != d.name.length()) continue;
            if (!w.text.regionMatches(leaf[0], d.name, 0, d.name.length())) continue;
            if (w.declStarts.contains(leaf[0])) continue;
            if (!lines.contains(lineOfOffset(w.text, leaf[0]))) continue;
            candidates.add(leaf[0]);
        }
        if (candidates.size() > maxCandidates) {
            return "X:" + candidates.size() + " occurrences on " + lines.size() + " line(s)";
        }

        // 4. the basis: the declaration and every candidate renamed must compile.  This is
        // what makes step 5 a measurement: with the basis accepted, renaming candidates
        // introduces no error of its own, so a refusal can only be an unrenamed bound use.
        Set<Integer> all = new LinkedHashSet<Integer>(candidates);
        all.add(d.start);
        Files.write(current, renameAll(w.text, all, d.name.length(), fresh)
                .getBytes(StandardCharsets.ISO_8859_1));
        if (check(current).exit != 0) return "U:unverifiable-basis";

        // 5. one candidate at a time: the file is refused exactly when that occurrence had
        // to be renamed, i.e. when the compiler binds the old name there to this declaration.
        List<Integer> bound = new ArrayList<Integer>();
        for (int o : candidates) {
            Set<Integer> rest = new LinkedHashSet<Integer>(candidates);
            rest.remove(o);
            rest.add(d.start);
            Files.write(current, renameAll(w.text, rest, d.name.length(), fresh)
                    .getBytes(StandardCharsets.ISO_8859_1));
            if (check(current).exit != 0) bound.add(o);
        }
        Collections.sort(bound);
        StringBuilder key = new StringBuilder("B=");
        for (int i = 0; i < bound.size(); i++) {
            if (i > 0) key.append(',');
            key.append(bound.get(i));
        }
        key.append(";C=").append(candidates.size());
        return key.toString();
    }

    // ------------------------------------------------------------------ the report

    private void report(List<FileWork> works) throws Exception {
        int exit = 0;
        Map<String, long[]> byKind = new TreeMap<String, long[]>();
        Map<String, Integer> skips = new LinkedHashMap<String, Integer>();
        for (String k : SKIP_KINDS) skips.put(k, 0);
        List<String> wrong = new ArrayList<String>();
        List<String> skippedRows = new ArrayList<String>();
        List<String> unprovableRows = new ArrayList<String>();

        long judged = 0;
        long wrongDecls = 0;
        long boundUses = 0;
        long claimedUses = 0;
        long missed = 0;
        long wrongScope = 0;
        long unprovable = 0;
        long renameOk = 0;
        long crashedFiles = 0;
        long missingFiles = 0;

        if (show) {
            System.out.println("== every declaration: file, line, kind, name, bound uses, claimed"
                    + " uses, verdict ==");
        }
        for (FileWork w : works) {
            if (w.error != null) {
                String kind = w.error.equals("not there") ? "missing-corpus-file"
                        : (w.error.startsWith("over ") ? "too-large" : "crashed");
                if (kind.equals("crashed")) crashedFiles++;
                if (kind.equals("missing-corpus-file")) missingFiles++;
                skips.put(kind, skips.get(kind) + 1);
                if (skippedRows.size() < MAX_SKIP_ROWS) {
                    skippedRows.add("  " + w.rel + ": no declaration judged (" + w.error + ")");
                }
                continue;
            }
            if (!w.judgeable) {
                // Every declaration in a refused file is one skipped unit: the oracle cannot
                // say anything about a file the compiler will not read.
                skips.put("compiler-refused", skips.get("compiler-refused") + w.decls.size());
                if (skippedRows.size() < MAX_SKIP_ROWS) {
                    skippedRows.add("  " + w.rel + ": `vm.exe check` refuses this file, so its "
                            + w.decls.size() + " declaration(s) are not judged: "
                            + firstLine(w.baseErr));
                }
                continue;
            }
            for (Row r : w.rows) {
                long[] row = byKind.get(r.d.kind);
                if (row == null) {
                    row = new long[6];   // judged, bound, claimed, missed, wrong-scope, wrong
                    byKind.put(r.d.kind, row);
                }
                if (!r.outcome.equals("judged")) {
                    String k = skips.containsKey(r.outcome) ? r.outcome : "crashed";
                    skips.put(k, skips.get(k) + 1);
                    if (skippedRows.size() < MAX_SKIP_ROWS) {
                        skippedRows.add("  " + w.rel + ":" + r.d.line + " `" + r.d.name + "` ("
                                + r.d.kind + "): not judged [" + r.outcome + "]"
                                + (r.note.isEmpty() ? "" : " " + r.note));
                    }
                    if (r.outcome.equals("contradiction")) {
                        if (wrong.size() < MAX_WRONG_ROWS) {
                            wrong.add("  " + w.rel + ":" + r.d.line + " `" + r.d.name
                                    + "`: HARNESS CONTRADICTION -- the two answers agree and the"
                                    + " compiler still refuses the plugin's rename: " + r.renameError);
                        }
                        exit = 3;
                    }
                    continue;
                }
                judged++;
                row[0]++;
                row[1] += r.boundUses;
                row[2] += r.claimedUses;
                row[3] += r.missed.size();
                row[4] += r.wrongScope.size();
                boundUses += r.boundUses;
                claimedUses += r.claimedUses;
                missed += r.missed.size();
                wrongScope += r.wrongScope.size();
                unprovable += r.notRequired.size();
                if (r.renameCompiled) renameOk++;
                if (!r.notRequired.isEmpty() && unprovableRows.size() < MAX_UNPROVABLE_ROWS) {
                    unprovableRows.add("  " + w.rel + ":" + r.d.line + " `" + r.d.name + "` ("
                            + r.d.kind + "): the table claims " + offsets(w.text, r.notRequired)
                            + ", and no declaration in this file is required by the compiler to be"
                            + " renamed there.  The compiler's silence is not a denial: it either"
                            + " does not resolve that position at all (see the header on"
                            + " `b: P = a`) or it cannot see the difference (a shadowed name,"
                            + " where renaming either declaration leaves the program compiling),"
                            + " so this tool counts the occurrence and does not judge it");
                }
                if (!r.findings.isEmpty()) {
                    row[5]++;
                    wrongDecls++;
                    if (exit == 0) exit = 1;
                    if (wrong.size() < MAX_WRONG_ROWS) {
                        wrong.add("  " + w.rel + ":" + r.d.line + " `" + r.d.name + "` ("
                                + r.d.kind + ")");
                        for (String f : r.findings) wrong.add("      " + f);
                        if (explain && !r.renameCompiled) {
                            wrong.add("      [explain] the compiler on the plugin's own rename: "
                                    + r.renameError);
                        }
                    }
                }
                if (show) {
                    System.out.println("  " + pad(w.rel, 40) + pad(String.valueOf(r.d.line), 6)
                            + pad(r.d.kind, 13) + pad(r.d.name, 18)
                            + pad(String.valueOf(r.boundUses), 7)
                            + pad(String.valueOf(r.claimedUses), 9)
                            + (r.findings.isEmpty() ? "correct"
                                    : "WRONG (" + r.findings.size() + " finding(s))"));
                }
            }
        }

        System.out.println("== per kind: declarations judged, and use-level numbers ==");
        System.out.println(pad("kind", 16) + pad("decls", 8) + pad("bound", 8) + pad("claimed", 9)
                + pad("missed", 8) + pad("wrong-scope", 12) + "wrong decls");
        System.out.println("-".repeat(16) + "  " + "-".repeat(6) + "  " + "-".repeat(6) + "  "
                + "-".repeat(7) + "  " + "-".repeat(6) + "  " + "-".repeat(10) + "  " + "-".repeat(11));
        for (Map.Entry<String, long[]> e : byKind.entrySet()) {
            long[] v = e.getValue();
            System.out.println(pad(e.getKey(), 16) + pad(String.valueOf(v[0]), 8)
                    + pad(String.valueOf(v[1]), 8) + pad(String.valueOf(v[2]), 9)
                    + pad(String.valueOf(v[3]), 8) + pad(String.valueOf(v[4]), 12) + v[5]);
        }
        System.out.println();
        System.out.println("  judged declarations     : " + judged);
        System.out.println("  bound uses (compiler)   : " + boundUses);
        System.out.println("  claimed uses (plugin)   : " + claimedUses);
        System.out.println("  MISSED (bound, not claimed)              : " + missed);
        System.out.println("  WRONG-SCOPE (claimed, bound to another)  : " + wrongScope);
        System.out.println("  NOT-REQUIRED (claimed, compiler silent)  : " + unprovable
                + "   (unprovable, counted, never called wrong)");
        System.out.println("  wrong declarations      : " + wrongDecls);
        System.out.println("  the plugin's rename compiled : " + renameOk + " of " + judged
                + " judged declaration(s)");
        System.out.println("  compiler processes      : " + compilerRuns + " in this run, "
                + cacheHits + " declaration(s) answered from the cache ("
                + (elapsedMs / 1000) + " s, " + threads + " threads)");
        System.out.println();
        System.out.println("== every declaration that is not judged, and why ==");
        int skipTotal = 0;
        StringBuilder cats = new StringBuilder();
        for (Map.Entry<String, Integer> e : skips.entrySet()) {
            skipTotal += e.getValue();
            if (cats.length() > 0) cats.append(", ");
            cats.append(e.getKey()).append(" ").append(e.getValue());
        }
        System.out.println("  " + cats);
        if (skippedRows.isEmpty()) System.out.println("  (nothing)");
        else for (String s : skippedRows) System.out.println(s);
        System.out.println();
        System.out.println("== every wrong declaration, in full ==");
        if (wrong.isEmpty()) System.out.println("  (none)");
        else for (String s : wrong) System.out.println(s);
        System.out.println();
        System.out.println("== claimed references the compiler never requires (unprovable) ==");
        if (unprovableRows.isEmpty()) System.out.println("  (none)");
        else for (String s : unprovableRows) System.out.println(s);
        System.out.println();

        // The instrument's own control: a comparator that cannot fire proves nothing, so
        // it is asked to fire in both directions before its zeros are believed.
        if (control) {
            boolean ok = controlProbe();
            if (!ok) exit = 3;
        }

        String verdict;
        if (wrongDecls > 0) {
            verdict = wrongDecls + " declaration(s) of " + judged + " judged are wrong: "
                    + missed + " bound use(s) missed and " + wrongScope + " reference(s) claimed"
                    + " for a declaration the compiler binds elsewhere";
            exit = 1;
        } else if (crashedFiles > 0) {
            verdict = "NOT A PASS: no wrong answer among the " + judged + " judged declaration(s),"
                    + " but " + crashedFiles + " file(s) crashed this harness and their"
                    + " declarations were never judged";
            exit = 3;
        } else if (missingFiles > 0) {
            verdict = "NOT A PASS: no wrong answer among the " + judged + " judged declaration(s),"
                    + " but " + missingFiles + " corpus entry(ies) are not on disk, so the corpus"
                    + " measured is not the corpus named";
            exit = 3;
        } else {
            verdict = "for every one of the " + judged + " judged declaration(s), the reference"
                    + " table names exactly the " + boundUses + " use(s) the compiler binds and"
                    + " nothing else, and renaming them produces a program the compiler accepts";
            exit = 0;
        }
        System.out.println("VERDICT: " + verdict);
        System.out.println("COVERAGE: ran " + judged + " / skipped " + skipTotal + " (" + cats
                + ") / wrong " + wrongDecls);
        System.exit(exit);
    }

    /**
     * The comparator, asked to fire: one missed use, one extra reference, and one agreement.
     *
     * This is the harness's own instrument, and an instrument that has never reported a
     * finding is not evidence of anything.  It does not test the plugin -- the negative
     * control for that is a deliberately mutated copy of the plugin's source, run through
     * this same tool -- it tests that the comparison below *can* return the two lists the
     * tool's whole report is built from.
     */
    private boolean controlProbe() {
        System.out.println("== control: the comparator itself, asked to fire ==");
        Set<Integer> bound = new LinkedHashSet<Integer>(List.of(10, 20, 30));
        boolean missedSeen = minus(bound, new LinkedHashSet<Integer>(List.of(10, 20)))
                .equals(List.of(30));
        boolean extraSeen = minus(new LinkedHashSet<Integer>(List.of(10, 20, 30, 40)), bound)
                .equals(List.of(40));
        boolean clean = minus(new LinkedHashSet<Integer>(List.of(10, 20, 30)), bound).isEmpty();
        System.out.println("  a bound use the table lacks is a MISSED : " + missedSeen);
        System.out.println("  a reference the compiler lacks is EXTRA  : " + extraSeen);
        System.out.println("  two equal answers are clean             : " + clean);
        boolean ok = missedSeen && extraSeen && clean;
        System.out.println("  control verdict: " + (ok ? "the comparator fires in both directions"
                : "THE COMPARATOR CANNOT FIRE, so its zeros below mean nothing"));
        System.out.println();
        return ok;
    }

    // ------------------------------------------------------------- the inventory

    private static final class Decl {
        final String kind;
        final String name;
        final int start;
        final int end;
        final int line;
        final String key;

        Decl(String kind, String name, int start, int end, int line) {
            this.kind = kind;
            this.name = name;
            this.start = start;
            this.end = end;
            this.line = line;
            this.key = kind + "@" + line + ":" + name;
        }

        String contentKey(String text) {
            return text.length() + "," + line + "," + start + "," + kind + "," + name;
        }
    }

    /**
     * The declaration sites, read from the plugin's parser -- the tree `ast-diff.ps1` holds
     * to `vm.exe parse`.  A `def` inside a struct is a `method`; everywhere else a
     * declaration is a function, a struct, a field, a parameter, a local binding or a loop
     * variable, which is the same list `GotoOracle` builds for go-to-declaration.
     */
    private static List<Decl> declarations(String text, VelaSyntaxTree tree) {
        List<Decl> out = new ArrayList<Decl>();
        walkDecls(text, tree, tree.root, out, false);
        return out;
    }

    private static void walkDecls(String text, VelaSyntaxTree tree, VelaSyntaxNode n,
                                  List<Decl> out, boolean parentIsStruct) {
        switch (n.kind) {
            case DEF:
                addName(text, tree, n, parentIsStruct ? "method" : "function", out);
                break;
            case STRUCT:
                addName(text, tree, n, "struct", out);
                break;
            // SPEC.md §13: the two names a file declares that this walk did not know.  A
            // variant is renamed as a declaration like any other -- and the compiler is the
            // authority on which *uses* must follow it: the construction, the arm's pattern,
            // and a bare value.  Judging them is the whole point; not listing them here is
            // why "0 MISSED" was true of a reference table that could not resolve one.
            case ENUM:
                addName(text, tree, n, "enum", out);
                break;
            case VARIANT:
                addName(text, tree, n, "variant", out);
                break;
            case FIELD:
                addName(text, tree, n, "field", out);
                break;
            case PARAM:
                addName(text, tree, n, "parameter", out);
                break;
            case DECL:
                // A `mut`-declared member of a struct is a DECL node, not a FIELD node, and
                // the plugin's own rule says so (`velaIsFieldMember`: `kind == FIELD ||
                // (kind == DECL && typeText.isNotEmpty())`).  Calling it a local here made
                // this tool expect the label `variable` for `t.ncap` while the plugin, which
                // reads that same rule, said `field` -- and 9 declarations in
                // tests/run_tests.vel were reported wrong for that reason alone.  The rule
                // is therefore asked here too: a DECL whose parent is the struct is a field,
                // everywhere else a binding.
                addName(text, tree, n, parentIsStruct ? "field" : "local", out);
                break;
            case FOR:
                addName(text, tree, n, "loop variable", out);
                break;
            default:
                break;
        }
        boolean structHere = n.kind == VelaNodeKind.STRUCT;
        for (VelaSyntaxNode c : n.children) walkDecls(text, tree, c, out, structHere);
    }

    private static void addName(String text, VelaSyntaxTree tree, VelaSyntaxNode n, String kind,
                                List<Decl> out) {
        int from = n.startTok >= 0 ? n.startTok : 0;
        int to = n.endTok >= 0 ? n.endTok : tree.toks.size() - 1;
        if (kind.equals("function") || kind.equals("method")) {
            for (int i = from; i <= to && i < tree.toks.size(); i++) {
                VelaTok t = tree.toks.get(i);
                if (t.kind == VelaTokKind.NAME && t.code == 1) {      // VelaKw.DEF
                    for (int j = i + 1; j <= to && j < tree.toks.size(); j++) {
                        VelaTok u = tree.toks.get(j);
                        if (u.kind == VelaTokKind.NAME && u.code == 0 && u.start < u.end) {
                            add(out, kind, text, u);
                            return;
                        }
                    }
                    return;
                }
            }
            return;
        }
        for (int i = from; i <= to && i < tree.toks.size(); i++) {
            VelaTok t = tree.toks.get(i);
            if (t.kind != VelaTokKind.NAME || t.code != 0 || t.start >= t.end) continue;
            add(out, kind, text, t);
            return;
        }
    }

    private static void add(List<Decl> out, String kind, String text, VelaTok t) {
        out.add(new Decl(kind, text.substring(t.start, t.end), t.start, t.end,
                lineOfOffset(text, t.start)));
    }

    /**
     * Every identifier leaf, in file order: the candidate set the reference provider is
     * registered over, lexed with the plugin's own lexer.
     */
    private static void collectLeaves(FileWork w) {
        Lexer lexer = new VelaLexer();
        lexer.start(w.text);
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() == VelaTokenTypes.IDENTIFIER) {
                w.leaves.add(new int[]{lexer.getTokenStart(), lexer.getTokenEnd()});
            }
            lexer.advance();
        }
    }

    // ------------------------------------------------------------------ the cache

    private Map<String, String> loadCache() throws IOException {
        Map<String, String> out = new ConcurrentHashMap<String, String>();
        if (!Files.isRegularFile(cache) || rebuild) return out;
        for (String line : Files.readAllLines(cache, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return out;
    }

    private void saveCache(Map<String, String> cacheMap) throws IOException {
        Files.createDirectories(cache.getParent());
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, String> e : cacheMap.entrySet()) {
            lines.add(e.getKey() + "\t" + e.getValue());
        }
        Collections.sort(lines);
        Files.write(cache, lines, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ plumbing

    private void collectCorpus() throws IOException {
        Set<String> seen = new LinkedHashSet<String>();
        for (String dir : new String[]{"tests", "examples", "ide-demo", "selfhost/parts", "bench"}) {
            Path d = repoRoot.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            List<Path> found = new ArrayList<Path>();
            Files.walk(d)
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vel"))
                    .forEach(found::add);
            Collections.sort(found);
            for (Path f : found) {
                seen.add(repoRoot.relativize(f).toString().replace('\\', '/'));
            }
        }
        // `selfhost/vm.vel` is deliberately NOT here, for the reason GotoOracle gives: it is
        // the 631 KB concatenation of the parts, and one `check` over it is a second per
        // candidate.  The parts themselves are in the corpus and are reported as
        // compiler-refused, which is a limit of the oracle and not of the plugin.
        for (String extra : new String[]{"selfhost/vela.vel"}) {
            Path p = repoRoot.resolve(extra);
            if (Files.isRegularFile(p)) seen.add(extra);
        }
        corpus.addAll(seen);
    }

    /** The first non-empty line of a compiler message, for a one-line row. */
    private static String firstLine(String text) {
        if (text == null) return "";
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (!line.trim().isEmpty()) return line.trim();
        }
        return "";
    }

    private static String read(Path p) throws IOException {
        byte[] raw = Files.readAllBytes(p);
        String text;
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            text = new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        } else {
            text = new String(raw, StandardCharsets.UTF_8);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** The line of the compiler's first `at <file>:<line>` position, whatever the message. */
    private static Integer reportedLine(String stderr) {
        if (stderr == null) return null;
        Matcher m = Pattern.compile(":(\\d+)\\s*$", Pattern.MULTILINE).matcher(stderr);
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
    }

    private static boolean lineHasWord(String text, int line, String word) {
        return indexOfWord(lineBody(text, line), word, 0) >= 0;
    }

    private static String lineBody(String text, int line) {
        int from = 0;
        for (int i = 1; i < line; i++) {
            int nl = text.indexOf('\n', from);
            if (nl < 0) return "";
            from = nl + 1;
        }
        int to = text.indexOf('\n', from);
        if (to < 0) to = text.length();
        return text.substring(from, to);
    }

    /** Replace the first remaining occurrence of `name` on `line` with `fresh`, or null. */
    private static String renameFirstUseOnLine(String text, int line, String name, String fresh) {
        int from = 0;
        for (int i = 1; i < line; i++) {
            int nl = text.indexOf('\n', from);
            if (nl < 0) return null;
            from = nl + 1;
        }
        int to = text.indexOf('\n', from);
        if (to < 0) to = text.length();
        String body = text.substring(from, to);
        int at = indexOfWord(body, name, 0);
        if (at < 0) return null;
        return text.substring(0, from + at) + fresh + text.substring(from + at + name.length());
    }

    private static int indexOfWord(String body, String word, int from) {
        int i = from;
        while (true) {
            i = body.indexOf(word, i);
            if (i < 0) return -1;
            boolean before = i == 0 || !isNamePart(body.charAt(i - 1));
            int after = i + word.length();
            boolean afterOk = after >= body.length() || !isNamePart(body.charAt(after));
            if (before && afterOk) return i;
            i++;
        }
    }

    /**
     * One replacement of every range in `starts` with `fresh`, back to front.
     *
     * This is the arithmetic of `document.replaceString(start, end, newName)` applied to
     * every reference: same text, and the only ordering question (`which replacement comes
     * first`) is answered by doing the later ones first, so no offset shifts under another.
     */
    private static String renameAll(String text, java.util.Collection<Integer> starts,
                                    int nameLen, String fresh) {
        List<Integer> sorted = new ArrayList<Integer>(new TreeSet<Integer>(starts));
        StringBuilder sb = new StringBuilder(text.length() + sorted.size() * 3);
        int at = 0;
        for (int s : sorted) {
            if (s < at || s + nameLen > text.length()) {
                throw new IllegalStateException("overlapping edit at " + s);
            }
            sb.append(text, at, s).append(fresh);
            at = s + nameLen;
        }
        sb.append(text, at, text.length());
        return sb.toString();
    }

    private static List<Integer> minus(java.util.Collection<Integer> a,
                                       java.util.Collection<Integer> b) {
        List<Integer> out = new ArrayList<Integer>();
        for (int x : new TreeSet<Integer>(a)) {
            if (!b.contains(x)) out.add(x);
        }
        return out;
    }

    private static String between(String s, String open, String close) {
        int i = s.indexOf(open);
        int j = s.indexOf(close, i + 1);
        return i < 0 || j < 0 ? "" : s.substring(i + open.length(), j);
    }

    private static List<Integer> parseCsv(String csv) {
        List<Integer> out = new ArrayList<Integer>();
        if (csv == null || csv.isEmpty()) return out;
        for (String part : csv.split(",")) {
            if (!part.isEmpty()) out.add(Integer.parseInt(part));
        }
        return out;
    }

    /** The offsets as `file:line` references, which is what a reader can act on. */
    private static String offsets(String text, List<Integer> offs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < offs.size() && i < 6; i++) {
            if (i > 0) sb.append(", ");
            sb.append("line ").append(lineOfOffset(text, offs.get(i))).append(" (offset ")
              .append(offs.get(i)).append(")");
        }
        if (offs.size() > 6) sb.append(", and ").append(offs.size() - 6).append(" more");
        return sb.toString();
    }

    /** The word the results view should show for a declaration of this kind. */
    private static String label(String kind) {
        return kind.equals("local") ? "variable" : kind;
    }

    private static int lineOfOffset(String text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Collections.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private static String sha256(Path p) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
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

    private static boolean isNamePart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static String pad(String s, int n) {
        if (s.length() >= n) return s + "  ";
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private static String allSkipCountersZero() {
        StringBuilder sb = new StringBuilder();
        for (String k : SKIP_KINDS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(k).append(" 0");
        }
        return sb.toString();
    }

    private RenameOracle() {
    }
}
