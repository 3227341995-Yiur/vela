package dev.vela.plugin

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.FactoryInlayHintsCollector
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/*
 * Parameter-name inlay hints at Vela call sites: the `name:` a reader needs in
 * order to read `p.dot(p)` as `p.dot(o = p)`, drawn as inlay text beside the
 * argument rather than written into the buffer.
 *
 * This file is split in two on purpose, and the split is the point:
 *
 *   VelaHints   decides *what* to show, from a CharSequence alone.  No platform
 *               type, no PSI, no editor, so the decision can be tested headlessly:
 *               it is a function from text to "these offsets become these labels".
 *   VelaParameterNameInlayHintsProvider
 *               the platform's interface, which asks VelaHints and paints the
 *               answer.  This half decides nothing, which is why nothing in it is
 *               worth testing without a running IDE.
 *
 * The names come from where every other name-level answer in this plugin comes
 * from — [VelaModel] through [resolveCall] — so a hint cannot disagree with the
 * completion list or the parameter-info popup about which declaration a call
 * names.  Nothing here asks the compiler anything: "which parameter is this
 * argument?" is a question about the characters of a signature, not about
 * meaning, and the compiler is still the only thing that says whether the call is
 * legal.
 *
 * The extension point and the interface were read out of the installed platform's
 * own class files, not assumed.  Both platforms on this machine's build classpath
 * declare the same thing:
 *
 *   META-INF/LangExtensionPoints.xml, `intellij.platform.ide.impl.jar` (IDEA
 *   2026.2.1) and the same entry in the PyCharm 2025.3 descriptor tree:
 *
 *       <extensionPoint name="codeInsight.inlayProvider"
 *                       beanClass="com.intellij.codeInsight.hints.InlayHintsProviderExtensionBean">
 *         <with attribute="implementationClass"
 *               implements="com.intellij.codeInsight.hints.InlayHintsProvider"/>
 *       </extensionPoint>
 *
 *   and `com.intellij.codeInsight.hints.InlayHintsProvider<T>` (major 69 in IDEA
 *   `lib/intellij.platform.lang.jar`, major 65 in PyCharm `lib/app.jar`) has the
 *   *same* generic signature in both: abstract `getCollectorFor(PsiFile, Editor,
 *   T, InlayHintsSink)`, `createSettings`, `getName`, `getKey`, `getPreviewText`
 *   and `createConfigurable`, everything else inherited.
 *
 * There is no `<lang.inlayHintsProvider>` extension point in either descriptor
 * tree — registering under that id would have been a silent no-op, which is the
 * failure mode this project has already been bitten by twice.
 *
 * A note on the *other* inlay API.  The platform also declares
 * `codeInsight.parameterNameHints`, whose `InlayParameterHintsProvider` is a
 * bespoke interface for exactly this feature and would need less code here.  It is
 * not used, deliberately: it takes over the whole parameter-hint settings surface
 * (its own blacklist, its own exclude-list integration, its own "which cases"
 * options) and would have to say something about each of those on Vela's behalf.
 * The classic collector API draws the same hints and leaves those questions
 * unasked.
 */

/**
 * The pure half of both new features: text in, decisions out.
 *
 * A holder object rather than top-level functions only so the published entry
 * points have one name to be called by, which is what makes them callable from a
 * headless probe as `VelaHints.parameterHints(text, text.length)`.
 */
object VelaHints {

