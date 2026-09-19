package dev.vela.plugin

/**
 * The Vela syntax tree -- a real parser, written here, with no platform types
 * anywhere in it.
 *
 * WHY THIS FILE EXISTS AT ALL
 *
 * This plugin used to build a *flat* PSI tree: one leaf per token and no
 * structure, on the argument that the compiler decides what a Vela program
 * means, so a second parser would be a second opinion.  That argument confuses
 * syntax with meaning.  Where a block starts and ends, which statement an
 * expression belongs to, where a `for` header stops -- these are not semantic
 * claims, and without them the structure view, folding, "select enclosing
 * statement", breadcrumbs, brace matching and every future quick fix are
 * guesses over a token stream instead of facts about a tree.
 *
 * So this is a parser, and the one rule that keeps it from being a second
 * opinion is the one it is held to: **it must produce the same tree the
 * compiler produces**.  `selfhost/parts/parser.vel` is the authority; every
 * node kind here carries the compiler's name for it, `VelaSyntaxDump.kt`
 * prints this tree in the compiler's own canonical format, and the harness at
 * `idea-plugin/build/tools/src/AstDiff.java` runs both over the whole corpus
 * and diffs them line by line.  A parser that agrees with `vm.exe parse` on
 * every file the language has is a mirror, not a second opinion.
 *
 * WHAT IS MIRRORED, DELIBERATELY
 *
 *  * The **same scanner**, with the same tokens in the same order.  In
 *    particular a NEWLINE token is inserted at a newline only at bracket depth
 *    zero (`(` and `[` suppress it, `{` does not), the same "the last token is
 *    always a newline" rule applies, and `->` is one operator.
 *  * The same **precedence ladder**, which is visible as the call graph below:
 *    to read the grammar, read the `parseX` signatures in order.
 *  * The compiler's **desugaring**, because the tree is compared to its dump:
 *    `+=` becomes an `augassign` carrying the base operator it stands for,
 *    `x: int = e` becomes a `decl` whose target is a `name`, `for i in
 *    range(n)` gets a synthesised `int 0` start, and a struct field becomes a
 *    `field` where such a node exists.
 *  * The compiler's **refusals** where they are cheap and total -- `elif`,
 *    chained comparisons, tuples, `!` as a unary operator.  A parse of an
 *    invalid file is not required to match anything, but refusing what the
 *    compiler refuses means the two agree on which files are valid, which is
 *    what an editor needs to annotate.
 *
 * WHAT IS NOT MIRRORED: RECOVERY
 *
 * The compiler stops at its first syntax error -- it only ever parses files it
 * accepts.  The plugin is used *while a file is being typed*, so the tree has
 * to be shaped even when the text is not.  [VelaSyntaxParser.recover] is that
 * difference and the only one: on an error the parser reports it, throws the
 * partial statement away, and skips tokens to the next statement boundary --
 * the next NEWLINE at the bracket depth it started at, a `}` that would close
 * the enclosing block, or end of file.  This is ordinary panic-mode recovery,
 * and it means a missing `}` costs one statement's worth of shape, not the
 * rest of the file.
 *
 * The scanner and the parser never look at a character outside this file and
 * never touch the platform, which is what makes the whole thing testable
 * without booting an IDE.
 */

// ---------------------------------------------------------------------------
// tokens

/** What a token is, as far as a parser is concerned. */
enum class VelaTokKind {
    /** A name, or one of the language's words (`def`, `if`, `range`, ...). */
    NAME,

    /** An integer or float literal. */
    NUMBER,

    /** A string literal; [VelaTok.stringFlag] says whether it has escapes. */
    STRING,

    /** An operator or a bracket; [VelaTok.op] is its code. */
    OP,

    /** A statement separator, emitted by the scanner and never written out. */
    NEWLINE,

    /** End of input. */
    EOF,
}

/** How the scanner itself failed, if it did. */
enum class VelaScanErrorKind {
    NONE,
    UNTERMINATED_STRING,
    NUMBER_WITHOUT_DIGITS,
    INTEGER_LITERAL_TOO_LARGE,
    INTEGER_LITERAL_WITH_NO_DIGITS,
    STRAY_BACKSLASH,
}

/**
 * One token.
 *
 * `start` and `end` are byte offsets into the source, exactly as the compiler's
 * lexer records them, which is what lets a PSI element point at the same text
 * the compiler pointed at.  The scanner here is character based; every file
 * this plugin can be pointed at is ASCII in the code before its first string
 * literal, and a Vela string literal is bytes the compiler itself counts one
 * at a time, so offsets agree.
 */
class VelaTok(
    @JvmField val kind: VelaTokKind,
    @JvmField val start: Int,
    @JvmField val end: Int,
    /** `keywordId` for a name, an operator code for an operator, else 0. */
    @JvmField val code: Int,
    /** The integer value of a NUMBER, when [VelaTok.isInt]. */
    @JvmField val intValue: Long,
    /** The float value of a NUMBER, when not [VelaTok.isInt]. */
    @JvmField val floatValue: Double,
    @JvmField val isInt: Boolean,
    /** A string literal's escape flag: 1 when it contains a backslash. */
    @JvmField val stringFlag: Int,
    /** The characters of an integer literal that did not fit, else null. */
    @JvmField val badText: String?,
)

/** The scanner's output: the tokens, and what (if anything) went wrong. */
class VelaScanResult(
    @JvmField val toks: List<VelaTok>,
    @JvmField val error: VelaScanErrorKind,
    @JvmField val errorStart: Int,
    @JvmField val errorEnd: Int,
) {
    @JvmField val ok: Boolean = error == VelaScanErrorKind.NONE
}

// ---------------------------------------------------------------------------
// the operator and keyword tables -- the compiler's numbering
//
// The codes are `selfhost/vela.vel`'s (`op1_code`, `scan_op`, `keyword_id`),
// because the dump prints an operator *through* its code and two numberings
// would be two dialects of one language.
//
// `@JvmField val`, not `const val`, and deliberately: a Kotlin `const` is inlined
// at every use site, so the object holding it never appears in any class file that
// uses it -- the tables below would read as if they had been written with bare
// numbers, and the shipped `VelaOps`/`VelaKw` classes would be classes nothing can
// call.  The verifier's dead-class check caught exactly that and was right to: a
// class that ships in the jar and is reachable from nothing is a liability, whatever
// it holds.  With a real field the tables read the codes at class-init time and both
// objects are genuinely live.

object VelaOps {
    @JvmField val PLUS = 1
    @JvmField val MINUS = 2
    @JvmField val STAR = 3
    @JvmField val SLASH = 4
    @JvmField val PERCENT = 5
    @JvmField val LT = 6
    @JvmField val GT = 7
    @JvmField val EQ = 8
    @JvmField val LPAREN = 9
    @JvmField val RPAREN = 10
    @JvmField val COMMA = 11
    @JvmField val COLON = 12
    @JvmField val DOT = 13
    @JvmField val LBRACKET = 14
    @JvmField val RBRACKET = 15
    @JvmField val EXCL = 16
    @JvmField val AMP = 17
    @JvmField val PIPE = 18
    @JvmField val CARET = 19
    @JvmField val FLOORDIV = 20
    @JvmField val POW = 21
    @JvmField val EQEQ = 22
    @JvmField val NE = 23
    @JvmField val LE = 24
    @JvmField val GE = 25
    @JvmField val ARROW = 26
    @JvmField val PLUSEQ = 27
    @JvmField val MINUSEQ = 28
    @JvmField val STAREQ = 29
    @JvmField val SLASHEQ = 30
    @JvmField val PERCENTEQ = 31
    @JvmField val TILDE = 32
    @JvmField val LBRACE = 33
    @JvmField val RBRACE = 34
    @JvmField val SHL = 35
    @JvmField val SHR = 36
    @JvmField val SHLEQ = 37
    @JvmField val SHREQ = 38
    @JvmField val AMPEQ = 39
    @JvmField val PIPEEQ = 40
    @JvmField val CARETEQ = 41
    @JvmField val POWEQ = 42
    @JvmField val FLOORDIVEQ = 43
}

/** Keyword ids, from `keyword_id` in `selfhost/vela.vel`. */
object VelaKw {
    @JvmField val DEF = 1
    @JvmField val RETURN = 2
    @JvmField val IF = 3
    @JvmField val ELIF = 4
    @JvmField val ELSE = 5
    @JvmField val WHILE = 6
    @JvmField val FOR = 7
    @JvmField val IN = 8
    @JvmField val RANGE = 9
    @JvmField val BREAK = 10
    @JvmField val CONTINUE = 11
    @JvmField val PASS = 12
    @JvmField val AND = 13
    @JvmField val OR = 14
    @JvmField val NOT = 15
    @JvmField val TRUE = 16
    @JvmField val FALSE = 17
    @JvmField val NONE = 18
    @JvmField val MUT = 19
    @JvmField val PURE = 20
    @JvmField val STRUCT = 21
    @JvmField val PARALLEL = 22
    @JvmField val EXTERN = 23
}

/**
 * The operator table, longest match first -- the order is the compiler's, and
 * it matters: `<<=` must not be read as `<<` and `=`.
 */
