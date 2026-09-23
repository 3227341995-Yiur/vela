package dev.vela.plugin

import com.intellij.ide.structureView.FileEditorPositionListener
import com.intellij.ide.structureView.ModelListener
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.Filter
import com.intellij.ide.util.treeView.smartTree.Grouper
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.util.ui.EmptyIcon
import javax.swing.Icon

/**
 * The Structure view: a file, its declarations, and where they are.
 *
 * Everything shown here comes from [VelaModel], which reads *characters* with the
 * lexer and decides nothing about meaning.  That is the only source that can
 * answer this question honestly: there is no Vela PSI (a `ParserDefinition`
 * produces flat token leaves, with no element carrying a range), and the one
 * thing that does decide meaning — the compiler — prints a syntax dump with no
 * line numbers at all, which is the wrong shape for this view.
 *
 * So a line number here is a fact about the text ("`def dot` starts on line 12"),
 * read from the *current* document every time it is asked.  The builder is
 * created per request by the platform and holds nothing, so a node cannot go
 * stale between edits — there is nothing to go stale.
 */

class VelaStructureViewBuilder(private val project: Project) : TreeBasedStructureViewBuilder() {

    /**
     * A fresh model per call, over the document as it is right now.  This is also
     * how the view follows edits: the platform rebuilds the tree by asking the
     * builder again, so no listener and no cached copy is needed.
     */
    override fun createStructureViewModel(editor: Editor?): StructureViewModel {
        return VelaStructureViewModel(project, editor)
    }
}

/**
 * The model the platform's structure tree is built from.
 *
 * It is deliberately *not* Psi-based: [TreeBasedStructureViewBuilder] would have
 * accepted a `StructureViewModelBase`, but every constructor of that takes a
 * `PsiFile`, and a Vela file has no meaningful `PsiFile` to give it.  So the root
 * is a plain tree element carrying the file, the text and the line numbers.
 */
private class VelaStructureViewModel(
    private val project: Project,
    private val editor: Editor?,
) : StructureViewModel {

    /**
     * Where the model gets its text and its file, on demand.  Nothing here is
     * held: a node can only ever describe the text it was built from, and it is
     * built from whatever the document says at that moment.
     */
    private val file: VirtualFile? get() = editor?.virtualFile

    private val text: CharSequence get() = currentText(file)

    override fun getRoot(): StructureViewTreeElement = VelaStructureElement.root(project, file)

    /**
     * Which element the caret is inside, so the tree can follow the editor.  The
     * innermost declaration that does not start after the caret's line — the same
     * "nearest preceding declaration" rule completion uses, and the most precise
     * answer the lexer's line numbers support.
     */
    override fun getCurrentEditorElement(): Any? {
        val offset = editor?.caretModel?.offset ?: return null
        val all = VelaModel.symbols(text)
        val caretLine = VelaModel.lineOf(text, offset)
        var best = -1
        for (i in all.indices) {
            val s = all[i]
            if (s.kind == VelaSymbolKind.PARAMETER) continue
            if (s.line > caretLine) continue
            if (best < 0 || s.line > all[best].line) best = i
        }
        if (best < 0) return null
        return VelaStructureElement.of(project, file, all[best])
    }

    // Sorting, grouping and filtering are all "off": the view's whole value is
    // that it shows the file in the order the reader wrote it, and a default
    // alphabetical sorter would destroy exactly that.

    override fun getSorters(): Array<Sorter> = EMPTY_SORTERS

    override fun getGroupers(): Array<Grouper> = EMPTY_GROUPERS

    override fun getFilters(): Array<Filter> = EMPTY_FILTERS

    // This model has no live tree to notify and tracks no position ahead of a
    // request, so these listeners have nothing to hear.  They are here because the
    // interface has them; pretending otherwise would be a lie in a different way.

    override fun addEditorPositionListener(listener: FileEditorPositionListener) = Unit

    override fun removeEditorPositionListener(listener: FileEditorPositionListener) = Unit

    override fun addModelListener(listener: ModelListener) = Unit

    override fun removeModelListener(listener: ModelListener) = Unit

    override fun dispose() = Unit

    override fun shouldEnterElement(element: Any?): Boolean = false

    private companion object {
        val EMPTY_SORTERS = emptyArray<Sorter>()
        val EMPTY_GROUPERS = emptyArray<Grouper>()
        val EMPTY_FILTERS = emptyArray<Filter>()
    }
}

/**
 * One node of the structure view.
 *
 * It is both a [StructureViewTreeElement] (the platform's shape for a node) and a
 * [Navigatable]: the platform's structure view only accepts a Navigatable as an
 * activation target, so the second interface is not optional if navigation — and
 * therefore "enter on a node" — is to work at all.
 *
 * Both are implemented from the same fact, [VelaSymbol.line], so what a node says
 * and where it goes cannot disagree.  [getValue] returns the symbol for a
 * declaration and the file for the root; it is *not* a PSI element and does not
 * pretend to be one, which is why the caret is moved by hand in [navigate] and
 * [canNavigateToSource] answers `false`.
 */
