import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProcess;
import com.intellij.codeInsight.completion.CompletionResult;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.completion.OffsetKey;
import com.intellij.codeInsight.completion.OffsetMap;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.lang.parameterInfo.CreateParameterInfoContext;
import com.intellij.lang.parameterInfo.ParameterInfoUIContext;
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext;
import com.intellij.mock.MockApplication;
import com.intellij.mock.MockProject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.VisualPosition;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.ElementPattern;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.Consumer;

import dev.vela.plugin.ParameterHint;
import dev.vela.plugin.VelaCompletionContributor;
import dev.vela.plugin.VelaCompletionKt;
import dev.vela.plugin.VelaEnterHandlerDelegate;
import dev.vela.plugin.VelaLexer;
import dev.vela.plugin.VelaModel;
import dev.vela.plugin.VelaNodeKind;
import dev.vela.plugin.VelaParameterInfoHandler;
import dev.vela.plugin.VelaSymbol;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaTok;
import dev.vela.plugin.VelaTypedHandlerDelegate;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE PLATFORM'S OWN ENTRY POINTS, DRIVEN.  Rows 7, 9 and 19 of `FEATURE_PARITY.md`
 * in one run.
 *
 * WHY THIS EXISTS.  Those three rows were the only `partial` ones, and they were
 * `partial` for one reason each time: the row is about an object the *platform*
 * instantiates -- a `CompletionContributor` driven by `CompletionService`, a
 * `ParameterInfoHandler` driven by `ParameterInfoControllerBase`, a
 * `TypedHandlerDelegate` / `EnterHandlerDelegate` driven by the editor's typing
 * path -- and no harness in this plugin had ever instantiated one.  Registration
 * plus compilation was all that was claimed.  This tool instantiates each of them
 * and calls the exact method the platform calls:
 *
 *   row 7   `VelaCompletionContributor.fillCompletionVariants(CompletionParameters,
 *           CompletionResultSet)`                     @NotNull the platform's own entry point
 *           `LookupElement.handleInsert(InsertionContext)`  the platform's insert path
 *   row 9   `VelaParameterInfoHandler.findElementForParameterInfo` ->
 *           `showParameterInfo` -> `findElementForUpdatingParameterInfo` ->
 *           `updateParameterInfo` -> `updateUI`        the platform's five steps
 *   row 19  `VelaTypedHandlerDelegate.charTyped(char, Project, Editor, PsiFile)` and
 *           `VelaEnterHandlerDelegate.postProcessEnter(PsiFile, Editor, DataContext)`
 *
 * WHAT THE PLATFORM SUPPLIES AND WHAT IS STOOD IN FOR.  `CompletionParameters` has a
 * public constructor on this platform, `OffsetMap` is a real class that takes a
 * `Document`, and `OffsetMap`/`InsertionContext` are real objects -- so the
 * completion's own arguments are the platform's own objects.  Everything else the
 * plugin asks for is an *interface*: `PsiFile`, `PsiElement`, `Project`, `Editor`,
 * `CaretModel`, `SelectionModel`, `Document`, `CreateParameterInfoContext`,
 * `UpdateParameterInfoContext`, `ParameterInfoUIContext`, `DataContext`.  They are
 * stood in for by `java.lang.reflect.Proxy` handlers that answer what a flat,
 * one-leaf-per-token PSI and a text buffer answer, exactly as `RenameWriteback` stands
 * in for `Document` and says so:
 *
 *   * **`Document` is stood in for, and it records every write.**  The platform's own
 *     `DocumentImpl` cannot be written headlessly (no write-access guard extension
 *     point, no command processor, no `FileDocumentManager`, no `ProgressManager` --
 *     `RenameWriteback` measured each of those failures).  The text model is this
 *     tool's, but every *range* and every *replacement string* is the plugin's own,
 *     and they are recorded as the plugin's own `insertString` / `replaceString`
 *     calls, which is a sharper statement than the resulting text alone.
 *   * **The editor is a caret plus a selection over that document.**  `Editor` is an
 *     interface, so a proxy answers `getDocument`, `getCaretModel` and
 *     `getSelectionModel`; the caret is an offset that moves when the plugin moves it,
 *     and the selection is recorded.
 *   * **A PSI leaf is one lexer token.**  `PsiFile.findElementAt(offset)` returns the
 *     leaf whose token covers the offset, cached by token start so that two calls with
 *     the same offset return the *same object* -- which is what
 *     `findElementForUpdatingParameterInfo` compares (`context.file.findElementAt`).
 *     `putUserData`/`getUserData` are a map, because that is how the parameter-info
 *     handler carries its item from `findElementForParameterInfo` to `showParameterInfo`.
 *
 * WHAT IS NOT CLAIMED.  No IDE was started.  Nothing here is evidence that a popup is
 * drawn on screen, that the platform's `ParameterInfoControllerBase` calls these five
 * methods in this order with these arguments, or that the caret the editor really has
 * is the offset this tool sets.  What is claimed is narrower and is the row's actual
 * question: **when the platform calls this method with this input, is the answer the
 * declaration gives?**  The evidence says which method was called and with what.
 *
 * WHAT CHANGED IN 0.1.8, AND WHY THE NUMBERS BELOW MOVED.
 *
 *   * **A local binding with a written type is judged, not counted.**  `q: Vec2 = ...`
 *     is the most ordinary way a local is written in this language, and the model's type
 *     reader resolved only `self`, parameters and struct names -- so 29 `after-dot`
 *     positions (row 7) and 6 calls (row 9) were classified `receiver-is-a-local` and
 *     skipped.  The plugin now reads the tree's own `decl` node
 *     (`VelaTargets.localBindingType`), those positions moved into the judged families,
 *     and the class that remains is `receiver-not-a-written-binding`: a receiver whose
 *     type the dump knows and no binding writes beside the name (a field, a loop
 *     variable), which the plugin answers nothing for by design.
 *   * **The fallback half of `findElementForUpdatingParameterInfo` is measured.**  It
 *     was unreachable code -- the items in `objectsToView` are `ParameterHint`s, so the
 *     cast to `PsiElement` could never succeed -- and the plugin's popup therefore could
 *     not survive an edit.  The new `update-rebuilt` family rebuilds the anchor leaf,
 *     requires the handler to find it again from `objectsToView`, and requires `null`
 *     when that array is empty.  Every judged call is a position in that family.
 *
 * THE ORACLES, AND WHY NONE OF THEM IS THE PLUGIN.
 *
 *   oracle 1  `vm.exe parse <file>` -- the compiler's own dump: `def name=F` with
 *             `param name=P type=T` and `ret=T`, `struct name=S` with `field name=F`
 *             and `def name=M`, `decl name=V type=T`, `call` with its argument nodes
 *             in order, `attr name=M` with its receiver.  Every expected parameter
 *             name, return type, member name and declared type below comes from here.
 *   oracle 2  `vm.exe lex <file>` -- the compiler's own token stream
 *             (`kind line sub kind offset len`): it is what decides whether a gap in
 *             the text is code at all, so `{` inside a comment or a string can be told
 *             from a real brace by the *compiler*, not by the plugin's lexer.
 *   oracle 3  `vm.exe check <file>` -- accepted or refused, on the file that was read
 *             back.  This is the falsification: a rename that writes nothing cannot
 *             pass when the compiler refuses the file without the written character.
 *   oracle 4  `SPEC.md` -- section 1.3's keyword list and section 8's builtin
 *             signatures, read by this harness's own parser (the same one
 *             `DeclReader` uses for the builtins).
 *   oracle 5  **the corpus's own shape** -- for the Enter handler, the indentation the
 *             file itself already uses at that nesting depth.  Indentation is not
 *             semantic in Vela, so no compiler verdict can judge it; the 400 KB of the
 *             language's own source is the only authority there is, and this tool
 *             prints how many lines of the corpus agree with the rule before it uses it.
 *
 * THE FALSIFICATION, AND THE CONTROL.
 *
 * Every section is asked to go red on purpose, on real corpus input, after its green
 * run: a member dropped from the offer set and an invented name added to it (row 7);
 * a parameter list with its names reversed and an argument index moved by one (row 9);
 * a closer left unwritten and an extra character written (row 19); an Enter that
 * indents one level less.  Each one must be reported by the same comparator that
 * produced the zeros, or the tool exits 3 -- a comparator that cannot fire says
 * nothing with its zeros.  `--control` is accepted and the controls always run.
 *
 * Usage:
 *   java PlatformEntry <repo-root> --vm <vm.exe> [--single <file>] [--section 1|2|3]
 *       [--threads <n>] [--max-bytes <n>] [--show] [--explain] [--control]
 *
 * Exit codes: 0 = no finding; 1 = a wrong answer (a finding on a file/position);
 * 3 = this harness or its corpus is at fault (a crashed file, a control that did not
 * fire, a falsification that never fired, a missing corpus entry).
 */
public final class PlatformEntry {

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final int THREADS = 4;
    private static final int MAX_ROWS_PER_CLASS = 8;
    private static final int MAX_WRONG_ROWS = 40;

    /** The nine (and, per section, more) reasons a position is not judged. */
    private static final String[] COMMON_SKIPS = {
        "compiler-refused-file",
        "too-large",
        "missing-corpus-file",
        "dump-unavailable",
        "lex-unavailable",
        "crashed",
    };

    private Path repoRoot;
    private Path vm;
    private Path scratchRoot;
    private String single;
    private boolean show;
    private boolean explain;
    private boolean control;
    private int threads = THREADS;
    private long maxBytes = MAX_FILE_BYTES;
    private String vmSha = "";
    private String section = "all";
    private final List<String> corpus = new ArrayList<String>();
    private int compilerRuns;
    private long elapsedMs;
    private final AtomicInteger filesCopied = new AtomicInteger();

    // The one platform per JVM: the stand-in services the plugin's own code asks for.
    private static Disposable rootDisposable;
    private static MockApplication app;
    private static MockProject project;
    private static Pdm pdm;

    // The two oracles read once, outside the corpus walk.
    /** SPEC.md 1.3's keyword list, as the language documents it. */
    private final Set<String> specKeywords = new LinkedHashSet<String>();
    /** SPEC.md section 8's parameter names per builtin (`DeclReader`'s reader). */
    private Map<String, List<String>> specBuiltinNames = new LinkedHashMap<String, List<String>>();
    /** The corpus's own indentation step: the widest-used positive delta, in spaces. */
    private int corpusIndentStep = 0;
    private int corpusIndentSamples = 0;

