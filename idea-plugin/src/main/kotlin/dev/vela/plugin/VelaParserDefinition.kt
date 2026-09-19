package dev.vela.plugin

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * A `ParserDefinition` for Vela that builds a **real** tree.
 *
 * The tree comes from [VelaSyntaxParser], a parser written for this plugin and
 * held to the compiler's own syntax tree by the differential harness in
 * `idea-plugin/ast-diff.ps1`: for every file in the corpus, the shape this parser
 * produces and the shape `vm.exe parse` prints come out the same, line for line,
 * over 100,000 node lines.  So the platform is not being told a second opinion
 * about a Vela program -- it is being told the compiler's own shape, in a form it
 * can hang PSI on.
 *
 * This file is only the *adapter*: it replays that tree onto the platform's
 * `PsiBuilder`, in tree order, so text ranges, offsets and element ranges come
 * from the platform and are exact.
 *
 * HOW THE REPLAY WORKS, AND WHY IT IS ORDERED THE WAY IT IS
 *
 * A node knows which tokens it claimed ([VelaSyntaxNode.startTok] and `endTok`
 * index the token list the parser was built from), so the adapter opens a marker
 * at the node's first token, walks the node's children, consumes the node's own
 * remaining tokens, and closes the marker at its last token.
 *
 * The order is the whole correctness argument, and the first version of this file
 * got it wrong in a way that is worth recording because it is invisible in the
 * node *count*: it consumed a child's tokens *after* closing the child's marker, so
 * a leaf token always landed one level too high -- `NAME` elements came out empty
 * with the identifier sitting beside them as a sibling, `param` elements came out
 * zero-width with `a`, `:`, `int` as siblings of the `def`, and `let x: int = 1`
 * produced an `EXPR` whose only child was an empty `NAME`.  The composite skeleton
 * was right and every leaf was in the wrong place.  So: `consumeTo(endOffset(node))`
 * runs *inside* the node's marker, before `done`, and the trivia between two
 * siblings is consumed inside the parent -- which is what a hand-written parser
 * gets for free and what a replay has to be explicit about.
 *
 * What a leaf can be, measured rather than assumed:
 *
 *  * whitespace **is** a leaf.  `PsiBuilder.advanceLexer()` on this platform
 *    (2026.2, measured through `idea-plugin/psi-tree-diff.ps1`, which parses
 *    through the platform's own `PsiBuilderImpl`) leaves `WHITE_SPACE` elements in
 *    the tree between the tokens, which is what the formatter and the folding
 *    builder walk over.  `getWhitespaceTokens()` does not remove them.
 *  * comments are leaves, in their real place, for the same reason: a run of
 *    comments folds, and a comment's indentation is formatted.  A comment after
 *    the last statement of a block lands inside that block, because the builder
 *    only walks forward and that is where the node's span ends.
 *  * a token no node claimed -- a `(`, a `,`, a `}` -- becomes a bare leaf inside
 *    whichever node's span covers it.
 *
 * A node that claimed no token of its own -- the synthesised `int 0` a
 * one-argument `range` implies, a `module` before its first statement -- is opened
 * and closed at a single position, which is what makes it a real (empty) element
 * rather than a range guessed from its children.
 *
 * The file element stands in for the parser's `module` node: the platform requires
 * the root marker to be the file element type, so `VELA_FILE` is where `MODULE`
 * would be and the module's statements are its children.  Nothing else is
 * flattened.
 */
private val VELA_FILE: IFileElementType = IFileElementType("VELA_FILE", VelaLanguage)

class VelaParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = VelaLexer()

    /**
     * Parse the file: build the real tree, then replay it onto the builder.
     *
     * The parser never throws and always returns a tree, so this cannot leave the
     * platform with a half-built file -- an invalid file (the state a file is in
     * whenever someone is typing) produces a tree with recovery nodes in it, and
     * everything after the error still gets its shape.  `psi-tree-diff.ps1` asserts
     * that over the corpus, broken files included: it parses every file through the
     * platform's own builder and requires the tree to cover the file's whole text.
     */
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val text = builder.originalText
        val tree = VelaSyntaxParser.parse(text.toString())
        VelaPsiReplay(builder, tree, text).replay(root)
        builder.getTreeBuilt()
    }

    override fun getFileNodeType(): IFileElementType = VELA_FILE

    override fun getWhitespaceTokens(): TokenSet = TokenSet.create(TokenType.WHITE_SPACE)

    override fun getCommentTokens(): TokenSet = TokenSet.create(VelaTokenTypes.COMMENT)

    /**
     * Empty on purpose.  A string literal here is a *value* whose meaning the
     * compiler fixes; claiming it as a platform "string literal element" would
     * hand it to language injection and text-block handling that Vela does not
     * have, and that would be a second opinion about a literal.
     */
    override fun getStringLiteralElements(): TokenSet = TokenSet.EMPTY

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = VelaPsiFile(viewProvider)
}

