package dev.vela.plugin

import com.intellij.lang.Commenter

/**
 * `#` to end of line — the only comment Vela has.
 *
 * ## What this class is and is not
 *
 * `Commenter` is a *description* of a language's comments, not the Ctrl+/ action.
 * The action itself is the platform's `CommentByLineCommentAction`, and it is the
 * thing that implements all three behaviours asked for here: Ctrl+/ on one line,
 * Ctrl+/ across a selection, and Ctrl+/ again to uncomment. It decides what to do
 * from this contract alone — with a line prefix and no block prefix it comments
 * every line of the selection, and uncomments a selection whose lines all already
 * begin with the prefix (which is what "correct behaviour on lines that are
 * already comments" means: a second Ctrl+/ removes the `#` rather than adding a
 * second one).
 *
 * Reimplementing that action here would be a second opinion about what Ctrl+/
 * does, and the plugin's rule is one answer per question. So the block members
 * answer `null` — Vela has no block comment (`SPEC.md` §1: a comment is `#` to the
 * end of the line, and `VelaLexer` reads exactly that) — and the line prefix is
 * the same `#` the lexer reads a comment from, which is why the character is
 * named here and in `VelaLexer` and nowhere else.
 */
class VelaCommenter : Commenter {

    override fun getLineCommentPrefix(): String = "#"

    override fun getBlockCommentPrefix(): String? = null

    override fun getBlockCommentSuffix(): String? = null

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null
}
