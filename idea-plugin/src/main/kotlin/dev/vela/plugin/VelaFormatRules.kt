package dev.vela.plugin

import com.intellij.formatting.Spacing
import com.intellij.psi.tree.IElementType

/**
 * One *significant* token: a token that is not whitespace.
 *
 * Nothing here is a PSI or AST type, and that is the point. The spacing and
 * indent rules are a pure function of the token sequence, so the exact same
 * rules that drive the platform's formatter can also be run over a plain string
 * by a harness outside the IDE — which is the only way "formatting twice equals
 * formatting once" can be checked without a running editor.
 */
class VelaToken(
    @JvmField val type: IElementType?,
    @JvmField val text: String,
    @JvmField val start: Int,
    @JvmField val end: Int,
)

/**
 * The five numbers `Spacing.createSpacing` takes, in a class of our own so the
 * rules can be expressed, compared and printed without the platform on the
 * classpath.
 *
 * The signature, spelled out because the last parameter is not the flag its name
 * suggests:
 *
 *     createSpacing(int minSpaces, int maxSpaces, int minLineFeeds,
 *                   boolean keepLineBreaks, int keepBlankLines)
 *
 * `keepBlankLines` is a *count of blank lines to keep*, not a yes/no. The first
 * version of this file passed a `Boolean` there and the compiler rejected it; the
 * field is an `Int` now, so the same mistake cannot be made twice.
 */
class VelaSpacing(
    @JvmField val minSpaces: Int,
    @JvmField val maxSpaces: Int,
    @JvmField val minLineFeeds: Int,
    @JvmField val keepLineBreaks: Boolean,
    /** How many *blank* lines may survive at this gap. A count, not a flag. */
    @JvmField val keepBlankLines: Int,
) {
    fun toSpacing(): Spacing =
        Spacing.createSpacing(minSpaces, maxSpaces, minLineFeeds, keepLineBreaks, keepBlankLines)

    override fun toString(): String =
        "spaces $minSpaces..$maxSpaces, lf>=$minLineFeeds, keepLf=$keepLineBreaks, keepBlank=$keepBlankLines"
}

/**
 * Vela's formatting rules, as a function of the token sequence alone.
 *
 * The single invariant every rule below obeys, and the reason a mis-formatting
 * cannot change what a program means:
 *
 *   **No rule ever removes a line break.**
 *
 * Vela's statements end at a newline (`SPEC.md` §1: "inside `{ }` … a newline
 * separates statements"), so two tokens that were on different lines must still
 * be on different lines after formatting; the only thing the formatter is
 * allowed to move between statements is *horizontal* whitespace and the
 * indentation of a line. Every rule therefore has `keepLineBreaks = true`, and
 * the two rules that force a break (`{` and `}`) force a minimum of one line
 * feed — a minimum, never a maximum of zero. Whitespace can only be added
 * inside a line, removed inside a line, or turned into a line feed.
 *
 * The rules themselves, in the order they are checked:
 *
 *   1. `{` on the same line as its `def`/`if`/`while`/`for`/`struct` — one
 *      space before it, and the body starts on the next line.
 *   2. `}` starts its own line; an empty block is written `{}`.
 *   3. no space inside `(` `)` `[` `]`, `, ` between arguments, `a.b`, `a[i]`,
 *      `x: int` without a space before the colon and one after it.
 *   4. spaces around binary operators, and none after the prefix `-`/`!`/`~`.
 *   5. one space between two words (`def main`, `mut x`, `and y`).
 *   6. a comment owns the rest of its line: one space before a trailing
 *      comment, and nothing at all after a comment.
 */
object VelaFormatRules {

    /**
     * Vela's indent is four spaces: `SPEC.md` §1 examples, and every file in
     * `tests/build/`, are written that way. This is the fallback for a
     * `CodeStyleSettings` that has no Vela section yet, so "no settings" cannot
     * mean "no indentation".
     */
    const val INDENT_SIZE = 4

