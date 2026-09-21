import dev.vela.plugin.VelaFold;
import dev.vela.plugin.VelaFoldingKt;

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
 * The differential for folding: the ranges now read from the tree against the
 * ranges the brace matcher produced.
 *
 * Folding used to find `{`/`}` on the token stream and pair them with a stack, and
 * its comment said the tree was flat so there was no node whose range could be the
 * fold region.  There is one now: a `VELA_BLOCK` element covers its braces, so the
 * region is the node's own range.  That conversion has to be measured, because
 * folding is the one feature where a wrong range is *visible* -- a region that is
 * one character short eats a brace, and a region that is one long eats a line.
 *
 * This runs both pure functions over every file in the corpus and reports:
 *
 *   * files where the region lists are identical (offset, end, placeholder);
 *   * files where they differ, with the first difference in full, and how many
 *     regions each produced.
 *
 * A difference is a finding, not a failure: where the two disagree, one of them is
 * wrong, and the report says which by showing both.
 *
 *   java -cp <plugin classes> FoldDiff <repo-root> [--verbose]
 */
public final class FoldDiff {

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private final List<String> corpus = new ArrayList<>();
    private Path repoRoot;

    public static void main(String[] args) throws Exception {
        FoldDiff tool = new FoldDiff();
        List<String> rest = new ArrayList<>();
        for (String a : args) rest.add(a);
        Path root = rest.isEmpty() ? Paths.get("").toAbsolutePath()
                : Paths.get(rest.get(0)).toAbsolutePath();
        tool.repoRoot = root;
        tool.collectCorpus();
        tool.run();
    }

    private void run() throws IOException {
        System.out.println("model    : velaFoldRanges (tree) vs velaFoldRangesReference (brace matching)");
        System.out.println("comparison: the region lists in order -- start, end, placeholder text");
        System.out.println();
        int same = 0;
        int differ = 0;
        int regions = 0;
        int absent = 0;
        int tooLarge = 0;
        int threw = 0;
        StringBuilder table = new StringBuilder(8192);
        StringBuilder detail = new StringBuilder(16384);
        table.append(pad("file", 58)).append(pad("tree", 6)).append(pad("brace", 6)).append("  verdict\n");
        table.append("-".repeat(58)).append("  ").append("-".repeat(4)).append("  ")
                .append("-".repeat(4)).append("  ").append("-".repeat(30)).append('\n');
        for (String rel : corpus) {
            Path p = repoRoot.resolve(rel);
            if (!Files.isRegularFile(p)) {
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
            List<VelaFold> mine;
            List<VelaFold> theirs;
            try {
                mine = VelaFoldingKt.velaFoldRanges(text);
                theirs = VelaFoldingKt.velaFoldRangesReference(text);
            } catch (Throwable t) {
                // A crash is not a disagreement: they are counted apart because they
                // need different work done about them, and because the verdict below
                // must not read "0 different" over a file that threw.
                threw++;
                table.append(pad(rel, 58)).append("  THREW ").append(t).append('\n');
                detail.append(rel).append(": THREW ").append(t).append('\n');
                continue;
            }
            String verdict;
            if (describe(mine).equals(describe(theirs))) {
                same++;
                regions += mine.size();
                verdict = "identical";
            } else {
                differ++;
                verdict = "DIFFERS";
                int n = Math.max(mine.size(), theirs.size());
                for (int i = 0; i < n; i++) {
                    String a = i < mine.size() ? describe(mine.get(i)) : "<end of list>";
                    String b = i < theirs.size() ? describe(theirs.get(i)) : "<end of list>";
                    if (!a.equals(b)) {
                        detail.append("  ").append(rel).append(": region ").append(i).append('\n')
                              .append("      from the tree : ").append(a).append('\n')
                              .append("      brace matching: ").append(b).append('\n')
                              .append("      context around the tree's region: `")
                              .append(snippet(text, i < mine.size() ? mine.get(i) : null)).append("`\n");
                        if (i < mine.size() && i < theirs.size()) {
                            detail.append("      context around the brace one : `")
                                  .append(snippet(text, theirs.get(i))).append("`\n");
                        }
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
        System.out.println("  files compared               : " + (same + differ));
        System.out.println("  identical region lists       : " + same);
        System.out.println("  different                    : " + differ);
        System.out.println("  fold regions in the identical ones: " + regions);
        System.out.println();
        System.out.println("== every difference, in full ==");
        System.out.println(detail.length() == 0 ? "  (none)" : detail.toString());
        System.out.println("VERDICT: " + (differ == 0 && threw == 0
                ? "the tree's fold regions are the brace matcher's, file for file"
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
        System.exit(differ > 0 ? 1 : (cov.hasDefect() ? 3 : 0));
    }

    private static String describe(List<VelaFold> list) {
        StringBuilder sb = new StringBuilder(256);
        for (VelaFold f : list) sb.append(describe(f)).append('\n');
        return sb.toString();
    }

    private static String describe(VelaFold f) {
        return "[" + f.getStart() + "," + f.getEnd() + ") `" + f.getPlaceholder() + "`";
    }

    private static String snippet(String text, VelaFold f) {
        if (f == null) return "(none)";
        int from = Math.max(0, f.getStart() - 8);
        int to = Math.min(text.length(), f.getEnd() + 8);
        String s = text.substring(from, to).replace("\n", "\\n").replace("\r", "");
        if (s.length() > 90) s = s.substring(0, 90) + "...";
        return s;
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

    private FoldDiff() {
    }
}
