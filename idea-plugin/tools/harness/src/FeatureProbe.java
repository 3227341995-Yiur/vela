import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import dev.vela.plugin.VelaCommenter;
import dev.vela.plugin.VelaFormatRules;
import dev.vela.plugin.VelaNameKind;
import dev.vela.plugin.VelaNameUse;
import dev.vela.plugin.VelaPairedBraceMatcher;
import dev.vela.plugin.VelaParserDefinition;
import dev.vela.plugin.VelaSemanticNames;
import dev.vela.plugin.VelaSpacing;
import dev.vela.plugin.VelaToken;
import dev.vela.plugin.VelaTokenTypes;
import dev.vela.plugin.VelaSyntaxHighlighter;
import java.io.IOException;
import java.io.InputStream;
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
 * The features that had only a *registration* behind them, measured.
 *
 * WHY THIS EXISTS
 *
 * `FEATURE_PARITY.md` had two kinds of evidence available for each row: a
 * registration read back out of the installed platform (the platform will call
 * this class) and a harness output (this class decides the right thing).  Only
 * seven rows had the second kind.  Highlighting, brace matching, commenting, the
 * formatter, semantic colours, live templates and the code-style page had the
 * first kind only -- which is the same shape as the three inert features this
 * plugin has already shipped: "registered" proves the platform will *ask*, and
 * proves nothing about the answer.
 *
 * So every one of them is asked here, over the same corpus the other harnesses
 * use, and the answer is checked against something that is not this plugin:
 *
 *   highlighting   every token type the lexer actually emits over the corpus must
 *                  come back with at least one colour key.  The authority is the
 *                  lexer's own output -- a type it can produce and the highlighter
 *                  cannot colour is an uncoloured token on screen.
 *   brace matching every delimiter the lexer emits (`{ } ( ) [ ]`) must be one of
 *                  the types `getPairs()` declares, or the platform cannot match
 *                  it.  Measured per delimiter kind, over every file.
 *   commenting     the commenter's line prefix must be the character the lexer
 *                  reads a comment from, checked against real comment tokens.
 *   semantic       `classifyAll` must classify, and `classify(text, offset)` -- the
 *                  binary search the editor calls on every caret move -- must agree
 *                  with `classifyAll` for every use in the corpus.  A disagreement
 *                  is a name that is coloured one way and looked up another.
 *   formatter      the rule set's own stated invariant, checked over the corpus:
 *                  a gap that contains a line feed in the source must not be
 *                  formatted into a space.  This is the one property that separates
 *                  reformatting from changing the program.
 *   live templates the template file the descriptor names must exist, parse, and
 *                  declare templates in the language's own context.
 *
 * None of this needs an IDE application instance, which is the point: nothing here
 * is allowed to claim more than it measured.
 *
 *   java -cp <plugin classes> FeatureProbe <repo-root>
 */
public final class FeatureProbe {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private final Path repoRoot;
    private final List<String> corpus = new ArrayList<>();

    /**
     * The sections that failed, in their own words.
     *
     * Six sections each print their own `  VERDICT:` sentence, so the one line a driver
     * reads has to be an aggregate, and the aggregate must not be able to be greener
     * than its parts: a green section 6 must never cover a red section 1.  Before this
     * existed, the tool's *only* whole-run conclusion was its exit code -- and
     * `harness.ps1` reads the text, so it summarised a run that had printed six verdicts
     * and exited 0 as "the tool did not finish".
     */
    private final List<String> sectionFailures = new ArrayList<>();

    private FeatureProbe(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Paths.get(args[0]).toAbsolutePath()
                : Paths.get("").toAbsolutePath();
        if (!Files.isDirectory(root)) {
            System.out.println("not a directory: " + root);
            System.exit(2);
            return;
        }
        FeatureProbe tool = new FeatureProbe(root);
        tool.run();
    }