    /**
     * The parameter names to draw at a call site, and where to draw them.
     *
     *   text    the document text.  It is read as the committed buffer, so the
     *           offsets returned index the same string the platform will paint
     *           into.
     *   offset  how far to look.  Only call sites whose `(` is at or before this
     *           offset are considered — the caret's line of work while typing, the
     *           whole document during a repaint.
     *
     * The result is a list of `(offset, "param: ")` pairs in increasing offset
     * order.  Each offset is the *first character of the argument*, so the label
     * is drawn before the argument, the way IDEA's parameter-name hints read for
     * Java and Kotlin.
     *
     * What is deliberately absent, each for a reason:
     *
     *   * a callee that does not resolve contributes nothing.  No name is invented
     *     from text that merely looks like a function.
     *   * an argument already written as the parameter's own name gets no hint:
     *     for `f(x)` where the parameter is called `x`, the hint would repeat what
     *     the buffer already says.
     *   * an omitted argument — a hole between two commas, or after a trailing
     *     comma — gets no hint, because there is no argument there to label.
     *   * a method's leading receiver parameter (`def dot(self: Vec2, o: Vec2)`) is
     *     not offered: at `p.dot(p)` the receiver is the `p` before the dot, and
     *     the one argument written is `o`.  The compiler's own free functions that
     *     take a first parameter *named* `self` — `write_c(self: str, path: str,
     *     cfile: str)`, called as `write_c(self, path, cfile)` — do write that
     *     argument, so its name is offered like any other.  This is exactly how
     *     [resolveCall] already tells the two apart: only a `.receiver` written
     *     before the name makes the first parameter implicit.
     */
    fun parameterHints(text: CharSequence, offset: Int): List<Pair<Int, String>> {
        val limit = offset.coerceIn(0, text.length)
        val out = ArrayList<Pair<Int, String>>()
        for (open in parenPositions(text, limit)) {
            val close = matchingParen(text, open)
            if (close <= open + 1) continue // `f()` writes no argument to name

            // The same call reader the parameter-info popup uses, asked at the
            // offset just inside the `(`.  Resolving the *call* rather than the raw
            // name is what keeps a `def` signature (`pure def fib(`) out of this:
            // `callAt` already refuses to read a declaration as a call.
            val call = text.callAt(open + 1) ?: continue
            if (call.openParen != open) continue
            // resolveCall still decides *whether* this is a call worth naming: a
            // declaration is not a call, and a name nothing declares gets nothing.
            resolveCall(text, call) ?: continue

            // The names come from the *declaration*, read out of the tree at the
            // callee's own name -- not from a string split of the symbol's text.
            // `declaredParameterNames` answers null when the declaration's parameter
            // list cannot be trusted (it was recovered from, so the text inside the
            // parentheses is not the parameters the parser recorded), and then this
            // call gets no hint at all: a hint is right or it is not drawn.  That
            // null is what removes the reported defect -- `def f(s, s, s) -> int`,
            // which the compiler refuses for its unannotated parameters, used to
            // name the arguments of `f(1, 2, 3)` as `s: s: s: `.
            val calleeAt = open - call.name.length
            val declared = VelaTargets.declaredParameterNames(text, calleeAt)
                ?: (if (VelaTargets.declaresFunction(text, calleeAt)) null
                    else VelaTargets.builtinParameterNames(call.name))
                ?: continue
            if (declared.isEmpty()) continue
            // A receiver is written before the dot, so the method's own first
            // parameter — `self` — has its argument in the receiver, not here.
            val parameters = if (call.receiver != null) declared.drop(1) else declared
            if (parameters.isEmpty()) continue

            val args = argumentRanges(text, open, close)
            for (i in args.indices) {
                if (i >= parameters.size) break // more arguments than the signature names
                // The *argument*, not the space in front of it: `g(v, 3)` labels the
                // `3`, and the range's own leading and trailing whitespace is not
                // part of what the user wrote as the argument.
                val range = trimmedRange(text, args[i])
                if (range.isEmpty()) continue
                val written = text.subSequence(range.first, range.last + 1).toString()
                // The convention: no hint when the argument already *is* the name.
                if (written == parameters[i]) continue
                out.add(range.first to (parameters[i] + ": "))
            }
        }
        return out
    }

    /** [parameterHints] with the caret at the end: every call in the text. */
    fun parameterHints(text: CharSequence): List<Pair<Int, String>> =
        parameterHints(text, text.length)

