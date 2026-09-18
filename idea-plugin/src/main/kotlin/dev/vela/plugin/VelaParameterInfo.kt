package dev.vela.plugin

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Parameter info: which argument of which call the caret is in.
 *
 * Like the hover, this is a *names* question — "which callable is this call, and
 * what does its signature say" — so it is answered from the file's own characters
 * with [VelaModel], on the platform's thread, with no process started.  Whether
 * the call is legal is not this file's business and is not claimed here: the
 * compiler says that, through `VelaExternalAnnotator`.
 *
 * A call the model cannot resolve shows no popup at all.  The alternative —
 * showing the nearest callable — would be teaching the reader about a function
 * they did not call, which is worse than the popup simply not appearing.
 *
 * The platform's three steps are followed exactly as this version declares them:
 * `findElementForParameterInfo` decides what to show and returns the leaf the hint
 * hangs on, `showParameterInfo` hands the items to the context, and `updateUI`
 * draws one of them.  There is no `getParametersForElement` in this interface —
 * the items travel through the context — so the resolved call is stored as user
 * data on the returned leaf, which is the object the platform passes back.
 */
class VelaParameterInfoHandler : ParameterInfoHandler<PsiElement, ParameterHint> {

    /** Where the resolved call travels between the three calls. */
    private companion object {
        val HINT: Key<ParameterHint> = Key.create("vela.parameterInfo.hint")
    }

    // ------------------------------------------------------------------ finding

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val file = context.file
        val offset = context.offset
        val text = fileText(file) ?: return null
        val call = text.callAt(offset) ?: return null
        val sym = resolveCall(text, call) ?: return null

        // The index the caret is in, from the commas written so far.  A caret just
        // inside the `(` is in argument 0, which is the argument being typed.
        val hint = ParameterHint(sym, text.argumentIndex(call.openParen, offset))
        // Anchored to the `(` of the call being read, not to the caret: with the
        // caret deep in an argument list that is where a reader looks.  This leaf is
        // what `findElementForUpdatingParameterInfo` finds again by identity.
        val anchor = file.findElementAt(call.openParen) ?: file
        anchor.putUserData(HINT, hint)
        return anchor
    }

    /**
     * The platform's second step: the elements to show are the ones resolved in
     * `findElementForParameterInfo`, carried on the returned leaf.
     */
    override fun showParameterInfo(parameterOwner: PsiElement, context: CreateParameterInfoContext) {
        val hint = parameterOwner.getUserData(HINT) ?: return
        context.itemsToShow = arrayOf(hint)
        context.setHighlightedElement(parameterOwner)
    }

    // ------------------------------------------------------------------ updating

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? {
        // The leaf the hint is anchored to, found the way the platform finds leaves,
        // so the object identity matches the one `showParameterInfo` was given.
        val anchor = context.file.findElementAt(context.parameterListStart)
        if (anchor != null && anchor.getUserData(HINT) != null) return anchor

        // The caret has moved and that leaf was rebuilt: the ones that were shown are
        // handed back in `objectsToView`.
        val shown = context.objectsToView
        if (shown != null && shown.isNotEmpty()) {
            (shown[0] as? PsiElement)?.let { if (it.getUserData(HINT) != null) return it }
        }
        return null
    }

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        val hint = parameterOwner.getUserData(HINT) ?: return
        val text = fileText(context.file) ?: return
        val call = text.callAt(context.offset)
        val index = if (call == null) hint.index else text.argumentIndex(call.openParen, context.offset)
        context.setCurrentParameter(index)
    }

    override fun isWhitespaceSensitive(): Boolean = false

    override fun isDumbAware(): Boolean = true

    // ------------------------------------------------------------------ drawing

    override fun updateUI(hint: ParameterHint?, context: ParameterInfoUIContext) {
        val sym = hint?.sym
        if (sym == null) {
            context.setUIComponentEnabled(false)
            return
        }
        val shown = symbolParameters(sym)
        val current = hint.index
        fun widthUpTo(n: Int): Int = shown.take(n).sumOf { it.length + 2 }
        // One string with the parameters as written, then the return type: both are
        // facts from the signature, and drawing them together is what a reader
        // reads the call against.
        val text = shown.joinToString(", ") + returnTypeSuffix(sym)
        val start = if (current in shown.indices) widthUpTo(current) else 0
        val length = if (current in shown.indices) shown[current].length else 0
        // `setupUIComponentPresentation` takes the (start, end) of the part to
        // emphasise.  With no argument under the caret the range is empty and
        // nothing is emphasised — which is the honest drawing of "no argument yet".
        context.setupUIComponentPresentation(
            text,
            start,
            start + length,
            false, // no bold component
            false, // the range is not a subclass reference
            true,  // nothing is greyed out: every parameter belongs to this call
            context.defaultParameterColor,
        )
    }

    /** `-> float` as written in the signature, or nothing when none is recorded. */
    private fun returnTypeSuffix(sym: VelaSymbol): String =
        if (sym.type.isEmpty()) "" else " -> ${sym.type}"

    /*
     * `couldShowInLookup`, `supportsOverloadSwitching` and `tracksParameterIndex`
     * are deprecated in this platform version and are deliberately not overridden:
     * a call means what the compiler decides, the model resolves one name to one
     * declaration, and there is nothing honest to switch between or to infer from a
     * lookup item.  Their inherited answers already say "no".
     */

    // ------------------------------------------------------------------ help

    /**
     * The text the popup reads, which is the *document* rather than the PSI text:
     * the flat token tree has no committed document behind a half-typed call, and
     * the platform's offsets (the caret, `parameterListStart`) index the document.
     */
    private fun fileText(file: PsiFile): CharSequence? {
        val project = file.project
        if (!project.isDefault) {
            PsiDocumentManager.getInstance(project).getDocument(file)?.let {
                return it.immutableCharSequence
            }
        }
        val text = file.text
        return if (text.isEmpty()) null else text
    }
}

/**
 * What the popup is about: the resolved callable and which of its parameters the
 * caret's argument is — `0` for the first, and for a caret just inside the `(`.
 */
class ParameterHint(val sym: VelaSymbol, val index: Int)
