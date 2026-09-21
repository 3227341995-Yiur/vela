import java.lang.reflect.*;
import java.util.*;

/**
 * Walks the resolution chain the hint function walks, one step at a time, so a
 * zero-hint result says *which* link produced it rather than just "nothing".
 */
public final class ProbeChain {

    static Class<?> callCls;
    static Class<?> symbolCls;

    public static void main(String[] args) throws Exception {
        String classesDir = args[0];
        java.io.File dir = new java.io.File(classesDir);
        java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{dir.toURI().toURL()}, ProbeChain.class.getClassLoader());

        String text = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(args[1])), java.nio.charset.StandardCharsets.UTF_8);

        Class<?> names = Class.forName("dev.vela.plugin.VelaNamesKt", true, loader);
        Class<?> completion = Class.forName("dev.vela.plugin.VelaCompletionKt", true, loader);
        Class<?> model = Class.forName("dev.vela.plugin.VelaModel", true, loader);
        Class<?> hintsCls = Class.forName("dev.vela.plugin.VelaHints", true, loader);
        callCls = Class.forName("dev.vela.plugin.VelaCall", true, loader);
        symbolCls = Class.forName("dev.vela.plugin.VelaSymbol", true, loader);

        Object modelInst = model.getField("INSTANCE").get(null);
        Method symbols = model.getMethod("symbols", CharSequence.class);
        Object syms = symbols.invoke(modelInst, text);
        System.out.println("VelaModel.symbols -> " + ((List<?>) syms).size() + " symbol(s)");
        for (Object s : (List<?>) syms) {
            System.out.println("     " + symbolCls.getMethod("getKind").invoke(s) + " "
                    + symbolCls.getMethod("getName").invoke(s)
                    + "   detail=" + symbolCls.getMethod("getDetail").invoke(s));
        }

        Method callAt = completion.getMethod("callAt", CharSequence.class, int.class);
        Method resolveCall = names.getMethod("resolveCall", CharSequence.class, callCls);
        Object hintsInstance = hintsCls.getField("INSTANCE").get(null);
        Method parameterNames = hintsCls.getMethod("parameterNames", symbolCls);
        Method hintsCallAt = hintsCls.getMethod("callAt", CharSequence.class, int.class);

        System.out.println();
        System.out.println("per `(` in the sample:");
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '(') continue;
            String line = line(text, i);
            Object call = callAt.invoke(null, text, i + 1);
            System.out.println("   @(" + i + "  on: " + line);
            System.out.println("        callAt      -> " + describe(call));
            if (call == null) continue;
            int openParen = (Integer) callCls.getMethod("getOpenParen").invoke(call);
            if (openParen != i) {
                System.out.println("        (the innermost open `(` is " + openParen + ", so this is not it)");
                continue;
            }
            Object viaHints = hintsCallAt.invoke(hintsInstance, text, i + 1);
            System.out.println("        VelaHints.callAt -> " + describe(viaHints));
            Object sym = resolveCall.invoke(null, text, call);
            System.out.println("        resolveCall -> " + (sym == null ? "NULL"
                    : symbolCls.getMethod("getDetail").invoke(sym)));
            if (sym != null) {
                System.out.println("        parameterNames -> " + parameterNames.invoke(hintsInstance, sym));
            }
        }
    }

    private static String describe(Object call) {
        if (call == null) return "null";
        try {
            return "name=" + callCls.getMethod("getName").invoke(call)
                    + " receiver=" + callCls.getMethod("getReceiver").invoke(call)
                    + " openParen=" + callCls.getMethod("getOpenParen").invoke(call);
        } catch (Exception e) {
            return "?" + e;
        }
    }

    private static String line(String text, int offset) {
        int start = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int end = text.indexOf('\n', offset);
        if (end < 0) end = text.length();
        return text.substring(start, end).trim();
    }
}
