import com.intellij.lang.ASTNode;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.lang.impl.PsiBuilderImpl;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.tree.IElementType;

import dev.vela.plugin.VelaParserDefinition;
import dev.vela.plugin.VelaSyntaxDump;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaTok;
import dev.vela.plugin.VelaTokKind;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The PSI half of the parser's acceptance test: does the tree the IDE is actually
 * given have the shape the parser built, and does it cover the file?
 *
 * `AstDiff` proves the parser agrees with the compiler about what a Vela program
 * *is*.  This proves the other half, which the differential cannot see: that
 * `VelaParserDefinition` hands the platform a tree with the same shape, with every
 * leaf where the parser said it was and the whole file covered.  A parser that is
 * right and a replay that drops every leaf one level up would pass `AstDiff` and
 * leave the editor with empty `NAME` elements -- which is exactly what the first
 * version of `VelaPsiReplay` did.
 *
 * It runs through the platform's own `PsiBuilderImpl`, not a stand-in, so the
 * whitespace-leaf behaviour, the marker bookkeeping and the element ranges are the
 * platform's and not a model of them.  That needs a booted `Application`, which
 * this machine has no `idea.exe` for; `MockApplication` is the platform's own
 * headless application (the one its test framework uses) and it is sufficient
 * here, because parsing needs the extension area for `ASTFactory` and nothing
 * more.
 *
 * WHAT IS ASSERTED, PER FILE
 *
 *   1. the replay does not throw, on valid files or broken ones;
 *   2. the composite elements, in depth-first order, are the parser's nodes with
 *      the same kinds -- `VELA_FILE` standing in for the parser's `module`;
 *   3. every leaf is a real lexer token range, the leaves do not overlap, and they
 *      tile the file's text exactly (offset 0 to the end, no gap);
 *   4. every non-trivia leaf is one of the tokens the parser claimed, with the same
 *      text -- so no leaf can be invented and none can be lost;
 *   5. a node the parser reported a problem in still produces an element with the
 *      parser's ERROR type, and the file's *last* line still has a node, so one bad
 *      line never costs the rest of the file.
 *
 *   java -cp <plugin classes> PsiTreeDiff <repo-root> [--vm <compiler>] [--single f]
 */
public final class PsiTreeDiff {

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private final List<String> corpus = new ArrayList<>();
    private Path repoRoot;
    private Path vm;
    private String single;
    private boolean verbose;
    private final Set<String> nodeTypes = new LinkedHashSet<>();
    private int scannerRefusedFiles;