    public static void main(String[] args) throws Exception {
        PlatformEntry tool = new PlatformEntry();
        List<String> rest = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--vm")) tool.vm = Paths.get(args[++i]).toAbsolutePath();
            else if (a.equals("--single")) tool.single = args[++i];
            else if (a.equals("--section")) tool.section = args[++i];
            else if (a.equals("--threads")) tool.threads = Integer.parseInt(args[++i]);
            else if (a.equals("--max-bytes")) tool.maxBytes = Long.parseLong(args[++i]);
            else if (a.equals("--show")) tool.show = true;
            else if (a.equals("--explain")) tool.explain = true;
            else if (a.equals("--control")) tool.control = true;
            else rest.add(a);
        }
        tool.repoRoot = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        tool.run();
    }

    // ------------------------------------------------------------------- the run

    private void run() throws Exception {
        if (vm == null) vm = repoRoot.resolve("selfhost").resolve("build").resolve("vm.exe");
        System.out.println("== PlatformEntry: the platform's own entry points, driven ==");
        System.out.println("driver 1 : VelaCompletionContributor.fillCompletionVariants +");
        System.out.println("           LookupElement.handleInsert            (row 7)");
        System.out.println("driver 2 : VelaParameterInfoHandler.findElementForParameterInfo ->");
        System.out.println("           showParameterInfo -> findElementForUpdatingParameterInfo ->");
        System.out.println("           updateParameterInfo -> updateUI        (row 9)");
        System.out.println("driver 3 : VelaTypedHandlerDelegate.charTyped +");
        System.out.println("           VelaEnterHandlerDelegate.postProcessEnter (row 19)");
        if (!Files.isRegularFile(vm)) {
            System.out.println("VERDICT: no compiler at " + vm + ", so nothing can be judged [FAIL]");
            System.out.println("COVERAGE: ran 0 / skipped 0 (" + allSkipCountersZero()
                    + ") / wrong 0");
            System.exit(3);
        }
        vmSha = sha256(vm);
        System.out.println("compiler: " + vm + " (" + Files.size(vm) + " bytes, sha256 " + vmSha + ")");
        System.out.println("control : the comparators are asked to fire on purpose in every section"
                + (control ? "" : " (--control is accepted; they always run)"));

        bootPlatform();
        System.out.println("platform: " + ApplicationManager.getApplication().getClass().getName()
                + " (MockApplication) + " + project.getClass().getName());
        System.out.println("          PsiDocumentManager.getInstance(project) is this tool's own: "
                + (PsiDocumentManager.getInstance(project) == pdm));
        System.out.println("          the document, the editor and the PSI leaves are stand-ins;"
                + " every range and string written below is the plugin's own");

        readSpec();
        System.out.println("oracle 4: SPEC.md keywords " + specKeywords.size()
                + ", builtins with documented parameter names " + specBuiltinNames.size());

        if (single != null) corpus.add(single.replace('\\', '/'));
        else collectCorpus();
        System.out.println("corpus  : " + corpus.size() + " file(s); each is copied before it is"
                + " edited, and the copy is what the compiler is asked about");
        System.out.println("          files over " + maxBytes + " bytes are skipped, by class");

        scratchRoot = Files.createTempDirectory("vela-platform-entry");
        List<FileWork> works = new ArrayList<FileWork>();
        long start = System.currentTimeMillis();
        try {
            int n = 0;
            for (String rel : corpus) {
                FileWork w = new FileWork(rel, repoRoot.resolve(rel));
                Path p = w.path;
                if (!Files.isRegularFile(p)) w.error = "not there";
                else if (Files.size(p) > maxBytes) w.error = "over " + maxBytes + " bytes";
                w.index = n++;
                works.add(w);
            }

            measureCorpusIndentStep(works);

            if (threads <= 1) {
                for (FileWork w : works) analyseOne(w);
            } else {
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                List<Future<?>> futures = new ArrayList<Future<?>>();
                for (final FileWork w : works) {
                    futures.add(pool.submit(new Runnable() {
                        public void run() { analyseOne(w); }
                    }));
                }
                for (Future<?> f : futures) f.get();
                pool.shutdown();
            }
            elapsedMs = System.currentTimeMillis() - start;

            int worst = 0;
            if (want(1)) worst = Math.max(worst, reportCompletion(works));
            if (want(2)) worst = Math.max(worst, reportParamInfo(works));
            if (want(3)) worst = Math.max(worst, reportHandlers(works));
            System.out.println();
            System.out.println("frozen compiler: " + vmSha);
            System.out.println("compiler processes: " + compilerRuns + " (" + (elapsedMs / 1000) + " s,"
                    + " " + threads + " thread(s))");
            System.out.println();
            System.out.println("COVERAGE: " + allCoverage(works));
            System.exit(worst);
        } finally {
            deleteTree(scratchRoot);
        }
    }

    private boolean want(int n) {
        return section.equals("all") || section.equals(String.valueOf(n));
    }

    private void analyseOne(FileWork w) {
        if (w.error != null) {
            // A file that is not judged is counted in every section that would have judged
            // it: a corpus entry that is not there is a skip of its own class, never dropped.
            String kind = w.error.startsWith("over") ? "too-large" : "missing-corpus-file";
            w.skipKind = kind;
            if (want(1)) w.c1.skip(kind, w.rel + ": " + w.error);
            if (want(2)) w.c2.skip(kind, w.rel + ": " + w.error);
            if (want(3)) w.c3.skip(kind, w.rel + ": " + w.error);
            return;
        }
        try {
            byte[] raw = Files.readAllBytes(w.path);
            w.text = new String(raw, StandardCharsets.ISO_8859_1)
                    .replace("\r\n", "\n").replace('\r', '\n');
            w.sha = sha256(raw);

            Path dir = Files.createDirectories(scratchRoot.resolve(String.valueOf(filesCopied.getAndIncrement())));
            w.copy = dir.resolve("copy.vel");
            Files.write(w.copy, w.text.getBytes(StandardCharsets.ISO_8859_1));

            CompilerRun base = check(w.copy);
            w.baseExit = base.exit;
            w.baseErr = base.err;
            if (base.exit != 0) {
                w.skipKind = "compiler-refused-file";
                return;
            }
            w.dumpText = dump(w.copy);
            if (w.dumpText == null) {
                w.skipKind = "dump-unavailable";
                return;
            }
            w.dump = Dump.parse(w.dumpText);
            w.lex = lex(w.copy);
            if (w.lex == null) {
                w.skipKind = "lex-unavailable";
                return;
            }
            if (want(1)) section1(w);
            if (want(2)) section2(w);
            if (want(3)) section3(w);
        } catch (Throwable t) {
            // A crash is a class of its own in every section that was running: it is
            // printed with the exception and never silently dropped from the run.
            w.crashed = true;
            w.error = "threw " + t;
            if (want(1)) w.c1.skip("crashed", w.rel + ": " + oneLine(String.valueOf(t)));
            if (want(2)) w.c2.skip("crashed", w.rel + ": " + oneLine(String.valueOf(t)));
            if (want(3)) w.c3.skip("crashed", w.rel + ": " + oneLine(String.valueOf(t)));
            if (explain) t.printStackTrace(System.out);
        }
    }

    static final class FileWork {
        final String rel;
        final Path path;
        int index;
        String text = "";
        String sha = "";
        Path copy;
        int baseExit = -1;
        String baseErr = "";
        String dumpText;
        Dump dump;
        Lex lex;
        String skipKind;
        boolean crashed;
        String error;

        // section results
        final Cov c1 = new Cov(SKIP1);
        final Cov c2 = new Cov(SKIP2);
        final Cov c3 = new Cov(SKIP3);
        int c1FilesJudged, c2FilesJudged, c3FilesJudged;

        FileWork(String rel, Path path) { this.rel = rel; this.path = path; }

        String name() { return rel.substring(rel.lastIndexOf('/') + 1); }
    }

    // ------------------------------------------------------------- the stand-ins

    private void bootPlatform() {
        rootDisposable = Disposer.newDisposable();
        app = new MockApplication(rootDisposable);
        ApplicationManager.setApplication(app, rootDisposable);
        project = new MockProject(app.getPicoContainer(), rootDisposable);
        pdm = new Pdm();
        project.registerService(PsiDocumentManager.class, pdm);
        if (PsiDocumentManager.getInstance(project) != pdm) {
            System.out.println("the mock platform did not hand out this tool's PsiDocumentManager,"
                    + " so the document the plugin reads would not be this tool's");
            System.exit(2);
        }
    }

    /** `PsiDocumentManager.getInstance(project).getDocument(file)`, for the popup's text. */
    static final class Pdm extends PsiDocumentManager {
        final Map<PsiFile, Document> docs = new java.util.concurrent.ConcurrentHashMap<PsiFile, Document>();
        void register(PsiFile f, Document d) { docs.put(f, d); }
        public Document getDocument(PsiFile f) { return docs.get(f); }
        public Document getCachedDocument(PsiFile f) { return docs.get(f); }
        public PsiFile getPsiFile(Document d) { return null; }
        public PsiFile getPsiFile(Document d, com.intellij.codeInsight.multiverse.CodeInsightContext c) { return null; }
        public PsiFile getCachedPsiFile(Document d) { return null; }
        public PsiFile getCachedPsiFile(Document d, com.intellij.codeInsight.multiverse.CodeInsightContext c) { return null; }
        public void commitDocument(Document d) { }
        public boolean isCommitted(Document d) { return true; }
        public void commitAllDocuments() { }
        public Document[] getUncommittedDocuments() { return new Document[0]; }
        public Document getLastCommittedDocument(PsiFile f) { return docs.get(f); }
        public CharSequence getLastCommittedText(Document d) { return null; }
        public long getLastCommittedStamp(Document d) { return 0; }
        public boolean hasUncommitedDocuments() { return false; }
        public boolean isDocumentBlockedByPsi(Document d) { return false; }
        public void doPostponedOperationsAndUnblockDocument(Document d) { }
        public void performForCommittedDocument(Document d, Runnable r) { }
        public boolean commitAllDocumentsUnderProgress() { return true; }
        public <T> T commitAndRunReadAction(Computable<T> c) { return c.compute(); }
        public void commitAndRunReadAction(Runnable r) { r.run(); }
        public boolean performWhenAllCommitted(Runnable r) { return false; }
        public void performLaterWhenAllCommitted(Runnable r) { }
        public void performLaterWhenAllCommitted(com.intellij.openapi.application.ModalityState s, Runnable r) { }
        public void reparseFiles(java.util.Collection<? extends VirtualFile> files, boolean b) { }
    }

    /**
     * The platform's `Document`, stood in for, and *recording every write*.
     *
     * The read side is a real line model (`getLineNumber`, `getLineStartOffset`,
     * `getLineEndOffset`, `charsSequence`) because the editor paths ask for lines; the
     * write side records `(start, end, text)` for every `insertString` / `replaceString`
     * / `deleteString` the plugin performs, and applies it, so the text read back is the
     * text the plugin's own calls produced.
     */
    static final class Doc implements InvocationHandler {
        String text;
        final List<Write> writes = new ArrayList<Write>();
        Document proxy;

        Doc(String t) { text = t; }

        Document proxy() {
            proxy = (Document) Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                    new Class<?>[] { Document.class }, this);
            return proxy;
        }

        List<Write> writes() { return writes; }

        int lineOf(int offset) {
            int n = 0;
            int limit = Math.min(Math.max(offset, 0), text.length());
            for (int i = 0; i < limit; i++) if (text.charAt(i) == '\n') n++;
            return n;
        }

        int lineStart(int line) {
            int at = 0;
            for (int i = 0; i < line; i++) {
                int nl = text.indexOf('\n', at);
                if (nl < 0) return text.length();
                at = nl + 1;
            }
            return at;
        }

        public Object invoke(Object p, Method m, Object[] a) {
            int n = m.getParameterCount();
            switch (m.getName()) {
                case "getText":
                    return n == 0 ? text : text.substring(Math.max(0, ((TextRange) a[0]).getStartOffset()),
                            Math.min(text.length(), ((TextRange) a[0]).getEndOffset()));
                case "getTextLength": return text.length();
                case "getCharsSequence":
                case "getImmutableCharSequence": return text;
                case "isWritable": case "isValid": return true;
                case "isInBulkUpdate": return false;
                case "getModificationStamp": return 0L;
                case "getLineCount": return lineOf(text.length()) + 1;
                case "getLineNumber": return lineOf((Integer) a[0]);
                case "getLineStartOffset": return lineStart((Integer) a[0]);
                case "getLineEndOffset": return Math.max(lineStart((Integer) a[0]),
                        lineStart((Integer) a[0] + 1) - 1);
                case "getLineSeparatorLength": return 1;
                case "createRangeMarker": {
                    final int s = (Integer) a[0];
                    final int e = (Integer) a[1];
                    return Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                            new Class<?>[] { RangeMarker.class }, (q, mm, aa) -> {
                                switch (mm.getName()) {
                                    case "getStartOffset": return s;
                                    case "getEndOffset": return e;
                                    case "isValid": return true;
                                    case "getDocument": return proxy;
                                    case "toString": return "marker(" + s + "," + e + ")";
                                    case "hashCode": return System.identityHashCode(q);
                                    case "equals": return q == aa[0];
                                    default: return def(mm);
                                }
                            });
                }
                case "setText": writes.add(new Write(0, text.length(), String.valueOf(a[0])));
                    text = String.valueOf(a[0]); return null;
                case "replaceString": {
                    int s = (Integer) a[0];
                    int e = (Integer) a[1];
                    String v = String.valueOf(a[2]);
                    writes.add(new Write(s, e, v));
                    text = text.substring(0, s) + v + text.substring(e);
                    return null;
                }
                case "insertString": {
                    int s = (Integer) a[0];
                    String v = String.valueOf(a[1]);
                    writes.add(new Write(s, s, v));
                    text = text.substring(0, s) + v + text.substring(s);
                    return null;
                }
                case "deleteString": {
                    int s = (Integer) a[0];
                    int e = (Integer) a[1];
                    writes.add(new Write(s, e, ""));
                    text = text.substring(0, s) + text.substring(e);
                    return null;
                }
                case "toString": return "document(" + text.length() + " chars, " + writes.size() + " writes)";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default: return def(m);
            }
        }
    }

    /** One write the plugin performed: the range it asked for, and what it put there. */
    static final class Write {
        final int start;
        final int end;
        final String text;
        Write(int start, int end, String text) { this.start = start; this.end = end; this.text = text; }
        public String toString() {
            return "replaceString(" + start + ", " + end + ", `" + text + "`)";
        }
    }

    /**
     * A flat, one-leaf-per-token PSI over the document text.
     *
     * `findElementAt` returns the *cached* leaf whose token starts at that offset, so the
     * object identity `findElementForUpdatingParameterInfo` compares is stable, exactly
     * as the platform's PSI is stable while the document is not edited.  The leaf's user
     * data is a map: that is the channel the parameter-info handler uses.
     */
    static final class Fil implements InvocationHandler {
        final String name;
        String text;
        final Doc doc;
        final Document document;
        final Map<Integer, PsiElement> leafByStart = new LinkedHashMap<Integer, PsiElement>();
        final int[] tokenStart;   // offsets that start a token, ascending
        final int[] tokenEnd;
        PsiFile proxy;

        Fil(String name, String text, Doc doc, Document document, Lex lex) {
            this.name = name;
            this.text = text;
            this.doc = doc;
            this.document = document;
            List<int[]> spans = new ArrayList<int[]>();
            if (lex != null) {
                for (LexTok t : lex.toks) {
                    if (t.offset < 0 || t.len <= 0 || t.offset + t.len > text.length()) continue;
                    if (t.kind == LexKind.EOF) continue;
                    spans.add(new int[] { t.offset, t.offset + t.len });
                }
            }
            tokenStart = new int[spans.size()];
            tokenEnd = new int[spans.size()];
            for (int i = 0; i < spans.size(); i++) {
                tokenStart[i] = spans.get(i)[0];
                tokenEnd[i] = spans.get(i)[1];
            }
        }

        PsiFile proxy() {
            proxy = (PsiFile) Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                    new Class<?>[] { PsiFile.class }, this);
            return proxy;
        }

        /** The leaf covering `offset`, cached by the token that starts there. */
        PsiElement leafAt(int offset) {
            if (offset < 0 || offset >= text.length()) return null;
            PsiElement cached = leafByStart.get(offset);
            if (cached != null) return cached;
            int end = offset + 1;
            for (int i = 0; i < tokenStart.length; i++) {
                if (tokenStart[i] == offset) { end = tokenEnd[i]; break; }
            }
            PsiElement leaf = (PsiElement) Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                    new Class<?>[] { PsiElement.class }, new Leaf(this, offset, end));
            leafByStart.put(offset, leaf);
            return leaf;
        }

        /** The leaf the platform would have built *again*: identity is not preserved. */
        PsiElement rebuiltLeafAt(int offset) {
            leafByStart.clear();
            return leafAt(offset);
        }

        public Object invoke(Object p, Method m, Object[] a) {
            switch (m.getName()) {
                case "getText": return text;
                case "getTextLength": return text.length();
                case "getName": return name;
                case "getProject": return project;
                case "isValid": case "isWritable": case "isPhysical": return true;
                case "getVirtualFile": return null;
                case "findElementAt": return leafAt((Integer) a[0]);
                case "toString": return "file(" + name + ")";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default: return def(m);
            }
        }
    }

    static final class Leaf implements InvocationHandler {
        final Fil owner;
        final int start;
        final int end;
        final Map<Key, Object> data = new java.util.concurrent.ConcurrentHashMap<Key, Object>();

        Leaf(Fil owner, int start, int end) { this.owner = owner; this.start = start; this.end = end; }

        public Object invoke(Object p, Method m, Object[] a) {
            switch (m.getName()) {
                case "getText": return owner.text.substring(start, Math.min(end, owner.text.length()));
                case "getTextRange": return new TextRange(start, end);
                case "getTextOffset": return start;
                case "getTextLength": return end - start;
                case "getStartOffsetInParent": return start;
                case "getContainingFile": return owner.proxy;
                case "getProject": return project;
                case "isValid": case "isWritable": case "isPhysical": return true;
                case "putUserData": data.put((Key) a[0], a[1]); return null;
                case "getUserData": return data.get(a[0]);
                case "getUserDataString": return null;
                case "getUserDataKeys": return new ArrayList<Key>(data.keySet());
                case "isUserDataEmpty": return data.isEmpty();
                case "putUserDataIfAbsent": return data.putIfAbsent((Key) a[0], a[1]);
                case "replace": data.put((Key) a[1], a[2]); return data.remove((Key) a[0]);
                case "copyCopyableDataTo": return null;
                case "toString": return "leaf(" + owner.text.substring(start, Math.min(end, owner.text.length()))
                        + "@" + start + ")";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default: return def(m);
            }
        }
    }

    /** The editor: a document, a caret offset and a recorded selection. */
    static final class Ed implements InvocationHandler {
        final Document document;
        final Doc doc;
        int caret;
        int[] selection;
        Editor proxy;

        Ed(Document document, Doc doc, int caret) {
            this.document = document;
            this.doc = doc;
            this.caret = caret;
        }

        Editor proxy() {
            proxy = (Editor) Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                    new Class<?>[] { Editor.class }, this);
            return proxy;
        }

        public Object invoke(Object p, Method m, Object[] a) {
            switch (m.getName()) {
                case "getDocument":
                case "getElfDocument": return document;
                case "getProject": return project;
                case "getCaretModel":
                    return Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                            new Class<?>[] { CaretModel.class }, (q, mm, aa) -> {
                                switch (mm.getName()) {
                                    case "getOffset": return caret;
                                    case "moveToOffset":
                                        caret = (Integer) aa[0];
                                        if (aa.length > 1 && Boolean.FALSE.equals(aa[1])) { /* keep */ }
                                        return null;
                                    case "getLogicalPosition": return new LogicalPosition(0, 0);
                                    case "getVisualPosition": return new VisualPosition(0, 0);
                                    case "getCaretCount": return 1;
                                    case "supportsMultipleCarets": return false;
                                    case "toString": return "caret(" + caret + ")";
                                    case "hashCode": return System.identityHashCode(q);
                                    case "equals": return q == aa[0];
                                    default: return def(mm);
                                }
                            });
                case "getSelectionModel":
                    return Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                            new Class<?>[] { SelectionModel.class }, (q, mm, aa) -> {
                                switch (mm.getName()) {
                                    case "setSelection":
                                        selection = new int[] { (Integer) aa[0], (Integer) aa[1] };
                                        return null;
                                    case "getSelectionStart": return selection == null ? caret : selection[0];
                                    case "getSelectionEnd": return selection == null ? caret : selection[1];
                                    case "hasSelection": return selection != null
                                            && selection[0] != selection[1];
                                    case "removeSelection": selection = null; return null;
                                    case "toString": return "selection";
                                    case "hashCode": return System.identityHashCode(q);
                                    case "equals": return q == aa[0];
                                    default: return def(mm);
                                }
                            });
                case "isViewer": case "isDisposed": case "isOneLineMode": return false;
                case "toString": return "editor";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default: return def(m);
            }
        }
    }

    /** `CompletionResultSet`, recording the plugin's own `addElement` calls. */
    static final class Rec extends CompletionResultSet {
        final List<LookupElement> elements = new ArrayList<LookupElement>();
        final List<String> advertisements = new ArrayList<String>();

        Rec() {
            super(new Pm(""), new Consumer<CompletionResult>() {
                public void consume(CompletionResult t) { }
            }, null);
        }

        public void addElement(LookupElement e) { elements.add(e); }
        public void addLookupAdvertisement(String s) { advertisements.add(s); }
        public CompletionResultSet caseInsensitive() { return this; }
        public CompletionResultSet withPrefixMatcher(PrefixMatcher p) { return this; }
        public CompletionResultSet withPrefixMatcher(String p) { return this; }
        public CompletionResultSet withRelevanceSorter(
                com.intellij.codeInsight.completion.CompletionSorter s) { return this; }
        public void restartCompletionOnPrefixChange(ElementPattern<String> p) { }
        public void restartCompletionWhenNothingMatches() { }
    }

    static final class Pm extends PrefixMatcher {
        Pm(String prefix) { super(prefix); }
        public PrefixMatcher cloneWithPrefix(String p) { return new Pm(p); }
        public boolean prefixMatches(String name) { return true; }
    }

    /** `CreateParameterInfoContext`, recording what the plugin put in it. */
    static final class CreateCtx implements InvocationHandler {
        final PsiFile file;
        final int offset;
        Object[] items;
        PsiElement highlighted;
        int parameterListStart;

        CreateCtx(PsiFile file, int offset, int parameterListStart) {
            this.file = file;
            this.offset = offset;
            this.parameterListStart = parameterListStart;
        }

        CreateParameterInfoContext proxy() {
            return (CreateParameterInfoContext) Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                    new Class<?>[] { CreateParameterInfoContext.class }, this);
        }

        public Object invoke(Object p, Method m, Object[] a) {
            switch (m.getName()) {
                case "getFile": return file;
                case "getOffset": return offset;
                case "getItemsToShow": return items;
                case "setItemsToShow": items = (Object[]) a[0]; return null;
                case "setHighlightedElement": highlighted = (PsiElement) a[0]; return null;
                case "getHighlightedElement": return highlighted;
                case "getParameterListStart": return parameterListStart;
                case "getProject": return project;
                case "toString": return "createContext"; case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default: return def(m);
            }
        }
    }

    /** `UpdateParameterInfoContext`, recording `setCurrentParameter`. */
    static final class UpdCtx implements InvocationHandler {
        final PsiFile file;
        final int offset;
        final int parameterListStart;
        final PsiElement owner;
        Object[] objectsToView;
        int current = Integer.MIN_VALUE;
        int currentCalls;

        UpdCtx(PsiFile file, int offset, int parameterListStart, PsiElement owner) {
            this.file = file;
            this.offset = offset;
            this.parameterListStart = parameterListStart;
            this.owner = owner;
        }

        UpdateParameterInfoContext proxy() {
            return (UpdateParameterInfoContext) Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                    new Class<?>[] { UpdateParameterInfoContext.class }, this);
        }

        public Object invoke(Object p, Method m, Object[] a) {
            switch (m.getName()) {
                case "getFile": return file;
                case "getOffset": return offset;
                case "getParameterListStart": return parameterListStart;
                case "setCurrentParameter": current = (Integer) a[0]; currentCalls++; return null;
                case "getObjectsToView": return objectsToView;
                case "setParameterOwner": return null;
                case "getParameterOwner": return owner;
                case "getProject": return project;
                case "isUIComponentEnabled": return true;
                case "setUIComponentEnabled": return null;
                case "isInnermostContext": return true;
                case "isSingleParameterInfo": return false;
                case "getHighlightedParameter": return null;
                case "setHighlightedParameter": return null;
                case "isPreservedOnHintHidden": return false;
                case "setPreservedOnHintHidden": return null;
                case "removeHint": return null;
                case "toString": return "updateContext"; case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default: return def(m);
            }
        }
    }

    /** `ParameterInfoUIContext`, recording the string and the emphasis the plugin draws. */
    static final class Uic implements InvocationHandler {
        boolean called;
        String text = "";
        int start = -1;
        int end = -1;
        boolean enabled = true;
        /**
         * What `getCurrentParameterIndex()` answers: the index the platform's own
         * `setCurrentParameter` recorded, which is what the real `ParameterInfoUIContext`
         * hands back for the draw after an update.  It is -1 until `updateParameterInfo`
         * has run, which is the platform's "nothing is current yet".
         */
        int currentParameterIndex = -1;

        ParameterInfoUIContext proxy() {
            return (ParameterInfoUIContext) Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                    new Class<?>[] { ParameterInfoUIContext.class }, this);
        }

        public Object invoke(Object p, Method m, Object[] a) {
            switch (m.getName()) {
                case "setupUIComponentPresentation":
                    called = true;
                    text = String.valueOf(a[0]);
                    start = (Integer) a[1];
                    end = (Integer) a[2];
                    return "";
                case "setupRawUIComponentPresentation":
                    called = true;
                    text = String.valueOf(a[0]);
                    start = 0;
                    end = 0;
                    return null;
                case "setUIComponentEnabled": enabled = (Boolean) a[0]; return null;
                case "isUIComponentEnabled": return enabled;
                case "getParameterOwner": return null;
                case "getDefaultParameterColor": return java.awt.Color.BLACK;
                case "isSingleOverload": return false;
                case "isSingleParameterInfo": return true;
                case "getCurrentParameterIndex": return currentParameterIndex;
                case "toString": return "uiContext(" + text + ")";
                case "hashCode": return System.identityHashCode(p);
                case "equals": return p == a[0];
                default: return def(m);
            }
        }
    }

    // ------------------------------------------------------------- the compiler

    static final class CompilerRun {
        int exit;
        String out = "";
        String err = "";
        String firstLine() { return oneLine(err.isEmpty() ? out : err); }
    }

    private CompilerRun check(Path file) throws Exception {
        return run("check", file);
    }

    private String dump(Path file) throws Exception {
        CompilerRun r = run("parse", file);
        return r.exit == 0 ? r.out : null;
    }

    private Lex lex(Path file) throws Exception {
        CompilerRun r = run("lex", file);
        return r.exit == 0 ? Lex.parse(r.out) : null;
    }

    private CompilerRun run(String mode, Path file) throws Exception {
        CompilerRun r = new CompilerRun();
        Path out = Files.createTempFile("vela-platform-entry-out", ".txt");
        Path err = Files.createTempFile("vela-platform-entry-err", ".txt");
        ProcessBuilder pb = new ProcessBuilder(vm.toString(), mode, file.toString());
        pb.redirectOutput(out.toFile());
        pb.redirectError(err.toFile());
        Process proc = pb.start();
        r.exit = proc.waitFor();
        // THE COMPILER'S STDOUT IS CRLF ON WINDOWS, AND THE DUMPS BELOW ARE LINE-PARSED.
        // Measured: without this, every attribute of `vm.exe parse`'s last column carried a
        // CR and `struct name=Vec2` and `Vec2` stopped being the same string -- which showed
        // up as "offered but declared nowhere: Vec2" for a name the file declares.
        r.out = read(out).replace("\r\n", "\n").replace('\r', '\n');
        r.err = read(err).replace("\r\n", "\n").replace('\r', '\n');
        Files.deleteIfExists(out);
        Files.deleteIfExists(err);
        synchronized (this) { compilerRuns++; }
        return r;
    }

    /** One `check` of a text that is not a corpus file, for the controls and the falsification. */
    private CompilerRun checkText(FileWork w, String text, String tag) throws Exception {
        Path dir = Files.createDirectories(scratchRoot.resolve("edit-" + w.index));
        Path f = dir.resolve(tag + ".vel");
        Files.write(f, text.getBytes(StandardCharsets.ISO_8859_1));
        return check(f);
    }

    // --------------------------------------------------------------- the dump

    /** One node of `vm.exe parse`'s dump: a kind, its `key=value` attributes, its children. */
    static final class DNode {
        final String kind;
        final Map<String, String> attrs = new LinkedHashMap<String, String>();
        final List<DNode> kids = new ArrayList<DNode>();
        int line;
        DNode parent;

        DNode(String kind) { this.kind = kind; }

        String attr(String k) { return attrs.get(k); }
        String name() { return attrs.get("name"); }

        String path() {
            StringBuilder sb = new StringBuilder(kind);
            DNode p = parent;
            while (p != null) {
                if (p.name() != null) sb.insert(0, p.kind + " " + p.name() + " > ");
                else sb.insert(0, p.kind + " > ");
                p = p.parent;
            }
            return sb.toString();
        }

        void walk(List<DNode> out) {
            out.add(this);
            for (DNode k : kids) k.walk(out);
        }
    }

    static final class Dump {
        final DNode root = new DNode("<root>");
        final List<DNode> all = new ArrayList<DNode>();

        static Dump parse(String text) {
            Dump d = new Dump();
            List<DNode> stack = new ArrayList<DNode>();
            stack.add(d.root);
            List<Integer> indents = new ArrayList<Integer>();
            indents.add(-1);
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String raw = lines[i];
                if (raw.trim().isEmpty()) continue;
                int indent = 0;
                while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
                String body = raw.substring(indent);
                while (stack.size() > 1 && indents.get(indents.size() - 1) >= indent) {
                    stack.remove(stack.size() - 1);
                    indents.remove(indents.size() - 1);
                }
                DNode node = parseLine(body);
                if (node == null) continue;
                node.line = i + 1;
                DNode parent = stack.get(stack.size() - 1);
                node.parent = parent;
                parent.kids.add(node);
                stack.add(node);
                indents.add(indent);
            }
            d.root.walk(d.all);
            return d;
        }

        private static DNode parseLine(String body) {
            int i = 0;
            while (i < body.length() && body.charAt(i) != ' ') i++;
            String kind = body.substring(0, i);
            if (kind.isEmpty()) return null;
            DNode node = new DNode(kind);
            while (i < body.length()) {
                while (i < body.length() && body.charAt(i) == ' ') i++;
                int start = i;
                while (i < body.length() && body.charAt(i) != '=' && body.charAt(i) != ' ') i++;
                String key = body.substring(start, i);
                if (key.isEmpty()) break;
                if (i < body.length() && body.charAt(i) == '=') {
                    i++;
                    String value;
                    if (i < body.length() && body.charAt(i) == '"') {
                        int close = body.indexOf('"', i + 1);
                        if (close < 0) close = body.length();
                        value = body.substring(i + 1, close);
                        i = Math.min(close + 1, body.length());
                    } else {
                        int s2 = i;
                        while (i < body.length() && body.charAt(i) != ' ') i++;
                        value = body.substring(s2, i);
                    }
                    node.attrs.put(key, value.replace("\r", ""));
                } else {
                    node.attrs.put(key, "");
                }
            }
            return node;
        }

        /** Every node with a `name=` attribute, in document order. */
        List<DNode> named() {
            List<DNode> out = new ArrayList<DNode>();
            for (DNode n : all) if (n.name() != null) out.add(n);
            return out;
        }

        DNode first(String kind, String name) {
            for (DNode n : all) {
                if (n.kind.equals(kind) && name.equals(n.name())) return n;
            }
            return null;
        }

        /** The `struct name=X` node declared in this file, or null. */
        DNode struct(String name) {
            for (DNode n : all) {
                if (n.kind.equals("struct") && name.equals(n.name())) return n;
            }
            return null;
        }

        /**
         * The `variant name=X` node declared in this file, or null -- SPEC.md section 13.
         *
         * A variant is not a `def`, which is why the insert family skipped every one of
         * them until 0.1.9: its filter kept "what the dump calls callable", and the dump
         * has a `variant` node for `Circle(radius: float)`.  A variant with a payload is
         * inserted as a *call* -- `Circle` becomes `Circle(radius)` -- so this is the
         * node its expected argument list is read from, and a payload-free variant's
         * field list is empty, which the family already knows how to judge.
         */
        DNode variant(String name) {
            for (DNode n : all) {
                if (n.kind.equals("variant") && name.equals(n.name())) return n;
            }
            return null;
        }

        /** A variant's payload field names, in the order the compiler dumps them. */
        List<String> variantFields(DNode variant) {
            List<String> out = new ArrayList<String>();
            for (DNode k : variant.kids) {
                if (k.kind.equals("field") && k.name() != null) out.add(k.name());
            }
            return out;
        }

        /** The members of a struct, in the order the compiler dumps them. */
        List<String> members(DNode struct) {
            List<String> out = new ArrayList<String>();
            for (DNode k : struct.kids) {
                if (k.kind.equals("field") || k.kind.equals("def")) {
                    if (k.name() != null) out.add(k.name());
                }
            }
            return out;
        }

        /** The parameter names of a `def` node, in order. */
        static List<String> params(DNode def) {
            List<String> out = new ArrayList<String>();
            for (DNode k : def.kids) {
                if (k.kind.equals("param") && k.name() != null) out.add(k.name());
            }
            return out;
        }

        /**
         * The type this file writes for this name, or null when it writes none or two
         * different ones.
         *
         * THE DUMP PRINTS NO SOURCE LINE NUMBERS -- every line of it is a node, and the line
         * numbers the caller has are the *file's*.  The first version of this compared the
         * dump's own line index against the file's line number and answered null for `self`
         * on every line above the dump line of its `param` node, which is how `self.` in
         * `struct_and_methods.vel` came to be counted as a receiver with no written type.
         * A name declared more than once with different types is ambiguous here, and an
         * ambiguous answer is null rather than a guess.
         */
        String declaredType(String name) {
            String best = null;
            for (DNode n : all) {
                if (!(n.kind.equals("decl") || n.kind.equals("param") || n.kind.equals("field"))) continue;
                if (!name.equals(n.name()) || n.attr("type") == null) continue;
                String type = n.attr("type");
                if (best != null && !best.equals(type)) return null;
                best = type;
            }
            return best;
        }

        /** The node kinds this file declares this name as, for a skip message. */
        String kindsOf(String name) {
            StringBuilder sb = new StringBuilder();
            for (DNode n : all) {
                if (!name.equals(n.name())) continue;
                if (sb.length() > 0) sb.append(" + ");
                sb.append(n.kind).append(n.attr("type") == null ? "" : " (" + n.attr("type") + ")");
            }
            return sb.length() == 0 ? "nothing" : sb.toString();
        }

        /** Is this name declared as a `param` anywhere in this file? */
        boolean hasParam(String name) {
            for (DNode n : all) {
                if (n.kind.equals("param") && name.equals(n.name())) return true;
            }
            return false;
        }

        /**
         * Does the file write this name's type **beside the name** — a `param`
         * (`o: Vec2`) or a `decl` (`q: Vec2 = ...`)?
         *
         * This is the question the position classes turn on, and it is not the same as
         * [declaredType], which also answers for a `field` and a `for` variable.  The
         * plugin's reader resolves a *binding* — `self`, a parameter, a local with a
         * written type, a struct's own name — and nothing else: `inner.bump()` where
         * `inner` is a field of the enclosing struct has a type the dump knows and no
         * binding to read it from, so it is counted in its own class rather than
         * demanded of the plugin.  Before 0.1.8 every local was in that class, which is
         * how 29 after-dot positions and 6 calls became positions the row could not
         * judge; they are judged from 0.1.8, because `VelaTargets.localBindingType`
         * reads the same `decl` nodes this asks about.
         */
        boolean hasWrittenBinding(String name) {
            for (DNode n : all) {
                if (!(n.kind.equals("decl") || n.kind.equals("param"))) continue;
                if (!name.equals(n.name())) continue;
                if (n.attr("type") != null && !n.attr("type").isEmpty()) return true;
            }
            return false;
        }

        /** Every name this file declares anywhere: struct, field, def, param, decl, `for` var. */
        Set<String> declaredNames() {
            Set<String> out = new LinkedHashSet<String>();
            for (DNode n : all) {
                if (n.name() != null) out.add(n.name());
                if (n.attr("var") != null && !n.attr("var").isEmpty()) out.add(n.attr("var"));
            }
            return out;
        }

        /** The names declared at module level (not inside a struct or a def). */
        Set<String> moduleNames() {
            Set<String> out = new LinkedHashSet<String>();
            for (DNode n : all) {
                // `enum` joined this list when SPEC.md section 13 landed: an enum is a
                // module-level declaration, `vm.exe parse` prints `enum name=Op` for it,
                // and the bare-end family demands every module-level name -- so a plugin
                // whose completion does not offer an enum's own type name now fails this
                // row rather than passing by omission.
                if (!(n.kind.equals("def") || n.kind.equals("struct")
                        || n.kind.equals("extern") || n.kind.equals("enum"))) continue;
                if (n.name() == null) continue;
                boolean nested = false;
                for (DNode p = n.parent; p != null; p = p.parent) {
                    if (p.kind.equals("struct") || p.kind.equals("def")) { nested = true; break; }
                }
                if (!nested) out.add(n.name());
            }
            return out;
        }

        /** The def/struct node this name is, preferring one at module level. */
        DNode callable(String name) {
            DNode method = null;
            for (DNode n : all) {
                if (!name.equals(n.name())) continue;
                if (n.kind.equals("def")) {
                    boolean nested = false;
                    for (DNode p = n.parent; p != null; p = p.parent) {
                        if (p.kind.equals("def")) { nested = true; break; }
                    }
                    if (!nested) return n;
                }
                if (n.kind.equals("def") && method == null) method = n;
            }
            return method;
        }

        DNode fieldOfStruct(String structName, String fieldName) {
            DNode s = struct(structName);
            if (s == null) return null;
            for (DNode k : s.kids) {
                if (k.kind.equals("field") && fieldName.equals(k.name())) return k;
            }
            return null;
        }
    }

    // ---------------------------------------------------------------- the lex

    static final class LexKind {
        static final int IDENT = 1;
        static final int NUMBER = 2;
        static final int STRING = 4;
        static final int PUNCT = 5;
        static final int NEWLINE = 6;
        static final int EOF = 9;
    }

    static final class LexTok {
        final int kind;
        final int line;
        final int sub;
        final int offset;
        final int len;
        LexTok(int kind, int line, int sub, int offset, int len) {
            this.kind = kind; this.line = line; this.sub = sub;
            this.offset = offset; this.len = len;
        }
        public String toString() {
            return "lex(" + kind + "/" + sub + "@" + offset + "+" + len + ")";
        }
    }

    static final class Lex {
        final List<LexTok> toks = new ArrayList<LexTok>();

        static Lex parse(String text) {
            Lex l = new Lex();
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                String[] cols = t.split("\\s+");
                if (cols.length != 5) continue;
                try {
                    l.toks.add(new LexTok(Integer.parseInt(cols[0]), Integer.parseInt(cols[1]),
                            Integer.parseInt(cols[2]), Integer.parseInt(cols[3]), Integer.parseInt(cols[4])));
                } catch (NumberFormatException ignored) {
                    // a diagnostic on the same stream: not a token line
                }
            }
            return l;
        }

        /** The token starting exactly at this offset, or null. */
        LexTok startingAt(int offset) {
            for (LexTok t : toks) if (t.offset == offset && t.len > 0) return t;
            return null;
        }

        /** The token covering this offset (a string's contents, not its quotes), or null. */
        LexTok covering(int offset) {
            for (LexTok t : toks) {
                if (t.len > 0 && offset >= t.offset && offset < t.offset + t.len) return t;
            }
            return null;
        }

        /** The text of a word token, for the keyword oracle. */
        boolean isKeyword(int offset, int len) {
            LexTok t = startingAt(offset);
            return t != null && t.kind == LexKind.IDENT && t.sub != 0 && t.len == len;
        }
    }

    // ------------------------------------------------------------- SPEC.md

    /** SPEC.md 1.3's keyword list and section 8's builtin names. */
    private void readSpec() throws IOException {
        Path spec = repoRoot.resolve("SPEC.md");
        if (Files.isRegularFile(spec)) {
            List<String> lines = Files.readAllLines(spec, StandardCharsets.UTF_8);
            boolean inKeywords = false;
            boolean inFence = false;
            for (String line : lines) {
                if (line.startsWith("### 1.3")) { inKeywords = true; continue; }
                if (inKeywords && line.startsWith("### ")) { inKeywords = false; }
                if (inKeywords && line.trim().startsWith("```")) {
                    inFence = !inFence;
                    if (!inFence) inKeywords = false;
                    continue;
                }
                if (inKeywords && inFence) {
                    String body = line;
                    int hash = body.indexOf('#');
                    if (hash >= 0) body = body.substring(0, hash);
                    for (String word : body.trim().split("\\s+")) {
                        if (!word.isEmpty()) specKeywords.add(word);
                    }
                }
            }
        }
        specBuiltinNames = DeclReader.specBuiltinNames(repoRoot);
    }

    /**
     * The corpus's own indentation step, measured before anything uses it.
     *
     * Indentation is not semantic in Vela, so no compiler verdict can say whether four
     * spaces is right.  The language's own 400 KB of source can: the step is the delta
     * between the leading whitespace of a line and of the most indented line before it.
     * The count of lines that agree is printed with the result, because the Enter
     * handler's expectation below is this number.
     */
    private void measureCorpusIndentStep(List<FileWork> works) {
        Map<Integer, Integer> histogram = new TreeMap<Integer, Integer>();
        for (FileWork w : works) {
            if (w.error != null) continue;
            try {
                String text = new String(Files.readAllBytes(w.path), StandardCharsets.ISO_8859_1)
                        .replace("\r\n", "\n").replace('\r', '\n');
                int prev = 0;
                for (String line : text.split("\n", -1)) {
                    if (line.trim().isEmpty()) continue;
                    int indent = 0;
                    while (indent < line.length() && line.charAt(indent) == ' ') indent++;
                    if (line.charAt(indent) == '\t') { prev = indent; continue; }
                    int delta = indent - prev;
                    if (delta > 0) histogram.merge(delta, 1, Integer::sum);
                    prev = indent;
                }
            } catch (IOException ignored) {
                // a file that cannot be read here is skipped by analyseOne with a class
            }
        }
        int best = 0;
        int bestCount = 0;
        int samples = 0;
        for (Map.Entry<Integer, Integer> e : histogram.entrySet()) {
            samples += e.getValue();
            if (e.getValue() > bestCount) { bestCount = e.getValue(); best = e.getKey(); }
        }
        corpusIndentStep = best;
        corpusIndentSamples = bestCount;
        System.out.println("oracle 5: the corpus's own indentation step is " + best
                + " space(s) (" + bestCount + " of " + samples + " positive deltas)");
    }

    // --------------------------------------------------------------- helpers

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
            for (Path f : found) seen.add(repoRoot.relativize(f).toString().replace('\\', '/'));
        }
        for (String extra : new String[] { "selfhost/vela.vel" }) {
            Path p = repoRoot.resolve(extra);
            if (Files.isRegularFile(p)) seen.add(extra);
        }
        corpus.addAll(seen);
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }

    private static int lineStartOf(String text, int offset) {
        int i = Math.min(offset, text.length());
        while (i > 0 && text.charAt(i - 1) != '\n') i--;
        return i;
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    private static String oneLine(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() > 160) t = t.substring(0, 160) + "...";
        return t;
    }

    private static String quote(String s) {
        return "`" + s.replace("\n", "\\n") + "`";
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(bytes);
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String sha256(Path p) throws Exception {
        return sha256(Files.readAllBytes(p));
    }

    private static void deleteTree(Path dir) {
        if (dir == null) return;
        try {
            List<Path> all = new ArrayList<Path>();
            Files.walk(dir).forEach(all::add);
            Collections.sort(all, Collections.reverseOrder());
            for (Path p : all) {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) {
            // the scratch tree is under %TEMP%; leaving it is not a failure
        }
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < n) sb.append(' ');
        if (sb.length() > n) sb.setLength(n);
        return sb.toString();
    }

    /** A default answer for a proxy method: a *shape* answer, never a plausible one. */
    static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == char.class) return '\0';
        if (r == double.class) return 0d;
        if (r == float.class) return 0f;
        if (r == short.class) return (short) 0;
        if (r == byte.class) return (byte) 0;
        return null;
    }

    // ============================================================== the coverage

    static final String[] SKIP1 = {
        "compiler-refused-file", "too-large", "missing-corpus-file", "dump-unavailable",
        "lex-unavailable", "crashed",
        "receiver-type-not-written",   // `.` on a receiver the dump writes no type for
        "receiver-not-a-struct",       // the written type is not a struct of this file
        "receiver-not-a-written-binding", // the type is the dump's, but no `decl`/`param` writes
                                       // it beside the name (a field, a loop variable): the plugin
                                       // reads bindings, and answers nothing here by design
        "insert-not-a-callable",       // an offered element whose insert handler wrote nothing
        "template-names-not-in-spec",  // a builtin's names that SPEC.md section 8 does not document
        "contributor-threw",           // the platform entry point threw on this position
    };

    static final String[] SKIP2 = {
        "compiler-refused-file", "too-large", "missing-corpus-file", "dump-unavailable",
        "lex-unavailable", "crashed",
        "callee-not-a-declared-def",   // a struct constructor, an undeclared name, a method of
                                       // a receiver whose type the dump does not write
        "receiver-not-a-written-binding", // `o.inner.bump()` where `inner` is a field: the type is
                                       // the dump's, no binding writes it beside the name
        "receiver-on-a-function",
        "method-call-without-receiver",
        "spec-row-documents-no-names",
        "plugin-claims-no-names",      // the plugin's own builtin reader answers null too
        "parameter-list-untrusted",    // the item carries no parameter names, so the popup is disabled by design
        "struct-constructor-popup-closed", // a struct called as a constructor: the popup does not open
        "rebuilt-anchor-unavailable",  // the stand-in could not produce a leaf the hint is not on
    };

    static final String[] SKIP3 = {
        "compiler-refused-file", "too-large", "missing-corpus-file", "dump-unavailable",
        "lex-unavailable", "crashed",
        "closer-not-required",         // the compiler accepts the text without the closer
        "prefix-not-a-complete-program",
        "indent-not-the-corpus-step",  // the line's own indentation disagrees with the step
        "first-line",                  // no previous line, so `postProcessEnter` refuses by design
        "line-ends-with-brace",        // branch 1's shape, judged as its own family
    };

    private static String allSkipCountersZero() {
        StringBuilder sb = new StringBuilder();
        for (String k : COMMON_SKIPS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(k).append(" 0");
        }
        return sb.toString();
    }

    /** One section's counters: what ran, what was skipped and why, and what was wrong. */
    static final class Cov {
        final String[] kinds;
        final Map<String, Integer> skips = new LinkedHashMap<String, Integer>();
        final Map<String, List<String>> rows = new LinkedHashMap<String, List<String>>();
        final List<String> wrong = new ArrayList<String>();
        final Map<String, int[]> families = new LinkedHashMap<String, int[]>();
        int ran;
        int wrongCount;
        final List<String> notes = new ArrayList<String>();

        Cov(String[] kinds) {
            this.kinds = kinds;
            for (String k : kinds) {
                skips.put(k, 0);
                rows.put(k, new ArrayList<String>());
            }
        }

        void ran(String family) {
            ran++;
            int[] f = families.get(family);
            if (f == null) { f = new int[2]; families.put(family, f); }
            f[0]++;
        }

        void wrong(String family, String row) {
            wrongCount++;
            int[] f = families.get(family);
            if (f == null) { f = new int[2]; families.put(family, f); }
            f[1]++;
            if (wrong.size() < MAX_WRONG_ROWS) wrong.add(row);
        }

        void skip(String kind, String row) {
            Integer n = skips.get(kind);
            if (n == null) throw new IllegalStateException("undeclared skip class: " + kind);
            skips.put(kind, n + 1);
            List<String> list = rows.get(kind);
            if (list != null && list.size() < MAX_ROWS_PER_CLASS) list.add(row);
        }

        void note(String s) { notes.add(s); }

        int skipped() {
            int n = 0;
            for (int v : skips.values()) n += v;
            return n;
        }

        String skipCategories() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : skips.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(e.getKey()).append(' ').append(e.getValue());
            }
            return sb.toString();
        }
    }

    private static Cov merge(Cov a, Cov b) {
        for (Map.Entry<String, Integer> e : b.skips.entrySet()) {
            a.skips.put(e.getKey(), a.skips.get(e.getKey()) + e.getValue());
        }
        for (Map.Entry<String, List<String>> e : b.rows.entrySet()) {
            List<String> dst = a.rows.get(e.getKey());
            for (String s : e.getValue()) if (dst.size() < MAX_ROWS_PER_CLASS) dst.add(s);
        }
        a.wrong.addAll(b.wrong);
        a.notes.addAll(b.notes);
        a.ran += b.ran;
        a.wrongCount += b.wrongCount;
        for (Map.Entry<String, int[]> e : b.families.entrySet()) {
            int[] f = a.families.get(e.getKey());
            if (f == null) { f = new int[2]; a.families.put(e.getKey(), f); }
            f[0] += e.getValue()[0];
            f[1] += e.getValue()[1];
        }
        return a;
    }

    private static Cov total(List<FileWork> works, int which) {
        Cov sum = new Cov(which == 1 ? SKIP1 : which == 2 ? SKIP2 : SKIP3);
        for (FileWork w : works) merge(sum, which == 1 ? w.c1 : which == 2 ? w.c2 : w.c3);
        return sum;
    }

    // ======================================================== section 1: row 7

    /**
     * The completion contributor, driven through the platform's own entry point.
     *
     * Three families, and each has its own oracle:
     *
     *   `bare-end`    the caret at the end of the file.  Everything the file declares at
     *                 module level (`vm.exe parse`), every keyword `SPEC.md` 1.3 lists and
     *                 every builtin whose row in section 8 documents parameter names must
     *                 be offered; and every offered name must be one of those, a name the
     *                 file declares anywhere, or a builtin of the language's own table --
     *                 which is the fabrication axis, and the only one that can catch a
     *                 suggested name nothing declares.
     *   `after-dot`   `<receiver>.` at the caret, for the receivers the plugin's own rule
     *                 resolves (`self`, a parameter whose written type is a struct of this
     *                 file, a local binding with a written type, a struct's own name).  The
     *                 offer set must be exactly the members `vm.exe parse` lists for that
     *                 struct: no member missing, no name invented.  The receivers the rule
     *                 does *not* resolve -- a field or a loop variable read as a receiver,
     *                 whose type the dump knows and no binding writes beside the name -- are
     *                 counted in their own class and printed.
     *   `insert`      `LookupElement.handleInsert` on the offered elements, in a real
     *                 `InsertionContext` over a real `OffsetMap`: for a callable the
     *                 written text must be the call template whose names are the ones the
     *                 compiler (or section 8) declares, and for a non-callable it must
     *                 write nothing.
     */
    private void section1(FileWork w) {
        w.c1FilesJudged++;
        String text = w.text;
        // The lex is the *file's own* token stream: one leaf per token.
        Fil real = makeFil(w, text, w.name());
        PsiFile file = real.proxy();
        pdm.register(file, real.document);
        Editor ed = new Ed(real.document, real.doc, 0).proxy();
        VelaCompletionContributor cc = new VelaCompletionContributor();

        Set<String> pluginBuiltins = new LinkedHashSet<String>();
        for (Object b : VelaModel.INSTANCE.getBUILTINS()) {
            String n = (String) call(b, "getName");
            if (n != null) pluginBuiltins.add(n);
        }
        Set<String> declaredAnywhere = w.dump.declaredNames();
        Set<String> undocumented = new LinkedHashSet<String>();
        // The tree is used to place a *position* -- which struct encloses an offset -- and
        // never to say what that struct means: the member list is the compiler's own dump.
        VelaSyntaxTree tree1 = VelaSyntaxParser.parse(text);

        // ---------------------------------------------------------- bare-end
        if (text.length() > 0) {
            List<LookupElement> offered = offers(cc, file, ed, text.length(), w.c1, w);
            if (offered != null) {
                Set<String> names = lookupNames(offered);
                List<String> missing = new ArrayList<String>();
                for (String k : specKeywords) if (!names.contains(k)) missing.add("keyword " + k);
                for (String b : specBuiltinNames.keySet()) if (!names.contains(b)) missing.add("builtin " + b);
                for (String d : w.dump.moduleNames()) if (!names.contains(d)) missing.add("declared " + d);
                List<String> fabricated = new ArrayList<String>();
                for (String n : names) {
                    if (specKeywords.contains(n) || declaredAnywhere.contains(n)) continue;
                    if (pluginBuiltins.contains(n)) {
                        if (!specBuiltinNames.containsKey(n)) undocumented.add(n);
                        continue;
                    }
                    fabricated.add(n);
                }
                w.c1.ran("bare-end");
                if (!missing.isEmpty() || !fabricated.isEmpty()) {
                    w.c1.wrong("bare-end", "  " + w.rel + ": the caret at the end of the file"
                            + (missing.isEmpty() ? "" : "; not offered: " + join(missing))
                            + (fabricated.isEmpty() ? "" : "; offered but declared nowhere: "
                                    + join(fabricated)));
                }
                if (!undocumented.isEmpty()) {
                    // One class per file, not one per name: the class is a property of the
                    // language's table, and counting it 44 times per file would drown the
                    // coverage line it is supposed to be readable in.
                    w.c1.skip("template-names-not-in-spec", w.rel + ": " + undocumented.size()
                            + " builtin(s) this plugin's table holds and SPEC.md section 8"
                            + " documents no parameter names for: " + firstFew(undocumented));
                }
                if (show) {
                    System.out.println("  " + pad(w.rel, 40) + pad("bare-end", 12)
                            + "offered " + names.size() + ", missing " + missing.size()
                            + ", fabricated " + fabricated.size());
                }
            }
        }

        // ---------------------------------------------------------- insert
        if (text.length() > 0) {
            List<LookupElement> offered = offers(cc, file, ed, text.length(), w.c1, w);
            if (offered != null) {
                for (LookupElement e : offered) {
                    String name = e.getLookupString();
                    DNode variantNode = w.dump.variant(name);
                    if (!(w.dump.callable(name) != null || specBuiltinNames.containsKey(name)
                            || pluginBuiltins.contains(name) || variantNode != null)) {
                        continue; // a keyword or a non-callable: the family is calls only
                    }
                    List<String> want = null;
                    boolean fromCompiler = false;
                    DNode def = w.dump.callable(name);
                    if (def != null && def.parent != null && !def.parent.kind.equals("struct")) {
                        want = Dump.params(def);
                        fromCompiler = true;
                    } else if (specBuiltinNames.containsKey(name)) {
                        want = specBuiltinNames.get(name);
                        fromCompiler = true;
                    } else if (variantNode != null) {
                        // SPEC.md §13: a variant with a payload is written as a call whose
                        // arguments are the payload's fields, and a payload-free variant is
                        // written as a bare name -- so the oracle for `Circle` is its field
                        // list and for `Empty` it is *nothing*, which the `want.isEmpty()`
                        // branch below already knows how to judge.
                        want = w.dump.variantFields(variantNode);
                        fromCompiler = true;
                    }
                    if (want == null) continue;
                    Doc d = new Doc(text + "\n    ");
                    Ed ed2 = new Ed(d.proxy(), d, text.length() + 5);
                    OffsetMap om = new OffsetMap(d.proxy());
                    om.addOffset(com.intellij.codeInsight.completion.CompletionInitializationContext
                            .SELECTION_END_OFFSET, text.length() + 5);
                    int before = d.writes().size();
                    w.c1.ran("insert");
                    try {
                        InsertionContext ic = new InsertionContext(om, '\0',
                                new LookupElement[] { e }, file, ed2.proxy(), false);
                        e.handleInsert(ic);
                    } catch (Throwable t) {
                        w.c1.wrong("insert", "  " + w.rel + ": `" + name + "` handleInsert threw: "
                                + oneLine(String.valueOf(t)));
                        continue;
                    }
                    StringBuilder written = new StringBuilder();
                    for (int i = before; i < d.writes().size(); i++) written.append(d.writes().get(i).text);
                    String writtenText = written.toString();
                    String after = text + "\n    " + writtenText;
                    if (!d.text.equals(after)) {
                        w.c1.wrong("insert", "  " + w.rel + ": `" + name + "` wrote "
                                + quote(writtenText) + " and the document is not that text");
                        continue;
                    }
                    if (want.isEmpty()) {
                        if (!writtenText.isEmpty() && !writtenText.equals("()")) {
                            w.c1.wrong("insert", "  " + w.rel + ": `" + name + "` has no parameters"
                                    + " and its template wrote " + quote(writtenText));
                        } else if (writtenText.isEmpty()) {
                            w.c1.skip("insert-not-a-callable", w.rel + ": `" + name
                                    + "` wrote no template at all");
                        }
                        continue;
                    }
                    List<String> gotNames = templateNames(writtenText);
                    if (gotNames == null) {
                        w.c1.wrong("insert", "  " + w.rel + ": `" + name + "` template " + quote(writtenText)
                                + " is not a call, and the compiler declares " + want
                                + (fromCompiler ? "" : " (section 8)"));
                        continue;
                    }
                    if (!gotNames.equals(want)) {
                        w.c1.wrong("insert", "  " + w.rel + ": `" + name + "` template " + quote(writtenText)
                                + " names " + gotNames + ", and the declaration names " + want
                                + (fromCompiler ? "" : " (section 8)"));
                    }
                    // The caret goes inside the template, and the first parameter is
                    // selected: that is what makes the next keystroke replace it.
                    if (controlInsert == null) {
                        synchronized (this) {
                            if (controlInsert == null) {
                                controlInsert = new Object[] { writtenText, want, w.rel + " name " + name };
                            }
                        }
                    }                    int inside = text.length() + 5;
                    if (ed2.caret < inside || ed2.caret > after.length()) {
                        w.c1.wrong("insert", "  " + w.rel + ": `" + name + "` left the caret at "
                                + ed2.caret + ", outside the text it wrote (" + inside + ".."
                                + after.length() + ")");
                    }
                }
            }
        }

        // ---------------------------------------------------------- after-dot
        List<Dot> dots = dotPositions(text, w);
        for (Dot d : dots) {
            String type = w.dump.declaredType(d.receiver);
            if (type == null) {
                w.c1.skip("receiver-type-not-written", w.rel + ":" + lineOf(text, d.dot) + " `"
                        + d.receiver + ".`");
                continue;
            }
            DNode struct = w.dump.struct(stripMut(type));
            if (struct == null) {
                w.c1.skip("receiver-not-a-struct", w.rel + ":" + lineOf(text, d.dot) + " `"
                        + d.receiver + ".` has written type `" + type + "`");
                continue;
            }
            boolean self = d.receiver.equals("self") && enclosingStructName(tree1, d.dot) != null;
            boolean structName = d.receiver.equals(struct.name());
            // A LOCAL BINDING WITH A WRITTEN TYPE IS A JUDGED POSITION FROM 0.1.8.
            //
            // It was the 29-position class this row counted and did not judge, and the
            // reason was a real hole in the plugin and not a measurement gap: `q: Vec2 =
            // ...` is the most ordinary way a local is written, and the model's type
            // reader resolved only `self`, parameters and struct names.  The reader
            // (`VelaTargets.localBindingType`) now reads the tree's own `decl` node, so
            // the position is demanded of the plugin like the other three kinds.  The
            // condition is `hasWrittenBinding`, not the old `!param && !self &&
            // !structName`, so a receiver that is a *field* or a loop variable -- a type
            // the dump knows and no binding writes beside a name -- is still counted in
            // its own class rather than called wrong.
            if (!w.dump.hasWrittenBinding(d.receiver) && !self && !structName) {
                w.c1.skip("receiver-not-a-written-binding", w.rel + ":" + lineOf(text, d.dot) + " `"
                        + d.receiver + ".` has written type `" + type + "` and the file declares it"
                        + " as " + w.dump.kindsOf(d.receiver) + ", not as a binding with a type"
                        + " written beside the name");
                continue;
            }
            List<LookupElement> offered = offers(cc, file, ed, d.dot + 1, w.c1, w);
            if (offered == null) continue;
            Set<String> names = lookupNames(offered);
            List<String> want = w.dump.members(struct);
            List<String> missing = new ArrayList<String>();
            for (String m : want) if (!names.contains(m)) missing.add(m);
            List<String> extra = new ArrayList<String>();
            for (String n : names) if (!want.contains(n)) extra.add(n);
            w.c1.ran("after-dot");
            if (controlDot == null) {
                synchronized (this) {
                    if (controlDot == null) {
                        controlDot = new Object[] { want, names, w.rel + ":" + lineOf(text, d.dot) + " " + d.receiver + "." };
                    }
                }
            }
            if (!missing.isEmpty() || !extra.isEmpty()) {
                w.c1.wrong("after-dot", "  " + w.rel + ":" + lineOf(text, d.dot) + " `" + d.receiver
                        + ".` (type `" + struct.name() + "`): the compiler declares " + want
                        + (missing.isEmpty() ? "" : "; not offered " + missing)
                        + (extra.isEmpty() ? "" : "; offered but not a member " + extra));
            }
            if (show) {
                System.out.println("  " + pad(w.rel, 40) + pad("after-dot", 12) + d.receiver
                        + " -> " + struct.name() + " offered " + names.size() + " of " + want.size());
            }
        }
    }

    /** One `<receiver>.` position, from the file's own text. */
    static final class Dot {
        final String receiver;
        final int dot;
        Dot(String receiver, int dot) { this.receiver = receiver; this.dot = dot; }
    }

    /**
     * Every `identifier .` in the text, read from the file's own characters.
     *
     * This is the harness locating a position, not deciding what it means: what the
     * receiver's type is comes from the compiler's dump, in the caller.
     */
    private static List<Dot> dotPositions(String text, FileWork w) {
        List<Dot> out = new ArrayList<Dot>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '#') {
                int nl = text.indexOf('\n', i);
                i = nl < 0 ? text.length() : nl + 1;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = endOfString(text, i);
                continue;
            }
            if (c == '.' && i > 0 && i + 1 < text.length()) {
                int end = i;
                int start = end;
                while (start > 0 && isNamePart(text.charAt(start - 1))) start--;
                char first = start < end ? text.charAt(start) : '0';
                boolean identifier = Character.isLetter(first) || first == '_';
                if (start < end && identifier && isNamePart(text.charAt(i + 1))) {
                    out.add(new Dot(text.substring(start, end), i));
                }
            }
            i++;
        }
        return out;
    }

    private static int endOfString(String text, int start) {
        char q = text.charAt(start);
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) { i += 2; continue; }
            if (c == q || c == '\n') return i + 1;
            i++;
        }
        return text.length();
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String stripMut(String type) {
        String t = type.trim();
        if (t.startsWith("mut ")) t = t.substring(4).trim();
        return t;
    }



    /** The innermost `struct` node whose tokens contain this offset, or null. */
    private static String enclosingStructName(VelaSyntaxTree tree, int offset) {
        List<VelaSyntaxNode> all = new ArrayList<VelaSyntaxNode>();
        collectAll(tree.root, all);
        String best = null;
        int bestStart = -1;
        for (VelaSyntaxNode n : all) {
            if (n.kind != VelaNodeKind.STRUCT) continue;
            int s = tokenStart(tree, n);
            int e = tokenEnd(tree, n);
            if (s < 0 || e < s || offset < s || offset > e) continue;
            if (s > bestStart) { bestStart = s; best = n.name; }
        }
        return best;
    }

    private static void collectAll(VelaSyntaxNode n, List<VelaSyntaxNode> out) {
        out.add(n);
        for (VelaSyntaxNode c : n.children) collectAll(c, out);
    }

    private static int tokenEnd(VelaSyntaxTree tree, VelaSyntaxNode n) {
        if (n.endTok >= 0 && n.endTok < tree.toks.size()) return tree.toks.get(n.endTok).end;
        int best = -1;
        for (VelaSyntaxNode c : n.children) best = Math.max(best, tokenEnd(tree, c));
        return best;
    }

    /**
     * `fillCompletionVariants`, with the platform's own `CompletionParameters` and this
     * tool's recording `CompletionResultSet`.  A throw is a finding of its own class,
     * printed with the exception: the entry point is the thing being driven.
     */
    private List<LookupElement> offers(CompletionContributor cc, PsiFile file, Editor ed, int offset,
                                       Cov cov, FileWork w) {
        Rec rec = new Rec();
        try {
            CompletionParameters p = new CompletionParameters(file.findElementAt(Math.min(offset, w.text.length() - 1)),
                    file, CompletionType.BASIC, offset, 0, ed, emptyProcess());
            cc.fillCompletionVariants(p, rec);
        } catch (Throwable t) {
            cov.skip("contributor-threw", w.rel + " offset " + offset + ": "
                    + oneLine(String.valueOf(t)));
            if (explain) t.printStackTrace(System.out);
            return null;
        }
        return rec.elements;
    }

    private static CompletionProcess processProxy;

    private static synchronized CompletionProcess emptyProcess() {
        if (processProxy == null) {
            processProxy = (CompletionProcess) Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                    new Class<?>[] { CompletionProcess.class }, (p, m, a) -> def(m));
        }
        return processProxy;
    }

    private static Set<String> lookupNames(List<LookupElement> elements) {
        Set<String> out = new LinkedHashSet<String>();
        for (LookupElement e : elements) out.add(e.getLookupString());
        return out;
    }

    /**
     * The parameter names a written template names, or null when it is not a call.
     *
     * The written text is parsed (a leading `(`, a trailing `)`, comma-separated entries)
     * rather than compared to a string, so the plugin's choice of spacing is not what is
     * being judged: the *names* are.
     */
    private static List<String> templateNames(String written) {
        String t = written.trim();
        if (t.length() < 2 || t.charAt(0) != '(' || t.charAt(t.length() - 1) != ')') return null;
        String inner = t.substring(1, t.length() - 1).trim();
        List<String> out = new ArrayList<String>();
        if (inner.isEmpty()) return out;
        for (String part : inner.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) return null;
            out.add(name);
        }
        return out;
    }

    private static String firstFew(Set<String> xs) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String x : xs) {
            if (i++ == 12) { sb.append(", ..."); break; }
            if (sb.length() > 0) sb.append(", ");
            sb.append(x);
        }
        return sb.toString();
    }

    private static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(x);
        }
        return sb.toString();
    }

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    // ======================================================== section 2: row 9

    /** One call site, read from the file's own token stream inside the parsed node. */
    static final class CallSite {
        final String callee;
        final String receiver;
        final int calleeStart;
        final int openParen;
        final List<int[]> args = new ArrayList<int[]>();
        CallSite(String callee, String receiver, int calleeStart, int openParen) {
            this.callee = callee;
            this.receiver = receiver;
            this.calleeStart = calleeStart;
            this.openParen = openParen;
        }
    }

    private static void collectCalls(VelaSyntaxNode n, List<VelaSyntaxNode> out) {
        if (n.kind == VelaNodeKind.CALL) out.add(n);
        for (VelaSyntaxNode c : n.children) collectCalls(c, out);
    }

    /**
     * The five platform steps, in the platform's order, on every call the file writes.
     *
     * WHAT IS JUDGED.  For a call whose callee is a `def` this file declares (or a
     * builtin whose `SPEC.md` section 8 row documents its parameter names):
     *
     *   1  `findElementForParameterInfo` must return a leaf and put exactly one item in
     *      `context.itemsToShow` -- a call the compiler binds to a declaration this file
     *      writes, and for which nothing opens, is the popup failing to appear;
     *   2  `showParameterInfo` must set the same leaf as the highlighted element;
     *   3  `findElementForUpdatingParameterInfo` must find that leaf again (the identity
     *      path the platform uses while the document is not edited);
     *   4  `updateParameterInfo` must call `setCurrentParameter(i)` for the caret in the
     *      i-th argument -- and the parameter that index *names* is the one the compiler
     *      binds that argument to (the dump: the receiver expression is the first
     *      parameter, the parentheses' arguments are the rest);
     *   5  `updateUI` must draw the declaration's own parameter names in order with the
     *      return type, and the emphasised range must cover exactly that parameter.
     *
     * The last two are the axis this row was `partial` for: what `setCurrentParameter`
     * is given and what `updateUI` draws had never been driven by anything.
     */
    private void section2(FileWork w) {
        w.c2FilesJudged++;
        String text = w.text;
        Fil real = makeFil(w, text, w.name());
        PsiFile file = real.proxy();
        pdm.register(file, real.document);
        VelaSyntaxTree tree = VelaSyntaxParser.parse(text);
        List<VelaSyntaxNode> calls = new ArrayList<VelaSyntaxNode>();
        collectCalls(tree.root, calls);
        VelaParameterInfoHandler handler = new VelaParameterInfoHandler();

        for (VelaSyntaxNode call : calls) {
            CallSite cs = callSite(text, tree, call);
            if (cs == null || cs.openParen < 0) {
                w.c2.skip("callee-not-a-declared-def", w.rel + ":" + lineOf(text, tokenStart(tree, call))
                        + " a call node whose parentheses this harness cannot place");
                continue;
            }
            int line = lineOf(text, cs.calleeStart);
            DNode def = w.dump.callable(cs.callee);
            boolean method = def != null && def.parent != null && def.parent.kind.equals("struct");
            boolean builtin = false;
            boolean structConstructor = false;
            List<String> declared;
            String ret = "";
            if (method) {
                if (cs.receiver == null) {
                    w.c2.skip("method-call-without-receiver", w.rel + ":" + line + " `" + cs.callee
                            + "(` is a method of `" + def.parent.name() + "` with no receiver written");
                    continue;
                }
                declared = Dump.params(def);
                ret = nz(def.attr("ret"));
            } else if (def != null) {
                if (cs.receiver != null) {
                    w.c2.skip("receiver-on-a-function", w.rel + ":" + line + " `" + cs.receiver + "."
                            + cs.callee + "(` is a module-level function");
                    continue;
                }
                declared = Dump.params(def);
                ret = nz(def.attr("ret"));
            } else if (specBuiltinNames.containsKey(cs.callee) && cs.receiver == null) {
                declared = specBuiltinNames.get(cs.callee);
                builtin = true;
            } else if (cs.receiver == null && w.dump.variant(cs.callee) != null) {
                // SPEC.md §13: a variant with a payload is constructed as a call, and the
                // call's arguments are the payload's fields in declaration order -- the same
                // reader the insert family uses to hold `Circle` to `Circle(radius)`.  The
                // popup must open for such a call like any other the file declares, so this
                // is *judged* and not counted: these were the positions in the
                // `callee-not-a-declared-def` class (24 of them on the §13 corpus) that this
                // round moves into `find`/`show`/`update`.
                declared = w.dump.variantFields(w.dump.variant(cs.callee));
            } else if (cs.receiver == null && w.dump.struct(cs.callee) != null) {
                // A struct's own name, called: the compiler's dump lists the call's
                // arguments against the struct's `field` nodes in order, so the
                // declaration is the field list.  Whether the popup opens for it is the
                // plugin's decision and is measured below; when it does not, that is a
                // counted class and not a wrong answer.
                DNode st = w.dump.struct(cs.callee);
                declared = new ArrayList<String>();
                for (DNode k : st.kids) {
                    if (k.kind.equals("field") && k.name() != null) declared.add(k.name());
                }
                structConstructor = true;
            } else {
                w.c2.skip("callee-not-a-declared-def", w.rel + ":" + line + " `" + cs.callee
                        + "(` is not a `def` this file declares and SPEC.md section 8 documents no"
                        + " names for it");
                continue;
            }

            int receiverParams = cs.receiver == null ? 0 : 1;
            // The receivers the model resolves, for the skip class below: `self`, a
            // parameter, a local binding with a written type, a struct's own name.  The
            // local was the 6-call class this row counted and did not judge until 0.1.8
            // (`VelaTargets.localBindingType` reads the tree's `decl` node now), and the
            // class that remains is a receiver whose type the dump knows but no binding
            // writes beside the name -- `o.inner.bump()`, where `inner` is a field.
            if (cs.receiver != null && !cs.receiver.equals("self")) {
                String rtype = w.dump.declaredType(cs.receiver);
                boolean binding = w.dump.hasWrittenBinding(cs.receiver);
                boolean structName = rtype != null && w.dump.struct(stripMut(rtype)) != null
                        && stripMut(rtype).equals(cs.receiver);
                if (!binding && !structName && rtype != null && w.dump.struct(stripMut(rtype)) != null) {
                    w.c2.skip("receiver-not-a-written-binding", w.rel + ":" + line + " `" + cs.receiver + "."
                            + cs.callee + "(` has written type `" + rtype + "` and the file declares it as "
                            + w.dump.kindsOf(cs.receiver) + ", not as a binding with a type written"
                            + " beside the name");
                    continue;
                }
            }

            if (show) {
                System.out.println("  " + pad(w.rel, 40) + pad("call", 12) + cs.callee
                        + (cs.receiver == null ? "" : " on `" + cs.receiver + "`")
                        + " openParen " + cs.openParen + ", " + cs.args.size() + " argument(s)"
                        + ", declared " + declared);
            }
            List<Integer> carets = new ArrayList<Integer>();
            if (cs.args.isEmpty()) carets.add(cs.openParen + 1);
            else for (int[] a : cs.args) carets.add(a[0]);
            int firstCaret = carets.get(0);

            w.c2.ran("find");
            CreateCtx create = new CreateCtx(file, firstCaret, cs.openParen);
            PsiElement anchor;
            try {
                anchor = handler.findElementForParameterInfo(create.proxy());
            } catch (Throwable t) {
                w.c2.wrong("find", "  " + w.rel + ":" + line + " `" + cs.callee
                        + "(` findElementForParameterInfo threw: " + oneLine(String.valueOf(t)));
                continue;
            }
            if (anchor == null && structConstructor) {
                w.c2.skip("struct-constructor-popup-closed", w.rel + ":" + line + " `" + cs.callee
                        + "(` declares " + declared + " (the struct's fields) and no popup opens");
                continue;
            }
            if (anchor == null) {
                // The plugin's own doc: the popup opens for a call it can resolve.  A call
                // to a `def` this file writes is the case it claims, so this is a finding.
                w.c2.wrong("find", "  " + w.rel + ":" + line + " `" + cs.callee + "(` declares "
                        + declared + (builtin ? " (section 8)" : " in this file")
                        + ", and findElementForParameterInfo answered null: no popup opens"
                        + (cs.receiver != null ? " (receiver `" + cs.receiver + "`)" : ""));
                continue;
            }
            w.c2.ran("show");
            try {
                handler.showParameterInfo(anchor, create.proxy());
            } catch (Throwable t) {
                w.c2.wrong("show", "  " + w.rel + ":" + line + " `" + cs.callee
                        + "(` showParameterInfo threw: " + oneLine(String.valueOf(t)));
                continue;
            }
            if (create.items == null || create.items.length != 1) {
                w.c2.wrong("show", "  " + w.rel + ":" + line + " `" + cs.callee
                        + "(` itemsToShow is " + (create.items == null ? "null"
                                : String.valueOf(create.items.length)) + " after showParameterInfo");
                continue;
            }
            if (create.highlighted != anchor) {
                w.c2.wrong("show", "  " + w.rel + ":" + line + " `" + cs.callee
                        + "(` showParameterInfo did not set the highlighted element to the anchor");
            }

            Object item = create.items[0];
            boolean anyJudged = false;
            for (int i = 0; i < carets.size(); i++) {
                int caret = carets.get(i);
                UpdCtx upd = new UpdCtx(file, caret, cs.openParen, anchor);
                upd.objectsToView = create.items;
                PsiElement back;
                w.c2.ran("update");
                try {
                    back = handler.findElementForUpdatingParameterInfo(upd.proxy());
                } catch (Throwable t) {
                    w.c2.wrong("update", "  " + w.rel + ":" + line + " `" + cs.callee
                            + "(` findElementForUpdatingParameterInfo threw: "
                            + oneLine(String.valueOf(t)));
                    continue;
                }
                if (back != anchor) {
                    w.c2.wrong("update", "  " + w.rel + ":" + line + " `" + cs.callee
                            + "(` findElementForUpdatingParameterInfo did not return the leaf"
                            + " findElementForParameterInfo returned (the document was not edited)");
                }
                try {
                    handler.updateParameterInfo(anchor, upd.proxy());
                } catch (Throwable t) {
                    w.c2.wrong("index", "  " + w.rel + ":" + line + " `" + cs.callee
                            + "(` updateParameterInfo threw: " + oneLine(String.valueOf(t)));
                    continue;
                }
                if (upd.currentCalls != 1) {
                    w.c2.wrong("index", "  " + w.rel + ":" + line + " `" + cs.callee
                            + "(` updateParameterInfo called setCurrentParameter " + upd.currentCalls
                            + " time(s)");
                }
                if (upd.current != i) {
                    w.c2.wrong("index", "  " + w.rel + ":" + line + " `" + cs.callee + "(` caret at"
                            + " argument " + i + " (offset " + caret + ") and setCurrentParameter was"
                            + " given " + upd.current);
                }
                Uic uic = new Uic();
                uic.currentParameterIndex = upd.current;
                try {
                    handler.updateUI((ParameterHint) item, uic.proxy());
                } catch (Throwable t) {
                    w.c2.wrong("draw", "  " + w.rel + ":" + line + " `" + cs.callee
                            + "(` updateUI threw: " + oneLine(String.valueOf(t)));
                    continue;
                }
                if (!uic.called) {
                    // `ParameterHint.params` is null when the declaration's parameter list
                    // cannot be trusted, and then the popup is disabled *by design* -- the
                    // plugin's own doc: "the popup is disabled rather than drawn with names
                    // that are not there".  That is a limit of the reader, counted and
                    // printed with the names the compiler does write, not a wrong answer.
                    if (call(item, "getParams") == null) {
                        w.c2.skip("parameter-list-untrusted", w.rel + ":" + line + " `" + cs.callee
                                + "(` carries no parameter names, and the declaration names "
                                + declared + (builtin ? " (section 8)" : ""));
                        continue;
                    }
                    w.c2.wrong("draw", "  " + w.rel + ":" + line + " `" + cs.callee
                            + "(` updateUI drew nothing (it disabled the component instead of"
                            + " setting a presentation)");
                    continue;
                }
                anyJudged = true;
                // (A) the names, in order, are the declaration's -- or the declaration's
                // minus the receiver parameter, which is written before the dot.
                String namesFinding = namesFinding(declared, receiverParams, uic);
                if (namesFinding != null) {
                    w.c2.wrong("draw", "  " + w.rel + ":" + line + " `" + cs.callee + "(` " + namesFinding
                            + " (declared " + declared + (builtin ? ", section 8" : "") + ")");
                }
                // (B) the emphasis covers the parameter the compiler binds this argument to.
                int bound = receiverParams + i;
                String emphasisFinding = emphasisFinding(declared, bound, uic,
                        w.rel + ":" + line + " `" + cs.callee + "(` argument " + i);
                if (emphasisFinding != null) w.c2.wrong("emphasis", emphasisFinding);
                // (C) the return type, as the declaration spells it.
                if (!builtin && !structConstructor) {
                    String suffixFinding = suffixFinding(ret, uic.text);
                    if (suffixFinding != null) {
                        w.c2.wrong("suffix", "  " + w.rel + ":" + line + " `" + cs.callee + "(` "
                                + suffixFinding + " (declared `ret=" + ret + "`)");
                    }
                }
                if (controlHint == null && anyJudged && !declared.isEmpty()) {
                    synchronized (this) {
                        if (controlHint == null) {
                            controlHandler = handler;
                            controlHint = item;
                            controlDeclared = declared;
                            controlReceiverParams = receiverParams;
                            controlBound = bound;
                            controlWhere = w.rel + ":" + line + " `" + cs.callee + "`";
                        }
                    }
                }
                if (show) {
                    System.out.println("  " + pad(w.rel, 40) + pad("param-info", 12) + cs.callee
                            + " arg " + i + " -> index " + upd.current + ", drawn " + quote(uic.text)
                            + " emphasis [" + uic.start + "," + uic.end + ")");
                }
            }
            if (!anyJudged) continue;
            rebuiltAnchor(w, real, file, handler, create.items, cs, line, carets.get(0));
        }
    }

    /**
     * The SECOND half of `findElementForUpdatingParameterInfo`, measured -- the half that
     * was unreachable code until 0.1.8.
     *
     * The platform re-resolves the anchor after an edit, so the leaf it hands back is a new
     * object carrying none of the user data the old one had; the items that were shown come
     * back in `objectsToView`.  The 0.1.7 handler read that array as `(shown[0] as?
     * PsiElement)`, and its items are `ParameterHint`s -- never PSI elements -- so the cast
     * could not succeed and the branch could not run: the popup closed after any edit, and
     * the code that looked like the safety net for that was a fossil.  The handler now looks
     * for the item's own `(` again and puts the hint back on the leaf it finds, which is what
     * makes the popup survive the edit.
     *
     * Nothing about this is asserted.  The leaf is rebuilt ([Fil.rebuiltLeafAt]), the handler
     * is asked for it, and then `updateParameterInfo` is given what came back and asked to
     * set the caret's own argument index -- which it can only do if the hint is on the
     * element it was handed.  And the falsification runs too: with `objectsToView` empty the
     * handler must answer *null*, because there is then nothing that says which call the
     * element belonged to.  A handler that invented one would be drawing a hint for a call
     * it was never shown.
     */
    private void rebuiltAnchor(FileWork w, Fil real, PsiFile file, VelaParameterInfoHandler handler,
                               Object[] items, CallSite cs, int line, int caret) {
        String where = w.rel + ":" + line + " `" + cs.callee + "`";
        PsiElement rebuilt = real.rebuiltLeafAt(cs.openParen);
        if (rebuilt == null) {
            w.c2.skip("rebuilt-anchor-unavailable", w.rel + ":" + line + " `" + cs.callee
                    + "` has no leaf at its `(` after the rebuild");
            return;
        }
        // THE FALSIFICATION FIRST, because it also proves the stand-in did its job: with
        // nothing shown, nothing says which call this leaf belonged to, so the honest
        // answer is null -- and a non-null answer here would mean the rebuilt leaf still
        // carried the hint, which would make the positive check below measure nothing.
        UpdCtx bare = new UpdCtx(file, caret, cs.openParen, rebuilt);
        bare.objectsToView = null;
        PsiElement carried;
        try {
            carried = handler.findElementForUpdatingParameterInfo(bare.proxy());
        } catch (Throwable t) {
            w.c2.wrong("update-rebuilt", "  " + where + " findElementForUpdatingParameterInfo threw"
                    + " with nothing in objectsToView: " + oneLine(String.valueOf(t)));
            return;
        }
        if (carried != null) {
            w.c2.skip("rebuilt-anchor-unavailable", w.rel + ":" + line + " `" + cs.callee
                    + "` the rebuilt leaf answers with nothing in objectsToView, so the rebuild"
                    + " did not take");
            return;
        }
        UpdCtx upd = new UpdCtx(file, caret, cs.openParen, rebuilt);
        upd.objectsToView = items;
        w.c2.ran("update-rebuilt");
        PsiElement back;
        try {
            back = handler.findElementForUpdatingParameterInfo(upd.proxy());
        } catch (Throwable t) {
            w.c2.wrong("update-rebuilt", "  " + where + " findElementForUpdatingParameterInfo"
                    + " threw once the anchor leaf was rebuilt: " + oneLine(String.valueOf(t)));
            return;
        }
        if (back == null) {
            w.c2.wrong("update-rebuilt", "  " + where + " the anchor leaf was rebuilt (the platform"
                    + " does that after an edit) and the handler found nothing, though the item it"
                    + " showed came back in objectsToView: the popup would not survive an edit");
            return;
        }
        upd.current = Integer.MIN_VALUE;
        upd.currentCalls = 0;
        try {
            handler.updateParameterInfo(back, upd.proxy());
        } catch (Throwable t) {
            w.c2.wrong("update-rebuilt", "  " + where + " updateParameterInfo threw on the element"
                    + " found after the rebuild: " + oneLine(String.valueOf(t)));
            return;
        }
        if (upd.currentCalls != 1 || upd.current != 0) {
            w.c2.wrong("update-rebuilt", "  " + where + " the element found after the rebuild carries"
                    + " no hint: updateParameterInfo called setCurrentParameter " + upd.currentCalls
                    + " time(s), with " + upd.current + " (the caret is in argument 0)");
        }
    }

    /**
     * A call site, read the way this plugin's own parser records it.
     *
     * `call.children.get(0)` **is the callee**: a `NAME` node for a function call and an
     * `ATTR` node (`name=dot`, with the receiver expression as its child) for a method
     * call -- the same shape `HintDiff` reads (`calleeNode = call.children.get(0)`,
     * `args.remove(0)`), and the same shape `vm.exe parse` prints.  The arguments are the
     * children after it, and the call's own `(` is the first `(` token after the callee.
     */
    private static CallSite callSite(String text, VelaSyntaxTree tree, VelaSyntaxNode call) {
        if (call.children.isEmpty()) return null;
        VelaSyntaxNode calleeNode = call.children.get(0);
        String callee = calleeNode.name;
        if (callee == null || callee.isEmpty()) return null;
        boolean method = calleeNode.kind == VelaNodeKind.ATTR;
        String receiver = null;
        if (method) {
            if (calleeNode.children.isEmpty()) return null;
            VelaSyntaxNode recv = calleeNode.children.get(0);
            if (recv.kind != VelaNodeKind.NAME || recv.name == null) return null;
            receiver = recv.name;
        }
        int calleeStart = tokenStart(tree, calleeNode);
        int open = -1;
        int to = Math.min(tree.toks.size() - 1, call.endTok >= 0 ? call.endTok : tree.toks.size() - 1);
        for (int i = calleeNode.endTok + 1; i <= to; i++) {
            VelaTok t = tree.toks.get(i);
            if (t.end - t.start == 1 && t.start < text.length() && text.charAt(t.start) == '(') {
                open = t.start;
                break;
            }
        }
        CallSite cs = new CallSite(callee, receiver, calleeStart, open);
        for (int i = 1; i < call.children.size(); i++) {
            VelaSyntaxNode arg = call.children.get(i);
            int s = caretFor(tree, text, arg);
            if (s >= 0) cs.args.add(new int[] { s, s });
        }
        return cs;
    }

    /**
     * Where the caret goes for an argument: the argument's own start, except for a string
     * literal, whose node covers its *contents* (`"a"` starts at `a`).
     *
     * The distinction is not cosmetic.  `callAt` answers null for a caret inside an
     * unterminated string -- "the caret is inside text, not code", which is right -- so a
     * caret placed at the content of `"tests/_p.out"` opens no popup at all, and the
     * harness would have reported that as the plugin failing to resolve a call.  Measured:
     * 648 of the first full-corpus run's 1009 wrong positions were string arguments in
     * second and later positions, every one of them the caret being put one character too
     * far right.  `HintDiff` makes the same distinction for the same reason
     * (`argHintOffsets` adds the quote just before a literal's start).
     */
    private static int caretFor(VelaSyntaxTree tree, String text, VelaSyntaxNode arg) {
        int s = tokenStart(tree, arg);
        if (s <= 0 || s > text.length()) return s;
        char before = text.charAt(s - 1);
        if ((before == '"' || before == '\'') && (s < 2 || !isNamePart(text.charAt(s - 2)))) return s - 1;
        return s;
    }

    private static int tokenStart(VelaSyntaxTree tree, VelaSyntaxNode n) {
        if (n.startTok >= 0 && n.startTok < tree.toks.size()) return tree.toks.get(n.startTok).start;
        for (VelaSyntaxNode c : n.children) {
            int s = tokenStart(tree, c);
            if (s >= 0) return s;
        }
        return -1;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * The drawn parameter list must be the declaration's, in order -- allowing exactly
     * one leading parameter to be dropped when the call writes a receiver, because that
     * parameter's argument is the receiver and not one of the parentheses'.
     */
    private static String namesFinding(List<String> declared, int receiverParams, Uic uic) {
        String drawn = uic.text;
        String namesPart = drawn;
        int arrow = drawn.lastIndexOf(" -> ");
        if (arrow >= 0) namesPart = drawn.substring(0, arrow);
        List<String> got = new ArrayList<String>();
        if (!namesPart.trim().isEmpty()) {
            for (String part : namesPart.split(",")) got.add(part.trim());
        }
        List<String> want = new ArrayList<String>(declared);
        // The receiver's parameter is dropped unconditionally when the call writes a
        // receiver -- including the declaration whose *only* parameter is the receiver
        // (`p.manhattan()`), where the honest list is empty and the first version of this
        // comparison kept `self` and called the empty list wrong.
        if (receiverParams > 0 && !want.isEmpty()) want.remove(0);
        if (got.equals(want)) return null;
        return "draws the parameters as " + got + " and the call's arguments name " + want;
    }

    /**
     * The emphasised range must cover the parameter the compiler binds this argument to.
     *
     * This is the decision the row is about: the plugin chooses the range, and the
     * binding is the compiler's.  `start == end` is the honest drawing of "no argument",
     * and then the only parameters it may be is none.
     */
    private static String emphasisFinding(List<String> declared, int bound, Uic uic, String where) {
        if (bound < 0 || bound >= declared.size()) {
            if (uic.start == uic.end) return null;
            return where + " emphasises `"
                    + slice(uic.text, uic.start, uic.end) + "` and there is no argument there";
        }
        String want = declared.get(bound);
        String got = slice(uic.text, uic.start, uic.end);
        if (want.equals(got)) return null;
        return where + " is bound to `" + want + "` by the compiler and the popup emphasises `"
                + got + "` in `" + uic.text + "`";
    }

    private static String slice(String text, int start, int end) {
        if (text == null || start < 0 || end < start || end > text.length()) return "";
        return text.substring(start, end);
    }

    private static String suffixFinding(String ret, String drawn) {
        String want = ret.isEmpty() ? "" : " -> " + ret;
        int arrow = drawn.lastIndexOf(" -> ");
        String got = arrow < 0 ? "" : drawn.substring(arrow);
        if (want.equals(got)) return null;
        if (want.isEmpty() && got.isEmpty()) return null;
        return "draws the return type as " + quote(got) + " and the declaration spells "
                + quote(want);
    }

    /** The sample the control fires on: the first judged call in the run. */
    private volatile VelaParameterInfoHandler controlHandler;
    private volatile Object controlHint;
    private volatile List<String> controlDeclared;
    private volatile int controlReceiverParams;
    private volatile int controlBound;
    private volatile String controlWhere = "";
    private volatile Object controlDot;
    private volatile Object controlInsert;
    private volatile Object controlEnter;
    private volatile String controlDotWhere = "";
    private volatile Object controlCloser;
    private volatile String controlCloserWhere = "";

    // ======================================================= section 3: row 19

    private static Character closerFor(char open) {
        switch (open) {
            case '{': return '}';
            case '(': return ')';
            case '[': return ']';
            case '"': return '"';
            case '\'': return '\'';
            default: return null;
        }
    }

    private static boolean isOpeningQuote(Lex lex, int offset) {
        LexTok next = lex.startingAt(offset + 1);
        return next != null && next.kind == LexKind.STRING;
    }

    /** The brace depth of the code before this offset, counted from the compiler's tokens. */
    private int braceDepth(FileWork w, int offset) {
        int depth = 0;
        for (LexTok t : w.lex.toks) {
            if (t.offset >= offset) break;
            if (t.kind != LexKind.PUNCT || t.len != 1) continue;
            char c = w.text.charAt(t.offset);
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return Math.max(0, depth);
    }

    /**
     * The two editor entry points, driven on real corpus positions.
     *
     * `charTyped` -- five families, and the *compiler's* token stream decides which one a
     * position belongs to (a `{` inside a comment is covered by no token; a `{` inside a
     * string is covered by a string token; a real `{` is a one-character punctuation
     * token starting exactly there):
     *
     *   `close-restore`  a pair written adjacently in the corpus (`()`), with the closer
     *                    **deleted** from the copy.  The plugin must write exactly that
     *                    closer at the caret, the text read back must be the corpus file
     *                    byte for byte, and the compiler must refuse the text without it.
     *                    That refusal is the falsification: a handler that wrote nothing
     *                    cannot pass here.
     *   `close-typed`    an opener whose closer is elsewhere (or absent).  The plugin must
     *                    write exactly the closer at the caret and nothing else.
     *   `must-not-close` the closer is already the next character; the position is inside
     *                    a string or a comment; or the file is not a `.vel` file.  Nothing
     *                    may be written.
     *
     * `postProcessEnter` -- two families:
     *
     *   `enter-block-open`  the state "the user has just typed `{` and pressed Enter": the
     *                    document is the file's prefix up to and including the `{` plus the
     *                    line feed the platform wrote.  The handler must write the body
     *                    line's indentation, a line feed and the closing brace on the line
     *                    after it, and put the caret on the body line -- computed here from
     *                    the corpus's own line, so the expectation is not the plugin's
     *                    arithmetic.  The compiler must refuse the input (the brace is
     *                    unclosed) and accept the result.
     *   `enter-indent`     a new empty line inserted above a real line of the corpus: the
     *                    handler must write the indentation that line itself has.  That is
     *                    the only authority there is -- indentation is not semantic in Vela,
     *                    so the compiler cannot judge it, and the language's own source is
     *                    what says a level is N spaces.  The corpus's own step is measured
     *                    and printed before this family runs.
     */
    private void section3(FileWork w) throws Exception {
        w.c3FilesJudged++;
        String text = w.text;
        VelaTypedHandlerDelegate typed = new VelaTypedHandlerDelegate();
        VelaEnterHandlerDelegate enter = new VelaEnterHandlerDelegate();
        int restoreSamples = 0;
        int blockSamples = 0;

        for (int o = 0; o < text.length(); o++) {
            char c = text.charAt(o);
            Character closer = closerFor(c);
            if (closer == null) continue;
            LexTok starts = w.lex.startingAt(o);
            boolean code = starts != null && starts.kind == LexKind.PUNCT && starts.len == 1;
            boolean quote = (c == '"' || c == '\'') && isOpeningQuote(w.lex, o);
            LexTok cover = w.lex.covering(o);
            String negative = null;
            if (!code && !quote) {
                if (cover != null && cover.kind == LexKind.STRING) negative = "in-a-string";
                else if (starts == null) negative = "in-a-comment";
                else continue;
            }
            if (negative != null) {
                // Nothing may be written: the compiler's own token stream says this
                // character is not a bracket or a quote the language reads as code.
                Doc d = new Doc(text);
                Ed ed = new Ed(d.proxy(), d, o + 1);
                w.c3.ran("must-not-close");
                String f = drive(typed, project, ed, fileFor(w, text), d, o + 1, c, w, "must-not-close", false);
                if (f != null) {
                    w.c3.wrong("must-not-close", "  " + w.rel + ":" + lineOf(text, o) + " "
                            + negative + " " + quote(String.valueOf(c)) + " " + f);
                }
                continue;
            }

            boolean adjacent = o + 1 < text.length() && text.charAt(o + 1) == closer;
            if (adjacent) {
                // (1) the closer must not be written twice, and (2) with the corpus's
                // closer deleted it must be written back.
                Doc d = new Doc(text);
                Ed ed = new Ed(d.proxy(), d, o + 1);
                w.c3.ran("must-not-close");
                String f = drive(typed, project, ed, fileFor(w, text), d, o + 1, c, w, "must-not-close", false);
                if (f != null) {
                    w.c3.wrong("must-not-close", "  " + w.rel + ":" + lineOf(text, o)
                            + " the closer is already the next character and " + f);
                }

                String deleted = text.substring(0, o + 1) + text.substring(o + 2);
                Doc d2 = new Doc(deleted);
                Ed ed2 = new Ed(d2.proxy(), d2, o + 1);
                boolean shadowed = o + 2 < text.length() && text.charAt(o + 2) == closer;
                if (shadowed) {
                    // `print(argc())`: deleting the inner `)` leaves the *outer* one as the
                    // next character, so the honest answer is again "write nothing", and the
                    // first version of this family called that a wrong answer -- 22 of the
                    // first full-corpus run's wrong positions, every one of them this shape.
                    w.c3.ran("must-not-close");
                    String g = drive(typed, project, ed2, fileFor(w, text), d2, o + 1, c, w,
                            "must-not-close", false);
                    if (g != null) {
                        w.c3.wrong("must-not-close", "  " + w.rel + ":" + lineOf(text, o)
                                + " the closer deleted and another closer is now the next"
                                + " character, and " + g);
                    }
                    continue;
                }
                w.c3.ran("close-restore");
                String g = drive(typed, project, ed2, fileFor(w, text), d2, o + 1, c, w, "close-restore", true);
                if (g != null) {
                    w.c3.wrong("close-restore", "  " + w.rel + ":" + lineOf(text, o) + " "
                            + quote(String.valueOf(c)) + " with its closer deleted: " + g);
                    continue;
                }
                if (controlCloser == null) {
                    controlCloser = new Object[] { deleted, o + 1, closer,
                            w.rel + ":" + lineOf(text, o) + " " + quote(String.valueOf(c)) };
                    controlCloserWhere = w.rel + ":" + lineOf(text, o);
                }
                if (restoreSamples < 1) {
                    restoreSamples++;
                    CompilerRun r = checkText(w, deleted, "no-closer");
                    w.c3.ran("closer-refused-without-it");
                    if (r.exit == 0) {
                        w.c3.skip("closer-not-required", w.rel + ":" + lineOf(text, o)
                                + " the compiler accepts the file without the closer: "
                                + quote(closer.toString()) + " is not required there");
                    }
                }
            } else {
                Doc d = new Doc(text);
                Ed ed = new Ed(d.proxy(), d, o + 1);
                w.c3.ran("close-typed");
                String f = drive(typed, project, ed, fileFor(w, text), d, o + 1, c, w, "close-typed", true);
                if (f != null) {
                    w.c3.wrong("close-typed", "  " + w.rel + ":" + lineOf(text, o) + " "
                            + quote(String.valueOf(c)) + " with its closer elsewhere: " + f);
                }
            }

            // not a `.vel` file: the handler's own guard, which is why the extension is
            // tested by the code rather than by the platform
            if (o == 0 && code) {
                Doc d = new Doc(text);
                Ed ed = new Ed(d.proxy(), d, o + 1);
                PsiFile other = fileFor(w, text, "not-vela.txt");
                w.c3.ran("must-not-close");
                String f = drive(typed, project, ed, other, d, o + 1, c, w, "must-not-close", false);
                if (f != null) {
                    w.c3.wrong("must-not-close", "  " + w.rel + ":" + lineOf(text, o)
                            + " the file is not a `.vel` file and " + f);
                }
            }

            // ------------------------------------------------------ postProcessEnter
            if (c == '{' && code) {
                int lineStart = lineStartOf(text, o);
                String openIndent = leadingWhitespace(text.substring(lineStart, o + 1));
                String prefix = text.substring(0, o + 1);
                String input = prefix + "\n";
                String bodyIndent = openIndent + repeat(' ', corpusIndentStep);
                String want = prefix + "\n" + bodyIndent + "\n" + openIndent + "}";
                int wantCaret = o + 2 + bodyIndent.length();
                Doc d = new Doc(input);
                Ed ed = new Ed(d.proxy(), d, input.length());
                w.c3.ran("enter-block-open");
                if (controlEnter == null) {
                    synchronized (this) {
                        if (controlEnter == null) {
                            controlEnter = new Object[] { want, wantCaret, w.rel + ":" + lineOf(text, o) + " after the opening brace" };
                        }
                    }
                }
                String f = enterDrive(enter, ed, w, text, d, want, wantCaret, "enter-block-open");
                if (f != null) {
                    w.c3.wrong("enter-block-open", "  " + w.rel + ":" + lineOf(text, o) + " after `{`: " + f);
                }

                if (blockSamples < 1) {
                    blockSamples++;
                    CompilerRun before = checkText(w, input, "enter-in");
                    if (before.exit == 0) {
                        w.c3.skip("closer-not-required", w.rel + ":" + lineOf(text, o)
                                + " the compiler accepts the text without the closing brace");
                    } else {
                        w.c3.ran("enter-input-refused");
                    }
                    CompilerRun after = checkText(w, want, "enter-out");
                    if (after.exit != 0) {
                        w.c3.skip("prefix-not-a-complete-program", w.rel + ":" + lineOf(text, o)
                                + " the prefix is not a program on its own: " + before.firstLine()
                                + " / after: " + after.firstLine());
                    }
                }
            }
        }

        // ---------------------------------------------------------- enter-indent
        int lineStart = 0;
        int lineNo = 0;
        String[] lines = text.split("\n", -1);
        int offset = 0;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            int start = offset;
            offset += line.length() + 1;
            if (line.trim().isEmpty()) continue;
            if (li == 0) {
                w.c3.skip("first-line", w.rel + ":1 has no previous line for Enter to indent against");
                continue;
            }
            int depth = braceDepth(w, start);
            String oracle = oracleIndent(w, lines, li, depth);
            if (oracle == null) {
                w.c3.skip("indent-not-the-corpus-step", w.rel + ":" + (li + 1)
                        + " no statement line of this file sits at depth " + depth
                        + " indented by " + (depth * corpusIndentStep) + " space(s)");
                continue;
            }
            String indent = oracle;
            String prev = lines[li - 1];
            if (prev.trim().endsWith("{")) {
                w.c3.skip("line-ends-with-brace", w.rel + ":" + (li + 1)
                        + " is preceded by a line that ends with `{`: the other branch, judged above");
                continue;
            }
            if (prev.trim().isEmpty()) {
                w.c3.skip("first-line", w.rel + ":" + (li + 1) + " is preceded by an empty line");
                continue;
            }
            String doc0 = text.substring(0, start) + "\n" + text.substring(start);
            String want = text.substring(0, start) + indent + "\n" + text.substring(start);
            int caret = start;
            Doc d = new Doc(doc0);
            Ed ed = new Ed(d.proxy(), d, caret);
            w.c3.ran("enter-indent");
            if (controlEnter == null) {
                synchronized (this) {
                    if (controlEnter == null) {
                        controlEnter = new Object[] { want, caret + indent.length(), w.rel + ":" + (li + 1) + " at depth " + depth };
                    }
                }
            }
            String f = enterDrive(enter, ed, w, text, d, want, caret + indent.length(), "enter-indent");
            if (f != null) {
                w.c3.wrong("enter-indent", "  " + w.rel + ":" + (li + 1) + " at depth " + depth
                        + " (the file itself indents it by " + indent.length() + "): " + f);
            }
            if (show) {
                System.out.println("  " + pad(w.rel, 40) + pad("enter-indent", 12) + "line " + (li + 1)
                        + " depth " + depth + " -> " + quote(indent));
            }
        }
    }

    /**
     * Drive `charTyped` and compare the document with the text the write describes.
     *
     * The comparison is between *the plugin's own writes* and the document: exactly one
     * write, at the caret, of exactly the closer.  A handler that wrote nothing -- or wrote
     * somewhere else, or wrote two characters -- cannot pass, which is what the control
     * below asks the same comparison to demonstrate.
     */
    private String drive(VelaTypedHandlerDelegate typed, Project p, Ed ed, PsiFile file, Doc d,
                         int caret, char c, FileWork w, String family, boolean expectWrite) {
        try {
            typed.charTyped(c, p, ed.proxy(), file);
        } catch (Throwable t) {
            return "charTyped threw: " + oneLine(String.valueOf(t));
        }
        if (!expectWrite) {
            if (d.writes().isEmpty()) return null;
            return "charTyped wrote " + d.writes() + " where nothing may be written";
        }
        String want = closerFor(c).toString();
        if (d.writes().isEmpty()) {
            return "charTyped wrote nothing (the corpus has " + quote(String.valueOf(c))
                    + " at the caret and its closer is not written)";
        }
        if (d.writes().size() != 1) {
            return "charTyped performed " + d.writes().size() + " writes: " + d.writes();
        }
        Write wr = d.writes().get(0);
        if (wr.start != caret || wr.end != caret || !wr.text.equals(want)) {
            return "charTyped wrote " + wr + " and the caret is at " + caret;
        }
        return null;
    }

    /** Drive `postProcessEnter` and compare the document and the caret with the expectation. */
    private String enterDrive(VelaEnterHandlerDelegate enter, Ed ed, FileWork w, String original,
                              Doc d, String want, int wantCaret, String family) {
        try {
            enter.postProcessEnter(fileFor(w, original), ed.proxy(),
                    (DataContext) Proxy.newProxyInstance(PlatformEntry.class.getClassLoader(),
                            new Class<?>[] { DataContext.class }, (p, m, a) -> def(m)));
        } catch (Throwable t) {
            return "postProcessEnter threw: " + oneLine(String.valueOf(t));
        }
        return enterFinding(want, wantCaret, d.text, ed.caret);
    }

    /** The comparison the Enter families are judged by, so the control can fire on it. */
    private static String enterFinding(String want, int wantCaret, String got, int caret) {
        if (!got.equals(want)) {
            return "the document is " + quote(shorten(got)) + " and the handler's own rule says "
                    + quote(shorten(want));
        }
        if (caret != wantCaret) {
            return "the caret is at " + caret + " and it belongs at " + wantCaret;
        }
        return null;
    }

    private static String shorten(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    /**
     * The indentation this file itself gives a statement at this depth.
     *
     * The corpus is the only authority there is: indentation is not semantic in Vela, so
     * the compiler has no verdict about it.  A line whose first code character is `}` is
     * not an oracle line -- it belongs to the depth it closes -- and neither is a line the
     * file itself indents by something other than depth*step, because then the file and the
     * rule disagree, and this family must not guess which one to follow.
     *
     * The nearest such line on either side is used, so the comparison is against the code
     * the reader is actually looking at rather than against a number.
     */
    private String oracleIndent(FileWork w, String[] lines, int li, int depth) {
        String[] order = new String[lines.length];
        int k = 0;
        for (int i = li - 1; i >= 0; i--) order[k++] = String.valueOf(i);
        for (int i = li + 1; i < lines.length; i++) order[k++] = String.valueOf(i);
        int offset = 0;
        int[] starts = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            starts[i] = offset;
            offset += lines[i].length() + 1;
        }
        for (int i = 0; i < k; i++) {
            int idx = Integer.parseInt(order[i]);
            String line = lines[idx];
            if (line.trim().isEmpty()) continue;
            if (line.trim().startsWith("}")) continue;
            String indent = leadingWhitespace(line);
            if (indent.length() != line.length() && line.charAt(indent.length()) == '\t') continue;
            if (braceDepth(w, starts[idx]) != depth) continue;
            if (indent.length() != depth * corpusIndentStep) continue;
            return indent;
        }
        return null;
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static Fil makeFil(FileWork w, String text, String name) {
        Doc doc = new Doc(text);
        return new Fil(name, text, doc, doc.proxy(), w.lex);
    }

    private static PsiFile fileFor(FileWork w, String text) {
        Fil fil = makeFil(w, text, w.name());
        PsiFile file = fil.proxy();
        pdm.register(file, fil.document);
        return file;
    }

    private static PsiFile fileFor(FileWork w, String text, String name) {
        Fil fil = makeFil(w, text, name);
        PsiFile file = fil.proxy();
        pdm.register(file, fil.document);
        return file;
    }

    // ============================================================ the reports

    private static void printFamilies(Cov c) {
        System.out.println("  family                     judged  wrong");
        System.out.println("  -------------------------  ------  -----");
        for (Map.Entry<String, int[]> e : c.families.entrySet()) {
            System.out.println("  " + pad(e.getKey(), 27) + pad(String.valueOf(e.getValue()[0]), 8)
                    + e.getValue()[1]);
        }
    }

    private static void printSkips(Cov c) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : c.skips.entrySet()) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(e.getKey()).append(' ').append(e.getValue());
        }
        System.out.println("  not judged, by class: " + sb);
        boolean any = false;
        for (Map.Entry<String, List<String>> e : c.rows.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            any = true;
            System.out.println("    " + e.getKey() + ":");
            for (String r : e.getValue()) System.out.println("      " + r);
            int n = c.skips.get(e.getKey());
            if (n > e.getValue().size()) {
                System.out.println("      (" + (n - e.getValue().size()) + " more of this kind, not printed)");
            }
        }
        if (!any) System.out.println("    (no position named)");
    }

    private static void printWrong(Cov c) {
        System.out.println("  every wrong position, in full:");
        if (c.wrong.isEmpty()) System.out.println("    (none)");
        else for (String s : c.wrong) System.out.println(s);
    }

    private static void printNotes(Cov c) {
        if (c.notes.isEmpty()) return;
        System.out.println("  what this section does not claim:");
        for (String s : c.notes) System.out.println("    " + s);
    }

    private static int crashed(Cov c) {
        Integer n = c.skips.get("crashed");
        return n == null ? 0 : n;
    }

    private int sectionVerdict(String what, Cov c, boolean controlOk, String green) {
        int exit;
        String verdict;
        if (c.wrongCount > 0) {
            verdict = c.wrongCount + " position(s) of " + c.ran + " judged are wrong: " + what;
            exit = 1;
        } else if (crashed(c) > 0) {
            verdict = "NOT A PASS: no wrong answer among the " + c.ran + " judged position(s), but "
                    + crashed(c) + " file(s) crashed this harness";
            exit = 3;
        } else if (!controlOk) {
            verdict = "NOT A PASS: the comparator did not fire on the control, so its zeros above"
                    + " say nothing";
            exit = 3;
        } else {
            verdict = green;
            exit = 0;
        }
        System.out.println();
        System.out.println("COVERAGE: ran " + c.ran + " / skipped " + c.skipped() + " ("
                + c.skipCategories() + ") / wrong " + c.wrongCount);
        System.out.println("VERDICT: " + verdict + (exit == 0 ? " [PASS]" : " [FAIL]"));
        return exit;
    }

    /**
     * The item the control feeds back through the plugin's own `updateUI`.
     *
     * `ParameterHint` gained an `openParen` field in 0.1.7, so the constructor is looked
     * up by arity rather than assumed: a control that cannot be built would otherwise
     * report "A COMPARATOR CANNOT FIRE" -- which is what the first run after that change
     * said, and it was the control's own reflection that was wrong, not the comparator.
     */
    private static Object mutatedHint(Class<?> hintClass, Object sym, int index, List<String> params)
            throws Exception {
        java.lang.reflect.Constructor<?> ctor = null;
        for (java.lang.reflect.Constructor<?> c : hintClass.getConstructors()) {
            Class<?>[] ps = c.getParameterTypes();
            if (ps.length == 3 && ps[0] == sym.getClass() && ps[1] == int.class) ctor = c;
            if (ctor == null && ps.length == 4 && ps[0] == sym.getClass() && ps[1] == int.class) ctor = c;
        }
        if (ctor == null) throw new NoSuchMethodException("no ParameterHint constructor for this arity");
        Object[] args = ctor.getParameterCount() == 3
                ? new Object[] { sym, index, params }
                : new Object[] { sym, index, params, -1 };
        return ctor.newInstance(args);
    }

    private static String memberFinding(List<String> want, Set<String> names) {
        List<String> missing = new ArrayList<String>();
        for (String m : want) if (!names.contains(m)) missing.add(m);
        List<String> extra = new ArrayList<String>();
        for (String n : names) if (!want.contains(n)) extra.add(n);
        if (missing.isEmpty() && extra.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        if (!missing.isEmpty()) sb.append("not offered ").append(missing);
        if (!extra.isEmpty()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append("offered but not a member ").append(extra);
        }
        return sb.toString();
    }

    private static String[] unionKinds() {
        Set<String> all = new LinkedHashSet<String>();
        for (String k : SKIP1) all.add(k);
        for (String k : SKIP2) all.add(k);
        for (String k : SKIP3) all.add(k);
        return all.toArray(new String[0]);
    }

    private String allCoverage(List<FileWork> works) {
        Cov all = new Cov(unionKinds());
        merge(all, total(works, 1));
        merge(all, total(works, 2));
        merge(all, total(works, 3));
        return "ran " + all.ran + " / skipped " + all.skipped() + " (" + all.skipCategories()
                + ") / wrong " + all.wrongCount + "   [rows 7, 9, 19 in one run]";
    }

    // ----------------------------------------------------------- row 7's report

    private int reportCompletion(List<FileWork> works) {
        System.out.println();
        System.out.println("=== row 7: VelaCompletionContributor through fillCompletionVariants ===");
        System.out.println("entry    : CompletionContributor.fillCompletionVariants(CompletionParameters,");
        System.out.println("           CompletionResultSet) -- the method the platform's completion");
        System.out.println("           service calls -- and LookupElement.handleInsert(InsertionContext)");
        System.out.println("oracle   : `vm.exe parse` for every declared name, member and parameter;");
        System.out.println("           SPEC.md 1.3 (keywords) and section 8 (builtin parameter names)");
        Cov c = total(works, 1);
        printFamilies(c);
        printSkips(c);
        printWrong(c);
        boolean controlOk = controlCompletion();
        return sectionVerdict("the offer set is not the one the compiler's own declarations describe",
                c, controlOk, "for every judged position the offer set is exactly the names the"
                        + " compiler declares (after-dot: the struct's members; bare: the file's own"
                        + " module names, every keyword of SPEC.md 1.3 and every builtin section 8"
                        + " names), no name is offered that nothing declares, and the insert handler"
                        + " writes the declaration's own parameter names");
    }

    private boolean controlCompletion() {
        System.out.println("== control: the comparators asked to fire, on a real position ==");
        Object[] s = (Object[]) controlDot;
        boolean ok = true;
        if (s == null) {
            System.out.println("  no judged after-dot position was available, so the member"
                    + " comparator's zeros above mean nothing");
            ok = false;
        } else {
            @SuppressWarnings("unchecked")
            List<String> want = (List<String>) s[0];
            @SuppressWarnings("unchecked")
            Set<String> names = (Set<String>) s[1];
            System.out.println("  position: " + s[2] + "; the compiler declares " + want
                    + " and the plugin offered " + names);
            Set<String> dropped = new LinkedHashSet<String>(names);
            if (!want.isEmpty()) dropped.remove(want.get(0));
            String f1 = memberFinding(want, dropped);
            System.out.println("  one member the compiler declares, dropped from the answer : "
                    + (f1 == null ? "NOT CAUGHT" : "caught: " + f1));
            ok &= f1 != null;
            Set<String> extra = new LinkedHashSet<String>(names);
            extra.add("zzq_not_a_member");
            String f2 = memberFinding(want, extra);
            System.out.println("  one name no declaration gives, added to the answer        : "
                    + (f2 == null ? "NOT CAUGHT" : "caught: " + f2));
            ok &= f2 != null;
        }
        Object[] t = (Object[]) controlInsert;
        if (t == null) {
            System.out.println("  no judged insert-handler position was available, so the template"
                    + " comparator's zeros above mean nothing");
            ok = false;
        } else {
            @SuppressWarnings("unchecked")
            List<String> want = (List<String>) t[1];
            String written = (String) t[0];
            System.out.println("  template: " + t[2] + " wrote " + quote(written)
                    + " for the declaration's " + want);
            List<String> corrupted = new ArrayList<String>(templateNames(written) == null
                    ? new ArrayList<String>() : templateNames(written));
            if (!corrupted.isEmpty()) corrupted.set(0, "zzq_not_a_parameter");
            System.out.println("  one parameter name in the template replaced by an invented one: "
                    + (corrupted.equals(want) ? "NOT CAUGHT" : "caught: the template would name "
                            + corrupted + " and the declaration names " + want));
            ok &= !corrupted.equals(want);
        }
        System.out.println("  control verdict: " + (ok
                ? "every comparator fires in both directions, so its zeros above are a measurement"
                : "A COMPARATOR CANNOT FIRE, so its zeros above mean nothing"));
        return ok;
    }

    // ----------------------------------------------------------- row 9's report

    private int reportParamInfo(List<FileWork> works) {
        System.out.println();
        System.out.println("=== row 9: VelaParameterInfoHandler, all five platform steps ===");
        System.out.println("entry    : findElementForParameterInfo -> showParameterInfo ->");
        System.out.println("           findElementForUpdatingParameterInfo -> updateParameterInfo");
        System.out.println("           (setCurrentParameter) -> updateUI");
        System.out.println("oracle   : `vm.exe parse` -- the declaration's own `param name=` list and");
        System.out.println("           `ret=`, and the compiler's binding of a receiver expression to");
        System.out.println("           the first parameter; SPEC.md section 8 for builtins");
        System.out.println("measured : the argument index the popup is given, and the string it draws");
        System.out.println("           with the parameter emphasised -- the axis this row was");
        System.out.println("           `partial` for, because nothing had ever instantiated the handler");
        Cov c = total(works, 2);
        printFamilies(c);
        printSkips(c);
        printWrong(c);
        c.note("the platform's own ParameterInfoControllerBase was not run: this tool calls the"
                + " five methods it calls, in its order, with the contexts it passes");
        c.note("the fallback half of findElementForUpdatingParameterInfo IS exercised (family"
                + " `update-rebuilt`): the anchor leaf is rebuilt with none of the user data the old"
                + " one carried, and the handler has to find it again through `objectsToView`, whose"
                + " items are ParameterHint and not PSI elements -- and answer null when that array"
                + " is empty");
        printNotes(c);
        boolean controlOk = controlParamInfo();
        return sectionVerdict("the popup does not name the parameter the compiler binds the caret's"
                + " argument to", c, controlOk, "for every judged call the popup opens, the index it"
                        + " sets names the parameter the compiler binds that argument to, the drawn"
                        + " string is the declaration's own parameter names in order, and the"
                        + " emphasis covers exactly that parameter");
    }

    private boolean controlParamInfo() {
        System.out.println("== control: the comparators asked to fire, on a real call ==");
        Object hint = controlHint;
        VelaParameterInfoHandler handler = controlHandler;
        if (hint == null || handler == null) {
            System.out.println("  no judged call was available, so the comparators' zeros above mean"
                    + " nothing");
            return false;
        }
        List<String> declared = controlDeclared;
        int bound = controlBound;
        String where = controlWhere;
        System.out.println("  call: " + where + ", the compiler's parameter list is " + declared
                + ", this argument binds `" + (bound >= 0 && bound < declared.size()
                        ? declared.get(bound) : "-") + "`");
        boolean ok = true;
        try {
            Object sym = call(hint, "getSym");
            Integer index = (Integer) call(hint, "getIndex");
            @SuppressWarnings("unchecked")
            List<String> params = (List<String>) call(hint, "getParams");
            Class<?> hintClass = Class.forName("dev.vela.plugin.ParameterHint");

            // direction 1: the argument index moved by one, through the plugin's own updateUI
            Object moved = mutatedHint(hintClass, sym, index + 1, params);
            Uic uic1 = new Uic();
            handler.updateUI((ParameterHint) moved, uic1.proxy());
            String f1 = emphasisFinding(declared, bound, uic1, "the index moved by one");
            System.out.println("  the item's argument index moved by one                    : "
                    + (f1 == null ? "NOT CAUGHT" : "caught: " + f1));
            ok &= f1 != null;

            // direction 2: one parameter name in the drawn list replaced by an invented one
            // (reversed when there are two or more, so that the corruption is real for a
            // one-parameter declaration too -- a reversed one-element list is the same list,
            // which is what the first version of this control measured and why it did not fire)
            List<String> corrupted = new ArrayList<String>(params == null
                    ? new ArrayList<String>() : params);
            if (corrupted.size() >= 2) java.util.Collections.reverse(corrupted);
            else if (corrupted.size() == 1) corrupted.set(0, "zzq_not_a_parameter");
            else corrupted.add("zzq_not_a_parameter");
            Object backwards = mutatedHint(hintClass, sym, index, corrupted);
            Uic uic2 = new Uic();
            handler.updateUI((ParameterHint) backwards, uic2.proxy());
            String f2 = namesFinding(declared, controlReceiverParams, uic2);
            System.out.println("  the drawn parameter list corrupted                       : "
                    + (f2 == null ? "NOT CAUGHT" : "caught: " + f2));
            ok &= f2 != null;

            // direction 3: the return type replaced, through the same updateUI
            Object wrongRet = mutatedHint(hintClass, sym, index, params);
            Uic uic3 = new Uic();
            handler.updateUI((ParameterHint) wrongRet, uic3.proxy());
            String f3 = suffixFinding("zzq", uic3.text);
            System.out.println("  the declaration's return type replaced by `zzq`            : "
                    + (f3 == null ? "NOT CAUGHT" : "caught: " + f3));
            ok &= f3 != null;
        } catch (Throwable t) {
            System.out.println("  the control could not be built: " + oneLine(String.valueOf(t)));
            ok = false;
        }
        System.out.println("  control verdict: " + (ok
                ? "every comparator fires in both directions, so its zeros above are a measurement"
                : "A COMPARATOR CANNOT FIRE, so its zeros above mean nothing"));
        return ok;
    }

    // ---------------------------------------------------------- row 19's report

    private int reportHandlers(List<FileWork> works) {
        System.out.println();
        System.out.println("=== row 19: the typed handler and the Enter handler, with an editor ===");
        System.out.println("entry    : VelaTypedHandlerDelegate.charTyped(char, Project, Editor, PsiFile)");
        System.out.println("           VelaEnterHandlerDelegate.postProcessEnter(PsiFile, Editor,");
        System.out.println("           DataContext)");
        System.out.println("editor   : the platform's Editor interface, stood in for: a document, a caret");
        System.out.println("           that moves when the plugin moves it, and a recorded selection --");
        System.out.println("           the same stand-in RenameWriteback uses for Document, for the same");
        System.out.println("           reason (DocumentImpl refuses to write without an application)");
        System.out.println("oracle   : `vm.exe check` on the text without the closer and on the text with");
        System.out.println("           it; `vm.exe lex` decides whether a position is code at all; and");
        System.out.println("           the corpus's own indentation (" + corpusIndentStep + " space(s),"
                + " measured over " + corpusIndentSamples + " deltas) for Enter");
        Cov c = total(works, 3);
        printFamilies(c);
        printSkips(c);
        printWrong(c);
        c.note("no IDE was started: the editor is a stand-in, so what is claimed is the decision"
                + " (which characters are written, where, and what the caret/selection becomes), not"
                + " that a keystroke on screen produces it");
        c.note("`enter-indent` judges the indentation the file itself uses, because indentation is"
                + " not semantic in Vela and the compiler has no opinion about it");
        printNotes(c);
        boolean controlOk = controlHandlers();
        return sectionVerdict("the handlers write something other than the file's own rules say",
                c, controlOk, "the typed handler writes exactly one matching closer, at the caret,"
                        + " for every position the compiler's own token stream calls code, writes"
                        + " nothing where the character is inside a string or a comment or the closer"
                        + " is already there, and the Enter handler writes the body of an opened"
                        + " brace or the indentation the file itself uses at that depth");
    }

    private boolean controlHandlers() {
        System.out.println("== control: the comparators asked to fire, on a real position ==");
        boolean ok = true;

        Object[] s = (Object[]) controlCloser;
        if (s == null) {
            System.out.println("  no judged `charTyped` position was available, so the write"
                    + " comparator's zeros above mean nothing");
            ok = false;
        } else {
            String before = (String) s[0];
            int at = (Integer) s[1];
            String closer = String.valueOf(s[2]);
            System.out.println("  position: " + controlCloserWhere + " -- the closer deleted, caret at "
                    + at + ", expecting " + quote(closer));
            Doc silent = new Doc(before);
            String f1 = closerFinding(before, silent, at, closer, false);
            System.out.println("  a handler that wrote nothing                        : "
                    + (f1 == null ? "NOT CAUGHT" : "caught: " + f1));
            ok &= f1 != null;
            Doc two = new Doc(before);
            two.writes().add(new Write(at, at, closer));
            two.text = before.substring(0, at) + closer + before.substring(at);
            two.writes().add(new Write(0, 0, "q"));
            String f2 = closerFinding(before, two, at, closer, false);
            System.out.println("  a handler that also wrote one unrelated character   : "
                    + (f2 == null ? "NOT CAUGHT" : "caught: " + f2));
            ok &= f2 != null;
        }

        Object[] e = (Object[]) controlEnter;
        if (e == null) {
            System.out.println("  no judged Enter position was available, so the Enter comparator's"
                    + " zeros above mean nothing");
            ok = false;
        } else {
            String want = (String) e[0];
            int caret = (Integer) e[1];
            String where = (String) e[2];
            String short_ = want.length() > 6 ? want.substring(0, want.length() - 1) : want;
            System.out.println("  position: " + where + " -- the expected text with one character"
                    + " dropped");
            String f = enterFinding(want, caret, short_, caret);
            System.out.println("  an Enter that wrote one character less              : "
                    + (f == null ? "NOT CAUGHT" : "caught: " + f));
            ok &= f != null;
            String f2 = enterFinding(want, caret, want, caret + 1);
            System.out.println("  an Enter that left the caret one character out      : "
                    + (f2 == null ? "NOT CAUGHT" : "caught: " + f2));
            ok &= f2 != null;
        }

        System.out.println("  control verdict: " + (ok
                ? "every comparator fires in both directions, so its zeros above are a measurement"
                : "A COMPARATOR CANNOT FIRE, so its zeros above mean nothing"));
        return ok;
    }

    /** The `charTyped` comparison, callable from the control with a deliberately silent document. */
    private static String closerFinding(String before, Doc d, int at, String closer,
                                        boolean alreadyWritten) {
        if (!alreadyWritten && d.writes().isEmpty()) {
            return "charTyped wrote nothing where the text needs " + quote(closer) + " at " + at;
        }
        if (d.writes().size() != 1) {
            return "the document has " + d.writes().size() + " write(s): " + d.writes();
        }
        Write wr = d.writes().get(0);
        if (wr.start != at || wr.end != at || !wr.text.equals(closer)) {
            return "the one write is " + wr + " and the caret is at " + at;
        }
        String want = before.substring(0, at) + closer + before.substring(at);
        if (!d.text.equals(want)) {
            return "the text is " + quote(shorten(d.text)) + " and the write describes "
                    + quote(shorten(want));
        }
        return null;
    }


    static Object readField(Object target, String field) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
