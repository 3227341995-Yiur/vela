import dev.vela.plugin.VelaFold;
import dev.vela.plugin.VelaFoldingKt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * WHAT KIND OF DIFFERENCE IS A FOLD DIFFERENCE?  Measured, not asserted.
 *
 * `FoldDiff` reports "62 of 126 files differ" and that number cannot be read as 62
 * defects: neither the compiler nor any other authority defines a fold region list.
 * Before writing a criterion into the tool, this probe checks whether a criterion
 * *exists* -- that is, whether the differences fall into classes a rule can decide.
 *
 * For every (tree region, brace region) pair it computes the relation between the
 * two ranges:
 *
 *   nested   : one contains the other, both ends strictly inside -- "the tree folds
 *              more of the same construct" (the whole body vs one inner block)
 *   equal    : identical ranges
 *   disjoint : no overlap at all
 *   straddle : they overlap without containment -- the case a containment rule
 *              cannot decide
 *
 * It reports the counts per class, so the criterion written into `FoldDiff` is the
 * one the corpus supports rather than the one that was hoped for.
 *
 *   java -cp <plugin classes> FoldShapeProbe <repo-root>
 */
public final class FoldShapeProbe {

    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Paths.get(args[0]).toAbsolutePath()
                : Paths.get("").toAbsolutePath();
        Set<String> corpus = new LinkedHashSet<>();
        Path cases = root.resolve("tests").resolve("cases.txt");
        if (Files.isRegularFile(cases)) {
            for (String line : Files.readAllLines(cases, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] parts = t.split("\\s+");
                if (parts.length >= 3 && parts[2].endsWith(".vel")) corpus.add(parts[2]);
            }
        }
        for (String dir : new String[]{"selfhost/parts", "tests/build", "tests/probes",
                                       "examples", "bench", "ide-demo"}) {
            Path d = root.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            List<Path> found = new ArrayList<>();
            Files.walk(d).filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".vel")).forEach(found::add);
            Collections.sort(found);
            for (Path f : found) {
                corpus.add(root.relativize(f).toString().replace('\\', '/'));
            }
        }

        TreeMap<String, Integer> byClass = new TreeMap<>();
        int files = 0;
        int differ = 0;
        int identical = 0;
        int threw = 0;
        List<String> straddles = new ArrayList<>();
        List<String> disjoints = new ArrayList<>();
        // For each differing file: how many region pairs are nested, and does the
        // whole difference set consist of nested pairs?
        int filesAllNested = 0;
        int filesWithOther = 0;
        // The set-level classification: the one the criterion is written from.
        int setNested = 0;
        int setMineInsideOnly = 0;
        int setTheirsInsideOnly = 0;
        int setNeither = 0;

        for (String rel : corpus) {
            Path p = root.resolve(rel);
            if (!Files.isRegularFile(p) || Files.size(p) > MAX_FILE_BYTES) continue;
            files++;
            String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            List<VelaFold> mine;
            List<VelaFold> theirs;
            try {
                mine = VelaFoldingKt.velaFoldRanges(text);
                theirs = VelaFoldingKt.velaFoldRangesReference(text);
            } catch (Throwable t) {
                threw++;
                continue;
            }
            if (describe(mine).equals(describe(theirs))) {
                identical++;
                continue;
            }
            differ++;
            // Pairing by INDEX is wrong for this question: the two implementations
            // emit different numbers of regions in different orders (an extra region
            // near the top shifts every later index), which is why the index-paired
            // table below is mostly `disjoint` noise.  The relation that decides the
            // criterion is over the SETS: is every region one implementation emits
            // either equal to, or contained in, some region of the other?
            boolean allMineInside = allCoveredBy(mine, theirs);
            boolean allTheirsInside = allCoveredBy(theirs, mine);
            if (allMineInside && allTheirsInside) setNested++;
            else if (allMineInside) setMineInsideOnly++;
            else if (allTheirsInside) setTheirsInsideOnly++;
            else setNeither++;
            // Pair up by index, the way FoldDiff shows them, and classify each pair.
            boolean sawOther = false;
            int n = Math.max(mine.size(), theirs.size());
            for (int i = 0; i < n; i++) {
                VelaFold a = i < mine.size() ? mine.get(i) : null;
                VelaFold b = i < theirs.size() ? theirs.get(i) : null;
                String cls;
                if (a == null || b == null) {
                    cls = "one-side-only";
                } else if (a.getStart() == b.getStart() && a.getEnd() == b.getEnd()) {
                    cls = "equal";
                } else if (contains(a, b)) {
                    cls = "nested(tree contains brace)";
                } else if (contains(b, a)) {
                    cls = "nested(brace contains tree)";
                } else if (a.getEnd() <= b.getStart() || b.getEnd() <= a.getStart()) {
                    cls = "disjoint";
                    if (disjoints.size() < 12) {
                        disjoints.add(rel + " region " + i + "  tree=" + range(a)
                                + "  brace=" + range(b));
                    }
                } else {
                    cls = "straddle";
                    if (straddles.size() < 12) {
                        straddles.add(rel + " region " + i + "  tree=" + range(a)
                                + "  brace=" + range(b));
                    }
                }
                byClass.merge(cls, 1, Integer::sum);
                if (!cls.startsWith("nested") && !cls.equals("equal")) sawOther = true;
            }
            if (sawOther) filesWithOther++;
            else filesAllNested++;
        }

        System.out.println("files in the corpus            : " + files);
        System.out.println("identical region lists         : " + identical);
        System.out.println("differing region lists         : " + differ);
        System.out.println("  of which every pair is nested or equal : " + filesAllNested);
        System.out.println("  of which at least one pair is not      : " + filesWithOther);
        System.out.println("threw                          : " + threw);
        System.out.println();
        System.out.println("== differing files, by the SET relation ==");
        System.out.println("  every tree region is contained in (or equal to) a brace region");
        System.out.println("  AND vice versa -- the two lists differ only by nesting : " + setNested);
        System.out.println("  every tree region is inside a brace region, but not the other way : "
                + setMineInsideOnly);
        System.out.println("  every brace region is inside a tree region, but not the other way : "
                + setTheirsInsideOnly);
        System.out.println("  neither direction holds (some region is in neither list's spans) : "
                + setNeither);
        System.out.println();
        System.out.println("== region pairs by relation (paired by index) ==");
        for (var e : byClass.entrySet()) {
            System.out.println("  " + pad(e.getKey(), 34) + e.getValue());
        }
        System.out.println();
        System.out.println("== the first 12 disjoint pairs ==");
        for (String s : disjoints) System.out.println("  " + s);
        System.out.println("== the first 12 straddling pairs ==");
        for (String s : straddles) System.out.println("  " + s);
    }

    private static boolean allCoveredBy(List<VelaFold> inner, List<VelaFold> outer) {
        return uncovered(inner, outer).isEmpty();
    }

    private static List<String> uncovered(List<VelaFold> inner, List<VelaFold> outer) {
        List<String> out = new ArrayList<>();
        for (VelaFold f : inner) {
            boolean covered = false;
            for (VelaFold o : outer) {
                if (o.getStart() <= f.getStart() && f.getEnd() <= o.getEnd()) {
                    covered = true;
                    break;
                }
            }
            if (!covered) out.add(range(f));
        }
        return out;
    }

    private static boolean contains(VelaFold outer, VelaFold inner) {
        if (outer.getStart() == inner.getStart() && outer.getEnd() == inner.getEnd()) return false;
        return outer.getStart() <= inner.getStart() && inner.getEnd() <= outer.getEnd();
    }

    private static String range(VelaFold f) {
        return "[" + f.getStart() + "," + f.getEnd() + ")";
    }

    private static String describe(List<VelaFold> list) {
        StringBuilder sb = new StringBuilder(256);
        for (VelaFold f : list) sb.append(range(f)).append('`').append(f.getPlaceholder()).append("`\n");
        return sb.toString();
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    private FoldShapeProbe() {
    }
}
