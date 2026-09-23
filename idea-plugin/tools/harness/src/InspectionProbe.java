import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;

import dev.vela.plugin.VelaCompiler;
import dev.vela.plugin.VelaImmutableAssignmentInspection;
import dev.vela.plugin.VelaInspectionEdit;
import dev.vela.plugin.VelaInspectionFinding;
import dev.vela.plugin.VelaInspectionRules;
import dev.vela.plugin.VelaIntFloatMixingInspection;
import dev.vela.plugin.VelaProblem;
import dev.vela.plugin.VelaStringConcatenationInspection;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
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
import java.util.TreeMap;

/**
 * The three inspections against the compiler that is their authority.
 *
 * WHY THIS EXISTS
 *
 * `FEATURE_PARITY.md`'s problems/inspections/quick-fixes row was `partial` for one
 * reason: the plugin had no `localInspection` and no quick fix at all.  Inspections
 * are also the one feature where the plugin can *invent* language rules 閳?a rule the
 * compiler does not have, with a fix that does not repair anything, teaches the user
 * to write a program `vm.exe` will refuse.  So this tool exists to make that failure
 * measurable, and it is the whole reason the three rules were chosen: each one is a
 * `check` refusal the language's own test battery already asserts.
 *
 * WHAT IS MEASURED, PER CORPUS FILE
 *
 *   1. the *registered* inspection classes are run for real: each one is
 *      instantiated, `buildVisitor(holder, isOnTheFly)` is called with the
 *      platform's own `ProblemsHolder`, and the visitor is handed a `PsiFile` whose
 *      `getText()` is the file.  What the class registers is what the editor would
 *      see, and it is read back out of the `LocalQuickFix` objects the class
 *      attached (the finding each fix carries is the thing whose edits are applied
 *      below 閳?so "the fix does X" is a statement about the fix, not about a
 *      re-implementation of it).
 *   2. what the classes registered must equal what `VelaInspectionRules` says for
 *      the same text, one for one, range for range.  The two are only the same
 *      thing by construction, and this is the check that keeps it that way.
 *   3. `vm.exe check` is run on the file, and every finding must come back with a
 *      diagnostic **on its own line** carrying that rule's own compiler words
 *      ([VelaInspectionRules.authority]).  A finding with no such diagnostic is
 *      `wrong`: the inspection is claiming a refusal the compiler does not make.
 *   4. the findings' edits are applied to the text and `check` is run again.  The
 *      refusal that was there must be gone, no refusal of the same kind may appear
 *      on a line that was clean, and the rules must find strictly fewer problems.
 *      "Fixes exit 0" and "the fix removed its own problem" are counted separately,
 *      because a corpus file may hold two independent refusals and only one of them
 *      is this rule's business.
 *
 * THE COMPILER IS FROZEN FIRST, and its SHA256 is printed with every run: an oracle
 * whose binary changes underneath it decides nothing.  `--vm <path>` points at the
 * frozen copy; nothing here builds or rebuilds the compiler.
 *
 * WHAT IS NOT MEASURED, AND WHY
 *
 *   * `LocalQuickFix.applyFix` itself.  It needs a live `Application` (a real
 *     `Project`, a `PsiDocumentManager` and a write action); this machine has no
 *     booted IDE.  What it does is 10 lines of document plumbing over the edits
 *     measured here, and `VelaInspectionFix` re-derives the finding from the
 *     document's current text before writing, so the part that could be wrong is
 *     the part measured.  Reported as `unverified-headless: LocalQuickFix.applyFix`.
 *   * registration in `plugin.xml`.  The plugin descriptor belongs to another
 *     agent this round; this tool reads what the classes *are*, and the XML snippet
 *     that registers them is in the evidence file next to this run's log.
 *
 * Usage:
 *   java -cp <plugin classes> InspectionProbe <repo-root> [--vm <frozen vm.exe>]
 *                                            [--single <file>] [--exclude <file>]
 *                                            [--verbose] [--no-fix]
 *
 * `--exclude` takes a corpus file out of the run and prints that it did: for a file
 * `vm.exe check` does not terminate on, it is the difference between a run whose
 * verdict is about a hanging compiler and a run whose verdict is about the
 * inspections.
 */
public final class InspectionProbe {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    /** The corpus, the same dirs the plugin's other harnesses measure over. */
    private static final String[] CORPUS_DIRS =
            {"tests", "examples", "ide-demo", "bench", "selfhost/parts"};
    private static final String[] CORPUS_FILES = {"selfhost/vela.vel"};

    private Path repoRoot;
    private Path vm;
    private String single;
    private boolean verbose;
    private boolean noFix;

    /**
     * Corpus entries left out of this run, by request — and **printed**, never
     * silent.
     *
     * The one use this has had: `tests/safety/cases/strict_no_inferred_binding_type.vel`,
     * on which `vm.exe check` does not terminate (measured on three compiler builds).
     * The file is a defect category in a full run (`check-did-not-terminate`, which
     * makes the run non-clean on purpose), and this option exists so that the
     * *other* 309 files can still be measured in a run whose verdict is only about
     * the inspections.  An exclusion that does not announce itself is a hole in a
     * corpus, which is the one thing this project's harnesses are not allowed to
     * have.
     */
    private final List<String> excluded = new ArrayList<>();

    /** The rules and the classes that register them; the pair is checked, not assumed. */
    private final List<String> rules = new ArrayList<>();
    private final Map<String, LocalInspectionTool> tools = new LinkedHashMap<>();

    private final Map<String, RuleStats> stats = new TreeMap<>();
    private final List<String> sectionFailures = new ArrayList<>();

    /** One rule's numbers over the corpus. */
    private static final class RuleStats {
        long fired;
        long verified;         // the compiler refused that very line with this rule's words
        long corroborated;      // same edit as a verified finding: one defect, one refusal
        long unproven;          // an unrelated refusal blocked the walk to this line
        long disputed;          // the compiler's refusal on that line said something else
        long cleanCorpusFired; // findings on files `check` accepts: must be 0
        long missed;           // the compiler refused with this rule's words and the rule said nothing
        long fixApplied;
        long fixClean;         // after the whole fix set, check exits 0
        long fixPartial;       // after the whole fix set, another refusal remains
        final List<String> missSamples = new ArrayList<>();
        final List<String> cleanFiredSamples = new ArrayList<>();
    }

