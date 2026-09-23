import dev.vela.plugin.VelaCall;
import dev.vela.plugin.VelaHints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parameter-name hints against an oracle that is NOT the hint's own model.
 *
 * WHY THIS EXISTS, AND WHY HintProbe COULD NOT DO IT
 *
 * The report is "a call site shows the same parameter name in front of every
 * argument" -- `s: s: s: `.  `HintProbe` compares the drawn labels against
 * `VelaTargets`' own idea of the declaration, which is the *same reader the hint
 * engine draws from*: when that reader is wrong the hint and the expectation are
 * wrong together and the run prints `wrong 0`.  So this tool derives the expected
 * name sequence from the **declaration text** -- a scanner written here, in the
 * harness, that has nothing to do with `VelaTargets` -- and from `SPEC.md`'s
 * builtin table for a name the file does not declare.
 *
 * WHAT IT JUDGES, per call site:
 *
 *   * a label that is not among the declaration's parameter names            -> WRONG
 *   * a name drawn more times than the declaration uses it                   -> WRONG
 *   * a label drawn for a call whose declaration's parameter list cannot be
 *     read (an unannotated parameter: the compiler refuses the file, and the
 *     parser records no parameter nodes for it)                              -> WRONG
 *   * a receiver call answered from a module-level declaration, or a bare call
 *     answered from a method's declaration (whose first parameter, `self`, has
 *     its argument in the receiver)                                          -> WRONG
 *
 * WHAT IT DOES NOT JUDGE, and every one of these is counted and named:
 *
 *   * a callee the file does not declare and `SPEC.md` does not document
 *     (a cross-file call, or a name that is not a name at all)
 *   * a builtin whose arity `SPEC.md` documents without parameter names
 *     (`pow`, `to_float`, `min_int`, `emit_*`): there is no name to check
 *     against, so nothing is claimed about it
 *   * a call with nothing written between the parentheses
 *
 * Usage:
 *   java -cp <plugin classes> HintTruth <repo-root> [--single <file>] [--dump]
 *                                          [--truncate <n>]
 * `--dump` prints the per-call table for every file it judges: the callee, the
 * argument texts, the labels drawn and the declaration's names, which is the raw
 * evidence for one reproduction.
 */
public final class HintTruth {

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private final List<String> corpus = new ArrayList<>();
    private Path repoRoot;
    private String single;
    private boolean dump;
    private int truncate = 0;

