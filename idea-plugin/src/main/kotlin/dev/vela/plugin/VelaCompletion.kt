package dev.vela.plugin

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * Completion for names and characters.
 *
 * The plugin's rule is that the compiler is the only thing that decides what a
 * program *means*, so this file does not try to: it offers the language's
 * keywords, the builtins `VelaModel.BUILTINS` lists, and the names the file
 * actually declares — all of which are questions about names, answered by the
 * same lexer-driven model the structure view and the hover use.  No process is
 * started, nothing is indexed: the text of the file in front of the caret is the
 * whole input, which is what makes this fast enough to run on every keystroke.
 *
 * What is deliberately *not* offered is a guess about a receiver's type.  A bare
 * name has no type here (Vela has no type in that position to read), so after
 * `x.` the only receiver the model can honestly resolve is one whose declared
 * type it can see — `self`, a parameter, or the name of a struct — and for
 * anything else the members are simply not offered rather than offered wrongly.
 */
class VelaCompletionContributor : CompletionContributor() {

    init {
        /*
         * Registration is what makes the platform call this contributor at all:
         * CompletionContributor.fillCompletionVariants is only invoked by the
         * contributors that registered a provider, and a contributor whose
         * registration the platform never reaches adds nothing quietly.  The
         * pattern is the whole file (there is no parse tree to match against),
         * so every position in a Vela file gets an offer and the position is
         * decided by reading the text.
         */
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    offer(parameters, result)
                }
            },
        )
    }

    private fun offer(parameters: CompletionParameters, result: CompletionResultSet) {
        // Whatever the platform is completing is a character question, and the
        // offsets it reports index the file as committed — the same text
        // VelaModel reads.  Using the file's text (not the copy with the
        // automatic-popup dummy identifier) keeps the two in step.
        val text = parameters.originalFile.text
        val offset = parameters.offset.coerceIn(0, text.length)
        val seen = HashSet<String>()

        val receiver = text.receiverBeforeDot(offset)
        if (receiver != null) {
            for (m in membersOfReceiver(text, offset, receiver)) {
                if (!seen.add(m.name)) continue
                // After a `.` the receiver is already written, so a method's own
                // `self` parameter is that receiver and not an argument.
                result.addElement(element(m, receiverWritten = true))
            }
            // After a dot only that struct's own members make sense: a keyword
            // cannot follow `self.`, so completing one there would be noise.
            return
        }

        for (keyword in VelaTokenTypes.KEYWORDS) {
            if (!seen.add(keyword)) continue
            result.addElement(
                LookupElementBuilder.create(keyword)
                    .withTypeText("keyword", true)
            )
        }
        for ((name, description) in VelaModel.BUILTINS) {
            // `range` is both a builtin and a keyword; the keyword spelling above
            // is the one the lexer produces, so offering it twice would be two
            // identical entries.
            if (!seen.add(name)) continue
            // The builtin is offered through the same path as a file's own
            // declaration: one symbol, one lookup element, one insert handler, so
            // `concat` inserts `concat(a, b)` for the same reason `fib` inserts
            // `fib(n)`.  The description is the tail text the model already holds.
            val sym = VelaHints.builtinSymbol(name)
            val base = LookupElementBuilder.create(name).withTailText("  $description", true)
            result.addElement(
                if (sym == null) base else base.withInsertHandler(callHandler(sym, receiverWritten = false))
            )
        }

        // The enclosing struct's fields and methods, so `x` inside a method is one
        // keystroke; then the file's own structs, functions and the parameters of
        // the callable the caret is in.
        val struct = VelaModel.enclosingStruct(text, offset)
        if (struct != null) {
            for (m in VelaModel.membersOf(text, struct)) {
                if (!seen.add(m.name)) continue
                result.addElement(element(m, receiverWritten = false))
            }
        }
        for (s in VelaModel.visibleSymbols(text, offset)) {
            if (!seen.add(s.name)) continue
            result.addElement(element(s, receiverWritten = false))
        }
    }

    /** The members `receiver.` should offer, or nothing when the type is unknown. */
    private fun membersOfReceiver(
        text: CharSequence,
        offset: Int,
        receiver: String,
    ): List<VelaSymbol> {
        val struct = structTypeOf(text, offset, receiver) ?: return emptyList()
        return VelaModel.membersOf(text, struct)
    }

    private fun element(sym: VelaSymbol, receiverWritten: Boolean): LookupElement {
        // A struct is a type: inserting it asks for no parentheses.  Everything
        // else the model calls callable gets them, with the parameter names
        // filled in when there is at least one, selected so the first keystroke
        // replaces them — the same shape as `print(` in a language whose
        // completion knows its own declarations.
        if (sym.kind == VelaSymbolKind.STRUCT || !sym.isCallable) {
            return LookupElementBuilder.create(sym.name)
                .withTypeText(sym.detail, true)
        }
        val tail = if (sym.kind == VelaSymbolKind.METHOD) {
            "  ${sym.type}"
        } else {
            "  -> ${sym.type}"
        }
        return LookupElementBuilder.create(sym.name)
            .withTailText(tail, true)
            .withInsertHandler(callHandler(sym, receiverWritten))
    }

    private companion object {
        /**
         * One handler per offered callable, closing over the declaration it belongs
         * to — `fib`'s handler knows it is `fib`.
         *
         * This is the correction that matters.  A single shared handler cannot know
         * which entry the user picked, so it has to re-read the document to find out
         * — and at insert time the document does not yet contain the `(` that would
         * name the call, which is why the previous version could only ever insert an
         * empty `()` at the end of a line.  Carrying the symbol on the element
         * removes the guess entirely: the entry the user chose *is* the answer.
         */
        fun callHandler(sym: VelaSymbol, receiverWritten: Boolean): InsertHandler<LookupElement> =
            InsertHandler { context: InsertionContext, _ ->
                insertCallFill(context, sym, receiverWritten)
            }

        /**
         * Complete the call the user has just been offered: `fib` becomes `fib(n)`
         * with `n` selected, `concat` becomes `concat(a, b)` with the whole list
         * selected, `p.dot` becomes `dot(o)`, and `main` becomes `main()` with the
         * caret inside.
         *
         * What goes between the parentheses — which names, and which of them is
         * selected — is decided by [VelaHints.callTemplate], which is a pure
         * function of the declaration and can be tested without an IDE.  What is
         * left here is only the platform's side of it: writing the characters and
         * moving the caret.  A null answer means "not a call", which is what keeps
         * `Vec2` a type; the caller does not attach this handler to a struct, so
         * that branch is a guard rather than a path.
         */
        fun insertCallFill(context: InsertionContext, sym: VelaSymbol, receiverWritten: Boolean) {
            val document = context.document
            val after = context.tailOffset
            val template = VelaHints.callTemplate(
                sym = sym,
                receiverWritten = receiverWritten,
                // Vela is written `f(x)`, so the gap is not offered; the flag is
                // the parameter's whole reason to exist.
                argumentsSurroundingSpace = false,
            ) ?: return

            document.insertString(after, template.text)
            // `caretStart`/`caretEnd` are offsets *inside* `template.text`, which
            // was written at `after` — so this only has to add that base, and not
            // re-derive where the platform put the name.
            context.editor.caretModel.moveToOffset(after + template.caretStart)
            if (template.caretEnd > template.caretStart) {
                context.editor.selectionModel.setSelection(
                    after + template.caretStart,
                    after + template.caretEnd,
                )
            }
        }
    }
}

