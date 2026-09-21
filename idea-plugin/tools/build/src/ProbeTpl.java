import java.lang.reflect.*;
import java.util.*;

/** why does callTemplate return null, step by step. */
public final class ProbeTpl {

    public static void main(String[] args) throws Exception {
        java.io.File dir = new java.io.File(args[0]);
        java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{dir.toURI().toURL()}, ProbeTpl.class.getClassLoader());

        Class<?> hints = Class.forName("dev.vela.plugin.VelaHints", true, loader);
        Object inst = hints.getField("INSTANCE").get(null);
        Class<?> callCls = Class.forName("dev.vela.plugin.VelaCall", true, loader);
        Class<?> names = Class.forName("dev.vela.plugin.VelaNamesKt", true, loader);
        Class<?> completion = Class.forName("dev.vela.plugin.VelaCompletionKt", true, loader);
        Class<?> symbolCls = Class.forName("dev.vela.plugin.VelaSymbol", true, loader);

        Method callTemplate = hints.getMethod("callTemplate", CharSequence.class, int.class, boolean.class);
        Method hintsCallAt = hints.getMethod("callAt", CharSequence.class, int.class);
        Method callAt = completion.getMethod("callAt", CharSequence.class, int.class);
        Method resolveCall = names.getMethod("resolveCall", CharSequence.class, callCls);
        Method parameterNames = hints.getMethod("parameterNames", symbolCls);

        String comp = "struct Vec2 {\n"
                + "    def dot(self: Vec2, o: Vec2) -> float { return 0.0 }\n"
                + "    def scale(mut self: Vec2, k: float) -> None { return }\n"
                + "}\n"
                + "pure def fib(n: int) -> int { return n }\n"
                + "def main() -> None { return }\n";

        for (String at : new String[]{"p.dot(", "p.scale(", "fib(", "main(", "concat(", "w("}) {
            String text = at.startsWith("w") ? "def w(a: int, b: int, c: int, d: int, e: int) -> int { return a }\n" : comp;
            int idx = text.indexOf(at);
            if (idx < 0) { System.out.println("==== " + at + " NOT PRESENT (probe bug)"); continue; }
            int off = idx + at.length();
            System.out.println("==== " + at + "  offset=" + off);
            System.out.println("     VelaHints.callAt(off)  -> " + describe(hintsCallAt.invoke(inst, text, off), callCls));
            System.out.println("     text.callAt(off)       -> " + describe(callAt.invoke(null, text, off), callCls));
            Object t = callTemplate.invoke(inst, text, off, false);
            System.out.println("     callTemplate           -> " + t);
        }

        // resolveCall on a hand-built VelaCall for `fib`, with the real open-paren offset
        int open = comp.indexOf("fib(") + 3;
        Object sample = callCls.getConstructor(String.class, String.class, int.class)
                .newInstance("fib", null, open);
        Object sym = resolveCall.invoke(null, comp, sample);
        System.out.println("hand-built VelaCall(fib, open=" + open + ") -> " + (sym == null ? "NULL"
                : symbolCls.getMethod("getDetail").invoke(sym)));
        if (sym != null) {
            System.out.println("   parameterNames -> " + parameterNames.invoke(inst, sym));
        }
    }

    private static String describe(Object call, Class<?> callCls) {
        if (call == null) return "null";
        try {
            return "name=" + callCls.getMethod("getName").invoke(call)
                    + " receiver=" + callCls.getMethod("getReceiver").invoke(call)
                    + " open=" + callCls.getMethod("getOpenParen").invoke(call);
        } catch (Exception e) {
            return "? " + e;
        }
    }
}
