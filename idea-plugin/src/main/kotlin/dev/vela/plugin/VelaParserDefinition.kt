package dev.vela.plugin

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * A `ParserDefinition` for Vela — with a parser that deliberately does not parse.
 *
 * The plugin's rule is that the compiler decides what a Vela program *means*, so
 * writing a second parser here would be writing a second opinion, and the two
 * would drift apart.  But the platform asks every language for a PSI tree, and
 * without one there are no leaves under the caret — so hover, parameter info and
 * completion have nothing to be handed and silently never fire.
 *
 * So the tree is **flat**: one file node, one leaf per token, in order.  Every
 * token gets a `PsiElement` with a real text range, which is exactly what the
 * editor-side features need (the name under the caret, and the text around it).
 * Structure comes from `VelaModel` — the plugin's own lexer — and meaning comes
 * from `vm.exe`.  Nothing here pretends to be a parse.
 *
 * If Vela ever grows a structured dump the plugin can consume (the compiler
 * already prints `vm.exe parse` in a canonical indented form and could print line
 * numbers with it), the honest upgrade is to build this tree from *that*.  Until
 * then a flat tree beats a wrong one.
 */
private val VELA_FILE: IFileElementType = IFileElementType("VELA_FILE", VelaLanguage)

class VelaParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?): Lexer = VelaLexer()

    /**
     * Consume everything and claim nothing: `root` is the file node, and the
     * builder's own token stream becomes its children.  A parser that reads no
     * grammar cannot disagree with the compiler about one.
     */
    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val mark = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        mark.done(root)
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

/** The file element: its name, its type, and nothing else it can honestly know. */
class VelaPsiFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, VelaLanguage) {
    override fun getFileType() = VelaFileType
    override fun toString(): String = "Vela file: $name"
}
