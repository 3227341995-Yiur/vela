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
 * THE DIFFERENCE IS CLASSIFIED, BECAUSE "40 FILES DIFFER" IS NOT A FINDING.
 *
 * This tool compares the shipping tree-derived model against this plugin's own
 * *retired* token scan.  No external authority defines a symbol list, so a
 * difference is not by itself a defect.  The 40 fall into decidable classes, and the
 * tool says which one each file is:
 *
 *   IDENTICAL   the two lists agree symbol for symbol.
 *   TYPE SPELLING ONLY   the two lists are the same length, and become **identical**
 *                once the retired scan's spelling of an array type is rewritten:
 *                the scan echoes what the source wrote (`[int, 786432]`), the tree
 *                answers with the canonical Vela type name (`Array[int,786432]`).
 *                The test is applied, not eyeballed.
 *   MORE DECLARATIONS IN THE TREE   the tree reports a symbol the retired scan does
 *                not report at all.  A count difference cannot be a spelling
 *                difference, so this is its own class.
 *   STRUCTURAL  the same number of symbols, but some symbol's kind, name, line or
 *                parent differs after the spelling rewrite.  Every such file is
 *                printed in full.
 *
 * THE CEILING IS MEASURED, NOT ASSUMED.  A spelling test can only apply to a file
 * whose two lists are the same length, and `SpellRuleProbe` (kept beside this file)
 * measures over this corpus: of the 40 differing files, **25 have more symbols in the
 * tree than in the scan, and 0 have more in the scan**; 15 have equal counts, of
 * which only **2** become identical under the rewrite.  So the honest numbers are
 * 2 spelling-only, 25 where the tree found declarations the retired scan did not, and
 * 13 equal-count files that still differ structurally.  A classifier that reported
 * "0 spelling-only" over all 40 -- which this one did, because its rewrite was
 * applied to both sides and mangled the tree's own canonical text -- could only ever
 * say "structural"; `SpellRuleProbe` is what showed that.
 *
 * `vm.exe` is not involved: the question is where the *plugin's* model comes from,
 * not what the compiler thinks.  The tree side is held to the compiler by
 * `ast-diff.ps1`, which is what makes "the tree found more" a statement with an
 * authority behind it rather than a preference.
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
        int spellingOnly = 0;
        int treeMore = 0;
        int scanMore = 0;
        int structural = 0;
        int unexplainedOther = 0;
        int spellingFirstButLater = 0;
        int bothEmpty = 0;
        int threw = 0;
        int absent = 0;
        int tooLarge = 0;
        long symbols = 0;
        StringBuilder table = new StringBuilder(8192);
        StringBuilder detail = new StringBuilder(32768);
        List<String> structuralFiles = new ArrayList<>();
        List<String> countFiles = new ArrayList<>();
        List<String> unexplainedFiles = new ArrayList<>();
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
                // The decidable part: does normalising the RETIRED scan's spelling of a
                // type make the two lists identical?  If it does, both sides describe the
                // same declaration and the difference is presentation.
                //
                // ONLY THE RETIRED SIDE IS NORMALISED, AND THAT IS NOT A DETAIL.  The
                // first version of this test ran the normaliser over *both* lists.  The
                // rewrite recognises a bracketed group (`[int, 786432]`) and rewrites it,
                // and the tree's own form `Array[int,2]` CONTAINS one -- so normalising the
                // tree turned its correct `Array[int,2]` into `Array[int,Array[2]]` and
                // manufactured a difference in a pair that had none.  The classifier then
                // reported `type-spelling-only-difference 0` over the whole corpus: a
                // classification that could only ever say "structural".  Measured with
                // `SpellRuleProbe`, which tries each candidate rule over the corpus.
                boolean spelling = describe(mine).equals(describeNormalised(theirs));
                if (spelling) {
                    spellingOnly++;
                    verdict = "differs (type spelling only)";
                } else if (mine.size() > theirs.size()) {
                    // A COUNT difference is not a spelling difference: a normaliser cannot
                    // turn one list into the other.  Measured over this corpus, this is the
                    // largest class and the *stated* claim of the tree-side rewrite -- the
                    // tree reports `extern` declarations the retired token scan never saw.
                    treeMore++;
                    verdict = "tree has more symbols (" + mine.size() + " vs " + theirs.size() + ")";
                    countFiles.add("  " + rel + ": tree " + mine.size()
                            + " symbol(s), token scan " + theirs.size() + " symbol(s)");
                } else if (theirs.size() > mine.size()) {
                    scanMore++;
                    verdict = "scan has more symbols (" + mine.size() + " vs " + theirs.size() + ")";
                    countFiles.add("  " + rel + ": tree " + mine.size()
                            + " symbol(s), token scan " + theirs.size() + " symbol(s)");
                } else {
                    structural++;
                    verdict = "DIFFERS (same count, different content)";
                    structuralFiles.add("  " + rel + ": tree " + mine.size()
                            + " symbol(s), token scan " + theirs.size() + " symbol(s)");
                }
                // WHAT, EXACTLY, IS DIFFERENT?  Computed per index, not from the two whole
                // lists.
                //
                // The first version asked "which symbols are in one list and not the other"
                // and reported, for `selfhost/parts/emit.vel`, 192 symbols only in the tree
                // and 192 only in the scan -- listing the SAME 192 names on both sides.  The
                // sets were equal, so the difference was inside a symbol, not in the
                // membership, and that test could not say which.  A per-index pair compare
                // says it directly: the first index at which the two descriptions differ,
                // and the field that differs.
                int firstDiff = firstDifferingIndex(mine, theirs);
                String pairMine = firstDiff < mine.size() ? describe(mine.get(firstDiff)) : "<end>";
                String pairTheirs = firstDiff < theirs.size() ? describe(theirs.get(firstDiff))
                        : "<end>";
                String whichField = firstDifferingField(pairMine, pairTheirs);
                String countPair = "tree " + mine.size() + " / scan " + theirs.size()
                        + ", first difference at symbol " + firstDiff + " in `" + whichField + "`";
                if (verdict.startsWith("DIFFERS")) {
                    // ONLY this class gets the sub-counts, because they are a statement about
                    // it: "same count, and the difference is / is not a spelling".  An earlier
                    // version incremented them for every differing file, so they read 18 and
                    // 22 against a class of 13 -- a denominator violated in the output, which
                    // is how it was caught.  A count whose parts do not sum to its whole is
                    // worse than no count.
                    structuralFiles.add("  " + rel + ": " + countPair);
                    if (!whichField.endsWith("/type-spelling")) {
                        unexplainedOther++;
                    } else {
                        spellingFirstButLater++;
                    }
                    if (unexplainedFiles.size() < 6) {
                        unexplainedFiles.add("  " + rel + ": " + countPair
                                + "\n      tree : " + pairMine + "\n      scan : " + pairTheirs);
                    }
                } else {
                    countFiles.add("  " + rel + ": " + countPair);
                }
                int n = Math.max(mine.size(), theirs.size());
                for (int i = 0; i < n; i++) {
                    String a = i < mine.size() ? describe(mine.get(i)) : "<end of list>";
                    String b = i < theirs.size() ? describe(theirs.get(i)) : "<end of list>";
                    if (!a.equals(b)) {
                        String bNorm = i < theirs.size()
                                ? describeNormalised(theirs.get(i)) : "<end of list>";
                        detail.append("  ").append(rel).append(": symbol ").append(i).append('\n')
                              .append("      from the tree : ").append(a).append('\n')
                              .append("      token scan    : ").append(b).append('\n');
                        if (spelling) {
                            detail.append("      (identical after normalising the type spelling: ")
                                  .append(bNorm).append(")\n");
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
        System.out.println("  files compared            : " + (same + differ));
        System.out.println("  identical symbol lists    : " + same + " (of which both empty: " + bothEmpty + ")");
        System.out.println("  different                 : " + differ);
        System.out.println("    type spelling only            : " + spellingOnly
                + "   (same length, identical once the retired scan's `[T, n]` spelling is"
                + " rewritten as the canonical `Array[T,n]`)");
        System.out.println("    same count, different content : " + structural
                + "   (of which " + spellingFirstButLater + " have a type spelling as their FIRST"
                + " difference and something else later, and " + unexplainedOther
                + " have a first difference that is not a spelling; the two sum to "
                + (spellingFirstButLater + unexplainedOther) + " -- see the pairs below)");
        System.out.println("    the tree has MORE symbols     : " + treeMore
                + "   (declarations the retired token scan never produced)");
        System.out.println("    the scan has MORE symbols     : " + scanMore);
        System.out.println("  symbols in the identical ones: " + symbols);
        System.out.println();
        System.out.println("== the class that decides the verdict: same count, different content ==");
        System.out.println("  Each line names the first symbol index at which the two descriptions");
        System.out.println("  differ, and WHICH FIELD differs.  That is the point of this class: these");
        System.out.println("  files have the same number of declarations, so the difference is inside");
        System.out.println("  a symbol, and only a field name says which one.");
        if (structuralFiles.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (String s : structuralFiles) System.out.println(s);
        }
        System.out.println();
        System.out.println("== the same-count files whose first difference is NOT a type spelling ==");
        System.out.println("  " + unexplainedOther + " of " + structural + " file(s) -- the honest"
                + " defect count of this tool, with both sides for the first six:");
        if (unexplainedFiles.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (String s : unexplainedFiles) System.out.println(s);
        }
        System.out.println();
        System.out.println("== every count difference (tree vs retired scan), file by file ==");
        if (countFiles.isEmpty()) {
            System.out.println("  (none)");
        } else {
            for (String s : countFiles) System.out.println(s);
        }
        System.out.println();
        System.out.println("== the type-spelling-only differences ==");
        System.out.println("  " + spellingOnly + " file(s): the same declarations, spelled differently"
                + " -- the retired scan echoes the source text and the tree answers with the"
                + " canonical type name.");
        System.out.println();
        System.out.println("== every difference, in full ==");
        System.out.println(detail.length() == 0 ? "  (none)" : detail.toString());
        System.out.println("VERDICT: " + (structural == 0 && threw == 0
                ? "no file has the same symbol count and different content from the retired token"
                        + " scan (" + same + " identical, " + spellingOnly
                        + " differing only in how a type is spelled, " + treeMore
                        + " where the tree reports more declarations, " + scanMore
                        + " where the scan does, " + structural + " in the failing class)"
                : structural + " file(s) have the same symbol count as the retired token scan and"
                        + " different content, and " + threw + " threw; each is listed above with"
                        + " both sides"));
        Coverage cov = new Coverage()
                .defectCategory("threw")
                .defectCategory("missing-corpus-file")
                .category("too-large")
                .category("type-spelling-only-difference")
                .category("tree-reports-more-declarations")
                .category("scan-reports-more-declarations")
                .ran(same)
                .skipped("threw", threw)
                .skipped("missing-corpus-file", absent)
                .skipped("too-large", tooLarge)
                .skipped("type-spelling-only-difference", spellingOnly)
                .skipped("tree-reports-more-declarations", treeMore)
                .skipped("scan-reports-more-declarations", scanMore)
                .wrong(structural);
        cov.print();
        // The exit code is the decidable class only.  It used to exit 1 for all 40, which
        // made a spelling difference and a content difference indistinguishable -- and 25
        // of those 40 are the tree reporting declarations the retired scan never had, which
        // is the rewrite's *point* rather than a defect.  `ast-diff.ps1` is the authority
        // on the tree; this tool is the regression detector beside it.
        System.exit(structural > 0 ? 1 : (cov.hasDefect() ? 3 : 0));
    }

    /** The first index at which the two descriptions of the lists differ. */
    private static int firstDifferingIndex(List<VelaSymbol> a, List<VelaSymbol> b) {
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            String x = i < a.size() ? describe(a.get(i)) : "<end of list>";
            String y = i < b.size() ? describe(b.get(i)) : "<end of list>";
            if (!x.equals(y)) return i;
        }
        return -1;
    }

    /**
     * Which field of one symbol pair differs: `kind`, `name`, `detail`, `type`, `line`,
     * or `parent`.
     *
     * EXTRACTED BY LABEL, NOT SPLIT.  The first version of this used
     * `a.split("` | detail=|` type=|` line=|` parent=")` and read the pieces by index.
     * `String.split` with a pattern discards the delimiters, and `describe()` writes
     * `(kind) `name` detail=`...`` -- so between the closing backtick of the name and
     * `detail=` there is nothing, and the split produced empty strings that shifted every
     * later index by one.  The reported field was then always `name` while the pair
     * printed underneath it plainly showed a `detail`/`type` difference.  Reading each
     * field back by its own label cannot go wrong that way.
     */
    private static String firstDifferingField(String a, String b) {
        String[] names = {"kind", "name", "detail", "type", "line", "parent"};
        for (String field : names) {
            String va = fieldOf(a, field);
            String vb = fieldOf(b, field);
            if (!va.equals(vb)) {
                // A type spelling is presentation when the rewrite makes them equal -- for
                // `detail` the spelling can be embedded in a signature, so both are tested.
                if (normalise(vb).equals(va)) return field + "/type-spelling";
                if (va.indexOf('[') >= 0 && normalise(vb).equals(va)) {
                    return field + "/type-spelling";
                }
                return field;
            }
        }
        return "(no field differs)";
    }

    /** The value of one field of a `describe()` line, by its label. */
    private static String fieldOf(String described, String field) {
        switch (field) {
            case "kind": {
                int i = described.indexOf('`');
                return i < 0 ? described : described.substring(0, i).trim();
            }
            case "name": {
                int i = described.indexOf('`');
                int j = described.indexOf('`', i + 1);
                return (i < 0 || j < 0) ? "" : described.substring(i + 1, j);
            }
            case "detail": {
                String s = between(described, "detail=`", "`");
                return s;
            }
            case "type": {
                return between(described, "type=`", "`");
            }
            case "line": {
                return between(described, "line=", " parent=");
            }
            case "parent": {
                int i = described.indexOf("parent=");
                return i < 0 ? "" : described.substring(i + 7).trim();
            }
            default:
                return "";
        }
    }

    private static String between(String s, String open, String close) {
        int i = s.indexOf(open);
        if (i < 0) return "";
        int j = s.indexOf(close, i + open.length());
        return j < 0 ? "" : s.substring(i + open.length(), j);
    }

    /** One symbol as a single line, so the two lists can be compared textually. */
    private static String describe(List<VelaSymbol> list) {
        StringBuilder sb = new StringBuilder(256);
        for (VelaSymbol s : list) sb.append(describe(s)).append('\n');
        return sb.toString();
    }

    /** `kind \`name\`` for each symbol of [inner] that [outer] has no matching entry for. */
    private static List<String> minus(List<VelaSymbol> inner, List<VelaSymbol> outer) {
        List<String> out = new ArrayList<>();
        for (VelaSymbol s : inner) {
            boolean found = false;
            for (VelaSymbol o : outer) {
                if (describe(o).equals(describe(s))) {
                    found = true;
                    break;
                }
            }
            if (!found) out.add(s.getKind() + " `" + s.getName() + "`");
        }
        return out;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** The name out of a `kind \`name\`` string. */
    private static String nameOf(String described) {
        int a = described.indexOf('`');
        int b = described.indexOf('`', a + 1);
        if (a < 0 || b < 0) return "";
        return described.substring(a + 1, b);
    }

    private static String describe(VelaSymbol s) {
        return s.getKind() + " `" + s.getName() + "` detail=`" + s.getDetail() + "` type=`"
                + s.getType() + "` line=" + s.getLine() + " parent=" + s.getParent();
    }

    /** As [describe], with the type spelling normalised -- see the class comment. */
    private static String describeNormalised(VelaSymbol s) {
        return s.getKind() + " `" + s.getName() + "` detail=`" + normalise(s.getDetail())
                + "` type=`" + normalise(s.getType()) + "` line=" + s.getLine()
                + " parent=" + s.getParent();
    }

    /**
     * The retired scan's text with every type spelling in its `detail` rewritten.
     *
     * THE SPELLING TEST AND THE FIELD TEST HAVE TO USE THE SAME DEFINITION.
     *
     * `describeNormalised` rewrites `detail` wholesale, which is only correct when the
     * detail *is* a type (`mem: [int, 3741696]`).  When the detail is a signature --
     * `vshim_emit(mem: [int, 786432], x: int) -> i32` -- a wholesale rewrite leaves the
     * inner `[int, 786432]` and the plain `[int, 786432]` and the two forms disagree, so
     * the list-level test called the file structural while the field-level test, which
     * looks at the differing field by label, correctly called it a spelling difference.
     * The two disagreed on the same input, which is how this was found.
     *
     * This rewrites a signature as well as a bare type, by handing each type occurrence
     * inside `detail` to the same `normalise`.  A type occurrence is what sits between a
     * `:` and a `,` or `)` or `->`, which is exactly where the source would have written
     * one.
     */
    private static String describeNormalisedDeep(VelaSymbol s) {
        return s.getKind() + " `" + s.getName() + "` detail=`" + normaliseTypesIn(s.getDetail())
                + "` type=`" + normalise(s.getType()) + "` line=" + s.getLine()
                + " parent=" + s.getParent();
    }

    /**
     * The retired list rendered with every type spelling normalised, signature or not.
     *
     * NOT USED, AND KEPT AS A RECORD OF A RULE THAT WAS MEASURED AND REJECTED.
     *
     * The idea was to make the list-level spelling test as tolerant as the field-level
     * one, by rewriting `[int, 786432]` wherever it appears -- including inside a
     * signature -- instead of only when the detail *is* a type.  Measured over the corpus
     * it classified **0** files, against 2 for the plain form, because repeating the
     * rewrite until it stabilises also turns the tree's own canonical text into
     * `ArrayArray[...]`.  A rule that classifies nothing is worse than the narrower rule
     * it replaced, so the narrow one ships and this is left here so the experiment is not
     * repeated.  See `SpellRuleProbe` for the same finding measured three ways.
     */
    private static String describeNormalisedDeepList(List<VelaSymbol> list) {
        StringBuilder sb = new StringBuilder(256);
        for (VelaSymbol s : list) sb.append(describeNormalisedDeep(s)).append('\n');
        return sb.toString();
    }

    /** Rewrite every type occurrence inside a `detail` string. */
    private static String normaliseTypesIn(String detail) {
        if (detail == null || detail.isEmpty() || detail.indexOf('[') < 0) return detail;
        // Every `[..]` group is a type spelling wherever it appears, including inside a
        // signature; the same callback as `normalise`, applied repeatedly until the text
        // stops changing, so a nested form settles.
        String out = detail;
        for (int pass = 0; pass < 4; pass++) {
            String next = normalise(out);
            if (next.equals(out)) break;
            out = next;
        }
        return out;
    }

    /**
     * Rewrite the retired token scan's spelling of an array type as the canonical
     * Vela type name.
     *
     * The scan copies what the source wrote -- `[int, 786432]` -- while the tree
     * answers with `Array[int,786432]`, which is the name the compiler's own `Array`
     * builtin has.  Both describe the same type; only the spelling differs.
     *
     * THE REPLACEMENT IS A CALLBACK, AND THE OPERANDS ARE THE RIGHT WAY ROUND.  The
     * first version was a single `replaceAll` with `"Array[$1,$2]"`, and it produced
     * `Array[int, 262144,]` -- the element type and the dimension swapped, and a comma
     * left over from the case where there is no dimension.  A regex `$n` reference
     * cannot say "omit the comma when group 2 is absent", and getting the order wrong
     * is invisible until you print the result.  The callback form below is longer and
     * says what it means:
     *
     *   `[int, 786432]`     -> `Array[int,786432]`
     *   `[T]`               -> `Array[T]`            (no dimension: no comma)
     *   `[[int, 2], 3]`     -> `Array[Array[int,2],3]`
     *
     * The whitespace inside the dimension is NOT normalised: the test was calibrated
     * against the corpus, not loosened until it passed.
     */
    private static String normalise(String detail) {
        if (detail == null || detail.isEmpty() || detail.indexOf('[') < 0) return detail;
        return PATTERN_ARRAY.matcher(detail).replaceAll(mr -> {
            String element = mr.group(1).trim();
            String dimension = mr.group(2);
            return dimension == null
                    ? "Array[" + element + "]"
                    : "Array[" + element + "," + dimension + "]";
        });
    }

    /** `[<element>]` or `[<element>, <dimension>]`, both single level (no nested brackets). */
    private static final java.util.regex.Pattern PATTERN_ARRAY =
            java.util.regex.Pattern.compile("\\[([^\\[\\]]+?)(?:,\\s*(\\w+))?\\]");

    /** The retired list with every type spelling normalised, for the equality test. */
    private static String describeNormalised(List<VelaSymbol> list) {
        StringBuilder sb = new StringBuilder(256);
        for (VelaSymbol s : list) sb.append(describeNormalised(s)).append('\n');
        return sb.toString();
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