val VELA_OPERATOR_TABLE: Array<Pair<String, Int>> = arrayOf(
    "**=" to VelaOps.POWEQ,
    "//=" to VelaOps.FLOORDIVEQ,
    "<<=" to VelaOps.SHLEQ,
    ">>=" to VelaOps.SHREQ,
    "//" to VelaOps.FLOORDIV,
    "**" to VelaOps.POW,
    "==" to VelaOps.EQEQ,
    "!=" to VelaOps.NE,
    "<=" to VelaOps.LE,
    ">=" to VelaOps.GE,
    "->" to VelaOps.ARROW,
    "+=" to VelaOps.PLUSEQ,
    "-=" to VelaOps.MINUSEQ,
    "*=" to VelaOps.STAREQ,
    "/=" to VelaOps.SLASHEQ,
    "%=" to VelaOps.PERCENTEQ,
    "<<" to VelaOps.SHL,
    ">>" to VelaOps.SHR,
    "&=" to VelaOps.AMPEQ,
    "|=" to VelaOps.PIPEEQ,
    "^=" to VelaOps.CARETEQ,
    "+" to VelaOps.PLUS,
    "-" to VelaOps.MINUS,
    "*" to VelaOps.STAR,
    "/" to VelaOps.SLASH,
    "%" to VelaOps.PERCENT,
    "<" to VelaOps.LT,
    ">" to VelaOps.GT,
    "=" to VelaOps.EQ,
    "&" to VelaOps.AMP,
    "|" to VelaOps.PIPE,
    "^" to VelaOps.CARET,
    "~" to VelaOps.TILDE,
    "!" to VelaOps.EXCL,
    "(" to VelaOps.LPAREN,
    ")" to VelaOps.RPAREN,
    "," to VelaOps.COMMA,
    ":" to VelaOps.COLON,
    "." to VelaOps.DOT,
    "[" to VelaOps.LBRACKET,
    "]" to VelaOps.RBRACKET,
    "{" to VelaOps.LBRACE,
    "}" to VelaOps.RBRACE,
)

/**
 * The words the language owns.
 *
 * `range` is in this set because the compiler's `keyword_id` returns 9 for it:
 * `range` is a *word*, not a function name, so `range(0, 5)` is only legal as
 * a `for` header and `x: int = range(0, 5)` is a syntax error -- which this
 * parser reproduces, because it must agree with the compiler about which files
 * are valid.
 */
val VELA_KEYWORDS: Map<String, Int> = run {
    val m = HashMap<String, Int>(32)
    m["def"] = VelaKw.DEF
    m["return"] = VelaKw.RETURN
    m["if"] = VelaKw.IF
    m["elif"] = VelaKw.ELIF
    m["else"] = VelaKw.ELSE
    m["while"] = VelaKw.WHILE
    m["for"] = VelaKw.FOR
    m["in"] = VelaKw.IN
    m["range"] = VelaKw.RANGE
    m["break"] = VelaKw.BREAK
    m["continue"] = VelaKw.CONTINUE
    m["pass"] = VelaKw.PASS
    m["and"] = VelaKw.AND
    m["or"] = VelaKw.OR
    m["not"] = VelaKw.NOT
    m["True"] = VelaKw.TRUE
    m["False"] = VelaKw.FALSE
    m["None"] = VelaKw.NONE
    m["mut"] = VelaKw.MUT
    m["pure"] = VelaKw.PURE
    m["struct"] = VelaKw.STRUCT
    m["parallel"] = VelaKw.PARALLEL
    m["extern"] = VelaKw.EXTERN
    m
}

/** The augmented-assignment operators, mapped to the operator they stand for. */
fun velaAugBase(op: Int): Int = when (op) {
    VelaOps.PLUSEQ -> VelaOps.PLUS
    VelaOps.MINUSEQ -> VelaOps.MINUS
    VelaOps.STAREQ -> VelaOps.STAR
    VelaOps.SLASHEQ -> VelaOps.SLASH
    VelaOps.PERCENTEQ -> VelaOps.PERCENT
    VelaOps.SHLEQ -> VelaOps.SHL
    VelaOps.SHREQ -> VelaOps.SHR
    VelaOps.AMPEQ -> VelaOps.AMP
    VelaOps.PIPEEQ -> VelaOps.PIPE
    VelaOps.CARETEQ -> VelaOps.CARET
    VelaOps.POWEQ -> VelaOps.POW
    VelaOps.FLOORDIVEQ -> VelaOps.FLOORDIV
    else -> 0
}

fun velaIsAugAssign(op: Int): Boolean = velaAugBase(op) != 0

fun velaIsCmpOp(op: Int): Boolean = op == VelaOps.LT || op == VelaOps.GT ||
    op == VelaOps.EQEQ || op == VelaOps.NE || op == VelaOps.LE || op == VelaOps.GE

/**
 * An operator's spelling, as the compiler's dump prints it.
 *
 * The codes the dump can never be handed (the augmented ones, `!`, `=`, `:`)
 * are spelled out here anyway rather than mapped to something wrong: a printer
 * that lies about what it was given is worse than one that cannot be asked.
 */
fun velaOpName(op: Int): String = when (op) {
    VelaOps.PLUS -> "+"
    VelaOps.MINUS -> "-"
    VelaOps.STAR -> "*"
    VelaOps.SLASH -> "/"
    VelaOps.PERCENT -> "%"
    VelaOps.LT -> "<"
    VelaOps.GT -> ">"
    VelaOps.EQ -> "="
    VelaOps.LPAREN -> "("
    VelaOps.RPAREN -> ")"
    VelaOps.COMMA -> ","
    VelaOps.COLON -> ":"
    VelaOps.DOT -> "."
    VelaOps.LBRACKET -> "["
    VelaOps.RBRACKET -> "]"
    VelaOps.EXCL -> "!"
    VelaOps.AMP -> "&"
    VelaOps.PIPE -> "|"
    VelaOps.CARET -> "^"
    VelaOps.FLOORDIV -> "//"
    VelaOps.POW -> "**"
    VelaOps.EQEQ -> "=="
    VelaOps.NE -> "!="
    VelaOps.LE -> "<="
    VelaOps.GE -> ">="
    VelaOps.ARROW -> "->"
    VelaOps.PLUSEQ -> "+="
    VelaOps.MINUSEQ -> "-="
    VelaOps.STAREQ -> "*="
    VelaOps.SLASHEQ -> "/="
    VelaOps.PERCENTEQ -> "%="
    VelaOps.TILDE -> "~"
    VelaOps.LBRACE -> "{"
    VelaOps.RBRACE -> "}"
    VelaOps.SHL -> "<<"
    VelaOps.SHR -> ">>"
    VelaOps.SHLEQ -> "<<="
    VelaOps.SHREQ -> ">>="
    VelaOps.AMPEQ -> "&="
    VelaOps.PIPEEQ -> "|="
    VelaOps.CARETEQ -> "^="
    VelaOps.POWEQ -> "**="
    VelaOps.FLOORDIVEQ -> "//="
    else -> "op?" + op
}

/**
 * The spelling of a *unary* operator, as the compiler's dump prints it.
 *
 * `not` is the reason this exists: the compiler's `parse_unary` stores the
 * keyword id 15 in the node's operator slot, and its `op_name` -- which the
 * binary operators share -- has a case for 15 returning "not".  A printer that
 * asked `op_name` for a unary `not` and got its fall-through would print
 * `unary op=>>`, which is exactly the sort of quiet nonsense this harness is
 * for (and it did, on `tests/build/bool_logic.vel`, until this line existed).
 */
fun velaUnaryOpName(op: Int): String = if (op == VelaKw.NOT) "not" else velaOpName(op)

// ---------------------------------------------------------------------------
// the scanner

/**
 * Turns Vela source into the token list the compiler's lexer would produce.
 *
 * This does not replace [VelaLexer]: that one is the editor's lexer, and it is
 * shaped by the editor's questions (colour this, where does this comment end).
 * This one is shaped by the parser's question (what comes next), and the two
 * are held together by the differential harness -- `AstDiff` compares this
 * parser's output to the compiler's, so a scanner that disagreed would show up
 * as a diff rather than as a mystery.
 */
object VelaSyntaxScanner {

