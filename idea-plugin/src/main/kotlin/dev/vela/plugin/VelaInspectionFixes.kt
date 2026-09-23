package dev.vela.plugin

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile

/**
 * The write half of the three inspections.
 *
 * The change itself is not decided here: [VelaInspectionRules] computed the edits
 * when the finding was made, and `tools/harness/src/InspectionProbe.java` applies
 * exactly those edits, writes the result out and asks `vm.exe check` about it — so
 * "this fix repairs the program" is a measurement of the code in this class rather
 * than a claim about it.
 *
 * WHAT THIS CLASS ADDS IS WHAT A HEADLESS RUN CANNOT SEE: a *postponed* fix.
 *
 * A quick fix is a closure over a buffer that was analysed some time ago.  Between
 * the analysis and the click the user may have typed, and an edit expressed as an
 * absolute offset then lands in the middle of a different word.  The platform's own
 * answer is that it re-runs the inspection after every write, so the descriptor
 * goes away with the result that produced it; this class still does not trust the
 * offset, and the check costs one parse:
 *
 *   * if the document still holds the text the finding was computed from, apply it;
 *   * otherwise recompute the rule over the *current* text and apply only a finding
 *     at the very same range and rule;
 *   * otherwise do nothing at all.
 *
 * A fix that does nothing is one the user can see did nothing.  A fix that writes
 * at a stale offset is one they have to debug.
 */
class VelaInspectionFix(private val finding: VelaInspectionFinding) : LocalQuickFix {

    override fun getFamilyName(): String = finding.fixName

    override fun getName(): String = finding.fixName

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement as? PsiFile ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val current = document.text
        val target = if (current == finding.source) {
            finding
        } else {
            VelaInspectionRules.find(finding.rule, current)
                .firstOrNull { it.start == finding.start && it.end == finding.end }
                ?: return
        }
        val applied = VelaInspectionRules.appliedTo(current, listOf(target))
        if (applied.text == current) return
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(applied.text)
        }
    }
}
