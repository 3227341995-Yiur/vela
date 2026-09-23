import com.intellij.lexer.Lexer;
import com.intellij.mock.MockApplication;
import com.intellij.mock.MockProject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.command.UndoConfirmationPolicy;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.util.ProcessingContext;

import dev.vela.plugin.VelaLexer;
import dev.vela.plugin.VelaLeafManipulator;
import dev.vela.plugin.VelaNodeKind;
import dev.vela.plugin.VelaReferenceProvider;
import dev.vela.plugin.VelaReferences;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaTok;
import dev.vela.plugin.VelaTokKind;
import dev.vela.plugin.VelaTokenTypes;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
 * The rename WRITE-BACK, measured by reading the file back: rows 11 and 12 of
 * `FEATURE_PARITY.md` in one run.
 *
 * WHY THIS EXISTS, WHEN `RenameOracle` ALREADY RUNS
 *
 * `RenameOracle` measures which occurrences the compiler binds to a declaration and
 * compares that set with the plugin's reference table.  Its end-to-end column then
 * builds the renamed text *itself*, with a `StringBuilder`: the arithmetic of a rename
 * rather than the rename.  So both of this plugin's rename rows were `partial` for one
 * honest reason -- the registration and the resolution were measured, and **nothing
 * ever renamed anything and read the file back**.  A `StringBuilder` that agrees with
 * the table cannot fail when the write-back is broken, because the write-back is not
 * in it.
 *
 * This tool drives the plugin's own write path instead:
 *
 *   VelaReferenceContributor -> VelaReferenceProvider.getReferencesByElement
 *       -> VelaReference.resolve()          (which leaf is a usage of which declaration)
 *       -> VelaReference.handleElementRename (writes the usage)
 *   VelaLeafManipulator.handleContentChange  (writes the declaration -- the
 *       `lang.elementManipulator` the platform's `ElementManipulators` hands out)
 *
 * The writes happen in a `Document` -- the platform's interface, stood in for and
 * *recording* -- through the plugin's own `document.replaceString(start, end, newName)`
 * calls, inside the plugin's own `CommandProcessor.executeCommand`.  What the platform
 * contributes here is its *interfaces*: `PsiElement`, `PsiFile`, `Project` and
 * `Document` are interfaces and are stood in for by `java.lang.reflect.Proxy` handlers
 * that answer exactly what a flat, one-leaf-per-token PSI and a text buffer would
 * answer; `PsiDocumentManager` and `CommandProcessor` are abstract classes and are
 * stood in for by subclasses that return that document and run the command inline.
 * Nothing in `dev.vela.plugin` is reimplemented, every range and every replacement
 * string below is computed by the plugin's own code, and each write the plugin performs
 * is recorded with the range it asked for (see `DocHandle` for why the platform's own
 * `DocumentImpl` cannot be used headlessly, and what that costs this measurement).
 *
 * WHO ORDERED THE WRITES
 *
 * The platform renames usages in reverse document order (`RenameUtil` sorts the usage
 * list by offset descending) so that a write at a lower offset cannot invalidate the
 * offset of a usage not yet written.  This tool does the same, for the same reason: the
 * leaf ranges are the PSI's ranges and are not recomputed after each write, exactly as
 * they are not recomputed by the platform inside one rename.
 *
 * WHAT IS ASSERTED, PER DECLARATION
 *
 *   1. a copy of a real corpus file is made and hashed, and `vm.exe check` accepts it
 *      (a file the compiler refuses is not judged -- an error after an edit could not
 *      be attributed to the edit);
 *   2. `VelaReferenceProvider` is asked about every identifier leaf, and
 *      `VelaReference.resolve()` decides which leaves are usages of this declaration:
 *      that set, plus the declaration itself, is the write set W.  Nothing else is
 *      written;
 *   3. every one of the |W| writes is performed by the plugin's code, and each one is
 *      *counted*: a write that changed no character (a silent refusal -- `canWrite`
 *      said no and `handleElementRename` returned the element) is a finding, not a
 *      quiet zero;
 *   4. the file is read back **from disk** and asserted equal to the original with
 *      exactly W's ranges replaced by the new name -- byte for byte otherwise;
 *   5. the same property is stated at token level, which is what makes it legible: both
 *      texts are lexed with the plugin's own lexer, the token lists must be the same
 *      length and the same kinds, and a token's text may differ **exactly** where its
 *      original offset is in W, by exactly `name` -> `newName`.  So: every reference to
 *      the symbol changed, and no other identifier in the file changed;
 *   6. `vm.exe check` accepts the file read back (exit 0), and
 *   7. the FALSIFICATION: renaming the declaration and leaving its uses behind is
 *      refused by the compiler.  A measurement of a rename that cannot fail is not a
 *      measurement.  The same write path is used for it -- one write, the declaration --
 *      and the compiler is required to refuse the result; the count of where that
 *      fires is printed, and a run in which it never fires exits 3 rather than
 *      reporting zeros;
 *   8. per occurrence, the tightest form of "every reference changed": renaming W minus
 *      one occurrence must be refused by the compiler (the compiler binds that
 *      occurrence to this declaration, so the write set could not have omitted it).
 *      Where the compiler accepts that, it never resolves the position at all -- the
 *      same unprovable class `RenameOracle` counts -- and it is counted, printed and
 *      never called wrong.
 *
 * THE CONTROL
 *
 * `--control` asks the checker itself to fire: the same comparison is run against a
 * text with one reference deliberately left unchanged, and against a text with one
 * unrelated identifier changed.  Both must be caught, on a real corpus declaration and
 * with the compiler's own verdict on the malformed file printed beside them.  If either
 * is not caught, the tool exits 3: a comparator that cannot fire says nothing with its
 * zeros.
 *
 * THE COVERAGE TRIPLE
 *
 * The unit of `ran`/`skipped` is the DECLARATION, as it is in `RenameOracle`, so the two
 * tools' triples can be read side by side.  Every reason a declaration is not judged has
 * its own counter and is printed even when it is zero.
 *
 * Usage:
 *   java RenameWriteback <repo-root> [--vm <vm.exe>] [--single <file>] [--show]
 *       [--control] [--explain] [--threads <n>] [--max-bytes <n>] [--max-refs <n>]
 *       [--max-declarations <n>]
 *
 * Exit codes: 0 = no finding, 1 = the write-back is wrong (a finding on a declaration),
 * 3 = this harness or its corpus is at fault (a crashed file, a missing corpus entry,
 * the falsification never firing, the control not firing).
 */
public final class RenameWriteback {

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final int THREADS = 8;
    /** Above this many claimed references the leave-one-out sweep is not run. */
    private static final int MAX_REFS = 6;
    private static final int MAX_WRONG_ROWS = 40;
    private static final int MAX_SKIP_ROWS = 24;
    private static final int MAX_UNPROVABLE_ROWS = 12;

    /** The nine reasons a declaration is not judged.  Declared here so a zero is printed. */
    private static final String[] SKIP_KINDS = {
        "compiler-refused-file",        // `vm.exe check` refuses the file itself
        "too-large",                    // over --max-bytes
        "missing-corpus-file",          // a corpus entry that is not on disk
        "no-references-and-silent",     // no usage to write and the compiler notices nothing
        "not-attributable",             // the compiler's complaint is about the program, not a use
        "new-name-refused",             // `VelaReferences.isWritableName` refuses the new name
        "new-name-collides",            // the new name is already an identifier in the file
        "too-many-references",          // more than --max-refs claimed references
        "crashed",                      // this harness threw on the file
        "contradiction",                // the tool's own two measurements disagree
    };

    private Path repoRoot;
    private Path vm;
    private Path scratchRoot;
    private String single;
    private boolean show;
    private boolean control;
    private boolean explain;
    private boolean debug;
    private int threads = THREADS;
    private long maxBytes = MAX_FILE_BYTES;
    private int maxRefs = MAX_REFS;
    private int maxDeclarations = Integer.MAX_VALUE;
    private String vmSha = "";
    private final List<String> corpus = new ArrayList<String>();

    // The platform stand-ins the PSI proxies answer with.  Static because the proxies are
    // static classes and there is one platform per JVM; every one of them is assigned in
    // `bootPlatform` before a single file is read.
    private static Disposable rootDisposable;
    private static MockApplication app;
    private static MockProject project;
    private static StubDocumentManager pdm;
    private static StubCommandProcessor cp;

    private int debugged;
    private int compilerRuns;
    private final java.util.concurrent.atomic.AtomicInteger filesCopied =
            new java.util.concurrent.atomic.AtomicInteger();
    private int corpusFilesWithCrlf;
    private long elapsedMs;

    public static void main(String[] args) throws Exception {
        RenameWriteback tool = new RenameWriteback();
        List<String> rest = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--vm")) tool.vm = Paths.get(args[++i]).toAbsolutePath();
            else if (a.equals("--single")) tool.single = args[++i];
            else if (a.equals("--show")) tool.show = true;
            else if (a.equals("--control")) tool.control = true;
            else if (a.equals("--explain")) tool.explain = true;
            else if (a.equals("--debug")) tool.debug = true;
            else if (a.equals("--threads")) tool.threads = Integer.parseInt(args[++i]);
            else if (a.equals("--max-bytes")) tool.maxBytes = Long.parseLong(args[++i]);
            else if (a.equals("--max-refs")) tool.maxRefs = Integer.parseInt(args[++i]);
            else if (a.equals("--max-declarations")) tool.maxDeclarations = Integer.parseInt(args[++i]);
            else rest.add(a);
        }
        tool.repoRoot = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        tool.run();
    }

    // ------------------------------------------------------------------- the run

    private void run() throws Exception {
        if (vm == null) vm = repoRoot.resolve("selfhost").resolve("build").resolve("vm.exe");
        if (!Files.isRegularFile(vm)) {
            System.out.println("VERDICT: no compiler at " + vm + ", so nothing can be judged");
            System.out.println("COVERAGE: ran 0 / skipped 0 (" + allSkipCountersZero() + ") / wrong 0");
            System.exit(3);
        }
        vmSha = sha256(vm);

        System.out.println("== RenameWriteback: the rename write-back, read back from disk ==");
        System.out.println("driver   : VelaReferenceContributor -> VelaReferenceProvider ->");
        System.out.println("           VelaReference.resolve / handleElementRename, and");
        System.out.println("           VelaLeafManipulator.handleContentChange for the declaration");
        System.out.println("           (the plugin's own write path; the platform supplies the");
        System.out.println("           interfaces, the document and the command processor)");
        System.out.println("compiler : " + vm + " (" + Files.size(vm) + " bytes, sha256 " + vmSha + ")");

        bootPlatform();
        System.out.println("platform : " + ApplicationManager.getApplication().getClass().getName()
                + " (MockApplication) + " + project.getClass().getName());
        System.out.println("           PsiDocumentManager.getInstance(project) is this tool's own: "
                + (PsiDocumentManager.getInstance(project) == pdm));
        System.out.println("           CommandProcessor.getInstance() is this tool's own: "
                + (CommandProcessor.getInstance() == cp));

        if (single != null) corpus.add(single.replace('\\', '/'));
        else collectCorpus();
        System.out.println("corpus   : " + corpus.size() + " file(s), each copied before it is edited");
        System.out.println("           files over " + maxBytes + " bytes are skipped");

        scratchRoot = Files.createTempDirectory("vela-writeback");
        try {
            List<FileWork> works = new ArrayList<FileWork>();
            for (String rel : corpus) {
                FileWork w = new FileWork(rel, repoRoot.resolve(rel));
                Path p = w.path;
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
                        analyse(w);
                    } catch (Throwable t) {
                        w.error = "threw " + t;
                        w.crashed = true;
                        if (explain) t.printStackTrace(System.out);
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
            pool.shutdown();
            elapsedMs = System.currentTimeMillis() - start;

            report(works);
        } finally {
            if (!show) deleteTree(scratchRoot);
        }
    }

    private void bootPlatform() {
        rootDisposable = Disposer.newDisposable();
        app = new MockApplication(rootDisposable);
        ApplicationManager.setApplication(app, rootDisposable);
        project = new MockProject(app.getPicoContainer(), rootDisposable);
        pdm = new StubDocumentManager();
        cp = new StubCommandProcessor();
        project.registerService(PsiDocumentManager.class, pdm);

        // THE TWO SERVICES THE PLUGIN'S WRITE PATH ASKS FOR.
        //
        // The plugin's `handleElementRename` and `handleContentChange` both ask the
        // platform for a `PsiDocumentManager` (to get the document for the file) and
        // wrap their write in `CommandProcessor.executeCommand` (so the edit is one undo
        // step).  Under a mock application neither is registered at all -- each call
        // would be a NullPointerException before the plugin's own line ran -- so both are
        // stood in for, and the assertions below refuse to continue if the platform hands
        // out anything other than these.
        app.registerService(CommandProcessor.class, cp);

        // A silent fallback here would measure the harness, not the plugin: if the plugin
        // asks for one of these and the mock platform hands it something else, every rename
        // below would be performed by whatever answered instead.
        if (PsiDocumentManager.getInstance(project) != pdm) {
            System.out.println("the mock platform did not hand out this tool's PsiDocumentManager,"
                    + " so the writes below would not be the plugin's");
            System.exit(2);
        }
        if (CommandProcessor.getInstance() != cp) {
            System.out.println("the mock platform did not hand out this tool's CommandProcessor,"
                    + " so the writes below would not be the plugin's");
            System.exit(2);
        }
    }

    // --------------------------------------------------------------- a whole file

    private static final class FileWork {
        final String rel;
        final Path path;
        String original = "";
        String fileSha = "";
        Path copy;                       // the pristine copy the compiler is asked about
        Path edit;                       // the copy the write-back edits
        String copySha = "";
        boolean hasCrlf;
        List<Decl> decls = new ArrayList<Decl>();
        List<int[]> leaves = new ArrayList<int[]>();
        FileHandle handle;
        List<Row> rows = new ArrayList<Row>();
        boolean judgeable;
        boolean crashed;
        String error;
        String baseErr = "";

        FileWork(String rel, Path path) {
            this.rel = rel;
            this.path = path;
        }
    }

    private void analyse(FileWork w) throws Exception {
        byte[] raw = Files.readAllBytes(w.path);
        w.hasCrlf = indexOfCrlf(raw);
        w.original = new String(raw, StandardCharsets.ISO_8859_1)
                .replace("\r\n", "\n").replace('\r', '\n');
        w.fileSha = sha256(raw);
        if (w.hasCrlf) corpusFilesWithCrlf++;

        // THE COPY, AND THE PROOF THAT IT IS ONE.  Every edit below happens in
        // `scratchRoot`, never in the checkout: this tool is a guest in a tree other
        // agents are editing, and a measurement that modified the corpus would not be
        // repeatable.
        Path dir = Files.createDirectories(scratchRoot.resolve(
                String.valueOf(filesCopied.getAndIncrement())));
        w.copy = dir.resolve("copy.vel");
        w.edit = dir.resolve("edit.vel");
        byte[] normalized = w.original.getBytes(StandardCharsets.ISO_8859_1);
        Files.write(w.copy, normalized);
        w.copySha = sha256(w.copy);
        if (!w.copySha.equals(sha256(normalized))) {
            throw new IllegalStateException("the copy on disk is not the bytes read: " + w.rel);
        }

        VelaSyntaxTree tree = VelaSyntaxParser.parse(w.original);
        w.decls = declarations(w.original, tree);
        collectLeaves(w);

        CompilerRun base = check(w.copy);
        w.judgeable = base.exit == 0;
        w.baseErr = base.err;
        if (!w.judgeable) return;

        w.handle = new FileHandle(w);
        int judged = 0;
        for (Decl d : w.decls) {
            if (judged >= maxDeclarations) break;
            judge(w, d);
            judged++;
        }
    }

    /** One declaration's row, and every finding it produced. */
    private static final class Row {
        final Decl d;
        String outcome = "judged";
        String note = "";
        final List<Integer> claimed = new ArrayList<Integer>();   // the plugin's usages of d
        final List<Integer> changedIdentifiers = new ArrayList<Integer>();   // where the text moved
        final List<String> findings = new ArrayList<String>();
        int written;                 // writes that landed
        int attempted;               // writes performed
        boolean pathsAgree;
        boolean declOnlyRefused;
        boolean fullCompiled;
        final List<Integer> compilerRequired = new ArrayList<Integer>();
        final List<Integer> compilerSilent = new ArrayList<Integer>();
        String fullError = "";
        String declOnlyError = "";

        Row(Decl d) {
            this.d = d;
        }
    }

    /**
     * One declaration, end to end: the plugin's write set, the plugin's writes, the file
     * read back, and the compiler's verdict on the file and on three variants of it.
     */
    private void judge(FileWork w, Decl d) throws Exception {
        Row row = new Row(d);
        w.rows.add(row);

        // THE DOCUMENT GOES BACK TO THE CORPUS TEXT FIRST, AND IT IS NOT A DETAIL.
        // `VelaReferences.referenceTargetAt` resolves against the file's *text*, so a
        // declaration judged after another one would otherwise be asking about the text
        // some earlier rename left behind -- measured: with this line absent, every
        // declaration after the first was resolved against a file whose earlier offsets had
        // moved by the length of the new name, and the tool reported that the provider
        // found no references to any of them.  A defect in the harness that would have read
        // as a defect in the plugin.
        w.handle.reset();

        // (1) THE PLUGIN'S ANSWER.  One question per identifier leaf, asked of the object
        // `VelaReferenceContributor` registers, and answered by `VelaReference.resolve()`:
        // find-usages and rename read nothing else.
        for (int[] leaf : w.leaves) {
            PsiReference ref = w.handle.referenceAt(leaf[0]);
            if (debug && debugged < 14) {
                Integer direct = VelaReferences.INSTANCE.referenceTargetAt(w.original, leaf[0], leaf[1]);
                System.out.println("    leaf " + leaf[0] + ".." + leaf[1] + " `"
                        + w.original.substring(leaf[0], leaf[1]) + "` provider="
                        + (ref == null ? "none" : "1") + " referenceTargetAt=" + direct
                        + (ref == null ? "" : " resolve=" + (ref.resolve() == null ? "null"
                                : String.valueOf(ref.resolve().getTextRange().getStartOffset()))));
                debugged++;
            }
            if (ref == null) continue;
            PsiElement target = ref.resolve();
            if (target == null) continue;
            if (target.getTextRange().getStartOffset() == d.start) row.claimed.add(leaf[0]);
        }
        if (debug) {
            System.out.println("  decl line " + d.line + " `" + d.name + "` " + d.kind + " start="
                    + d.start + " end=" + d.end + " claimed=" + row.claimed);
        }
        Collections.sort(row.claimed);

        // (2) THE NEW NAME.  Two refusals that are not findings: a name the write-back's own
        // gate refuses, and a name that already occurs in the file (renaming to an existing
        // identifier would collide, and a refusal after that would be about the collision
        // rather than about the rename).
        String fresh = d.name + "zzq";
        if (!VelaReferences.INSTANCE.isWritableName(fresh)) {
            row.outcome = "new-name-refused";
            row.note = "`" + fresh + "` is not a name the write-back accepts";
            return;
        }
        for (int[] leaf : w.leaves) {
            if (w.original.regionMatches(leaf[0], fresh, 0, fresh.length())
                    && leaf[1] - leaf[0] == fresh.length()) {
                row.outcome = "new-name-collides";
                row.note = "`" + fresh + "` is already an identifier in this file (line "
                        + lineOfOffset(w.original, leaf[0]) + ")";
                return;
            }
        }
        if (row.claimed.size() > maxRefs) {
            row.outcome = "too-many-references";
            row.note = row.claimed.size() + " claimed reference(s), over --max-refs " + maxRefs;
            return;
        }

        // (3) THE FALSIFICATION, FIRST, BECAUSE IT DECIDES WHAT THE FULL RENAME MEANS.
        // Renaming the declaration alone must be refused by the compiler: that is the
        // measurement's own ability to fail, and it is performed through the same write
        // path as the rename itself.
        String declOnly = rename(w, d, true, new ArrayList<Integer>());
        Files.write(w.edit, declOnly.getBytes(StandardCharsets.ISO_8859_1));
        CompilerRun only = check(w.edit);
        row.declOnlyRefused = only.exit != 0;
        row.declOnlyError = firstLine(only.err);
        if (row.declOnlyRefused && !attributable(declOnly, only.err, d.name)) {
            // THE COMPILER'S COMPLAINT IS ABOUT THE PROGRAM, NOT ABOUT A USE.  Renaming
            // `main` produces "no 'main' function", whose position is the `def` line
            // itself; nothing can be attributed to a use when the whole program stopped
            // being a program.  `RenameOracle` counts this as `not-attributable`, and so
            // does this tool -- judging it would report a defect that is not one.
            row.outcome = "not-attributable";
            row.note = "renaming the declaration alone is refused for a reason that is about the"
                    + " program rather than a use: " + row.declOnlyError;
            return;
        }
        if (row.claimed.isEmpty()) {
            if (row.declOnlyRefused) {
                // THE COMPILER BINDS USES AND THE PROVIDER CLAIMS NONE.  This is the
                // find-usages direction of the comparison, and the only way the two can
                // disagree in that direction here: the compiler refuses a file whose
                // declaration was renamed while its uses still say the old name, and there
                // is no other declaration of that name that could have kept them alive.
                row.findings.add("MISSED-REFERENCES: renaming this declaration makes the compiler"
                        + " refuse the file -- so the compiler binds at least one use of `"
                        + d.name + "` -- and the reference provider claims none, so a rename"
                        + " built from it leaves every use behind: " + firstLine(only.err));
                row.written = 0;
                row.attempted = 1;
                return;
            }
            row.outcome = "no-references-and-silent";
            row.note = "no reference resolves to this declaration and renaming it is accepted by"
                    + " the compiler, so there is nothing for a rename to write";
            return;
        }

        // (4) THE WRITE-BACK: the declaration through the manipulator, every reference
        // through `VelaReference.handleElementRename`, both in reverse document order.
        // The writes the reference path made are copied out here, before the second path
        // runs and records writes of its own.
        String pluginText = rename(w, d, false, row.claimed);
        List<Write> log = new ArrayList<Write>(w.handle.writes());
        String manipulatorText = renameAll(w, d, row.claimed);
        row.pathsAgree = pluginText.equals(manipulatorText);

        // (5) THE FILE READ BACK FROM DISK.  Not the string in memory: the compiler is
        // asked about a file, so the file is what is compared.
        Files.write(w.edit, pluginText.getBytes(StandardCharsets.ISO_8859_1));
        String readBack = new String(Files.readAllBytes(w.edit), StandardCharsets.ISO_8859_1);

        Set<Integer> writeSet = new TreeSet<Integer>(row.claimed);
        writeSet.add(d.start);
        row.attempted = writeSet.size();
        String expected = splice(w.original, writeSet, d.name.length(), fresh);

        // (5a) THE WRITES THEMSELVES, AS THE PLUGIN MADE THEM.  The document recorded
        // every `replaceString(start, end, newName)` the plugin's code performed, so the
        // claim below is not "the file looks right" but "these characters were written at
        // these ranges, once each, and no others".
        row.written = log.size();
        Set<Integer> wrote = new TreeSet<Integer>();
        for (Write wr : log) {
            wrote.add(wr.start);
            if (!wr.text.equals(fresh)) {
                row.findings.add("WRONG-REPLACEMENT: the write at offset " + wr.start + " put "
                        + quote(wr.text) + " in the document where the rename's own new name is "
                        + quote(fresh));
            }
            if (!w.handle.leafStarts(wr.start) || wr.end - wr.start != d.name.length()) {
                row.findings.add("WRONG-RANGE: the write at " + wr + " is not one whole identifier"
                        + " leaf of " + d.name.length() + " character(s), so the characters it"
                        + " replaced are not the name the reference resolved");
            }
        }
        if (!wrote.equals(writeSet)) {
            List<Integer> extra = minus(wrote, writeSet);
            List<Integer> missingSet = minus(writeSet, wrote);
            row.findings.add("WRONG-WRITE-SET: the write-back wrote "
                    + (extra.isEmpty() ? "no additional range" : offsets(w.original, extra) + ", which"
                    + " is not a reference to this declaration")
                    + (missingSet.isEmpty() ? "" : " and left "
                    + offsets(w.original, missingSet) + " unwritten, which the reference table"
                    + " says are references to it"));
        }
        if (log.size() != row.attempted) {
            row.findings.add("SILENT-WRITE: " + row.attempted + " range(s) were to be written and"
                    + " the document was told to write " + log.size() + " time(s), so at least one"
                    + " write did nothing (a rename that does nothing looks like a rename that"
                    + " worked)");
        }
        if (!readBack.equals(expected)) {
            row.findings.add("WRITEBACK-MISMATCH: the file read back is not the original with"
                    + " exactly the write set replaced. " + firstDifference(w.original, expected, readBack)
                    + " (declaration `" + d.name + "` on line " + d.line + ", write set "
                    + writeSet.size() + " range(s))");
        }
        if (!row.pathsAgree) {
            row.findings.add("PATHS-DISAGREE: the write through VelaReference.handleElementRename"
                    + " and the write through VelaLeafManipulator.handleContentChange produce"
                    + " different files, and the platform's rename uses both (the manipulator for"
                    + " the declaration, the reference for each usage)");
        }

        // (6) THE TOKEN-LEVEL STATEMENT OF (a) AND (b).  This is the legible form of
        // "every reference to the symbol changed and no other identifier changed": both
        // texts are lexed, the token lists must line up, and a token's text may differ
        // exactly where its original offset was written.
        compareTokens(w.original, readBack, d, row, writeSet, fresh);

        // (7) THE COMPILER ON THE FILE READ BACK.
        CompilerRun full = check(w.edit);
        row.fullCompiled = full.exit == 0;
        row.fullError = firstLine(full.err);
        if (!row.fullCompiled) {
            String why = row.declOnlyRefused
                    ? "MISSED: the compiler refused the declaration-only rename, so it binds at"
                    + " least one use of `" + d.name + "`, and it also refuses the file the"
                    + " write-back produced -- the write set does not cover every use the"
                    + " compiler requires"
                    : "WRITEBACK-REFUSED: the compiler refuses the file the write-back produced";
            row.findings.add(why + ": " + row.fullError);
        }

        // (8) PER OCCURRENCE: leaving any one of them out must be refused, which is what
        // makes "every reference changed" a measurement rather than a definition.
        for (int o : row.claimed) {
            List<Integer> rest = new ArrayList<Integer>(row.claimed);
            rest.remove(Integer.valueOf(o));
            String without = rename(w, d, false, rest);
            Files.write(w.edit, without.getBytes(StandardCharsets.ISO_8859_1));
            CompilerRun r = check(w.edit);
            if (r.exit != 0) row.compilerRequired.add(o);
            else row.compilerSilent.add(o);
        }
        Files.write(w.edit, pluginText.getBytes(StandardCharsets.ISO_8859_1));

        // (9) THE TOOL'S OWN CONTRADICTION, counted apart from a plugin defect.  If the
        // compiler requires some claimed occurrence, then a rename of the declaration
        // alone must be refused -- unless another declaration of the same name is in scope
        // at that occurrence, which is the shadowing case `RenameOracle` counts as
        // NOT-REQUIRED.  With no such second declaration, a disagreement here means one of
        // the two measurements above is wrong and nothing below may be read as agreement.
        boolean sameNameElsewhere = false;
        for (Decl other : w.decls) {
            if (other != d && other.name.equals(d.name) && other.start != d.start) {
                sameNameElsewhere = true;
            }
        }
        if (!sameNameElsewhere && !row.compilerRequired.isEmpty() && !row.declOnlyRefused) {
            row.outcome = "contradiction";
            row.note = "the compiler requires occurrence(s) " + row.compilerRequired
                    + " of `" + d.name + "` and accepts the rename of its declaration alone,"
                    + " with no other declaration of that name in the file";
        }
    }

    /**
     * The plugin's writes, and the text they produce.
     *
     * `declOnly` writes only the declaration, through the manipulator -- the
     * falsification.  Otherwise every offset in `set` is written in reverse document
     * order: the declaration through `VelaLeafManipulator.handleContentChange`, each
     * usage through `handleElementRename` on the reference the provider gives for that
     * leaf.
     */
    private String rename(FileWork w, Decl d, boolean declOnly, List<Integer> set) throws Exception {
        List<Integer> order = new ArrayList<Integer>(set);
        if (declOnly) {
            // THE FALSIFICATION: the declaration, and nothing else.  A rename that wrote
            // every use and no declaration would be as wrong as this one, and this one is
            // the one the compiler can be asked about.
            order.clear();
            order.add(d.start);
        } else {
            order.add(d.start);
        }
        Collections.sort(order, Collections.reverseOrder());
        String fresh = d.name + "zzq";
        // THE REFERENCES ARE COLLECTED BEFORE THE FIRST WRITE, as the platform collects
        // them: `VelaReferences.referenceTargetAt` reads the file's *text*, so asking it
        // after a write would be asking it about a file that no longer says what the
        // offsets in this list mean.
        Document document = w.handle.reset();
        Map<Integer, PsiReference> refs = new LinkedHashMap<Integer, PsiReference>();
        if (!declOnly) {
            for (int start : set) {
                PsiReference ref = w.handle.referenceAt(start);
                if (ref == null) {
                    throw new IllegalStateException("the provider gave no reference for the leaf at "
                            + start + " of `" + d.name + "` in " + w.rel);
                }
                refs.put(start, ref);
            }
        }
        for (int start : order) {
            if (start == d.start) {
                // The declaration: the platform renames it through the manipulator it has
                // for this leaf, not through a reference.
                VelaLeafManipulator.INSTANCE.handleContentChange(w.handle.leafAt(start), fresh);
            } else {
                refs.get(start).handleElementRename(fresh);
            }
        }
        return document.getText();
    }

    /** The same write set, written through the manipulator for every element. */
    private String renameAll(FileWork w, Decl d, List<Integer> set) throws Exception {
        Document document = w.handle.reset();
        List<Integer> order = new ArrayList<Integer>(set);
        order.add(d.start);
        Collections.sort(order, Collections.reverseOrder());
        String fresh = d.name + "zzq";
        for (int start : order) {
            VelaLeafManipulator.INSTANCE.handleContentChange(w.handle.leafAt(start), fresh);
        }
        return document.getText();
    }

    /**
     * (a) and (b), at token level.
     *
     * The two texts are lexed with the plugin's own lexer.  The token lists must have the
     * same length and the same kinds -- a write that split, merged or deleted a token is a
     * finding -- and a token's text may differ exactly where its original offset is in the
     * write set, where it must differ by `name` -> `fresh`.  Everything else must be
     * identical, which is the statement "no other identifier in the file changed".
     */

    /**
     * (a) and (b), at token level.
     *
     * The two texts are lexed with the plugin's own lexer.  The token lists must have the
     * same length and the same kinds -- a write that split, merged or deleted a token is a
     * finding -- and a token's text may differ exactly where its original offset is in the
     * write set, where it must differ by `name` -> `fresh`.  Everything else must be
     * identical, which is the statement "no other identifier in the file changed".
     */
    private static void compareTokens(String original, String written, Decl d, Row row,
                                       Set<Integer> writeSet, String fresh) {
        List<Object[]> a = tokens(original);
        List<Object[]> b = tokens(written);
        if (a.size() != b.size()) {
            row.findings.add("TOKEN-COUNT: the file read back has " + b.size() + " token(s) and"
                    + " the original has " + a.size() + ", so a write changed the tokenisation");
            return;
        }
        List<Integer> outsideSet = new ArrayList<Integer>();
        List<Integer> inSet = new ArrayList<Integer>();
        for (int i = 0; i < a.size(); i++) {
            String kindA = (String) a.get(i)[0];
            String kindB = (String) b.get(i)[0];
            int startA = (Integer) a.get(i)[1];
            String textA = (String) a.get(i)[2];
            String textB = (String) b.get(i)[2];
            if (!kindA.equals(kindB)) {
                row.findings.add("TOKEN-KIND: the token at offset " + startA + " was `" + kindA
                        + "` (" + quote(textA) + ") and is `" + kindB + "` (" + quote(textB)
                        + ") after the write-back");
                continue;
            }
            if (textA.equals(textB)) continue;
            boolean inWriteSet = writeSet.contains(startA);
            if (inWriteSet && textA.equals(d.name) && textB.equals(fresh)) {
                inSet.add(startA);
            } else if (inWriteSet) {
                row.findings.add("WRONG-CHARACTERS: offset " + startA + " is in the write set and"
                        + " the token went from " + quote(textA) + " to " + quote(textB)
                        + ", not to " + quote(fresh) + " -- the write used the wrong range or the"
                        + " wrong replacement");
            } else {
                outsideSet.add(startA);
            }
        }
        row.changedIdentifiers.addAll(inSet);
        if (!outsideSet.isEmpty()) {
            row.findings.add("OTHER-IDENTIFIER-CHANGED: " + outsideSet.size() + " token(s) outside"
                    + " the write set changed, at " + offsets(original, outsideSet) + " -- a rename"
                    + " that rewrites something it did not resolve to this declaration");
        }
        // The same statement from the other side, so a write set that is too *large* cannot
        // hide behind a small diff: every range the plugin claimed must appear here as a
        // changed token.
        List<Integer> missing = new ArrayList<Integer>();
        for (int start : writeSet) {
            if (!inSet.contains(start)) missing.add(start);
        }
        if (!missing.isEmpty()) {
            row.findings.add("UNCHANGED-REFERENCE: " + missing.size() + " range(s) of the write set"
                    + " are unchanged in the file read back, at " + offsets(original, missing)
                    + " -- those are references the rename did not write");
        }
    }

    /** Every token of a text, as {kind, start, text}, lexed by the plugin's own lexer. */
    private static List<Object[]> tokens(String text) {
        List<Object[]> out = new ArrayList<Object[]>();
        Lexer lexer = new VelaLexer();
        lexer.start(text);
        while (lexer.getTokenType() != null) {
            out.add(new Object[] { lexer.getTokenType().toString(), lexer.getTokenStart(),
                    text.substring(lexer.getTokenStart(), lexer.getTokenEnd()) });
            lexer.advance();
        }
        return out;
    }

    // ------------------------------------------------------------------ the report

    private void report(List<FileWork> works) throws Exception {
        int exit = 0;
        Map<String, long[]> byKind = new TreeMap<String, long[]>();
        Map<String, Integer> skips = new LinkedHashMap<String, Integer>();
        for (String k : SKIP_KINDS) skips.put(k, 0);
        List<String> wrong = new ArrayList<String>();
        // EVERY SKIP CATEGORY GETS ITS OWN ROWS, not a shared budget.  With one shared cap
        // the first category consumed it and a counted skip could be invisible: measured in
        // this tool's first full run, where `not-attributable 135` was in the counters and
        // not one of its 135 rows could be seen.  A skip nobody can look at is a number
        // pretending to be a measurement.
        Map<String, List<String>> skippedRows = new LinkedHashMap<String, List<String>>();
        for (String k : SKIP_KINDS) skippedRows.put(k, new ArrayList<String>());
        List<String> unprovableRows = new ArrayList<String>();
        List<String> falsificationRows = new ArrayList<String>();

        long judged = 0;
        long wrongDecls = 0;
        long claimedRefs = 0;
        long writes = 0;
        long writesAttempted = 0;
        long required = 0;
        long silent = 0;
        long declOnlyRefused = 0;
        long fullCompiled = 0;
        long pathsAgree = 0;
        long filesJudged = 0;
        long crashedFiles = 0;
        long missingFiles = 0;
        Row controlRow = null;
        FileWork controlFile = null;

        if (show) {
            System.out.println("== every declaration: file, line, kind, name, claimed references,"
                    + " writes, compiler-required, verdict ==");
        }
        for (FileWork w : works) {
            if (w.error != null) {
                String kind = w.error.equals("not there") ? "missing-corpus-file"
                        : (w.error.startsWith("over ") ? "too-large" : "crashed");
                if (kind.equals("crashed")) crashedFiles++;
                if (kind.equals("missing-corpus-file")) missingFiles++;
                skips.put(kind, skips.get(kind) + 1);
                addSkipRow(skippedRows, kind, "  " + w.rel + ": no declaration judged (" + w.error + ")");
                continue;
            }
            if (!w.judgeable) {
                skips.put("compiler-refused-file",
                        skips.get("compiler-refused-file") + w.decls.size());
                addSkipRow(skippedRows, "compiler-refused-file", "  " + w.rel + ": `vm.exe check` refuses this file, so its "
                        + w.decls.size() + " declaration(s) are not judged: "
                        + firstLine(w.baseErr));
                continue;
            }
            filesJudged++;
            for (Row r : w.rows) {
                long[] row = byKind.get(r.d.kind);
                if (row == null) {
                    row = new long[6];   // judged, claimed, written, required, silent, wrong
                    byKind.put(r.d.kind, row);
                }
                if (!r.outcome.equals("judged")) {
                    String k = skips.containsKey(r.outcome) ? r.outcome : "crashed";
                    skips.put(k, skips.get(k) + 1);
                    if (r.outcome.equals("contradiction")) {
                        if (wrong.size() < MAX_WRONG_ROWS) {
                            wrong.add("  " + w.rel + ":" + r.d.line + " `" + r.d.name + "`: HARNESS"
                                    + " CONTRADICTION -- " + r.note);
                        }
                        exit = 3;
                    } else {
                        addSkipRow(skippedRows, k, "  " + w.rel + ":" + r.d.line + " `" + r.d.name
                                + "` (" + r.d.kind + "): not judged [" + r.outcome + "]"
                                + (r.note.isEmpty() ? "" : " " + r.note));
                    }
                    continue;
                }
                if (r.claimed.isEmpty() && !r.declOnlyRefused) {
                    skips.put("no-references-and-silent",
                            skips.get("no-references-and-silent") + 1);
                    addSkipRow(skippedRows, "no-references-and-silent", "  " + w.rel + ":"
                            + r.d.line + " `" + r.d.name + "` (" + r.d.kind + ")");
                    continue;
                }
                judged++;
                row[0]++;
                row[1] += r.claimed.size();
                row[2] += r.written;
                row[3] += r.compilerRequired.size();
                row[4] += r.compilerSilent.size();
                claimedRefs += r.claimed.size();
                writes += r.written;
                writesAttempted += r.attempted;
                required += r.compilerRequired.size();
                silent += r.compilerSilent.size();
                if (r.declOnlyRefused) declOnlyRefused++;
                if (r.fullCompiled) fullCompiled++;
                if (r.pathsAgree) pathsAgree++;
                if (controlRow == null && !r.claimed.isEmpty()) {
                    controlRow = r;
                    controlFile = w;
                }
                if (!r.compilerSilent.isEmpty() && unprovableRows.size() < MAX_UNPROVABLE_ROWS) {
                    unprovableRows.add("  " + w.rel + ":" + r.d.line + " `" + r.d.name + "` ("
                            + r.d.kind + "): the table claims " + offsets(w.original, r.compilerSilent)
                            + ", and renaming the declaration plus every other claimed reference"
                            + " compiles, so the compiler does not bind that occurrence to this"
                            + " declaration.  Counted, never called wrong: the compiler's silence"
                            + " is not a denial");
                }
                if (r.declOnlyRefused && falsificationRows.size() < 6) {
                    falsificationRows.add("  " + w.rel + ":" + r.d.line + " `" + r.d.name + "` ("
                            + r.d.kind + "): declaration-only rename refused -- "
                            + firstLine(r.declOnlyError));
                }
                if (!r.findings.isEmpty()) {
                    row[5]++;
                    wrongDecls++;
                    if (exit == 0) exit = 1;
                    if (wrong.size() < MAX_WRONG_ROWS) {
                        wrong.add("  " + w.rel + ":" + r.d.line + " `" + r.d.name + "` ("
                                + r.d.kind + "), " + r.claimed.size() + " claimed reference(s), "
                                + r.written + "/" + r.attempted + " write(s) landed");
                        for (String f : r.findings) wrong.add("      " + f);
                        if (explain) {
                            wrong.add("      [explain] the compiler on the file read back: "
                                    + r.fullError);
                            wrong.add("      [explain] the compiler on the declaration-only rename: "
                                    + r.declOnlyError);
                        }
                    }
                }
                if (show) {
                    System.out.println("  " + pad(w.rel, 40) + pad(String.valueOf(r.d.line), 6)
                            + pad(r.d.kind, 13) + pad(r.d.name, 18)
                            + pad(String.valueOf(r.claimed.size()), 9)
                            + pad(r.written + "/" + r.attempted, 9)
                            + pad(String.valueOf(r.compilerRequired.size()), 9)
                            + (r.findings.isEmpty() ? "correct"
                                    : "WRONG (" + r.findings.size() + " finding(s))"));
                }
            }
        }

        System.out.println("== per kind: declarations judged, and reference-level numbers ==");
        System.out.println(pad("kind", 16) + pad("decls", 8) + pad("refs", 8) + pad("written", 9)
                + pad("required", 10) + pad("silent", 8) + "wrong decls");
        System.out.println("-".repeat(16) + "  " + "-".repeat(6) + "  " + "-".repeat(6) + "  "
                + "-".repeat(7) + "  " + "-".repeat(8) + "  " + "-".repeat(6) + "  " + "-".repeat(11));
        for (Map.Entry<String, long[]> e : byKind.entrySet()) {
            long[] v = e.getValue();
            System.out.println(pad(e.getKey(), 16) + pad(String.valueOf(v[0]), 8)
                    + pad(String.valueOf(v[1]), 8) + pad(String.valueOf(v[2]), 9)
                    + pad(String.valueOf(v[3]), 10) + pad(String.valueOf(v[4]), 8) + v[5]);
        }
        System.out.println();
        System.out.println("  files judged (the compiler accepts the copy) : " + filesJudged
                + " of " + corpus.size() + " file(s) in the corpus");
        System.out.println("  judged declarations                        : " + judged);
        System.out.println("  claimed references (the plugin's answer)   : " + claimedRefs);
        System.out.println("  writes landed / attempted by the plugin    : " + writes + " / "
                + writesAttempted);
        System.out.println("  the file read back compiles               : " + fullCompiled + " of "
                + judged);
        System.out.println("  the two write paths agree                 : " + pathsAgree + " of "
                + judged);
        System.out.println("  the falsification fires (declaration alone refused) : " + declOnlyRefused
                + " of " + judged);
        System.out.println("  compiler-required claimed references      : " + required + "   (leaving"
                + " one out is refused: the write set could not have omitted it)");
        System.out.println("  compiler-silent claimed references        : " + silent + "   (counted,"
                + " never called wrong)");
        System.out.println("  wrong declarations                        : " + wrongDecls);
        System.out.println("  compiler processes                        : " + compilerRuns
                + " in this run (" + (elapsedMs / 1000) + " s, " + threads + " threads)");
        System.out.println("  corpus files that had CRLF                : " + corpusFilesWithCrlf
                + (corpusFilesWithCrlf > 0 ? "   (the copy is the file normalised to \\n, which is"
                        + " stated because it is a difference: the copy is what the compiler is"
                        + " asked about)" : ""));
        System.out.println();
        System.out.println("== the falsification: renaming the declaration and leaving its uses"
                + " behind ==");
        System.out.println("  refused by the compiler for " + declOnlyRefused + " declaration(s);"
                + " accepted for " + (judged - declOnlyRefused) + ", which are the declarations"
                + " the compiler binds no use of (nothing to falsify)");
        if (falsificationRows.isEmpty()) System.out.println("  (none: THE FALSIFICATION NEVER FIRED,"
                + " so a rename that skipped every use would have looked like a rename that"
                + " worked -- see the verdict)");
        else for (String s : falsificationRows) System.out.println(s);
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
        boolean anySkipRow = false;
        for (Map.Entry<String, List<String>> e : skippedRows.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            anySkipRow = true;
            int shown = e.getValue().size();
            if (shown < skips.get(e.getKey())) {
                e.getValue().add("  (" + (skips.get(e.getKey()) - shown) + " more of this kind, not printed)");
            }
            for (String s : e.getValue()) System.out.println(s);
        }
        if (!anySkipRow) System.out.println("  (nothing)");
        System.out.println();
        System.out.println("== every wrong declaration, in full ==");
        if (wrong.isEmpty()) System.out.println("  (none)");
        else for (String s : wrong) System.out.println(s);
        System.out.println();
        System.out.println("== claimed references the compiler never requires (unprovable) ==");
        if (unprovableRows.isEmpty()) System.out.println("  (none)");
        else for (String s : unprovableRows) System.out.println(s);
        System.out.println();

        boolean controlOk = true;
        if (control) {
            controlOk = controlProbe(controlFile, controlRow);
            if (!controlOk) exit = 3;
        }

        String verdict;
        if (wrongDecls > 0) {
            verdict = wrongDecls + " declaration(s) of " + judged + " judged are wrong: the"
                    + " write-back did not produce the file its own write set describes, or the"
                    + " compiler refuses the file it produced";
            exit = 1;
        } else if (crashedFiles > 0) {
            verdict = "NOT A PASS: no wrong answer among the " + judged + " judged declaration(s),"
                    + " but " + crashedFiles + " file(s) crashed this harness";
            exit = 3;
        } else if (missingFiles > 0) {
            verdict = "NOT A PASS: no wrong answer among the " + judged + " judged declaration(s),"
                    + " but " + missingFiles + " corpus entry(ies) are not on disk";
            exit = 3;
        } else if (declOnlyRefused == 0) {
            verdict = "NOT A PASS: the falsification never fired -- the compiler accepted the"
                    + " rename of a declaration whose uses were left behind for all " + judged
                    + " judged declaration(s), so this run cannot tell a working rename from one"
                    + " that writes nothing";
            exit = 3;
        } else {
            verdict = "for every one of the " + judged + " judged declaration(s), the plugin's own"
                    + " write-back wrote exactly the " + claimedRefs + " reference(s) it claims and"
                    + " the declaration, changed no other token in the file, and produced a file"
                    + " the compiler accepts; renaming the declaration alone is refused by the"
                    + " compiler for " + declOnlyRefused + " of them, and leaving out any one"
                    + " compiler-required reference is refused for " + required + " of them";
            exit = 0;
        }
        System.out.println("VERDICT: " + verdict);
        System.out.println("COVERAGE: ran " + judged + " / skipped " + skipTotal + " (" + cats
                + ") / wrong " + wrongDecls);
        System.exit(exit);
    }

    /**
     * The comparator, asked to fire, on a real corpus declaration.
     *
     * Three texts are handed to the same comparison the run above uses: the file the
     * plugin wrote (which must be clean), the same file with one claimed reference
     * deliberately left unwritten, and the same file with one unrelated identifier
     * deliberately rewritten.  The last two must be caught, and the compiler's verdict on
     * each is printed with them, so the difference between "the tool says so" and "the
     * compiler refuses it" is visible rather than asserted.
     */
    private boolean controlProbe(FileWork w, Row r) throws Exception {
        System.out.println("== control: the comparator asked to fire, on a real declaration ==");
        if (w == null || r == null) {
            System.out.println("  no judged declaration with a reference was available, so the"
                    + " control could not be run at all -- and the comparator's zeros above mean"
                    + " nothing without it");
            return false;
        }
        Decl d = r.d;
        String fresh = d.name + "zzq";
        Set<Integer> writeSet = new TreeSet<Integer>(r.claimed);
        writeSet.add(d.start);
        String expected = splice(w.original, writeSet, d.name.length(), fresh);
        System.out.println("  declaration: " + w.rel + ":" + d.line + " `" + d.name + "` ("
                + d.kind + "), " + r.claimed.size() + " claimed reference(s), write set "
                + writeSet.size() + " range(s)");

        boolean ok = true;

        Row clean = new Row(d);
        compareTokens(w.original, expected, d, clean, writeSet, fresh);
        System.out.println("  the plugin's own write set, compared with the original     : "
                + (clean.findings.isEmpty() ? "no finding (correct)"
                        : clean.findings.size() + " finding(s) " + clean.findings));
        ok &= clean.findings.isEmpty();

        if (!r.claimed.isEmpty()) {
            Row dropped = new Row(d);
            String leftOneOut = splice(w.original, minus(writeSet, r.claimed.get(0)),
                    d.name.length(), fresh);
            compareTokens(w.original, leftOneOut, d, dropped, writeSet, fresh);
            Files.write(w.edit, leftOneOut.getBytes(StandardCharsets.ISO_8859_1));
            CompilerRun cr = check(w.edit);
            System.out.println("  one reference deliberately left unwritten               : "
                    + (dropped.findings.isEmpty() ? "NOT CAUGHT -- the comparator cannot see a"
                    + " reference that was not written" : "caught: " + dropped.findings.get(0)));
            System.out.println("      and the compiler on that file: exit " + cr.exit + " -- "
                    + firstLine(cr.err));
            ok &= !dropped.findings.isEmpty();
            ok &= cr.exit != 0;
        }

        int unrelated = -1;
        for (int[] leaf : w.leaves) {
            if (!writeSet.contains(leaf[0])) {
                unrelated = leaf[0];
                break;
            }
        }
        if (unrelated >= 0) {
            // The unrelated identifier is rewritten *to its own length*, so the token
            // alignment holds and the finding has to be the identifier rule rather than a
            // token count: a control that fires for the wrong reason proves the wrong thing.
            int len = 0;
            for (int[] leaf : w.leaves) {
                if (leaf[0] == unrelated) len = leaf[1] - leaf[0];
            }
            Set<Integer> plus = new TreeSet<Integer>(writeSet);
            plus.add(unrelated);
            Row extra = new Row(d);
            String oneExtra = splice(w.original, plus, len, fresh);
            compareTokens(w.original, oneExtra, d, extra, writeSet, fresh);
            System.out.println("  one unrelated identifier deliberately rewritten at offset "
                    + unrelated + " : " + (extra.findings.isEmpty()
                            ? "NOT CAUGHT -- the comparator cannot see a write outside its own set"
                            : "caught: " + extra.findings.get(0)));
            ok &= !extra.findings.isEmpty();
        }

        System.out.println("  control verdict: " + (ok
                ? "the comparator fires in both directions, so its zeros above are a measurement"
                : "THE COMPARATOR CANNOT FIRE, so its zeros above mean nothing"));
        System.out.println();
        Files.write(w.edit, expected.getBytes(StandardCharsets.ISO_8859_1));
        return ok;
    }

    // ------------------------------------------------------------- the PSI stand-ins

    /**
     * The pieces of PSI this tool has to have, and nothing more.
     *
     * `PsiElement`, `PsiFile` and `Project` are interfaces, so they are proxied.  The
     * answers are the ones a flat, one-leaf-per-token PSI gives: `getText`,
     * `getTextRange`, `getContainingFile`, `getProject`, and `findElementAt` returning the
     * identifier leaf that covers an offset.  Everything else is a default, and every
     * default is a *shape* answer (false, 0, null) rather than a plausible one, so a code
     * path that unexpectedly depends on it fails loudly instead of quietly.
     */
    static final class FileHandle implements InvocationHandler {
        private final FileWork w;
        private final Map<Integer, PsiElement> leafByStart = new LinkedHashMap<Integer, PsiElement>();
        private final PsiElement[] leafByOffset;
        private final DocHandle doc;
        private final Document document;
        private final PsiFile file;
        private final String name;

        FileHandle(FileWork w) {
            this.w = w;
            this.name = w.rel.substring(w.rel.lastIndexOf('/') + 1);
            this.doc = new DocHandle(w.original);
            this.document = (Document) Proxy.newProxyInstance(
                    RenameWriteback.class.getClassLoader(),
                    new Class<?>[] { Document.class }, doc);
            this.leafByOffset = new PsiElement[w.original.length() + 1];
            for (int[] leaf : w.leaves) {
                PsiElement e = (PsiElement) Proxy.newProxyInstance(
                        RenameWriteback.class.getClassLoader(),
                        new Class<?>[] { PsiElement.class }, new LeafHandler(this, leaf[0], leaf[1]));
                leafByStart.put(leaf[0], e);
                for (int i = leaf[0]; i < leaf[1] && i < leafByOffset.length; i++) {
                    leafByOffset[i] = e;
                }
            }
            this.file = (PsiFile) Proxy.newProxyInstance(
                    RenameWriteback.class.getClassLoader(), new Class<?>[] { PsiFile.class }, this);
            pdm.register(file, document);
        }

        /** The document is reset to the corpus text before each write pass. */
        Document reset() {
            doc.reset(w.original);
            return document;
        }

        /** Every write the plugin performed, in order, with the range it asked for. */
        List<Write> writes() {
            return doc.writes();
        }

        String text() {
            return doc.text();
        }

        boolean leafStarts(int offset) {
            return leafByStart.containsKey(offset);
        }

        PsiElement leafAt(int start) {
            PsiElement e = leafByStart.get(start);
            if (e == null) throw new IllegalStateException("no identifier leaf at " + start);
            return e;
        }

        /** The reference the plugin's own provider gives for the leaf at `start`. */
        PsiReference referenceAt(int start) {
            PsiElement leaf = leafByStart.get(start);
            if (leaf == null) return null;
            PsiReference[] refs;
            try {
                refs = VelaReferenceProvider.INSTANCE.getReferencesByElement(leaf, emptyContext());
            } catch (Throwable t) {
                throw new IllegalStateException("the reference provider threw on the leaf at "
                        + start + ": " + t, t);
            }
            return refs.length == 0 ? null : refs[0];
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] a) {
            switch (m.getName()) {
                case "getText": return doc.text();
                case "getProject": return project;
                case "isValid": return true;
                case "isWritable": return true;
                case "isPhysical": return true;
                case "getName": return name;
                case "getVirtualFile": return null;
                case "findElementAt": {
                    int off = (Integer) a[0];
                    if (off < 0 || off >= leafByOffset.length) return null;
                    return leafByOffset[off];
                }
                case "toString": return "file(" + name + ")";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == a[0];
                default: return def(m);
            }
        }
    }

    /** One write the plugin performed: the range it asked for, and what it put there. */
    static final class Write {
        final int start;
        final int end;
        final String text;

        Write(int start, int end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }

        @Override
        public String toString() {
            return "replaceString(" + start + ", " + end + ", `" + text + "`)";
        }
    }

    /**
     * The platform's `Document` interface, stood in for, and *recording*.
     *
     * WHY NOT THE PLATFORM'S OWN `DocumentImpl`: measured, not assumed.  `DocumentImpl`
     * refuses to write without a running IDE -- it asserts a write-access guard extension
     * point (`com.intellij.documentWriteAccessGuard`, declared by the core plugin, which a
     * mock application does not load), then a `CommandProcessor`, then a
     * `FileDocumentManager`, then a `ProgressManager`, and each one that is absent is a
     * NullPointerException inside the platform rather than a result.  So the text model is
     * stood in for; what is NOT stood in for is the plugin's half -- the range, the
     * replacement string and the call are all `dev.vela.plugin`'s, and they are recorded
     * here as the plugin's own `replaceString(start, end, newName)` calls, which is a
     * sharper statement than the resulting text alone: "it wrote these characters at these
     * ranges, once each" rather than "the file looks right".
     */
    static final class DocHandle implements InvocationHandler {
        private String text;
        private final List<Write> writes = new ArrayList<Write>();

        DocHandle(String text) {
            this.text = text;
        }

        void reset(String t) {
            text = t;
            writes.clear();
        }

        String text() {
            return text;
        }

        List<Write> writes() {
            return writes;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] a) {
            int n = m.getParameterCount();
            switch (m.getName()) {
                case "getText":
                    return n == 0 ? text : text.substring(((TextRange) a[0]).getStartOffset(),
                            ((TextRange) a[0]).getEndOffset());
                case "getTextLength":
                    return text.length();
                case "getCharsSequence":
                case "getImmutableCharSequence":
                    return text;
                case "isWritable":
                case "isValid":
                    return true;
                case "isInBulkUpdate":
                    return false;
                case "getModificationStamp":
                    return 0L;
                case "getLineCount":
                    return 1;
                case "setText":
                    writes.clear();
                    text = String.valueOf(a[0]);
                    return null;
                case "replaceString": {
                    int start = (Integer) a[0];
                    int end = (Integer) a[1];
                    String s = String.valueOf(a[2]);
                    writes.add(new Write(start, end, s));
                    text = text.substring(0, start) + s + text.substring(end);
                    return null;
                }
                case "insertString": {
                    int at = (Integer) a[0];
                    String s = String.valueOf(a[1]);
                    writes.add(new Write(at, at, s));
                    text = text.substring(0, at) + s + text.substring(at);
                    return null;
                }
                case "deleteString": {
                    int start = (Integer) a[0];
                    int end = (Integer) a[1];
                    writes.add(new Write(start, end, ""));
                    text = text.substring(0, start) + text.substring(end);
                    return null;
                }
                case "toString":
                    return "document(" + text.length() + " chars, " + writes.size() + " writes)";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == a[0];
                default:
                    return def(m);
            }
        }
    }

    /** One identifier leaf: its text, its absolute range, and where it lives. */
    static final class LeafHandler implements InvocationHandler {
        private final FileHandle owner;
        private final int start;
        private final int end;
        private final String text;

        LeafHandler(FileHandle owner, int start, int end) {
            this.owner = owner;
            this.start = start;
            this.end = end;
            this.text = owner.text().substring(start, end);
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] a) {
            switch (m.getName()) {
                case "getText": return text;
                case "getTextRange": return new TextRange(start, end);
                case "getTextOffset": return start;
                case "getTextLength": return end - start;
                case "getStartOffsetInParent": return start;
                case "getContainingFile": return owner.file;
                case "getProject": return project;
                case "isValid": return true;
                case "isWritable": return true;
                case "isPhysical": return true;
                case "toString": return "leaf(" + text + "@" + start + ")";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == a[0];
                default: return def(m);
            }
        }
    }

    /**
     * `PsiDocumentManager`, standing in for the platform's.
     *
     * It is an abstract class rather than an interface, so it cannot be proxied; it is
     * subclassed, and the two members the plugin's write path uses are implemented:
     * `getDocument(file)` answers the real document for the file, and `commitDocument`
     * does nothing -- there is no PSI tree behind these stand-in elements to re-parse,
     * and this tool reads the document, which is where the plugin wrote.
     */
    static final class StubDocumentManager extends PsiDocumentManager {
        private final Map<PsiFile, Document> docs = new ConcurrentHashMap<PsiFile, Document>();

        void register(PsiFile file, Document document) {
            docs.put(file, document);
        }

        @Override
        public Document getDocument(PsiFile file) {
            return file == null ? null : docs.get(file);
        }

        @Override
        public Document getCachedDocument(PsiFile file) {
            return getDocument(file);
        }

        @Override
        public void commitDocument(Document document) {
        }

        @Override
        public boolean isCommitted(Document document) {
            return true;
        }

        @Override
        public PsiFile getPsiFile(Document document) {
            for (Map.Entry<PsiFile, Document> e : docs.entrySet()) {
                if (e.getValue() == document) return e.getKey();
            }
            return null;
        }

        @Override
        public PsiFile getPsiFile(Document document, com.intellij.codeInsight.multiverse.CodeInsightContext context) {
            return getPsiFile(document);
        }

        @Override
        public PsiFile getCachedPsiFile(Document document) {
            return getPsiFile(document);
        }

        @Override
        public PsiFile getCachedPsiFile(Document document, com.intellij.codeInsight.multiverse.CodeInsightContext context) {
            return getPsiFile(document);
        }

        @Override
        public Document getLastCommittedDocument(PsiFile file) {
            return getDocument(file);
        }

        @Override
        public CharSequence getLastCommittedText(Document document) {
            return document.getCharsSequence();
        }

        @Override
        public long getLastCommittedStamp(Document document) {
            return 0;
        }

        @Override
        public boolean hasUncommitedDocuments() {
            return false;
        }

        @Override
        public void commitAllDocuments() {
        }

        @Override
        public boolean commitAllDocumentsUnderProgress() {
            return false;
        }

        @Override
        public boolean performWhenAllCommitted(Runnable runnable) {
            runnable.run();
            return true;
        }

        @Override
        public void performLaterWhenAllCommitted(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void performLaterWhenAllCommitted(com.intellij.openapi.application.ModalityState modalityState, Runnable runnable) {
            runnable.run();
        }

        @Override
        public void performForCommittedDocument(Document document, Runnable runnable) {
            runnable.run();
        }

        @Override
        public void commitAndRunReadAction(Runnable runnable) {
            runnable.run();
        }

        @Override
        public <T> T commitAndRunReadAction(Computable<T> computation) {
            return computation.compute();
        }

        @Override
        public boolean isDocumentBlockedByPsi(Document document) {
            return false;
        }

        @Override
        public void doPostponedOperationsAndUnblockDocument(Document document) {
        }

        @Override
        public Document[] getUncommittedDocuments() {
            return new Document[0];
        }

        @SuppressWarnings("unchecked")
        @Override
        public void reparseFiles(java.util.Collection<? extends VirtualFile> files, boolean includeOpenFiles) {
        }
    }

    /**
     * `CommandProcessor`, standing in for the platform's, and doing exactly the one thing
     * this measurement needs: running the command.  The plugin's write path wraps its
     * `document.replaceString` in `executeCommand`; under a mock application no command
     * processor is registered at all, so the call would be a NullPointerException and the
     * measurement would stop at the first write.
     */
    static final class StubCommandProcessor extends CommandProcessor {
        @Override
        public void executeCommand(Project project, Runnable command, String name, Object groupId) {
            command.run();
        }

        @Override
        public void executeCommand(Project project, Runnable command, String name, Object groupId,
                                   UndoConfirmationPolicy undoConfirmationPolicy) {
            command.run();
        }

        @Override
        public void executeCommand(Project project, Runnable command, String name, Object groupId,
                                   UndoConfirmationPolicy undoConfirmationPolicy, boolean shouldRecordActionForActiveDocument) {
            command.run();
        }

        @Override
        public void executeCommand(Project project, Runnable command, String name, Object groupId,
                                   UndoConfirmationPolicy undoConfirmationPolicy, Document document) {
            command.run();
        }

        @Override
        public void executeCommand(Project project, Runnable command, String name, Object groupId,
                                   UndoConfirmationPolicy undoConfirmationPolicy, boolean shouldRecordActionForActiveDocument, Document document) {
            command.run();
        }

        @Override
        public void executeCommand(Project project, Runnable command, String name, Object groupId,
                                   Document document) {
            command.run();
        }

        @Override
        public boolean isUndoTransparentActionInProgress() {
            return false;
        }

        @Override
        public void runUndoTransparentAction(Runnable action) {
            action.run();
        }

        @Override
        public java.lang.AutoCloseable withUndoTransparentAction() {
            return () -> {
            };
        }

        @Override
        public void allowMergeGlobalCommands(Runnable runnable) {
            runnable.run();
        }

        @Override
        public Runnable getCurrentCommand() {
            return null;
        }

        @Override
        public String getCurrentCommandName() {
            return null;
        }

        @Override
        public void setCurrentCommandName(String name) {
        }

        @Override
        public Object getCurrentCommandGroupId() {
            return null;
        }

        @Override
        public void setCurrentCommandGroupId(Object groupId) {
        }

        @Override
        public Project getCurrentCommandProject() {
            return null;
        }

        @Override
        public void markCurrentCommandAsGlobal(Project project) {
        }

        @Override
        public void addAffectedDocuments(Project project, Document... documents) {
        }

        @Override
        public void addAffectedFiles(Project project, VirtualFile... files) {
        }
    }

    // ------------------------------------------------------------- the inventory

    private static final class Decl {
        final String kind;
        final String name;
        final int start;
        final int end;
        final int line;

        Decl(String kind, String name, int start, int end, int line) {
            this.kind = kind;
            this.name = name;
            this.start = start;
            this.end = end;
            this.line = line;
        }
    }

    /**
     * The declaration sites, read from the plugin's parser -- the same inventory
     * `RenameOracle` builds, so the two tools' coverage lines count the same units.
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
            // SPEC.md §13: an enum and its variants, so the write-back is driven over the
            // two names the plugin's reference table gained in 0.1.10.  The proof this tool
            // exists for is the one that matters here: a renamed variant must leave a file
            // `vm.exe check` still accepts, and renaming the declaration alone must be
            // refused -- which is what tells a write set that is *right* from one that is
            // merely non-empty.
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
     * Every identifier leaf, in file order: the set `VelaReferenceContributor` registers
     * its provider over, lexed with the plugin's own lexer.
     */
    private static void collectLeaves(FileWork w) {
        Lexer lexer = new VelaLexer();
        lexer.start(w.original);
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() == VelaTokenTypes.IDENTIFIER) {
                w.leaves.add(new int[] { lexer.getTokenStart(), lexer.getTokenEnd() });
            }
            lexer.advance();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private void collectCorpus() throws IOException {
        Set<String> seen = new LinkedHashSet<String>();
        for (String dir : new String[] { "tests", "examples", "ide-demo", "selfhost/parts", "bench" }) {
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
        // `selfhost/vm.vel` is deliberately NOT here: it is the 631 KB concatenation of
        // the parts, and this tool asks the compiler about every declaration in a file.
        for (String extra : new String[] { "selfhost/vela.vel" }) {
            Path p = repoRoot.resolve(extra);
            if (Files.isRegularFile(p)) seen.add(extra);
        }
        corpus.addAll(seen);
    }

    private static ProcessingContext ctx;

    /** `ProcessingContext.EMPTY`, reached reflectively so this file compiles against any. */
    private static synchronized ProcessingContext emptyContext() {
        if (ctx == null) {
            try {
                Class<?> c = Class.forName("com.intellij.util.ProcessingContext");
                Object instance = null;
                try {
                    instance = c.getField("EMPTY").get(null);
                } catch (Throwable ignored) {
                    // no such field on this platform
                }
                if (instance == null) {
                    java.lang.reflect.Constructor<?> ctor = c.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    instance = ctor.newInstance();
                }
                ctx = (ProcessingContext) instance;
            } catch (Throwable t) {
                throw new IllegalStateException("no ProcessingContext for the reference provider", t);
            }
        }
        return ctx;
    }

    private static final class CompilerRun {
        int exit;
        String out = "";
        String err = "";
    }

    private CompilerRun check(Path file) throws Exception {
        CompilerRun r = new CompilerRun();
        Path out = Files.createTempFile("vela-writeback-out", ".txt");
        Path err = Files.createTempFile("vela-writeback-err", ".txt");
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

    /** Replace every range in `starts` with `fresh`, back to front: the expected write. */
    private static String splice(String text, java.util.Collection<Integer> starts,
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

    private static Set<Integer> minus(Set<Integer> a, int remove) {
        Set<Integer> out = new TreeSet<Integer>(a);
        out.remove(remove);
        return out;
    }

    private static List<Integer> minus(java.util.Collection<Integer> a,
                                       java.util.Collection<Integer> b) {
        List<Integer> out = new ArrayList<Integer>();
        for (int x : new TreeSet<Integer>(a)) {
            if (!b.contains(x)) out.add(x);
        }
        return out;
    }

    private static String firstDifference(String original, String expected, String actual) {
        int n = Math.min(expected.length(), actual.length());
        for (int i = 0; i < n; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                return "the first difference is at offset " + i + " (line "
                        + lineOfOffset(original, i) + "): expected "
                        + quote(context(expected, i)) + ", read back " + quote(context(actual, i));
            }
        }
        if (expected.length() != actual.length()) {
            return "the two texts agree for " + n + " character(s) and then differ in length:"
                    + " expected " + expected.length() + ", read back " + actual.length();
        }
        return "the two texts are equal, so this is not the difference";
    }

    private static String context(String s, int at) {
        int from = Math.max(0, at - 12);
        int to = Math.min(s.length(), at + 12);
        return s.substring(from, to);
    }

    private static String quote(String s) {
        return "`" + s.replace("\n", "\\n").replace("\r", "\\r") + "`";
    }

    private static boolean indexOfCrlf(byte[] raw) {
        for (int i = 0; i + 1 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n') return true;
        }
        return false;
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

    private static String firstLine(String text) {
        if (text == null) return "";
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            if (!line.trim().isEmpty()) return line.trim();
        }
        return "";
    }

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

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
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

    private static String sha256(Path p) {
        try {
            return sha256(Files.readAllBytes(p));
        } catch (Exception e) {
            return "(could not hash: " + e + ")";
        }
    }

    /**
     * Is the compiler's refusal about a *use*, or about the program?
     *
     * `vm.exe check` reports a position, never a subject.  Renaming `main` produces "no
     * 'main' function", positioned at the `def` line -- a complaint about the program,
     * and nothing that can be attributed to an occurrence.  `RenameOracle`'s rule is
     * reused rather than reinvented: the first refusal's line must contain the old name
     * as a whole word, i.e. some use of it is still standing there.  A line where the
     * name only survives as a *prefix* of the new one (`mainzzq`) is not such a line,
     * which is what makes the rule decide this case rather than pass it through.
     */
    private static boolean attributable(String renamedText, String stderr, String name) {
        Integer line = reportedLine(stderr);
        if (line == null) return false;
        return indexOfWord(lineBody(renamedText, line), name, 0) >= 0;
    }

    /** The line of the compiler's first `at <file>:<line>` position, whatever the message. */
    private static Integer reportedLine(String stderr) {
        if (stderr == null) return null;
        Matcher m = Pattern.compile(":(\\d+)\\s*$", Pattern.MULTILINE).matcher(stderr);
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
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

    private static boolean isNamePart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    /**
     * One row of one skip category, three per category at most.
     *
     * The cap is per category rather than shared, because a shared cap is consumed by
     * whichever category comes first: measured on this tool's first full run, where the
     * counters said `not-attributable 135` and every visible row was a
     * `compiler-refused-file` one, so 135 counted skips could not be looked at.
     */
    private static void addSkipRow(Map<String, List<String>> rows, String kind, String row) {
        List<String> list = rows.get(kind);
        if (list == null) {
            list = new ArrayList<String>();
            rows.put(kind, list);
        }
        if (list.size() < 3) list.add(row);
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

    private static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == short.class) return (short) 0;
        if (r == byte.class) return (byte) 0;
        if (r == char.class) return (char) 0;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (r == void.class) return null;
        if (r.isArray()) return java.lang.reflect.Array.newInstance(r.getComponentType(), 0);
        if (r == List.class) return Collections.emptyList();
        if (r == Map.class) return Collections.emptyMap();
        if (r == Set.class) return Collections.emptySet();
        return null;
    }

    private RenameWriteback() {
    }
}