    fun scan(src: String): VelaScanResult {
        val toks = ArrayList<VelaTok>(src.length / 4 + 16)
        val n = src.length
        var pos = 0
        var line = 1
        // How many `(` or `[` are open.  Newlines are insignificant inside
        // those, so a call or an array literal may be laid out over several
        // lines; `{` deliberately does not count, because inside a block a
        // newline separates statements.  This is the compiler's `cx.aux`.
        var depth = 0
        var err = VelaScanErrorKind.NONE
        var errStart = 0
        var errEnd = 0

        fun fail(kind: VelaScanErrorKind, start: Int, end: Int) {
            if (err != VelaScanErrorKind.NONE) return
            err = kind
            errStart = start
            errEnd = end
        }

        // A byte-order mark is a writing artefact, not part of the program:
        // several Windows tools add one, and refusing to lex those files would
        // be strictness in the wrong place.  The cursor is moved past it rather
        // than the text being trimmed, so every offset still addresses the real
        // source.
        //
        // Both spellings are here because the two callers hand this scanner
        // different things: the platform decodes a file as UTF-8 (so the mark is
        // one character, U+FEFF), and the differential harness reads it as one
        // character per byte (so the mark is the three bytes EF BB BF).  A
        // scanner that recognised only one of them would disagree with the
        // compiler about a file with a mark in it, which is exactly what
        // `tests/build/bom_first_byte.vel` is for.
        if (n >= 3 && src[0] == '\u00EF' && src[1] == '\u00BB' && src[2] == '\u00BF') {
            pos = 3
        } else if (n >= 1 && src[0] == '\uFEFF') {
            pos = 1
        }

        while (pos < n && err == VelaScanErrorKind.NONE) {
            val c = src[pos]
            if (c == '\n') {
                if (depth == 0 && toks.isNotEmpty() && toks[toks.size - 1].kind != VelaTokKind.NEWLINE) {
                    toks.add(newline(pos))
                }
                pos++
                line++
                continue
            }
            if (c == ' ' || c == '\t') {
                pos++
                continue
            }
            if (c == '\r') {
                pos++
                continue
            }
            if (c == '#') {
                while (pos < n && src[pos] != '\n') pos++
                continue
            }
            if (c == '\\') {
                if (pos + 1 < n && src[pos + 1] == '\n') {
                    pos += 2
                    line++
                } else {
                    fail(VelaScanErrorKind.STRAY_BACKSLASH, pos, pos + 1)
                }
                continue
            }
            if (c.isDigit()) {
                pos = scanNumber(src, pos, toks) { kind, a, b -> fail(kind, a, b) }
                continue
            }
            if (isNameStart(c)) {
                val start = pos
                while (pos < n && isNamePart(src[pos])) pos++
                val text = src.substring(start, pos)
                val kw = VELA_KEYWORDS[text]
                toks.add(
                    VelaTok(
                        VelaTokKind.NAME, start, pos, kw ?: 0,
                        0L, 0.0, true, 0, null,
                    )
                )
                continue
            }
            if (c == '"' || c == '\'') {
                val scanned = scanString(src, pos)
                if (scanned == null) {
                    fail(VelaScanErrorKind.UNTERMINATED_STRING, pos, pos + 1)
                    continue
                }
                toks.add(scanned)
                pos = scanned.end + 1
                continue
            }
            val (code, width) = matchOperator(src, pos)
            if (code == 0) {
                fail(VelaScanErrorKind.STRAY_BACKSLASH, pos, pos + 1)
                continue
            }
            if (code == VelaOps.LPAREN || code == VelaOps.LBRACKET) {
                depth++
            } else if (code == VelaOps.RPAREN || code == VelaOps.RBRACKET) {
                if (depth > 0) depth--
            }
            toks.add(
                VelaTok(VelaTokKind.OP, pos, pos + width, code, 0L, 0.0, true, 0, null)
            )
            pos += width
        }

        // The tokens scanned before the failure are kept, and the list is closed with
        // the same newline-and-EOF the successful path appends.  The compiler stops
        // here; an editor cannot, and throwing the prefix away costs the whole file
        // for one unreadable token: with an empty token list the parser cannot build
        // a single declaration, so `literal_overflow.vel` (one integer literal too
        // large) produced an empty structure view and an empty completion list.
        // Measured by `SymbolDiff`, which reported 0 declarations from the tree
        // against 1 from the token scan before this change.
        if (toks.isNotEmpty() && toks[toks.size - 1].kind != VelaTokKind.NEWLINE) {
            toks.add(newline(pos))
        }
        toks.add(
            VelaTok(VelaTokKind.EOF, pos, pos, 0, 0L, 0.0, true, 0, null)
        )
        if (err != VelaScanErrorKind.NONE) {
            return VelaScanResult(toks, err, errStart, errEnd)
        }
        return VelaScanResult(toks, VelaScanErrorKind.NONE, 0, 0)
    }

    private fun newline(pos: Int): VelaTok =
        VelaTok(VelaTokKind.NEWLINE, pos, pos + 1, 0, 0L, 0.0, true, 0, null)

    private fun isDigit(c: Char) = c in '0'..'9'
    private fun isNameStart(c: Char) = c == '_' || c.isLetter()
    private fun isNamePart(c: Char) = c == '_' || c.isLetterOrDigit()

    /**
     * Longest match, in the compiler's order.  Returns the code and width, or
     * 0 for a character that is not part of Vela.
     */
    private fun matchOperator(src: String, pos: Int): Pair<Int, Int> {
        for ((text, code) in VELA_OPERATOR_TABLE) {
            if (pos + text.length <= src.length && src.startsWith(text, pos)) {
                return code to text.length
            }
        }
        return 0 to 0
    }

    /**
     * A string literal, from its opening quote.
     *
     * The returned token's [VelaTok.end] is the offset of the closing quote, so
     * the token spans quote..quote+1 and the *contents* are `start + 1 until
     * end` -- which is exactly the (offset, length) pair the compiler hands its
     * dumper, and therefore the pair this parser has to carry too.
     *
     * A literal that runs into a newline before its closing quote is not a
     * literal: the compiler reports "unterminated string literal" and stops.
     */
    private fun scanString(src: String, quoteAt: Int): VelaTok? {
        val n = src.length
        val quote = src[quoteAt]
        var pos = quoteAt + 1
        val start = pos
        var esc = 0
        var closed = false
        while (pos < n) {
            val c = src[pos]
            if (c == '\n') break
            if (c == '\\') {
                esc = 1
                pos += 2
            } else if (c == quote) {
                closed = true
                break
            } else {
                pos++
            }
        }
        if (!closed) return null
        // pos is the closing quote; end is that quote, and the caller resumes
        // one past it.
        val tok = VelaTok(
            VelaTokKind.STRING, start, pos, 0, 0L, 0.0, true, esc, null,
        )
        return tok
    }

    /**
     * A number, from its first digit.
     *
     * `0x`/`0o`/`0b` take the letter/digit/underscore run after the prefix and
     * drop the underscores, exactly as the compiler does -- so `0x_ff` is a
     * number with no digits and is refused, rather than being silently read as
     * zero.
     */
    private fun scanNumber(src: String, at: Int, toks: MutableList<VelaTok>,
                           fail: (VelaScanErrorKind, Int, Int) -> Unit): Int {
        val n = src.length
        val start = at
        var pos = at
        val c = src[pos]

        if (c == '0' && pos + 1 < n) {
            val c2 = src[pos + 1]
            val base = when (c2) {
                'x', 'X' -> 16
                'b', 'B' -> 2
                'o', 'O' -> 8
                else -> 0
            }
            if (base > 0) {
                pos += 2
                var v = 0L
                var seen = false
                while (pos < n) {
                    val ch = src[pos]
                    val d = when {
                        ch == '_' -> {
                            // keep the cursor moving: the token still ends
                            // where the literal does
                            pos++
                            -2
                        }
                        (ch in '0'..'9') || (ch in 'a'..'z') || (ch in 'A'..'Z') -> digitValue(ch, base)
                        else -> -1
                    }
                    if (d == -2) continue
                    if (d < 0) break
                    v = v * base + d
                    seen = true
                    pos++
                }
                if (!seen) {
                    fail(VelaScanErrorKind.NUMBER_WITHOUT_DIGITS, start, pos)
                    return pos
                }
                toks.add(VelaTok(VelaTokKind.NUMBER, start, pos, 0, v, 0.0, true, 0, null))
                return pos
            }
        }

        var iv = 0L
        var over = false
        while (pos < n) {
            val cc = src[pos]
            if (cc == '_') {
                pos++
            } else if (isDigit(cc)) {
                val d = cc - '0'
                // 9223372036854775807 is the largest int.  Once a literal has
                // passed it, the digits stop being accumulated -- the cursor
                // keeps moving, so the token still ends where the literal does,
                // and the refusal is raised below, where the source text is
                // still in reach.
                if (!over) {
                    if (iv > 922337203685477580L) {
                        over = true
                    } else if (iv == 922337203685477580L && d > 7) {
                        over = true
                    } else {
                        iv = iv * 10 + d
                    }
                }
                pos++
            } else {
                break
            }
        }

        var isf = false
        var frac = 0.0
        if (pos + 1 < n && src[pos] == '.' && isDigit(src[pos + 1])) {
            isf = true
            pos++
            var scale = 1.0
            while (pos < n && isDigit(src[pos])) {
                scale *= 10.0
                frac += (src[pos] - '0').toDouble() / scale
                pos++
            }
        }

        var exp = 0
        if (pos < n) {
            val cc2 = src[pos]
            if (cc2 == 'e' || cc2 == 'E') {
                var j = pos + 1
                var esign = 1
                if (j < n) {
                    if (src[j] == '+') {
                        j++
                    } else if (src[j] == '-') {
                        esign = -1
                        j++
                    }
                }
                if (j < n && isDigit(src[j])) {
                    isf = true
                    var ev = 0
                    while (j < n && isDigit(src[j])) {
                        ev = ev * 10 + (src[j] - '0')
                        j++
                    }
                    exp = esign * ev
                    pos = j
                }
            }
        }

        if (isf) {
            var value = iv.toDouble() + frac
            if (exp != 0) value *= Math.pow(10.0, exp.toDouble())
            val flag = if (value == 0.0) 2 else 0
            toks.add(VelaTok(VelaTokKind.NUMBER, start, pos, 0, 0L, value, false, flag, null))
            return pos
        }
        if (over) {
            fail(VelaScanErrorKind.INTEGER_LITERAL_TOO_LARGE, start, pos)
            toks.add(
                VelaTok(VelaTokKind.NUMBER, start, pos, 0, 0L, 0.0, true, 0, src.substring(start, pos))
            )
            return pos
        }
        toks.add(VelaTok(VelaTokKind.NUMBER, start, pos, 0, iv, 0.0, true, 0, null))
        return pos
    }