    /**
     * The `( ... )` the completion list should insert for `sym` while the user is
     * picking it, or null when `sym` is not a call at all.
     *
     * This takes the *resolved symbol* rather than re-reading the document, and
     * that is a correction, not a preference.  An earlier version looked at the
     * text after the caret for a `(`, the way [parameterHints] does at a finished
     * call — but at insert time the `(` does not exist yet: the document still
     * reads `fib`, which is exactly why completion is inserting.  A text-driven
     * version could therefore only ever find a call the user had *already* typed
     * the parenthesis for, and answered null for the ordinary case of picking a
     * name at the end of a line.  The lookup element already knows which
     * declaration it is offering, so the honest input is that declaration.
     *
     * Null for a struct: `Vec2` is a type, and `Vec2()` would be completion
     * inventing a call the language does not have.
     *
     * [receiverWritten] says whether the user is completing after a `.`, which is
     * what makes a method's leading `self` parameter the receiver rather than an
     * argument: `p.dot` offers `o`, not `self, o`.  The caller knows this from the
     * text it completed in; it is passed in so this stays a pure function.
     * [argumentsSurroundingSpace] is the caller's decision about the document's own
     * style — `f( x )` rather than `f(x)` — for the same reason.
     */
    fun callTemplate(
        sym: VelaSymbol,
        receiverWritten: Boolean,
        argumentsSurroundingSpace: Boolean = false,
    ): VelaCallTemplate? {
        if (!sym.isCallable) return null
        // `symbolParameters` drops a variadic `...`, so `print` produces `()`
        // rather than a literal `( ... )`, and it drops a leading `mut `.
        val parameters = parameterNames(sym)
        val names = if (receiverWritten) parameters.drop(1) else parameters
        if (names.isEmpty()) return VelaCallTemplate("()", 1, 1)

        val gap = if (argumentsSurroundingSpace) " " else ""
        val body = names.joinToString(", ", prefix = gap, postfix = gap)
        val first = 1 + gap.length
        return VelaCallTemplate(
            text = "($body)",
            // One argument: select its own name, so the first keystroke replaces
            // exactly that.  Several: select the whole argument list, so the first
            // keystroke replaces all of them — the rule this plugin already had,
            // and the one that stays unambiguous without implying that Tab moves
            // between the names.
            caretStart = if (names.size == 1) first else 1,
            caretEnd = if (names.size == 1) first + names[0].length else 1 + body.length,
        )
    }

    /**
     * The symbol a builtin name denotes — `concat` -> `concat(a: str, b: str) -> str`
     * — so completion can offer the language's own names through exactly the same
     * lookup-element path and table as the file's own declarations.
     *
     * The lookup is [resolveCall] with a synthetic call site, because
     * `VelaModel.BUILTINS` is where the parameter lists of the language's names are
     * written down and there is no second copy of that table here.  A builtin's
     * meaning does not depend on where it is written, so the site this passes in is
     * deliberately empty; `resolveCall` finds the builtin by name and nothing else.
     */
    fun builtinSymbol(name: String): VelaSymbol? = resolveCall("", VelaCall(name, null, 0))

    /** The call reader, exposed so a probe can ask the same question the hints do. */
    fun callAt(text: CharSequence, offset: Int): VelaCall? = text.callAt(offset)

    /** The parameters a resolved symbol declares, in order; empty when none. */
    fun parameterNames(sym: VelaSymbol): List<String> =
        if (sym.isCallable) symbolParameters(sym) else emptyList()

    /**
     * The scanning half on its own, exposed so a headless probe can tell "no call
     * sites were found" apart from "call sites were found and every one of them was
     * rejected".  This is the diagnostic that found the completed-call bug in
     * [parenPositions]' history.  Internal: a diagnostic for the decision, not a
     * second API.
     */
    internal fun callSites(text: CharSequence, offset: Int): List<String> {
        val limit = offset.coerceIn(0, text.length)
        val out = ArrayList<String>()
        for (open in parenPositions(text, limit)) {
            val close = matchingParen(text, open)
            val call = text.callAt(open + 1)
            val args = if (close > open) argumentRanges(text, open, close) else emptyList<IntRange>()
            out.add(
                "(" + open + ".." + close + ") callAt=" + (call?.name ?: "null")
                    + " args=" + args.map { text.subSequence(it.first, it.last + 1) }
            )
        }
        return out
    }
}

