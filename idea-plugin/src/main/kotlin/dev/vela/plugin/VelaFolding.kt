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
 * ## Where the block ranges come from
 *
 * From the **tree**.  This used to match braces off a token sequence, with a
 * comment here explaining that the parser definition built "a flat tree — tokens
 * only, no block nodes" so there was no node whose range could be the fold region.
 * That is no longer true and has not been for a version: `VelaParserDefinition`
 * replays a real tree, `VELA_BLOCK` elements cover their braces, and the node's
 * own range *is* the region.  So the block pass reads the tree, and its ranges are
 * the compiler's blocks by construction rather than by matching.
 *
 * The comment-run pass still reads leaves, and that is not a leftover: the tree
 * has no comment nodes, on purpose (a comment is trivia, and `vm.exe parse` prints
 * a line for no comment either).  Comments come from the same PSI tree's leaves,
 * so both passes read one source.
 *
 * The two pure functions below are the whole computation, and
 * `idea-plugin/fold-diff.ps1` runs the tree version against
 * [velaFoldRangesReference] — the brace-matching implementation this replaced —
 * over the corpus, so "the ranges did not change" is a table and not a claim.
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
        for (fold in velaFoldRanges(text)) {
            blockStarts.add(fold.start)
            val region = FoldingDescriptor(file, TextRange(fold.start, fold.end))
            region.setPlaceholderText(fold.placeholder)
            out.add(region)
        }
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


    private companion object {
        private val NO_REGIONS = emptyArray<FoldingDescriptor>()
    }
}

/** A foldable region: the body between two braces, and what to show instead. */
data class VelaFold(val start: Int, val end: Int, val placeholder: String)

/**
 * The block fold ranges, read from the parser's tree.
 *
 * A `VELA_BLOCK` element covers its own braces, so the region is the body: one
 * character past the `{` and one before the `}`.  Two conditions survive from the
 * brace-matching version, and both are about not hiding text for nothing: a region
 * needs a line break inside it, and it needs at least one line that holds a token
 * (a blank line is not a line of code).  The line count is the same number the old
 * implementation reported, so the placeholder text is unchanged.
 *
 * An `UNDECLARED_BLOCK` -- a body whose `{` was missing, which only recovery
 * creates -- has no braces to fold and is skipped, and so is a block that reaches
 * the end of the file without a `}`.
 */
fun velaFoldRanges(text: CharSequence): List<VelaFold> {
    val src = text.toString()
    val tree = VelaSyntaxParser.parse(src)
    val out = ArrayList<VelaFold>(16)
    collectBlockFolds(tree.root, tree.toks, src, text, out)
    return out
}

private fun collectBlockFolds(n: VelaSyntaxNode, toks: List<VelaTok>, src: String,
                              text: CharSequence, out: MutableList<VelaFold>) {
    if (n.kind == VelaNodeKind.BLOCK) {
        val open = n.startTok
        val close = n.endTok
        if (open >= 0 && close > open && close < toks.size) {
            val from = toks[open].end
            val to = toks[close].start
            if (to > from && hasLineBreak(text, from, to)) {
                val lines = tokenLines(toks, open + 1, close, text)
                if (lines > 0) out.add(VelaFold(from, to, blockPlaceholder(lines)))
            }
        }
    }
    for (c in n.children) collectBlockFolds(c, toks, src, text, out)
}

/**
 * The brace-matched block fold ranges: the implementation the tree replaced.
 *
 * Kept so the conversion is measurable -- `idea-plugin/fold-diff.ps1` runs both over
 * the corpus and reports every file whose regions differ -- and read no longer by
 * anything the plugin runs.  It matches braces over the parser's own token list,
 * which is what the builder did over the lexer's tokens before.
 */
fun velaFoldRangesReference(text: CharSequence): List<VelaFold> {
    val scan = VelaSyntaxScanner.scan(text.toString())
    val toks = scan.toks
    val out = ArrayList<VelaFold>(16)
    val open = ArrayList<Int>(16)
    for (i in toks.indices) {
        val t = toks[i]
        if (t.kind != VelaTokKind.OP) continue
        if (t.code == VelaOps.LBRACE) {
            open.add(i)
            continue
        }
        if (t.code != VelaOps.RBRACE) continue
        if (open.isEmpty()) continue
        val openIndex = open.removeAt(open.size - 1)
        val from = toks[openIndex].end
        val to = t.start
        if (to <= from) continue
        if (!hasLineBreak(text, from, to)) continue
        val lines = tokenLines(toks, openIndex + 1, i, text)
        if (lines <= 0) continue
        out.add(VelaFold(from, to, blockPlaceholder(lines)))
    }
    return out
}

/** How many distinct lines hold a token in `toks[from until to]`. */
private fun tokenLines(toks: List<VelaTok>, from: Int, to: Int, text: CharSequence): Int {
    var lines = 0
    var lastLine = -1
    var i = from
    while (i < to && i < toks.size) {
        val line = lineOfOffset(text, toks[i].start)
        if (line != lastLine) {
            lines++
            lastLine = line
        }
        i++
    }
    return lines
}

private fun lineOfOffset(text: CharSequence, offset: Int): Int {
    var line = 1
    var i = 0
    val end = if (offset > text.length) text.length else offset
    while (i < end) {
        if (text[i] == '\n') line++
        i++
    }
    return line
}

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
