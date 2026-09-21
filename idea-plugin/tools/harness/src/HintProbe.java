import dev.vela.plugin.VelaHints;
import dev.vela.plugin.VelaSyntaxNode;
import dev.vela.plugin.VelaSyntaxParser;
import dev.vela.plugin.VelaSyntaxTree;
import dev.vela.plugin.VelaNodeKind;

import java.io.IOException;
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
 * Where do the parameter hints and the *declared* parameters disagree?
 *
 * The complaint was "a call site shows a run of `s: s: s:`".  This makes that a
 * measurement: for every call in the corpus it prints the callee, the parameter
 * names the plugin's parser found *declared*, and the names the hint engine drew,
 * and it reports every place where the two disagree.
 *
 *   java -cp <plugin classes> HintProbe <repo-root> [--single <file>] [--all]
 */
public final class HintProbe {

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private final List<String> corpus = new ArrayList<>();
    private Path repoRoot;
    private String single;
    private boolean all;

    public static void main(String[] args) throws Exception {
        HintProbe tool = new HintProbe();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--single")) {
                tool.single = args[++i];
            } else if (a.equals("--all")) {
                tool.all = true;
            } else {
                rest.add(a);
            }
        }
        tool.repoRoot = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        if (tool.single != null) tool.corpus.add(tool.single.replace('\\', '/'));
        else tool.collectCorpus();
        tool.run();
    }

    private void run() throws IOException {
        System.out.println("hints    : VelaHints.parameterHints(text, text.length) -- every call in the file");
        System.out.println("declared : the parameter names in the *parser's* tree for that callee");
        System.out.println();
        long calls = 0;
        long multi = 0;
        long mismatches = 0;
        long unreadable = 0;
        List<String> findings = new ArrayList<>();
        List<String> table = new ArrayList<>();
        table.add(pad("file", 44) + "  " + pad("callee", 16) + "  " + pad("declared", 34)
                + "  hints drawn");
        table.add("-".repeat(44) + "  " + "-".repeat(16) + "  " + "-".repeat(34) + "  "
                + "-".repeat(30));
        for (String rel : corpus) {
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p) || Files.size(p) > MAX_FILE_BYTES) continue;
            String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            VelaSyntaxTree tree = VelaSyntaxParser.parse(text);
            List<Pair> hints;
            try {
                hints = new ArrayList<>();
                for (Object o : VelaHints.INSTANCE.parameterHints(text, text.length())) {
                    // kotlin.Pair, so read it reflectively: the harness has no Kotlin
                    // compile-time dependency on purpose (it is plain Java on the JDK
                    // that build-offline.ps1 uses).
                    Integer off = (Integer) o.getClass().getMethod("getFirst").invoke(o);
                    String label = (String) o.getClass().getMethod("getSecond").invoke(o);
                    hints.add(new Pair(off, label));
                }
            } catch (Throwable t) {
                findings.add(rel + ": parameterHints THREW " + t);
                continue;
            }
            // Group the hints by the call they belong to: a call is the `CALL` node
            // whose span contains the argument offset.
            List<VelaSyntaxNode> callsList = new ArrayList<>();
            collectCalls(tree.root, callsList);
            for (VelaSyntaxNode call : callsList) {
                List<VelaSyntaxNode> args = new ArrayList<>(call.children);
                if (args.size() < 2) continue;   // callee + at least one argument
                calls++;
                args.remove(0);                  // the callee
                if (args.size() < 2) continue;   // multi-argument calls are the complaint
                multi++;
                String callee = calleeName(tree, call);
                List<String> declaredNames = declaredParameters(tree, callee);
                List<String> drawn = new ArrayList<>();
                for (VelaSyntaxNode arg : args) {
                    int off = argStart(tree, arg);
                    for (Pair h : hints) {
                        if (h.offset == off) drawn.add(h.label);
                    }
                }
                if (drawn.isEmpty()) continue;
                if (!all && drawn.size() < 2) continue;
                String declared = declaredNames == null ? "(not declared in this file)" : String.join(",", declaredNames);
                String drawnNames = names(drawn);
                table.add(pad(rel, 44) + "  " + pad(callee, 16) + "  " + pad(declared, 34) + "  "
                        + drawnNames);
                if (declaredNames == null) {
                    unreadable++;
                    continue;
                }
                if (!drawnNames.equals(String.join(",", declaredNames))) {
                    mismatches++;
                    findings.add(rel + ": call `" + callee + "`  declared: " + declaredNames
                            + "  hints drawn: " + drawnNames
                            + "\n      call text: `" + snippet(text, call, tree) + "`");
                }
            }
        }
        System.out.println("== every multi-argument call ==");
        for (String line : table) System.out.println(line);
        System.out.println();
        System.out.println("== where the hint names are not the declared names ==");
        if (findings.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (String f : findings) System.out.println("  " + f);
        }
        System.out.println();
        System.out.println("== totals ==");
        System.out.println("  calls seen                      : " + calls);
        System.out.println("  multi-argument calls            : " + multi);
        System.out.println("  callee not declared in its file : " + unreadable
                + " (builtin or cross-file; not judged here)");
        System.out.println("  hints drawn from the wrong name : " + mismatches);
        System.exit(mismatches == 0 ? 0 : 1);
    }

    private static final class Pair {
        final int offset;
        final String label;

        Pair(int offset, String label) {
            this.offset = offset;
            this.label = label;
        }
    }

    private static String names(List<String> drawn) {
        List<String> out = new ArrayList<>();
        for (String label : drawn) {
            String stripped = label.trim();
            if (stripped.endsWith(":")) stripped = stripped.substring(0, stripped.length() - 1).trim();
            out.add(stripped);
        }
        return String.join(",", out);
    }

    private static void collectCalls(VelaSyntaxNode n, List<VelaSyntaxNode> out) {
        if (n.kind == VelaNodeKind.CALL) out.add(n);
        for (VelaSyntaxNode c : n.children) collectCalls(c, out);
    }

    private static String calleeName(VelaSyntaxTree tree, VelaSyntaxNode call) {
        if (call.children.isEmpty()) return "?";
        VelaSyntaxNode callee = call.children.get(0);
        if (callee.kind == VelaNodeKind.NAME) return callee.name;
        if (callee.kind == VelaNodeKind.ATTR) return callee.name;
        return "?";
    }

    /** The declared parameter names for a callee declared in this file, or null. */
    private static List<String> declaredParameters(VelaSyntaxTree tree, String callee) {
        List<VelaSyntaxNode> defs = new ArrayList<>();
        collectDefs(tree.root, defs);
        for (VelaSyntaxNode def : defs) {
            if (!def.name.equals(callee)) continue;
            List<String> names = new ArrayList<>();
            for (VelaSyntaxNode c : def.children) {
                if (c.kind == VelaNodeKind.PARAM) names.add(c.name);
            }
            return names;
        }
        return null;
    }

    private static void collectDefs(VelaSyntaxNode n, List<VelaSyntaxNode> out) {
        if (n.kind == VelaNodeKind.DEF) out.add(n);
        for (VelaSyntaxNode c : n.children) collectDefs(c, out);
    }

    private static int argStart(VelaSyntaxTree tree, VelaSyntaxNode arg) {
        int i = arg.startTok;
        if (i >= 0 && i < tree.toks.size()) return tree.toks.get(i).start;
        return -1;
    }

    private static String snippet(String text, VelaSyntaxNode n, VelaSyntaxTree tree) {
        int from = n.startTok >= 0 ? tree.toks.get(n.startTok).start : 0;
        int to = n.endTok >= 0 && n.endTok < tree.toks.size() ? tree.toks.get(n.endTok).end : from;
        if (from < 0 || to > text.length() || to < from) return "?";
        String s = text.substring(from, to).replace("\n", "\\n");
        return s.length() > 70 ? s.substring(0, 70) + "..." : s;
    }

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
            for (Path f : found) {
                seen.add(repoRoot.relativize(f).toString().replace('\\', '/'));
            }
        }
        corpus.addAll(seen);
    }

    private static String pad(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private HintProbe() {
    }
}