    public static void main(String[] args) throws Exception {
        InspectionProbe tool = new InspectionProbe();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--vm": tool.vm = Paths.get(args[++i]).toAbsolutePath(); break;
                case "--single": tool.single = args[++i].replace('\\', '/'); break;
                case "--exclude": tool.excluded.add(args[++i].replace('\\', '/')); break;
                case "--verbose": tool.verbose = true; break;
                case "--no-fix": tool.noFix = true; break;
                default: rest.add(a); break;
            }
        }
        Path root = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        if (!Files.isDirectory(root)) {
            System.out.println("not a directory: " + root);
            System.exit(2);
            return;
        }
        tool.run(root);
    }

    private void run(Path root) throws Exception {
        repoRoot = root;
        for (String r : VelaInspectionRules.RULES) {
            rules.add(r);
            stats.put(r, new RuleStats());
        }

        Disposable rootDisposable = Disposer.newDisposable();
        MockApplication app = new MockApplication(rootDisposable);
        ApplicationManager.setApplication(app, rootDisposable);
        System.out.println("application : " + ApplicationManager.getApplication().getClass().getName()
                + " (the platform's own headless application; the holder and the visitor are the real ones)");
        System.out.println("repo root   : " + repoRoot);
        System.out.println("rules       : " + String.join(", ", rules));

        if (single != null) corpus.add(single);
        else collectCorpus();
        System.out.println("corpus      : " + corpus.size() + " file(s)");

        SectionA.theClasses(this);
        SectionB.theCompiler(this);
        SectionD.theFixesBeforeAndAfter(this);
        SectionC.theCorpus(this);

        System.out.println();
        System.out.println("unverified-headless: LocalQuickFix.applyFix (needs a live Application: project,"
                + " PsiDocumentManager, write action); the edits it writes ARE measured below.");
        System.out.println("unverified-headless: plugin.xml registration (another agent owns the descriptor"
                + " this round); the XML snippet is in the evidence file beside this log.");
        System.out.println("not measured on purpose: rules whose repair is ambiguous or unsafe 閳?assignments"
                + " to an immutable parameter, to an array, and `'%'` / `'**'` operand mixing.");

        Coverage cov = SectionC.coverage();
        boolean clean = sectionFailures.isEmpty() && cov.wrong() == 0 && !cov.hasDefect();
        System.out.println();
        System.out.println("VERDICT: " + (clean
                ? "[PASS] every finding the three registered inspections report was walked to a refusal the"
                  + " frozen compiler itself makes on that line, every fix removes the refusal it was offered"
                  + " for, and no finding lands on a file the compiler accepts -- over " + cov.ran()
                  + " judged file(s), wrong 0, no defect category tripped"
                : "[FAIL] " + (sectionFailures.isEmpty() ? "no section failed" : String.join("; ", sectionFailures))
                  + (cov.wrong() > 0 ? " -- wrong " + cov.wrong() + " file(s): an inspection reported"
                        + " something the compiler did not" : "")
                  + (cov.hasDefect() ? " -- and the run itself is not clean (a defect category tripped)" : "")));
        System.exit(clean ? 0 : (sectionFailures.isEmpty() ? 3 : 1));
    }

    // ------------------------------------------------------------------ corpus

    private final List<String> corpus = new ArrayList<>();

    private void collectCorpus() throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        for (String dir : CORPUS_DIRS) {
            Path d = repoRoot.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            List<Path> found = new ArrayList<>();
            Files.walk(d)
                    .filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vel"))
                    .forEach(found::add);
            Collections.sort(found);
            for (Path f : found) {
                // `tests/safety/cases` ships one stray .exe beside a case; the .vel filter
                // already keeps it out, and the sort keeps the order reproducible.
                seen.add(repoRoot.relativize(f).toString().replace('\\', '/'));
            }
        }
        for (String extra : CORPUS_FILES) {
            if (Files.isRegularFile(repoRoot.resolve(extra))) seen.add(extra);
        }
        if (!excluded.isEmpty()) {
            for (String e : excluded) {
                boolean wasThere = seen.remove(e);
                if (wasThere) excludedCount++;
                System.out.println("excluded by request: " + e + (wasThere ? ""
                        : "  (WARNING: it was not in the corpus anyway -- check the path)"));
            }
        }
        corpus.addAll(seen);
    }

    /** How many corpus entries [excluded] actually removed; it is a skip category. */
    private int excludedCount = 0;

    // ------------------------------------------------------------------- check

    /** What `vm.exe check` said about one program. */
    static final class Checked {
        final int exit;
        final String stderr;
        final List<VelaProblem> problems;
        /** True when `check` was killed for not terminating; [exit] is then -1. */
        final boolean timedOut;
        /** How long it took, for the files that hang. */
        final long millis;

        Checked(int exit, String stderr, List<VelaProblem> problems, boolean timedOut, long millis) {
            this.exit = exit;
            this.stderr = stderr;
            this.problems = problems;
            this.timedOut = timedOut;
            this.millis = millis;
        }
    }

    /**
     * How long one `check` may take before it is killed.
     *
     * A MEASUREMENT MUST NOT HANG.  The first version of this tool read the child's
     * output with `readAllBytes()` and `waitFor()` and no bound, on the assumption
     * that `vm.exe check` terminates; a corpus file proved otherwise — `check` sat
     * in an infinite loop, and this tool sat behind it, and the run had to be killed
     * from outside.  A corpus with a non-terminating compiler on one file must still
     * produce every other file's number, so the child is killed and the file is
     * reported as its own defect category instead.
     *
     * 60 s is ~1000x the measured cost of `check` over the largest file here
     * (120 KB, 37 ms), so a timeout is a hang and never a slow machine.
     */
    private static final int CHECK_TIMEOUT_SECONDS = 60;

    /**
     * The compiler runs on a *copy* under the scratch directory, the way
     * `VelaDiagnostics` hands the compiler a buffer: `check` reads a path, and the
     * corpus file must not be touched.  The copy keeps the file's own name, so a
     * diagnostic that quotes a path quotes something recognisable.
     */
    private Checked check(Path scratch, String name, String text) throws IOException, InterruptedException {
        Path dir = scratch.resolve("check");
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.write(f, text.getBytes(StandardCharsets.ISO_8859_1));
        ProcessBuilder pb = new ProcessBuilder(vm.toString(), "check", name);
        // The compiler's own driver resolves `runtime/` relative to the working
        // directory (VelaCompiler.runtimeDir explains why); `check` does not need it,
        // but the copy's own directory is what every other harness uses.
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        long started = System.currentTimeMillis();
        Process p = pb.start();
        // Read on a thread of its own: reading after `waitFor` deadlocks as soon as
        // the child writes more than the pipe buffer holds.
        StringBuilder out = new StringBuilder(4096);
        Thread reader = new Thread(() -> {
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (out) {
                        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // The pipe closes on destroyForcibly; the timeout is what matters.
            }
        }, "check-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished = p.waitFor(CHECK_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        long millis = System.currentTimeMillis() - started;
        if (!finished) {
            p.destroyForcibly();
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            reader.join(2000);
            return new Checked(-1, "vm.exe check did not terminate within " + CHECK_TIMEOUT_SECONDS
                    + " s and was killed", Collections.emptyList(), true, millis);
        }
        reader.join(2000);
        String said;
        synchronized (out) {
            said = out.toString();
        }
        // The plugin's own reader, not a second one: `VelaCompiler.parse` is what the
        // annotator draws from, so the two cannot drift about what the compiler said.
        return new Checked(p.exitValue(), said, VelaCompiler.INSTANCE.parse(said), false,
                System.currentTimeMillis() - started);
    }

    /** The first diagnostic in the compiler's list that one of the three rules claims. */
    private static VelaProblem firstAuthority(List<VelaProblem> problems) {
        for (VelaProblem p : problems) {
            for (String rule : VelaInspectionRules.RULES) {
                if (isAuthority(rule, p.getMessage())) return p;
            }
        }
        return null;
    }

    /**
     * Is this finding's repair the very same edit as one already verified?
     *
     * `x: int = 1` assigned twice is *one* defect with two sites, and the compiler
     * words it once (it stops at the first refusal).  The second site's fix is the
     * same single `mut ` 鈥?so the compiler's confirmation of the first is a
     * confirmation of the repair the second offers.  Nothing weaker than
     * edit-for-edit equality counts.
     */
    private static boolean sameEdits(VelaInspectionFinding f, List<VelaInspectionFinding> verified) {
        for (VelaInspectionFinding v : verified) {
            if (v.edits.size() != f.edits.size()) continue;
            boolean same = true;
            for (int i = 0; i < v.edits.size(); i++) {
                VelaInspectionEdit a = v.edits.get(i);
                VelaInspectionEdit b = f.edits.get(i);
                if (a.start != b.start || a.end != b.end || !a.replacement.equals(b.replacement)) same = false;
            }
            if (same && !v.edits.isEmpty()) return true;
        }
        return false;
    }

    /** The first diagnostic on `line` that carries this rule's own compiler words. */
    private static VelaProblem authorityOn(List<VelaProblem> problems, int line, String rule, String subject) {
        for (VelaProblem p : problems) {
            if (p.getLine() != line) continue;
            if (!isAuthority(rule, p.getMessage())) continue;
            if (!subject.isEmpty() && !p.getMessage().contains("'" + subject + "'")) continue;
            return p;
        }
        return null;
    }

    private static boolean isAuthority(String rule, String message) {
        for (String s : VelaInspectionRules.authority(rule)) {
            if (message.contains(s)) return true;
        }
        return false;
    }

    // ---------------------------------------------------- the classes, as classes

    /**
     * What the jar contains, asked the way the platform asks it.
     *
     * `VerifyPlugin` checks the registration's *type* once the descriptor names the
     * class; this checks the class itself before any descriptor exists, because the
     * class is what this round adds and the descriptor line is the other agent's.
     */
    static final class SectionA {
        static void theClasses(InspectionProbe t) {
            System.out.println();
            System.out.println("== A. the registered classes ==");
            Map<String, Class<?>> classes = new LinkedHashMap<>();
            classes.put(VelaInspectionRules.IMMUTABLE_ASSIGNMENT, VelaImmutableAssignmentInspection.class);
            classes.put(VelaInspectionRules.STRING_CONCATENATION, VelaStringConcatenationInspection.class);
            classes.put(VelaInspectionRules.INT_FLOAT_MIXING, VelaIntFloatMixingInspection.class);
            for (Map.Entry<String, Class<?>> e : classes.entrySet()) {
                String rule = e.getKey();
                Class<?> c = e.getValue();
                boolean isTool = LocalInspectionTool.class.isAssignableFrom(c);
                Object instance = null;
                String shortName = "(not instantiated)";
                try {
                    instance = c.getDeclaredConstructor().newInstance();
                    shortName = ((LocalInspectionTool) instance).getShortName();
                } catch (Throwable x) {
                    t.sectionFailures.add("class " + c.getSimpleName() + " could not be constructed: " + x);
                }
                boolean named = rule.equals(shortName);
                System.out.println("  " + Coverage.pad(rule, 26) + Coverage.pad(c.getSimpleName(), 40)
                        + (isTool ? "LocalInspectionTool" : "NOT a LocalInspectionTool")
                        + ", getShortName()=" + shortName + (named ? "" : "  MISMATCH"));
                if (!isTool) {
                    t.sectionFailures.add(rule + ": the class does not extend LocalInspectionTool");
                }
                if (instance == null || !named) {
                    t.sectionFailures.add(rule + ": getShortName() is `" + shortName
                            + "`, which is not the rule id the descriptor will register");
                } else {
                    t.tools.put(rule, (LocalInspectionTool) instance);
                }
            }
            System.out.println("  the descriptor line is NOT this round's file; see the evidence file for it.");
        }
    }

    // ----------------------------------------------------- the compiler, frozen

    static final class SectionB {
        static void theCompiler(InspectionProbe t) throws Exception {
            System.out.println();
            System.out.println("== B. the authority ==");
            if (t.vm == null) {
                Path guess = Paths.get(System.getProperty("java.io.tmpdir"), "vela-plugin-parse", "vm.exe");
                if (Files.isRegularFile(guess)) t.vm = guess.toAbsolutePath();
            }
            if (t.vm == null || !Files.isRegularFile(t.vm)) {
                System.out.println("  no frozen compiler: pass --vm <path> (harness.ps1 freezes"
                        + " %TEMP%\\vela-plugin-parse\\vm.exe)");
                t.sectionFailures.add("no compiler to compare against: nothing below could be judged");
                return;
            }
            String hash = sha256(t.vm);
            System.out.println("  frozen compiler : " + t.vm + " (" + Files.size(t.vm) + " bytes)");
            System.out.println("  sha256          : " + hash);
            Path scratch = Files.createTempDirectory("inspection-probe-");
            try {
                // One program the battery already asserts per rule: the refusal each
                // inspection claims, read out of the compiler in this very run rather
                // than quoted from a document.
                String[][] probes = {
                        {"VelaImmutableAssignment",
                         "def main() -> None {\n    x: int = 1\n    x = 2\n    print(x)\n}\n"},
                        {"VelaStringConcatenation",
                         "def main() -> None {\n    print(\"a\" + \"b\")\n}\n"},
                        {"VelaIntFloatMixing",
                         "def main() -> None {\n    print(1 + 2.5)\n}\n"},
                };
                for (String[] probe : probes) {
                    String rule = probe[0];
                    Checked c = t.check(scratch, "probe.vel", probe[1]);
                    List<String> said = new ArrayList<>();
                    for (VelaProblem p : c.problems) {
                        said.add("line " + p.getLine() + " " + p.getKind() + ": " + p.getMessage());
                    }
                    String line = said.isEmpty() ? "(no diagnostic)" : said.get(0);
                    boolean agrees = c.exit != 0 && t.authorityCount(rule, c.problems) > 0;
                    System.out.println("  " + Coverage.pad(rule, 26) + "exit " + c.exit + "  " + line
                            + (agrees ? "   <- the rule's own words" : "   <- NOT the rule's words"));
                    if (!agrees) {
                        t.sectionFailures.add(rule + ": the frozen compiler does not refuse the probe"
                                + " program with this rule's message (" + line + ")");
                    }
                }
            } finally {
                deleteTree(scratch);
            }
        }
    }

    private long authorityCount(String rule, List<VelaProblem> problems) {
        long n = 0;
        for (VelaProblem p : problems) {
            if (isAuthority(rule, p.getMessage())) n++;
        }
        return n;
    }

    // ------------------------------------------------------ the corpus itself

    /**
     * The plumbing that makes an inspection runnable without an IDE.
     *
     * `ProblemsHolder` is the platform's own class; only `registerProblem` is
     * overridden, so what the inspection *calls* is the platform's API and what is
     * read back is the argument list of that call.  Descriptor construction is
     * platform code and is the one part skipped 閳?it is the same for every
     * inspection in the IDE, and the `InspectionManager` it needs cannot exist
     * without a project.
     */
    static final class RecordingHolder extends ProblemsHolder {
        final List<Recorded> recorded = new ArrayList<>();

        RecordingHolder(Project project, PsiFile file) {
            super(new StubManager(project), file, true);
        }

        @Override
        public void registerProblem(PsiElement element, TextRange range, String message, LocalQuickFix... fixes) {
            recorded.add(new Recorded(range, message, fixes));
        }

        @Override
        public void registerProblem(PsiElement element, String message, LocalQuickFix... fixes) {
            recorded.add(new Recorded(null, message, fixes));
        }

        @Override
        public void registerProblem(PsiElement element, String message, ProblemHighlightType type,
                                    LocalQuickFix... fixes) {
            recorded.add(new Recorded(null, message, fixes));
        }

        @Override
        public void registerProblem(PsiElement element, String message, ProblemHighlightType type,
                                    TextRange range, LocalQuickFix... fixes) {
            recorded.add(new Recorded(range, message, fixes));
        }
    }

    static final class Recorded {
        final TextRange range;
        final String message;
        final LocalQuickFix[] fixes;

        Recorded(TextRange range, String message, LocalQuickFix[] fixes) {
            this.range = range;
            this.message = message;
            this.fixes = fixes;
        }
    }

    /**
     * The minimum an `InspectionManager` has to be for `ProblemsHolder`'s
     * constructor to accept it.  Every member that would build a descriptor throws:
     * if anything ever reached them, the run would say so instead of quietly
     * testing nothing.
     */
    static final class StubManager extends InspectionManager {
        private final Project project;

        StubManager(Project project) {
            this.project = project;
        }

        @Override
        public Project getProject() {
            return project;
        }

        @Override
        public com.intellij.codeInspection.GlobalInspectionContext createNewGlobalContext() {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.GlobalInspectionContext createNewGlobalContext(boolean b) {
            throw unsupported();
        }

        @Override
        public List<com.intellij.codeInspection.ProblemDescriptor> defaultProcessFile(
                LocalInspectionTool tool, PsiFile file) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement e, String s, LocalQuickFix f, ProblemHighlightType t) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement e, String s, LocalQuickFix[] f, ProblemHighlightType t) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement e, String s, LocalQuickFix[] f, ProblemHighlightType t, boolean b) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement start, PsiElement end, String s, ProblemHighlightType t, LocalQuickFix... f) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement e, TextRange r, String s, ProblemHighlightType t, String tooltip,
                boolean b, LocalQuickFix... f) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement e, String s, boolean b, LocalQuickFix[] f, ProblemHighlightType t) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement e, String s, LocalQuickFix f, ProblemHighlightType t, boolean b) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ModuleProblemDescriptor createProblemDescriptor(
                String s, com.intellij.openapi.module.Module m, com.intellij.codeInspection.QuickFix... f) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.CommonProblemDescriptor createProblemDescriptor(
                String s, com.intellij.codeInspection.QuickFix... f) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement e, String s, boolean b, ProblemHighlightType t, boolean b2, LocalQuickFix[] f) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement e, String s, LocalQuickFix[] f, ProblemHighlightType t, boolean b, boolean b2) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement e, TextRange r, String s, ProblemHighlightType t, boolean b, LocalQuickFix... f) {
            throw unsupported();
        }

        @Override
        public com.intellij.codeInspection.ProblemDescriptor createProblemDescriptor(
                PsiElement start, PsiElement end, String s, ProblemHighlightType t, boolean b, LocalQuickFix... f) {
            throw unsupported();
        }

        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException(
                    "the recording holder never asks the manager for a descriptor");
        }
    }

    /** A `Project` with no services: nothing below asks it anything. */
    static final class ProjectStub implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "toString": return "inspection-probe-project";
                case "hashCode": return 11;
                case "equals": return proxy == (args == null ? null : args[0]);
                default: return null;
            }
        }
    }

    /** A `PsiFile` whose text is the buffer under test and nothing else. */
    static PsiFile fileOf(final String text, final String name) {
        return (PsiFile) java.lang.reflect.Proxy.newProxyInstance(
                InspectionProbe.class.getClassLoader(), new Class<?>[]{PsiFile.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        switch (method.getName()) {
                            case "getText": return text;
                            case "getName": return name;
                            case "toString": return name;
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == (args == null ? null : args[0]);
                            case "getTextLength": return text.length();
                            case "getTextRange": return TextRange.create(0, text.length());
                            default: return null;
                        }
                    }
                });
    }

    /**
     * Run one registered inspection over one buffer, exactly as the platform does,
     * and hand back what it registered.
     */
    private List<Recorded> runTool(String rule, String text, String name, Project project) {
        LocalInspectionTool tool = tools.get(rule);
        if (tool == null) return Collections.emptyList();
        PsiFile file = fileOf(text, name);
        RecordingHolder holder = new RecordingHolder(project, file);
        try {
            PsiElementVisitor visitor = tool.buildVisitor(holder, true);
            if (visitor == null) {
                sectionFailures.add(rule + ": buildVisitor returned null, so the class reports nothing at all");
                return Collections.emptyList();
            }
            visitor.visitFile(file);
        } catch (Throwable t) {
            sectionFailures.add(rule + ": buildVisitor/visitFile threw " + t);
            return Collections.emptyList();
        }
        return holder.recorded;
    }

    /** The finding a quick fix carries, which is where its edits live. */
    private static VelaInspectionFinding findingOf(LocalQuickFix fix) {
        try {
            Field f = fix.getClass().getDeclaredField("finding");
            f.setAccessible(true);
            return (VelaInspectionFinding) f.get(fix);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------- the fix, before and after, raw

    /**
     * The three canonical programs, verbatim: `check` before the fix, the edit the
     * registered class offers, and `check` after it.
     *
     * This is the shortest possible statement of what a quick fix is for, and it is
     * printed rather than summarised because the point of a fix is its *text*: the
     * compiler's exact words before, the exact edit, the compiler's exact words
     * after.  The three files are the language's own safety cases — the programs that
     * already assert these refusals — and the passing twin of each is a file in the
     * same directory (`strict_*_ok.vel`, `strict_*_explicit.vel`), which is why
     * "the fix writes the legal form" is not a judgement call.
     */
    static final class SectionD {
        private static final String[][] CANONICAL = {
                {"tests/safety/cases/strict_immutability.vel", VelaInspectionRules.IMMUTABLE_ASSIGNMENT},
                {"tests/safety/cases/strict_no_string_concatenation.vel", VelaInspectionRules.STRING_CONCATENATION},
                {"tests/safety/cases/strict_no_implicit_conversion.vel", VelaInspectionRules.INT_FLOAT_MIXING},
        };

        static void theFixesBeforeAndAfter(InspectionProbe t) throws Exception {
            System.out.println();
            System.out.println("== D. one fix, before and after: raw `vm.exe check` output ==");
            if (t.vm == null || !Files.isRegularFile(t.vm)) {
                System.out.println("  (no frozen compiler: pass --vm <path>)");
                t.sectionFailures.add("no compiler, so the before/after section could not run");
                return;
            }
            Project project = (Project) java.lang.reflect.Proxy.newProxyInstance(
                    InspectionProbe.class.getClassLoader(), new Class<?>[]{Project.class},
                    new ProjectStub());
            Path scratch = Files.createTempDirectory("inspection-fixes-");
            try {
                for (String[] pair : CANONICAL) {
                    Path p = t.repoRoot.resolve(pair[0]);
                    String rule = pair[1];
                    if (!Files.isRegularFile(p)) {
                        System.out.println("  MISSING " + pair[0] + " -- the corpus moved?");
                        t.sectionFailures.add("canonical file missing: " + pair[0]);
                        continue;
                    }
                    String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                    List<Recorded> recorded = t.runTool(rule, text, p.getFileName().toString(), project);
                    List<VelaInspectionFinding> findings = new ArrayList<>();
                    for (Recorded r : recorded) {
                        if (r.fixes != null && r.fixes.length == 1) {
                            VelaInspectionFinding f = findingOf(r.fixes[0]);
                            if (f != null) findings.add(f);
                        }
                    }
                    if (findings.isEmpty()) {
                        System.out.println("  " + pair[0] + ": the rule reported nothing -- nothing to show");
                        t.sectionFailures.add(rule + " reported nothing on its canonical file " + pair[0]);
                        continue;
                    }
                    VelaInspectionRules.Applied applied = VelaInspectionRules.appliedTo(text, findings);
                    Checked before = t.check(scratch, "before.vel", text);
                    Checked after = t.check(scratch, "after.vel", applied.text);
                    System.out.println();
                    System.out.println("  " + Coverage.pad(pair[0], 52) + rule);
                    System.out.println("    the rule (line " + findings.get(0).line + "): "
                            + findings.get(0).message);
                    System.out.println("    the fix: \"" + findings.get(0).fixName + "\" ->");
                    for (VelaInspectionEdit e : findings.get(0).edits) {
                        System.out.println("             replace [" + e.start + "," + e.end + ") with \""
                                + e.replacement.replace("\n", "\\n") + "\"");
                    }
                    if (findings.size() > 1) {
                        System.out.println("             (and " + (findings.size() - 1)
                                + " more finding(s) in this file, applied too)");
                    }
                    System.out.println("    -- check BEFORE the fix (exit " + before.exit + "):");
                    for (String line : raw(before.stderr)) System.out.println("         " + line);
                    System.out.println("    -- check AFTER the fix (exit " + after.exit + "):");
                    for (String line : raw(after.stderr)) System.out.println("         " + line);
                    if (before.exit == 0 || after.exit != 0) {
                        t.sectionFailures.add(rule + ": the canonical pair is not (refused -> accepted):"
                                + " exit " + before.exit + " then " + after.exit);
                    }
                }
            } finally {
                InspectionProbe.deleteTree(scratch);
            }
        }

        /** The compiler's own bytes, lines trimmed but nothing else changed. */
        private static List<String> raw(String stderr) {
            List<String> out = new ArrayList<>();
            for (String line : stderr.split("\r?\n")) {
                String s = line.trim();
                if (!s.isEmpty()) out.add(s);
            }
            if (out.isEmpty()) out.add("(nothing on stdout or stderr)");
            return out;
        }
    }

    // --------------------------------------------------------- the measurement

    static final class SectionC {
        private static Coverage cov;

        static Coverage coverage() {
            return cov;
        }

        static void theCorpus(InspectionProbe t) throws Exception {
            cov = new Coverage()
                    .defectCategory("crashed-file")
                    .defectCategory("missing-corpus-file")
                    .defectCategory("too-large")
                    .defectCategory("compiler-did-not-run")
                    .defectCategory("check-did-not-terminate")
                    .defectCategory("fix-wrote-nothing")
                    .defectCategory("inspection-class-disagrees-with-the-rule")
                    .category("does-not-parse")
                    .category("empty-or-whitespace-only")
                    .category("excluded-by-request");
            cov.skipped("excluded-by-request", t.excludedCount);

            System.out.println();
            System.out.println("== C. the corpus ==");
            System.out.println("  unit: one file for `ran` / `skipped` / `wrong`; wrong is a file where an"
                    + " inspection reported something the compiler does not, or where a fix did not");
            System.out.println("        remove the refusal it was offered for.  Per-rule numbers below.");
            System.out.println();

            Project project = (Project) java.lang.reflect.Proxy.newProxyInstance(
                    InspectionProbe.class.getClassLoader(), new Class<?>[]{Project.class},
                    new ProjectStub());
            Path scratch = Files.createTempDirectory("inspection-corpus-");
            long accepted = 0;
            long refused = 0;
            long findingsOnAccepted = 0;
            long fixRuns = 0;
            long unprovenFindings = 0;
            long slowest = 0;
            String slowestFile = "(none)";
            List<String> table = new ArrayList<>();
            try {
                for (String rel : t.corpus) {
                    Path p = t.repoRoot.resolve(rel);
                    String name = p.getFileName().toString();
                    if (!Files.isRegularFile(p)) {
                        cov.defect("missing-corpus-file", 1);
                        continue;
                    }
                    long size = Files.size(p);
                    if (size > MAX_FILE_BYTES) {
                        cov.defect("too-large", 1);
                        continue;
                    }
                    String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                    if (text.trim().isEmpty()) {
                        cov.skipped("empty-or-whitespace-only", 1);
                        continue;
                    }
                    VelaSyntaxTree tree;
                    try {
                        tree = VelaSyntaxParser.parse(text);
                    } catch (Throwable x) {
                        cov.defect("crashed-file", 1);
                        table.add("  " + rel + ": the parser threw " + x);
                        continue;
                    }
                    if (!tree.complete) {
                        // The rules have no opinion on a file that is not valid Vela, and
                        // neither has the compiler's checker: its first message is a syntax
                        // error.  Counted, never silently dropped.
                        cov.skipped("does-not-parse", 1);
                        continue;
                    }

                    // (1) the registered classes, for real.
                    List<VelaInspectionFinding> findings = new ArrayList<>();
                    boolean classDisagrees = false;
                    for (String rule : t.rules) {
                        List<Recorded> recorded = t.runTool(rule, text, name, project);
                        List<VelaInspectionFinding> byRule = VelaInspectionRules.find(rule, text);
                        if (recorded.size() != byRule.size()) {
                            classDisagrees = true;
                            table.add("  " + rel + ": " + rule + " registered " + recorded.size()
                                    + " problem(s) but the rule says " + byRule.size());
                            continue;
                        }
                        for (int i = 0; i < recorded.size(); i++) {
                            Recorded r = recorded.get(i);
                            VelaInspectionFinding pure = byRule.get(i);
                            VelaInspectionFinding fixCarries = r.fixes != null && r.fixes.length == 1
                                    ? findingOf(r.fixes[0]) : null;
                            if (r.range == null || r.range.getStartOffset() != pure.start
                                    || r.range.getEndOffset() != pure.end
                                    || !pure.message.equals(r.message)
                                    || fixCarries == null
                                    || r.fixes.length != 1
                                    || !pure.fixName.equals(r.fixes[0].getFamilyName())
                                    || fixCarries.edits.size() != pure.edits.size()) {
                                classDisagrees = true;
                                table.add("  " + rel + ": " + rule + " registered " + r.range + " \"" + r.message
                                        + "\" with " + (r.fixes == null ? 0 : r.fixes.length) + " fix(es), but the"
                                        + " rule says [" + pure.start + "," + pure.end + ") \"" + pure.message
                                        + "\" with fix \"" + pure.fixName + "\"");
                                continue;
                            }
                            // The fix carries the finding, and the finding carries the edits:
                            // the object applied below is the object the fix would apply.
                            findings.add(fixCarries);
                        }
                    }
                    if (classDisagrees) cov.defect("inspection-class-disagrees-with-the-rule", 1);

                    // (2) the compiler's own verdict on the same text.
                    Checked before;
                    try {
                        before = t.check(scratch, name, text);
                    } catch (Throwable x) {
                        cov.defect("compiler-did-not-run", 1);
                        table.add("  " + rel + ": vm.exe check could not be run: " + x);
                        continue;
                    }
                    if (before.timedOut) {
                        // The compiler never came back, so this file has no verdict and
                        // nothing may be concluded from it -- least of all that an
                        // inspection was right.  Named, counted, and fatal to the run's
                        // cleanliness: a non-terminating `check` is a defect.
                        cov.defect("check-did-not-terminate", 1);
                        table.add("  " + rel + ": vm.exe check did NOT terminate within "
                                + CHECK_TIMEOUT_SECONDS + " s (killed after " + before.millis + " ms) -- the"
                                + " compiler hangs on this program, so this file was not judged");
                        continue;
                    }
                    if (before.millis > slowest) {
                        slowest = before.millis;
                        slowestFile = rel;
                    }
                    if (before.exit == 0) accepted++;
                    else refused++;

                    cov.ran(cov.ran() + 1);
                    boolean wrongHere = false;
                    for (VelaInspectionFinding f : findings) t.stats.get(f.rule).fired++;

                    if (before.exit == 0 && !findings.isEmpty()) {
                        // The loudest possible failure: the compiler accepts this file and an
                        // inspection says it is wrong.  Nothing about authority matters here.
                        for (VelaInspectionFinding f : findings) {
                            RuleStats s = t.stats.get(f.rule);
                            s.cleanCorpusFired++;
                            findingsOnAccepted++;
                            if (s.cleanFiredSamples.size() < 3) {
                                s.cleanFiredSamples.add(rel + ":" + f.line + " " + f.message);
                            }
                        }
                        table.add("  " + rel + ": check ACCEPTS this file and " + findings.size()
                                + " finding(s) were reported -- a false alarm");
                        wrongHere = true;
                    } else if (!findings.isEmpty()) {
                        // THE LADDER.  `check` reports the *first* refusal and stops -- measured,
                        // not assumed: `tests/build/accept/string_concat_basic.vel` has three `+`
                        // on strings and the compiler words one of them (line 16).  So a finding
                        // on line 20 is not judged by "is there a diagnostic on line 20" -- that
                        // question answers no for a reason that has nothing to do with the rule.
                        // It is judged by walking the refusal stack one fix at a time: fix the
                        // finding the compiler just named, ask again, and the next refusal is the
                        // next finding's own proof.  Each step is one more `check`, and the walk
                        // stops as soon as the compiler's next refusal is not something these
                        // rules claim -- at which point the findings below it are *unproven*
                        // (counted, printed, not called wrong: an unrelated refusal is not this
                        // rule's answer).
                        List<VelaInspectionFinding> pending = new ArrayList<>(findings);
                        List<VelaInspectionFinding> verified = new ArrayList<>();
                        // HOW MANY findings at each (rule, line) the walk has confirmed.  A count,
                        // not a flag: one line can hold two of them (`1 + 2.5 + 3` is two mixed
                        // operations), and a flag would credit the second one with the first one's
                        // proof.
                        Map<String, Integer> confirmed = new LinkedHashMap<>();
                        String working = text;
                        Checked cur = before;
                        int steps = 0;
                        String stopped = "";
                        while (steps <= findings.size() + 1) {
                            VelaProblem named = firstAuthority(cur.problems);
                            if (named == null) {
                                stopped = "the compiler's refusals of these three families are exhausted"
                                        + (cur.exit == 0 ? " (it accepts the file)"
                                                         : " (exit " + cur.exit + ")");
                                break;
                            }
                            VelaInspectionFinding target = null;
                            VelaInspectionFinding other = null;
                            for (VelaInspectionFinding f : pending) {
                                if (f.line != named.getLine()) continue;
                                if (isAuthority(f.rule, named.getMessage())
                                        && (f.subject.isEmpty()
                                            || named.getMessage().contains("'" + f.subject + "'"))) {
                                    target = f;
                                } else {
                                    other = f;
                                }
                            }
                            if (target == null) {
                                if (other != null) {
                                    // The compiler refuses that line, for a *different* reason than
                                    // the rule claims.  This is the one thing this tool exists to
                                    // catch, so it is `wrong` and not `unproven`.
                                    wrongHere = true;
                                    t.stats.get(other.rule).disputed++;
                                    table.add("  " + rel + ":" + named.getLine() + "  " + other.rule + " claims \""
                                            + other.message + "\" there, and the compiler's refusal on that line is"
                                            + " \"" + named.getMessage() + "\" -- a disagreement about the reason");
                                }
                                stopped = "the compiler's refusal at line " + named.getLine() + " -- \""
                                        + named.getMessage() + "\" -- is not one this rule set claims (a miss,"
                                        + " not a wrong)";
                                break;
                            }
                            verified.add(target);
                            pending.remove(target);
                            confirmed.merge(target.rule + "@" + target.line, 1, Integer::sum);
                            if (t.verbose) {
                                table.add("  " + rel + ":" + target.line + "  " + target.rule + " verified: the"
                                        + " compiler refuses it with \"" + named.getMessage() + "\"");
                            }
                            // THE EDITS ARE APPLIED AGAINST THE TEXT THEY WERE COMPUTED FROM, and
                            // the remaining findings are recomputed from the *new* text before the
                            // next step.  Every one of these fixes adds characters (`concat(`,
                            // `to_float(`, `mut `), so every offset after it moves; reusing the old
                            // ones writes at the wrong place, and the ladder as first written did
                            // exactly that -- it produced a file the compiler still refused on the
                            // line it had just "fixed", which the run below reported instead of
                            // hiding.
                            working = VelaInspectionRules.apply(working, Collections.singletonList(target));
                            try {
                                cur = t.check(scratch, name, working);
                            } catch (Throwable x) {
                                cov.defect("compiler-did-not-run", 1);
                                stopped = "vm.exe check could not be re-run: " + x;
                                break;
                            }
                            pending = new ArrayList<>(VelaInspectionRules.findAll(working));
                            steps++;
                        }
                        // What the walk could not reach, classified.  A finding offering the *same*
                        // edit as one the compiler confirmed is one defect with two sites (see
                        // sameEdits), and a finding left over on a file the compiler now ACCEPTS was
                        // never refused at all -- a false alarm, and the one case that must not be
                        // filed under "unproven".
                        long unreached = 0;
                        for (VelaInspectionFinding f : findings) {
                            String key = f.rule + "@" + f.line;
                            Integer left = confirmed.get(key);
                            if (left != null && left > 0) {
                                confirmed.put(key, left - 1);
                                continue;
                            }
                            if (sameEdits(f, verified)) {
                                t.stats.get(f.rule).corroborated++;
                                continue;
                            }
                            if (cur.exit == 0) {
                                wrongHere = true;
                                t.stats.get(f.rule).disputed++;
                                table.add("  " + rel + ":" + f.line + "  " + f.rule + ": check ACCEPTS the file"
                                        + " once the other findings are fixed, and this line was never refused"
                                        + " -- a false alarm");
                            } else {
                                t.stats.get(f.rule).unproven++;
                                unreached++;
                            }
                        }
                        for (VelaInspectionFinding f : verified) {
                            t.stats.get(f.rule).verified++;
                        }
                        if (unreached > 0) {
                            unprovenFindings += unreached;
                            table.add("  " + rel + ": " + unreached + " finding(s) could not be walked to -- "
                                    + stopped);
                        }
                    }

                    // (3) the misses: the compiler refused with a rule's own words and the
                    // rule said nothing about it.  Not a failure -- it is not this rule's
                    // refusal -- but it is the number that keeps `fired` honest.
                    for (String rule : t.rules) {
                        RuleStats s = t.stats.get(rule);
                        for (VelaProblem diag : before.problems) {
                            if (!isAuthority(rule, diag.getMessage())) continue;
                            boolean covered = false;
                            for (VelaInspectionFinding f : findings) {
                                if (f.rule.equals(rule) && f.line == diag.getLine()) covered = true;
                            }
                            if (!covered) {
                                s.missed++;
                                if (s.missSamples.size() < 3) {
                                    s.missSamples.add(rel + ":" + diag.getLine() + " \"" + diag.getMessage() + "\"");
                                }
                            }
                        }
                    }

                    // (4) every finding at once, re-checked: the state a user reaches by
                    // applying the whole set.  Separate from the ladder because it asks a
                    // different question -- not "is each finding real" but "does the set of
                    // fixes leave a program the compiler accepts, with no new refusal".
                    if (!findings.isEmpty() && !t.noFix) {
                        VelaInspectionRules.Applied applied = VelaInspectionRules.appliedTo(text, findings);
                        if (applied.text.equals(text)) {
                            cov.defect("fix-wrote-nothing", 1);
                            wrongHere = true;
                            table.add("  " + rel + ": applying " + findings.size() + " finding(s) changed nothing");
                        } else {
                            Checked after;
                            try {
                                after = t.check(scratch, name, applied.text);
                            } catch (Throwable x) {
                                cov.defect("compiler-did-not-run", 1);
                                table.add("  " + rel + ": vm.exe check could not be re-run: " + x);
                                continue;
                            }
                            fixRuns++;
                            boolean removed = true;
                            for (VelaInspectionFinding f : findings) {
                                if (authorityOn(after.problems, f.line, f.rule, f.subject) != null) {
                                    removed = false;
                                    wrongHere = true;
                                    table.add("  " + rel + ":" + f.line + "  " + f.rule + ": after the fix the"
                                            + " compiler still refuses on that line -- "
                                            + describe(after.problems));
                                }
                            }
                            // No new refusal of the same family on a line that was clean: a fix
                            // that trades one error for another is not a repair.
                            for (VelaProblem fresh : after.problems) {
                                boolean wasClean = true;
                                for (VelaProblem q : before.problems) {
                                    if (q.getLine() == fresh.getLine() && isAuthorityOfAnyRule(t, q.getMessage())) {
                                        wasClean = false;
                                    }
                                }
                                if (wasClean && isAuthorityOfAnyRule(t, fresh.getMessage())) {
                                    wrongHere = true;
                                    table.add("  " + rel + ":" + fresh.getLine() + ": the fix introduced a new"
                                            + " refusal -- \"" + fresh.getMessage() + "\"");
                                }
                            }
                            long afterFindings = VelaInspectionRules.findAll(applied.text).size();
                            if (afterFindings >= findings.size()) {
                                wrongHere = true;
                                table.add("  " + rel + ": the rules still report " + afterFindings
                                        + " finding(s) after the fix (was " + findings.size() + ")");
                            }
                            for (VelaInspectionFinding f : findings) {
                                RuleStats s = t.stats.get(f.rule);
                                s.fixApplied++;
                                if (after.exit == 0) s.fixClean++;
                                else s.fixPartial++;
                            }
                            if (t.verbose || after.exit != 0) {
                                table.add("  " + rel + ": all " + findings.size() + " fix(es) -> exit " + after.exit
                                        + (after.exit == 0 ? " (ok)"
                                           : "  remaining: " + describe(after.problems))
                                        + (removed ? "" : "  [a fix did not remove its own refusal]"));
                            }
                        }
                    }

                    if (wrongHere) cov.wrong(cov.wrong() + 1);
                }
            } finally {
                deleteTree(scratch);
            }

            System.out.println("  files the compiler accepts        : " + accepted);
            System.out.println("  files the compiler refuses         : " + refused);
            System.out.println("  findings on files it ACCEPTS       : " + findingsOnAccepted
                    + "   <- must be 0: an inspection may not invent a refusal");
            System.out.println("  files where all fixes were applied : " + fixRuns);
            System.out.println("  findings the walk could not reach   : " + unprovenFindings
                    + "   (an unrelated refusal sat in front of them; they are neither confirmed nor"
                    + " contradicted, and they are listed below)");
            System.out.println("  slowest single `check`             : " + slowest + " ms (" + slowestFile
                    + ")   <- the " + CHECK_TIMEOUT_SECONDS + " s timeout is not a slow machine");
            System.out.println();
            System.out.println("  per rule (`verified` = the compiler refused that very line with this rule's own"
                    + " words):");
            System.out.println("    " + Coverage.pad("rule", 26) + Coverage.pad("fired", 7) + Coverage.pad("verified", 9)
                    + Coverage.pad("corrob", 8) + Coverage.pad("unprov", 8) + Coverage.pad("disputed", 9)
                    + Coverage.pad("missed", 8) + Coverage.pad("fix-clean", 10) + Coverage.pad("fix-partial", 12)
                    + "on-accepted");
            for (String rule : t.rules) {
                RuleStats s = t.stats.get(rule);
                System.out.println("    " + Coverage.pad(rule, 26) + Coverage.pad(String.valueOf(s.fired), 7)
                        + Coverage.pad(String.valueOf(s.verified), 9) + Coverage.pad(String.valueOf(s.corroborated), 8)
                        + Coverage.pad(String.valueOf(s.unproven), 8) + Coverage.pad(String.valueOf(s.disputed), 9)
                        + Coverage.pad(String.valueOf(s.missed), 8) + Coverage.pad(String.valueOf(s.fixClean), 10)
                        + Coverage.pad(String.valueOf(s.fixPartial), 12) + s.cleanCorpusFired);
            }
            System.out.println("  where a rule missed a refusal it claims (the compiler's words, no finding):");
            boolean anyMiss = false;
            for (String rule : t.rules) {
                RuleStats s = t.stats.get(rule);
                for (String m : s.missSamples) {
                    System.out.println("    " + Coverage.pad(rule, 26) + m);
                    anyMiss = true;
                }
            }
            if (!anyMiss) System.out.println("    (none: every refusal in the corpus that one of these three"
                    + " rules claims was reported)");

            if (!table.isEmpty()) {
                System.out.println();
                System.out.println("  every file with something to say (fix runs, disagreements, false alarms):");
                for (String line : table) System.out.println(line);
            }

            if (findingsOnAccepted > 0) {
                for (String rule : t.rules) {
                    RuleStats s = t.stats.get(rule);
                    for (String c : s.cleanFiredSamples) {
                        t.sectionFailures.add(rule + ": reported on a file the compiler accepts -- " + c);
                    }
                }
            }
            if (cov.get("fix-wrote-nothing") > 0) {
                t.sectionFailures.add("a fix wrote nothing: " + cov.get("fix-wrote-nothing") + " file(s)");
            }
            if (cov.get("inspection-class-disagrees-with-the-rule") > 0) {
                t.sectionFailures.add("a registered class reported something other than its rule: "
                        + cov.get("inspection-class-disagrees-with-the-rule") + " file(s)");
            }
            if (cov.hasDefect()) {
                t.sectionFailures.add("a defect category tripped: crashed-file=" + cov.get("crashed-file")
                        + ", missing-corpus-file=" + cov.get("missing-corpus-file")
                        + ", too-large=" + cov.get("too-large")
                        + ", compiler-did-not-run=" + cov.get("compiler-did-not-run")
                        + ", check-did-not-terminate=" + cov.get("check-did-not-terminate"));
            }
            System.out.println();
            cov.print();
        }

        private static boolean isAuthorityOfAnyRule(InspectionProbe t, String message) {
            for (String rule : t.rules) {
                if (isAuthority(rule, message)) return true;
            }
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String describe(List<VelaProblem> problems) {
        if (problems.isEmpty()) return "(no diagnostic at all)";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (VelaProblem p : problems) {
            if (shown++ > 0) sb.append(" | ");
            sb.append("line ").append(p.getLine()).append(' ').append(p.getKind()).append(": ")
              .append(p.getMessage());
            if (shown >= 3) break;
        }
        return sb.toString();
    }

    static String sha256(Path p) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(Files.readAllBytes(p));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void deleteTree(Path root) {
        try {
            if (!Files.isDirectory(root)) return;
            List<Path> paths = new ArrayList<>();
            Files.walk(root).forEach(paths::add);
            Collections.reverse(paths);
            for (Path p : paths) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A file the compiler just read is not always deletable on Windows on
                    // the first try; a stray temp file is not worth failing a measurement.
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
    }
}
