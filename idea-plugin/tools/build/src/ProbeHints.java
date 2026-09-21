import java.lang.reflect.*;
import java.util.*;

/**
 * Headless probe for the two pure decisions in `VelaInlayHints.kt`, driven through
 * reflection so it needs nothing but the plugin's own classes:
 *
 *   VelaHints.parameterHints(CharSequence, int)          -> List<Pair<Int, String>>
 *   VelaHints.callTemplate(CharSequence, int, boolean)   -> VelaCallTemplate?
 *
 * `VelaHints` is a Kotlin `object`, so every call goes through its INSTANCE.
 *
 * Usage: ProbeHints <classes-dir> <sample.vel>
 */
public final class ProbeHints {

    static int passed = 0;
    static int failed = 0;
    /** The Kotlin `object VelaHints` receiver, set once in main. */
    static Object hintsInstance;
    static Method parameterHints;
    static Method callTemplate;
    static Class<?> templateCls;

    public static void main(String[] args) throws Exception {
        String classesDir = args[0];
        java.io.File dir = new java.io.File(classesDir);
        java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{dir.toURI().toURL()}, ProbeHints.class.getClassLoader());

        Class<?> hints = Class.forName("dev.vela.plugin.VelaHints", true, loader);
        hintsInstance = hints.getField("INSTANCE").get(null);
        parameterHints = hints.getMethod("parameterHints", CharSequence.class, int.class);
        callTemplate = hints.getMethod("callTemplate", CharSequence.class, int.class, boolean.class);
        templateCls = Class.forName("dev.vela.plugin.VelaCallTemplate", true, loader);

        System.out.println("== the pure API, as reflected ==");
        System.out.println("   receiver: " + hintsInstance.getClass().getName() + ".INSTANCE  (a Kotlin object)");
        System.out.println("   " + parameterHints);
        System.out.println("   " + callTemplate);