    /** The operators that are written with one space on each side. */
    private val BINARY = hashSetOf(
        "+", "-", "*", "/", "%", "**", "//",
        "==", "!=", "<", ">", "<=", ">=", "<<", ">>",
        "&", "|", "^", "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
        "**=", "//=", "<<=", ">>=", "->",
    )

    /** Operators that only ever have a right operand, so nothing follows them. */
    private val PREFIX_ONLY = hashSetOf("!", "~")

    /**
     * How many blank lines the formatter may keep at one gap.
     *
     * One is the platform's own default for code
     * (`CommonCodeStyleSettings.KEEP_BLANK_LINES_IN_CODE`), and it is also the
     * largest number that cannot change what a program means: a blank line holds
     * no token, so collapsing two blank lines into one still leaves a line feed
     * between whatever is on either side of them. Nothing in this rule set can
     * turn a newline into a space — that is the invariant in the KDoc above.
     */
    private const val KEEP_BLANK_LINES = 1

    /** No space at all, and whatever line structure exists is left alone. */
    private val GLUE = VelaSpacing(0, 0, 0, true, KEEP_BLANK_LINES)

    /** Exactly one space. */
    private val ONE_SPACE = VelaSpacing(1, 1, 0, true, KEEP_BLANK_LINES)

    /**
     * The body of a block starts on the next line.
     *
     * `keepLineBreaks = false` here does *not* mean "delete line breaks": with
     * `minLineFeeds = 1` at least one is always written, so this rule can only add
     * a break or trim blank lines down to [KEEP_BLANK_LINES]. That is the whole
     * reason `{` and `}` are allowed to be the two rules that touch the line
     * structure at all.
     */
    private val AFTER_OPEN_BRACE = VelaSpacing(0, 0, 1, false, KEEP_BLANK_LINES)

    /** `}` starts its own line. Same reasoning as [AFTER_OPEN_BRACE]. */
    private val BEFORE_CLOSE_BRACE = VelaSpacing(0, 0, 1, false, KEEP_BLANK_LINES)

    /** The result of the whole rule set for one file's worth of tokens. */
    class Analysis(
        @JvmField val tokens: List<VelaToken>,
        /** Brace nesting depth *at* each token, with `}` counted one level out. */
        @JvmField val depths: IntArray,
        /** `spacings[i]` is the gap between token `i - 1` and token `i`. */
        @JvmField val spacings: List<VelaSpacing>,
    ) {
        /** The gap before the very first token of the file: nothing. */
        fun spacingAt(index: Int): VelaSpacing =
            if (index > 0 && index < spacings.size) spacings[index] else BEFORE_THE_FILE

        private companion object {
            /**
             * There is no whitespace before the first token of a file, and saying
             * so here rather than reaching for the rule set's `GLUE` keeps this
             * class readable on its own.
             */
            private val BEFORE_THE_FILE = VelaSpacing(0, 0, 0, true, 1)
        }
    }

    /**
     * Depth and spacing for every token, in one pass, so that the block tree and
     * the offline harness cannot disagree about what the rules say.
     */
    fun analyse(tokens: List<VelaToken>): Analysis {
        val depths = IntArray(tokens.size)
        val spacings = ArrayList<VelaSpacing>(tokens.size)
        var depth = 0
        for (i in tokens.indices) {
            val text = tokens[i].text
            // `}` belongs to the level it closes; `{` opens the level its body
            // lives at. Without that, `}` would be indented one level too deep.
            if (text == "}") depth--
            if (depth < 0) depth = 0
            depths[i] = depth
            if (text == "{") depth++
            spacings.add(spacing(tokens, i))
        }
        return Analysis(tokens, depths, spacings)
    }

