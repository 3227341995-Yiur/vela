import dev.vela.plugin.VelaModel;
import dev.vela.plugin.VelaSymbol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Is the type-spelling normalisation in `SymbolDiff` doing what it claims?
 *
 * `SymbolDiff` classifies a difference as `type spelling only` when the two lists
 * become identical after the retired scan's `[int, 786432]` is rewritten as
 * `Array[int,786432]`.  The first run of that classifier found **0** such files, which
 * is a claim about the normalisation function and not about the corpus -- so this
 * prints, for one file, the two lists symbol by symbol and the normalised form of the
 * retired one, and the first pair that still differs afterwards.
 *
 * A classifier that can only say "structural" is not a classifier.
 *
 *   java -cp <plugin classes> StringSpellProbe <repo-root> <file>
 */
public final class StringSpellProbe {

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]).toAbsolutePath();
        String rel = args[1];
        String text = new String(Files.readAllBytes(root.resolve(rel)), StandardCharsets.ISO_8859_1);
        List<VelaSymbol> mine = VelaModel.INSTANCE.symbols(text);
        List<VelaSymbol> theirs = VelaModel.INSTANCE.referenceSymbols(text);

        System.out.println("file          : " + rel);
        System.out.println("tree symbols  : " + mine.size());
        System.out.println("scan symbols  : " + theirs.size());
        int n = Math.max(mine.size(), theirs.size());
        int firstRaw = -1;
        int firstNormalised = -1;
        for (int i = 0; i < n; i++) {
            String a = i < mine.size() ? describe(mine.get(i)) : "<end of list>";
            String b = i < theirs.size() ? describe(theirs.get(i)) : "<end of list>";
            String bn = i < theirs.size() ? describeNormalised(theirs.get(i)) : "<end of list>";
            if (firstRaw < 0 && !a.equals(b)) firstRaw = i;
            if (firstNormalised < 0 && !a.equals(bn)) firstNormalised = i;
            if (i < 40 || !a.equals(bn)) {
                System.out.println("  [" + i + "] tree : " + a);
                System.out.println("      scan : " + b);
                if (!b.equals(bn)) {
                    System.out.println("      scan': " + bn);
                    System.out.println("      DOES THE TREE'S OWN FORM SURVIVE ITS OWN NORMALISER? "
                            + (i < mine.size() ? ("`" + normalise(mine.get(i).getDetail()) + "`")
                                    : "(n/a)"));
                }
            }
        }
        System.out.println();
        System.out.println("first index where the raw forms differ        : " + firstRaw);
        System.out.println("first index where the NORMALISED forms differ : " + firstNormalised);
        System.out.println();
        System.out.println("VERDICT: " + (firstNormalised < 0
                ? "the whole list matches after normalisation -- this file IS spelling-only"
                : "differences survive normalisation, starting at index " + firstNormalised));
    }

    private static String describe(VelaSymbol s) {
        return s.getKind() + " `" + s.getName() + "` detail=`" + s.getDetail() + "` type=`"
                + s.getType() + "` line=" + s.getLine() + " parent=" + s.getParent();
    }

    private static String describeNormalised(VelaSymbol s) {
        return s.getKind() + " `" + s.getName() + "` detail=`" + normalise(s.getDetail())
                + "` type=`" + normalise(s.getType()) + "` line=" + s.getLine()
                + " parent=" + s.getParent();
    }

    private static String normalise(String detail) {
        if (detail == null || detail.isEmpty() || detail.indexOf('[') < 0) return detail;
        // ONE PASS with a callback, which is what SymbolDiff does.
        //
        // The loop version of this was wrong and is worth recording: it re-ran `find()`
        // from a fresh matcher on the *rewritten* string, so after turning `[int, 262144]`
        // into `Array[int,262144]` it matched the `[262144]` inside the result and produced
        // `ArrayArray[int,262144]`, then again, and again.  `replaceAll` with a callback
        // cannot do that: it consumes each match once, in one left-to-right pass.
        return INNER.matcher(detail).replaceAll(new java.util.function.Function<java.util.regex.MatchResult, String>() {
            @Override
            public String apply(java.util.regex.MatchResult mr) {
                String element = mr.group(1).trim();
                String dimension = mr.group(2);
                return dimension == null
                        ? "Array[" + element + "]" : "Array[" + element + "," + dimension + "]";
            }
        });
    }

    private static final java.util.regex.Pattern INNER =
            java.util.regex.Pattern.compile("\\[([^\\[\\]]+?)(?:,\\s*(\\w+))?\\]");

    private StringSpellProbe() {
    }
}
