import dev.vela.plugin.VelaModel;
import dev.vela.plugin.VelaSymbol;

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
 * WHICH NORMALISATION RULE ACTUALLY CLASSIFIES THE CORPUS?
 *
 * `SymbolDiff` needs a decidable test for "these two symbol lists describe the same
 * declarations and differ only in how a type is spelled".  Writing that test by
 * reasoning about regexes produced three wrong versions in a row (a swapped operand
 * order that emitted `Array[int, 262144,]`, a form that normalised the tree's *own*
 * canonical text into `ArrayArray[...]`, and a lookbehind that could not tell the tree
 * from the scan).  So this probe tries each candidate rule over the whole corpus and
 * prints how many of the 40 differing files each rule classifies, plus the first pair
 * each rule leaves different.  The rule that goes into `SymbolDiff` is the one this
 * measures, not the one that reads best.
 *
 *   java -cp <plugin classes> SpellRuleProbe <repo-root>
 */
public final class SpellRuleProbe {

    /** One candidate rule: rewrite a source-spelled array type, or leave the text alone. */
    interface Rule {
        String name();

        String apply(String text);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]).toAbsolutePath();
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

        List<Rule> rules = new ArrayList<>();
        rules.add(new Rule() {
            public String name() {
                return "A: callback, operand order fixed (group1=element, group2=dimension)";
            }

            public String apply(String t) {
                return t.replaceAll("\\[([^\\[\\]]+?)(?:,\\s*(\\w+))?\\]", "Array[$1,$2]");
            }
        });
        rules.add(new Rule() {
            public String name() {
                return "B: LIKE '%' bracket-to-paren matching by hand";
            }

            public String apply(String t) {
                return handRewrite(t);
            }
        });
        rules.add(new Rule() {
            public String name() {
                return "C: B, then one more pass (nested element types)";
            }

            public String apply(String t) {
                String once = handRewrite(t);
                String twice = handRewrite(once);
                return twice;
            }
        });

        // Collect the differing files first: the classification only concerns those.
        List<String> differing = new ArrayList<>();
        List<List<VelaSymbol>> trees = new ArrayList<>();
        List<List<VelaSymbol>> scans = new ArrayList<>();
        for (String rel : corpus) {
            Path p = root.resolve(rel);
            if (!Files.isRegularFile(p)) continue;
            String text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            List<VelaSymbol> mine = VelaModel.INSTANCE.symbols(text);
            List<VelaSymbol> theirs = VelaModel.INSTANCE.referenceSymbols(text);
            if (!describe(mine).equals(describe(theirs))) {
                differing.add(rel);
                trees.add(mine);
                scans.add(theirs);
            }
        }
        System.out.println("corpus files with differing symbol lists : " + differing.size());
        System.out.println("  (of which equal symbol COUNTS            : "
                + countEqualCounts(trees, scans) + ")");
        System.out.println();

        for (Rule rule : rules) {
            System.out.println("== rule " + rule.name());
            int classified = 0;
            List<String> classifiedNames = new ArrayList<>();
            String firstUnclassified = null;
            for (int i = 0; i < differing.size(); i++) {
                boolean ok = listNormalised(scans.get(i), rule).equals(describe(trees.get(i)));
                if (ok) {
                    classified++;
                    classifiedNames.add(differing.get(i)
                            + " (tree " + trees.get(i).size() + " / scan " + scans.get(i).size() + ")");
                } else if (firstUnclassified == null) {
                    firstUnclassified = firstFailure(trees.get(i), scans.get(i), rule);
                }
            }
            System.out.println("   files classified as spelling-only : " + classified
                    + " of " + differing.size());
            for (String s : classifiedNames) System.out.println("      " + s);
            if (firstUnclassified != null) {
                System.out.println("   first pair it cannot classify     : " + firstUnclassified);
            }
            System.out.println();
        }

        // WHY the rest cannot be classified, by symbol count: a spelling test can only
        // ever apply to a file whose two lists have the same length, so the count
        // distribution IS the honest ceiling on this criterion.
        System.out.println("== the attainable ceiling, by symbol count ==");
        int equalCounts = 0;
        int treeMore = 0;
        int scanMore = 0;
        for (int i = 0; i < differing.size(); i++) {
            int a = trees.get(i).size();
            int b = scans.get(i).size();
            if (a == b) equalCounts++;
            else if (a > b) treeMore++;
            else scanMore++;
        }
        System.out.println("  equal counts (a spelling rule can in principle apply) : " + equalCounts);
        System.out.println("  the tree has more symbols than the scan               : " + treeMore);
        System.out.println("  the scan has more symbols than the tree               : " + scanMore);
        System.out.println("  a count difference is NOT a spelling difference, and no normaliser"
                + " can turn one into the other.");
    }

    /**
     * Rewrite `[element, dim]` as `Array[element,dim]` by hand, left to right, so the
     * pass cannot re-read its own output: each `[` is matched to its `]` once and the
     * scan resumes AFTER the rewritten text.
     */
    private static String handRewrite(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '[') {
                out.append(c);
                i++;
                continue;
            }
            int close = matchingBracket(text, i);
            if (close < 0) {
                out.append(c);
                i++;
                continue;
            }
            String inside = text.substring(i + 1, close);
            // element [, dimension] -- split on the LAST top-level comma
            int comma = -1;
            int depth = 0;
            for (int k = 0; k < inside.length(); k++) {
                char ic = inside.charAt(k);
                if (ic == '[') depth++;
                else if (ic == ']') depth--;
                else if (ic == ',' && depth == 0) comma = k;
            }
            String element = (comma < 0 ? inside : inside.substring(0, comma)).trim();
            String dimension = comma < 0 ? null : inside.substring(comma + 1).trim();
            out.append(dimension == null
                    ? "Array[" + element + "]" : "Array[" + element + "," + dimension + "]");
            i = close + 1;
        }
        return out.toString();
    }

    private static int matchingBracket(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int countEqualCounts(List<List<VelaSymbol>> trees, List<List<VelaSymbol>> scans) {
        int n = 0;
        for (int i = 0; i < trees.size(); i++) {
            if (trees.get(i).size() == scans.get(i).size()) n++;
        }
        return n;
    }

    private static String firstFailure(List<VelaSymbol> mine, List<VelaSymbol> theirs, Rule rule) {
        int n = Math.max(mine.size(), theirs.size());
        for (int i = 0; i < n; i++) {
            String a = i < mine.size() ? describe(mine.get(i)) : "<end>";
            String b = i < theirs.size() ? describeNormalised(theirs.get(i), rule) : "<end>";
            if (!a.equals(b)) return "\n        tree : " + a + "\n        scan': " + b;
        }
        return "(none)";
    }

    private static String describeNormalised(VelaSymbol s, Rule rule) {
        return s.getKind() + " `" + s.getName() + "` detail=`" + rule.apply(s.getDetail())
                + "` type=`" + rule.apply(s.getType()) + "` line=" + s.getLine()
                + " parent=" + s.getParent();
    }

    private static String describe(List<VelaSymbol> list) {
        StringBuilder sb = new StringBuilder(256);
        for (VelaSymbol s : list) sb.append(describe(s)).append('\n');
        return sb.toString();
    }

    private static String describe(VelaSymbol s) {
        return s.getKind() + " `" + s.getName() + "` detail=`" + s.getDetail() + "` type=`"
                + s.getType() + "` line=" + s.getLine() + " parent=" + s.getParent();
    }

    private static String describeNormalised(List<VelaSymbol> list, Rule rule) {
        StringBuilder sb = new StringBuilder(256);
        for (VelaSymbol s : list) {
            sb.append(s.getKind()).append(" `").append(s.getName()).append("` detail=`")
              .append(rule.apply(s.getDetail())).append("` type=`").append(rule.apply(s.getType()))
              .append("` line=").append(s.getLine()).append(" parent=").append(s.getParent())
              .append('\n');
        }
        return sb.toString();
    }

    private static String listNormalised(List<VelaSymbol> list, Rule rule) {
        return describeNormalised(list, rule);
    }
    private SpellRuleProbe() {
    }
}
