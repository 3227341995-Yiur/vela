package dev.vela.plugin

import com.intellij.lang.PsiStructureViewFactory
import com.intellij.psi.PsiFile
import com.intellij.ide.structureView.StructureViewBuilder

/**
 * The two lines of glue between the platform's language-keyed extension point and
 * [VelaStructureViewBuilder].
 *
 * This class exists because the wiring the plugin originally used does not exist.
 * It registered `<lang.structureViewBuilder language="Vela" …>`, and there is no
 * such extension point: the id `com.intellij.lang.structureViewBuilder` is
 * declared by nothing in the installed IDE, and not one of its 1987 plugin jars
 * uses it.  A registration against an unknown id is not an error the platform
 * reports — the plug-in loads, the entry is a no-op, and the feature the user
 * asked for simply never appears.  The build's verifier caught it; a user would
 * have found it by asking why the Structure view is empty.
 *
 * What the platform actually offers for a language is
 * `com.intellij.lang.psiStructureViewFactory`, whose value must be a
 * [PsiStructureViewFactory] — a factory, not a builder.  So this is a factory
 * that returns the builder the plugin already had.
 *
 * The other route is file-type keyed (`<structureViewBuilder key="Vela File" …>`,
 * taking a `StructureViewBuilderProvider`).  It is not needed here: the platform
 * registers `LanguageStructureViewBuilderProvider` for every file type and
 * dispatches through this language-keyed point, so one registration covers
 * `.vel`, `.vela` and anything else the file type ever claims.
 */
class VelaPsiStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? =
        VelaStructureViewBuilder(psiFile.project)
}
