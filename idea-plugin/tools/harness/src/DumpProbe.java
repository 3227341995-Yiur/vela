import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import dev.vela.plugin.VelaSyntaxDump;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;

/**
 * What the plugin's own tree says about one file, and what its `import` nodes hold.
 *
 * `ast-diff` compares the plugin's dump against the compiler's, and when a multi-file
 * program disagreed it could only say "mine 10 nodes vs compiler 17" -- which is true of
 * both "the splice found no import" and "the import is not an IMPORT node".  This probe
 * answers the question the comparison cannot: print the dump, then every top-level node's
 * kind, own text, and first child's text.
 *
 *     java -cp "<platform classpath>;build\tools\harness\classes" DumpProbe <file.vel>
 */
public final class DumpProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("usage: DumpProbe <file.vel>");
            return;
        }
        Path file = Paths.get(args[0]);
        String src = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        VelaSyntaxTree tree = VelaSyntaxParser.parse(src);
        System.out.println("=== dump ===");
        System.out.println(VelaSyntaxDump.INSTANCE.dump(tree));
        System.out.println("=== top-level nodes ===");
        if (!tree.root.children.isEmpty()) {
            for (VelaSyntaxNode s : tree.root.children.get(0).children) {
                String kid = s.children.isEmpty() ? "<none>" : s.children.get(0).text;
                System.out.println(s.kind + "  ownText=[" + s.text + "]  firstChild=[" + kid + "]");
            }
        }
    }
}