    private fun digitValue(c: Char, base: Int): Int {
        var v = -1
        if (c in '0'..'9') v = c - '0'
        if (c in 'a'..'z') v = c - 'a' + 10
        if (c in 'A'..'Z') v = c - 'A' + 10
        return if (v >= 0 && v < base) v else -1
    }
}

// ---------------------------------------------------------------------------
// the tree

/**
 * Is this member of a struct body a *field*?
 *
 * Two shapes, and both occur in the corpus:
 *
 *  * `x: T` -- no `mut`, so the parser reads it as a `decl` whose declared type
 *    is set (flag 2).  The compiler stores exactly that node, and its dumper
 *    prints it as a `field` line, not as a `decl` line.
 *  * `mut x: T` -- the parser reads it through its `mut` path, which is a
 *    different statement shape, so the node is given the FIELD kind where it
 *    stands rather than being turned into a `decl` and back again.
 *
 * Both paths are exercised by the corpus rather than by argument:
 * `bench/run_bench.vel`'s `struct Run { mut ncap: int ... }` is the second
 * shape, `tests/build/struct_and_methods.vel`'s fields are the first, and
 * getting either wrong costs a line on every file that uses that shape.
 */
internal fun velaIsFieldMember(c: VelaSyntaxNode): Boolean =
    c.kind == VelaNodeKind.FIELD ||
        (c.kind == VelaNodeKind.DECL && c.typeText.isNotEmpty())

/**
 * A node kind, named after the compiler's node kind of the same shape.
 *
 * The names are the compiler's (`module`, `def`, `param`, `augassign`, `cmp`,
 * ...) because that is the vocabulary the differential harness compares in: a
 * diff line says `def name=fib pure=1 ret=int` on both sides or it does not
 * pass.
 *
 * Some names are this parser's own, for shapes the compiler has but the dump
 * does not print as a node of their own:
 *
 *  * [MODULE_BLOCK] -- the compiler's module node holds a *chain* of
 *    statements and its dumper prints "block" and indents, so the block is a
 *    node here even though the compiler's node layout has no kind for it.
 *  * [UNDECLARED_BLOCK] -- a `{` block the compiler refuses to open because a
 *    parent construct already failed; it exists so recovery still has a shape
 *    to hold the statements in, and it prints as nothing.
 *  * [ERROR] -- a construct the parser could not read.  The compiler has no
 *    such node: it stops instead.
 */
enum class VelaNodeKind {
    MODULE,
    MODULE_BLOCK,
    BLOCK,
    UNDECLARED_BLOCK,
    DEF,
    PARAM,
    STRUCT,
    FIELD,
    DECL,
    ASSIGN,
    AUGASSIGN,
    EXPR,
    RETURN,
    IF,
    ELSE,
    WHILE,
    FOR,
    BREAK,
    CONTINUE,
    PASS,
    INT,
    FLOAT,
    STR,
    BOOL,
    NONE,
    NAME,
    CALL,
    BINOP,
    UNARY,
    BOOLOP,
    CMP,
    INDEX,
    ATTR,
    LIST,
    SLICE,
    ERROR,
}

/**
 * One node.
 *
 * `startTok` and `endTok` are the token indices the node covers, which is what
 * gives the PSI replay exact boundaries: a node's text range on the platform is
 * the range of the tokens it claimed, not a guess.  A synthesised node (the
 * `int 0` a one-argument `range` implies, a `module` before its first
 * statement) has `startTok == -1` and takes its start from its first child.
 */
class VelaSyntaxNode(
    @JvmField val kind: VelaNodeKind,
    @JvmField var startTok: Int,
    @JvmField var endTok: Int,
) {
    @JvmField val children: MutableList<VelaSyntaxNode> = ArrayList(4)

    /** Operator code, keyword id, or a shape flag -- per kind. */
    @JvmField var op: Int = 0

    /** `int`/`bool`: the value the literal denotes. */
    @JvmField var intValue: Long = 0L

    /** A name, a field name, a loop variable, a struct or function name. */
    @JvmField var name: String = ""

    /** The compiler's flag word: 1 mut/parallel, 2 declared/zero-float, 4 pure, 8 extern. */
    @JvmField var flags: Int = 0

    /** `def`: the spelling of the return type, "None" for `-> None`. */
    @JvmField var retType: String = ""

    /** `param`/`decl`/`field`/`struct` field: the spelling of the type. */
    @JvmField var typeText: String = ""

    /** `STR`: the literal's contents, exactly as written, quotes excluded. */
    @JvmField var text: String = ""

    /** `STR`: the literal's contents with its escapes resolved (byte semantics). */
    @JvmField var bytes: ByteArray? = null

    /** `str`: whether the literal contains a backslash. */
    @JvmField var strEscapes: Boolean = false

    /** `float`: the literal as written, so the dump never reformats a number. */
    @JvmField var floatText: String = ""

    /** Whether the node stands for itself in the dump (synthetic nodes do not). */
    @JvmField var printSelf: Boolean = true

    /**
     * A character range for a node that has no tokens.
     *
     * The scan-error node is the case: when the scanner stops -- an unterminated
     * string, an integer literal too large -- there is no token list to index, so
     * `startTok`/`endTok` cannot say where the file broke and the PSI replay would
     * have to put the error at offset 0.  -1 means "no opinion", which is what every
     * other node answers.
     */
    @JvmField var charStart: Int = -1
    @JvmField var charEnd: Int = -1

    fun add(child: VelaSyntaxNode): VelaSyntaxNode {
        children.add(child)
        if (startTok < 0 || (child.startTok >= 0 && child.startTok < startTok)) {
            if (child.startTok >= 0) startTok = child.startTok
        }
        if (child.endTok > endTok) endTok = child.endTok
        return child
    }

    fun adopt(from: VelaSyntaxNode) {
        if (from.startTok >= 0 && (startTok < 0 || from.startTok < startTok)) startTok = from.startTok
        if (from.endTok > endTok) endTok = from.endTok
    }

    override fun toString(): String = kind.name + "[" + startTok + ".." + endTok + "]"
}

/** A parse: the tree, the tokens it was built from, and what went wrong. */
class VelaSyntaxTree(
    @JvmField val root: VelaSyntaxNode,
    @JvmField val toks: List<VelaTok>,
    /** The source, so the dump can print a literal the way it was written. */
    @JvmField val src: String,
    @JvmField val problems: List<VelaSyntaxProblem>,
) {
    @JvmField val complete: Boolean = problems.isEmpty()
}

/** One thing the parser could not read, and where. */
class VelaSyntaxProblem(
    @JvmField val message: String,
    @JvmField val start: Int,
    @JvmField val end: Int,
)

// ---------------------------------------------------------------------------
// the parser

/**
 * The parser.  Recursive descent, one function per precedence level, and the
 * call graph *is* the precedence table -- exactly as the compiler's is, which
 * is how the two can be compared by reading them side by side.
 *
 * Two states matter at every point, and both are modelled on the compiler:
 * `recovering` (we are skipping to a statement boundary) and the bracket depth
 * the scanner already used to decide where a statement can end.
 */