/**
 * What the completion handler should insert.
 *
 * [text] is the `( ... )` itself, and [caretStart]/[caretEnd] are offsets
 * *relative to it*, so the handler does not need to know how the platform counted
 * the name it just inserted.
 */
class VelaCallTemplate(
    val text: String,
    val caretStart: Int,
    val caretEnd: Int,
) {
    override fun toString(): String = "$text[$caretStart,$caretEnd)"
}

// ----------------------------------------------------------------- text scanning

/**
 * Every `(` in the text up to [limit], in order — *closed* calls as well as ones
 * the user is still typing inside.
 *
 * This is the correction to a real bug: an earlier version of this function
 * collected only the parentheses that were *still open* at the limit, which meant
 * a call that had already been written contributed nothing at all.  A Vela file
 * being repainted is almost entirely complete calls, so that version found hints
 * for the one call under the caret and for nothing else — the feature would have
 * looked like it worked while typing and vanished on every finished file.  The
 * question "which calls does this text contain" has nothing to do with the caret;
 * [offset] only bounds how far to look.
 *
 * The states tracked here are [CharSequence.callAt]'s own four: a string is
 * skipped whole, `#` starts a comment that runs to the end of the line, and a
 * `(` inside either is not a call.  A bracket *stack* is deliberately not kept any
 * more: matching is [matchingParen]'s job, one `(` at a time, which is what makes
 * a complete call readable rather than only a half-written one.
 */
private fun parenPositions(text: CharSequence, limit: Int): List<Int> {
    val out = ArrayList<Int>(8)
    var quote: Char? = null
    var i = 0
    while (i < limit) {
        val c = text[i]
        if (quote != null) {
            when {
                c == '\\' && i + 1 < limit -> { i += 2; continue }
                c == quote -> quote = null
                c == '\n' -> {
                    // An unterminated string ends at the line break; whatever
                    // follows is code.  This pass has already committed to reading
                    // the line as text, so it stops rather than guess — a call
                    // after an unterminated string is simply not hinted.
                    quote = null
                    i++
                    break
                }
            }
            i++
            continue
        }
        when (c) {
            '\'', '"' -> quote = c
            '#' -> {
                // A comment runs to the end of the line, so a `(` written after it
                // is not a call: `print(1) # f(` has one call, not two.
                while (i < limit && text[i] != '\n') i++
                continue
            }
            '(' -> out.add(i)
        }
        i++
    }
    return out
}

/** The index of the `)` closing the `(` at [openParen], or the text's length. */
private fun matchingParen(text: CharSequence, openParen: Int): Int {
    var depth = 0
    var i = openParen
    while (i < text.length) {
        when (text[i]) {
            '\'', '"' -> i = endOfQuoted(text, i) - 1
            '#' -> while (i < text.length && text[i] != '\n') i++
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return text.length
}

/** The index just past the quoted run starting at [start]. */
private fun endOfQuoted(text: CharSequence, start: Int): Int {
    val closing = text[start]
    var i = start + 1
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            i += 2
            continue
        }
        if (c == closing || c == '\n') return i + 1
        i++
    }
    return text.length
}

/**
 * The argument ranges inside `( [openParen] ... [closeParen] )`, split on the
 * commas that belong to *this* call: one inside a nested `(`/`[` belongs to the
 * nested list, and one inside a string is text.  A range may be empty — a hole
 * between two commas — which the caller reads as "nothing written here" rather
 * than as an argument named by the empty string.
 */