/**
 * A call site: the name before the `(`, the struct after a `.` if there is one.
 * Public because [resolveCall], which reads one, is part of the shared name
 * resolution the completion, hover and parameter info all go through.
 */
data class VelaCall(
    val name: String,
    val receiver: String?,
    /** Offset of the `(`, so a signature can be read the same way. */
    val openParen: Int,
)

/** The parameters a `VelaSymbol` declares, in order; empty for a non-callable. */
/** A parameter list that cannot be trusted answers nothing, never a guess. */
internal fun symbolParameters(sym: VelaSymbol): List<String> {
    // The form check matters as much as the split: a symbol whose `detail` does not
    // begin with its own name and `(`, or whose text does not close the list, is not
    // a signature at all, and splitting it would name arguments out of prose.  The
    // hint engine and the parameter-info popup no longer come through here at all --
    // they read the declaration out of the tree (`VelaTargets.declaredParameterNames`)
    // -- so what is left is the completion template, where a wrong name would be
    // inserted into the document rather than merely drawn.
    if (!sym.isCallable) return emptyList()
    if (!sym.detail.startsWith(sym.name + "(")) return emptyList()
    val open = sym.detail.indexOf('(')
    if (open < 0) return emptyList()
    val close = sym.detail.indexOf(')', open + 1)
    if (close < 0) return emptyList()
    val inner = sym.detail.substring(open + 1, close).trim()
    if (inner.isEmpty()) return emptyList()
    val out = ArrayList<String>()
    var depth = 0
    val current = StringBuilder()
    for (c in inner) {
        when {
            c == '[' || c == '(' -> depth++
            c == ']' || c == ')' -> depth--
            c == ',' && depth == 0 -> {
                out.add(parameterName(current.toString()))
                current.setLength(0)
            }
            else -> current.append(c)
        }
    }
    out.add(parameterName(current.toString()))
    // `print(...)` and `emit_str(s: str)`: a variadic slot is not a parameter to
    // offer, so it is dropped rather than inserted as a literal "...".
    return out.filter { it.isNotEmpty() && it != "..." && it != "…" }
}