        String sample = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(args[1])), java.nio.charset.StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("== parameterHints(text, text.length()) on the task's sample ==");
        for (String line : sample.split("\n")) {
            System.out.println("   | " + line);
        }
        List<?> out = hints(sample, sample.length());
        System.out.println("   -> " + out.size() + " hint(s)");
        for (Object p : out) {
            kotlin.Pair<?, ?> pair = (kotlin.Pair<?, ?>) p;
            int off = (Integer) pair.getFirst();
            System.out.printf("      @%-4d %-8s  on: %s%n",
                    off, "\"" + pair.getSecond() + "\"", context(sample, off));
        }

        System.out.println();
        System.out.println("== the decisions that must hold ==");

        // print(p.dot(p)): the inner call resolves; print is variadic.
        expect(out, sample, "p.dot(p)", "o: ", "the receiver is `p`, so the one argument is `o`, not self");
        check(out.stream().noneMatch(p -> context(sample, off(p)).contains("print(p.dot")
                        && ((kotlin.Pair<?, ?>) p).getSecond().equals("self: ")),
                "no `self:` hint is ever offered at a method call");
        check(out.stream().noneMatch(p -> context(sample, off(p)).startsWith("print(concat")
                        || context(sample, off(p)).startsWith("print(fib")),
                "print's own arguments get no hint: a variadic slot is not a parameter");

        // concat("a", "b") -> a: and b:
        expect(out, sample, "concat(\"a\", \"b\")", "a: ", "first parameter of concat");
        expect(out, sample, "concat(\"a\", \"b\")", "b: ", "second parameter of concat");

        // Vec2(3.0, 4.0): a struct is not a call the model resolves.
        check(out.stream().noneMatch(p -> context(sample, off(p)).contains("Vec2(3.0, 4.0)")),
                "a struct name resolves to no callable, so no hint is invented for Vec2(3.0, 4.0)");

        // fib(n - 1) inside fib's own body, and fib(10) at the call site.
        expectAll(out, sample, "fib(n - 1)", "n: ", "the file's own function, one parameter");
        expectAll(out, sample, "fib(10)", "n: ", "fib(10) names `n`");
        check(out.stream().noneMatch(p -> {
            String c = context(sample, off(p));
            return c.contains("def fib(") || c.contains("def dot(") || c.contains("def main(");
        }), "a declaration's own parameter list is never read as a call");

        // A comment and a string are not calls.
        check(out.stream().noneMatch(p -> {
            String c = context(sample, off(p));
            return c.startsWith("#") || c.contains("\"text with");
        }), "neither a comment nor a string literal produces a hint");

        // No hint when the argument already is the parameter's own name.
        String same = "pure def p(x: int) -> int { return x }\n"
                + "def main() -> None { print(p(x)) }\n";
        check(hints(same, same.length()).isEmpty(),
                "`p(x)` where the parameter is called `x` gets no hint (got " + hints(same, same.length()) + ")");

        // An unresolved callee produces nothing.
        String unknown = "def main() -> None { nosuchthing(1, 2) }\n";
        check(hints(unknown, unknown.length()).isEmpty(),
                "a call to an unresolved name produces no hint (got " + hints(unknown, unknown.length()) + ")");

        // A free function whose first parameter is *named* self does write it.
        String freeSelf = "def write_c(self: str, path: str, cfile: str) -> None { return }\n"
                + "def main() -> None { write_c(a, b, c) }\n";
        check(labels(hints(freeSelf, freeSelf.length())).equals(List.of("self: ", "path: ", "cfile: ")),
                "a free function with a `self` parameter names all three (got "
                        + labels(hints(freeSelf, freeSelf.length())) + ")");

        // A hole gets no hint; a comma in a string is not a separator; a comma in a
        // nested call belongs to the nested call.
        String holes = "def f(a: int, b: int) -> None { return }\n"
                + "def main() -> None { f(1, ) }\n";
        check(labels(hints(holes, holes.length())).equals(List.of("a: ")),
                "an omitted argument gets no hint (got " + labels(hints(holes, holes.length())) + ")");

        String nested = "def g(x: int, y: int) -> int { return x }\n"
                + "def f(a: int, b: str) -> int { return a }\n"
                + "def main() -> None { f(g(1, 2), \"x, y\") }\n";
        check(labels(hints(nested, nested.length())).equals(List.of("x: ", "y: ", "a: ", "b: ")),
                "a nested call and a comma inside a string (got "
                        + labels(hints(nested, nested.length())) + ")");

        // `offset` limits the work: a caret before the call produces nothing for it.
        int craft = sample.indexOf("p.dot(p)");
        check(hints(sample, craft - 1).isEmpty(),
                "a caret before the call produces no hint for it (got "
                        + hints(sample, craft - 1) + ")");

        System.out.println();
        System.out.println("== callTemplate: what completion inserts ==");
        String comp = "struct Vec2 {\n"
                + "    def dot(self: Vec2, o: Vec2) -> float { return 0.0 }\n"
                + "    def scale(mut self: Vec2, k: float) -> None { return }\n"
                + "}\n"
                + "pure def fib(n: int) -> int { return n }\n"
                + "def main() -> None { return }\n";
        checkTemplate(comp, "    p.dot(", "(o)", 1, 2, "method: receiver written, so only `o`");
        checkTemplate(comp, "    p.scale(", "(k)", 1, 2, "method with `mut self`: only `k`");
        checkTemplate(comp, "    fib(", "(n)", 1, 2, "free function, one parameter: `n` selected");
        checkTemplate(comp, "    main(", "()", 1, 1, "no parameters: `()` with the caret inside");
        checkTemplate(comp, "    concat(", "(a, b)", 1, 5, "builtin, two parameters: the list is selected");
        checkTemplate(comp, "    pow(", "(b, e)", 1, 5, "builtin, two parameters");
        checkTemplate(comp, "    print(", "()", 1, 1, "variadic builtin: no name to insert");
        checkTemplate(comp, "    Vec2(", null, 0, 0, "a struct name is NOT turned into a call");
        checkTemplate(comp, "    nosuch(", null, 0, 0, "an unresolved name is NOT turned into a call");
        String wide = "def w(a: int, b: int, c: int, d: int, e: int) -> int { return a }\n";
        checkTemplate(wide, "    w(", "(a, b, c, d, e)", 1, 16, "five parameters: whole list selected");
        checkTemplate(comp, "    fib(", true, "( n )", 2, 3, "argumentsSurroundingSpace=true keeps the gap");

        System.out.println();
        System.out.printf("RESULT: %d passed, %d failed%n", passed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static List<?> hints(String text, int offset) throws Exception {
        return (List<?>) parameterHints.invoke(hintsInstance, text, offset);
    }

    private static int off(Object pair) {
        return (Integer) ((kotlin.Pair<?, ?>) pair).getFirst();
    }

    private static void checkTemplate(String text, String at, String wantText, int wantStart,
                                      int wantEnd, String why) throws Exception {
        checkTemplate(text, at, false, wantText, wantStart, wantEnd, why);
    }

    private static void checkTemplate(String text, String at, boolean spaced, String wantText,
                                      int wantStart, int wantEnd, String why) throws Exception {
        int offset = text.indexOf(at) + at.length();
        Object t = callTemplate.invoke(hintsInstance, text, offset, spaced);
        if (wantText == null) {
            check(t == null, "callTemplate(\"" + at + "\") is null: " + why);
            return;
        }
        if (t == null) {
            check(false, "callTemplate(\"" + at + "\") was null, expected " + wantText + ": " + why);
            return;
        }
        String gotText = (String) templateCls.getMethod("getText").invoke(t);
        int gotStart = (Integer) templateCls.getMethod("getCaretStart").invoke(t);
        int gotEnd = (Integer) templateCls.getMethod("getCaretEnd").invoke(t);
        boolean ok = gotText.equals(wantText) && gotStart == wantStart && gotEnd == wantEnd;
        check(ok, at + " -> " + gotText + "[" + gotStart + "," + gotEnd + ")"
                + (ok ? "" : "  expected " + wantText + "[" + wantStart + "," + wantEnd + ")")
                + "  (" + why + ")");
        if (ok && gotEnd <= gotText.length()) {
            System.out.println("        inserted text \"" + gotText + "\", selection \"" + gotText.substring(gotStart, gotEnd) + "\"");
        }
    }

    private static List<String> labels(List<?> pairs) {
        List<String> out = new ArrayList<>();
        for (Object p : pairs) {
            out.add((String) ((kotlin.Pair<?, ?>) p).getSecond());
        }
        return out;
    }

    private static void expect(List<?> pairs, String text, String near, String label, String why) {
        for (Object p : pairs) {
            if (context(text, off(p)).contains(near) && ((kotlin.Pair<?, ?>) p).getSecond().equals(label)) {
                check(true, "hint \"" + label + "\" at " + near + "  (" + why + ")");
                return;
            }
        }
        check(false, "expected hint \"" + label + "\" at " + near + "  (" + why + ")");
    }

    private static void expectAll(List<?> pairs, String text, String near, String label, String why) {
        for (Object p : pairs) {
            if (context(text, off(p)).contains(near) && ((kotlin.Pair<?, ?>) p).getSecond().equals(label)) {
                check(true, "hint \"" + label + "\" at " + near + "  (" + why + ")");
                return;
            }
        }
        check(false, "expected hint \"" + label + "\" at " + near + "  (" + why + ")");
    }

    /** The line a hint sits on. */
    private static String context(String text, int offset) {
        int start = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int end = text.indexOf('\n', offset);
        if (end < 0) end = text.length();
        return text.substring(start, end).trim();
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
            System.out.println("   OK   " + what);
        } else {
            failed++;
            System.out.println("   FAIL " + what);
        }
    }
}
