package dev.vela.plugin

import com.intellij.lang.ASTNode
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Reading a Vela file, for the editor mechanics.
 *
 * The plugin has exactly one lexer — `VelaLexer` — and every feature here asks
 * it rather than reading characters again.  "Is the caret inside a string?"
 * and "where does this brace close?" are the same question the highlighter and
 * the model ask, and two answers to one question is how a plugin starts
 * disagreeing with itself.
 *
 * Two ways in, because the callers differ:
 *
 *  * [velaLeafNodes] walks the PSI tree the parser definition built.  It is a
 *    *flat* tree — one leaf per token, no block nodes — which is why the
 *    formatter, the folding builder and the brace matcher all work from the
 *    token sequence and compute nesting themselves.
 *  * [velaTokenAt] and [velaBraceDepth] lex a `CharSequence` directly, for the
 *    typing path, where the PSI is deliberately not used: a keystroke has not
 *    been committed to the tree yet, and reading the document is the only way to
 *    see the character that was just typed.
 */

/** One leaf of the flat tree, whitespace included, in document order. */
internal fun velaLeafNodes(node: ASTNode): List<ASTNode> {
    val out = ArrayList<ASTNode>(256)
    collectVelaLeaves(node, out)
    return out
}

private fun collectVelaLeaves(node: ASTNode, out: MutableList<ASTNode>) {
    var child = node.firstChildNode
    while (child != null) {
        if (child.firstChildNode == null) {
            out.add(child)
        } else {
            // Only reachable for a `PsiErrorElement` the platform wrapped around a
            // bad character; the parser definition itself builds nothing else.
            collectVelaLeaves(child, out)
        }
        child = child.treeNext
    }
}

/**
 * The significant tokens under [node] — whitespace dropped — appended to
 * [nodes] and [tokens] in step, so index `i` of one belongs to index `i` of the
 * other.
 *
 * Comments are *not* dropped: they are blocks in the formatting model (they have
 * an indentation), they are foldable (a run of them is one region), and a
 * comment is what decides that a `#` typed at the start of a line is a comment
 * rather than something the typed handler should fight.
 */
internal fun collectVelaTokens(
    node: ASTNode,
    nodes: MutableList<ASTNode>,
    tokens: MutableList<VelaToken>,
) {
    for (leaf in velaLeafNodes(node)) {
        if (leaf.elementType == TokenType.WHITE_SPACE) continue
        val range = leaf.textRange
        nodes.add(leaf)
        tokens.add(VelaToken(leaf.elementType, leaf.text, range.startOffset, range.endOffset))
    }
}

/** A token as the lexer sees one: what it is, and where. */
internal class VelaLexToken(
    @JvmField val type: IElementType?,
    @JvmField val start: Int,
    @JvmField val end: Int,
)

/**
 * The lexer's token covering [offset], or `null` when the offset is past the end
 * or inside nothing the lexer produced.
 *
 * [text] is normally one *line*, not the whole file: Vela literals are
 * single-line by definition (`SPEC.md` §1 — "single-line only — a literal may
 * not contain a raw newline"), and a `#` comment ends at the newline, so a line
 * is the largest span any token can occupy.  Lexing a line per keystroke keeps
 * the typing path proportional to the line rather than to the file.
 */
internal fun velaTokenAt(text: CharSequence, offset: Int): VelaLexToken? {
    if (offset < 0 || offset > text.length) return null
    val lexer = VelaLexer()
    lexer.start(text)
    while (lexer.tokenType != null) {
        val start = lexer.tokenStart
        val end = lexer.tokenEnd
        if (offset >= start && offset < end) return VelaLexToken(lexer.tokenType, start, end)
        if (start > offset) return null
        lexer.advance()
    }
    return null
}

/** Is [offset] inside a string literal or a comment? */
internal fun velaIsInStringOrComment(text: CharSequence, offset: Int): Boolean {
    val token = velaTokenAt(text, offset) ?: return false
    return token.type == VelaTokenTypes.STRING || token.type == VelaTokenTypes.COMMENT
}

/**
 * How many `{` are still open at [endOffset].
 *
 * Counted from the lexer, so a `{` inside a string or a comment — the two places
 * Vela lets one appear without opening a block — is not counted.  This is the
 * number the `Enter` handler indents to and the formatter indents by.
 */
internal fun velaBraceDepth(text: CharSequence, endOffset: Int): Int {
    if (endOffset <= 0) return 0
    val limit = if (endOffset > text.length) text.length else endOffset
    val lexer = VelaLexer()
    lexer.start(text, 0, limit, 0)
    var depth = 0
    while (lexer.tokenType != null) {
        if (lexer.tokenType == VelaTokenTypes.BRACES) {
            val start = lexer.tokenStart
            if (start in 0 until limit) {
                val c = text[start]
                if (c == '{') depth++
                if (c == '}') depth--
            }
        }
        lexer.advance()
    }
    return if (depth > 0) depth else 0
}

/** The line [offset] is on, as a slice of the document plus where it starts. */
internal class VelaLine(
    @JvmField val start: Int,
    @JvmField val text: CharSequence,
)

internal fun velaLineAt(document: Document, offset: Int): VelaLine {
    val line = document.getLineNumber(offset)
    val start = document.getLineStartOffset(line)
    val end = document.getLineEndOffset(line)
    return VelaLine(start, document.charsSequence.subSequence(start, end))
}

/**
 * Is this file a Vela source?  The suffix list lives in one place — the same
 * place `VelaFileType` and the New File action read it from — because the
 * editor-side extension points (`typedHandler`, `enterHandlerDelegate`) are
 * registered for *all* files and have to decide for themselves.
 */
internal fun velaIsVelaFile(file: PsiFile): Boolean = isVelaFileName(file.name)

/** `n` spaces, for an indentation the editor paths build by hand. */
internal fun velaIndent(levels: Int): String {
    if (levels <= 0) return ""
    val count = levels * VelaFormatRules.INDENT_SIZE
    val sb = StringBuilder(count)
    for (i in 0 until count) sb.append(' ')
    return sb.toString()
}

/** The leading spaces and tabs of [text]. */
internal fun velaLeadingWhitespace(text: CharSequence): String {
    var i = 0
    while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
    return text.subSequence(0, i).toString()
}
