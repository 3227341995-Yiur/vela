import dev.vela.plugin.VelaDeclarations;
import dev.vela.plugin.VelaTarget;
import dev.vela.plugin.VelaTargets;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Throwaway: what does the resolver answer for three known references? */
public final class ProbeGoto {
    public static void main(String[] a) throws Exception {
        String text = new String(Files.readAllBytes(Paths.get(a[0])), StandardCharsets.ISO_8859_1);
        int[] offsets = new int[a.length - 1];
        for (int i = 1; i < a.length; i++) offsets[i - 1] = Integer.parseInt(a[i]);
        for (int off : offsets) {
            VelaTarget t = VelaTargets.INSTANCE.declarationFor(text, off);
            Object range = VelaDeclarations.INSTANCE.declarationRange(text, off);
            System.out.println("offset " + off + "  context=`" + snippet(text, off) + "`");
            System.out.println("   declarationFor: " + (t == null ? "null"
                    : t.getKind() + " `" + t.getName() + "` " + t.getStart() + ".." + t.getEnd()));
            System.out.println("   declarationRange: " + (range == null ? "null" : range));
        }
    }

    private static String snippet(String text, int off) {
        int from = Math.max(0, off - 14);
        int to = Math.min(text.length(), off + 14);
        return text.substring(from, to).replace("\n", "\\n");
    }
}