    public static void main(String[] args) throws Exception {
        HintTruth tool = new HintTruth();
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

    // ------------------------------------------------------------------ totals

    private long judged;
    private long wrong;
    private long missing;          // an argument the declaration names and no label was drawn for
    private final Map<String, Long> skipped = new LinkedHashMap<>();
    private final List<String> findings = new ArrayList<>();
    private final Map<String, Long> specBuiltins = new LinkedHashMap<>();

    private void run() throws IOException {
        System.out.println("labels   : VelaHints.parameterHints(text, text.length()) -- every call in the file");
        System.out.println("oracle   : the declaration's own parameter list, read from the source text by this");
        System.out.println("           harness; for a builtin, SPEC.md section 8's table.  Not VelaTargets.");
        System.out.println("compiler : the same declarations as `vm.exe parse` prints `param name=` for them,");
        System.out.println("           which is where the shape of the oracle came from.");
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

        System.out.println("== findings: a label the declaration does not give, or gives fewer times ==");
        if (findings.isEmpty()) System.out.println("  (none)");
        else for (String f : findings) System.out.println("  " + f);

        System.out.println();
        System.out.println("== what the oracle says about calls to the language's own names ==");
        System.out.println("  (the plugin's own table is not the oracle here; SPEC.md section 8 is)");
        if (specBuiltins.isEmpty()) System.out.println("  (no builtin call was judged)");
        else for (Map.Entry<String, Long> e : specBuiltins.entrySet()) {
            System.out.println("  " + pad(e.getKey(), 12) + e.getValue() + " call(s)");
        }

        System.out.println();
        System.out.println("== totals ==");
        System.out.println("  calls judged                     : " + judged);
        System.out.println("  arguments with no label drawn    : " + missing
                + " (not a defect: a label is deliberately not drawn when the");
        System.out.println("                                      argument already writes the parameter's name)");
        System.out.println("  labels the oracle does not give  : " + wrong);

        Coverage cov = new Coverage()
                .defectCategory("harness-threw")
                .category("missing-corpus-file")
                .category("file-too-large")
                .category("no-argument-call")
                .category("callee-not-declared-in-file")
                .category("builtin-arity-documented-without-names")
                .ran(judged)
                .defect("harness-threw", skipped.getOrDefault("harness-threw", 0L))
                .skipped("missing-corpus-file", skipped.getOrDefault("missing-corpus-file", 0L))
                .skipped("file-too-large", skipped.getOrDefault("file-too-large", 0L))
                .skipped("no-argument-call", skipped.getOrDefault("no-argument-call", 0L))
                .skipped("callee-not-declared-in-file", skipped.getOrDefault("callee-not-declared-in-file", 0L))
                .skipped("builtin-arity-documented-without-names",
                        skipped.getOrDefault("builtin-arity-documented-without-names", 0L))
                .wrong(wrong);
        cov.print();
        System.out.println();
        System.out.println(wrong == 0
                ? "VERDICT: every label drawn is a parameter name the declaration gives for that argument"
                : "VERDICT: " + wrong + " call(s) drew a label the declaration does not give");
        System.exit(wrong > 0 ? 1 : (cov.hasDefect() ? 3 : 0));
    }

    private void bump(String why) {
        skipped.merge(why, 1L, Long::sum);
    }

    private void finding(String text) {
        wrong++;
        if (findings.size() < 60) findings.add(text);
    }

    // ------------------------------------------------------------------ judging

    private void judge(String text, String rel, int cutAt) {
        List<Hint> hints = hints(text);
        List<Decl> decls = declarations(text);
        Map<String, List<String>> builtinSpec = specBuiltinNames();

        List<Call> calls = calls(text);
        for (Call call : calls) {
            if (call.close <= call.open + 1) {
                bump("no-argument-call");
                continue;
            }
            List<Range> args = argumentRanges(text, call);
            // The labels drawn, per argument, in argument order.
            List<String> drawn = new ArrayList<>();
            boolean anyLoose = false;
            for (Range r : args) {
                List<String> here = new ArrayList<>();
                for (Hint h : hints) {
                    if (h.offset >= call.open && h.offset < call.close) {
                        if (h.offset == start(text, r)) here.add(h.label);
                    }
                }
                for (Hint h : hints) {
                    if (h.offset >= call.open && h.offset < call.close && h.offset != start(text, r)
                            && !isArgumentStart(text, args, h.offset)) {
                        anyLoose = true;
                    }
                }
                if (here.size() > 1) {
                    finding(where(rel, cutAt) + ": call `" + call.name + "` argument " + args.indexOf(r)
                            + " carries " + here.size() + " labels: " + here
                            + "\n      call text: `" + snippet(text, call.open, call.close) + "`");
                }
                drawn.add(here.isEmpty() ? null : here.get(0));
            }

            // ---- the oracle: which declaration does this call name?
            Decl decl = pick(decls, call);
            List<String> truth;
            boolean untrustworthy;
            if (decl == null) {
                List<String> spec = builtinSpec.get(call.name);
                if (spec == null) {
                    if (call.name != null && specBuiltinNames().containsKey(call.name)) {
                        bump("builtin-arity-documented-without-names");
                    } else {
                        bump("callee-not-declared-in-file");
                    }
                    continue;
                }
                truth = call.receiver != null && !spec.isEmpty() ? spec.subList(1, spec.size()) : spec;
                untrustworthy = false;
                specBuiltins.merge(call.name, 1L, Long::sum);
            } else {
                truth = decl.params;
                untrustworthy = decl.params == null;
                if (truth != null) {
                    truth = call.receiver != null && !truth.isEmpty()
                            ? truth.subList(1, truth.size()) : truth;
                }
            }
            judged++;

            // ---- the judgement
            if (untrustworthy) {
                for (int i = 0; i < drawn.size(); i++) {
                    if (drawn.get(i) != null) {
                        finding(where(rel, cutAt) + ": call `" + call.name + "` argument " + i
                                + " is labelled `" + drawn.get(i) + "` although the declaration's"
                                + " parameter list cannot be read: `" + decl.signature + "`"
                                + "\n      call text: `" + snippet(text, call.open, call.close) + "`");
                    }
                }
            } else {
                if (decl != null && call.receiver != null && !decl.isMethod) {
                    finding(where(rel, cutAt) + ": `" + call.name + "` is called on the receiver `"
                            + call.receiver + "`, which is a method call, and the declaration the hint"
                            + " used is the module-level `" + decl.signature + "`"
                            + "\n      call text: `" + snippet(text, call.open, call.close) + "`");
                }
                if (decl != null && call.receiver == null && decl.isMethod) {
                    finding(where(rel, cutAt) + ": `" + call.name + "` is called with no receiver and"
                            + " the declaration the hint used is the method `" + decl.signature
                            + "`, whose first parameter is the receiver"
                            + "\n      call text: `" + snippet(text, call.open, call.close) + "`");
                }
                for (int i = 0; i < drawn.size(); i++) {
                    String label = drawn.get(i);
                    if (label == null) {
                        if (i < truth.size() && !(args.get(i) != null
                                && text.substring(args.get(i).from, args.get(i).to).trim().equals(truth.get(i)))) {
                            missing++;
                        }
                        continue;
                    }
                    String name = label.trim();
                    if (name.endsWith(":")) name = name.substring(0, name.length() - 1).trim();
                    int inTruth = occurrences(truth, name);
                    int inDrawn = occurrences(drawn, name);
                    if (inTruth == 0) {
                        finding(where(rel, cutAt) + ": call `" + call.name + "` argument " + i
                                + " is labelled `" + label + "`, which the declaration does not name: "
                                + truth + " (`" + (decl == null ? "SPEC.md section 8" : decl.signature) + "`)"
                                + "\n      call text: `" + snippet(text, call.open, call.close) + "`");
                    } else if (inDrawn > inTruth) {
                        finding(where(rel, cutAt) + ": call `" + call.name + "` draws `" + name
                                + "` " + inDrawn + " time(s) where the declaration names it " + inTruth
                                + ": labels " + labels(drawn) + " vs declaration " + truth
                                + "\n      call text: `" + snippet(text, call.open, call.close) + "`");
                    }
                }
            }
            if (dump) {
                System.out.println("---- " + where(rel, cutAt) + "  `"
                        + snippet(text, call.open, call.close) + "`");
                System.out.println("     callee=" + call.name
                        + (call.receiver == null ? "" : " receiver=" + call.receiver)
                        + "  judged=" + (!untrustworthy)
                        + "  declaration=" + (decl == null ? "SPEC.md section 8" : decl.signature)
                        + (decl == null ? "" : "  isMethod=" + decl.isMethod));
                System.out.println("     drawn  : " + labels(drawn));
                System.out.println("     oracle : " + (untrustworthy ? "(cannot be read)" : truth));
            }
            if (anyLoose) {
                finding(where(rel, cutAt) + ": call `" + call.name + "` has a label that is not at the"
                        + " start of any argument"
                        + "\n      call text: `" + snippet(text, call.open, call.close) + "`");
            }
        }
    }

    private static String where(String rel, int cutAt) {
        return cutAt < 0 ? rel : rel + " [cut at " + cutAt + "]";
    }

    private static List<String> labels(List<String> drawn) {
        List<String> out = new ArrayList<>();
        for (String d : drawn) out.add(d == null ? "-" : "`" + d + "`");
        return out;
    }

    private static int occurrences(List<String> names, String name) {
        int n = 0;
        for (String s : names) if (s != null && s.equals(name)) n++;
        return n;
    }

    private static boolean isArgumentStart(String text, List<Range> args, int offset) {
        for (Range r : args) if (start(text, r) == offset) return true;
        return false;
    }

    private static int start(String text, Range r) {
        int i = r.from;
        while (i < r.to && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    /** The declaration a call names: a method for a receiver call, a module-level one otherwise. */
    private static Decl pick(List<Decl> decls, Call call) {
        Decl moduleLevel = null;
        Decl method = null;
        for (Decl d : decls) {
            if (call.name == null || !d.name.equals(call.name)) continue;
            if (d.isMethod) {
                if (method == null) method = d;
            } else if (moduleLevel == null) moduleLevel = d;
        }
        if (call.receiver != null) return method != null ? method : moduleLevel;
        return moduleLevel != null ? moduleLevel : method;
    }

    // ------------------------------------------------------------------ hints

    /** One drawn label: the offset of an argument, and the text the inlay paints there. */
    private static final class Hint {
        final int offset;
        final String label;

        Hint(int offset, String label) {
            this.offset = offset;
            this.label = label;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Hint> hints(String text) {
        List<Hint> out = new ArrayList<>();
        try {
            List<Object> pairs = (List<Object>) (List<?>) VelaHints.INSTANCE.parameterHints(text, text.length());
            for (Object o : pairs) {
                // kotlin.Pair, read reflectively: this harness is plain Java with no Kotlin
                // compile-time dependency (it is compiled by the same javac as the rest).
                Integer off = (Integer) o.getClass().getMethod("getFirst").invoke(o);
                String label = (String) o.getClass().getMethod("getSecond").invoke(o);
                out.add(new Hint(off, label));
            }
        } catch (Throwable t) {
            throw new RuntimeException("parameterHints threw: " + t, t);
        }
        return out;
    }

    // ------------------------------------------------------------------ calls

    private static final class Call {
        final int open;
        final int close;
        final String name;
        final String receiver;

        Call(int open, int close, String name, String receiver) {
            this.open = open;
            this.close = close;
            this.name = name;
            this.receiver = receiver;
        }
    }

    /**
     * Every call in the text, read through the plugin's own call reader
     * ([VelaHints.callAt]) so the grouping is the one the hint engine itself uses --
     * the thing under test is where the *name* comes from, not which `(` is a call.
     */
    private static List<Call> calls(String text) {
        List<Call> out = new ArrayList<>();
        int lineStart = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = endOfString(text, i);
                continue;
            }
            if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '\n') {
                lineStart = i + 1;
                i++;
                continue;
            }
            if (c == '(') {
                int k = i;
                while (k > lineStart && isNamePart(text.charAt(k - 1))) k--;
                if (k < i && !declares(text, k)) {
                    VelaCall call = VelaHints.INSTANCE.callAt(text, i + 1);
                    if (call != null && call.getOpenParen() == i) {
                        out.add(new Call(i, matchingParen(text, i), text.substring(k, i),
                                call.getReceiver()));
                    }
                }
            }
            i++;
        }
        return out;
    }

    /** The same test `VelaHints.callAt` applies: is this name being declared? */
    private static boolean declares(String text, int nameStart) {
        int i = nameStart;
        while (i > 0 && (text.charAt(i - 1) == ' ' || text.charAt(i - 1) == '\t')) i--;
        int start = i;
        while (start > 0 && isNamePart(text.charAt(start - 1))) start--;
        if (start == i) return false;
        String word = text.substring(start, i);
        return word.equals("def") || word.equals("pure") || word.equals("extern");
    }

    private static int matchingParen(String text, int open) {
        int depth = 0;
        int i = open;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = endOfString(text, i);
                continue;
            }
            if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
            } else if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return text.length();
    }