/** `mut x: Array[int, 4]` -> `x`: the name the user would type beside. */
private fun parameterName(parameter: String): String {
    val beforeColon = parameter.substringBefore(':').trim()
    return beforeColon.removePrefix("mut ").trim()
}

/*
 * Text-level helpers.  A name in a Vela file is where the lexer says it is, and
 * the lexer is `VelaLexer`; these read the same characters the model reads, so
 * completion, hover and parameter info cannot disagree with each other about
 * which name the caret is on.
 */

/**
 * The single identifier immediately before a `.` at `offset`, or null.  The
 * caret may already be inside the member name being typed (`self.d<caret>`), so
 * the identifier after the dot is skipped first.
 */
fun CharSequence.receiverBeforeDot(offset: Int): String? {
    var i = offset.coerceIn(0, length)
    while (i > 0 && isNamePart(this[i - 1])) i--
    if (i == 0 || this[i - 1] != '.') return null
    var j = i - 1
    var k = j
    while (k > 0 && isNamePart(this[k - 1])) k--
    if (k == j) return null
    return substring(k, j)
}

/**
 * The number of the argument the caret sits in, counted from the `(` at
 * `openParen`: commas outside nested brackets, parentheses and strings.  This is
 * what makes `left + right` one argument and `f(g(1, 2), 3)` two.
 */
/**
 * The number of the argument the caret sits in, counted from the `(` at
 * `openParen`: commas outside nested brackets, parentheses and strings.  This is
 * what makes `left + right` one argument and `f(g(1, 2), 3)` two.
 *
 * The scan *includes* the opening parenthesis, so the call's own argument list is
 * one level deep and a separator inside it is a separator of this call rather than
 * of some enclosing expression.  Starting after the `(` instead counts the call
 * itself as nesting, and then no separator at depth 0 exists and every caret reads
 * as argument 0 — which is how this was wrong before the trace showed it.
 *
 * The count also runs to the end of the *line*, not to the caret, because the
 * separator that ends an argument comes *after* the argument.  Reading past the
 * caret is safe: only separators before the caret can be to its left, and the
 * trailing ones end later arguments.
 */
fun CharSequence.argumentIndex(openParen: Int, offset: Int): Int {
    var index = 0
    var depth = 0
    var i = openParen.coerceIn(0, length)
    while (i < length) {
        val c = this[i]
        when {
            // A string is skipped whole, so a comma inside it is not an argument
            // separator and a bracket inside it is not nesting.  `i` is left on the
            // first character *after* the string, and this branch's `continue`
            // advances from there — which is why `endOfString` returns the index past
            // the closing quote rather than the index of it.
            c == '\'' || c == '"' -> {
                i = endOfString(this, i)
                continue
            }
            c == '(' || c == '[' -> depth++
            c == ')' || c == ']' -> {
                // This call's list has closed: nothing beyond it is its argument.
                if (depth == 0) return index
                depth--
            }
            c == ',' && depth == 1 -> {
                // A separator at or after the caret ends the argument the caret is in.
                if (i >= offset) return index
                index++
            }
        }
        i++
    }
    return index
}

/** The index just past the string literal starting at `start`. */
private fun endOfString(text: CharSequence, start: Int): Int {
    val quote = text[start]
    var i = start + 1
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            i += 2
            continue
        }
        // The quote ends it, and so does a newline: the lexer treats an unterminated
        // string as ending at the line break.
        if (c == quote || c == '\n') return i + 1
        i++
    }
    return text.length
}

