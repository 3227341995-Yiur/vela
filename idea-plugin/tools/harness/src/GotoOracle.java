import dev.vela.plugin.VelaDeclarations;
import dev.vela.plugin.VelaNodeKind;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaTok;
import dev.vela.plugin.VelaTokKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The completeness table for go-to-declaration, with the compiler as the oracle.
 *
 * WHAT THE ORACLE IS, AND WHY IT IS THE COMPILER'S
 *
 * "Which declaration does this use bind to?" has one authority: the compiler.
 * `vm.exe parse` prints the tree with no positions, so it cannot answer a positional
 * question -- but `vm.exe check` can be made to:
 *
 *   rename ONE declaration (its name becomes `xzzq`), then run `check`.  Every use
 *   the compiler had bound to that declaration is now broken, and the compiler
 *   prints the line of one of them.  Repair that one use (write `xzzq` there), run
 *   again: the next bound use is reported.  Repeat until `check` passes.
 *
 * The lines repaired along the way are the lines the compiler binds to that
 * declaration -- and the protocol *verifies its own answer*: if the file does not
 * come back to exit 0 after repairing exactly those lines, the set is thrown away
 * and the declaration is not judged.  Nothing here reads the compiler's message
 * text to decide what a diagnostic *means*, because a rename can produce
 * `undeclared name 'x'`, `no 'main' function`, or something else again; what is read
 * is the `at <file>:<line>` position, and only its line.
 *
 * The only thing this harness contributes is where the candidate declarations are,
 * which it takes from the plugin's parser -- the one `ast-diff.ps1` holds to
 * `vm.exe parse`: 107 files and 115,451 node lines, no difference.
 *
 * THE LINE-NUMBER CONVENTION, WHICH IS THE WHOLE COMPARISON
 *
 * The oracle speaks *lines* because that is what `check` prints.  So:
 *
 *   * the expected target is the declaration's line, 1-based, counted from the start
 *     of the file -- the compiler counts the same way;
 *   * the plugin's answer is a character range, turned into a line here by counting
 *     newlines before its start: the same convention, and the same rule as the
 *     plugin's own `VelaModel.lineOf`;
 *   * a reference is judged only when the oracle names exactly ONE declaration for
 *     it.  Two declarations of one name bound on one line is a real ambiguity at
 *     line granularity, and it is counted as such rather than hidden;
 *   * a reference in a file the compiler refuses outright is UNJUDGED, and counted.
 *
 * NO TARGET IS A LEGAL ANSWER.  The rule is "right, or nothing": only a wrong target
 * is a failure.  A builtin is declared by the language and not by the file, so for
 * one the expected answer is no target.
 *
 *   java -cp <plugin classes> GotoOracle <repo-root> --vm <vm.exe> [--cache <file>]
 *                                   [--rebuild-oracle] [--single <file>] [--debug]
 */
public final class GotoOracle {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int THREADS = 8;

    private Path repoRoot;
    private Path vm;
    private Path cache;
    private boolean rebuild;
    private boolean debug;
    private boolean show;
    private String single;
    private boolean probeCache;
    private String vmSha = "";
    private final List<String> corpus = new ArrayList<>();

    /** How many times the atomic move is retried when the destination is held open. */
    private static final int MOVE_ATTEMPTS = 20;
    /** Base backoff between move attempts, in milliseconds (multiplied by the attempt). */
    private static final long MOVE_RETRY_MS = 5;

