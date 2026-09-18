package dev.vela.plugin

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Folding, for the two things a Vela file is made of: `{ }` blocks and runs of
 * `#` comment lines.
 *
 * ## What is folded, and how the ranges are chosen
 *
 * A block region spans *the body*, from just after its `{` to just before its
 * `}`, so collapsing a function reads `def main() -> None { 12 lines }` — the
 * placeholder sits where the body was and the closing brace stays visible.
 * A region is only created when the body actually contains a line break, because
 * folding a one-line body hides text and reveals a placeholder of the same size.
 *
 * Regions nest the way the language nests: an inner block's range is inside its
 * outer block's range, and a comment run inside a block is inside the block's
 * range.  The descriptors are returned sorted by start offset (shortest first
 * when two share a start) because each pass rebuilds them from scratch.
 *
 * ## Nesting is computed here, not read from the tree
 *
 * `VelaParserDefinition` deliberately builds a flat tree — tokens only, no block
 * nodes — so there is no `def` node whose range could be the fold region.  The
 * braces are matched off the token sequence instead, which is the same sequence
 * the formatter indents by and the brace matcher pairs: one answer, from one
 * source.
 *
 * ## Placeholders
 *
 * A block says how many lines of code it holds (`{ 12 lines }` — counted as the
 * number of lines that contain at least one token inside the block, so a blank
 * line is not a line of code and a statement spread over two lines counts once).
 * A comment run says how many comment lines it holds (`# 3 comment lines`).
 * Neither is `...`: the whole point of folding is that the reader can decide
 * whether to open it.
 */
class VelaFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file: PsiFile = if (root is PsiFile) root else root.containingFile
        val node = file.node ?: return NO_REGIONS
        val nodes = ArrayList<ASTNode>(256)
        val tokens = ArrayList<VelaToken>(256)
        collectVelaTokens(node, nodes, tokens)
        if (tokens.isEmpty()) return NO_REGIONS

        val text = document.charsSequence
        val out = ArrayList<FoldingDescriptor>()
        val blockStarts = HashSet<Int>()
        addBlockRegions(file, document, text, tokens, out, blockStarts)
        addCommentRegions(file, document, text, tokens, out, blockStarts)
        if (out.isEmpty()) return NO_REGIONS
        out.sortWith(compareBy({ it.range.startOffset }, { it.range.endOffset }))
        return out.toTypedArray()
    }

    /**
     * The placeholder for a region described by an `ASTNode`.  The real text is
     * set on each `FoldingDescriptor` while the ranges are known; this answers the
     * same question for anything that asks the builder instead — and it describes
     * the node rather than returning `...`, which is the rule for this feature.
     */
    override fun getPlaceholderText(node: ASTNode): String {
        // The real placeholders are set on the descriptors, where the fold ranges
        // are known. This is the same question asked of the node instead: a comment
        // counts its own lines, a block counts the lines it spans.
        val text = node.text
        var lines = 1
        for (c in text) if (c == '\n') lines++
        return if (text.startsWith("#")) commentPlaceholder(lines) else blockPlaceholder(lines)
    }

    override fun getPlaceholderText(region: ASTNode, range: TextRange): String = getPlaceholderText(region)

    /**
     * Nothing is folded on open.  Collapsing a block by default hides the code
     * the reader came to see, and Vela's blocks are function bodies, not
     * boilerplate.
     */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    // ------------------------------------------------------------------ blocks

    private fun addBlockRegions(
        file: PsiFile,
        document: Document,
        text: CharSequence,
        tokens: List<VelaToken>,
        out: MutableList<FoldingDescriptor>,
        blockStarts: MutableSet<Int>,
    ) {
        val open = ArrayList<Int>(16)
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.type != VelaTokenTypes.BRACES) continue
            if (token.text == "{") {
                open.add(i)
                continue
            }
            if (token.text != "}") continue
            if (open.isEmpty()) continue
            val openIndex = open.removeAt(open.size - 1)
            val from = tokens[openIndex].end
            val to = token.start
            if (to <= from) continue
            if (!hasLineBreak(text, from, to)) continue
            var lines = 0
            var lastLine = -1
            for (k in openIndex + 1 until i) {
                val line = document.getLineNumber(tokens[k].start)
                if (line != lastLine) {
                    lines++
                    lastLine = line
                }
            }
            if (lines <= 0) continue
            blockStarts.add(from)
            val region = FoldingDescriptor(file, TextRange(from, to))
            region.setPlaceholderText(blockPlaceholder(lines))
            out.add(region)
        }
    }

    // ----------------------------------------------------------------- comments

    /**
     * A run of two or more comment lines.  A *comment line* is a line whose only
     * token is a `#` comment, so a trailing comment never starts or continues a
     * run; two comments on consecutive lines are one run; a blank line between
     * them is not, which is the convention every other language plugin here uses.
     */
    private fun addCommentRegions(
        file: PsiFile,
        document: Document,
        text: CharSequence,
        tokens: List<VelaToken>,
        out: MutableList<FoldingDescriptor>,
        blockStarts: MutableSet<Int>,
    ) {
        var runStart = -1
        var runEnd = -1
        var runLines = 0
        var runLastLine = -1

        for (i in tokens.indices) {
            val token = tokens[i]
            val isOwnLineComment = token.type == VelaTokenTypes.COMMENT &&
                (i == 0 || hasLineBreak(text, tokens[i - 1].end, token.start))
            if (!isOwnLineComment) {
                if (runLines >= 2) addCommentRegion(file, out, blockStarts, runStart, runEnd, runLines)
                runStart = -1
                runEnd = -1
                runLines = 0
                runLastLine = -1
                continue
            }
            val line = document.getLineNumber(token.start)
            if (runLines > 0 && line == runLastLine + 1) {
                runEnd = token.end
                runLines++
                runLastLine = line
            } else {
                if (runLines >= 2) addCommentRegion(file, out, blockStarts, runStart, runEnd, runLines)
                runStart = token.start
                runEnd = token.end
                runLines = 1
                runLastLine = line
            }
        }
        if (runLines >= 2) addCommentRegion(file, out, blockStarts, runStart, runEnd, runLines)
    }

    private fun addCommentRegion(
        file: PsiFile,
        out: MutableList<FoldingDescriptor>,
        blockStarts: MutableSet<Int>,
        from: Int,
        to: Int,
        lines: Int,
    ) {
        if (to <= from) return
        // A block whose body starts with a comment on the very same offset would
        // otherwise have a region starting where this one starts: nested regions
        // may share an end, but sharing a start is the one arrangement the folding
        // model does not accept. The block wins; the comment run keeps its text.
        if (blockStarts.contains(from)) return
        val region = FoldingDescriptor(file, TextRange(from, to))
        region.setPlaceholderText(commentPlaceholder(lines))
        out.add(region)
    }

    // ------------------------------------------------------------------ text

    private fun hasLineBreak(text: CharSequence, from: Int, to: Int): Boolean {
        if (to > text.length) return false
        var i = if (from < 0) 0 else from
        while (i < to) {
            val c = text[i]
            if (c == '\n' || c == '\r') return true
            i++
        }
        return false
    }

    private fun blockPlaceholder(lines: Int): String =
        if (lines == 1) "{ 1 line }" else "{ $lines lines }"

    private fun commentPlaceholder(lines: Int): String =
        if (lines == 1) "# 1 comment line" else "# $lines comment lines"

    private companion object {
        private val NO_REGIONS = emptyArray<FoldingDescriptor>()
    }
}
