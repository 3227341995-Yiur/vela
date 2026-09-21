import dev.vela.plugin.VelaModel;
import dev.vela.plugin.VelaSymbol;

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
 * The differential for the declaration model: does reading the tree give the same
 * answer as the token scan it replaced?
 *
 * `VelaModel.symbols` is what the structure view, completion, go-to-declaration,
 * parameter info, the documentation provider and the parameter-name inlay hints all
 * read.  Those six features used to be fed by a hand-written token scan that
 * reconstructed declarations by counting braces; they are now fed by the plugin's
 * parser, whose tree `ast-diff.ps1` holds to `vm.exe parse`.
 *
 * A conversion like that has to be *measured*, because the interesting cases are
 * exactly the ones the old scan guessed at: a nested `def` in a function body, a
 * struct field written `mut x: T`, a field whose type is `Array[int, 4]`, a
 * parameter list written over several lines, and a file that is only half typed.
 * So this runs both implementations over every file in the corpus and prints:
 *
 *   * files where the two lists are identical, symbol for symbol;
 *   * files where they differ, with the first difference in full -- and, because a
 *     difference is only interesting if the tree is *right*, the file's name is
 *     printed either way.  A difference is not a failure here: it is the finding.
 *
 * `vm.exe` is not involved: the question is where the *plugin's* model comes from,
 * not what the compiler thinks.
 *
 *   java -cp <plugin classes> SymbolDiff <repo-root> [--single <file>]
 */
public final class SymbolDiff {

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private final List<String> corpus = new ArrayList<>();
    private Path repoRoot;
    private String single;
    private boolean verbose;

    public static void main(String[] args) throws Exception {
        SymbolDiff tool = new SymbolDiff();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--single")) {
                tool.single = args[++i];
            } else if (a.equals("--verbose")) {
                tool.verbose = true;
            } else {
                rest.add(a);
            }
        }
        Path root = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        tool.run(root);
    }

    private void run(Path root) throws Exception {
        repoRoot = root;
        if (single != null) {
            corpus.add(single.replace('\\', '/'));
        } else {
            collectCorpus();
        }
        System.out.println("model    : VelaModel.symbols (tree) vs VelaModel.referenceSymbols (token scan)");
        System.out.println("comparison: the two lists, symbol for symbol: kind, name, detail, type,"
                + " line, parent");
        System.out.println("a difference is reported, not failed: the tree is supposed to be right"
                + " where they disagree");
        System.out.println();

        int same = 0;
        int differ = 0;
        int bothEmpty = 0;
        int threw = 0;
        int absent = 0;
        int tooLarge = 0;
        long symbols = 0;
        StringBuilder table = new StringBuilder(8192);
        StringBuilder detail = new StringBuilder(32768);
        table.append(pad("file", 58)).append(pad("tree", 6)).append(pad("scan", 6)).append("  verdict\n");
        table.append("-".repeat(58)).append("  ").append("-".repeat(4)).append("  ")
                .append("-".repeat(4)).append("  ").append("-".repeat(34)).append('\n');

        for (String rel : corpus) {
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p)) {
                // Counted, not silently dropped: an unreadable corpus entry is a
                // reason a file was not compared, and the coverage line has to name it.
                absent++;
                table.append(pad(rel, 58)).append("  MISSING (not on disk)\n");
                continue;
            }
            if (Files.size(p) > MAX_FILE_BYTES) {
                tooLarge++;
                table.append(pad(rel, 58)).append("  skipped (too large)\n");
                continue;
            }
            String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            List<VelaSymbol> mine;
            List<VelaSymbol> theirs;
            try {
                mine = VelaModel.INSTANCE.symbols(text);
                theirs = VelaModel.INSTANCE.referenceSymbols(text);
            } catch (Throwable t) {
                // A thrown exception is its own category.  It used to be counted as
                // `differ`, which made a crash indistinguishable from a disagreement --
                // the two need different work done about them.
                threw++;
                table.append(pad(rel, 58)).append("  THREW ").append(t).append('\n');
                detail.append(rel).append(": THREW ").append(t).append('\n');
                continue;
            }
            String verdict;
            if (describe(mine).equals(describe(theirs))) {
                same++;
                symbols += mine.size();
                if (mine.isEmpty()) bothEmpty++;
                verdict = mine.isEmpty() ? "identical (both empty)" : "identical";
            } else {
                differ++;
                verdict = "DIFFERS";
                int n = Math.max(mine.size(), theirs.size());
                for (int i = 0; i < n; i++) {
                    String a = i < mine.size() ? describe(mine.get(i)) : "<end of list>";
                    String b = i < theirs.size() ? describe(theirs.get(i)) : "<end of list>";
                    if (!a.equals(b)) {
                        detail.append("  ").append(rel).append(": symbol ").append(i).append('\n')
                              .append("      from the tree : ").append(a).append('\n')
                              .append("      token scan    : ").append(b).append('\n');
                        break;
                    }
                }
            }
            table.append(pad(rel, 58)).append(pad(String.valueOf(mine.size()), 6))
                    .append(pad(String.valueOf(theirs.size()), 6)).append("  ").append(verdict)
                    .append('\n');
        }

        System.out.println("== corpus ==");
        System.out.println(table);
        System.out.println("== totals ==");
        System.out.println("  files compared            : " + (same + differ));
        System.out.println("  identical symbol lists    : " + same + " (of which both empty: " + bothEmpty + ")");
        System.out.println("  different                 : " + differ);
        System.out.println("  symbols in the identical ones: " + symbols);
        System.out.println();
        System.out.println("== every difference, in full ==");
        System.out.println(detail.length() == 0 ? "  (none)" : detail.toString());
        System.out.println("VERDICT: " + (differ == 0 && threw == 0
                ? "the tree-derived model is identical to the token scan over this corpus"
                : differ + " file(s) differ and " + threw + " threw; each is listed above"
                        + " with both sides"));
        Coverage cov = new Coverage()
                .defectCategory("threw")
                .defectCategory("missing-corpus-file")
                .category("too-large")
                .ran(same)
                .skipped("threw", threw)
                .skipped("missing-corpus-file", absent)
                .skipped("too-large", tooLarge)
                .wrong(differ);
        cov.print();
        // A diff or a crash both have to be visible in the exit code; this tool exited
        // 0 unconditionally, so its `VERDICT: ... differ` was a sentence no build could
        // act on.
        System.exit(differ > 0 ? 1 : (cov.hasDefect() ? 3 : 0));
    }

    /** One symbol as a single line, so the two lists can be compared textually. */
    private static String describe(List<VelaSymbol> list) {
        StringBuilder sb = new StringBuilder(256);
        for (VelaSymbol s : list) sb.append(describe(s)).append('\n');
        return sb.toString();
    }

    private static String describe(VelaSymbol s) {
        return s.getKind() + " `" + s.getName() + "` detail=`" + s.getDetail() + "` type=`"
                + s.getType() + "` line=" + s.getLine() + " parent=" + s.getParent();
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
            for (Path f : found) seen.add(rel(f));
        }
        corpus.addAll(seen);
    }

    private String rel(Path p) {
        return repoRoot.relativize(p).toString().replace('\\', '/');
    }

    private static String pad(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private SymbolDiff() {
    }
}
