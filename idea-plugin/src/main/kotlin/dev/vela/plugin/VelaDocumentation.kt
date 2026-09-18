package dev.vela.plugin

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Hover documentation: what the name under the caret is, as it is written.
 *
 * The Vela parser produces a flat tree of token leaves, so the element handed to
 * this provider is a leaf (or, for a whole-file request, the file).  Both cases
 * are handled by reading *the file's text* and asking [VelaModel] what is at the
 * caret's offset — the same model completion uses, so the two cannot disagree
 * about what `Vec2` is.
 *
 * What this never does is explain *semantics*.  Whether a call type-checks is
 * the compiler's answer, drawn by `VelaExternalAnnotator` from what `vm.exe check`
 * printed; this file only says "here is the declaration you are pointing at, it
 * is a `def`, it is on line 12".  A hover that invented a meaning here would be
 * the one place in this plugin where the editor could be wrong on its own.
 */
class VelaDocumentationProvider : DocumentationProvider {

    /**
     * The declaration a name means.  A member of the enclosing struct wins over a
     * file-level name of the same spelling, because inside a struct `x` is that
     * struct's field — that is the only shadowing Vela's own scoping has, and it
     * is the one a reader would be surprised to see hovered differently.
     */
    private fun declarationOf(text: CharSequence, offset: Int, name: String): VelaSymbol? {
        val struct = VelaModel.enclosingStruct(text, offset)
        if (struct != null) {
            VelaModel.membersOf(text, struct).firstOrNull { it.name == name }?.let { return it }
        }
        VelaModel.visibleSymbols(text, offset).firstOrNull { it.name == name }?.let { return it }
        // A name the file declares further down is still a declaration of this file.
        return VelaModel.symbols(text).firstOrNull { it.name == name }
    }

    /** The builtin named `name`, with the description `VelaModel.BUILTINS` gives it. */
    private fun builtin(name: String): Pair<String, String>? =
        VelaModel.BUILTINS.firstOrNull { it.first == name }

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val target = originalElement ?: element
        val file = containingVela(target) ?: return null
        val text = fileText(file) ?: return null
        val offset = target.textRange.startOffset
        val name = nameAt(text, offset) ?: return null

        // The language's own names first: a builtin is not declared in this file, so
        // the model would find nothing and the hover would stay silent.
        builtin(name)?.let { (_, description) ->
            return html(
                signature = description,
                kind = "builtin",
                line = 0,
                note = "Vela builtin — provided by the language, not declared in this file.",
            )
        }

        val sym = declarationOf(text, offset, name) ?: return null
        val note = when (sym.kind) {
            VelaSymbolKind.FIELD ->
                "Field of `${VelaModel.symbols(text).getOrNull(sym.parent)?.name ?: "?"}`; type `${sym.type}`."
            VelaSymbolKind.PARAMETER -> "Parameter of type `${sym.type}`."
            VelaSymbolKind.METHOD -> "Method, returns `${sym.type}`."
            VelaSymbolKind.FUNCTION -> "Function, returns `${sym.type}`."
            VelaSymbolKind.STRUCT -> "Struct declared in this file."
        }
        return html(signature = sym.detail, kind = sym.kind.title, line = sym.line, note = note)
    }

    /** Ctrl-hover: the same one-liner, so the two hovers cannot say different things. */
    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        // The element to read a name from: the one the platform points at, falling
        // back to the original copy — either can be the file itself.
        val target = originalElement ?: element ?: return null
        val file = containingVela(target) ?: return null
        val text = fileText(file) ?: return null
        val offset = target.textRange.startOffset
        val name = nameAt(text, offset) ?: return null

        builtin(name)?.let { return "${it.first} — ${it.second}" }
        val sym = declarationOf(text, offset, name) ?: return null
        val suffix = if (sym.line > 0) " (line ${sym.line})" else ""
        return "${sym.kind.title} ${sym.detail}$suffix"
    }

    /*
     * The provider has no link targets of its own: everything it says is a
     * declaration in this file, and navigation to it is the platform's own
     * word/documentation machinery, which works off the leaf's range.  Returning
     * null is the honest answer — an invented PSI target would navigate a reader
     * somewhere the text does not point.
     */
    override fun getDocumentationElementForLookupItem(
        psiManager: PsiManager,
        obj: Any?,
        element: PsiElement?,
    ): PsiElement? = null

    override fun getDocumentationElementForLink(
        psiManager: PsiManager,
        link: String?,
        context: PsiElement?,
    ): PsiElement? = null

    override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): MutableList<String>? = null

    // ------------------------------------------------------------------ help

    /**
     * The Vela file an element belongs to, or null when it is not one.  A leaf
     * inside a `.vel` file is the normal case; a directory, a foreign language, or
     * a file with no name gives null, and the caller then says nothing.
     */
    private fun containingVela(element: PsiElement): PsiFile? {
        val file = element.containingFile ?: (element as? PsiFile) ?: return null
        if (file.virtualFile?.let { !isVelaFileName(it.name) } == true) return null
        return file
    }

    /**
     * The whole file's text.
     *
     * `file.text` is what the model wants, but for a leaf inside a file whose PSI
     * is not fully built (the flat token tree has no committed document), the
     * document is the only complete source.  The offsets the platform reports are
     * document offsets, so the document is preferred when there is one.
     */
    private fun fileText(file: PsiFile): CharSequence? {
        val project = file.project
        if (!project.isDefault) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)
            if (document != null) return document.immutableCharSequence
        }
        val text = file.text
        return if (text.isEmpty()) null else text
    }

    /**
     * The popup body: the signature as written, which kind of declaration it is,
     * the line it is on, and one sentence of what the model actually recorded.
     * `DocumentationMarkup`'s own constants are used rather than a hand-written
     * `<html>` wrapper, so the popup looks like every other language's.
     *
     * A builtin has no line in this file, and rather than invent one the line is
     * omitted and the note says where the name comes from.
     */
    private fun html(signature: String, kind: String, line: Int, note: String): String {
        val where = if (line > 0) "line $line" else "not declared in this file"
        return buildString {
            append(DocumentationMarkup.DEFINITION_START)
            append(escape(signature))
            append(DocumentationMarkup.DEFINITION_END)
            append(DocumentationMarkup.CONTENT_START)
            append(escape(note))
            append(DocumentationMarkup.CONTENT_END)
            append(DocumentationMarkup.SECTIONS_START)
            append(DocumentationMarkup.GRAYED_START)
            append("$kind · $where")
            append(DocumentationMarkup.GRAYED_END)
            append(DocumentationMarkup.SECTIONS_END)
        }
    }

    /** Vela source text is displayed, not evaluated: `<` and `&` must stay characters. */
    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