    private static int endOfString(String text, int start) {
        char closing = text.charAt(start);
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (c == closing || c == '\n') return i + 1;
            i++;
        }
        return text.length();
    }

    private static boolean isNamePart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    // ------------------------------------------------------------------ arguments

    private static final class Range {
        final int from;
        final int to;

        Range(int from, int to) {
            this.from = from;
            this.to = to;
        }
    }

    /** The text of each argument of the call, split on the commas that belong to it. */
    private static List<Range> argumentRanges(String text, Call call) {
        List<Range> out = new ArrayList<>();
        int start = call.open + 1;
        int depth = 0;
        int i = start;
        while (i < call.close) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = endOfString(text, i);
                continue;
            }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') {
                if (depth > 0) depth--;
            } else if (c == ',' && depth == 0) {
                out.add(new Range(start, i));
                start = i + 1;
            }
            i++;
        }
        out.add(new Range(start, call.close));
        return out;
    }

    private static String snippet(String text, int from, int to) {
        String s = text.substring(Math.max(0, from), Math.min(text.length(), to + 1))
                .replace("\n", "\\n").replace("\r", "");
        return s.length() > 76 ? s.substring(0, 76) + "..." : s;
    }

    // ------------------------------------------------------------------ the oracle

    /** A declaration found in the source text, with the parameter names it writes. */
    private static final class Decl {
        final String name;
        final boolean isMethod;
        /** The parameter names, or null when the list cannot be read (no type annotation). */
        final List<String> params;
        final String signature;

        Decl(String name, boolean isMethod, List<String> params, String signature) {
            this.name = name;
            this.isMethod = isMethod;
            this.params = params;
            this.signature = signature;
        }
    }

    /**
     * Every `def` in the text, with the parameter list read from the declaration's own
     * characters -- `mut v: Array[int, 4], n: int` is two parameters, `s` alone is a
     * parameter list that cannot be read because the compiler refuses an unannotated
     * parameter and the parser therefore records no parameter node for it.
     */
    private static List<Decl> declarations(String text) {
        List<Decl> out = new ArrayList<>();
        Deque<Boolean> scopes = new ArrayDeque<>();
        boolean pendingStruct = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = endOfString(text, i);
                continue;
            }
            if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '{') {
                scopes.push(pendingStruct);
                pendingStruct = false;
                i++;
                continue;
            }
            if (c == '}') {
                if (!scopes.isEmpty()) scopes.pop();
                i++;
                continue;
            }
            if (!isNamePart(c)) {
                i++;
                continue;
            }
            int k = i;
            while (i < text.length() && isNamePart(text.charAt(i))) i++;
            String word = text.substring(k, i);
            if (word.equals("struct")) {
                int j = i;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                int n = j;
                while (n < text.length() && isNamePart(text.charAt(n))) n++;
                if (n > j) pendingStruct = true;
                continue;
            }
            if (!word.equals("def")) continue;

            // `def name( ... ) `
            int j = i;
            while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
            int n = j;
            while (n < text.length() && isNamePart(text.charAt(n))) n++;
            if (n == j) continue;
            String name = text.substring(j, n);
            while (n < text.length() && Character.isWhitespace(text.charAt(n))) n++;
            String signature = word + " " + name;
            List<String> params = null;
            if (n < text.length() && text.charAt(n) == '(') {
                int close = matchingParen(text, n);
                if (close > n && close <= text.length()) {
                    signature = text.substring(j, Math.min(text.length(), close + 1))
                            .replace('\n', ' ').replaceAll(" +", " ");
                    params = parameterNames(text.substring(n + 1, close));
                }
            }
            out.add(new Decl(name, scopes.contains(Boolean.TRUE), params, signature));
            i = n;
        }
        return out;
    }

    /**
     * The names a parameter list writes, or null when it cannot be read: an entry
     * without a `:` is a parameter the compiler refuses, and one the parser records no
     * node for, so no name may be drawn from it.
     */
    private static List<String> parameterNames(String inner) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                entries.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        entries.add(current.toString());
        for (String raw : entries) {
            String entry = raw.trim();
            if (entry.isEmpty()) return null;
            if (entry.startsWith("mut ")) entry = entry.substring(4).trim();
            int colon = entry.indexOf(':');
            if (colon < 0) return null;
            String name = entry.substring(0, colon).trim();
            if (name.isEmpty()) return null;
            for (int i = 0; i < name.length(); i++) {
                if (!isNamePart(name.charAt(i))) return null;
            }
            out.add(name);
        }
        return out;
    }

    // ------------------------------------------------------------------ SPEC.md

    /**
     * The parameter names SPEC.md's builtin table writes, per builtin name.  A builtin
     * whose row documents the arity without the names (`pow`, `to_float`, `min_int`,
     * `emit_*`) is deliberately absent: there is no name to judge a label against.
     */
    private static Map<String, List<String>> specBuiltinNames() {
        if (specCache != null) return specCache;
        Map<String, List<String>> out = new LinkedHashMap<>();
        try {
            Path spec = Paths.get("").toAbsolutePath();
            // The repo root is passed in; SPEC.md sits at its top.
            List<String> lines = Files.readAllLines(spec.resolve("SPEC.md"), StandardCharsets.UTF_8);
            boolean inSection = false;
            for (String line : lines) {
                if (line.startsWith("## 8. Built-in functions")) {
                    inSection = true;
                    continue;
                }
                if (inSection && line.startsWith("## ")) break;
                if (!inSection || !line.startsWith("|")) continue;
                if (!line.contains("`")) continue;
                String first = line.split("\\|", -1)[1].trim();
                if (first.equalsIgnoreCase("builtin") || first.startsWith("---")) continue;
                for (String token : first.split(",")) {
                    token = token.replace("`", "").trim();
                    if (token.isEmpty()) continue;
                    for (String one : token.split("/")) {
                        one = one.trim();
                        if (one.isEmpty()) continue;
                        int open = one.indexOf('(');
                        if (open < 0 || !one.endsWith(")")) continue;   // arity only, no names
                        String name = one.substring(0, open).trim();
                        String inner = one.substring(open + 1, one.length() - 1).trim();
                        if (name.isEmpty()) continue;
                        List<String> params = new ArrayList<>();
                        if (!inner.isEmpty() && !inner.equals("...")) {
                            for (String p : inner.split(",")) params.add(p.trim());
                        }
                        if (inner.equals("...")) continue;   // variadic: no name to judge
                        out.put(name, params);
                    }
                }
            }
        } catch (IOException e) {
            // No SPEC.md: then nothing about a builtin is claimed, and every builtin call
            // lands in the named skip category rather than being silently judged against
            // the plugin's own table.
            out.clear();
        }
        specCache = out;
        return out;
    }

    private static Map<String, List<String>> specCache;

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
        for (String dir : new String[]{"tests", "examples", "bench", "ide-demo",
                                       "selfhost/parts"}) {
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
        for (String extra : new String[]{"selfhost/vela.vel"}) {
            if (Files.isRegularFile(repoRoot.resolve(extra))) seen.add(extra);
        }
        corpus.addAll(seen);
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private HintTruth() {
    }
}