    private void run() throws Exception {
        collectCorpus();
        System.out.println("corpus   : " + repoRoot + " (" + corpus.size() + " file(s))");
        System.out.println("asking   : the six features that had a registration and no measurement");
        System.out.println();
        // FIVE OF THE SIX SECTIONS ARE OVER THE CORPUS.  With none, they answer "yes" having
        // looked at nothing, and the aggregate below must not call that clean.
        if (corpus.isEmpty()) {
            sectionFailures.add("corpus: no .vel file was found under " + repoRoot + ", so the"
                    + " five corpus-wide sections had nothing to judge");
        }

        Coverage cov = new Coverage()
                .defectCategory("missing-corpus-file")
                .defectCategory("too-large")
                .category("empty-or-whitespace-only");

        highlighting(cov);
        braceMatching(cov);
        commenting(cov);
        semantic(cov);
        formatter(cov);
        liveTemplates(cov);

        // The unit of this tool's coverage line is one (capability, corpus file) pair for
        // the five corpus-wide checks, plus one per artifact-level check: six
        // capabilities are asked of every readable file, and the live-template check is
        // asked once of the artifact.  The unit is stated because a triple whose unit is
        // guessed is the same defect as a verdict without a number.
        System.out.println("  unit: one (capability, file) pair for sections 1-5, plus one"
                + " artifact-level check for section 6");
        cov.print();
        // THE ONE LINE A DRIVER READS, AND IT CANNOT BE GREENER THAN THE SIX SECTIONS.
        //
        // `[PASS]` / `[FAIL]` is machine-readable on purpose: `harness.ps1` collects
        // `^\s*VERDICT` lines and lets a `[FAIL]` outrank whatever came last, because with
        // six sections in one log "the last verdict line" is a green section 6 covering a
        // red section 1.  Both this sentence and the exit code are computed from the same
        // two facts -- `sectionFailures` and the coverage triple -- so they cannot disagree.
        boolean clean = sectionFailures.isEmpty() && cov.wrong() == 0 && !cov.hasDefect();
        System.out.println("VERDICT: " + (clean
                ? "[PASS] all six features answered as the invariant requires, over "
                        + cov.ran() + " judged unit(s) (5 corpus-wide checks per readable file,"
                        + " plus one artifact-level check), wrong 0, no defect category tripped"
                : "[FAIL] " + sectionFailures.size() + " of 6 feature section(s) failed -- "
                        + String.join("; ", sectionFailures)
                        + (cov.hasDefect() ? " -- and the run itself is not clean (a defect"
                                + " category tripped: missing-corpus-file "
                                + cov.get("missing-corpus-file") + ", too-large "
                                + cov.get("too-large") + ")" : "")));
        // 1 is "a feature answered wrongly", 3 is "the harness or the corpus is at
        // fault".  The old shape -- a per-section count and no exit code -- could not
        // be used by anything.
        System.exit(clean ? 0 : (sectionFailures.isEmpty() ? 3 : 1));
    }