/**
 * The call whose parentheses contain `offset`, or null.
 *
 * Read by scanning the line *forwards* from its start to `offset`, keeping the
 * string state and the stack of open brackets: the innermost `(` still open when
 * the scan arrives at the caret is the argument list the caret is in, so
 * `print(len(xs))` reads as `len` while the caret is in `len`'s arguments and as
 * `print` once that list has closed.  One forward pass is both simpler and more
 * truthful than glancing backwards, because the things that can fool a backward
 * look — a parenthesis or a comma written inside a string, and which `(` belongs
 * to which `)` — are decided by state that only exists going forwards.
 *
 * The scan deliberately does not understand line continuation: a Vela statement
 * ends at a newline, so the innermost unclosed `(` on the caret's own line *is*
 * the call being written.  Where the text is ambiguous, this returns null rather
 * than a guess.
 *
 * `callAt` is also what keeps a `def` signature from being read as a call: the word
 * before the name is checked, so a declaration's parameter list — `def f(`,
 * `pure def fib(`, `extern c def abs(` — is not a call site and parameter info does
 * not open on one.
 */
fun CharSequence.callAt(offset: Int): VelaCall? {
    val limit = offset.coerceIn(0, length)

    // The start of this line: where a Vela statement begins.
    var lineStart = limit
    while (lineStart > 0 && this[lineStart - 1] != '\n') lineStart--

    // The bracket nesting as written, so a `]` closes a `[` and never an unrelated
    // `(`: `f(a]` is malformed, and it must not be read as having closed a call.
    val brackets = StringBuilder()
    // The `(` positions still open, innermost last.  A stack rather than a single
    // position is what makes `print(len(xs))` read as `len` while the caret is in
    // `len`'s arguments and as `print` once that list has closed — which one the
    // caret is inside changes as the argument list is written.
    val openParens = ArrayList<Int>(4)
    var quote: Char? = null
    var i = lineStart
    while (i < limit) {
        val c = this[i]
        if (quote != null) {
            // Inside a string: `\\` escapes the next character, the quote closes it,
            // and a newline ends it (the lexer's own rule for an unterminated string).
            if (c == '\\' && i + 1 < limit) {
                i += 2
                continue
            }
            if (c == quote) quote = null
            i++
            continue
        }
        when (c) {
            '\'', '"' -> quote = c
            '#' -> {
                // A comment runs to the end of the line, so a `(` written after it is
                // not a call: `print(1) # f(` has no call open at the caret.
                brackets.setLength(0)
                openParens.clear()
                break
            }
            '(', '[' -> {
                brackets.append(c)
                if (c == '(') openParens.add(i)
            }
            ')', ']' -> {
                val want = if (c == ')') '(' else '['
                // Only a matching bracket closes one; a stray one is not allowed to
                // pop an unrelated opening.
                if (brackets.isNotEmpty() && brackets[brackets.length - 1] == want) {
                    brackets.setLength(brackets.length - 1)
                    if (want == '(' && openParens.isNotEmpty()) {
                        openParens.removeAt(openParens.size - 1)
                    }
                }
            }
        }
        i++
    }
    // An unclosed string at the caret means the caret is inside text, not code.
    if (quote != null) return null
    // A caret with no `(` open is not inside a call — `Array[int, 4]` on its own, or
    // an unclosed `[` with no call around it, is an array literal.  An *outer* `(`
    // that is still open does count, which is what makes the caret in the `4` of
    // `f(Array[int, 4])` a position inside `f`'s argument list.
    if (openParens.isEmpty()) return null
    val open = openParens[openParens.size - 1]
    var k = open
    while (k > lineStart && isNamePart(this[k - 1])) k--
    if (k == open) return null
    val name = substring(k, open)
    if (declarationNameBefore(this, k)) return null

    var receiver: String? = null
    if (k > lineStart && this[k - 1] == '.') {
        var s = k - 1
        var r = s
        while (r > lineStart && isNamePart(this[r - 1])) r--
        if (r < s) receiver = substring(r, s)
    }
    return VelaCall(name, receiver, open)
}

/**
 * Whether the name starting at `nameStart` is being *declared* rather than called:
 * look back over spaces and tabs (a newline means the word is on another line, so
 * it cannot be this declaration's `def`) and test the word found there.
 */
private fun declarationNameBefore(text: CharSequence, nameStart: Int): Boolean {
    var i = nameStart
    while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
    var start = i
    while (start > 0 && isNamePart(text[start - 1])) start--
    if (start == i) return false
    return isDeclarationKeyword(text.subSequence(start, i))
}

/** The words that introduce a declaration in Vela. */
private fun isDeclarationKeyword(word: CharSequence): Boolean =
    word == "def" || word == "pure" || word == "extern"

/** A letter, digit or `_` — the characters `VelaLexer` reads a name from. */
fun isNamePart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'