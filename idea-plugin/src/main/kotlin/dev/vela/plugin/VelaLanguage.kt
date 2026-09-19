package dev.vela.plugin

import com.intellij.lang.Language
import com.intellij.lexer.LexerBase
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import javax.swing.Icon

/** The language object the platform hangs every extension on. */
object VelaLanguage : Language("Vela") {
    override fun getDisplayName(): String = "Vela"
}

object VelaFileType : LanguageFileType(VelaLanguage) {
    /**
     * `.vel` is what a Vela file actually is: every file in this repository ends
     * that way — `selfhost/vm.vel`, `tests/build/arith_basics.vel`, the
     * benchmarks, the compiler's own parts — and `.vela` is the same name spelled
     * out, kept because it is the spelling people reach for first.
     *
     * The first version of this plugin registered `.vela` only, and so on a real
     * Vela file it did *nothing*: no highlighting, no diagnostics, no actions.  A
     * plugin that works only on files nobody has is a plugin that does not work,
     * and no amount of "all the classes loaded" made that visible from inside.
     *
     * Both suffixes are declared in `plugin.xml` — `<fileType ... extensions="vel;vela">`
     * is the mechanism this platform actually has; `FileType` in 262 has no
     * `getExtensions()` to override, only `getDefaultExtension()`.  This list is
     * the same fact again, for the code that has to recognise a Vela file by name.
     */
    val EXTENSIONS = listOf("vel", "vela")

    override fun getName(): String = "Vela File"
    override fun getDescription(): String = "Vela source file"
    override fun getDefaultExtension(): String = "vel"
    override fun getIcon(): Icon =
        IconLoader.getIcon("/icons/vela.svg", VelaFileType::class.java)
}

/**
 * Is this file name a Vela source?  One place answers, so the annotator, the
 * actions and the build driver cannot drift apart about it — which is exactly
 * how the `.vela`-only version kept "working" while doing nothing.
 */
fun isVelaFileName(name: String): Boolean =
    VelaFileType.EXTENSIONS.any { name.endsWith(".$it") }

/** `hello.vel` -> `hello`: the name a C compiler expects beside the source. */
fun velaStem(path: String): String {
    for (ext in VelaFileType.EXTENSIONS) {
        if (path.endsWith(".$ext")) return path.dropLast(ext.length + 1)
    }
    return path
}

/**
 * Token kinds.  They follow the lexer in `selfhost/vela.vel` one for one — the
 * same list of keywords, the same operators including `//`, `**` and the
 * augmented forms — because a highlighter that disagrees with the compiler about
 * what a word is teaches the reader something false.
 */
object VelaTokenTypes {
    @JvmField val KEYWORD = IElementType("VELA_KEYWORD", VelaLanguage)
    @JvmField val IDENTIFIER = IElementType("VELA_IDENT", VelaLanguage)
    @JvmField val NUMBER = IElementType("VELA_NUMBER", VelaLanguage)
    @JvmField val STRING = IElementType("VELA_STRING", VelaLanguage)
    @JvmField val COMMENT = IElementType("VELA_COMMENT", VelaLanguage)
    @JvmField val OPERATOR = IElementType("VELA_OP", VelaLanguage)
    @JvmField val BRACES = IElementType("VELA_BRACES", VelaLanguage)
    @JvmField val BRACKETS = IElementType("VELA_BRACKETS", VelaLanguage)
    @JvmField val PARENS = IElementType("VELA_PARENS", VelaLanguage)
    @JvmField val COMMA = IElementType("VELA_COMMA", VelaLanguage)
    @JvmField val DOT = IElementType("VELA_DOT", VelaLanguage)
    @JvmField val COLON = IElementType("VELA_COLON", VelaLanguage)
    @JvmField val SEMICOLON = IElementType("VELA_SEMICOLON", VelaLanguage)

    val KEYWORDS: Set<String> = setOf(
        "def", "return", "if", "elif", "else", "while", "for", "in", "range",
        "break", "continue", "pass", "and", "or", "not", "True", "False",
        "None", "mut", "struct", "parallel", "pure", "extern",
    )
}

/**
 * A lexer, not a parser: the editor needs to colour a half-written line and to
 * find where a comment ends, and both are questions about characters.  Vela's
 * rule that every statement ends at a newline is the *parser's* business, so a
 * newline is whitespace here — colouring has no opinion about it.
 */
