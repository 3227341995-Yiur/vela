import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The oracle for "what does this declaration name its arguments?": a reader of the
 * *declaration text*, written here in the harness, plus `SPEC.md` for a name the file
 * does not declare.
 *
 * WHY IT IS NOT `VelaTargets`
 *
 * The defect this round is about lives in the plugin's own reader of parameter names,
 * and a harness that asks `VelaTargets` for the expected answer is asking the accused
 * for the verdict: when that reader is wrong, expectation and measurement are wrong
 * together and the run prints `wrong 0`.  `HintProbe` is exactly that shape, which is
 * why it stayed green through the reported bug.  So the expected names come from a
 * scanner that shares no line of code with the plugin -- the same reason `GotoOracle`
 * asks `vm.exe` instead of `VelaModel`.
 *
 * WHAT A DECLARATION'S PARAMETERS ARE HERE
 *
 * The text between the `(` and the `)` that closes it, split on the commas that belong
 * to the parameter list (a comma inside `Array[int, 4]` does not split it), each entry
 * `[mut] name: T`.  An entry with no `:` answers **null** for the whole list rather
 * than a name: the compiler refuses an unannotated parameter (`expected ':'`), the
 * parser records no `param` node for it, and a name taken from that text is a name no
 * declaration gives.  That null is the difference between this oracle and `detail`-text
 * splitting, and it is the shape the owner reported.
 */
public final class DeclReader {

    private DeclReader() {
    }

    /** A declaration found in the source text, with the parameter names it writes. */
    public static final class Decl {
        public final String name;
        public final boolean isMethod;
        /** The parameter names, or null when the list cannot be read (`s` with no type). */
        public final List<String> params;
        public final String signature;
        public final int line;
        public final int nameStart;

        Decl(String name, boolean isMethod, List<String> params, String signature, int line,
             int nameStart) {
            this.name = name;
            this.isMethod = isMethod;
            this.params = params;
            this.signature = signature;
            this.line = line;
            this.nameStart = nameStart;
        }

        @Override
        public String toString() {
            return (isMethod ? "method " : "def ") + name + " line " + line
                    + " params " + (params == null ? "(cannot be read)" : params);
        }
    }