/**
 * Replays a [VelaSyntaxTree] onto a platform [PsiBuilder].
 *
 * Kept out of the parser definition because the replay is the piece that decides
 * *where* every marker and every leaf goes, and it is worth being able to test on
 * its own: `psi-tree-diff.ps1` drives it through the platform's real
 * `PsiBuilderImpl` and asserts, for every file in the corpus, that the tree it
 * produces has this parser's shape, that its leaves tile the file's text exactly,
 * and that every non-trivia leaf is one of the tokens this parser claimed.
 *
 * The builder is forward-only -- it can be advanced and read, never seeked -- so
 * everything here compares *source offsets*, which the parser recorded for every
 * token, and advances until the builder is where the tree says it should be.
 */
internal class VelaPsiReplay(
    private val builder: PsiBuilder,
    private val tree: VelaSyntaxTree,
    private val text: CharSequence,
) {
    private var nextProblem = 0

    /**
     * Replay the whole file under the root marker the platform asked for.
     *
     * Two "loose ends" passes close the tree, and both run *inside* the root
     * marker: the first consumes the tokens the parser's own root node claimed but
     * no child of it did, the second consumes anything the parser never saw at all
     * -- the tail of a file whose scan failed, or tokens a recovering parse threw
     * away.  Without the second pass those characters would be in no element, and a
     * file element whose range is shorter than the file is a platform invariant
     * broken rather than a diagnostic.
     */
    fun replay(root: IElementType) {
        val mark = builder.mark()
        replayChildren(tree.root)
        consumeTo(endOffset(tree.root))
        reportProblemsUpTo(text.length)
        consumeTo(text.length)
        mark.done(root)
    }

    private fun replayChildren(owner: VelaSyntaxNode) {
        for (child in owner.children) {
            replayNode(child)
        }
    }

    /**
     * Replays one node: consume up to it, open a marker, replay its children,
     * consume the node's own remaining tokens, close.
     *
     * Everything the node's span covers is consumed while the node's marker is
     * open, so a leaf token cannot drift up to the parent: that is the bug this
     * ordering exists to prevent (see the class comment on the parser definition).
     *
     * The two `align` calls are the other half of the same problem, and they exist
     * because the parser's tokens and the lexer's tokens are *not* always the same
     * granule.  A string literal is the example: the compiler records the offset of
     * the literal's *contents* (`"hello"` is `hello`, which is what `d_str` prints),
     * while `VelaLexer` emits one token for the whole `"hello"` including the quotes,
     * because that is the range the highlighter must colour.  Given the contents'
     * offsets, a naive replay opens the `str` marker at 35 with the lexer standing at
     * 34, cannot consume a token that straddles its own boundary, and ends up with an
     * empty `VELA_STR` whose `"hello"` leaf sits in the enclosing declaration instead.
     * Snapping the node's start down and its end up to the lexer's token boundaries
     * gives the element the text it denotes.  Measured, not reasoned: this is what
     * `psi-tree-diff.ps1` reported on `tests/build/strings.vel`.
     */
    private fun replayNode(n: VelaSyntaxNode) {
        consumeTo(alignStart(startOffset(n)))
        val mark = builder.mark()
        // Everything the parser complained about up to here belongs to the element
        // that is open.  The sub-list of problems is in parse order, so a problem is
        // attached at the deepest element whose marker is open when the walk reaches
        // its offset -- which is what makes an error visible in the PSI tree at all.
        // Reporting only the parser's ERROR *nodes* was not enough: some problems
        // (a missing `}`, "adjacent string literals") are recorded without one, and
        // `psi-tree-diff.ps1` found two files where the parser reported a problem and
        // the platform tree carried no error element at all.
        reportProblemsUpTo(builder.currentOffset)
        replayChildren(n)
        consumeTo(alignEndAfter(endOffset(n)))
        reportProblemsUpTo(builder.currentOffset)
        mark.done(VelaNodeTypes.of(n.kind))
    }

    /**
     * Report every problem that starts at or before [offset], each at its own place.
     *
     * `consumeTo(problem.start)` is what puts the error where it happened rather than
     * where the walk got to: for a file the scanner could not tokenise there are no
     * tokens at all, and this is what lets the one error element land on the offset
     * the scanner stopped at instead of at the end of the file.
     */
    private fun reportProblemsUpTo(offset: Int) {
        while (nextProblem < tree.problems.size) {
            val p = tree.problems[nextProblem]
            if (p.start > offset) return
            consumeTo(p.start)
            builder.error(p.message)
            nextProblem++
        }
    }

    /**
     * Snap a node's start down to the start of the lexer token that contains it.
     *
     * The builder is positioned at the next unread token, so "contains" is
     * `currentOffset <= target < currentOffset + tokenText.length`, and when that
     * holds the token is the node's first token and must not be consumed as trivia
     * before the marker opens.
     */
    private fun alignStart(target: Int): Int {
        val cur = builder.currentOffset
        if (cur >= target) return target
        val len = builder.tokenText?.length ?: 0
        if (len > 0 && target < cur + len) return cur
        return target
    }

    /**
     * Snap a node's end up to the end of the lexer token that straddles it, so the
     * node consumes the whole of that token rather than none of it.
     */
    private fun alignEndAfter(target: Int): Int {
        val cur = builder.currentOffset
        val len = builder.tokenText?.length ?: 0
        if (len > 0 && cur < target && target < cur + len) return cur + len
        return target
    }

    /**
     * Consume tokens up to [target]: every token whose end is at or before
     * [target] is advanced past, and the first token that starts at or after
     * [target] is left for the caller.
     *
     * This is the whole "loose ends" mechanism, and by construction it consumes
     * each token at most once and never moves backwards.
     */
    private fun consumeTo(target: Int) {
        val limit = if (target > text.length) text.length else target
        var guard = 0
        while (!builder.eof()) {
            guard++
            if (guard > 4_000_000) return
            val start = builder.currentOffset
            if (start >= limit) return
            val length = builder.tokenText?.length ?: 0
            if (length == 0) return
            if (start + length > limit) return
            builder.advanceLexer()
        }
    }

    /**
     * The source offset a node begins at.
     *
     * A node that claimed tokens answers with its first token's start.  A node that
     * claimed none -- a synthesised literal, a `module` before its first statement
     * -- answers with its first child's, or with where the builder stands, and the
     * scan-error node answers with the offset the scanner stopped at, so a file
     * that cannot be tokenised is still marked where it broke rather than at 0.
     */
    private fun startOffset(n: VelaSyntaxNode): Int {
        val i = n.startTok
        if (i >= 0 && i < tree.toks.size) return tree.toks[i].start
        var best = -1
        for (c in n.children) {
            val s = startOffset(c)
            if (s >= 0 && (best < 0 || s < best)) best = s
        }
        if (best >= 0) return best
        if (n.charStart >= 0 && n.charStart <= text.length) return n.charStart
        return builder.currentOffset
    }

    /** The source offset just past where a node ends, by the same rules. */
    private fun endOffset(n: VelaSyntaxNode): Int {
        val i = n.endTok
        if (i >= 0 && i < tree.toks.size) return tree.toks[i].end
        var best = -1
        for (c in n.children) {
            val e = endOffset(c)
            if (e > best) best = e
        }
        if (best >= 0) return best
        if (n.charEnd >= 0 && n.charEnd <= text.length) return n.charEnd
        return builder.currentOffset
    }
}

/** The file element: its name, its type, and nothing else it can honestly know. */
class VelaPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, VelaLanguage) {
    override fun getFileType() = VelaFileType
    override fun toString(): String = "Vela file: $name"
}
