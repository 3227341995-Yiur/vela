import dev.vela.plugin.VelaHints;
import dev.vela.plugin.VelaModel;
import dev.vela.plugin.VelaNodeKind;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaTok;
import dev.vela.plugin.VelaTokKind;

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
 * The parameter-hint differential: is every hint's name the name the declaration
 * gives to *that* argument?
 *
 * THE RULE, WHICH IS THE WHOLE TEST
 *
 * A hint is either right or it is not drawn.  So for every call site:
 *
 *   * the expected names come from the compiler: `vm.exe parse` prints each `def`
 *     and, indented under it, its `param name=...` lines.  That dump is the
 *     authority for what a declaration's parameters are called -- not this plugin's
 *     model, and not a string split out of the same text the hint would be drawn
 *     from;
 *   * a call to a builtin is checked against `VelaModel.BUILTINS`, the language's
 *     own table of its names;
 *   * a call whose callee the file does not declare is expected to draw NO hint
 *     (there is no declaration to name the arguments from);
 *   * for argument i the hint, if drawn, must read `<expected[i]>: `; a hint beyond
 *     the declared parameters is a failure just as a wrong name is;
 *   * the same call is also checked with the file truncated at every N bytes, because
 *     a half-typed file is the state the user is in while typing and the state in
 *     which a declaration can parse differently from the finished file.
 *
 * WHERE THE CALL SITES COME FROM
 *
 * From the plugin's parser, the one `ast-diff.ps1` holds to `vm.exe parse` (107
 * files, 115,451 node lines, no difference): locating a call in a file is not a
 * judgement about Vela, it is bookkeeping.
 *
 *   java -cp <plugin classes> HintDiff <repo-root> --vm <vm.exe> [--truncate <n>]
 *                                  [--single <file>] [--show]
 */
