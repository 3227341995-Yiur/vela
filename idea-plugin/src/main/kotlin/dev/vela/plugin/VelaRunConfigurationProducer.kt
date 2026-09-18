package dev.vela.plugin

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import java.io.File

/**
 * What makes a Vela file runnable in IDEA's *own* Run and Debug menus.
 *
 * The first version of this plugin grew a "Vela" submenu of its own — Check, Build
 * and Run, New File — and the user asked the right question about it: what is that
 * for, and can it be real instead?  The honest answer was that most of it was
 * ceremony.  IDEA already has a Run menu, a Run window, a re-run history, a gutter
 * arrow, and a Debug action; a private submenu duplicates all of it, worse, and
 * teaches the user a second place to look.  A *producer* is how a language says
 * "this file is something you can run" and gets every one of those for free:
 *
 *   * right-click a `.vel` file → `Run 'fib.vel'` and `Debug 'fib.vel'`
 *   * the same entries in the top Run menu and in the project tree's Run section
 *   * a green arrow in the editor gutter, next to `def main`
 *   * the Run window, with re-run and stop
 *   * and, once the debugger track lands, Debug goes through the same entry
 *
 * The configuration it produces is the ordinary `VelaRunConfiguration`, so there is
 * one run path, not two: `vm.exe build` and then the executable, in one console.
 * Debug is still refused by the configuration itself — deliberately, until Vela
 * has a debugger to hand it to — and IDEA shows that refusal rather than a Debug
 * that quietly runs without one.
 *
 * The file test is by *name*, not by language, because it runs before any PSI is
 * asked for and must be cheap: it is asked on every context-menu build, for every
 * file, in every project.
 */
@Suppress("DEPRECATION")
class VelaRunConfigurationProducer : RunConfigurationProducer<VelaRunConfiguration>(
    // Both available constructors are marked deprecated in 262
    // (`(ConfigurationType)` and `(ConfigurationFactory)`), the no-argument one is
    // not reachable from Kotlin ("no value passed for parameter 'p0'"), and
    // `getConfigurationType()` is `final`.  The suppression is deliberate and
    // narrow: `runConfigurationProducer` is still the extension point the platform
    // itself registers producers under — the installed product's own descriptors
    // use it — so the mechanism is alive and only these overloads carry the
    // marker.  Evidence gathered from `intellij.platform.lang.jar` and the
    // installed descriptors, not from documentation.
    ConfigurationTypeUtil.findConfigurationType(VelaRunConfigurationType::class.java)
        .configurationFactories.first(),
) {

    override fun setupConfigurationFromContext(
        configuration: VelaRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = velaFile(context) ?: return false
        configuration.setFilePath(FileUtil.toSystemDependentName(file.absolutePath))
        // The name is what the user reads in the Run menu and the Run window.  The
        // file's own name is the honest one: this configuration is "the Vela
        // program in this file", and the path is already in the settings.
        configuration.name = file.name
        return true
    }

    override fun isConfigurationFromContext(
        configuration: VelaRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = velaFile(context) ?: return false
        return configuration.getFilePath() == FileUtil.toSystemDependentName(file.absolutePath)
    }

    /** The Vela file the context is about, or null when it is not about one. */
    private fun velaFile(context: ConfigurationContext): File? {
        val vf = context.location?.virtualFile ?: return null
        if (vf.isDirectory) return null
        if (!vf.isInLocalFileSystem) return null
        if (!isVelaFileName(vf.name)) return null
        return File(FileUtil.toSystemDependentName(vf.path))
    }
}