private class VelaStructureElement private constructor(
    private val project: Project,
    private val file: VirtualFile?,
    private val symbol: VelaSymbol?,
) : StructureViewTreeElement, Navigatable, ItemPresentation {

    override fun getValue(): Any = symbol ?: (file ?: this)

    /**
     * Rebuilt from the document *every* time it is asked, never cached in a field.
     *
     * A cached child list would be a snapshot of the file as it was when this node
     * was created, and the rows under it would then be names that may no longer
     * exist.  Re-reading costs one lex of the buffer — `VelaModel` caches that by
     * content, so the buffer is scanned once per keystroke however often the
     * platform asks — and it is the difference between a view that is a statement
     * about the file and one that is a memory of it.
     */
    override fun getChildren(): Array<TreeElement> {
        val text = currentText(file)
        val all = VelaModel.symbols(text)
        val me = symbol
        val built = if (me == null) {
            // The root: the file's top-level declarations, in source order — the
            // order the lexer saw them, which is the order a reader expects.
            all.filter { it.parent < 0 }.map { VelaStructureElement(project, file, it) }
        } else {
            // A struct shows its fields, methods and parameters; a callable shows
            // its parameters.  `VelaSymbol.parent` is the index of the enclosing
            // declaration, which is the only parenthood Vela has.  That index is
            // looked up afresh, because the list it indexes was just re-read: a
            // symbol's identity here is its kind, name and line — exactly what the
            // row shows and what navigation uses.
            val index = all.indexOfFirst { it.kind == me.kind && it.name == me.name && it.line == me.line }
            if (index < 0) {
                emptyList()
            } else {
                all.filter { it.parent == index }.map { VelaStructureElement(project, file, it) }
            }
        }
        return built.toTypedArray()
    }

    override fun getPresentation(): ItemPresentation = this

    override fun getPresentableText(): String {
        val me = symbol ?: return file?.name ?: "Vela file"
        return when (me.kind) {
            VelaSymbolKind.STRUCT -> "struct ${me.name}"
            // A callable's whole signature is what `VelaModel` wrote down beside
            // the name — one fact, also used by hover and parameter info.
            VelaSymbolKind.METHOD, VelaSymbolKind.FUNCTION -> me.detail
            VelaSymbolKind.FIELD -> "${me.name}: ${me.type}"
            VelaSymbolKind.PARAMETER -> if (me.name == "self") "self" else "${me.name}: ${me.type}"
            // SPEC.md §13: an enum and a variant both present as the declaration the
            // model read, which is the same text hover and find-usages use.
            VelaSymbolKind.ENUM, VelaSymbolKind.VARIANT -> me.detail
        }
    }

    override fun getLocationString(): String {
        val me = symbol ?: return file?.path ?: ""
        return "line ${me.line}"
    }

    override fun getIcon(unused: Boolean): Icon =
        if (symbol == null) EmptyIcon.ICON_16 else VelaFileType.icon

    // --------------------------------------------------------------- navigation

    override fun canNavigate(): Boolean = file != null && symbol != null

    /**
     * The caret work is done here rather than by handing the platform a PSI
     * element, because there is none to hand it.  [canNavigateToSource] is
     * consequently `false`: the platform uses that flag to decide whether this
     * element may be treated as a source reference, and answering `true` while
     * navigating by hand is how a plugin claims a target it cannot show.
     */
    override fun canNavigateToSource(): Boolean = false

    override fun navigate(requestFocus: Boolean) {
        val target = file ?: return
        val line = symbol?.line ?: return
        // `line` is 1-based, as the compiler's diagnostics are.  A stale line (the
        // file shrank between the model being read and the click) is clamped
        // rather than sent past the end of the document.
        val document = FileDocumentManager.getInstance().getDocument(target)
        val safeLine = if (document == null) line else line.coerceIn(1, document.lineCount)
        OpenFileDescriptor(project, target, safeLine - 1, 0).navigate(requestFocus)
    }

    companion object {
        /** The file's root node; its children are the top-level declarations. */
        fun root(project: Project, file: VirtualFile?): VelaStructureElement =
            VelaStructureElement(project, file, null)

        /** A node for one declaration, identified by the symbol itself. */
        fun of(project: Project, file: VirtualFile?, symbol: VelaSymbol): VelaStructureElement =
            VelaStructureElement(project, file, symbol)
    }
}

/**
 * The text a node describes: asked of the document each time, never held.
 *
 * A structure node must describe the buffer as it is now.  Holding the text in a
 * node — or worse, in a field of the model — would make a row a claim about the
 * file as it was when the platform happened to ask, and the one thing this view
 * must never do is show a name that is no longer in the file.
 */
private fun currentText(file: VirtualFile?): CharSequence {
    val document = file?.let { FileDocumentManager.getInstance().getDocument(it) }
    return document?.charsSequence ?: ""
}
