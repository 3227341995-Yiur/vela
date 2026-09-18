package dev.vela.plugin

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.psi.PsiFile

/**
 * The "Vela" live-template context, and the reason the templates only fire in a
 * Vela file.
 *
 * A live template carries a context id in its XML (`liveTemplates/Vela.xml` binds
 * every template to `VELA`), and the platform expands a template in a file only
 * when the context type with that id says the caret is in it. So the file-type
 * test lives here, once, instead of being repeated in every template or in a
 * `TemplateContextType`-per-construct.
 *
 * ## The method that matters, and the one that does not
 *
 * `TemplateContextType` in this platform has *no abstract members*. Both
 * `isInContext` overloads have default bodies, and the newer one delegates to the
 * older one — `isInContext(TemplateActionContext)` calls
 * `isInContext(PsiFile, int)` — while the older one throws. So an implementation
 * that overrode only the old signature would be relying on a method the platform
 * no longer reaches through, and one that overrode neither would throw at the
 * first keystroke in a Vela file. Both are overridden here:
 * `isInContext(TemplateActionContext)` is the entry point, and the two-argument
 * form is answered as well so that any older caller gets an answer rather than an
 * exception.
 *
 * ## What "in context" means here
 *
 * The file is a Vela source — the suffix list in `VelaFileType.EXTENSIONS`, which
 * is the same list the file type registration, the New File action and the
 * `.vela`/`.vel` decision everywhere else read. Nothing finer: a `defn` template
 * offered inside a struct body is still the reader's business, and Vela's syntax
 * has no place where a function definition is meaningless but a mistake.
 */
class VelaTemplateContextType : TemplateContextType("VELA", "Vela") {

    override fun getPresentableName(): String = PRESENTABLE_NAME

    override fun isInContext(context: TemplateActionContext): Boolean = velaIsVelaFile(context.file)

    override fun isInContext(file: PsiFile, offset: Int): Boolean = velaIsVelaFile(file)

    /** The template preview is highlighted by the editor's own Vela highlighter. */
    override fun createHighlighter(): SyntaxHighlighter = VelaSyntaxHighlighter()

    companion object {
        /** The id the templates in `liveTemplates/Vela.xml` are bound to. */
        const val CONTEXT_ID: String = "VELA"

        /** What the templates page and the Surround With menu call it. */
        const val PRESENTABLE_NAME: String = "Vela"
    }
}