class VelaSyntaxParser private constructor(
    private val src: String,
    private val toks: List<VelaTok>,
) {
    private var pos = 0
    private val problems = ArrayList<VelaSyntaxProblem>(4)
    private var recovering = false
    private var guard = 0

    /**
     * The cap on the two statement loops' iterations.
     *
     * Both loops consume at least one token per iteration -- `if (pos == before)
     * advance()` at the bottom of each is what makes that true -- so a file with n
     * tokens cannot need more than a small multiple of n iterations.  The cap is
     * therefore derived from the file rather than picked, and a runaway is caught
     * long before it can build a tree the editor has to walk: the previous cap was
     * the flat number 4,000,000, and a stray `}` turned a 5-line file into four
     * million error elements.
     */
    private fun stepLimit(): Int = toks.size * 4 + 64

    /** Loop nesting, so `break` outside a loop is refused, as the compiler does. */
    private var loopDepth = 0

    private var lastErrPos = -1

    companion object {
        /**
         * Parse [src], scanning it first.
         *
         * The result always has a tree.  A file that is not valid Vela still
         * comes back shaped -- that is the difference between an editor and a
         * compiler, and the whole reason recovery is in here.
         */
        @JvmStatic
        fun parse(src: String): VelaSyntaxTree {
            val scan = VelaSyntaxScanner.scan(src)
            if (!scan.ok) {
                // The scanner stopped, as the compiler's does.  What is left is the
                // tokens before the failure, and they are parsed as if they were the
                // whole file -- so the declarations written *above* an unreadable
                // token still have a tree, which is what keeps the structure view,
                // completion and go-to-declaration useful while someone is typing.
                // The scan error is prepended to the problem list, which is also what
                // keeps `complete` false: this file is still not valid Vela, and the
                // differential counts it as one the parser refused.
                val p = VelaSyntaxParser(src, scan.toks)
                val tree = p.parseModule()
                val problems = ArrayList<VelaSyntaxProblem>(tree.problems.size + 1)
                problems.add(
                    VelaSyntaxProblem(scanErrorMessage(scan.error), scan.errorStart, scan.errorEnd)
                )
                problems.addAll(tree.problems)
                return VelaSyntaxTree(tree.root, tree.toks, src, problems)
            }
            val p = VelaSyntaxParser(src, scan.toks)
            return p.parseModule()
        }

        private fun scanErrorMessage(kind: VelaScanErrorKind): String = when (kind) {
            VelaScanErrorKind.UNTERMINATED_STRING -> "unterminated string literal"
            VelaScanErrorKind.NUMBER_WITHOUT_DIGITS -> "number has no digits"
            VelaScanErrorKind.INTEGER_LITERAL_TOO_LARGE ->
                "integer literal does not fit in int (64-bit signed)"
            VelaScanErrorKind.INTEGER_LITERAL_WITH_NO_DIGITS -> "number has no digits"
            VelaScanErrorKind.STRAY_BACKSLASH -> "stray backslash"
            VelaScanErrorKind.NONE -> ""
        }
    }

    // ----------------------------------------------------------------- tokens

    private fun kind(): VelaTokKind = toks[pos].kind
    private fun tok(): VelaTok = toks[pos]
    private fun atEof(): Boolean = toks[pos].kind == VelaTokKind.EOF
    private fun atNl(): Boolean = toks[pos].kind == VelaTokKind.NEWLINE

    private fun atOp(op: Int): Boolean {
        val t = toks[pos]
        return t.kind == VelaTokKind.OP && t.code == op
    }

    private fun atOp2(offset: Int, op: Int): Boolean {
        val i = pos + offset
        if (i >= toks.size) return false
        val t = toks[i]
        return t.kind == VelaTokKind.OP && t.code == op
    }

    private fun atKw(kw: Int): Boolean {
        val t = toks[pos]
        return t.kind == VelaTokKind.NAME && t.code == kw
    }

    private fun atName(): Boolean {
        val t = toks[pos]
        return t.kind == VelaTokKind.NAME && t.code == 0
    }

    /** End of a block, or end of file. */
    private fun atClose(): Boolean = atOp(VelaOps.RBRACE) || atEof()

    private fun advance() {
        if (pos < toks.size - 1) pos++
    }

    private fun advance(k: Int) {
        var i = 0
        while (i < k) {
            advance()
            i++
        }
    }

    private fun text(tokIndex: Int): String {
        val t = toks[tokIndex]
        if (t.start >= t.end) return ""
        var e = t.end
        if (e > src.length) e = src.length
        if (t.start > e) return ""
        return src.substring(t.start, e)
    }

    /** The token under the cursor, spelled the way the reader wrote it. */
    private fun tokText(): String = text(pos)

    private fun newNode(kind: VelaNodeKind, at: Int): VelaSyntaxNode {
        val n = VelaSyntaxNode(kind, at, at)
        return n
    }

    // ------------------------------------------------------------- reporting

    private fun problem(message: String) {
        val t = toks[pos]
        if (pos == lastErrPos) return
        lastErrPos = pos
        problems.add(VelaSyntaxProblem(message, t.start, t.end))
    }

    /**
     * Panic-mode recovery: report, then skip to a boundary.
     *
     * A boundary is the next NEWLINE at the bracket depth we are at (a newline
     * inside a call is not a boundary -- the scanner has already thrown those
     * away), a `}` that closes an enclosing block, or end of file.
     *
     * This is what keeps a missing `}` from swallowing the rest of the file:
     * the construct that failed is abandoned where it stands and the next
     * statement is parsed as a full statement, with its own node.  The parser
     * never consumes tokens *inside* a recovery skip that it would otherwise
     * have used to build structure; it throws away exactly the text it could
     * not read.
     */
    private fun recover(message: String): VelaSyntaxNode {
        val at = pos
        problem(message)
        if (!recovering) {
            recovering = true
            var moved = 0
            while (!atEof() && !atClose() && !atNl()) {
                advance()
                moved++
            }
            if (moved == 0 && !atEof() && !atClose()) advance()
            recovering = false
        }
        val err = VelaSyntaxNode(VelaNodeKind.ERROR, at, pos)
        err.printSelf = false
        err.name = message
        return err
    }

    // -------------------------------------------------------------- the file

    /**
     * A Vela file: statements, not nested in anything.
     *
     * The module node's only child is a block, because that is what the
     * compiler's dumper prints -- "module", then "block", then the statements
     * one level deeper.
     */
    private fun parseModule(): VelaSyntaxTree {
        val root = newNode(VelaNodeKind.MODULE, -1)
        val block = newNode(VelaNodeKind.MODULE_BLOCK, -1)
        root.add(block)
        var count = 0
        while (!atEof()) {
            guard++
            if (guard > stepLimit()) {
                problem("the parser gave up here: the rest of the file is not readable as statements")
                break
            }
            if (atNl()) {
                advance()
                continue
            }
            val before = pos
            if (atOp(VelaOps.RBRACE)) {
                // A `}` with no block open.  The compiler stops here; the editor
                // cannot, so the token is reported and then *consumed*.
                //
                // Consuming it is not tidiness, it is the whole difference between
                // an editor and a hang: `recover` refuses to skip a token that
                // closes an enclosing block, so reporting a stray `}` without
                // advancing leaves the cursor exactly where it was and this loop
                // reports the same token again -- four million times, because that
                // was the guard.  The tree came out with four million error
                // elements on `tests/build/check_cases/chained_comparison.vel`,
                // and the compiler's-format dump hid every one of them (an error
                // node prints nothing), so only `psi-tree-diff.ps1` could see it.
                block.add(recover("unexpected '}': there is no block open here"))
                if (pos == before) advance()
                continue
            }
            val s = parseStatement()
            if (s != null) {
                block.add(s)
                count++
            }
            if (pos == before) advance()
            if (recovering) {
                // parseStatement already skipped to a boundary; the newline it
                // stopped on still belongs to the file
                recovering = false
            }
            if (atNl()) {
                advance()
                continue
            }
            if (atOp(VelaOps.RBRACE) || atEof()) continue
            block.add(recover("expected a newline between statements, found '" + tokText() + "'"))
            if (atNl()) advance()
        }
        root.startTok = block.startTok
        root.endTok = block.endTok
        val tree = VelaSyntaxTree(root, toks, src, problems)
        return tree
    }

    // ------------------------------------------------------------- statements

    private fun parseStatement(): VelaSyntaxNode? {
        if (recovering || atEof()) return null
        val at = pos
        val t = tok()
        if (t.kind == VelaTokKind.NAME) {
            when (t.code) {
                VelaKw.PURE -> {
                    advance()
                    if (!atKw(VelaKw.DEF)) {
                        return recover("'pure' must be followed by a function definition")
                    }
                    return parseFuncdef(at, 4)
                }
                VelaKw.DEF -> return parseFuncdef(at, 0)
                VelaKw.EXTERN -> return parseExtern(at)
                VelaKw.STRUCT -> return parseStructdef(at)
                VelaKw.IF -> return parseIf(at)
                VelaKw.ELIF -> {
                    advance()
                    return recover("'elif' is not part of Vela; write 'else if'")
                }
                VelaKw.WHILE -> return parseWhile(at)
                VelaKw.FOR -> return parseFor(at, 0)
                VelaKw.PARALLEL -> {
                    advance()
                    if (!atKw(VelaKw.FOR)) {
                        return recover("'parallel' must be followed by 'for'")
                    }
                    return parseFor(at, 1)
                }
                VelaKw.RETURN -> return parseReturn(at)
                VelaKw.BREAK -> {
                    if (loopDepth == 0) {
                        advance()
                        return recover("'break' outside a loop")
                    }
                    advance()
                    val n = newNode(VelaNodeKind.BREAK, at)
                    n.endTok = pos - 1
                    return n
                }
                VelaKw.CONTINUE -> {
                    if (loopDepth == 0) {
                        advance()
                        return recover("'continue' outside a loop")
                    }
                    advance()
                    val n = newNode(VelaNodeKind.CONTINUE, at)
                    n.endTok = pos - 1
                    return n
                }
                VelaKw.PASS -> {
                    advance()
                    val n = newNode(VelaNodeKind.PASS, at)
                    n.endTok = pos - 1
                    return n
                }
                else -> Unit
            }
        }
        return parseSimple(at)
    }

    /**
     * `extern c def f(...) -> T` -- a declaration, not a definition, so there
     * is no body.  The compiler's node is an ordinary `def` with flag 8 (and
     * 12 when `pure`) and *no* body chain; the dump prints an empty block for
     * it because its dumper always prints a body, so this parser gives it an
     * empty block too, and the two dumps agree line for line.
     */
    private fun parseExtern(at: Int): VelaSyntaxNode {
        advance()
        val line = at
        if (!atName()) {
            return recover("expected 'c' after 'extern'")
        }
        if (text(pos) != "c") {
            return recover("'extern' declares C functions only: write 'extern c'")
        }
        advance()
        var flag = 8
        if (atKw(VelaKw.PURE)) {
            flag = 12
            advance()
        }
        if (!atKw(VelaKw.DEF)) {
            return recover("expected a function definition after 'extern c'")
        }
        return parseFuncdef(line, flag)
    }

    /**
     * `def name(params) -> Ret { body }`.
     *
     * [flag] is the compiler's flag word: 4 for `pure`, 8 for `extern c`, 12
     * for both, 0 otherwise.
     */
    private fun parseFuncdef(at: Int, flag: Int): VelaSyntaxNode {
        advance()
        if (!atName()) {
            return recover("expected a function name")
        }
        val nm = text(pos)
        advance()
        if (!atOp(VelaOps.LPAREN)) {
            return recover("expected '(' after the function name")
        }
        advance()
        val params = ArrayList<VelaSyntaxNode>(4)
        if (!atOp(VelaOps.RPAREN)) {
            while (!recovering) {
                var pflag = 0
                val pAt = pos
                if (atKw(VelaKw.MUT)) {
                    pflag = 1
                    advance()
                }
                if (!atName()) {
                    recover("expected a parameter name")
                    return finishFuncdef(at, nm, flag, params, "")
                }
                val pnm = text(pos)
                advance()
                if (!atOp(VelaOps.COLON)) {
                    val msg = "parameter '" + pnm + "' has no type annotation"
                    recover(msg)
                    return finishFuncdef(at, nm, flag, params, "")
                }
                advance()
                val pt = parseType() ?: return finishFuncdef(at, nm, flag, params, "")
                val p = VelaSyntaxNode(VelaNodeKind.PARAM, pAt, pos - 1)
                p.name = pnm
                p.flags = pflag
                p.typeText = pt
                params.add(p)
                if (atOp(VelaOps.COMMA)) {
                    advance()
                    continue
                }
                break
            }
        }
        if (!atOp(VelaOps.RPAREN)) {
            recover("expected ')' after the parameters")
            return finishFuncdef(at, nm, flag, params, "")
        }
        advance()
        if (!atOp(VelaOps.ARROW)) {
            recover("function '" + nm + "' has no return type annotation")
            return finishFuncdef(at, nm, flag, params, "")
        }
        advance()
        val rt = parseType() ?: return finishFuncdef(at, nm, flag, params, "")
        return finishFuncdef(at, nm, flag, params, rt)
    }

    private fun finishFuncdef(at: Int, nm: String, flag: Int,
                              params: List<VelaSyntaxNode>, rt: String): VelaSyntaxNode {
        val n = VelaSyntaxNode(VelaNodeKind.DEF, at, pos - 1)
        n.name = nm
        n.flags = flag
        n.retType = rt
        for (p in params) n.add(p)
        // An `extern c` declaration has no body at all -- there is nothing to
        // open, and reading a brace that is not there would eat the next
        // declaration.
        if (flag and 8 == 0) {
            val body = parseBody()
            n.add(body)
        } else {
            val empty = VelaSyntaxNode(VelaNodeKind.BLOCK, -1, -1)
            empty.printSelf = true
            n.add(empty)
        }
        return n
    }

    /**
     * `struct Name { fields and methods }`.
     *
     * The compiler parses a struct body as an ordinary block and lets its
     * *dumper* decide what is a field; the dumper prints every field first and
     * every method second, whatever order they were written in -- so a struct
     * whose method is written above its fields still prints the fields first.
     * The tree here is put in that order for the same reason the nodes carry
     * the compiler's names: the two are compared mechanically, and a struct
     * whose members are in source order would differ on every file where
     * somebody wrote a method before a field.
     *
     * The PSI tree deliberately does not care -- it walks `startTok` and
     * `endTok`, so the members' text ranges are unchanged -- and the *editor*
     * reads source order from the text, not from the child order.
     */
    private fun parseStructdef(at: Int): VelaSyntaxNode {
        advance()
        if (!atName()) {
            return recover("expected a struct name")
        }
        val nm = text(pos)
        advance()
        val n = newNode(VelaNodeKind.STRUCT, at)
        n.name = nm
        val body = parseBody(struct = true)
        for (c in body.children) {
            if (velaIsFieldMember(c)) {
                n.adopt(c)
                n.children.add(c)
            }
        }
        for (c in body.children) {
            if (!velaIsFieldMember(c)) {
                n.adopt(c)
                n.children.add(c)
            }
        }
        n.endTok = if (pos > 0) pos - 1 else at
        return n
    }

    /**
     * `{ statements }`.
     *
     * [struct] marks a struct's body, where a statement that turns out to be
     * `name: T` is a *field*: the compiler stores it as an ordinary declaration
     * and its dumper prints it as `field`, so this parser builds the field node
     * where the shape is unambiguous and leaves the declaration node where it
     * is not.
     *
     * A body that cannot be opened still returns a node -- [UNDECLARED_BLOCK],
     * which prints as nothing -- so that recovery has somewhere to put the
     * statements that follow and the tree does not lose them.
     */
    private fun parseBody(struct: Boolean = false): VelaSyntaxNode {
        if (recovering) {
            val dead = VelaSyntaxNode(VelaNodeKind.UNDECLARED_BLOCK, -1, -1)
            dead.printSelf = false
            return dead
        }
        if (!atOp(VelaOps.LBRACE)) {
            val dead = VelaSyntaxNode(VelaNodeKind.UNDECLARED_BLOCK, -1, -1)
            dead.printSelf = false
            val saved = recovering
            recovering = false
            problems.add(
                VelaSyntaxProblem(
                    "expected '{' to open a block, found '" + tokText() + "'",
                    tok().start, tok().end,
                )
            )
            recovering = saved
            return dead
        }
        val open = pos
        advance()
        val block = VelaSyntaxNode(VelaNodeKind.BLOCK, open, open)
        while (!atClose() && !atEof()) {
            guard++
            if (guard > stepLimit()) {
                problem("the parser gave up here: the rest of the block is not readable as statements")
                break
            }
            if (atNl()) {
                advance()
                continue
            }
            val before = pos
            val s = if (struct) parseStructMember() else parseStatement()
            if (s != null) block.add(s)
            if (pos == before) advance()
            recovering = false
            if (atNl()) {
                advance()
                continue
            }
            if (atClose()) break
            block.add(recover("expected a newline between statements, found '" + tokText() + "'"))
            if (atNl()) advance()
        }
        if (atEof()) {
            // "missing '}': a block is never closed" -- the compiler stops; the
            // editor cannot, so the block keeps the statements it has and the
            // file keeps its shape.
            problem("missing '}': a block is never closed")
            block.endTok = pos - 1
            return block
        }
        block.endTok = pos
        advance()
        return block
    }

    /**
     * A struct member: a `def`, or a field.
     *
     * The compiler parses a struct body with its ordinary statement parser and
     * lets the *dumper* decide what is a field; the two shapes it can be are
     * `name: T` (a field) and everything else (a statement).  Peeking one token
     * past the name is enough to tell them apart, and doing it here is what
     * lets the field node exist at all.
     */
    private fun parseStructMember(): VelaSyntaxNode? {
        if (atName() && atOp2(1, VelaOps.COLON)) {
            val at = pos
            val nm = text(pos)
            advance()
            advance()
            val ty = parseType()
            val f = VelaSyntaxNode(VelaNodeKind.FIELD, at, pos - 1)
            f.name = nm
            f.typeText = ty ?: ""
            return f
        }
        return parseStatement()
    }

    /**
     * A type: a name, `None`, or `Name[Type]` / `Name[Type, length]`.
     *
     * Returned as the *spelling* rather than as a type, because the dump prints
     * the type it was given (`Array[int,4]` inside a `decl` line) and the PSI
     * tree wants the same text the reader wrote.
     */
    private fun parseType(): String? {
        if (atKw(VelaKw.NONE)) {
            advance()
            return "None"
        }
        if (!atName()) {
            recover("expected a type name")
            return null
        }
        val base = text(pos)
        advance()
        if (!atOp(VelaOps.LBRACKET)) return base
        advance()
        val elem = parseType() ?: return null
        var size = ""
        if (atOp(VelaOps.COMMA)) {
            advance()
            if (kind() != VelaTokKind.NUMBER || !tok().isInt) {
                recover("expected a compile-time array length")
                return null
            }
            size = text(pos)
            advance()
        }
        if (!atOp(VelaOps.RBRACKET)) {
            recover("expected ']' to close the type")
            return null
        }
        advance()
        return if (size.isEmpty()) base + "[" + elem + "]" else base + "[" + elem + "," + size + "]"
    }

    private fun parseIf(at: Int): VelaSyntaxNode {
        advance()
        val n = newNode(VelaNodeKind.IF, at)
        val cond = parseExpr()
        if (cond != null) n.add(cond)
        val thenBody = parseBody()
        n.add(thenBody)
        if (!recovering && atKw(VelaKw.ELIF)) {
            recover("'elif' is not part of Vela; write 'else if'")
            return n
        }
        if (!recovering && atKw(VelaKw.ELSE)) {
            val elseAt = pos
            advance()
            val elseNode = VelaSyntaxNode(VelaNodeKind.ELSE, elseAt, elseAt)
            if (atKw(VelaKw.IF)) {
                val inner = parseIf(pos)
                elseNode.add(inner)
                elseNode.endTok = inner.endTok
            } else {
                val body = parseBody()
                elseNode.add(body)
                elseNode.endTok = if (pos > 0) pos - 1 else elseAt
            }
            n.add(elseNode)
        }
        n.endTok = if (pos > 0) pos - 1 else at
        return n
    }

    private fun parseWhile(at: Int): VelaSyntaxNode {
        advance()
        val n = newNode(VelaNodeKind.WHILE, at)
        val cond = parseExpr()
        if (cond != null) n.add(cond)
        loopDepth++
        val body = parseBody()
        loopDepth--
        n.add(body)
        n.endTok = if (pos > 0) pos - 1 else at
        return n
    }

    /**
     * `for var in range(a, b, c) { body }`, with [par] 1 for `parallel for`.
     *
     * `range` is a word, not a call, so its arguments are read here rather than
     * by the expression parser -- and one argument means "0 to n", which the
     * compiler makes explicit by synthesising an `int 0` node.  This does the
     * same, because the dump prints it.
     */
    private fun parseFor(at: Int, par: Int): VelaSyntaxNode {
        advance()
        if (!atName()) {
            return recover("expected a loop variable")
        }
        val varName = text(pos)
        advance()
        if (!atKw(VelaKw.IN)) {
            return recover("expected 'in' after the loop variable")
        }
        advance()
        if (!atKw(VelaKw.RANGE)) {
            return recover("this front end iterates range(...) only")
        }
        if (!atOp2(1, VelaOps.LPAREN)) {
            return recover("'range' is a function: write range(0, n)")
        }
        advance()
        advance()
        val args = ArrayList<VelaSyntaxNode>(3)
        if (!atOp(VelaOps.RPAREN)) {
            while (!recovering) {
                val a = parseExpr() ?: break
                args.add(a)
                if (atOp(VelaOps.COMMA)) {
                    advance()
                    continue
                }
                break
            }
        }
        if (!atOp(VelaOps.RPAREN)) {
            return recover("expected ')' to close range(...)")
        }
        advance()
        if (args.isEmpty() || args.size > 3) {
            return recover("range() takes 1 to 3 arguments")
        }
        val n = VelaSyntaxNode(VelaNodeKind.FOR, at, pos - 1)
        n.name = varName
        n.flags = par
        if (args.size == 1) {
            val zero = VelaSyntaxNode(VelaNodeKind.INT, -1, -1)
            zero.intValue = 0L
            zero.floatText = ""
            n.add(zero)
            n.add(args[0])
        } else {
            for (a in args) n.add(a)
        }
        val hasStep = args.size == 3
        if (hasStep) n.op = 1
        loopDepth++
        val body = parseBody()
        loopDepth--
        n.add(body)
        n.endTok = if (pos > 0) pos - 1 else at
        return n
    }

    private fun parseReturn(at: Int): VelaSyntaxNode {
        advance()
        val n = newNode(VelaNodeKind.RETURN, at)
        n.op = 0
        if (atNl() || atEof()) {
            n.endTok = at
            return n
        }
        val v = parseExpr()
        if (v != null) {
            n.add(v)
            n.op = 1
        }
        n.endTok = if (pos > 0) pos - 1 else at
        return n
    }

    /**
     * `mut x: T = e`, `x: T = e`, `x = e`, `x += e`, or a bare expression.
     *
     * Which of the five it is, is decided by two tokens of lookahead -- the
     * compiler does the same thing with `at_name ... at_op2(1, ':')` -- and by
     * what follows the left-hand side.  A bare expression is a statement only
     * for its side effect: the dump calls it `expr`, and the compiler is where
     * "useless expression statement" is refused, not here.
     */
    private fun parseSimple(at: Int): VelaSyntaxNode? {
        if (atKw(VelaKw.MUT)) {
            advance()
            if (!atName()) {
                return recover("expected a variable name after 'mut'")
            }
            val nm = text(pos)
            advance()
            var typeText: String? = null
            if (atOp(VelaOps.COLON)) {
                advance()
                typeText = parseType() ?: return null
            }
            var value: VelaSyntaxNode? = null
            if (atOp(VelaOps.EQ)) {
                advance()
                value = parseExpr()
            } else if (typeText == null) {
                return recover("a 'mut' binding needs an initialiser or a type")
            }
            val n = VelaSyntaxNode(VelaNodeKind.DECL, at, pos - 1)
            n.name = nm
            n.flags = 3
            n.typeText = typeText ?: ""
            if (value != null) n.add(value)
            return n
        }
        if (atName() && atOp2(1, VelaOps.COLON)) {
            val nm = text(pos)
            advance()
            advance()
            val typeText = parseType() ?: return null
            var value: VelaSyntaxNode? = null
            if (atOp(VelaOps.EQ)) {
                advance()
                value = parseExpr()
            }
            val n = VelaSyntaxNode(VelaNodeKind.DECL, at, pos - 1)
            n.name = nm
            n.flags = 2
            n.typeText = typeText
            if (value != null) n.add(value)
            return n
        }
        val lhs = parseExpr() ?: return null
        if (atOp(VelaOps.EQ)) {
            advance()
            val rhs = parseExpr()
            val n = VelaSyntaxNode(VelaNodeKind.ASSIGN, at, pos - 1)
            n.add(lhs)
            if (rhs != null) n.add(rhs)
            return n
        }
        if (kind() == VelaTokKind.OP && velaIsAugAssign(tok().code)) {
            val op = velaAugBase(tok().code)
            advance()
            val rhs = parseExpr()
            val n = VelaSyntaxNode(VelaNodeKind.AUGASSIGN, at, pos - 1)
            n.op = op
            n.add(lhs)
            if (rhs != null) n.add(rhs)
            return n
        }
        val n = VelaSyntaxNode(VelaNodeKind.EXPR, at, pos - 1)
        n.add(lhs)
        return n
    }

    // ------------------------------------------------------------ expressions
    //
    // The ladder, loosest binding outermost.  The call graph is the precedence
    // table: parseExpr -> parseOr -> parseAnd -> parseNot -> parseCmp ->
    // parseBitOr -> parseBitXor -> parseBitAnd -> parseShift -> parseAdditive
    // -> parseMultiplicative -> parseUnary -> parsePower -> parsePostfix ->
    // parseAtom.  `and` binds tighter than `or`, `not` is below comparison, and
    // `**` is the one right-associative level -- all of which the compiler's
    // parser states the same way.

    private fun parseExpr(): VelaSyntaxNode? {
        if (recovering) return null
        return parseOr()
    }

    private fun parseOr(): VelaSyntaxNode? {
        var node = parseAnd() ?: return null
        while (!recovering && atKw(VelaKw.OR)) {
            val at = pos
            advance()
            val rhs = parseAnd() ?: break
            val n = VelaSyntaxNode(VelaNodeKind.BOOLOP, at, rhs.endTok)
            n.op = VelaKw.OR
            n.add(node)
            n.add(rhs)
            node = n
        }
        return node
    }

    private fun parseAnd(): VelaSyntaxNode? {
        var node = parseNot() ?: return null
        while (!recovering && atKw(VelaKw.AND)) {
            val at = pos
            advance()
            val rhs = parseNot() ?: break
            val n = VelaSyntaxNode(VelaNodeKind.BOOLOP, at, rhs.endTok)
            n.op = VelaKw.AND
            n.add(node)
            n.add(rhs)
            node = n
        }
        return node
    }

    private fun parseNot(): VelaSyntaxNode? {
        if (recovering) return null
        if (atKw(VelaKw.NOT)) {
            val at = pos
            advance()
            val operand = parseNot() ?: return null
            val n = VelaSyntaxNode(VelaNodeKind.UNARY, at, operand.endTok)
            n.op = VelaKw.NOT
            n.add(operand)
            return n
        }
        return parseCmp()
    }

    /** One comparison, never a chain -- `a < b < c` is refused where it is written. */
    private fun parseCmp(): VelaSyntaxNode? {
        val left = parseBitOr() ?: return null
        if (recovering) return null
        if (kind() == VelaTokKind.OP && velaIsCmpOp(tok().code)) {
            val op = tok().code
            val at = pos
            advance()
            val right = parseBitOr() ?: return null
            if (kind() == VelaTokKind.OP && velaIsCmpOp(tok().code)) {
                recover("chained comparison is not allowed")
                return null
            }
            val n = VelaSyntaxNode(VelaNodeKind.CMP, at, right.endTok)
            n.op = op
            n.add(left)
            n.add(right)
            return n
        }
        return left
    }

    private fun parseBitOr(): VelaSyntaxNode? {
        var node = parseBitXor() ?: return null
        while (!recovering && atOp(VelaOps.PIPE)) {
            val at = pos
            advance()
            val rhs = parseBitXor() ?: break
            node = binary(VelaOps.PIPE, at, node, rhs)
        }
        return node
    }

    private fun parseBitXor(): VelaSyntaxNode? {
        var node = parseBitAnd() ?: return null
        while (!recovering && atOp(VelaOps.CARET)) {
            val at = pos
            advance()
            val rhs = parseBitAnd() ?: break
            node = binary(VelaOps.CARET, at, node, rhs)
        }
        return node
    }

    private fun parseBitAnd(): VelaSyntaxNode? {
        var node = parseShift() ?: return null
        while (!recovering && atOp(VelaOps.AMP)) {
            val at = pos
            advance()
            val rhs = parseShift() ?: break
            node = binary(VelaOps.AMP, at, node, rhs)
        }
        return node
    }

    private fun parseShift(): VelaSyntaxNode? {
        var node = parseAdditive() ?: return null
        while (!recovering && (atOp(VelaOps.SHL) || atOp(VelaOps.SHR))) {
            val op = tok().code
            val at = pos
            advance()
            val rhs = parseAdditive() ?: break
            node = binary(op, at, node, rhs)
        }
        return node
    }

    private fun parseAdditive(): VelaSyntaxNode? {
        var node = parseMultiplicative() ?: return null
        while (!recovering && (atOp(VelaOps.PLUS) || atOp(VelaOps.MINUS))) {
            val op = tok().code
            val at = pos
            advance()
            val rhs = parseMultiplicative() ?: break
            node = binary(op, at, node, rhs)
        }
        return node
    }

    private fun parseMultiplicative(): VelaSyntaxNode? {
        var node = parseUnary() ?: return null
        while (!recovering && (atOp(VelaOps.STAR) || atOp(VelaOps.SLASH) ||
                    atOp(VelaOps.PERCENT) || atOp(VelaOps.FLOORDIV))
        ) {
            val op = tok().code
            val at = pos
            advance()
            val rhs = parseUnary() ?: break
            node = binary(op, at, node, rhs)
        }
        return node
    }

    /**
     * Unary `-`, `+` and `~`, which bind *looser* than `**`: `-2 ** 2` is
     * `-(2 ** 2)`, and the dump says so.
     *
     * `!` is deliberately not here.  The scanner knows the character, but the
     * compiler's `parse_unary` accepts only codes 1, 2 and 32, so `!x` is a
     * syntax error -- and a parser that accepted it would be accepting files
     * the compiler refuses.
     */
    private fun parseUnary(): VelaSyntaxNode? {
        if (recovering) return null
        if (atOp(VelaOps.MINUS) || atOp(VelaOps.PLUS) || atOp(VelaOps.TILDE)) {
            val op = tok().code
            val at = pos
            advance()
            val operand = parseUnary() ?: return null
            val n = VelaSyntaxNode(VelaNodeKind.UNARY, at, operand.endTok)
            n.op = op
            n.add(operand)
            return n
        }
        return parsePower()
    }

    /** `**` is right-associative: `2 ** -3` is `2 ** (-3)`. */
    private fun parsePower(): VelaSyntaxNode? {
        val node = parsePostfix() ?: return null
        if (recovering) return null
        if (atOp(VelaOps.POW)) {
            val at = pos
            advance()
            val right = parseUnary() ?: return null
            return binary(VelaOps.POW, at, node, right)
        }
        return node
    }

    /**
     * Calls, indexing, slicing and field access, in any order, as many as the
     * text offers.
     */
    private fun parsePostfix(): VelaSyntaxNode? {
        var node = parseAtom() ?: return null
        while (!recovering) {
            if (atOp(VelaOps.LPAREN)) {
                val at = pos
                advance()
                val n = VelaSyntaxNode(VelaNodeKind.CALL, at, at)
                n.add(node)
                if (!atOp(VelaOps.RPAREN)) {
                    while (!recovering) {
                        val before = pos
                        val a = parseExpr()
                        if (a != null) n.add(a)
                        if (pos == before) break
                        if (atOp(VelaOps.COMMA)) {
                            advance()
                            continue
                        }
                        break
                    }
                }
                if (!atOp(VelaOps.RPAREN)) {
                    recover("expected ')' to close the call")
                    return null
                }
                n.endTok = pos
                advance()
                node = n
                continue
            }
            if (atOp(VelaOps.LBRACKET)) {
                val at = pos
                advance()
                if (atOp(VelaOps.COLON)) {
                    // no lower bound: `a[:2]`
                    advance()
                    val n = VelaSyntaxNode(VelaNodeKind.SLICE, at, at)
                    n.add(node)
                    if (!atOp(VelaOps.RBRACKET)) {
                        val upper = parseExpr()
                        if (upper != null) n.add(upper)
                    }
                    if (!atOp(VelaOps.RBRACKET)) {
                        recover("expected ']' to close the slice")
                        return null
                    }
                    n.endTok = pos
                    advance()
                    node = n
                    continue
                }
                val lower = parseExpr()
                if (atOp(VelaOps.COLON)) {
                    advance()
                    val n = VelaSyntaxNode(VelaNodeKind.SLICE, at, at)
                    n.add(node)
                    if (lower != null) n.add(lower)
                    if (!atOp(VelaOps.RBRACKET)) {
                        val upper = parseExpr()
                        if (upper != null) n.add(upper)
                    }
                    if (!atOp(VelaOps.RBRACKET)) {
                        recover("expected ']' to close the slice")
                        return null
                    }
                    n.endTok = pos
                    advance()
                    node = n
                    continue
                }
                if (!atOp(VelaOps.RBRACKET)) {
                    recover("expected ']' to close the index")
                    return null
                }
                val n = VelaSyntaxNode(VelaNodeKind.INDEX, at, pos)
                n.add(node)
                if (lower != null) n.add(lower)
                advance()
                node = n
                continue
            }
            if (atOp(VelaOps.DOT)) {
                val at = pos
                advance()
                if (!atName()) {
                    recover("expected a field name after '.'")
                    return null
                }
                val nm = text(pos)
                val n = VelaSyntaxNode(VelaNodeKind.ATTR, at, pos)
                n.name = nm
                n.add(node)
                advance()
                node = n
                continue
            }
            break
        }
        return node
    }

    /**
     * A literal, a name, a parenthesised expression, or an array literal.
     *
     * `True`, `False` and `None` are *words* here, as they are to the compiler,
     * and so is `range` -- which is why `range(0, 5)` is not an expression: it
     * is a `for` header, and anywhere else the compiler says "unexpected token
     * in expression".
     */
    private fun parseAtom(): VelaSyntaxNode? {
        if (recovering) return null
        val at = pos
        val t = tok()
        if (t.kind == VelaTokKind.NUMBER) {
            advance()
            if (t.isInt) {
                val n = VelaSyntaxNode(VelaNodeKind.INT, at, at)
                n.intValue = t.intValue
                return n
            }
            val n = VelaSyntaxNode(VelaNodeKind.FLOAT, at, at)
            n.floatText = text(at)
            n.flags = t.stringFlag
            return n
        }
        if (t.kind == VelaTokKind.STRING) {
            advance()
            val n = VelaSyntaxNode(VelaNodeKind.STR, at, at)
            n.text = if (t.end > t.start) src.substring(t.start, t.end) else ""
            n.strEscapes = t.stringFlag == 1
            n.bytes = VelaStrings.literalBytes(n.text, n.strEscapes)
            return n
        }
        if (t.kind == VelaTokKind.NAME) {
            when (t.code) {
                0 -> {
                    advance()
                    val n = VelaSyntaxNode(VelaNodeKind.NAME, at, at)
                    n.name = text(at)
                    return n
                }
                VelaKw.TRUE, VelaKw.FALSE -> {
                    advance()
                    val n = VelaSyntaxNode(VelaNodeKind.BOOL, at, at)
                    n.intValue = if (t.code == VelaKw.TRUE) 1L else 0L
                    return n
                }
                VelaKw.NONE -> {
                    advance()
                    return VelaSyntaxNode(VelaNodeKind.NONE, at, at)
                }
                else -> {
                    // `range` and every other word: not an expression operand.
                    recover("unexpected token in expression")
                    return null
                }
            }
        }
        if (atOp(VelaOps.LPAREN)) {
            advance()
            val inner = parseExpr()
            if (atOp(VelaOps.COMMA)) {
                return recover("tuples are not supported")
            }
            if (!atOp(VelaOps.RPAREN)) {
                return recover("expected ')' to close the group")
            }
            advance()
            // The group is transparent: the node inside is the node, exactly as
            // the compiler returns the inner node.
            return inner
        }
        if (atOp(VelaOps.LBRACKET)) {
            advance()
            val n = VelaSyntaxNode(VelaNodeKind.LIST, at, at)
            var count = 0
            if (!atOp(VelaOps.RBRACKET)) {
                while (!recovering) {
                    val before = pos
                    val e = parseExpr()
                    if (e != null) {
                        n.add(e)
                        count++
                    }
                    if (pos == before) break
                    if (atOp(VelaOps.COMMA)) {
                        advance()
                        if (atOp(VelaOps.RBRACKET)) break
                        continue
                    }
                    break
                }
            }
            if (!atOp(VelaOps.RBRACKET)) {
                return recover("expected ']' to close the array literal")
            }
            if (count == 0) {
                return recover("an empty array literal has no element type")
            }
            n.endTok = pos
            advance()
            return n
        }
        recover("unexpected token in expression")
        return null
    }

    private fun binary(op: Int, at: Int, left: VelaSyntaxNode, right: VelaSyntaxNode): VelaSyntaxNode {
        val n = VelaSyntaxNode(VelaNodeKind.BINOP, at, right.endTok)
        n.op = op
        n.add(left)
        n.add(right)
        return n
    }
}