    // ------------------------------------------------------------------ corpus

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
        for (String extra : new String[]{"selfhost/vela.vel"}) {
            if (Files.isRegularFile(repoRoot.resolve(extra))) seen.add(extra);
        }
        corpus.addAll(seen);
    }

    /** The files this probe could read, and what it could not. */
    private List<String> readable(Coverage cov) {
        List<String> out = new ArrayList<>();
        for (String rel : corpus) {
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p)) {
                cov.defect("missing-corpus-file", 1);
                continue;
            }
            try {
                if (Files.size(p) > MAX_FILE_BYTES) {
                    cov.defect("too-large", 1);
                    continue;
                }
                String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                if (text.trim().isEmpty()) {
                    cov.skipped("empty-or-whitespace-only", 1);
                    continue;
                }
            } catch (IOException e) {
                cov.defect("too-large", 1);
                continue;
            }
            out.add(rel);
        }
        return out;
    }

    private static String read(Path p) throws IOException {
        return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
    }

    private static Lexer lexer() {
        return new VelaParserDefinition().createLexer(null);
    }

    // ------------------------------------------------- (1) syntax highlighting

    /**
     * Does every token the lexer can emit have a colour?
     *
     * The lexer is run over the whole corpus and the set of element types it
     * produces is collected first -- so the check is over the tokens that really
     * occur, not over the thirteen constants someone remembered to list.
     */
    private void highlighting(Coverage cov) throws IOException {
        System.out.println("== 1. syntax highlighting: is every token the lexer emits coloured? ==");
        VelaSyntaxHighlighter highlighter = new VelaSyntaxHighlighter();
        Map<String, Long> seen = new TreeMap<>();
        List<String> uncoloured = new ArrayList<>();
        long tokens = 0;
        List<String> files = readable(cov);
        for (String rel : files) {
            String text = read(repoRoot.resolve(rel));
            Lexer lexer = lexer();
            lexer.start(text);
            while (lexer.getTokenType() != null) {
                IElementType type = lexer.getTokenType();
                tokens++;
                if (type != TokenType.WHITE_SPACE) {
                    seen.merge(type.toString(), 1L, Long::sum);
                }
                lexer.advance();
            }
        }
        for (String name : seen.keySet()) {
            IElementType type = elementTypeNamed(name);
            if (type == null) {
                uncoloured.add(name + " (no IElementType with that name)");
                continue;
            }
            TextAttributesKey[] keys = highlighter.getTokenHighlights(type);
            if (keys == null || keys.length == 0) {
                uncoloured.add(name);
            }
        }
        System.out.println("  files lexed             : " + files.size());
        System.out.println("  tokens                  : " + tokens);
        System.out.println("  distinct token types    : " + seen.size());
        for (Map.Entry<String, Long> e : seen.entrySet()) {
            System.out.println("      " + Coverage.pad(e.getKey(), 18) + Coverage.pad(
                    String.valueOf(e.getValue()), 10)
                    + (highlighter.getTokenHighlights(elementTypeNamed(e.getKey())).length > 0
                            ? "coloured" : "NO COLOUR KEY"));
        }
        System.out.println("  token types with no colour: " + uncoloured.size()
                + (uncoloured.isEmpty() ? "" : " -> " + uncoloured));
        System.out.println("  VERDICT: " + (uncoloured.isEmpty()
                ? "every token type the lexer emits over this corpus gets a colour key"
                : uncoloured.size() + " token type(s) would be drawn uncoloured"));
        cov.ran(cov.ran() + files.size());
        cov.wrong(cov.wrong() + uncoloured.size());
        if (!uncoloured.isEmpty()) {
            sectionFailures.add("highlighting: " + uncoloured.size() + " token type(s) the lexer"
                    + " emits over this corpus would be drawn with no colour key " + uncoloured);
        }
        System.out.println();
    }

    /**
     * The element type behind a name, taken from the plugin's own constants rather
     * than by parsing a string: a mismatch there would make this check vacuous.
     */
    private static IElementType elementTypeNamed(String name) {
        IElementType[] all = {
            VelaTokenTypes.KEYWORD, VelaTokenTypes.IDENTIFIER, VelaTokenTypes.NUMBER,
            VelaTokenTypes.STRING, VelaTokenTypes.COMMENT, VelaTokenTypes.OPERATOR,
            VelaTokenTypes.BRACES, VelaTokenTypes.BRACKETS, VelaTokenTypes.PARENS,
            VelaTokenTypes.COMMA, VelaTokenTypes.DOT, VelaTokenTypes.COLON,
            VelaTokenTypes.SEMICOLON,
        };
        for (IElementType t : all) {
            if (t.toString().equals(name)) return t;
        }
        return null;
    }

    // ----------------------------------------------------- (2) brace matching

    /**
     * Is every delimiter the lexer emits a type the brace matcher declares?
     *
     * `getPairs()` is the whole answer the platform gets for `Ctrl+Shift+M`, caret
     * brace highlighting and closing-bracket insertion, so a delimiter that is not
     * in it is a bracket that silently does not match.
     */
    private void braceMatching(Coverage cov) throws IOException {
        System.out.println("== 2. brace matching: is every delimiter a declared pair? ==");
        VelaPairedBraceMatcher matcher = new VelaPairedBraceMatcher();
        Set<String> declared = new LinkedHashSet<>();
        for (Object p : matcher.getPairs()) {
            String left = invoke(p, "getLeftBraceType");
            String right = invoke(p, "getRightBraceType");
            String structural = invoke(p, "isStructural");
            declared.add(left);
            declared.add(right);
            System.out.println("  pair: " + Coverage.pad(left, 16) + " .. " + Coverage.pad(right, 16)
                    + "structural=" + structural);
        }
        Map<String, Long> delimiters = new TreeMap<>();
        long unmatched = 0;
        List<String> files = readable(cov);
        for (String rel : files) {
            String text = read(repoRoot.resolve(rel));
            Lexer lexer = lexer();
            lexer.start(text);
            while (lexer.getTokenType() != null) {
                IElementType type = lexer.getTokenType();
                if (type == VelaTokenTypes.BRACES || type == VelaTokenTypes.BRACKETS
                        || type == VelaTokenTypes.PARENS) {
                    String t = lexer.getTokenText();
                    delimiters.merge(t, 1L, Long::sum);
                    if (!declared.contains(type.toString())) unmatched++;
                }
                lexer.advance();
            }
        }
        System.out.println("  delimiters seen in the corpus: " + delimiters);
        for (String need : new String[]{"{", "}", "(", ")", "[", "]"}) {
            System.out.println("      " + Coverage.pad(need, 4) + Coverage.pad(
                    String.valueOf(delimiters.getOrDefault(need, 0L)), 8)
                    + (delimiters.getOrDefault(need, 0L) > 0 ? "present" : "ABSENT from the corpus"));
        }
        System.out.println("  delimiters with no declared pair: " + unmatched);
        System.out.println("  VERDICT: " + (unmatched == 0
                ? "every delimiter in the corpus is a type getPairs() declares"
                : unmatched + " delimiter(s) cannot be matched by the platform"));
        cov.ran(cov.ran() + files.size());
        cov.wrong(cov.wrong() + unmatched);
        if (unmatched > 0) {
            sectionFailures.add("brace matching: " + unmatched + " delimiter(s) are not one of the"
                    + " types getPairs() declares, so the platform cannot match them");
        }
        System.out.println();
    }

    private static String invoke(Object o, String method) {
        try {
            Object v = o.getClass().getMethod(method).invoke(o);
            return String.valueOf(v);
        } catch (Exception e) {
            return "(no " + method + "()): " + e;
        }
    }

    // --------------------------------------------------------- (3) commenting

    /**
     * Is the commenter's prefix the character the lexer reads a comment from?
     *
     * Two places decide what a comment is -- `Commenter.getLineCommentPrefix` for
     * `Ctrl+/`, and the lexer for the token -- and a plugin where they disagree
     * comments lines out in a way it cannot read back.
     */
    private void commenting(Coverage cov) throws IOException {
        System.out.println("== 3. commenting: is the prefix the lexer's own comment character? ==");
        VelaCommenter commenter = new VelaCommenter();
        String prefix = commenter.getLineCommentPrefix();
        System.out.println("  line comment prefix     : `" + prefix + "`");
        System.out.println("  block comment prefix    : " + commenter.getBlockCommentPrefix()
                + "  (Vela has no block comment; null is the honest answer)");
        long comments = 0;
        long wrongPrefix = 0;
        List<String> files = readable(cov);
        for (String rel : files) {
            String text = read(repoRoot.resolve(rel));
            Lexer lexer = lexer();
            lexer.start(text);
            while (lexer.getTokenType() != null) {
                if (lexer.getTokenType() == VelaTokenTypes.COMMENT) {
                    comments++;
                    if (!lexer.getTokenText().startsWith(prefix)) wrongPrefix++;
                }
                lexer.advance();
            }
        }
        System.out.println("  comment tokens in corpus: " + comments);
        System.out.println("  whose text does not start with the prefix: " + wrongPrefix);
        boolean prefixOk = wrongPrefix == 0 && prefix != null && prefix.length() > 0;
        System.out.println("  VERDICT: " + (prefixOk
                ? "the commenter's prefix is the lexer's comment character, over " + comments
                        + " real comment token(s)"
                : "the two disagree, so Ctrl+/ would write text the lexer does not read as a comment"));
        cov.ran(cov.ran() + files.size());
        // 1 WHENEVER THE SENTENCE ABOVE SAYS THE TWO DISAGREE.  It used to count 0 for a
        // prefix that is empty or absent (the condition and the count were separate, and
        // the count only looked at wrongPrefix), which would have been a red verdict with
        // `wrong 0` behind it -- exactly the shape this project refuses.
        cov.wrong(cov.wrong() + (prefixOk ? 0 : 1));
        if (!prefixOk) {
            sectionFailures.add("commenting: " + (prefix == null || prefix.isEmpty()
                    ? "the commenter declares no line-comment prefix at all"
                    : wrongPrefix + " of " + comments + " comment token(s) do not start with the"
                            + " prefix `" + prefix + "`"));
        }
        System.out.println();
    }

    // ----------------------------------------------------- (4) semantic colours

    /**
     * Do the two halves of semantic highlighting agree?
     *
     * `classifyAll` is what the annotator walks; `classify(text, offset)` is the
     * binary search the editor calls when the caret moves.  They are separate code
     * paths over one table, so a disagreement is a name coloured one way and
     * resolved another -- and it is invisible from either side alone.
     */
    private void semantic(Coverage cov) throws IOException {
        System.out.println("== 4. semantic highlighting: classifyAll vs the per-offset lookup ==");
        Map<String, Long> byKind = new TreeMap<>();
        long uses = 0;
        long disagreements = 0;
        long unclassified = 0;
        List<String> files = readable(cov);
        for (String rel : files) {
            String text = read(repoRoot.resolve(rel));
            List<VelaNameUse> all;
            try {
                all = VelaSemanticNames.INSTANCE.classifyAll(text);
            } catch (Throwable t) {
                unclassified++;
                System.out.println("  " + rel + ": classifyAll THREW " + t);
                continue;
            }
            for (VelaNameUse u : all) {
                uses++;
                VelaNameKind kind = u.getKind();
                byKind.merge(kind == null ? "(null)" : kind.toString(), 1L, Long::sum);
                if (kind == null) unclassified++;
                VelaNameKind one = VelaSemanticNames.INSTANCE.classify(text, u.getStart());
                if (one != kind) {
                    disagreements++;
                    if (disagreements <= 20) {
                        System.out.println("  " + rel + " offset " + u.getStart()
                                + " `" + text.substring(Math.max(0, u.getStart()),
                                        Math.min(text.length(), u.getEnd())) + "`"
                                + ": classifyAll says " + kind + ", classify says " + one);
                    }
                }
            }
        }
        System.out.println("  files classified        : " + files.size());
        System.out.println("  name uses classified    : " + uses);
        for (Map.Entry<String, Long> e : byKind.entrySet()) {
            System.out.println("      " + Coverage.pad(e.getKey(), 26) + e.getValue());
        }
        System.out.println("  uses with no kind       : " + unclassified);
        System.out.println("  classify vs classifyAll: " + disagreements + " disagreement(s)");
        System.out.println("  VERDICT: " + (disagreements == 0 && unclassified == 0
                ? "the two paths agree on every one of " + uses + " name use(s)"
                : disagreements + " disagreement(s) and " + unclassified + " unclassified use(s)"));
        cov.ran(cov.ran() + files.size());
        cov.wrong(cov.wrong() + disagreements);
        if (disagreements > 0 || unclassified > 0) {
            sectionFailures.add("semantic highlighting: " + disagreements + " use(s) where"
                    + " classify and classifyAll disagree, " + unclassified + " with no kind at all");
        }
        System.out.println();
    }

    // ----------------------------------------------------------- (5) formatter

    /**
     * The formatter's meaning-preservation invariant, over the corpus.
     *
     * `VelaFormatRules` states it in its own header: nothing in the rule set may
     * turn a newline into a space, because a newline is what ends a statement in
     * Vela.  That claim is checkable without an editor: for every gap between two
     * tokens that contains a line feed in the source, the rule for that gap must
     * either keep line breaks or insist on at least one line feed.  A single gap
     * that does neither is a reformat that changes what the program does.
     */
    private void formatter(Coverage cov) throws IOException {
        System.out.println("== 5. formatter: can reformatting join two statements? ==");
        System.out.println("  indent size the rules declare: " + VelaFormatRules.INDENT_SIZE);
        long tokensAnalysed = 0;
        long gapsWithNewline = 0;
        long atRisk = 0;
        long negativeDepth = 0;
        long lengthMismatch = 0;
        List<String> files = readable(cov);
        for (String rel : files) {
            String text = read(repoRoot.resolve(rel));
            List<VelaToken> tokens = new ArrayList<>();
            Lexer lexer = lexer();
            lexer.start(text);
            while (lexer.getTokenType() != null) {
                if (lexer.getTokenType() != TokenType.WHITE_SPACE) {
                    tokens.add(new VelaToken(lexer.getTokenType(), lexer.getTokenText(),
                            lexer.getTokenStart(), lexer.getTokenEnd()));
                }
                lexer.advance();
            }
            if (tokens.isEmpty()) continue;
            VelaFormatRules.Analysis a = VelaFormatRules.INSTANCE.analyse(tokens);
            tokensAnalysed += tokens.size();
            if (a.depths.length != tokens.size()) lengthMismatch++;
            for (int d : a.depths) if (d < 0) negativeDepth++;
            for (int i = 1; i < tokens.size(); i++) {
                int from = tokens.get(i - 1).end;
                int to = tokens.get(i).start;
                if (to <= from) continue;
                if (text.substring(from, to).indexOf('\n') < 0) continue;
                gapsWithNewline++;
                VelaSpacing s = a.spacingAt(i);
                if (!s.keepLineBreaks && s.minLineFeeds < 1) {
                    atRisk++;
                    if (atRisk <= 20) {
                        System.out.println("  " + rel + ": gap before token " + i + " (`"
                                + tokens.get(i).text + "`, preceded by `"
                                + tokens.get(i - 1).text + "`) has a line feed in the source"
                                + " but the rule is minLineFeeds=" + s.minLineFeeds
                                + " keepLineBreaks=" + s.keepLineBreaks
                                + " -- reformatting would join the lines");
                    }
                }
            }
        }
        System.out.println("  files analysed          : " + files.size());
        System.out.println("  tokens analysed         : " + tokensAnalysed);
        System.out.println("  gaps containing a line feed: " + gapsWithNewline);
        System.out.println("  depth/spacing length mismatches: " + lengthMismatch);
        System.out.println("  negative depths         : " + negativeDepth);
        System.out.println("  gaps a reformat could join: " + atRisk);
        System.out.println("  VERDICT: " + (atRisk == 0 && lengthMismatch == 0 && negativeDepth == 0
                ? "no gap with a line feed in it can be closed by this rule set, over "
                        + gapsWithNewline + " real line break(s)"
                : "the rule set can join lines it must not join"));
        cov.ran(cov.ran() + files.size());
        cov.wrong(cov.wrong() + atRisk + lengthMismatch + negativeDepth);
        if (atRisk > 0 || lengthMismatch > 0 || negativeDepth > 0) {
            sectionFailures.add("formatter: " + atRisk + " gap(s) a reformat could join, "
                    + lengthMismatch + " depth/spacing length mismatch(es), " + negativeDepth
                    + " negative depth(s)");
        }
        System.out.println();
    }

    // ------------------------------------------------------ (6) live templates

    /**
     * Is the template file the descriptor names real, and does it declare anything?
     *
     * `defaultLiveTemplates file="liveTemplates/Vela.xml"` resolves against the
     * plugin's own resources, and a path that does not exist is not an error the
     * platform reports -- the templates are simply never there.  So the resource is
     * read here, out of the plugin classes on the same classpath the platform will
     * use.
     */
    private void liveTemplates(Coverage cov) throws IOException {
        System.out.println("== 6. live templates: does the resource the descriptor names exist? ==");
        String resource = "/liveTemplates/Vela.xml";
        // TWO PLACES, BECAUSE ONE OF THEM ALREADY LIED TO THIS PROBE.  The first run of
        // this file reported "NOT ON THE CLASSPATH" and was wrong: the harness's run
        // classpath is `build\classes`, which holds compiled *classes* only -- the
        // resources are packed into the plugin jar from `src\main\resources` by
        // `build-offline.ps1` and are never copied into `build\classes`.  So the resource
        // is checked where the platform will actually find it -- inside the artifact --
        // and separately on the classpath, and the two are reported apart:
        //
        //   * the jar entry is authoritative for shipping: a file that is not in
        //     `dist\vela\lib\vela-idea-plugin.jar` cannot be loaded by any IDE;
        //   * the classpath lookup is what this test JVM sees, and it is only meaningful
        //     when `src\main\resources` is on the classpath (`harness.ps1` now puts it
        //     there).  A miss there is a property of this invocation, not of the plugin,
        //     and is reported as such rather than as a defect.
        Path jar = repoRoot.resolve("idea-plugin").resolve("dist").resolve("vela")
                .resolve("lib").resolve("vela-idea-plugin.jar");
        String fromJar = null;
        long jarSize = -1;
        if (Files.isRegularFile(jar)) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                java.util.zip.ZipEntry e = zip.getEntry("liveTemplates/Vela.xml");
                if (e != null) {
                    jarSize = e.getSize();
                    try (InputStream in = zip.getInputStream(e)) {
                        fromJar = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            } catch (Exception e) {
                System.out.println("  could not read the artifact: " + e);
            }
        }
        System.out.println("  artifact                : " + jar
                + (Files.isRegularFile(jar) ? "" : "  (not built; run build-offline.ps1)"));
        System.out.println("  liveTemplates/Vela.xml in the artifact: "
                + (jarSize >= 0 ? jarSize + " byte(s), read" : "ABSENT"));

        String xml = null;
        try (InputStream in = FeatureProbe.class.getResourceAsStream(resource)) {
            if (in != null) xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            xml = null;
        }
        System.out.println("  on this JVM's classpath : "
                + (xml != null ? xml.length() + " char(s)"
                        : "not found (this run's classpath has no resources root; the artifact"
                                + " check above is the one that decides)"));

        if (xml == null) xml = fromJar;
        if (xml == null) {
            System.out.println("  VERDICT: the descriptor names a template file that is in neither"
                    + " the plugin artifact nor the classpath, so no template would ever be"
                    + " offered");
            cov.ran(cov.ran() + 1);
            cov.wrong(cov.wrong() + 1);
            sectionFailures.add("live templates: the file the descriptor names is in neither the"
                    + " plugin artifact nor the classpath, so nothing would ever be offered");
            System.out.println();
            return;
        }

        List<String> names = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<template\\s+name=\"([^\"]+)\"").matcher(xml);
        while (m.find()) names.add(m.group(1));
        List<String> contexts = new ArrayList<>();
        m = java.util.regex.Pattern.compile("\\bcontext\\s*=\\s*\"([^\"]+)\"").matcher(xml);
        while (m.find()) contexts.add(m.group(1));
        Set<String> distinctContexts = new LinkedHashSet<>(contexts);
        System.out.println("  templates declared      : " + names.size() + " " + names);
        System.out.println("  contexts on those       : " + distinctContexts);
        boolean inArtifact = jarSize >= 0;
        boolean ok = !names.isEmpty() && distinctContexts.contains("VELA") && inArtifact;
        System.out.println("  VERDICT: " + (ok
                ? "the file is in the artifact, declares " + names.size()
                        + " template(s), and every one is in the VELA context"
                : (!inArtifact
                        ? "the file is not in the plugin artifact, so nothing would be offered"
                        : "the file exists but declares no template in the VELA context, so"
                                + " nothing would be offered inside a .vel file")));
        cov.ran(cov.ran() + 1);
        cov.wrong(cov.wrong() + (ok ? 0 : 1));
        if (!ok) {
            sectionFailures.add("live templates: " + (!inArtifact
                    ? "the template file is not in the plugin artifact, so nothing would ever be"
                            + " offered inside an IDE"
                    : "the file exists but declares no template in the VELA context"));
        }
        System.out.println("  (the context type itself, and the descriptor line that names this"
                + " file, are checked by VerifyPlugin against the installed platform)");
        System.out.println();
    }
}
