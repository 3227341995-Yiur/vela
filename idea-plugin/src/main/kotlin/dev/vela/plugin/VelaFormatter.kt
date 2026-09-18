package dev.vela.plugin

import com.intellij.formatting.ASTBlock
import com.intellij.formatting.Alignment
import com.intellij.formatting.Block
import com.intellij.formatting.ChildAttributes
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CodeStyleSettings

/**
 * Reformat Code, and the reformat half of paste.
 *
 * ## Why this is a whitespace-only formatter, by construction
 *
 * The platform's formatter never rewrites a token: it decides, for every pair of
 * adjacent leaves, what the *whitespace between them* should be, and writes that.
 * There is no hook in this API through which an implementation could change a
 * token's text.  So "formatting cannot change what a program means" is not a
 * property this code tries hard to have — it is a property of the shape of the
 * feature.  The rules in `VelaFormatRules` then add the one thing that *could*
 * still change meaning — deleting a line break and thereby joining two
 * statements, which in Vela are separated by newlines — and forbid it.
 *
 * ## Where the tree comes from
 *
 * `VelaParserDefinition` builds a *flat* tree: one leaf per token, no grammar, no
 * block nodes.  So there is no `def` node to hang a block on, and this model does
 * not invent one.  The root block is the file node and every significant token is
 * one of its children; nesting is expressed as an *indent* on each token block,
 * computed from the brace depth the token sits at, rather than as a tree of
 * blocks.  That is honest about the tree the plugin actually has, and it needs no
 * synthetic node whose text range would not match a real one.
 *
 * ## Why `createModel(FormattingContext)` and not the older overloads
 *
 * `FormattingModelBuilder` in this platform declares five `createModel`
 * overloads and **not one of them is abstract**: each has a default body that
 * forwards to the two-argument form, and the two-argument form builds a
 * `FormattingContext` and calls *this* one.  An implementation that overrides
 * nothing therefore recurses until the stack ends.  `createModel(FormattingContext)`
 * is the one the platform itself treats as the entry point — the JSON formatter
 * shipped in `intellij.json.jar` overrides this method and this method only —
 * so it is the one overridden here, and every other overload reaches it by the
 * platform's own forwarding.
 */
class VelaFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(context: FormattingContext): FormattingModel {
        val file = context.containingFile
        val settings = context.codeStyleSettings
        // The *file's* node, not the context's: the indentation of a line depends on
        // the braces that enclose it, and when only part of a file is reformatted
        // (paste, Reformat Selection) those braces are outside the range the platform
        // asked about.  `getNode()` would still be the file node here — the platform
        // passes the file element and lets the range be handled by the formatter —
        // but asking the file directly says so instead of relying on it.
        val node = file.node ?: context.node
        return FormattingModelProvider.createFormattingModelForPsiFile(file, VelaRootBlock(node, settings), settings)
    }
}

/**
 * The indentation one level is worth, read from the code style settings so the
 * Tabs and Indents page is not decorative.  `VelaLanguageCodeStyleSettingsProvider`
 * writes 4 as the default; this is what makes a reader's change to it take
 * effect, and it falls back to 4 rather than to 0 when no Vela section has been
 * written yet — "no settings" must not mean "no indentation".
 */
internal fun velaIndentStep(settings: CodeStyleSettings): Int {
    // `initIndentOptions` rather than the getter: the getter can answer null for a
    // language whose options have not been created yet, and this is the method the
    // platform provides to create them.
    val size = settings.getCommonSettings(VelaLanguage).initIndentOptions().INDENT_SIZE
    return if (size > 0) size else VelaFormatRules.INDENT_SIZE
}

/**
 * The file, as a block whose children are its significant tokens.
 *
 * `getChildAttributes` answers with an *absolute* none-indent, meaning "my
 * children's indent is the whole story" — which is what makes the per-token
 * space indents below come out as exactly `depth * indentSize` columns instead of
 * being added to an indent inherited from an ancestor.  There is only one
 * ancestor here (this block), so the arithmetic is unambiguous either way; saying
 * it absolutely removes the doubt.
 */
class VelaRootBlock(private val fileNode: ASTNode, settings: CodeStyleSettings) : ASTBlock {

    private val children: List<VelaLeafBlock>

    init {
        val step = velaIndentStep(settings)
        val nodes = ArrayList<ASTNode>(256)
        val tokens = ArrayList<VelaToken>(256)
        collectVelaTokens(fileNode, nodes, tokens)
        val analysis = VelaFormatRules.analyse(tokens)
        val built = ArrayList<VelaLeafBlock>(nodes.size)
        for (i in nodes.indices) {
            built.add(
                VelaLeafBlock(
                    nodes[i],
                    analysis.spacingAt(i),
                    Indent.getSpaceIndent(analysis.depths[i] * step),
                )
            )
        }
        children = built
    }

    override fun getNode(): ASTNode = fileNode

    override fun getSubBlocks(): List<Block> = children

    override fun getTextRange(): TextRange = fileNode.textRange

    override fun getIndent(): Indent? = Indent.getNoneIndent()

    /**
     * The gap between two adjacent tokens.  Each token block carries the spacing
     * that precedes it — the rules are a function of the token pair, and computing
     * them once when the model is built is the same answer as computing them here,
     * without asking `VelaFormatRules` per pair on every pass the formatter makes.
     *
     * Whitespace leaves are deliberately *not* children (see `collectVelaTokens`):
     * the platform reads the existing whitespace between two sub-blocks out of the
     * document itself, which is what lets a rule say "keep the line breaks that are
     * there".
     */
    override fun getSpacing(child1: Block?, child2: Block): Spacing? {
        val leaf = child2 as? VelaLeafBlock ?: return null
        return leaf.spacingBefore.toSpacing()
    }

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
        ChildAttributes(Indent.getAbsoluteNoneIndent(), null)

    override fun getWrap(): Wrap? = null

    override fun getAlignment(): Alignment? = null

    /** Nothing here can produce a half-parsed construct: the tree is tokens only. */
    override fun isIncomplete(): Boolean = false

    override fun isLeaf(): Boolean = false

    override fun toString(): String = "Vela file block"
}

/** One significant token: a block with a text range and an indentation, no children. */
class VelaLeafBlock(
    private val node: ASTNode,
    val spacingBefore: VelaSpacing,
    private val indent: Indent,
) : ASTBlock {

    override fun getNode(): ASTNode = node

    override fun getSubBlocks(): List<Block> = emptyList()

    override fun getTextRange(): TextRange = node.textRange

    override fun getIndent(): Indent? = indent

    override fun getSpacing(child1: Block?, child2: Block): Spacing? = null

    override fun getChildAttributes(newChildIndex: Int): ChildAttributes =
        ChildAttributes(Indent.getAbsoluteNoneIndent(), null)

    override fun getWrap(): Wrap? = null

    override fun getAlignment(): Alignment? = null

    override fun isIncomplete(): Boolean = false

    override fun isLeaf(): Boolean = true

    override fun toString(): String = node.text
}