class VelaLexer : LexerBase() {
    private var buffer: CharSequence = ""
    private var startOffset = 0
    private var endOffset = 0
    private var pos = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int,
                       initialState: Int) {
        this.buffer = buffer
        this.startOffset = startOffset
        this.endOffset = endOffset
        this.pos = startOffset
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset
    override fun getTokenType(): IElementType? = tokenType

    override fun advance() {
        tokenStart = pos
        if (pos >= endOffset) {
            tokenType = null
            tokenEnd = pos
            return
        }
        // A byte-order mark, at the very start of the file only: whitespace, because
        // that is what it is to the compiler.  `VelaSyntaxScanner` skips one and
        // `vm.exe` parses `tests/build/bom_first_byte.vel`, so a lexer that tokenised
        // the mark as an identifier would disagree with both -- and it did: the
        // highlighter coloured a BOM as a name, and the PSI replay had no parser token
        // to match those bytes against, which is how `psi-tree-diff.ps1` found it.
        // Both spellings are skipped, because the file is read as ISO-8859-1 in one
        // path and as characters in the other: UTF-8's EF BB BF, or a real U+FEFF.
        if (pos == startOffset) {
            val bom = bomLength()
            if (bom > 0) {
                pos += bom
                tokenType = TokenType.WHITE_SPACE
                tokenEnd = pos
                return
            }
        }
        val c = buffer[pos]
        tokenType = when {
            c == ' ' || c == '\t' || c == '\r' || c == '\n' -> {
                while (pos < endOffset && isSpace(buffer[pos])) pos++
                TokenType.WHITE_SPACE
            }
            c == '#' -> {
                while (pos < endOffset && buffer[pos] != '\n') pos++
                VelaTokenTypes.COMMENT
            }
            c == '"' || c == '\'' -> scanString(c)
            c.isDigit() -> scanNumber()
            isNameStart(c) -> scanName()
            else -> scanOperator()
        }
        tokenEnd = pos
    }

    /**
     * The length of a byte-order mark at [startOffset], or 0 if there is none.
     *
     * `EF BB BF` as three ISO-8859-1 characters (how the file's bytes read when they
     * are not decoded as UTF-8) or one `U+FEFF`.
     */
    private fun bomLength(): Int {
        val n = endOffset - startOffset
        if (n <= 0) return 0
        val a = buffer[startOffset]
        if (a == '\uFEFF') return 1
        if (n >= 3 && a == '\u00EF' && buffer[startOffset + 1] == '\u00BB' &&
            buffer[startOffset + 2] == '\u00BF'
        ) {
            return 3
        }
        return 0
    }

    /** A string is one token even unterminated: the compiler reports that. */
    private fun scanString(quote: Char): IElementType {        pos++
        while (pos < endOffset) {
            val c = buffer[pos]
            if (c == '\\' && pos + 1 < endOffset) {
                pos += 2
                continue
            }
            if (c == quote) {
                pos++
                break
            }
            if (c == '\n') break
            pos++
        }
        return VelaTokenTypes.STRING
    }

    private fun scanNumber(): IElementType {
        if (buffer[pos] == '0' && pos + 1 < endOffset) {
            val n = buffer[pos + 1]
            if (n == 'x' || n == 'X' || n == 'o' || n == 'O' || n == 'b' || n == 'B') {
                pos += 2
                while (pos < endOffset && (buffer[pos].isLetterOrDigit() || buffer[pos] == '_')) pos++
                return VelaTokenTypes.NUMBER
            }
        }
        while (pos < endOffset && (buffer[pos].isDigit() || buffer[pos] == '_')) pos++
        if (pos + 1 < endOffset && buffer[pos] == '.' && buffer[pos + 1].isDigit()) {
            pos++
            while (pos < endOffset && buffer[pos].isDigit()) pos++
        }
        if (pos < endOffset && (buffer[pos] == 'e' || buffer[pos] == 'E')) {
            var j = pos + 1
            if (j < endOffset && (buffer[j] == '+' || buffer[j] == '-')) j++
            if (j < endOffset && buffer[j].isDigit()) {
                pos = j
                while (pos < endOffset && buffer[pos].isDigit()) pos++
            }
        }
        return VelaTokenTypes.NUMBER
    }

    private fun scanName(): IElementType {
        while (pos < endOffset && isNamePart(buffer[pos])) pos++
        val text = buffer.subSequence(tokenStart, pos).toString()
        return if (VelaTokenTypes.KEYWORDS.contains(text)) {
            VelaTokenTypes.KEYWORD
        } else {
            VelaTokenTypes.IDENTIFIER
        }
    }

    /**
     * Longest match first, because `<<=` must not be seen as `<<` and `=`; the
     * list is the compiler's own operator table (SPEC.md §2).
     */
    private fun scanOperator(): IElementType {
        val rest = endOffset - pos
        fun at(s: String): Boolean {
            if (rest < s.length) return false
            for (i in s.indices) if (buffer[pos + i] != s[i]) return false
            return true
        }
        for (op in OPERATORS) {
            if (at(op)) {
                pos += op.length
                return when (op[0]) {
                    '{', '}' -> VelaTokenTypes.BRACES
                    '[', ']' -> VelaTokenTypes.BRACKETS
                    '(', ')' -> VelaTokenTypes.PARENS
                    ',' -> VelaTokenTypes.COMMA
                    '.' -> VelaTokenTypes.DOT
                    ':' -> VelaTokenTypes.COLON
                    ';' -> VelaTokenTypes.SEMICOLON
                    else -> VelaTokenTypes.OPERATOR
                }
            }
        }
        pos++
        return TokenType.BAD_CHARACTER
    }

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'
    private fun isNameStart(c: Char) = c.isLetter() || c == '_'
    private fun isNamePart(c: Char) = c.isLetterOrDigit() || c == '_'

    private companion object {
        val OPERATORS = listOf(
            "**=", "//=", "<<=", ">>=",
            "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=",
            "**", "//", "==", "!=", "<=", ">=", "<<", ">>", "->",
            "+", "-", "*", "/", "%", "<", ">", "=", "&", "|", "^", "~", "!",
            "{", "}", "[", "]", "(", ")", ",", ".", ":", ";",
        )
    }
}
