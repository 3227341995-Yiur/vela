package dev.vela.plugin

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.awt.BorderLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Diagnostics, from the compiler.
 *
 * This is the plugin's reason to exist.  IDEA already colours text; what it
 * cannot know is whether a program is a legal Vela program, and the only thing
 * that knows is the compiler.  So every time a `.vela` file is edited (after the
 * editor settles, which is when the platform calls this), the file is handed to
 * `vm.exe check` and what comes back is drawn on the line it came from.
 *
 * That means the editor is never wrong twice: there is no second checker here to
 * disagree with the compiler, and a rule Vela enforces at build time is a
 * squiggle at edit time for free.  It also means the squiggles are exactly as
 * strict as the language — which, for Vela, is the point.
 */
class VelaExternalAnnotator : ExternalAnnotator<PsiFile, List<VelaProblem>>() {

    override fun collectInformation(file: PsiFile): PsiFile? {
        val vf = file.virtualFile ?: return null
        if (!isVelaFileName(vf.name)) return null
        return file
    }

    override fun doAnnotate(file: PsiFile): List<VelaProblem> {
        val project = file.project
        val vf = file.virtualFile ?: return emptyList()
        if (!vf.isInLocalFileSystem) return emptyList()
        val compiler = VelaCompiler.locate(project)
            ?: return listOf(
                VelaProblem(
                    1, "compiler",
                    "Vela: cannot find the compiler. Set its path in " +
                        "Settings | Languages & Frameworks | Vela, or set VELA_VM."
                )
            )
        // The *buffer*, not the file on disk.  Checking the saved file meant every
        // squiggle described the previous version of the program; see
        // VelaDiagnostics for why that was wrong and what replaced it.
        return VelaDiagnostics.checkWithCompiler(compiler, file.text)
    }

    /**
     * Three arguments, not four: the platform's `ExternalAnnotator` dropped the
     * `runOnTheFly` overload in 2026.2, and the three-argument form is what both
     * the on-the-fly and the batch pass call.  Annotating identically either way
     * is right here — a compiler diagnostic does not become more or less true
     * depending on when it was asked for.
     */
    override fun apply(file: PsiFile, problems: List<VelaProblem>?, holder: AnnotationHolder) {
        if (problems.isNullOrEmpty()) return
        val document = file.viewProvider.document ?: return
        for (p in problems) {
            val severity = if (p.kind == "safety error") {
                HighlightSeverity.ERROR
            } else if (p.kind == "type error") {
                HighlightSeverity.ERROR
            } else {
                HighlightSeverity.WARNING
            }
            val label = if (p.kind == "compiler") "" else "vela: ${p.kind}: "
            holder.newAnnotation(severity, label + p.message)
                .range(lineRange(document, p.line))
                .create()
        }
    }

    private fun lineRange(document: Document, line: Int): TextRange {
        if (line < 1 || line > document.lineCount) {
            return TextRange(0, 0)
        }
        val start = document.getLineStartOffset(line - 1)
        val end = document.getLineEndOffset(line - 1)
        return if (end > start) TextRange(start, end) else TextRange(start, start)
    }
}

/** Settings | Languages & Frameworks | Vela */
class VelaSettingsConfigurable : Configurable {
    private var panel: JPanel? = null
    private val path = TextFieldWithBrowseButton()

    override fun getDisplayName(): String = "Vela"

    override fun createComponent(): JComponent {
        val p = JPanel(BorderLayout(8, 8))
        val row = JPanel(BorderLayout(8, 0))
        row.add(JLabel("Compiler (vm.exe):"), BorderLayout.WEST)
        row.add(path, BorderLayout.CENTER)
        p.add(row, BorderLayout.NORTH)
        p.add(
            JLabel("<html>The Vela compiler, <code>selfhost/build/vm.exe</code> — built from "
                + "<code>vm.c</code> by a C compiler, or by <code>tools/build.ps1</code>.<br>"
                + "Left empty, the plugin looks at <code>\$VELA_VM</code>, then "
                + "<code>selfhost/build/vm.exe</code> in the project's directory or any "
                + "directory above it, then <code>PATH</code>.</html>"),
            BorderLayout.CENTER,
        )
        // The 262 replacements for the two deprecated calls that used to be here:
        // `addBrowseFolderListener(text, description, project, descriptor)` and
        // `FileChooserDescriptorFactory.createSingleFileDescriptor()`.
        path.addBrowseFolderListener(null, FileChooserDescriptorFactory.singleFile())
        panel = p
        return p
    }

    override fun isModified(): Boolean =
        path.text.trim() != VelaSettings.getInstance().compilerPath

    override fun apply() {
        VelaSettings.getInstance().compilerPath = path.text.trim()
    }

    override fun reset() {
        path.text = VelaSettings.getInstance().compilerPath
    }

    override fun disposeUIResources() {
        panel = null
    }
}
