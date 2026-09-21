import dev.vela.plugin.VelaTargets;
import dev.vela.plugin.VelaNodeKind;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaTok;
import dev.vela.plugin.VelaTokKind;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Throwaway: what does declaredParameterNames answer for the calls on given lines? */
public final class ProbeParams {
    public static void main(String[] a) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.ISO_8859_1);
        for (int i = 1; i < a.length; i++) {
            int line = Integer.parseInt(a[i]);
            int off = offsetOfLine(text, line);
            String body = lineBody(text, line);
            System.out.println("line " + line + ": `" + body.trim() + "`");
            int at = 0;
            while (true) {
                at = body.indexOf('(', at);
                if (at < 0) break;
                int start = off + at - 1;
                while (start >= 0 && isNamePart(text.charAt(start))) start--;
                start++;
                String name = text.substring(start, off + at);
                List<String> names = VelaTargets.INSTANCE.declaredParameterNames(text, start);
                System.out.println("    call `" + name + "` at " + start
                        + " -> declaredParameterNames = " + names);
                if (names == null) {
                    VelaSyntaxTree tree = VelaSyntaxParser.parse(text);
                    for (int k = 0; k < tree.toks.size(); k++) {
                        VelaTok tok = tree.toks.get(k);
                        if (tok.start <= start + 1 && tok.end >= start - 1) {
                            System.out.println("        token " + k + " kind=" + tok.kind
                                    + " code=" + tok.code + " [" + tok.start + "," + tok.end + ")");
                        }
                    }
                }
                at++;
            }
        }
    }

    private static boolean isNamePart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    private static int offsetOfLine(String text, int line) {
        int from = 0;
        for (int i = 1; i < line; i++) {
            int nl = text.indexOf('\n', from);
            if (nl < 0) return 0;
            from = nl + 1;
        }
        return from;
    }

    private static String lineBody(String text, int line) {
        int from = offsetOfLine(text, line);
        int to = text.indexOf('\n', from);
        return text.substring(from, to < 0 ? text.length() : to);
    }
}
