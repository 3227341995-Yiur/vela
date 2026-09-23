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
        // The declared parameter names come from the tree at the callee's own name;
        // null (they cannot be trusted, or it is a name the language declares that
        // the table does not describe) disables the popup instead of showing guesses.
        val calleeAt = call.openParen - call.name.length
        // A name this file declares is answered by that declaration or by nothing:
        // the language's table is only consulted for a name the file does not write.
        val declared = VelaTargets.declaredParameterNames(text, calleeAt)
            ?: (if (VelaTargets.declaresFunction(text, calleeAt)) null
                else VelaTargets.builtinParameterNames(call.name))
        // A METHOD WITH A RECEIVER DROPS ITS LEADING PARAMETER, for the same reason
        // `VelaInlayHints.parameterHints` does: `p.dot(q)` writes the receiver's argument
        // before the dot, so the parentheses' arguments are the parameters *after* it.  The
        // list and the index have to count the same things, and they did not: the popup
        // drew `self, o -> float` and emphasised `self` for the caret in `q`, which the
        // compiler binds to `o`.  Measured by `PlatformEntry` over the corpus -- every
        // method call with a receiver, and `p.manhattan()` emphasising `self` when the call
        // has no argument at all.
        val shown = if (call.receiver == null) declared
        else declared?.drop(1)
        val hint = ParameterHint(sym, text.argumentIndex(call.openParen, offset), shown, call.openParen)
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
        // THE INDEX IS COUNTED FROM THE CALL'S OWN `(` AND WRITTEN BACK ON THE ITEM.
        //
        // Two defects lived in the one line this replaces, and both are measured by
        // `PlatformEntry`:
        //
        //  * `callAt(context.offset)` reads the caret's *own line*, so on the second line
        //    of a call whose argument list spans lines -- `cap(a,\n  b)` -- it answers
        //    nothing, and the index was then left at whatever it was when the popup
        //    opened (0).  The `(` the popup was opened on travels on the item now, and
        //    counting from it works on every line, because the scan itself does not care
        //    about newlines.
        //  * `updateUI` draws from `hint.index`, which only `findElementForParameterInfo`
        //    ever set -- so the popup emphasised the parameter the caret was in *when it
        //    opened*, for the rest of the call.  `setCurrentParameter` told the platform
        //    the new index and nothing told the item.  The item is the same object the
        //    platform hands back to `updateUI`, so writing it here is what makes the
        //    emphasis follow the caret.
        val call = text.callAt(context.offset)
        val open = call?.openParen ?: hint.openParen
        val index = if (open < 0) hint.index else text.argumentIndex(open, context.offset)
        hint.index = index
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
        // The declaration's own parameter names, or nothing at all.  A name read out
        // of text that is not the parameter list is a name the user never wrote.
        val shown = hint.params
        if (shown == null) {
            context.setUIComponentEnabled(false)
            return
        }
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
 * What the popup is about: the resolved callable, which of its parameters the
 * caret's argument is — `0` for the first, and for a caret just inside the `(` —
 * and the parameter names the *declaration* gives, in order.
 *
 * The names are read once, from the tree, where the file's text is at hand
 * (`findElementForParameterInfo`), and carried here.  They are deliberately not
 * read out of the symbol's own text: that path named the arguments of `f(1, 2, 3)`
 * as `s: s: s: ` for a declaration, `def f(s, s, s) -> int`, whose parameter list
 * the parser never read as parameters because the compiler refuses it.
 *
 * [params] is null when the declaration's parameter list cannot be trusted, and
 * then the popup is disabled rather than drawn with names that are not there.
 *
 * [index] is the argument the caret is in, and it is a `var` because the popup has to
 * keep up with the caret: `updateParameterInfo` recomputes it and writes it back here,
 * and `updateUI` — which the platform calls with this same object — draws from it.
 * [openParen] is the `(` the popup was opened on, which is where the index is counted
 * from when the caret has moved onto another line of a multi-line argument list.
 */
class ParameterHint(
    val sym: VelaSymbol,
    var index: Int,
    val params: List<String>?,
    /** Offset of the `(`, or -1 for a hint that did not come from a call. */
    val openParen: Int = -1,
)