    /**
     * Every `def` in the text, with the parameter list read from the declaration's own
     * characters.  `isMethod` is true when the `def` sits inside a `struct` body --
     * which is what makes its first parameter the receiver.
     */
    public static List<Decl> declarations(String text) {
        List<Decl> out = new ArrayList<>();
        Deque<Boolean> scopes = new ArrayDeque<>();
        boolean pendingStruct = false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = endOfString(text, i);
                continue;
            }
            if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '{') {
                scopes.push(pendingStruct);
                pendingStruct = false;
                i++;
                continue;
            }
            if (c == '}') {
                if (!scopes.isEmpty()) scopes.pop();
                i++;
                continue;
            }
            if (!isNamePart(c)) {
                i++;
                continue;
            }
            int k = i;
            while (i < text.length() && isNamePart(text.charAt(i))) i++;
            String word = text.substring(k, i);
            if (word.equals("struct")) {
                int j = i;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                int n = j;
                while (n < text.length() && isNamePart(text.charAt(n))) n++;
                if (n > j) pendingStruct = true;
                continue;
            }
            if (!word.equals("def")) continue;

            int j = i;
            while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
            int n = j;
            while (n < text.length() && isNamePart(text.charAt(n))) n++;
            if (n == j) continue;
            String name = text.substring(j, n);
            int nameStart = j;
            while (n < text.length() && Character.isWhitespace(text.charAt(n))) n++;
            String signature = "def " + name;
            List<String> params = null;
            if (n < text.length() && text.charAt(n) == '(') {
                int close = matchingParen(text, n);
                if (close > n && close < text.length()) {
                    signature = text.substring(nameStart, close + 1)
                            .replace('\n', ' ').replaceAll(" +", " ");
                    params = parameterNames(text.substring(n + 1, close));
                }
            }
            out.add(new Decl(name, scopes.contains(Boolean.TRUE), params, signature,
                    lineOf(text, nameStart), nameStart));
            i = n;
        }
        return out;
    }

    /**
     * The declaration a call names: a method when a receiver is written, a module-level
     * `def` otherwise, matched on the name and -- when the caller knows it -- the line,
     * so two same-named declarations cannot be confused for each other.
     */
    public static Decl pick(List<Decl> decls, String name, String receiver, int line) {
        Decl moduleLevel = null;
        Decl method = null;
        for (Decl d : decls) {
            if (name == null || !d.name.equals(name)) continue;
            if (d.isMethod) {
                if (method == null) method = d;
            } else if (moduleLevel == null) {
                moduleLevel = d;
            }
        }
        if (line > 0) {
            for (Decl d : decls) {
                if (d.name.equals(name) && d.line == line) return d;
            }
        }
        if (receiver != null) return method != null ? method : moduleLevel;
        return moduleLevel != null ? moduleLevel : method;
    }

    /**
     * The names a parameter list writes, or null when it cannot be read: an entry
     * without a `:`, an empty entry, or a name that is not an identifier each answer
     * null for the whole list, never a guess.
     */
    public static List<String> parameterNames(String inner) {
        // `def main() -> None`: an empty parameter list declares no parameters, which is
        // a fact about the declaration and not an unreadable list.
        if (inner.trim().isEmpty()) return new ArrayList<>();
        List<String> entries = topLevelCommaSplit(inner);
        List<String> out = new ArrayList<>();
        for (String raw : entries) {
            String entry = raw.trim();
            if (entry.isEmpty()) return null;
            if (entry.startsWith("mut ")) entry = entry.substring(4).trim();
            int colon = entry.indexOf(':');
            if (colon < 0) return null;
            String name = entry.substring(0, colon).trim();
            if (name.isEmpty()) return null;
            for (int i = 0; i < name.length(); i++) {
                if (!isNamePart(name.charAt(i))) return null;
            }
            out.add(name);
        }
        return out;
    }

    /** The text of [inner] split on the commas outside `(`/`[`/strings. */
    public static List<String> topLevelCommaSplit(String inner) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\'' || c == '"') {
                int end = endOfString(inner, i);
                current.append(inner, i, Math.min(end, inner.length()));
                i = end - 1;
                continue;
            }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            if (c == ',' && depth == 0) {
                out.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        out.add(current.toString());
        return out;
    }

    // ------------------------------------------------------------------ text

    public static int matchingParen(String text, int open) {
        int depth = 0;
        int i = open;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                i = endOfString(text, i);
                continue;
            }
            if (c == '#') {
                while (i < text.length() && text.charAt(i) != '\n') i++;
            } else if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return -1;
    }

    public static int endOfString(String text, int start) {
        char closing = text.charAt(start);
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (c == closing || c == '\n') return i + 1;
            i++;
        }
        return text.length();
    }

    public static boolean isNamePart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }

    /** 1-based line number of an offset, the numbering `VelaSymbol.line` uses. */
    public static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    // ------------------------------------------------------------------ SPEC.md

    /**
     * The parameter names `SPEC.md` section 8 writes for each builtin, which is the
     * only declaration a builtin has.  A row that documents the arity without the names
     * (`pow`, `to_float`, `min_int`, `emit_*`) is deliberately absent: there is no name
     * to check a label against, and inventing one is the defect.
     */
    public static Map<String, List<String>> specBuiltinNames(Path repoRoot) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Path spec = repoRoot.resolve("SPEC.md");
        if (!Files.isRegularFile(spec)) return out;
        List<String> lines;
        try {
            lines = Files.readAllLines(spec, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return out;
        }
        boolean inSection = false;
        for (String line : lines) {
            if (line.startsWith("## 8. Built-in functions")) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("## ")) break;
            if (!inSection || !line.startsWith("|") || !line.contains("`")) continue;
            String[] cells = line.split("\\|", -1);
            if (cells.length < 2) continue;
            String first = cells[1].trim();
            if (first.isEmpty() || first.equalsIgnoreCase("builtin") || first.startsWith("---")) continue;
            for (String token : first.split(",")) {
                token = token.replace("`", "").trim();
                if (token.isEmpty()) continue;
                for (String one : token.split("/")) {
                    one = one.trim();
                    if (one.isEmpty()) continue;
                    int open = one.indexOf('(');
                    if (open < 0 || !one.endsWith(")")) continue;   // arity only, no names
                    String name = one.substring(0, open).trim();
                    String inner = one.substring(open + 1, one.length() - 1).trim();
                    if (name.isEmpty() || inner.equals("...")) continue;
                    List<String> params = new ArrayList<>();
                    if (!inner.isEmpty()) {
                        for (String p : inner.split(",")) params.add(p.trim());
                    }
                    out.put(name, params);
                }
            }
        }
        return out;
    }

    public static Path repoRootOf(String arg) {
        return Paths.get(arg).toAbsolutePath();
    }
}
