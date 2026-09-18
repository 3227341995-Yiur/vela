package dev.vela.plugin

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * Colours, taken from the platform's own keys rather than invented: a reader who
 * has tuned their scheme for every other language gets Vela looking like the
 * rest of their editor, which is the whole point of using a key instead of a
 * colour.
 */
object VelaColors {
    @JvmField val KEYWORD =
        TextAttributesKey.createTextAttributesKey("VELA_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val IDENTIFIER =
        TextAttributesKey.createTextAttributesKey("VELA_IDENT", DefaultLanguageHighlighterColors.IDENTIFIER)
    @JvmField val NUMBER =
        TextAttributesKey.createTextAttributesKey("VELA_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    @JvmField val STRING =
        TextAttributesKey.createTextAttributesKey("VELA_STRING", DefaultLanguageHighlighterColors.STRING)
    @JvmField val COMMENT =
        TextAttributesKey.createTextAttributesKey("VELA_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    @JvmField val OPERATOR =
        TextAttributesKey.createTextAttributesKey("VELA_OP", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    @JvmField val BRACES =
        TextAttributesKey.createTextAttributesKey("VELA_BRACES", DefaultLanguageHighlighterColors.BRACES)
    @JvmField val BRACKETS =
        TextAttributesKey.createTextAttributesKey("VELA_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    @JvmField val PARENS =
        TextAttributesKey.createTextAttributesKey("VELA_PARENS", DefaultLanguageHighlighterColors.PARENTHESES)
    @JvmField val COMMA =
        TextAttributesKey.createTextAttributesKey("VELA_COMMA", DefaultLanguageHighlighterColors.COMMA)
    @JvmField val DOT =
        TextAttributesKey.createTextAttributesKey("VELA_DOT", DefaultLanguageHighlighterColors.DOT)
    @JvmField val COLON =
        TextAttributesKey.createTextAttributesKey("VELA_COLON", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    @JvmField val SEMICOLON =
        TextAttributesKey.createTextAttributesKey("VELA_SEMI", DefaultLanguageHighlighterColors.SEMICOLON)
    @JvmField val BAD =
        TextAttributesKey.createTextAttributesKey("VELA_BAD", HighlighterColors.BAD_CHARACTER)
}

class VelaSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = VelaLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        VelaTokenTypes.KEYWORD -> arrayOf(VelaColors.KEYWORD)
        VelaTokenTypes.IDENTIFIER -> arrayOf(VelaColors.IDENTIFIER)
        VelaTokenTypes.NUMBER -> arrayOf(VelaColors.NUMBER)
        VelaTokenTypes.STRING -> arrayOf(VelaColors.STRING)
        VelaTokenTypes.COMMENT -> arrayOf(VelaColors.COMMENT)
        VelaTokenTypes.OPERATOR -> arrayOf(VelaColors.OPERATOR)
        VelaTokenTypes.BRACES -> arrayOf(VelaColors.BRACES)
        VelaTokenTypes.BRACKETS -> arrayOf(VelaColors.BRACKETS)
        VelaTokenTypes.PARENS -> arrayOf(VelaColors.PARENS)
        VelaTokenTypes.COMMA -> arrayOf(VelaColors.COMMA)
        VelaTokenTypes.DOT -> arrayOf(VelaColors.DOT)
        VelaTokenTypes.COLON -> arrayOf(VelaColors.COLON)
        VelaTokenTypes.SEMICOLON -> arrayOf(VelaColors.SEMICOLON)
        TokenType.BAD_CHARACTER -> arrayOf(VelaColors.BAD)
        else -> TextAttributesKey.EMPTY_ARRAY
    }
}

class VelaSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: com.intellij.openapi.vfs.VirtualFile?):
        SyntaxHighlighter = VelaSyntaxHighlighter()
}
