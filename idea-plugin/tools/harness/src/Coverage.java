import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The coverage triple every harness in this plugin must end with.
 *
 * WHY THIS CLASS EXISTS
 *
 * Each harness used to end with a verdict -- "no reference goes to a declaration
 * the compiler does not bind it to", "PASS", "every hint names the parameter the
 * compiler declares" -- and nothing else.  Those sentences are about the work that
 * *happened*, so a run in which the oracle crashed on every file containing
 * `extern` printed the same clean verdict as a run over the whole corpus.  Measured,
 * not hypothesised: `GotoOracle` threw `NullPointerException: Cannot invoke
 * "java.util.List.iterator()"` on nine such files, and the run still ended with
 * `VERDICT: no reference goes to a declaration the compiler does not bind it to`.
 * The verdict was unfalsifiable by omission, because the number of references it
 * was about was never printed.
 *
 * So every verifier now declares its own skip categories up front -- including
 * categories it expects to be zero -- and prints:
 *
 *     COVERAGE: ran <n> / skipped <n> (<why> <n>, <why> <n>) / wrong <n>
 *
 * A category that is declared and zero is printed, because "0" and "this category
 * was never counted" must not look the same.  `ran + skipped` is the corpus, and
 * `wrong` is the number the verdict is actually about; a caller that has a skip
 * category it considers a defect (`crashed-file`) should also refuse to pass --
 * see `defect()`.
 *
 * Not a library: it is three fields and a formatter, deliberately, so that reading
 * one harness's final lines tells a reader exactly what was measured.
 */
public final class Coverage {

    private final Map<String, Long> skipped = new LinkedHashMap<>();
    private long ran;
    private long wrong;
    private long defect;

    /** Declare a skip category, so it is printed even when it stays at zero. */
    public Coverage category(String why) {
        skipped.putIfAbsent(why, 0L);
        return this;
    }

    /** A category that is a harness defect rather than a limit of the method. */
    public Coverage defectCategory(String why) {
        skipped.putIfAbsent(why, 0L);
        defectCategories.add(why);
        return this;
    }

    private final List<String> defectCategories = new ArrayList<>();

    public Coverage ran(long n) {
        this.ran = n;
        return this;
    }

    public Coverage wrong(long n) {
        this.wrong = n;
        return this;
    }

    public Coverage skipped(String why, long n) {
        skipped.merge(why, n, Long::sum);
        return this;
    }

    /** Add to the count of a category declared as a defect. */
    public Coverage defect(String why, long n) {
        if (!defectCategories.contains(why)) {
            throw new IllegalArgumentException(
                    "`" + why + "` was not declared with defectCategory() before defect()");
        }
        skipped.merge(why, n, Long::sum);
        defect += n;
        return this;
    }

    public long ran() { return ran; }

    public long wrong() { return wrong; }

    public long skippedTotal() {
        long total = 0;
        for (long v : skipped.values()) total += v;
        return total;
    }

    /** The number in a category, whether or not it was ever incremented. */
    public long get(String why) {
        Long v = skipped.get(why);
        return v == null ? 0L : v;
    }

    /** True when a defect category is non-zero: the run cannot be called clean. */
    public boolean hasDefect() { return defect > 0; }

    /** The measurement, as one line. */
    public String line() {
        StringBuilder sb = new StringBuilder(160);
        sb.append("COVERAGE: ran ").append(ran).append(" / skipped ").append(skippedTotal());
        sb.append(" (");
        boolean first = true;
        for (Map.Entry<String, Long> e : skipped.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append(' ').append(e.getValue());
            first = false;
        }
        if (first) sb.append("nothing skipped");
        sb.append(") / wrong ").append(wrong);
        return sb.toString();
    }

    /**
     * Print the block and the line.
     *
     * The block is what a reader needs to see the arithmetic; the line is what a
     * build log keeps.  Printing the same numbers twice is deliberate.
     */
    public void print() {
        System.out.println();
        System.out.println("== coverage: what ran, what did not, and why ==");
        System.out.println("  ran (judged)            : " + ran);
        System.out.println("  skipped                 : " + skippedTotal());
        for (Map.Entry<String, Long> e : skipped.entrySet()) {
            String flag = defectCategories.contains(e.getKey()) ? "  <- a defect, not a limit" : "";
            System.out.println("      " + pad(e.getKey(), 22) + pad(String.valueOf(e.getValue()), 8)
                    + flag);
        }
        System.out.println("  wrong                   : " + wrong);
        System.out.println("  corpus accounted for    : " + (ran + skippedTotal())
                + "  (ran + skipped)");
        System.out.println();
        System.out.println(line());
    }

    /**
     * The total a reader should reconcile against: every reference, call, file --
     * whatever this harness counts -- is either `ran` or in exactly one category.
     */
    public long accounted() { return ran + skippedTotal(); }

    static String pad(String s, int n) {
        if (s.length() >= n) return s + "  ";
        StringBuilder sb = new StringBuilder(n);
        sb.append(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }
}
