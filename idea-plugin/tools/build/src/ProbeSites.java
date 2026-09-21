import java.lang.reflect.*;
import java.util.*;

/**
 * Bisects the scanning half: the same tiny inputs, from a bare `f(1)` up to the
 * sample file, so the first input that produces nothing marks where it breaks.
 */
public final class ProbeSites {

    static Class<?> hints;
    static Object inst;
    static Method sites;
    static Method parameterHints;

    public static void main(String[] args) throws Exception {
        java.io.File dir = new java.io.File(args[0]);
        java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{dir.toURI().toURL()}, ProbeSites.class.getClassLoader());
        hints = Class.forName("dev.vela.plugin.VelaHints", true, loader);
        inst = hints.getField("INSTANCE").get(null);
        sites = hints.getMethod("callSites$vela_idea_plugin", CharSequence.class, int.class);
        parameterHints = hints.getMethod("parameterHints", CharSequence.class, int.class);

        String[] cases = {
            "f(1)",
            "def f(a: int) -> None { return }\ndef main() -> None { f(1) }\n",
            "pure def fib(n: int) -> int { return n }\n",
            "struct Vec2 {\n    x: float\n\n    def dot(self: Vec2, o: Vec2) -> float {\n        return self.x * o.x\n    }\n}\n",
            "def main() -> None { print(1) }\n",
            "# a comment\n",
            "s: str = \"text\"\n",
            "s: str = \"text with concat(1, 2) in it\"\n",
        };
        for (String c : cases) {
            System.out.println("==== " + c.replace("\n", "\\n"));
            System.out.println("     callSites     -> " + sites.invoke(inst, c, c.length()));
            System.out.println("     parameterHints-> " + parameterHints.invoke(inst, c, c.length()));
        }

        String sample = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(args[1])), java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("==== the sample file");
        System.out.println("     callSites     -> " + sites.invoke(inst, sample, sample.length()));
        System.out.println("     parameterHints-> " + parameterHints.invoke(inst, sample, sample.length()));

        // The sample minus its comment and minus its string literal, to see which
        // line stops the scan.
        String noComment = sample.replace("# fib(10) inside a comment must produce nothing\n", "");
        System.out.println("==== sample without the comment line");
        System.out.println("     callSites     -> " + sites.invoke(inst, noComment, noComment.length()));
        String noString = noComment.replace("s: str = \"text with concat(1, 2) in it\"\n", "");
        System.out.println("==== sample without the string-literal line");
        System.out.println("     callSites     -> " + sites.invoke(inst, noString, noString.length()));
        System.out.println("     parameterHints-> " + parameterHints.invoke(inst, noString, noString.length()));
        String upToPrint = noString.substring(0, noString.indexOf("print(p.dot(p))") + 13);
        System.out.println("==== sample truncated just after `print(p.dot(p)` -> " + upToPrint.length() + " chars");
        System.out.println("     callSites     -> " + sites.invoke(inst, upToPrint, upToPrint.length()));
    }
}