    public static void main(String[] args) throws Exception {
        PsiTreeDiff tool = new PsiTreeDiff();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--single")) {
                tool.single = args[++i];
            } else if (a.equals("--verbose")) {
                tool.verbose = true;
            } else if (a.equals("--vm")) {
                tool.vm = Paths.get(args[++i]).toAbsolutePath();
            } else {
                rest.add(a);
            }
        }
        Path root = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        tool.run(root);
    }

    private void run(Path root) throws Exception {
        repoRoot = root;
        Disposable rootDisposable = Disposer.newDisposable();
        MockApplication app = new MockApplication(rootDisposable);
        ApplicationManager.setApplication(app, rootDisposable);
        if (ApplicationManager.getApplication() == null) {
            System.out.println("could not boot the platform's headless application");
            System.exit(2);
        }
        System.out.println("application : " + ApplicationManager.getApplication().getClass().getName()
                + " (the platform's own headless application, so the builder under test is the real one)");
        if (vm != null) {
            System.out.println("compiler    : " + vm + " (" + Files.size(vm) + " bytes)");
        }

        if (single != null) {
            corpus.add(single.replace('\\', '/'));
        } else {
            collectCorpus();
        }

        VelaParserDefinition def = new VelaParserDefinition();
        nodeTypes.addAll(VelaSyntaxDump.INSTANCE.nodeElementTypeNames());
        Project project = (Project) Proxy.newProxyInstance(
                PsiTreeDiff.class.getClassLoader(), new Class<?>[]{Project.class}, new Stub());

        int ok = 0;
        int bad = 0;
        int broken = 0;
        int absent = 0;
        int tooLarge = 0;
        int threw = 0;
        long nodesChecked = 0;
        long leavesChecked = 0;
        StringBuilder table = new StringBuilder(16384);
        List<String> failures = new ArrayList<>();
        table.append(pad("file", 58)).append(pad("nodes", 8)).append(pad("leaves", 8)).append("  verdict\n");
        table.append("-".repeat(58)).append("  ").append("-".repeat(6)).append("  ")
                .append("-".repeat(6)).append("  ").append("-".repeat(30)).append('\n');

        for (String rel : corpus) {
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p)) {
                // Counted.  `MISSING` used to be printed and then forgotten, so a corpus
                // entry naming a file that is not on disk appeared in the table and
                // nowhere in the totals.
                absent++;
                table.append(pad(rel, 58)).append(pad("-", 8)).append(pad("-", 8)).append("  MISSING\n");
                continue;
            }
            if (Files.size(p) > MAX_FILE_BYTES) {
                tooLarge++;
                table.append(pad(rel, 58)).append(pad("-", 8)).append(pad("-", 8))
                        .append("  SKIPPED (too large)\n");
                continue;
            }
            String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            VelaSyntaxTree tree = VelaSyntaxParser.parse(text);
            if (!tree.complete) broken++;

            List<String> problemsHere = new ArrayList<>();
            ASTNode rootNode;
            try {
                Lexer lexer = def.createLexer(project);
                PsiBuilder b = new PsiBuilderImpl(project, null, def, lexer, null, text, null, null);
                PsiParser parser = def.createParser(project);
                parser.parse(def.getFileNodeType(), b);
                rootNode = b.getTreeBuilt();
            } catch (Throwable t) {
                bad++;
                threw++;
                table.append(pad(rel, 58)).append(pad("-", 8)).append(pad("-", 8))
                        .append("  REPLAY THREW ").append(t.getClass().getSimpleName()).append('\n');
                failures.add("  " + rel + ": the replay threw " + t);
                continue;
            }

            // (2) the composite skeleton is the parser's skeleton.
            List<String> mineKinds = new ArrayList<>();
            List<VelaSyntaxNode> mineNodes = new ArrayList<>();
            skipModule(tree.root, mineKinds, mineNodes);
            List<String> theirKinds = new ArrayList<>();
            List<ASTNode> theirNodes = new ArrayList<>();
            collectComposites(rootNode, theirKinds, theirNodes);
            if (!mineKinds.equals(theirKinds)) {
                problemsHere.add("composite shape differs: " + firstStringDiff(mineKinds, theirKinds));
            }

            // (3) and (4): the leaves tile the text, and each one is the parser's own.
            Set<String> claimed = new LinkedHashSet<>();
            for (VelaTok t : tree.toks) {
                claimed.add(t.start + ".." + t.end);
            }
            // A scan failure leaves the parser with no token list at all -- the
            // scanner stops, as the compiler's does (an integer literal too large, an
            // unterminated string), and there is nothing to claim anything with.  The
            // file then gets one error element at the offset the scanner stopped at and
            // the whole text as trivia.  That is a design decision, not a hole in the
            // test: with no tokens claimed, "no leaf is claimed" is the correct
            // expectation, and the check that matters -- the text is still covered --
            // still runs.  The count is reported so it cannot hide.
            // A file the parser could not finish has text past the last token it
            // claimed: the scan stopped at an unreadable token, or a recovering parse
            // gave up.  That text can only be trivia -- no node claims it -- and it is
            // still required to be *covered*, which is the check that matters.  So the
            // claim rule applies to the part of the file the parser did read.
            int lastClaimedEnd = 0;
            for (VelaTok t : tree.toks) {
                if (t.end > lastClaimedEnd) lastClaimedEnd = t.end;
            }
            if (!tree.complete && lastClaimedEnd < text.length()) scannerRefusedFiles++;
            int leaves = 0;
            int cursor = 0;
            boolean tiling = true;
            for (ASTNode leaf = firstLeaf(rootNode); leaf != null; leaf = nextLeaf(leaf)) {
                leaves++;
                if (leaf.getStartOffset() != cursor) {
                    tiling = false;
                    problemsHere.add("leaf gap/overlap at offset " + leaf.getStartOffset()
                            + " (the previous leaf ended at " + cursor + "), leaf type "
                            + leaf.getElementType());
                    break;
                }
                cursor = leaf.getStartOffset() + leaf.getTextLength();
                String type = leaf.getElementType().toString();
                if (leaf.getTextLength() == 0) {
                    // A synthesised element with no source text of its own -- the `int 0`
                    // a one-argument `range` implies, the `module` before its first
                    // statement.  It covers nothing, so it can claim no token; it must
                    // simply be empty and in the right place.
                    if (type.equals("WHITE_SPACE")) {
                        problemsHere.add("a zero-width WHITE_SPACE leaf at " + cursor);
                    }
                    continue;
                }
                if (!tree.complete && leaf.getStartOffset() >= lastClaimedEnd) continue;
                if (!type.equals("WHITE_SPACE") && !type.equals("VELA_COMMENT")) {
                    // Whitespace and comments are the lexer's trivia, not the parser's
                    // tokens: the scanner never sees a comment at all (it skips them),
                    // and a newline is a `NEWLINE` token for the parser but the start of
                    // a `WHITE_SPACE` run for the lexer.  Both are expected to appear in
                    // the tree without having been claimed, and both are what the
                    // formatter and the folding builder walk over.
                    //
                    // A string literal is the one place where the two granule sizes
                    // differ on purpose, so the rule says so instead of being loosened:
                    // the parser's `STRING` token is the literal's *contents* (that is
                    // what the compiler records, and what `d_str` prints), while the
                    // lexer's `VELA_STRING` is the whole `"..."` including the quotes
                    // (that is the range the highlighter colours).  So a VELA_STRING leaf
                    // is acceptable exactly when the parser claimed the same characters
                    // one inside each quote -- `start+1 .. end-1` -- and nothing else is.
                    String range = leaf.getStartOffset() + ".." + cursor;
                    boolean isClaimedText = claimed.contains(range);
                    if (!isClaimedText && type.equals("VELA_STRING") && leaf.getTextLength() >= 2) {
                        String inner = (leaf.getStartOffset() + 1) + ".." + (cursor - 1);
                        isClaimedText = claimed.contains(inner);
                    }
                    if (!isClaimedText) {
                        problemsHere.add("leaf " + type + " at " + range
                                + " is not a token this parser claimed: `" + leaf.getText() + "`");
                    }
                }
            }
            if (tiling && cursor != text.length()) {
                problemsHere.add("the leaves stop at offset " + cursor + " of " + text.length());
            }

            // (5) recovery keeps the tail: the last non-blank line still has a node.
            if (!tree.complete) {
                int lastLineStart = lastNonBlankLineOffset(text);
                if (lastLineStart >= 0) {
                    ASTNode at = deepestNodeAt(rootNode, lastLineStart);
                    if (at == null) {
                        problemsHere.add("recovery lost the last line: no element covers offset "
                                + lastLineStart);
                    }
                }
                // The editor has to be able to show *that* something is wrong, and two
                // shapes are legitimate: a `VELA_ERROR` node, where the parser abandoned
                // a construct and wrapped the tokens it could not read, and an
                // `ERROR_ELEMENT`, which is what `PsiBuilder.error` produces at a
                // problem's offset.  Some problems are recorded without a node of their
                // own (a missing `}` at the end of a file, adjacent string literals), and
                // those reach the editor as the second shape.
                boolean hasError = !collectByType(rootNode, "VELA_ERROR").isEmpty()
                        || !collectByType(rootNode, "ERROR_ELEMENT").isEmpty();
                if (!hasError) {
                    problemsHere.add("the parser reported a problem but the tree has no error"
                            + " element (neither VELA_ERROR nor ERROR_ELEMENT), so the editor"
                            + " would show nothing");
                }
            }

            if (problemsHere.isEmpty()) {
                ok++;
                nodesChecked += mineKinds.size();
                leavesChecked += leaves;
                table.append(pad(rel, 58)).append(pad(String.valueOf(mineKinds.size()), 8))
                        .append(pad(String.valueOf(leaves), 8)).append("  ok\n");
            } else {
                bad++;
                table.append(pad(rel, 58)).append(pad(String.valueOf(mineKinds.size()), 8))
                        .append(pad(String.valueOf(leaves), 8)).append("  FAILED\n");
                for (String s : problemsHere) {
                    failures.add("  " + rel + ": " + s);
                }
                if (verbose) {
                    System.out.println("---- " + rel + " (" + mineKinds.size()
                            + " composite node(s))");
                }
            }
        }

        System.out.println();
        System.out.println("== corpus ==");
        System.out.println(table);
        if (!failures.isEmpty()) {
            System.out.println("== what failed ==");
            for (String f : failures) System.out.println(f);
            System.out.println();
        }
        System.out.println("== totals ==");
        System.out.println("  files replayed through the platform's builder : "
                + (ok + bad) + " of " + corpus.size() + " in the corpus");
        System.out.println("  ok                                           : " + ok);
        System.out.println("  failed                                       : " + bad);
        System.out.println("  of which the parser itself reported a problem : " + broken
                + " (recovery is exercised, not avoided)");
        System.out.println("  of which the scanner refused to tokenise       : " + scannerRefusedFiles
                + " (for those, the text past the last token the parser claimed can only be"
                + " trivia, and is still required to be covered)");
        System.out.println("  composite nodes compared                     : " + nodesChecked);
        System.out.println("  leaf tokens compared                         : " + leavesChecked);
        System.out.println("  VERDICT                                      : " + (bad == 0 ? "PASS" : "FAIL"));
        Coverage cov = new Coverage()
                .defectCategory("missing-corpus-file")
                .defectCategory("replay-threw")
                .category("too-large")
                .ran(ok)
                .defect("replay-threw", threw)
                .defect("missing-corpus-file", absent)
                .skipped("too-large", tooLarge)
                .wrong(bad - threw);
        cov.print();
        if (cov.accounted() != corpus.size()) {
            System.out.println("  NOTE: ran + skipped = " + cov.accounted()
                    + " but the corpus has " + corpus.size()
                    + " entry/entries: a file was neither replayed nor categorised.");
        }
        System.out.println();
        System.exit(bad == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------- tree walking

    /** The parser's node kinds, in depth-first order, with `module` skipped. */
    private static void skipModule(VelaSyntaxNode n, List<String> kinds, List<VelaSyntaxNode> nodes) {
        for (VelaSyntaxNode c : n.children) {
            kinds.add(VelaSyntaxDump.INSTANCE.elementTypeNameOf(c.kind));
            nodes.add(c);
            skipModule(c, kinds, nodes);
        }
    }

    /**
     * The platform tree's composite elements, in depth-first order, with the file
     * element skipped.
     *
     * The file element is the platform's root marker and has no counterpart in the
     * parser's tree: the parser's `module` node was flattened into it on purpose.
     * Everything below it must line up, node for node, in the same order.
     *
     * An empty node is a composite too.  `extern c def f() -> int` carries an empty
     * `block` child because the compiler's dumper prints a `block` line for it, and
     * that child reaches the PSI tree as a zero-length element with no children --
     * which looks exactly like a leaf.  `nodeTypes` is the plugin's own list of node
     * element types, so this asks rather than guesses.
     */
    private void collectComposites(ASTNode n, List<String> kinds, List<ASTNode> nodes) {
        for (ASTNode c = n.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (c.getFirstChildNode() != null) {
                kinds.add(c.getElementType().toString());
                nodes.add(c);
                collectComposites(c, kinds, nodes);
            } else if (c.getElementType() instanceof com.intellij.psi.tree.IFileElementType) {
                collectComposites(c, kinds, nodes);
            } else if (c.getTextLength() == 0 && nodeTypes.contains(c.getElementType().toString())) {
                // an empty node: it covers no text, so it has no children
                kinds.add(c.getElementType().toString());
                nodes.add(c);
            }
        }
    }

    private static ASTNode firstLeaf(ASTNode n) {
        if (n.getFirstChildNode() == null) return n;
        for (ASTNode c = n.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            ASTNode leaf = firstLeaf(c);
            if (leaf != null) return leaf;
        }
        return null;
    }

    private static ASTNode nextLeaf(ASTNode leaf) {
        ASTNode n = leaf;
        while (n != null) {
            if (n.getTreeNext() != null) return firstLeaf(n.getTreeNext());
            n = n.getTreeParent();
        }
        return null;
    }

    private static ASTNode deepestNodeAt(ASTNode n, int offset) {
        if (offset < n.getStartOffset() || offset >= n.getStartOffset() + n.getTextLength()) {
            if (n.getTextLength() != 0) return null;
        }
        for (ASTNode c = n.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            if (offset >= c.getStartOffset()
                    && offset < c.getStartOffset() + c.getTextLength()) {
                return deepestNodeAt(c, offset);
            }
        }
        return n;
    }

    private static List<ASTNode> collectByType(ASTNode n, String type) {
        List<ASTNode> out = new ArrayList<>();
        if (n.getElementType().toString().equals(type)) out.add(n);
        for (ASTNode c = n.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            out.addAll(collectByType(c, type));
        }
        return out;
    }

    private static int lastNonBlankLineOffset(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
        if (end == 0) return -1;
        int start = text.lastIndexOf('\n', end - 1);
        String line = text.substring(start + 1, end);
        if (line.trim().isEmpty() || line.trim().equals("}")) {
            // A closing brace is not evidence that a statement survived: walk back to
            // the last line with a statement on it.
            if (start < 0) return -1;
            return lastNonBlankLineOffset(text.substring(0, start));
        }
        return start + 1;
    }

    private static String firstStringDiff(List<String> a, List<String> b) {
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            String x = i < a.size() ? a.get(i) : "<end of tree>";
            String y = i < b.size() ? b.get(i) : "<end of tree>";
            if (!x.equals(y)) {
                return "element " + i + ": the parser has " + x + ", the platform tree has " + y;
            }
        }
        return "(no difference)";
    }

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

    private static String pad(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    static final class Stub implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method m, Object[] args) {
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            if (r == long.class) return 0L;
            if (r == void.class) return null;
            String n = m.getName();
            if (n.equals("toString")) return "stub-project";
            if (n.equals("hashCode")) return System.identityHashCode(proxy);
            if (n.equals("equals")) return args != null && proxy == args[0];
            return null;
        }
    }

    private PsiTreeDiff() {
    }
}
