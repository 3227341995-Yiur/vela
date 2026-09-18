package dev.vela.plugin

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType

/**
 * Which brackets pair with which, for Ctrl+Shift+M, the caret-adjacent brace
 * highlight, and the platform's own "insert the closing bracket" behaviour.
 *
 * ## `{ }`, `( )`, `[ ]` — and deliberately **not** `< >`
 *
 * The brief for this file asked for `<`/`>` "only if Vela actually uses them as
 * paired brackets in source (check the lexer; a wrong matcher is worse than
 * none)". The lexer answers no, twice over:
 *
 *  * `VelaLexer.scanOperator` gives **every** operator in Vela the same element
 *    type, `VelaTokenTypes.OPERATOR`. `<` and `>` therefore have the same
 *    `IElementType` as `+`, `-`, `*` and `=` — there is no type that means "an
 *    angle bracket". A `BracePair(OPERATOR, OPERATOR)` would tell the platform
 *    that `+` opens a bracket that `<` closes. That is worse than no pair.
 *  * Vela does not need one. `<` and `>` are only comparison and shift operators
 *    (`SPEC.md` §2); the one construct that looks generic, `Array[T, N]`, is
 *    bracketed with `[ ]`, and there are no type arguments written with angle
 *    brackets anywhere in `SPEC.md` or in `tests/build/`.
 *
 * ## Why left and right are the same type in all three pairs
 *
 * For the same reason: `VelaTokenTypes.PARENS` is the type of *both* `(` and `)`,
 * `BRACKETS` of both `[` and `]`, `BRACES` of both `{` and `}` — one type per
 * bracket *kind*, because that is what the plugin's lexer produces and the
 * plugin's rule is that there is exactly one scanner.
 *
 * The consequence, stated plainly because it is a real limitation rather than a
 * detail: `BracePair` cannot by itself tell `(` from `)`, so the discrimination
 * is left to the platform's brace-matching layer, which classifies a
 * *one-character* brace token by the character at its start
 * (`com.intellij.codeInsight.highlighting.PairedBraceMatcherAdapter` and
 * `BraceMatchingUtil` are the classes that do it, and the adapter exists in this
 * platform for exactly this case). Two things follow, and neither is papered
 * over:
 *
 *  * If that text-based classification were absent, `(` and `)` would be
 *    indistinguishable here and matching would need distinct token types in the
 *    lexer — a change this plugin cannot make without a second scanner.
 *  * `isPairBraces` on two same-typed tokens answers true, so a caret sitting
 *    between two *identical* brackets (`((`) can be highlighted as a pair. That
 *    is a cosmetic wart in an unusual position, and it is the trade for reusing
 *    the compiler's own token set.
 *
 * `isStructural` is true only for `{}`: those are the braces that delimit a block
 * of statements, and it is what the platform's "select block" and structural
 * brace highlighting key off.
 */
class VelaPairedBraceMatcher : PairedBraceMatcher {

    private val pairs = arrayOf(
        BracePair(VelaTokenTypes.PARENS, VelaTokenTypes.PARENS, false),
        BracePair(VelaTokenTypes.BRACKETS, VelaTokenTypes.BRACKETS, false),
        BracePair(VelaTokenTypes.BRACES, VelaTokenTypes.BRACES, true),
    )

    override fun getPairs(): Array<BracePair> = pairs

    /**
     * Always allowed: `VelaLexer` has no token that a `}` may never follow, and
     * refusing some type here would only make the platform's own bracket
     * insertion behave differently in Vela than in every other brace language.
     */
    override fun isPairedBracesAllowedBeforeType(lBraceType: IElementType, tokenType: IElementType?): Boolean = true

    /**
     * The start of the construct a brace belongs to, for "select the block" from a
     * closing brace.  Vela has a flat PSI tree — there is no block node whose
     * range could be returned — so the honest answer is the brace's own offset,
     * which selects nothing beyond the brace instead of inventing a range.
     */
    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
}