    /** The gap between `tokens[index - 1]` and `tokens[index]`. */
    fun spacing(tokens: List<VelaToken>, index: Int): VelaSpacing {
        if (index <= 0) return GLUE
        val prev = tokens[index - 1]
        val next = tokens[index]
        val before = if (index >= 2) tokens[index - 2] else null
        val p = prev.text
        val n = next.text

        // An empty block is written `{}` — folding `{` and `}` apart would give
        // a block whose body is a line feed, which is a different tree.
        if (p == "{" && n == "}") return GLUE

        // `{` closes its line and `}` opens one.
        if (p == "{") return AFTER_OPEN_BRACE
        if (n == "}") return BEFORE_CLOSE_BRACE

        // A `#` comment runs to the end of its line, so nothing may be pushed
        // after it, and the line break that ends it must survive.
        if (prev.type == VelaTokenTypes.COMMENT) return GLUE
        if (next.type == VelaTokenTypes.COMMENT) return ONE_SPACE

        // `def f() {`, `while i < n {`, `struct Vec2 {`.
        if (n == "{") return ONE_SPACE

        // No space just inside a bracket pair: `range(0, n)`, `Array[int, 4]`,
        // `buf[0]`.
        if (p == "(" || p == "[" || n == ")" || n == "]" || n == "[") return GLUE

        // `, ` between arguments and elements.
        if (n == ",") return GLUE
        if (p == ",") return ONE_SPACE

        // `x: int` — the types are written after a colon with no space before it.
        if (n == ":") return GLUE
        if (p == ":") return ONE_SPACE

        // `a.b`, `p.x`.
        if (p == "." || n == ".") return GLUE

        // `!x`, `~x` — a prefix operator belongs to its operand.
        if (p in PREFIX_ONLY) return GLUE

        // `-` is the one operator that is both: `n - 1` but `-n`, `return -1`,
        // `a * -1`. Which one it is depends only on what came before it.
        if (p == "-") return if (minusIsUnary(tokens, index - 1)) GLUE else ONE_SPACE
        if (n == "-") return if (minusIsUnary(tokens, index)) GLUE else ONE_SPACE

        // `a and !b` — a prefix operator that follows a word keeps its space.
        if (n in PREFIX_ONLY) return ONE_SPACE

        // One space between two words: `def main`, `mut buf`, `pure def`,
        // `parallel for`, `else if`, `x == 1`.
        if (isWordLike(prev) && isWordLike(next)) return ONE_SPACE

        // `f(x)` — no space between a name and its argument list; a keyword
        // keeps one, so `return (a + b)` reads as two words.
        if (n == "(") return if (prev.type == VelaTokenTypes.KEYWORD) ONE_SPACE else GLUE

        // `) {` is handled by the `{` rule above; `} else {` needs its space.
        if (p == ")" || p == "]" || p == "}") return if (isWordLike(next)) ONE_SPACE else GLUE

        // Everything else that is an operator is a binary one.
        if (p in BINARY || n in BINARY) return ONE_SPACE

        return GLUE
    }

    /**
     * `-` is unary when what precedes it cannot end a value: the start of the
     * file, another operator (`a * -1`, `= -1`), an opening bracket (`(-1)`),
     * a comma (`f(a, -1)`), or a keyword (`return -1`, `and -1`).
     */
    private fun minusIsUnary(tokens: List<VelaToken>, index: Int): Boolean {
        if (index <= 0) return true
        val before = tokens[index - 1]
        if (before.type == VelaTokenTypes.KEYWORD) return true
        if (before.type == VelaTokenTypes.OPERATOR) return true
        if (before.type == VelaTokenTypes.COMMENT) return true
        val text = before.text
        return text == "(" || text == "[" || text == "{" || text == ","
    }

    /** A word: something that reads as one unit and takes a space as a separator. */
    private fun isWordLike(token: VelaToken): Boolean {
        val type = token.type ?: return false
        return type == VelaTokenTypes.KEYWORD ||
            type == VelaTokenTypes.IDENTIFIER ||
            type == VelaTokenTypes.NUMBER ||
            type == VelaTokenTypes.STRING
    }
}