private fun argumentRanges(text: CharSequence, openParen: Int, closeParen: Int): List<IntRange> {
    val out = ArrayList<IntRange>(4)
    var start = openParen + 1
    var depth = 0
    var i = start
    while (i < closeParen) {
        when (text[i]) {
            '\'', '"' -> i = endOfQuoted(text, i) - 1
            '#', '\n' -> i = closeParen // the statement ended; nothing more here
            '(', '[' -> depth++
            ')', ']' -> if (depth > 0) depth--
            ',' -> if (depth == 0) {
                out.add(start until i)
                start = i + 1
            }
        }
        i++
    }
    out.add(start until closeParen)
    return out
}

/**
 * `range` without the whitespace in front of and behind the argument.
 *
 * `f(a, b)` splits into `[a]` and `[ b]`, and the second argument is the `b`.  The
 * KDoc of [VelaHints.parameterHints] promises "the first character of the
 * argument", so the two have to agree: a label painted over the space in front of
 * the argument is a character away from where the argument starts, which is the
 * one place a reader is not looking.  An empty result is an omitted argument, and
 * that is the caller's signal to draw nothing.
 */
private fun trimmedRange(text: CharSequence, range: IntRange): IntRange {
    var start = range.first
    var end = range.last
    while (start <= end && text[start].isWhitespace()) start++
    while (end >= start && text[end].isWhitespace()) end--
    return start..end
}

/*
 * There is deliberately no `receiverBefore`-style text reader here any more.  The
 * completion helper used to find the call by re-reading the document after the
 * caret, and the receiver by looking back for a `.`; both are gone with it.  The
 * declaration the user picked is the answer, so no text needs to be re-read to
 * discover it — which is also what makes the decision testable without a document.
 */

// ----------------------------------------------------------------- the platform

/**
 * The classic inlay-hints provider, which is the only shape of answer the
 * platform's descriptors accept here.
 *
 * Every member below was read off the installed class with reflection
 * (`Class.getDeclaredMethods()` + `Modifier.isAbstract`), because the constant
 * pool alone will not tell you whether Kotlin wants an `override fun getX()` or an
 * `override val x`.  On both platforms here the six abstract members are:
 *
 *     getCollectorFor(PsiFile, Editor, T, InlayHintsSink)   `fun getCollectorFor(...)`
 *     createSettings()                                       `fun createSettings()`
 *     getName()           -> `val name: String`
 *     getKey()            -> `val key: SettingsKey<T>`
 *     getPreviewText()    -> `val previewText: String`
 *     createConfigurable(T) -> `fun createConfigurable(settings: T): ImmediateConfigurable`
 *
 * `group`, `description`, `isLanguageSupported`, `getSettingsLanguage`,
 * `createFile`, `getProperty`, `preparePreview`, `getCaseDescription` and
 * `isVisibleInSettings` are all non-abstract here and are therefore inherited.
 * `isEnabledByDefault` does **not** exist on this interface at all: that switch is
 * the plugin.xml registration's `isEnabledByDefault="true"` attribute (the same
 * attribute the Kotlin plugin's own
 * `KtCompilerPluginGeneratedDeclarationsInlayHintsProvider` registration uses),
 * which is why it is in the plugin.xml fragment rather than here.
 *
 * `NoSettings` because the feature has no setting beyond on/off and the platform
 * already owns that switch; a settings class to hold nothing would be inventing
 * state.  A `SettingsKey` id is still declared, because that id is what the
 * on/off state is stored under and what a user's toggle is read back by — it is
 * not decoration.
 */
class VelaParameterNameInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink,
    ): InlayHintsCollector = VelaParameterNameInlayHintsCollector(file, editor)

    override fun createSettings(): NoSettings = NoSettings()

    override val name: String = "Vela parameter names"

    override val key: SettingsKey<NoSettings> = KEY

    override val previewText: String = "p.dot(o: p)"

    /**
     * The provider's own on/off checkbox is where the switch belongs, and that
     * switch works; a per-case panel would be a second thing to build for a
     * feature with one case.  So the settings page shows the toggle and this
     * returns the one component that toggle needs.
     */
    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        VelaParameterNameConfigurable

    /*
     * `isVisibleInSettings` is a concrete default on this interface and is
     * deliberately not overridden: Kotlin will not accept `override fun
     * isVisibleInSettings()` here (the platform's own spelling is a property), and
     * the default already answers the question the right way — the provider's
     * on/off toggle should appear in the hints settings list, which is the one
     * switch this feature has.
     */

    private companion object {
        val KEY = SettingsKey<NoSettings>("dev.vela.plugin.parameterNames")
    }
}

/**
 * The settings component the contract requires a configurable to be able to
 * build.  The feature has one state — on or off, and the platform's own toggle
 * owns it — so there is nothing to put here; the honest drawing of that is a
 * panel that says so, rather than a checkbox that would duplicate the toggle the
 * settings page already shows above it.
 */
private object VelaParameterNameConfigurable : ImmediateConfigurable {

    override fun createComponent(listener: ChangeListener): JComponent =
        JPanel(BorderLayout()).apply {
            // HTML, because that is how the hints settings page renders the text
            // beside a provider's entry.
            add(
                JLabel(
                    "<html>Nothing to configure: Vela parameter-name hints are either " +
                        "on or off, and the switch in the list is that switch.</html>"
                )
            )
        }

    override val mainCheckboxText: String = "Show parameter names at Vela call sites"
}

/**
 * Paints what [VelaHints] decided: one inlay per argument, at the offset of the
 * argument itself, so the label reads as preceding it — `p.dot(o: p)` and not
 * `p.dot( o:p)`.
 *
 * [FactoryInlayHintsCollector] is the platform's own base class for exactly this
 * job: it takes the editor and hands back an [InlayPresentationFactory] through
 * `getFactory()`.  That matters because the factory is an *interface* here
 * (`com.intellij.codeInsight.hints.InlayPresentationFactory`) with no companion
 * object and no public constructor — the implementation lives in
 * `intellij.platform.lang.impl.jar` as
 * `com.intellij.codeInsight.hints.presentation.PresentationFactory(Editor)` and is
 * not part of the API a plugin is meant to instantiate.  Extending this base class
 * is the supported way to get one; constructing a presentation by hand would mean
 * implementing `InlayPresentation`'s own painting contract, which is the
 * platform's business, not a plugin's.
 */
private class VelaParameterNameInlayHintsCollector(
    private val file: PsiFile,
    editor: Editor,
) : FactoryInlayHintsCollector(editor) {

    /**
     * The document, not `file.text`: the offsets an inlay is registered at index
     * the document, and the document is what the user is looking at.  Falls back
     * to the PSI text when the file has no committed document, which is the state
     * the headless probes are in.
     */
    private fun text(): CharSequence {
        val project = file.project
        if (!project.isDefault) {
            PsiDocumentManager.getInstance(project).getDocument(file)?.let {
                return it.immutableCharSequence
            }
        }
        return file.text
    }

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        val text = text()
        if (text.isEmpty()) return false

        for ((offset, label) in VelaHints.parameterHints(text, text.length)) {
            if (offset >= text.length) continue
            // `factory.text` is the plain label presentation: every hint here is a
            // read-only string, so none of them is given click or hover behaviour.
            sink.addInlineElement(
                offset,
                true, // the label relates to the text that follows it, and moves with it
                factory.text(label),
                false, // not placed at the end of the line: it belongs at this offset
            )
        }
        return true
    }
}

/*
 * Deliberately not used: the `addInlineElement(Int, RootInlayPresentation,
 * HorizontalConstraints)` overload, which would let a hint carry a click handler.
 * The handler would have to navigate to the declaration of the parameter it names,
 * and this model records a parameter by the line it is declared on rather than as
 * a place a caret can be put, so the click would land nowhere in particular.
 * A click that does nothing is worse than no click.
 */