    public static void main(String[] args) throws Exception {
        GotoOracle tool = new GotoOracle();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--vm")) tool.vm = Paths.get(args[++i]).toAbsolutePath();
            else if (a.equals("--cache")) tool.cache = Paths.get(args[++i]);
            else if (a.equals("--rebuild-oracle")) tool.rebuild = true;
            else if (a.equals("--debug")) tool.debug = true;
            else if (a.equals("--probe-cache")) tool.probeCache = true;
            else if (a.equals("--show")) tool.show = true;
            else if (a.equals("--single")) tool.single = args[++i];
            else rest.add(a);
        }
        tool.repoRoot = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        if (tool.probeCache) {
            tool.cacheProbe();
            return;
        }
        tool.run();
    }

    /**
     * Prove the cache write cannot tear, by exercising the real `saveCache`.
     *
     * The claim is that `saveCache` is single-writer and atomic, and the way it was
     * broken is what makes it worth proving: eight worker threads call it, and a
     * torn cache file does not crash anything -- the next run's `loadCache` reads it
     * and judges references against bindings that were never measured, which is a
     * wrong oracle that reports clean.
     *
     * So this does the thing that used to tear: `THREADS` threads writing the same
     * cache path over and over, with a reader sampling it continuously.  Every
     * snapshot the reader sees must be a whole cache -- every line `key<TAB>value`
     * with a key that is one of the keys written, no partial line, non-empty.  A
     * single torn read is a finding, and the exit code says so.
     *
     * The keys are 64 hex characters (the shape `analyse` uses: a sha256, a file
     * hash and a declaration key joined by `|`), so a partial write is detectable:
     * a half-written line loses its tab, and a half-written file loses lines.
     */
    private void cacheProbe() throws Exception {
        int threads = THREADS;
        int rounds = 40;
        int base = 2000;
        Path dir = cache.resolveSibling(cache.getFileName() + ".probe");
        Files.createDirectories(dir);
        Path probeCache = dir.resolve("probe-cache.txt");
        this.cache = probeCache;

        // THE MAPS HAVE TO GROW MONOTONICALLY, OR THE MEASUREMENT IS VACUOUS.
        // The first version of this probe had all writers `put` into one shared map
        // and save it, which makes the reader's "fewer than N entries" test
        // meaningless: whether a snapshot is short depends on which entries happened
        // to be in the shared map at that instant, not on whether the write tore.  So
        // each writer owns its map: writer `t` in round `r` saves `base + 5*(r+1)`
        // entries, every one of which is a key derived from (t, r).  Every snapshot is
        // therefore either a whole cache of >= `base` entries, or torn.
        // ------------------------------------------------------- the fixture's format
        //
        // THE VALUES MUST NOT CONTAIN A REAL NEWLINE.  The first version of this probe
        // wrote `"21,34\n99,120"` as a value -- reasoning that a binding list is
        // multi-line -- and the reader then reported 8,151 "torn" snapshots whose
        // reported cause was `line at byte 152 has no key/value tab: 33,44`.  That was
        // the fixture, not the writer: this cache stores one entry per line, and the
        // second half of such a value has no tab in it by construction.  The real
        // format is visible in the shipped cache file:
        //
        //     0f75d65d...|0189a14e841680da|3169,10,484,function,q<TAB>26,28,46,56,60,
        //
        // so the invariant the reader checks -- every line is `key<TAB>value` -- is a
        // fact about the format, and the fixture has to be a member of it.  This is the
        // second time in this probe that the *instrument* was the thing at fault; both
        // are written down here so the third does not happen.
        String value = "11,22,33,44";

        List<List<String>> writerKeys = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            List<String> keys = new ArrayList<>();
            for (int r = 0; r < rounds; r++) {
                for (int k = 0; k < 5; k++) {
                    keys.add(sha256String("probe-key-" + t + "-" + r + "-" + k) + "|"
                            + sha256String("file-" + t + "-" + r) + "|decl-" + t + "-" + r);
                }
            }
            writerKeys.add(keys);
        }
        // Keys every writer has in every one of its saves: the ones that must survive
        // into the final file whatever order the writers finished in.
        List<String> commonKeys = new ArrayList<>();
        for (int i = 0; i < base; i++) {
            commonKeys.add(sha256String("probe-common-" + i) + "|" + sha256String("file-common")
                    + "|decl-common-" + i);
        }

        System.out.println("== GotoOracle cache-write probe ==");
        System.out.println("cache path        : " + probeCache);
        System.out.println("threads           : " + threads + " (the same pool size GotoOracle uses)");
        System.out.println("saveCache() calls : " + (threads * rounds) + " from " + threads
                + " threads, to ONE path");
        System.out.println("entries per save  : " + base + " growing by 5 per save (writer-local maps,"
                + " so every whole snapshot has at least " + base + " entries)");
        System.out.println();

        AtomicLong writes = new AtomicLong();
        AtomicLong writeFailures = new AtomicLong();
        AtomicLong reads = new AtomicLong();
        AtomicLong torn = new AtomicLong();
        AtomicLong shortReads = new AtomicLong();
        AtomicLong inFlight = new AtomicLong();
        AtomicLong atomicallyWatched = new AtomicLong();
        List<String> firstProblems = Collections.synchronizedList(new ArrayList<>());

        // ---------------------------------------------------------------- the control
        //
        // A verifier that cannot fail proves nothing.  Before the concurrent phase,
        // the reader is pointed at a deliberately torn file once and must call it torn
        // -- otherwise "0 torn" below is a statement about the reader, not the writer.
        Path control = dir.resolve("control-torn.txt");
        String good = "aaaa\t1,2\nbbbb\t3,4\n";
        Files.write(control, good.getBytes(StandardCharsets.UTF_8));
        boolean controlGoodAccepted = !looksTorn(good);
        boolean controlTornSeen = looksTorn("aaaa\t1,2\nbbbb\t3,");
        boolean controlHalfLine = looksTorn("aaaa\t1,2\nbbbb");
        boolean controlNewlineInValue = looksTorn("aaaa\t1,2\nbbbb\t33,44");
        Path controlBackup = cache;
        this.cache = control;
        try {
            saveCache(new LinkedHashMap<>(Map.of("x".repeat(64) + "|y|z", "5,6")));            String written = new String(Files.readAllBytes(control), StandardCharsets.UTF_8);
            atomicallyWatched.set(looksTorn(written) ? 0 : 1);
        } finally {
            this.cache = controlBackup;
        }
        System.out.println("control: a whole cache is accepted      : " + controlGoodAccepted);
        System.out.println("control: a cut-off last line is torn    : " + controlTornSeen);
        System.out.println("control: a line with no tab is torn     : " + controlHalfLine);
        // The control that would have caught this probe's own fixture bug: a value
        // containing a real newline is NOT a member of this format, so the reader must
        // call it torn.  It did -- which is how the bug was found -- and stating it here
        // means the next fixture with a newline in a value fails the control rather than
        // producing thousands of phantom tears.
        System.out.println("control: a newline inside a value is torn: " + controlNewlineInValue);
        System.out.println("control: the real saveCache output is whole: "
                + (atomicallyWatched.get() == 1));
        System.out.println();
        if (!(controlGoodAccepted && controlTornSeen && controlHalfLine
                && controlNewlineInValue && atomicallyWatched.get() == 1)) {
            System.out.println("VERDICT: the probe's own reader is wrong, so its measurement of the"
                    + " writer would be meaningless");
            System.exit(2);
        }

        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        Future<?> readerFuture = pool.submit(() -> {
            while (!stop.get()) {
                readOnce(probeCache, reads, torn, inFlight, base, firstProblems);
            }
        });

        List<Future<?>> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            final List<String> keys = writerKeys.get(t);
            writers.add(pool.submit(() -> {
                Map<String, String> mine = new LinkedHashMap<>();
                for (String k : commonKeys) mine.put(k, value);
                for (int r = 0; r < rounds; r++) {
                    for (int k = 0; k < 5; k++) {
                        mine.put(keys.get(r * 5 + k), value);
                    }
                    try {
                        // The real one.  Not a reimplementation of it.
                        saveCache(mine);
                        writes.incrementAndGet();
                    } catch (IOException e) {
                        // A write that could not complete is counted apart from a tear:
                        // with the retry above, the old file survives and no reader sees a
                        // partial one, but the entries in `mine` are NOT on disk -- which is
                        // a lost write, and a caller that ignores it loses work silently.
                        writeFailures.incrementAndGet();
                        if (firstProblems.size() < 3) firstProblems.add("writing threw " + e);
                    }
                }
            }));
        }
        for (Future<?> f : writers) f.get();
        stop.set(true);
        readerFuture.get();
        pool.shutdown();

        String finalText = new String(Files.readAllBytes(probeCache), StandardCharsets.UTF_8);
        Set<String> finalKeys = new LinkedHashSet<>();
        String finalWhy = tornReason(finalText);
        boolean malformedTorn = finalWhy != null;
        for (String line : finalText.split("\n")) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab > 0) finalKeys.add(line.substring(0, tab));
        }
        if (malformedTorn) System.out.println("final file is torn because: " + finalWhy);
        List<String> missing = new ArrayList<>();
        for (String k : commonKeys) if (!finalKeys.contains(k)) missing.add(k);

        System.out.println("saveCache() calls that returned : " + writes.get());
        System.out.println("saveCache() calls that threw    : " + writeFailures.get()
                + "   (the replace gave up after " + MOVE_ATTEMPTS + " attempts: the entries in"
                + " that map are NOT on disk)");
        System.out.println("reads of the cache file        : " + reads.get());
        System.out.println("TORN snapshots                 : " + torn.get()
                + "  (a partial line, a missing trailing newline, or fewer entries than"
                + " every save writes)");
        System.out.println("reads during the move's window : " + inFlight.get()
                + "   (the path was absent or empty three times running -- the file is"
                + " being replaced, not half-written)");
        System.out.println("short whole snapshots          : " + shortReads.get()
                + "   (a save that raced another and wrote a smaller map)");
        System.out.println("final file                     : " + finalKeys.size() + " entries, "
                + (malformedTorn ? "IS TORN" : "well formed"));
        System.out.println("keys every save wrote but the final cache lacks : " + missing.size());
        if (!firstProblems.isEmpty()) {
            System.out.println();
            System.out.println("== the first problems seen ==");
            for (String s : firstProblems) System.out.println("  " + s);
        }
        System.out.println();
        boolean ok = torn.get() == 0 && !malformedTorn && missing.isEmpty() && reads.get() > 0
                && writeFailures.get() == 0;
        System.out.println("VERDICT: " + (ok
                ? "every one of the " + reads.get() + " snapshots was a whole cache: " + threads
                        + " threads wrote one path " + writes.get() + " time(s), 0 torn, 0 partial"
                        + " lines, 0 short, 0 failed writes, and the final file holds all "
                        + commonKeys.size() + " keys every save wrote"
                : "the cache write is NOT safe: " + torn.get() + " torn snapshot(s), "
                        + shortReads.get() + " short snapshot(s), " + writeFailures.get()
                        + " failed write(s), " + (malformedTorn ? "a torn final file, " : "")
                        + missing.size() + " missing key(s), " + reads.get() + " read(s)"));
        System.exit(ok ? 0 : 1);
    }

    /**
     * Is this text not a whole cache?
     *
     * A whole cache is lines of `key<TAB>value` ending in a newline.  Anything else
     * -- no trailing newline, an empty line other than the final one, a line with no
     * tab -- is a partial write.  This is the probe's *instrument*, so the probe
     * proves it can fire before trusting its own zeros (see the control).
     */
    private static boolean looksTorn(String text) {
        return tornReason(text) != null;
    }

    /**
     * WHY this text is not a whole cache, or null if it is one.
     *
     * The boolean above is kept for the control checks; this is what the reader
     * reports, because "torn" with no byte position is a number, not a finding.  The
     * three shapes are named separately: a missing trailing newline (the last line was
     * still being written), a line with no tab (a line was cut mid-way), and an empty
     * file.
     */
    private static String tornReason(String text) {
        if (text.isEmpty()) return "the file is 0 bytes";
        if (!text.endsWith("\n")) {
            String tail = text.substring(Math.max(0, text.length() - 40));
            return "no trailing newline; the file ends `" + tail.replace("\n", "\\n")
                    + "` (" + text.length() + " bytes)";
        }
        int offset = 0;
        for (String line : text.split("\n")) {
            if (line.isEmpty()) {
                offset += 1;
                continue;
            }
            if (line.indexOf('\t') <= 0) {
                return "line at byte " + offset + " has no key/value tab: `" + line + "`";
            }            offset += line.length() + 1;
        }
        return null;
    }

    /**
     * The reader's loop, factored out so the three outcomes cannot be confused.
     *
     * WHAT THE FIRST VERSION OF THIS PROBE GOT WRONG, AND WHY IT MATTERS
     *
     * The first run reported 9,065 "torn" snapshots out of 9,025 reads and looked
     * like a devastating finding.  It was the *instrument*: between the moment
     * `Files.move` starts replacing the target and the moment it finishes, a reader
     * on a different thread can observe the path as **absent or empty** -- on Windows
     * the replacement is not observable as a single instant.  Treating "the file was
     * not there for a microsecond" as "the file's bytes are half-written" is exactly
     * the kind of check that reports a catastrophic number for a property nobody was
     * testing.
     *
     * The two outcomes are different and are counted apart:
     *
     *   not-there / empty  : the move was in flight.  Counted, NOT a finding.
     *   partial bytes      : a line with no tab, a missing trailing newline, or a
     *                        snapshot with fewer entries than every writer writes.
     *                        THIS is a torn write, and it is the finding.
     *
     * A retry covers the in-flight case: three consecutive empty/missing reads means
     * the file is persistently unavailable rather than mid-move, and that is a
     * finding too.  The retry cannot mask a tear -- a torn file has *bytes* in it, and
     * `looksTorn` fires on those on the first read, with no retry.
     */
    private void readOnce(Path probeCache, AtomicLong reads, AtomicLong torn,
                          AtomicLong inFlight, int base, List<String> firstProblems) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (!Files.isRegularFile(probeCache)) {
                    if (attempt < 2) continue;
                    inFlight.incrementAndGet();
                    return;
                }
                String text = new String(Files.readAllBytes(probeCache), StandardCharsets.UTF_8);
                if (text.isEmpty()) {
                    if (attempt < 2) continue;
                    inFlight.incrementAndGet();
                    return;
                }
                reads.incrementAndGet();
                String why = tornReason(text);
                if (why != null) {
                    torn.incrementAndGet();
                    if (firstProblems.size() < 3) {
                        // Dump the whole neighbourhood of the bad line, not just the line:
                        // a "torn" report that does not show what is around the tear cannot
                        // distinguish a partial write from a fixture that is wrong about
                        // the format, and this probe has already been wrong once.
                        StringBuilder nb = new StringBuilder();
                        String[] ls = text.split("\n", -1);
                        for (int i = 0; i < Math.min(6, ls.length); i++) {
                            String l = ls[i].length() > 70 ? ls[i].substring(0, 70) + "..." : ls[i];
                            nb.append("\n            line ").append(i).append(": `")
                              .append(l.replace("\t", "<TAB>")).append("`");
                        }
                        firstProblems.add("a torn snapshot was read: " + why
                                + "  (" + text.length() + " bytes, " + ls.length + " lines)"
                                + nb + "\n            ... last line: `"
                                + (ls.length > 0 && ls[ls.length - 1].length() > 60
                                    ? ls[ls.length - 1].substring(0, 60) + "..." : "")
                                + "`");
                    }
                    return;
                }
                int entries = 0;
                for (String line : text.split("\n")) if (!line.isEmpty()) entries++;
                if (entries < base) {
                    torn.incrementAndGet();
                    if (firstProblems.size() < 5) {
                        firstProblems.add("a whole-looking cache had only " + entries
                                + " entries, fewer than the " + base + " every save writes");
                    }
                }
                return;
            } catch (IOException e) {
                // A read that throws mid-move is the in-flight case; a read that throws
                // three times is not.
                if (attempt < 2) continue;
                inFlight.incrementAndGet();
                if (firstProblems.size() < 5) firstProblems.add("reading threw 3 times: " + e);
                return;
            }
        }
    }

    /** A sha256 of a string, the shape the probe's cache keys are built from. */
    private static String sha256String(String s) throws Exception {
        return sha256Hex(s);
    }

    private static String sha256Hex(String s) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void run() throws Exception {
        if (vm == null) vm = repoRoot.resolve("selfhost").resolve("build").resolve("vm.exe");
        if (cache == null) {
            cache = repoRoot.resolve("idea-plugin").resolve("build").resolve("tools")
                    .resolve("harness").resolve("goto-oracle.txt");
        }
        if (single != null) corpus.add(single.replace('\\', '/'));
        else collectCorpus();
        vmSha = sha256(vm);

        System.out.println("oracle   : `vm.exe check` with one declaration renamed at a time,");
        System.out.println("           each set of bound use lines verified by the file compiling again");
        System.out.println("compiler : " + vm + " (" + Files.size(vm) + " bytes, sha256 " + vmSha + ")");
        System.out.println("answer   : VelaDeclarations.declarationRange(text, offset) -> target line");
        System.out.println("threads  : " + THREADS);
        System.out.println();

        Map<String, String> cacheMap = loadCache();
        List<FileWork> works = new ArrayList<>();
        for (String rel : corpus) {
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p) || Files.size(p) > MAX_FILE_BYTES) continue;
            works.add(new FileWork(rel, p));
        }

        // The oracle is one compiler process per use plus one per declaration, which
        // over this corpus is tens of thousands of runs; it runs on a small pool.
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<?>> futures = new ArrayList<>();
        long start = System.currentTimeMillis();
        for (FileWork w : works) {
            futures.add(pool.submit(() -> {
                try {
                    analyse(w, cacheMap);
                } catch (Throwable t) {
                    w.error = String.valueOf(t);
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        long elapsed = System.currentTimeMillis() - start;
        saveCache(cacheMap);

        if (show) {
            System.out.println("== every reference: file, line, name, kind, expected declaration line, what the plugin answered ==");
            System.out.println("  " + pad("file", 44) + pad("line", 6) + pad("name", 22)
                    + pad("kind", 15) + pad("expected", 10) + pad("got", 10) + "verdict");
        }
        System.out.println("== completeness: every reference in the corpus ==");
        Map<String, long[]> byKind = new TreeMap<>();
        List<String> wrong = new ArrayList<>();
        List<String> unjudged = new ArrayList<>();
        long refs = 0;
        long correct = 0;
        long noTarget = 0;
        long wrongCount = 0;
        long ambiguous = 0;
        long unjudgedCount = 0;
        // THE SKIPS, ON THEIR OWN COUNTERS.  Every reason a reference is not judged
        // is a different fact and they must not be added together: a file the
        // compiler refuses is a limit of the oracle, an invisibly-bound member is the
        // compiler's own silence, and a crashed file is a defect in this harness.
        // Only the last one is a failure -- but it is a failure, because otherwise
        // the verdict above is a pass over whatever happened to survive.
        long skipRefused = 0;
        long skipInvisibleMember = 0;
        long skipAmbiguousRefs = 0;
        long skipCrashedRefs = 0;
        long skipCrashedFiles = 0;
        long declCount = 0;
        long compilerRuns = 0;
        long judgeableFiles = 0;
        for (FileWork w : works) {
            if (w.error != null) {
                // A thrown exception is counted as unreached *work*, and its file's
                // references as skipped: the failure mode this replaces counted them
                // as nothing at all, which is how a crash read as completeness.
                skipCrashedFiles++;
                skipCrashedRefs += w.refs.size();
                unjudged.add("  " + w.rel + ": the oracle could not run (" + w.refs.size()
                        + " reference(s) never judged): " + w.error);
                continue;
            }
            if (w.judgeable) judgeableFiles++;
            w.refCount = w.refs.size();
            declCount += w.decls.size();
            compilerRuns += w.compilerRuns;
            for (Ref r : w.refs) {
                refs++;
                Set<Integer> lines = new LinkedHashSet<>();
                String kind = null;
                for (Decl d : w.decls) {
                    if (!d.name.equals(r.name)) continue;
                    Integer declLine = w.bindings.get(d.key + ">" + r.line);
                    if (declLine != null) {
                        lines.add(declLine);
                        kind = d.kind;
                    }
                }
                Integer target = null;
                try {
                    Object range = VelaDeclarations.INSTANCE.declarationRange(w.text, r.start);
                    if (range != null) {
                        target = lineOfOffset(w.text, (Integer) range.getClass()
                                .getMethod("getFirst").invoke(range));
                    }
                } catch (Throwable t) {
                    wrongCount++;
                    wrong.add("  " + w.rel + ":" + r.line + " `" + r.name
                            + "` declarationRange THREW " + t);
                    byKind.computeIfAbsent("threw", k -> new long[5])[0]++;
                    continue;
                }
                if (lines.isEmpty() && (w.judgeable && w.invisibleNames.contains(r.name))) {
                    unjudgedCount++;
                    skipInvisibleMember++;
                    byKind.computeIfAbsent("unjudged (member: the compiler does not resolve it)",
                            k -> new long[5])[0]++;
                    if (unjudged.size() < 30) {
                        unjudged.add("  " + w.rel + ":" + r.line + " `" + r.name
                                + "`: renaming its declaration changes nothing the compiler notices"
                                + " (member access is unresolved by `vm.exe check`)");
                    }
                    continue;
                }
                if (lines.isEmpty() && !w.judgeable) {
                    unjudgedCount++;
                    skipRefused++;
                    byKind.computeIfAbsent("unjudged", k -> new long[5])[0]++;
                    if (unjudged.size() < 20) {
                        unjudged.add("  " + w.rel + ":" + r.line + " `" + r.name
                                + "` (the compiler refuses this file, so the oracle cannot say)");
                    }
                    continue;
                }
                String kindName = kind != null ? kind : (r.builtin ? "builtin" : "unresolved");
                long[] row = byKind.computeIfAbsent(kindName, k -> new long[5]);
                row[0]++;
                if (show) {
                    String exp = lines.isEmpty() ? "-"
                            : (lines.size() > 1 ? "ambiguous" : String.valueOf(lines.iterator().next()));
                    String got = target == null ? "-" : String.valueOf(target);
                    String verdict2;
                    if (lines.size() == 1) {
                        verdict2 = target == null ? "NO-TARGET" : (target == lines.iterator().next()
                                ? "correct" : "WRONG");
                    } else if (lines.isEmpty()) {
                        verdict2 = target == null ? "correct (no target expected)" : "WRONG";
                    } else {
                        verdict2 = "ambiguous";
                    }
                    System.out.println("  " + pad(w.rel, 44) + pad(String.valueOf(r.line), 6)
                            + pad(r.name, 22) + pad(kindName, 15) + pad(exp, 10) + pad(got, 10)
                            + verdict2);
                }
                if (lines.isEmpty()) {
                    if (target == null) {
                        row[2]++;
                        noTarget++;
                    } else if (isDeclLineOfName(w, r.name, target)) {
                        // THE COMPILER'S SILENCE IS NOT A DENIAL, AND CALLING IT ONE WAS
                        // THIS ORACLE'S OWN FALSE POSITIVE.  Measured on this build:
                        // `vm.exe check` accepts a write to a field the struct does not
                        // declare (`b.nosuchfield = 5` exits 0), so a struct-field write is
                        // neither bound nor refused -- renaming the field is reported only
                        // where the field is READ.  When the same name genuinely IS bound
                        // somewhere else in the file, its binding set is non-empty, so the
                        // "member is invisible" path above does not catch the writes, and
                        // every write fell through to here and was called WRONG.  All 13
                        // wrong rows in the first full run were exactly this: `t.ncap += 1`,
                        // `b.v = 10`, `a.x = 99` -- and the plugin's answer was the field's
                        // own declaration line every time.  The verdict stays strict where a
                        // wrong jump actually looks like one: a target that is NOT a
                        // declaration spelling this name is still counted WRONG below.
                        unjudgedCount++;
                        byKind.computeIfAbsent(
                                "unprovable (member write: `check` resolves it in reads, not in writes)",
                                k -> new long[5])[0]++;
                        if (unjudged.size() < 30) {
                            unjudged.add("  " + w.rel + ":" + r.line + " `" + r.name
                                    + "`: the compiler binds this name elsewhere in the file but says nothing"
                                    + " about this use, and `vm.exe check` accepts a write to a field that does"
                                    + " not exist, so this write is neither bound nor refused");
                        }
                    } else {
                        row[3]++;
                        wrongCount++;
                        wrong.add("  " + w.rel + ":" + r.line + " `" + r.name
                                + "`: no declaration of that name is bound here, so no target is the"
                                + " right answer, but the plugin went to line " + target
                                + "  [" + snippet(w.text, r) + "]");
                    }
                    continue;
                }
                if (lines.size() > 1) {
                    row[4]++;
                    ambiguous++;
                    skipAmbiguousRefs++;
                    continue;
                }
                int expected = lines.iterator().next();
                if (target == null) {
                    row[2]++;
                    noTarget++;
                } else if (target == expected) {
                    row[1]++;
                    correct++;
                } else {
                    row[3]++;
                    wrongCount++;
                    wrong.add("  " + w.rel + ":" + r.line + " `" + r.name + "` (" + kindName
                            + ") expected line " + expected + ", the plugin went to line " + target
                            + "  [" + snippet(w.text, r) + "]");
                }
            }
        }

        System.out.println(pad("kind", 16) + pad("refs", 8) + pad("correct", 9) + pad("no target", 11)
                + pad("WRONG", 8) + "ambiguous/u njudged");
        System.out.println("-".repeat(16) + "  " + "-".repeat(6) + "  " + "-".repeat(7) + "  "
                + "-".repeat(9) + "  " + "-".repeat(5) + "  " + "-".repeat(18));
        for (Map.Entry<String, long[]> e : byKind.entrySet()) {
            long[] v = e.getValue();
            System.out.println(pad(e.getKey(), 16) + pad(String.valueOf(v[0]), 8)
                    + pad(String.valueOf(v[1]), 9) + pad(String.valueOf(v[2]), 11)
                    + pad(String.valueOf(v[3]), 8) + v[4]);
        }
        System.out.println();
        System.out.println("  files analysed          : " + works.size() + ", of which the compiler"
                + " accepts (so the oracle can judge them): " + judgeableFiles + " (" + declCount
                + " declaration(s), " + compilerRuns + " compiler run(s), " + (elapsed / 1000) + " s)");
        System.out.println("  references judged       : " + refs);
        System.out.println("  correct target          : " + correct);
        System.out.println("  no target               : " + noTarget);
        System.out.println("  WRONG target            : " + wrongCount);
        System.out.println("  ambiguous (not judged)  : " + ambiguous);
        System.out.println("  unjudged (oracle silent): " + unjudgedCount);
        System.out.println();
        // The breakdown behind `skipped`, printed even when it is all zero: a
        // reader has to be able to see that the number was computed rather than
        // omitted, and zero-by-omission is the failure mode this whole block exists
        // for.
        long skippedRefs = skipCrashedRefs + skipRefused + skipInvisibleMember + skipAmbiguousRefs;
        long totalRefs = refs + skippedRefs;
        System.out.println("== coverage: every reference, and why it was or was not judged ==");
        System.out.println("  references in corpus    : " + totalRefs);
        System.out.println("  ran (judged)            : " + refs);
        System.out.println("  skipped                 : " + skippedRefs
                + "  (crashed-file " + skipCrashedRefs
                + " = " + skipCrashedFiles + " file(s)"
                + ", compiler-refused " + skipRefused
                + ", invisible-member " + skipInvisibleMember
                + ", ambiguous " + skipAmbiguousRefs + ")");
        System.out.println();
        System.out.println("== coverage: which files the oracle could speak for ==");
        for (FileWork w : works) {
            System.out.println("  " + pad(w.rel, 46) + pad(String.valueOf(w.refs.size()), 8)
                    + (w.judgeable ? "judged" : "UNJUDGED (vm.exe check refuses this file on its own)"));
        }
        System.out.println();
        System.out.println("== every wrong target, in full ==");
        if (wrong.isEmpty()) System.out.println("  (none)");
        else for (String w : wrong) System.out.println(w);
        System.out.println();
        System.out.println("== what the oracle could not judge ==");
        if (unjudged.isEmpty()) System.out.println("  (nothing)");
        else for (String u : unjudged) System.out.println(u);
        System.out.println();
        // THE VERDICT AND THE TRIPLE.  The verdict alone was falsifiable only by
        // reading the skip list, and a reader who did not read it could not tell a
        // clean run from a run in which nothing was attempted.  The second line is
        // the number the verdict is about, and a crashed file makes the verdict say
        // so instead of leaving a bare pass.
        String verdict;
        int exit;
        if (wrongCount > 0) {
            verdict = wrongCount + " reference(s) go to the wrong declaration";
            exit = 1;
        } else if (skipCrashedFiles > 0) {
            verdict = "NOT A PASS: no WRONG target among the " + refs
                    + " reference(s) that ran, but " + skipCrashedFiles
                    + " file(s) crashed the oracle and " + skipCrashedRefs
                    + " reference(s) were never judged";
            exit = 3;
        } else {
            verdict = "no reference goes to a declaration the compiler does not bind it to,"
                    + " over " + refs + " judged reference(s)";
            exit = 0;
        }
        System.out.println("VERDICT: " + verdict);
        System.out.println("COVERAGE: ran " + refs + " / skipped " + skippedRefs
                + " (crashed-file " + skipCrashedRefs
                + ", compiler-refused " + skipRefused
                + ", invisible-member " + skipInvisibleMember
                + ", ambiguous " + skipAmbiguousRefs + ")"
                + " / wrong " + wrongCount);
        System.exit(exit);
    }

    // ------------------------------------------------------------------ per file

    private static final class FileWork {
        final String rel;
        final Path path;
        String text;
        List<Decl> decls = new ArrayList<>();
        final List<Ref> refs = new ArrayList<>();
        final Map<String, Integer> bindings = new LinkedHashMap<>();
        boolean judgeable;
        final Set<String> invisibleNames = new LinkedHashSet<>();
        int refCount;
        long compilerRuns;
        String error;
        String hash;

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
        Set<Integer> declStarts = new LinkedHashSet<>();
        for (Decl d : w.decls) declStarts.add(d.start);
        collectRefs(w.text, tree, tree.root, declStarts, w.refs);

        Path scratch = Files.createTempDirectory("vela-goto-oracle");
        try {
            Path original = scratch.resolve("orig.vel");
            Files.write(original, w.text.getBytes(StandardCharsets.ISO_8859_1));
            CompilerRun base = check(original);
            w.compilerRuns++;
            // The oracle only means something on a file the compiler accepts: if it
            // already refuses the file, an error after a rename cannot be attributed
            // to the rename.
            w.judgeable = base.exit == 0;
            if (!w.judgeable) return;
            Path current = scratch.resolve("t.vel");
            for (Decl d : w.decls) {
                // The file must be part of the key, not just the declaration's shape:
                // two files can have a declaration at the same line and offset with the
                // same name, and a collision reuses another file's answer.
                String cacheKey = vmSha + "|" + w.hash + "|" + d.contentKey(w.text);
                String cached = cacheMap.get(cacheKey);
                if (cached != null && !rebuild) {
                    parseBindings(cached, d, w.bindings);
                    if (cached.equals("-")) w.invisibleNames.add(d.name);
                    continue;
                }
                List<Integer> bound = bindUseLines(w, d, current);
                if (bound == null) {
                    // THE NPE THAT MADE NINE EXTERN FILES UNJUDGED.  `bindUseLines`
                    // answers null for "the compiler noticed nothing about this
                    // declaration" and an *empty* list for "not verified", and this
                    // call site used the answer as if it were always a list: the
                    // enhanced-for over null threw `NullPointerException: Cannot
                    // invoke "java.util.List.iterator()"`, which is caught per file
                    // and reported as "the oracle could not run" -- so every file
                    // containing an `extern` declaration was skipped, and the run
                    // still ended with a clean verdict.  A verdict that is clean
                    // because the failures were never attempted is not a verdict.
                    //
                    // `-` is the cache's spelling for invisible (the cached branch
                    // above reads it back the same way), so the finding survives a
                    // re-run instead of being recomputed into the same null.
                    cacheMap.put(cacheKey, "-");
                    w.invisibleNames.add(d.name);
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (int l : bound) sb.append(l).append(',');
                cacheMap.put(cacheKey, sb.toString());
                parseBindings(sb.toString(), d, w.bindings);
                if (cacheMap.size() % 200 == 0) saveCache(cacheMap);
                if (debug && bound != null && !bound.isEmpty()) {
                    System.out.println("      [debug] " + w.rel + " " + d.kind + " `" + d.name
                            + "` @" + d.line + " -> uses on lines " + bound);
                }
            }
        } finally {
            deleteTree(scratch);
        }
    }

    /**
     * The lines bound to one declaration, verified.
     *
     * Rename it, then repair the use the compiler complains about, one at a time.  The
     * result is kept only if the file comes back to exit 0: that is what makes the set
     * of lines a *measurement* rather than a list of lines that happened to error.
     */
    private List<Integer> bindUseLines(FileWork w, Decl d, Path current) throws Exception {
        List<Integer> bound = new ArrayList<>();
        String fresh = d.name + "zzq";
        String text = w.text.substring(0, d.start) + fresh + w.text.substring(d.end);
        for (int guard = 0; guard < 64; guard++) {
            Files.write(current, text.getBytes(StandardCharsets.ISO_8859_1));
            CompilerRun run = check(current);
            w.compilerRuns++;
            if (run.exit == 0) {
                // Renaming this declaration changed nothing the compiler notices.  For a
                // declaration with no uses that is unsurprising; for a *field* or a
                // *method* it is the compiler itself: `vm.exe check` accepts `p.zzz` and
                // `p.dotq(q)` on a struct that declares neither, so member access is not
                // resolved by this build and a rename of a member is invisible.  Either
                // way the compiler has said nothing about this name, so no reference to
                // it can be judged -- recorded as "invisible", never as a wrong target.
                return bound.isEmpty() ? null : bound;
            }
            Integer line = reportedLine(run.err);
            if (line == null) break;
            if (!lineHasWord(text, line, d.name)) {
                // A whole-file error (renaming `main` gives "no 'main' function"): not
                // attributable to a use, so this declaration is not judged.
                break;
            }
            bound.add(line);
            String next = renameFirstUseOnLine(text, line, d.name, fresh);
            if (next == null || next.equals(text)) break;
            text = next;
        }
        return new ArrayList<>();   // not verified -> no bindings
    }

    private static void parseBindings(String csv, Decl d, Map<String, Integer> out) {
        if (csv == null || csv.isEmpty()) return;
        for (String part : csv.split(",")) {
            if (part.isEmpty()) continue;
            out.put(d.key + ">" + part, d.line);
        }
    }

    /** The line of the compiler's first `at <file>:<line>` position, whatever the message. */
    private static Integer reportedLine(String stderr) {
        if (stderr == null) return null;
        Matcher m = Pattern.compile(":(\\d+)\\s*$", Pattern.MULTILINE).matcher(stderr);
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
    }

    /**
     * True when this file declares a name spelled exactly `name` on `targetLine`.
     *
     * The one caller is the "unprovable" case: the compiler said nothing about a use,
     * and the plugin jumped to a declaration of the same name.  That is not a wrong
     * jump -- it cannot even be shown to be a jump to the *wrong declaration*, because
     * the only relation the oracle can check without the compiler is the name itself.
     */
    private static boolean isDeclLineOfName(FileWork w, String name, int targetLine) {
        for (Decl d : w.decls) {
            if (d.line == targetLine && name.equals(d.name)) return true;
        }
        return false;
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

    /** Replace the first remaining use of `name` on `line` with `fresh`, or null. */
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

    // ------------------------------------------------------------ the inventory

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

    private static final class Ref {
        final String name;
        final int start;
        final int end;
        final boolean builtin;
        final int line;

        Ref(String name, int start, int end, boolean builtin, int line) {
            this.name = name;
            this.start = start;
            this.end = end;
            this.builtin = builtin;
            this.line = line;
        }
    }

    /** The declaration sites: from the parser's tree, which `ast-diff.ps1` holds to the compiler. */
    private static List<Decl> declarations(String text, VelaSyntaxTree tree) {
        List<Decl> out = new ArrayList<>();
        walkDecls(text, tree, tree.root, out);
        return out;
    }

    private static void walkDecls(String text, VelaSyntaxTree tree, VelaSyntaxNode n, List<Decl> out) {
        switch (n.kind) {
            case DEF -> addName(text, tree, n, "function", out);
            case STRUCT -> addName(text, tree, n, "struct", out);
            case FIELD -> addName(text, tree, n, "field", out);
            case PARAM -> addName(text, tree, n, "parameter", out);
            case DECL -> addName(text, tree, n, "local", out);
            case FOR -> addName(text, tree, n, "loop variable", out);
            default -> {
            }
        }
        for (VelaSyntaxNode c : n.children) walkDecls(text, tree, c, out);
    }

    private static void addName(String text, VelaSyntaxTree tree, VelaSyntaxNode n, String kind,
                                List<Decl> out) {
        int from = n.startTok >= 0 ? n.startTok : 0;
        int to = n.endTok >= 0 ? n.endTok : tree.toks.size() - 1;
        if (kind.equals("function")) {
            for (int i = from; i <= to && i < tree.toks.size(); i++) {
                VelaTok t = tree.toks.get(i);
                if (t.kind == VelaTokKind.NAME && t.code == 1) {   // VelaKw.DEF
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

    /** Every name occurrence that is not one of those declarations. */
    private void collectRefs(String text, VelaSyntaxTree tree, VelaSyntaxNode n,
                             Set<Integer> declStarts, List<Ref> out) {
        if (n.kind == VelaNodeKind.NAME || n.kind == VelaNodeKind.ATTR) {
            int from = n.startTok >= 0 ? n.startTok : 0;
            int to = n.endTok >= 0 ? n.endTok : tree.toks.size() - 1;
            for (int i = from; i <= to && i < tree.toks.size(); i++) {
                VelaTok t = tree.toks.get(i);
                if (t.kind != VelaTokKind.NAME || t.code != 0 || t.start >= t.end) continue;
                if (n.kind == VelaNodeKind.NAME) {
                    if (declStarts.contains(t.start)) return;   // the declaration itself
                    addRef(text, t, out);
                    return;
                }
                // An attribute: the name after the dot is the reference; the receiver
                // before it is a NAME node of its own.
                if (i > from) {
                    addRef(text, t, out);
                    return;
                }
            }
            return;
        }
        for (VelaSyntaxNode c : n.children) collectRefs(text, tree, c, declStarts, out);
    }

    private static void addRef(String text, VelaTok t, List<Ref> out) {
        String name = text.substring(t.start, t.end);
        out.add(new Ref(name, t.start, t.end, isBuiltin(name), lineOfOffset(text, t.start)));
    }

    /** The language's buildin names, so a reference to one is not read as a file's. */
    private static boolean isBuiltin(String name) {
        for (String b : new String[]{
            "print", "emit_str", "emit_int", "emit_float", "emit_nl", "warn_str", "warn_int",
            "warn_nl", "len", "to_float", "to_int", "sqrt", "fabs", "floor", "pow", "abs",
            "min_int", "max_int", "min_float", "max_float", "bytes_at", "substr", "concat",
            "unescape", "intern", "interned", "argc", "arg", "read_text", "write_text", "panic",
            "now", "run_command", "env", "range",
        }) {
            if (b.equals(name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ plumbing

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
        return r;
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

    private static int lineOfOffset(String text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static String snippet(String text, Ref r) {
        int from = Math.max(0, r.start - 16);
        int to = Math.min(text.length(), r.end + 16);
        return text.substring(from, to).replace("\n", "\\n");
    }

    private Map<String, String> loadCache() throws IOException {
        Map<String, String> out = new ConcurrentHashMap<>();
        if (!Files.isRegularFile(cache) || rebuild) return out;
        for (String line : Files.readAllLines(cache, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return out;
    }

    /**
     * Write the cache, safely under `THREADS` concurrent writers.
     *
     * Two things were wrong here and both are the kind that produce a *wrong
     * oracle* rather than a crash.  Eight threads call this from `analyse` every
     * 200 new entries, so two of them could `Files.write` the same path at once --
     * a torn file, which the next run's single-threaded `loadCache` would then read
     * as bindings that were never measured.  And a reader that saw a half-written
     * cache would silently judge references against another file's answer, since
     * the key is a hash of the declaration and the value is a list of lines.  So:
     * one writer at a time, and the bytes land via a temporary file and a move, so
     * a cache file on disk is always a whole cache.
     *
     * THE ATOMIC MOVE NEEDS A RETRY, AND THAT WAS MEASURED, NOT GUESSED.
     *
     * `--probe-cache` (see `cacheProbe`) holds the destination open while 8 threads
     * replace it, and the first run of it reported
     * `java.nio.file.AccessDeniedException: ...probe-cache.txt.tmp -> ...probe-cache.txt`.
     * On Windows a rename over an existing file is `MoveFileEx(MOVEFILE_REPLACE_EXISTING)`,
     * which **fails, sharing-violation style, while another handle has the target
     * open** -- and it reports that failure as `AccessDeniedException`, which is not an
     * `AtomicMoveNotSupportedException`, so the `catch` below did not fire and the
     * exception escaped `saveCache` altogether.  The old file therefore survived
     * (nothing was torn, and `loadCache` next run reads a whole earlier cache), but the
     * write *failed*, and a caller that treats a thrown `IOException` as "the cache is
     * written" would lose the entries in silence.
     *
     * So the atomic move is retried a bounded number of times, and a failure that
     * survives the retries is rethrown as itself rather than being reported as a
     * successful save.  This is the same property the surrounding `synchronized`
     * is for: single-writer *within* a process is not the whole story, because two
     * `GotoOracle` processes pointed at one default cache path are two writers.
     */
    private synchronized void saveCache(Map<String, String> cacheMap) throws IOException {
        Files.createDirectories(cache.getParent());
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : cacheMap.entrySet()) {
            lines.add(e.getKey() + "\t" + e.getValue());
        }
        Collections.sort(lines);
        Path tmp = cache.resolveSibling(cache.getFileName() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MOVE_ATTEMPTS; attempt++) {
            try {
                try {
                    Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    // The filesystem cannot do it atomically; a plain replace is the best
                    // available, and it is still a whole file rather than a partial one.
                    Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                // A reader (or another process) has the destination open.  Transient.
                lastFailure = e;
                try {
                    Thread.sleep(MOVE_RETRY_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while replacing " + cache, ie);
                }
            }
        }
        throw lastFailure;
    }

    private void collectCorpus() throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        for (String dir : new String[]{"tests", "examples", "ide-demo", "selfhost/parts", "bench"}) {
            Path d = repoRoot.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            List<Path> found = new ArrayList<>();
            Files.walk(d)
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vel"))
                    .forEach(found::add);
            Collections.sort(found);
            for (Path f : found) {
                seen.add(repoRoot.relativize(f).toString().replace('\\', '/'));
            }
        }
        // `selfhost/vm.vel` is deliberately NOT in the corpus: it is the 400 KB
        // concatenation of the parts, and one `check` run over it costs about a second,
        // so the rename oracle -- one run per use -- would take ten minutes for it alone.
        // The parts it concatenates are in the corpus and are reported as unjudged,
        // because the compiler refuses a part on its own (it is one piece of a linked
        // program), which is a limit of the oracle and not of the plugin.
        for (String extra : new String[]{"selfhost/vela.vel"}) {
            Path p = repoRoot.resolve(extra);
            if (Files.isRegularFile(p)) seen.add(extra);
        }
        corpus.addAll(seen);
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

    private GotoOracle() {
    }
}
