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

import dev.vela.plugin.VelaParserDefinition;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaTok;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * WHY THE GUARD IN `PsiTreeDiff` DOES NOT FIRE: the raw offsets, on one file.
 *
 * This is a diagnostic, not a verdict.  It prints, for a single file:
 *
 *   * the parser's claimed token ranges, and `lastClaimedEnd`;
 *   * every composite node's (startOffset, endOffset) as `VelaParserDefinition`
 *     itself computes them -- `startTok` / `endTok` and the character range;
 *   * every leaf in the platform tree with its type, range and text;
 *   * whether the file is `complete`.
 *
 * It exists because `tests/build/lexer_error.vel` fails `psi-tree-diff.ps1` with
 * a leaf the parser never claimed, and reading the two code paths said it could
 * not happen.  A measurement beats an argument about which of the two readings
 * is wrong.
 *
 *   java -cp <plugin classes> PsiLeafProbe <repo-root> <file> [--vm <compiler>]
 */
public final class PsiLeafProbe {

    public static void main(String[] args) throws Exception {
        // Usage: PsiLeafProbe [<repo-root>] <file>.  The repo root defaults to the
        // working directory, and it is told apart from the file by looking like one
        // of the corpus roots rather than by position -- `PsiLeafProbe tests/...`
        // and `PsiLeafProbe C:\repo tests/...` both have to work.
        String rel = null;
        Path root = null;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--vm")) {
                i++;
            } else {
                rest.add(args[i]);
            }
        }
        if (rest.size() >= 2) {
            root = Paths.get(rest.get(0)).toAbsolutePath();
            rel = rest.get(1);
        } else if (rest.size() == 1) {
            rel = rest.get(0);
        } else {
            System.out.println("usage: PsiLeafProbe [<repo-root>] <file>");
            System.exit(2);
        }
        if (root == null) root = Paths.get("").toAbsolutePath();
        Path p = root.resolve(rel.replace('\\', '/'));
        if (!Files.isRegularFile(p)) {
            // Then the single argument was the root and the file was left out; say so
            // rather than reporting a NoSuchFileException as if it were a bug here.
            System.out.println("not a file: " + p);
            System.out.println("usage: PsiLeafProbe [<repo-root>] <file>");
            System.exit(2);
        }
        System.out.println("repo root: " + root);
        String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);

        System.out.println("file     : " + rel + " (" + text.length() + " chars, read as ISO-8859-1)");
        System.out.println("complete : see below");

        VelaSyntaxTree tree = VelaSyntaxParser.parse(text);
        System.out.println("tree.complete : " + tree.complete);
        System.out.println("problems      : " + tree.problems.size());
        for (Object pr : tree.problems) {
            System.out.println("   problem    : " + pr);
        }
        int lastClaimedEnd = 0;
        System.out.println();
        System.out.println("== the parser's tokens ==");
        for (int i = 0; i < tree.toks.size(); i++) {
            VelaTok t = tree.toks.get(i);
            if (t.end > lastClaimedEnd) lastClaimedEnd = t.end;
            System.out.println("  [" + i + "] " + pad(t.kind.toString(), 10) + " "
                    + pad(t.start + ".." + t.end, 12) + " text=`" + text.substring(
                            Math.max(0, Math.min(t.start, text.length())),
                            Math.max(0, Math.min(t.end, text.length()))) + "`");
        }
        System.out.println("  lastClaimedEnd = " + lastClaimedEnd);

        System.out.println();
        System.out.println("== the parser's nodes (depth-first), with the offsets the replay computes ==");
        dumpNodes(tree.root, 0, tree, text, 0);

        System.out.println();
        System.out.println("== the platform tree ==");
        Disposable d = Disposer.newDisposable();
        MockApplication app = new MockApplication(d);
        ApplicationManager.setApplication(app, d);
        VelaParserDefinition def = new VelaParserDefinition();
        Project project = (Project) Proxy.newProxyInstance(
                PsiLeafProbe.class.getClassLoader(), new Class<?>[]{Project.class}, new Stub());
        Lexer lexer = def.createLexer(project);
        PsiBuilder b = new PsiBuilderImpl(project, null, def, lexer, null, text, null, null);
        PsiParser parser = def.createParser(project);
        parser.parse(def.getFileNodeType(), b);
        ASTNode rootNode = b.getTreeBuilt();
        dumpTree(rootNode, 0, text);
        System.out.println();
        System.out.println("== leaves ==");
        int cursor = 0;
        for (ASTNode leaf = firstLeaf(rootNode); leaf != null; leaf = nextLeaf(leaf)) {
            System.out.println("  " + pad(leaf.getElementType().toString(), 12)
                    + " " + pad(leaf.getStartOffset() + ".."
                            + (leaf.getStartOffset() + leaf.getTextLength()), 12)
                    + " len=" + pad(String.valueOf(leaf.getTextLength()), 4)
                    + " claimed=" + claimed(tree, leaf)
                    + " text=`" + leaf.getText() + "`");
            cursor = leaf.getStartOffset() + leaf.getTextLength();
        }
        System.out.println("  leaves end at " + cursor + " of " + text.length());
    }

    private static String claimed(VelaSyntaxTree tree, ASTNode leaf) {
        String range = leaf.getStartOffset() + ".." + (leaf.getStartOffset() + leaf.getTextLength());
        for (VelaTok t : tree.toks) {
            if ((t.start + ".." + t.end).equals(range)) return "YES";
        }
        if (leaf.getTextLength() >= 2) {
            String inner = (leaf.getStartOffset() + 1) + ".." + (leaf.getStartOffset() + leaf.getTextLength() - 1);
            for (VelaTok t : tree.toks) {
                if ((t.start + ".." + t.end).equals(inner)) return "YES(inner " + inner + ")";
            }
        }
        return "NO";
    }

    private static void dumpNodes(VelaSyntaxNode n, int depth, VelaSyntaxTree tree,
                                  String text, int lastClaimedEnd) {
        for (VelaSyntaxNode c : n.children) {
            int startTok = c.startTok;
            int endTok = c.endTok;
            String startRange = (startTok >= 0 && startTok < tree.toks.size())
                    ? tree.toks.get(startTok).start + ".." + tree.toks.get(startTok).end : "<none>";
            String endRange = (endTok >= 0 && endTok < tree.toks.size())
                    ? tree.toks.get(endTok).start + ".." + tree.toks.get(endTok).end : "<none>";
            System.out.println("  " + " ".repeat(depth * 2) + pad(c.kind.toString(), 14)
                    + " startTok=" + pad(startTok + "[" + startRange + "]", 18)
                    + " endTok=" + pad(endTok + "[" + endRange + "]", 18)
                    + " charStart=" + pad(String.valueOf(c.charStart), 6)
                    + " charEnd=" + pad(String.valueOf(c.charEnd), 6)
                    + " children=" + c.children.size());
            dumpNodes(c, depth + 1, tree, text, lastClaimedEnd);
        }
    }

    private static void dumpTree(ASTNode n, int depth, String text) {
        String type = n.getElementType().toString();
        boolean composite = n.getFirstChildNode() != null;
        System.out.println("  " + " ".repeat(depth * 2) + pad(type, 16)
                + " " + pad(n.getStartOffset() + ".." + (n.getStartOffset() + n.getTextLength()), 12)
                + (composite ? " (composite)" : " (leaf)"));
        for (ASTNode c = n.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            dumpTree(c, depth + 1, text);
        }
    }

    static ASTNode firstLeaf(ASTNode n) {
        if (n.getFirstChildNode() == null) return n;
        for (ASTNode c = n.getFirstChildNode(); c != null; c = c.getTreeNext()) {
            ASTNode leaf = firstLeaf(c);
            if (leaf != null) return leaf;
        }
        return null;
    }

    static ASTNode nextLeaf(ASTNode leaf) {
        ASTNode n = leaf;
        while (n != null) {
            if (n.getTreeNext() != null) return firstLeaf(n.getTreeNext());
            n = n.getTreeParent();
        }
        return null;
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
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

    private PsiLeafProbe() {
    }
}