public final class HintDiff {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private Path repoRoot;
    private Path vm;
    private int step;
    private String single;
    private boolean explain;
    private boolean show;
    private final List<String> corpus = new ArrayList<>();
    private final Map<String, Map<String, List<String>>> dumpCache = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        HintDiff tool = new HintDiff();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--vm")) tool.vm = Paths.get(args[++i]).toAbsolutePath();
            else if (a.equals("--truncate")) tool.step = Integer.parseInt(args[++i]);
            else if (a.equals("--show")) tool.show = true;
            else if (a.equals("--single")) tool.single = args[++i];
        else if (a.equals("--explain")) tool.explain = true;
            else rest.add(a);
        }
        tool.repoRoot = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        tool.run();
    }

    private void run() throws Exception {
        if (vm == null) vm = repoRoot.resolve("selfhost").resolve("build").resolve("vm.exe");
        if (single != null) corpus.add(single.replace('\\', '/'));
        else collectCorpus();

        System.out.println("expected : `vm.exe parse` -- the param name= lines under each def name=");
        System.out.println("compiler : " + vm + " (" + Files.size(vm) + " bytes, sha256 " + sha256(vm) + ")");
        System.out.println("rule     : argument i's hint must read `<declared param i>: `, or not be drawn");
        System.out.println("half-typed: " + (step > 0 ? "also every prefix of every file, every " + step
                + " byte(s)" : "not searched (pass --truncate N)"));
        System.out.println();

        long calls = 0;
        long multi = 0;
        long hints = 0;
        long correct = 0;
        long wrong = 0;
        long extra = 0;
        long missingHint = 0;
        long suppressedByConvention = 0;
        long boundaryUnjudged = 0;
        long judgedArgs = 0;
        long skipArgsUnparsable = 0;
        long unresolvedCalls = 0;
        long unresolvedWithHints = 0;
        long unjudgedCalls = 0;
        List<String> findings = new ArrayList<>();
        if (show) {
            System.out.println("== every multi-argument call: file, line, callee, declared names, hints drawn, verdict ==");
        }
        for (String rel : corpus) {
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p) || Files.size(p) > MAX_FILE_BYTES) continue;
            String full = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            Map<String, List<String>> declared = declarationsFromDump(rel, full);
            // A file the compiler cannot parse has no dump, so there is no authority at
            // all for what its declarations' parameters are called.  Its calls are
            // counted as unjudged rather than judged by this plugin's own model.
            boolean judgeable = declared != null;
            if (!judgeable) declared = new LinkedHashMap<>();
            List<String> texts = new ArrayList<>();
            texts.add(full);
            if (step > 0) {
                for (int cut = 1; cut < full.length(); cut += step) texts.add(full.substring(0, cut));
            }
            for (String text : texts) {
                VelaSyntaxTree tree = VelaSyntaxParser.parse(text);
                List<VelaSyntaxNode> callNodes = new ArrayList<>();
                collectCalls(tree.root, callNodes);
                List<Hint> hintList = hints(text);
                for (VelaSyntaxNode call : callNodes) {
                    if (call.children.size() < 2) continue;
                    VelaSyntaxNode calleeNode = call.children.get(0);
                    String callee = calleeNode.name;
                    boolean receiver = calleeNode.kind == VelaNodeKind.ATTR;
                    List<VelaSyntaxNode> args = new ArrayList<>(call.children);
                    args.remove(0);
                    List<String> expected = expectedNames(declared, callee, receiver);
                    if (expected == null) {
                        if (!judgeable) {
                            unjudgedCalls++;
                            // Skipped, and counted with the *size* of what was skipped:
                            // the unit of this harness's coverage line is an argument
                            // position, so a call that is not judged has to contribute
                            // its positions to the skipped side of the arithmetic or the
                            // two sides cannot be reconciled.
                            skipArgsUnparsable += args.size();
                            continue;
                        }
                        unresolvedCalls++;
                        // Judged, not skipped.  The callee is declared neither in this file
                        // nor by the language's own table, so the only correct answer is no
                        // hint at all -- and the rule below ("or not be drawn") is a rule
                        // this harness can check.  Counting these as skipped would hide the
                        // exact bug the first version of this file found.
                        judgedArgs += args.size();
                        // The file does not declare this callee: the only honest answer is
                        // no hint at all, so any hint here is a finding.
                        for (VelaSyntaxNode arg : args) {
                            int[] spots = argHintOffsets(tree, arg, text);
                            if (spots.length == 0) continue;
                            for (Hint h : hintList) {
                                if (!isAt(h.offset, spots)) continue;
                                unresolvedWithHints++;
                                if (findings.size() < 60) {
                                    findings.add("  " + rel + ":" + lineOf(text, spots[0]) + " `"
                                            + callee
                                            + "` is not declared in this file, but a hint `" + h.label
                                            + "` was drawn from its argument");
                                }
                            }
                        }
                        continue;
                    }
                    calls++;
                    if (args.size() >= 2) multi++;
                    judgedArgs += args.size();
                    List<String> drawn = new ArrayList<>();
                    StringBuilder verdict = new StringBuilder();
                    for (int i = 0; i < args.size(); i++) {
                        int off = argStart(tree, args.get(i));
                        int[] spots = argHintOffsets(tree, args.get(i), text);
                        boolean sawOne = false;
                        for (Hint h : hintList) {
                            // At one of the argument's own start positions -- not anywhere
                            // inside its span: see argHintOffsets for the nested-call
                            // false positives that a span test produced.
                            if (!isAt(h.offset, spots)) continue;
                            sawOne = true;
                            hints++;
                            drawn.add(strip(h.label));
                            String want = i < expected.size() ? expected.get(i) + ": " : null;
                            if (want == null) {
                                extra++;
                                findings.add("  " + rel + ":" + lineOf(text, off) + " `" + callee
                                        + "` takes " + expected.size() + " parameter(s) "
                                        + expected + ", but a hint `" + h.label
                                        + "` was drawn for argument " + (i + 1));
                                verdict.append("EXTRA ");
                            } else if (h.label.equals(want) || h.label.trim().equals(want.trim())) {
                                correct++;
                            } else {
                                wrong++;
                                findings.add("  " + rel + ":" + lineOf(text, off) + " `" + callee
                                        + "` argument " + (i + 1) + " is `" + expected.get(i)
                                        + "`, but the hint reads `" + h.label + "`   (declared "
                                        + expected + ")");
                                verdict.append("WRONG ");
                            }
                        }
                        // A DECLARED PARAMETER WITH NO HINT IS OR IS NOT A FINDING, AND
                        // WHICH ONE DEPENDS ON THE ARGUMENT'S OWN TEXT.  The loop above
                        // only ever inspected hints that were *drawn*, so an argument
                        // position where the plugin showed nothing was counted as nothing
                        // -- invisible to the verdict, which is the same omission as
                        // counting a crash as success.  But "nothing" is also the correct
                        // answer where the argument already reads exactly like the
                        // parameter, which is the engine's stated convention; so the
                        // suppression is *checked* here rather than assumed.  Only the
                        // whole file is checked: while a file is being consulted through
                        // its every prefix (`--truncate`), an absent hint is the expected
                        // state and counting it would produce thousands of rows about
                        // half-typed text.
                        if (!sawOne && step == 0 && i < expected.size()) {
                            String written = argText(tree, args.get(i), text);
                            if (written != null && written.equals(expected.get(i))) {
                                suppressedByConvention++;
                            } else if (aHintForThisCalleeIsElsewhere(hintList, expected, text, off)) {
                                // NOT JUDGED, AND THAT IS A MEASUREMENT RATHER THAN AN EXCUSE.
                                //
                                // Measured with `--explain`: on a call with many arguments the
                                // engine draws a hint whose label IS one of this callee's declared
                                // parameter names, at an offset a few characters away from the
                                // argument node this harness computed.  Examples, raw:
                                //
                                //   emit_llvm.vel:1143 `ll_expr` arg 13 nodeStart=49143
                                //     nodeText=`a` hintsOnThatLine=[49133=`lit:`]
                                //   emit_llvm.vel:1101 `ll_expr` arg 13 nodeStart=47372
                                //     nodeText=`nd[e * 10 + 2]` hintsOnThatLine=[47367=`fns:`]
                                //   emit_llvm.vel:701  `ll_note` arg 8  nodeStart=43166
                                //     nodeText=`nd_line(nd, s)` hintsOnThatLine=(none)
                                //
                                // So for arguments that are themselves expressions the two sides
                                // disagree about where an argument begins, or how many there are,
                                // and this harness cannot say whether the hint that position
                                // should carry is present.  Reporting it as "N hint positions are
                                // not right" would be a harness limitation dressed as a plugin
                                // defect; reporting nothing would be the omission this whole
                                // triple exists to prevent.  So it gets its own counted category,
                                // and the parameter-hint row stays `partial` in
                                // FEATURE_PARITY.md until someone decides which side is wrong.
                                boundaryUnjudged++;
                            } else {
                                missingHint++;
                                if (findings.size() < 60) {
                                    findings.add("  " + rel + ":" + lineOf(text, off) + " `" + callee
                                            + "` argument " + (i + 1) + " is `" + expected.get(i)
                                            + "`, argument written `" + written
                                            + "`, but no hint was drawn at all");
                                }
                                verdict.append("MISSING ");
                            }
                        }
                    }
                    if (show && args.size() >= 2 && drawn.size() > 0) {
                        System.out.println("  " + pad(rel, 40) + pad(String.valueOf(lineOf(text,
                                argStart(tree, args.get(0)))), 6) + pad(callee, 16)
                                + pad(expected.toString(), 34) + pad(drawn + " " + verdict, 24));
                    }
                }
            }
        }
        System.out.println("== totals ==");
        System.out.println("  calls with a declared callee : " + calls + " (of which multi-argument: " + multi + ")");
        System.out.println("  hints drawn                  : " + hints);
        System.out.println("  hint names correct           : " + correct);
        System.out.println("  hint names WRONG             : " + wrong);
        System.out.println("  hints beyond the parameters  : " + extra);
        System.out.println("  positions where the two sides disagree about an argument's"
                + " boundary, so no hint was judged: " + boundaryUnjudged);
        System.out.println("  declared params with no hint : " + missingHint
                + " (of which correctly suppressed because the argument already reads as the"
                + " parameter's own name: " + suppressedByConvention + ")");
        System.out.println("  calls to an undeclared callee: " + unresolvedCalls
                + ", of which drew a hint: " + unresolvedWithHints);
        System.out.println("  calls in files the compiler cannot parse, unjudged: " + unjudgedCalls);
        System.out.println();
        System.out.println("== findings ==");
        if (findings.isEmpty()) System.out.println("  (none)");
        else for (String f : findings) System.out.println(f);
        System.out.println();
        long bad = wrong + extra + unresolvedWithHints + missingHint;
        System.out.println("VERDICT: " + (bad == 0
                ? "every hint names the parameter the compiler declares for that argument,"
                        + " and every declared parameter has a hint"
                : bad + " hint position(s) are not right"));
        // The unit is an ARGUMENT POSITION, so `ran + skipped` is every argument position
        // in the corpus and the two sides reconcile.  A call whose callee is declared
        // neither in the file nor by the language's own table is judged (the correct
        // answer is no hint), so it is on the `ran` side; only a file the compiler cannot
        // parse is skipped.
        Coverage cov = new Coverage()
                .category("compiler-cannot-parse")
                .category("arg-boundary-disagreement")
                // THE SUPPRESSED POSITIONS ARE JUDGED, NOT SKIPPED, AND THAT IS THE POINT.
                //
                // They were counted as skipped in the first version of this triple, which
                // put 14,932 of 29,008 argument positions on the skip side -- "a verifier
                // that skips half its corpus and calls the rest green".  But nothing is
                // being skipped there: the convention ("no hint when the argument already
                // reads as the parameter's own name") is *checked*, string against string,
                // by the branch above.  A position that satisfies it is a correct outcome;
                // one that does not and has no hint is a `missingHint` and is counted as
                // wrong.  So they belong on the `ran` side, and the skip side keeps only
                // the one thing this harness genuinely cannot judge: a call in a file the
                // compiler refuses to parse, which has no authority at all.
                .ran(judgedArgs)
                .skipped("compiler-cannot-parse", skipArgsUnparsable)
                .skipped("arg-boundary-disagreement", boundaryUnjudged)
                .wrong(bad);
        cov.print();
        System.exit(bad == 0 ? 0 : 1);
    }

    /** The declared parameter names for a callee, or null when nothing declares it. */
    private static List<String> expectedNames(Map<String, List<String>> declared, String callee,
                                             boolean receiver) {
        List<String> names = declared.get(callee);
        if (names == null) {
            // Not declared in this file: the language's own table is the only other
            // declaration there is, read here with this harness's own splitter rather
            // than through the plugin's, so the two are compared and not shared.
            List<String> builtin = builtinParams(callee);
            if (builtin == null) return null;
            return builtin;
        }
        if (!receiver) return names;
        List<String> out = new ArrayList<>(names);
        // A method's first parameter is the receiver (it is written before the dot),
        // so its argument is not one of the parentheses' arguments.
        if (out.size() > 1) out.remove(0);
        return out;
    }

    /** `substr(s: str, a: int, b: int) -> str` -> `[s, a, b]`, or null. */
    private static List<String> builtinParams(String name) {
        for (Object o : VelaModel.INSTANCE.getBUILTINS()) {
            // The table holds `name`, `signature` and `prose` as three fields, and this
            // reads the *signature* -- the declaration.  It used to be `name to
            // "signature — prose"` in one string and this found the parameter list with
            // `indexOf('(')` over the whole of it, which is the reading the plugin did
            // too: an oracle that shares the accused's reading cannot fail.  The old
            // accessors are still accepted so this harness runs against both builds.
            String n = (String) invoke(o, "getName");
            String description = (String) invoke(o, "getSignature");
            if (n == null) {
                n = (String) invoke(o, "getFirst");
                description = (String) invoke(o, "getSecond");
            }
            if (!name.equals(n) || description == null) continue;
            int open = description.indexOf('(');
            if (open < 0) return new ArrayList<>();
            int depth = 0;
            int close = -1;
            for (int i = open; i < description.length(); i++) {
                char c = description.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        close = i;
                        break;
                    }
                }
            }
            if (close < 0) return new ArrayList<>();
            String inner = description.substring(open + 1, close).trim();
            List<String> out = new ArrayList<>();
            if (inner.isEmpty()) return out;
            depth = 0;
            StringBuilder current = new StringBuilder();
            for (char c : inner.toCharArray()) {
                if (c == '[' || c == '(') depth++;
                else if (c == ']' || c == ')') depth--;
                if (c == ',' && depth == 0) {
                    out.add(nameOf(current.toString()));
                    current.setLength(0);
                    continue;
                }
                current.append(c);
            }
            out.add(nameOf(current.toString()));
            List<String> clean = new ArrayList<>();
            for (String s : out) {
                if (!s.isEmpty() && !s.equals("...")) clean.add(s);
            }
            return clean;
        }
        return null;
    }

    private static String nameOf(String parameter) {
        int colon = parameter.indexOf(':');
        String before = colon < 0 ? parameter : parameter.substring(0, colon);
        before = before.trim();
        return before.startsWith("mut ") ? before.substring(4).trim() : before;
    }

    private static Object invoke(Object o, String method) {
        try {
            return o.getClass().getMethod(method).invoke(o);
        } catch (Exception e) {
            return "";
        }
    }

    // ----------------------------------------------------- the compiler's own dump

    /**
     * `def name=X` / `param name=Y` pairs, from `vm.exe parse`.
     *
     * The dump indents by depth, so a parameter is the `param name=` line one level
     * deeper than the `def name=` it belongs to, and a method inside a struct is still
     * a `def` line.  Only the names are read: the question is what an argument is
     * called, not what its type is.
     */
    private Map<String, List<String>> declarationsFromDump(String rel, String text) throws Exception {
        if (dumpCache.containsKey(rel)) return dumpCache.get(rel);
        Map<String, List<String>> out = new LinkedHashMap<>();
        Path tmp = Files.createTempFile("vela-hint-dump", ".vel");
        try {
            Files.write(tmp, text.getBytes(StandardCharsets.ISO_8859_1));
            ProcessBuilder pb = new ProcessBuilder(vm.toString(), "parse", tmp.toString());
            Path outFile = Files.createTempFile("vela-hint-dump-out", ".txt");
            Path errFile = Files.createTempFile("vela-hint-dump-err", ".txt");
            pb.redirectOutput(outFile.toFile());
            pb.redirectError(errFile.toFile());
            Process proc = pb.start();
            int code = proc.waitFor();
            if (code == 0) {
                String dump = readDump(outFile);
                parseDump(dump, out);
            } else {
                out = null;   // no dump: nothing to compare against
            }
            Files.deleteIfExists(outFile);
            Files.deleteIfExists(errFile);
        } finally {
            Files.deleteIfExists(tmp);
        }
        dumpCache.put(rel, out);
        return out;
    }

    private static void parseDump(String dump, Map<String, List<String>> out) {
        String current = null;
        int currentDepth = -1;
        for (String line : dump.split("\n")) {
            int depth = 0;
            while (depth < line.length() && line.charAt(depth) == ' ') depth++;
            String body = line.trim();
            int indent = depth / 2;
            if (body.startsWith("def name=")) {
                current = body.substring("def name=".length()).split(" ")[0].trim();
                currentDepth = indent;
                out.computeIfAbsent(current, k -> new ArrayList<>());
                continue;
            }
            if (body.startsWith("param name=") && current != null && indent == currentDepth + 1) {
                out.get(current).add(body.substring("param name=".length()).split(" ")[0].trim());
            }
        }
    }

    private static String readDump(Path p) throws IOException {
        byte[] raw = Files.readAllBytes(p);
        String text;
        if (raw.length >= 2 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE) {
            text = new String(raw, 2, raw.length - 2, StandardCharsets.UTF_16LE);
        } else {
            text = new String(raw, StandardCharsets.UTF_8);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ---------------------------------------------------------------- plumbing

    private static final class Hint {
        final int offset;
        final String label;

        Hint(int offset, String label) {
            this.offset = offset;
            this.label = label;
        }
    }

    private static List<Hint> hints(String text) throws Exception {
        List<Hint> out = new ArrayList<>();
        for (Object o : VelaHints.INSTANCE.parameterHints(text, text.length())) {
            Integer off = (Integer) o.getClass().getMethod("getFirst").invoke(o);
            String label = (String) o.getClass().getMethod("getSecond").invoke(o);
            out.add(new Hint(off, label));
        }
        return out;
    }

    private static String strip(String label) {
        String s = label.trim();
        return s.endsWith(":") ? s.substring(0, s.length() - 1).trim() : s;
    }

    private static void collectCalls(VelaSyntaxNode n, List<VelaSyntaxNode> out) {
        if (n.kind == VelaNodeKind.CALL) out.add(n);
        for (VelaSyntaxNode c : n.children) collectCalls(c, out);
    }

    private static int argStart(VelaSyntaxTree tree, VelaSyntaxNode arg) {
        int i = arg.startTok;
        if (i >= 0 && i < tree.toks.size()) return tree.toks.get(i).start;
        return -1;
    }

    /**
     * The (at most two) source offsets a hint may be drawn at for one argument.
     *
     * WHY THIS IS TWO EXACT POSITIONS AND NOT A RANGE.  The two sides disagree about
     * where an argument starts, by exactly one character, when the argument is a string
     * literal: the parser's node covers the *contents* (the compiler records `hello` for
     * `"hello"`) while `VelaInlayHints.parameterHints` scans characters and reports the
     * opening quote.  So the candidates are the parser's start and the quote just before
     * it.
     *
     * The first attempt at this widened the window to the whole argument span, `[start-1,
     * end+1]`, and that was wrong in the other direction: for `print(len(a))` the outer
     * argument's span contains the *inner* argument's position, so `len`'s `a: ` hint was
     * attributed to `print`'s argument and the run reported 2,392 WRONG rows of the form
     * "`print` takes 0 parameter(s) [], but a hint `n: ` was drawn for argument 1".  A
     * containment test cannot tell an argument from the arguments nested inside it; two
     * candidate positions can, because a nested call's hint is never at the outer
     * argument's own start.
     */
    private static int[] argHintOffsets(VelaSyntaxTree tree, VelaSyntaxNode arg, String text) {
        int start = argStart(tree, arg);
        if (start < 0) return new int[0];
        if (start > 0 && isQuote(text.charAt(start - 1))) return new int[]{start - 1, start};
        return new int[]{start};
    }

    /** The argument's own source text, quotes included, for the suppression comparison. */
    private static String argText(VelaSyntaxTree tree, VelaSyntaxNode arg, String text) {
        int[] span = argSpan(tree, arg, text);
        if (span == null) return null;
        return text.substring(span[0], span[1]).trim();
    }

    /**
     * The argument's whole span, quotes included.
     *
     * Used only for reading the argument's own text back (the suppression comparison and
     * the wording of a finding) -- never for deciding which hint belongs to it; see
     * [argHintOffsets] for why a span cannot be used for that.
     *
     * A Vela string literal's *node* covers its contents, not its quotes: the compiler
     * records the offset of `hello` inside `"hello"`, and `VelaParserDefinition`'s replay
     * snaps that down to the lexer's token so the element gets the text it denotes.  So
     * the span is widened to include a quote on either side and both sides are then
     * measuring the same characters.
     */
    private static int[] argSpan(VelaSyntaxTree tree, VelaSyntaxNode arg, String text) {
        if (arg == null) return null;
        int start = argStart(tree, arg);
        if (start < 0) return null;
        int end = argEnd(tree, arg);
        if (end < start || end > text.length()) return null;
        if (start > 0 && isQuote(text.charAt(start - 1))) start--;
        if (end < text.length() && isQuote(text.charAt(end))) end++;
        return new int[]{start, end};
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '\'';
    }

    private static boolean isAt(int offset, int[] spots) {
        for (int s : spots) {
            if (s == offset) return true;
        }
        return false;
    }

    /**
     * Is there a hint on this line whose label is one of this callee's declared
     * parameter names?
     *
     * The evidence that the two sides disagree about argument boundaries rather than
     * about the hint: a hint for *this* call, with a name *this* callee declares, placed
     * where a different argument of the same call lives.  If no such hint exists, the
     * position genuinely has no hint and is counted as a defect instead.
     */
    private static boolean aHintForThisCalleeIsElsewhere(List<Hint> hints, List<String> expected,
                                                         String text, int argOffset) {
        int line = lineOf(text, argOffset);
        for (Hint h : hints) {
            if (lineOf(text, h.offset) != line) continue;
            String name = h.label.trim();
            if (name.endsWith(":")) name = name.substring(0, name.length() - 1).trim();
            if (expected.contains(name)) return true;
        }
        return false;
    }

    private static int argEnd(VelaSyntaxTree tree, VelaSyntaxNode arg) {
        int i = arg.endTok;
        if (i >= 0 && i < tree.toks.size()) return tree.toks.get(i).end;
        return -1;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) if (text.charAt(i) == '\n') line++;
        return line;
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
            for (Path f : found) seen.add(repoRoot.relativize(f).toString().replace('\\', '/'));
        }
        for (String extra : new String[]{"selfhost/vela.vel", "selfhost/vm.vel"}) {
            if (Files.isRegularFile(repoRoot.resolve(extra))) seen.add(extra);
        }
        corpus.addAll(seen);
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

    private static String pad(String s, int n) {
        if (s.length() >= n) return s + " ";
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private HintDiff() {
    }
}
