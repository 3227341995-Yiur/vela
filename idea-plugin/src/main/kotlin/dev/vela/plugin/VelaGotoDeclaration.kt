package dev.vela.plugin

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulator
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.TokenType
import com.intellij.util.ProcessingContext

/**
 * Go to declaration: from the name under the mouse or the caret to the declaration
 * it names.
 *
 * The plugin's rule is that the compiler decides what a program *means*, and that
 * the plugin's own model answers questions about *names*.  "Which declaration is
 * this name?" is the second kind of question, so the whole of it is answered here,
 * on top of `VelaModel` and the shared resolution in `VelaNames.kt` — the same
 * answers the completion offer, the parameter-info popup and the semantic
 * highlighter are built from, so the plugin cannot tell the reader two different
 * things about one word.
 *
 * The part of it that needs no IDE is [VelaDeclarations.declarationRange]: text and
 * an offset in, an offset range out.  Everything else in this file is the
 * platform's side of that answer — turning an offset into the leaf `PsiElement` the
 * platform navigates to, and offering the same answer as a `PsiReference` so that
 * find-usages, rename and "highlight usages in file" work from it.
 *
 * Nothing here guesses.  A name the text does not explain as a declaration resolves
 * to nothing, and a name that *is* a declaration is its own target, so the plugin
 * offers no target at all rather than one equal to the caret.
 */

/**
 * The declaration a name refers to, as offsets into the text it was read from.
 *
 * Pure and stateless: one `CharSequence` and one offset in, one `IntRange` out, the
 * same answer every time.  That is deliberate — the contract is checked headlessly,
 * without an IDE, and the handler and the reference below are only *renderings* of
 * it.
 */
object VelaDeclarations {

    /** A name and what it is at a call site, as read from the text's own shape. */
    private class LocalDeclaration(val range: IntRange, val type: String)

    /**
     * The range of the declaration the name at `offset` refers to, or null.
     *
     * `offset` is any index the name *covers* — the offsets the platform hands a
     * handler are inside the word or one past its end, and both read as that word
     * here.  A position no word touches (whitespace, an operator, a number) is
     * null, and so is an offset past the end of the text: a caret between two names
     * is on neither of them.
     *
     * What resolves, and to what:
     *
     *   * `fib` in `fib(10)` → `fib` in `pure def fib(n: int) -> int`;
     *   * `dot` in `p.dot(p)` → `dot` in `def dot(self: Vec2, o: Vec2)`, when the
     *     receiver's declared type is written down (`mut p: Vec2 = ...`, or `p` is
     *     a parameter — `o: Vec2`) — a receiver whose type the text does not say
     *     resolves to nothing, because Vela writes no type there to read;
     *   * `x` in `self.x` and in `p.x` → the field `x` in the struct's field list;
     *   * a parameter at a use → that parameter's own `name: T`, the nearest one
     *     above the use, so an inner binding shadows an outer one honestly;
     *   * `Vec2` in `Vec2(3.0, 4.0)` and in `o: Vec2` → the `struct Vec2` line;
     *   * a local binding's name → that binding, reached through a member
     *     (`p.dot(p)` reads `p`'s declared type); a *bare* read of a local resolves
     *     to nothing, because a local has no declaration line the model knows.
     *
     * Null for: an undeclared name; a builtin (`print` — the language declares it,
     * not this file); a keyword; a number; whitespace; an offset past the end; a
     * receiver whose type is not written down; a member the resolved struct does
     * not declare; and a name that is *itself* the declaration, which is what keeps
     * the plugin from offering a target equal to the caret.
     */

    fun declarationRange(text: CharSequence, offset: Int): IntRange? {
        val caret = nameRangeAt(text, offset) ?: return null
        val name = text.subSequence(caret.first, caret.last + 1).toString()
        // `caret.last + 1`, not `caret.last`: the four-argument form takes an
        // *exclusive* end (it mirrors `TextRange`), and passing the inclusive one made
        // `caretEnd <= caretStart` true for every one-character name -- so `n`, `s`,
        // `i`, `a`, `b` and `x` had no go-to-declaration target at all, in 0.1.3 and
        // since.  Vela code is full of one-character names.  `GotoOracle` is what
        // found it: three references to `n` in `tests/build/recursion_fib.vel` came
        // back null while `fib` in the same line resolved.
        return declarationRange(text, caret.first, caret.last + 1, name)
    }

    /**
     * The same question, asked by a caller that already knows which name it is
     * about: the platform hands the handler a leaf whose text is the name and whose
     * range is `[caretStart, caretEnd)`.
     *
     * The range matters as well as the name, because "is the answer the name
     * itself?" can only be asked with both — and the answer to that is null, not a
     * target.
     *
     * (`caretEnd` is *exclusive*, matching `TextRange` and `IntRange.last + 1`, and
     * not `IntRange.last`: the platform's ranges are half-open, and one character of
     * slack here would make the answer to `def dot` `do`.)
     */
    fun declarationRange(text: CharSequence, caretStart: Int, caretEnd: Int, name: String): IntRange? {
        val target = declarationTarget(text, caretStart, caretEnd, name) ?: return null
        return target.start until target.end
    }

    /**
     * The same answer with the *kind* attached: what the name is, not only where it is.
     *
     * [declarationRange] is this range, and it is the only caller that wants nothing
     * else; find-usages wants the kind, because the results view names it (`function`,
     * `field`, `variable`) and a reader has to be able to tell two same-named things
     * apart.  Both come from here so that the range and the kind cannot be two answers
     * about one word.
     *
     * The checks, and why each is asked:
     *
     *   * the name's shape (name characters, no leading digit) and the range's shape
     *     (inside the text, not empty) — what a leaf can be;
     *   * `isNamePart(text[caretStart])` — the caret is *on* the name, not beside it;
     *   * a keyword and a builtin are names the *language* declares: there is no line in
     *     this file to go to, and inventing one would be a guess;
     *   * the resolution itself, `VelaTargets.declarationFor` — the tree path that
     *     `GotoOracle` measured (24,683 references judged, 0 wrong) — then the two
     *     fallbacks in [fallbackTarget] for the shapes it does not answer;
     *   * the answer spells this name, and is not the name itself: a declaration is not
     *     a reference to itself, and a target equal to the caret is what makes a rename
     *     offer to rename the caret's own word.
     */
    fun declarationTarget(text: CharSequence, caretStart: Int, caretEnd: Int, name: String): VelaTarget? {
        if (name.isEmpty() || !name.all { isNamePart(it) } || name.first().isDigit()) return null
        if (caretStart < 0 || caretEnd > text.length || caretEnd <= caretStart) return null
        if (!isNamePart(text[caretStart])) return null
        if (VelaTokenTypes.KEYWORDS.contains(name)) return null
        if (VelaModel.BUILTINS.any { it.name == name }) return null

        // A DECLARATION'S OWN NAME IS NOT A REFERENCE, AND THIS IS THE CASE THAT MADE IT
        // NECESSARY: `x: int = 2` inside a block is the inner binding's name, and the tree
        // path answered the *enclosing* binding for it — case 3 of
        // `VelaTargets.declarationFor` looks for the nearest declaration that *precedes*
        // the offset, and an inner declaration's own name is preceded by the outer one.
        // Measured on `tests/safety/cases/scope_shadow_across_blocks_ok.vel`: Ctrl+Click
        // on the inner `x` jumped to the outer `x`, and a rename of the outer `x` rewrote
        // the inner declaration.  The rule is the plugin's own token-shape rule
        // (`isDeclarationNameAt`, the same one find-usages asks before offering a search),
        // so the two cannot disagree about which word is a declaration; the alternative —
        // a reference from a declaration to a different declaration — is not a shape Vela
        // has.
        if (isDeclarationNameAt(text, caretStart, caretEnd)) return null

        // The tree answers this, and it is the same answer for every caller:
        // `VelaTargets` resolves the name to a *declaration node* -- a field, a
        // local, a loop variable, a parameter, a method, a function, a struct -- and
        // returns the range of the declaration's own name.  The old path here read
        // the flat model and could only ever name a receiver-typed member or a
        // file-level symbol; `GotoOracle` measured what that cost (every reference in
        // `tests/build/recursion_fib.vel` -- a function, three parameters, a local and
        // a loop variable -- came back with no target at all).
        val target = VelaTargets.declarationFor(text, caretStart)
            ?: fallbackTarget(text, caretStart, name)
            ?: return null
        if (target.name != name) return null
        // The name *is* the declaration: the platform would offer the caret its own
        // element, and the honest answer is that there is nothing to go to.
        if (target.start == caretStart && target.end == caretEnd) return null
        return target
    }

    /**
     * The range of the name that `offset` is on, or null when no name touches it.
     *
     * One past the end of a name still belongs to it — that is where the platform
     * puts a caret after `fib<caret>` has been typed — and nowhere else: a position
     * that only *touches* a word, like the space after it, is on nothing.
     */
    fun nameRangeAt(text: CharSequence, offset: Int): IntRange? {
        val end = offset.coerceIn(0, text.length)
        val inside = end < text.length && isNamePart(text[end])
        if (!inside && (end == 0 || !isNamePart(text[end - 1]))) return null
        var start = if (inside) end else end - 1
        var stop = if (inside) end + 1 else end
        while (start > 0 && isNamePart(text[start - 1])) start--
        while (stop < text.length && isNamePart(text[stop])) stop++
        // A number is not a name: `3.0` and `10` are values, and there is nothing
        // for a reference to point at.
        if (text[start].isDigit()) return null
        return start until stop
    }

    /**
     * The two call shapes the tree path does not answer, and they are one question each:
     * the name is written with parentheses after it, and the file says which declaration
     * that is.  Both were found by measurement, not by reading:
     *
     * ## `P(1)` — a struct's constructor call
     *
     * `VelaTargets` resolves a call through `fileFunction` alone, and a struct is not a
     * function, so the whole of `P(1)` resolved to nothing.  The model path this file
     * used to run had it (`if (afterChar == '(') ... return structNamed(text, name)`),
     * and losing it was invisible until something measured the *set* of references
     * rather than the targets of the references that already existed.  `RenameOracle`
     * (tools\harness\src), over the corpus, before this fallback existed:
     *
     *     5 struct declarations had a constructor call that `vm.exe check` requires to
     *     be renamed, the reference table offered nothing there, and the rename built
     *     from that table left the call behind — a program the compiler refuses
     *     (`vela: type error: undeclared name`).  And in the *other* direction the table
     *     claimed 6 occurrences the compiler never asks for, which is silence and not a
     *     denial: see `RenameOracle`'s header on `b: P = a`.
     *
     * ## `o.inner.bump()` — a method call on a receiver the text does not type
     *
     * `VelaTargets.structFor` reads the receiver's type from a parameter or a binding
     * (`o: Outer`), from `self`, or from a capitalised name; `o.inner` is none of those,
     * so the call resolved to nothing even though the compiler binds it.  `RenameOracle`
     * found exactly one such call in the corpus — `tests/safety/cases/
     * hole_parallel_impure_method_nested_receiver.vel:25` — and the rename of `bump`
     * left it behind, which the compiler reports as `Inner has no method 'bump'`.
     *
     * The rule used here is the one this file already states for a receiver-less method
     * call: *exactly one* declaration of that name in the file, or nothing.  With one
     * method named `bump` anywhere in the file, a `...bump()` call can only be to it, so
     * the answer is the file's own text rather than a coin toss; with two, the target
     * is ambiguous and this returns null, which is the honest answer.
     */
    private fun fallbackTarget(text: CharSequence, offset: Int, name: String): VelaTarget? {
        val after = offset + name.length
        if (after >= text.length || text[after] != '(') return null
        val all = VelaModel.symbols(text)

        all.firstOrNull { it.name == name && it.kind == VelaSymbolKind.STRUCT }?.let { struct ->
            offsetOfNameOnLine(text, struct.line, name)?.let {
                return VelaTarget(VelaTargetKind.STRUCT, name, it.first, it.last + 1)
            }
        }
        // A *member* call only: `bump()` with nothing before the dot is not a call the
        // compiler accepts (see `VelaTargets`' note on a bare method call), so this
        // requires the receiver's dot to be there.
        if (offset == 0 || text[offset - 1] != '.') return null
        val methods = all.filter { it.name == name && it.kind == VelaSymbolKind.METHOD }
        if (methods.size != 1) return null
        val method = methods[0]
        offsetOfNameOnLine(text, method.line, name)?.let {
            return VelaTarget(VelaTargetKind.METHOD, name, it.first, it.last + 1)
        }
        return null
    }

    // -------------------------------------------------------------- resolution

    /**
     * The declaration the name starting at `offset` refers to, as the model
     * describes it.
     *
     * The cases are ordered by what the text around the name *says*, and the first
     * match wins because each is a stricter reading than the ones below it.
     */
    private fun resolve(text: CharSequence, offset: Int, name: String): VelaSymbol? {
        val after = offset + name.length
        val afterChar = if (after < text.length) text[after] else null

        // `self.x`, `p.x`, `o.dot` — a member is a member of the struct that the
        // receiver's *declared* type names.
        if (offset > 0 && text[offset - 1] == '.') {
            val receiver = receiverBefore(text, offset) ?: return null
            val owner = structOfReceiver(text, offset, receiver) ?: return null
            return VelaModel.membersOf(text, owner).firstOrNull { it.name == name }
        }

        if (afterChar == '.') {
            // `p.field` — the receiver is on the far side of the dot, so a name
            // written in a member position is a struct's name only when the text
            // has nothing else for it.
            return structNamed(text, name)
        }

        if (afterChar == '(') {
            // A call.  This is the shared call resolution, so `fib(10)` and
            // `p.dot(p)` mean here what they mean in the parameter-info popup.
            text.callAt(after)?.let { call ->
                if (call.name == name) {
                    resolveCall(text, call)?.let { called ->
                        if (called.name == name && called.line >= 1) return called
                    }
                }
            }
            // `Vec2(3.0, 4.0)` is not a call to a function: it names the type.
            return structNamed(text, name)
        }

        // A bare name, and the innermost declaration of it that is *visible*.  Asked
        // before the file-wide lookups, so `n` inside `fib` means `fib`'s `n` and
        // not another function's.
        return visibleDeclaration(text, offset, name)
    }

    /** The identifier immediately before the `.` at `offset`, or null. */
    private fun receiverBefore(text: CharSequence, offset: Int): String? {
        var i = offset
        while (i > 0 && (text[i - 1] == ' ' || text[i - 1] == '\t')) i--
        if (i == 0 || text[i - 1] != '.') return null
        var start = i - 1
        while (start > 0 && isNamePart(text[start - 1])) start--
        if (start == i - 1) return null
        return text.subSequence(start, i - 1).toString()
    }

    /**
     * The struct a receiver expression names, or null when the text does not say.
     *
     * `self`, a struct's own name, a parameter (`o: Vec2`) and a local binding
     * (`mut p: Vec2`) are the receivers whose type is written down beside them.
     * Anything else — the result of a call, an indexed element, an array literal —
     * returns null, and the caller then offers nothing rather than something
     * invented.  `VelaNames.structTypeOf` answers the first three; the local binding
     * is read here because the model declares fields and parameters and no locals,
     * and `p.dot(p)` is the receiver a reader clicks most often.
     */
    private fun structOfReceiver(text: CharSequence, offset: Int, receiver: String): VelaSymbol? {
        if (receiver == "self") return VelaModel.enclosingStruct(text, offset)
        localDeclaration(text, offset, receiver)?.let { local ->
            if (local.type.isNotEmpty()) return structNamed(text, local.type)
        }
        return structTypeOf(text, offset, receiver)
    }

    /**
     * A bare name read as a declaration: the nearest one above `offset` that is in
     * scope, then the file's own declarations.
     *
     * A local binding wins because it is the innermost thing that could mean the
     * name.  Then a parameter or a field, which is what `VelaModel.visibleSymbols`
     * answers.  Then the file's functions: a function is a name the whole file
     * shares, so `fib` written in any body means it.  A *method* is reached this way
     * only when the file declares exactly one declaration with that name — with two,
     * the text does not say which struct's method is meant, and a coin toss is not
     * an answer.
     */
    private fun visibleDeclaration(text: CharSequence, offset: Int, name: String): VelaSymbol? {
        localDeclaration(text, offset, name)?.let { return localSymbol(text, it, name) }

        VelaModel.visibleSymbols(text, offset).firstOrNull { it.name == name }?.let { return it }

        val all = VelaModel.symbols(text)
        val callable = all.firstOrNull { it.name == name && it.kind == VelaSymbolKind.FUNCTION }
        if (callable != null) return callable

        val named = all.filter { it.name == name }
        if (named.size == 1 && named[0].kind == VelaSymbolKind.STRUCT) return named[0]

        // A method named with no receiver inside the struct that declares it — the
        // `dot` of a `return dot(self, o)` — is that struct's method, and only that.
        val struct = VelaModel.enclosingStruct(text, offset)
        if (struct != null) {
            VelaModel.membersOf(text, struct).firstOrNull { it.name == name }?.let { member ->
                if (member.kind == VelaSymbolKind.METHOD) return member
            }
        }
        return null
    }

    /**
     * The nearest binding of `name` above `offset` — `mut p: Vec2 = ...`,
     * `p: Vec2 = ...`, `for p in range(...)`.
     *
     * Read from the tokens rather than from the model, which declares fields and
     * parameters and no locals at all.  The nearest one wins, so a rebinding in an
     * inner block shadows the outer name, which is what the reader sees.  A binding
     * with no type written down (`mut n = 0`) is returned with an empty type rather
     * than dropped: it still shadows, and answering with the outer `n` would be
     * wrong — it names no struct, so a member reached through it resolves to
     * nothing, which is honest.
     */
    private fun localDeclaration(text: CharSequence, offset: Int, name: String): LocalDeclaration? {
        val tokens = significantTokens(text)
        var index = tokens.size - 1
        while (index >= 0 && tokens[index].start >= offset) index--
        while (index >= 0) {
            val token = tokens[index]
            if (token.isName && token.text == name && !isDeclarationName(tokens, index)) {
                return LocalDeclaration(token.start until token.end, localType(text, tokens, index))
            }
            index--
        }
        return null
    }

    /**
     * The model's own answer for a local, so a local and the model agree about the
     * line the name is declared on.
     *
     * `symbols()` never returns a local, so this is a field or a parameter the model
     * also declares (an outer one, or one this pass has just walked past) and its
     * line is the line of *that* declaration — which is the line this name is
     * actually on only when the two are the same name on the same line.  Asking the
     * model for the symbol at this line keeps the line right in both cases.
     */
    private fun localSymbol(text: CharSequence, local: LocalDeclaration, name: String): VelaSymbol {
        val line = VelaModel.lineOf(text, local.range.first)
        val declared = VelaModel.symbols(text).firstOrNull { it.name == name && it.line == line }
        return declared
            ?: VelaSymbol(VelaSymbolKind.PARAMETER, name, name, local.type, line, -1)
    }

    /** The type written after a binding's name (`p: Vec2` → `Vec2`), or empty. */
    private fun localType(text: CharSequence, tokens: List<VelaToken>, nameIndex: Int): String {
        var j = nameIndex + 1
        if (j >= tokens.size || tokens[j].text != ":") return ""
        j++
        if (j >= tokens.size || !tokens[j].isName) return ""
        var type = tokens[j].text
        // `Array[int, 4]` is written as a whole, and only its first word is a name.
        if (type == "Array" && j + 1 < tokens.size && tokens[j + 1].text == "[") {
            val open = j + 1
            var depth = 0
            var k = open
            while (k < tokens.size) {
                if (tokens[k].text == "[") depth++
                if (tokens[k].text == "]") {
                    depth--
                    if (depth == 0) break
                }
                k++
            }
            if (k < tokens.size) type = text.subSequence(tokens[open].start, tokens[k].end).toString()
        }
        return type
    }

    /**
     * Is the token at `index` the *name of* a declaration rather than a use?
     *
     * `struct S`, `def f`, `for i` and `name: T` — the shapes a declaration has in a
     * flat stream of tokens, read from the neighbouring tokens, which is all a flat
     * tree offers without walking it.
     *
     * The `for` case is here because find-usages needs it to be: a loop variable is a
     * declaration with a name and usually several uses, and without this shape
     * `VelaUsageSearch.canSearchAt` answered *no* for the variable's own name — so the
     * platform refused to search for the one kind of name that has nothing *but* uses
     * in a loop body.
     */
    private fun isDeclarationName(tokens: List<VelaToken>, index: Int): Boolean {
        if (index > 0) {
            val before = tokens[index - 1].text
            if (before == "struct" || before == "def") return true
            if (before == "for") return true
        }
        return index + 1 < tokens.size && tokens[index + 1].text == ":"
    }

    /**
     * Is the identifier at `[start, end)` the *name of* a declaration?
     *
     * The same rule as [isDeclarationName], asked by a caller that has an offset rather
     * than a token index — the platform hands find-usages an element, and the harness
     * that measures find-usages has a range.  Used by `VelaUsageSearch.canSearchAt`
     * (a declaration *can* have its usages looked for even though it is not a reference
     * to anything else) and by nothing else.
     */
    fun isDeclarationNameAt(text: CharSequence, start: Int, end: Int): Boolean {
        if (start < 0 || end > text.length || end <= start) return false
        val tokens = significantTokens(text)
        val index = tokens.indexOfFirst { it.start == start && it.end == end }
        if (index < 0) return false
        return isDeclarationName(tokens, index)
    }

    // ------------------------------------------------- the declaration's offset

    /**
     * The range of the declaration's name, or null when the declaration cannot be
     * found in its own text.
     *
     * The model gives a line; the rest is reading that line, because that is how the
     * platform's flat tree turns "line 12 of the model" into a `PsiElement` — a
     * `PsiFile.findElementAt(the declaration's offset)` walks the leaves.  The name
     * is looked for on that line first, then — for the one case where the line is
     * off, a declaration whose name is written on another line than its `def` —
     * within two lines of it.  If the name is nowhere near, this returns null: an
     * offset chosen by proximity would be the guess this whole file exists not to
     * make.
     */
    private fun declarationOf(text: CharSequence, symbol: VelaSymbol, name: String): IntRange? {
        if (symbol.line < 1) return null
        val line = symbol.line
        offsetOfNameOnLine(text, line, name)?.let { return it }
        var delta = 1
        while (delta <= 2) {
            offsetOfNameOnLine(text, line - delta, name)?.let { return it }
            offsetOfNameOnLine(text, line + delta, name)?.let { return it }
            delta++
        }
        return null
    }

    /**
     * The first name on 1-based `line` whose text is exactly `name`, as an offset
     * range, or null when that line does not carry it.
     *
     * *First*, not any: on `def dot(self: Vec2, o: Vec2) -> float` the method's own
     * name is found at `dot`, and the receiver parameter `self` is found at `self:`
     * on the same line — which is exactly the distinction that has to be right when
     * two declarations share a line.  The scan stops at the end of the line, so a
     * name written further down is never mistaken for this declaration's.
     */
    private fun offsetOfNameOnLine(text: CharSequence, line: Int, name: String): IntRange? {
        if (line < 1) return null
        var start = 0
        var current = 1
        while (current < line) {
            val next = indexOfNewline(text, start)
            if (next < 0) return null
            start = next + 1
            current++
        }
        if (start >= text.length) return null
        val newline = indexOfNewline(text, start)
        val end = if (newline < 0) text.length else newline
        for (token in significantTokens(text)) {
            if (token.start < start) continue
            if (token.start >= end) return null
            if (token.isName && token.text == name) return token.start until token.end
        }
        return null
    }

    private fun indexOfNewline(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '\n') return i
            i++
        }
        return -1
    }

    // ------------------------------------------------------------------ tokens

    /**
     * A token as this file needs it: its text, its range, and whether it is a name.
     *
     * Read with the same `VelaLexer` every other part of the plugin reads the file
     * with, so "is this a name?" cannot differ between features.  Kept private and
     * rebuilt per question: the largest Vela file is 10k lines, and this runs on a
     * click or on a rename, not on every keystroke.
     */
    private class VelaToken(
        val text: String,
        val start: Int,
        val end: Int,
        val isName: Boolean,
    )

    private fun significantTokens(text: CharSequence): List<VelaToken> {
        val lexer = VelaLexer()
        lexer.start(text)
        val out = ArrayList<VelaToken>(128)
        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            if (type != null && type != TokenType.WHITE_SPACE && type != VelaTokenTypes.COMMENT) {
                out.add(
                    VelaToken(
                        text.subSequence(start, end).toString(),
                        start,
                        end,
                        type == VelaTokenTypes.IDENTIFIER,
                    )
                )
            }
            lexer.advance()
        }
        return out
    }
}

/**
 * Ctrl+Click and Ctrl+B over a name.
 *
 * The platform hands this the token under the mouse and the offset inside it, and
 * the offset is used in preference to the element: it is the caret's position, and
 * it is the same offset [VelaDeclarations.declarationRange] was checked with.  The
 * element only says which file to read.
 *
 * The answer is an array of `PsiElement`, but never a list of candidates: one
 * target or none.  The target is the leaf at the declaration name's offset — Vela's
 * PSI is flat, one leaf per token, so the leaf *is* the declaration as far as the
 * platform's navigation is concerned — and it is checked before it is returned, so a
 * leaf that is not the declaration's own name is dropped rather than offered.
 */
class VelaGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val file = element.containingFile ?: return null
        val leaf = leafAt(file, offset) ?: return null
        val name = leaf.text
        if (name.isEmpty() || !name.all { isNamePart(it) } || name.first().isDigit()) return null
        if (leaf.textRange.startOffset != offset) return null

        val text = file.text
        val target = VelaDeclarations.declarationRange(
            text,
            leaf.textRange.startOffset,
            leaf.textRange.endOffset,
            name,
        ) ?: return null
        val at = target.first
        if (at < 0 || at >= text.length) return null
        val found = leafAt(file, at) ?: return null
        if (found === leaf || found.textRange == leaf.textRange) return null
        if (found.text != name || found.textRange.startOffset != at) return null
        return arrayOf(found)
    }

    /**
     * The leaf token at `offset`.
     *
     * `PsiFile.findElementAt` is the platform walking the tree for us — the flat
     * tree has one leaf per token, so this is the token.  The offset is clamped to
     * the document first: the platform's offsets and the document's text length
     * differ by one at the very end of a file, and a `findElementAt` past the end
     * would throw rather than answer "no token".
     */
    private fun leafAt(file: PsiFile, offset: Int): PsiElement? {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null
        if (document.textLength == 0) return null
        val safe = offset.coerceIn(0, document.textLength - 1)
        return file.findElementAt(safe)
    }
}

/**
 * A reference on every identifier token in a Vela file, so that find-usages,
 * rename and "highlight usages in file" work from the same resolution the
 * Ctrl+Click handler uses.
 *
 * This is the handler's answer in the platform's own words: `resolve()` navigates,
 * `getRangeInElement()` is the name, and a name the text does not explain resolves
 * to null — which the platform then draws as *unresolved*, which is honest, and is
 * also how a reader finds a typo.  A reference that resolved to something invented
 * would make every rename offer to change the wrong word.
 */
class VelaReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // Identifier *tokens*, and nothing else: Vela's tree is one leaf per token
        // and `psiElement(IElementType)` is the platform's way to say so.  The
        // provider decides the rest, so a word that does not resolve contributes no
        // reference at all rather than an unresolved one in a wrong place.
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(VelaTokenTypes.IDENTIFIER),
            VelaReferenceProvider,
        )
    }
}

/**
 * One object, because a provider holds no state: the answer is a function of the
 * element's text and the file it belongs to.
 */
object VelaReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        if (!element.isValid) return EMPTY
        val file = element.containingFile ?: return EMPTY
        val leaf = element.textRange
        val name = element.text
        // THE DECISION IS `VelaReferences.referenceTargetAt`, AND ONLY IT.  Everything
        // below this line is the platform's side of that answer: an offset becomes the
        // leaf the platform navigates to, and a leaf that is not the name — or is the
        // element itself — is dropped rather than offered.  The decision is one
        // function because find-usages and rename read nothing else: the search walks
        // the identifier leaves and asks each one where it resolves, and `RenameOracle`
        // (tools\harness\src) walks the same leaves and asks the same function, so what
        // the harness measures is what this provider ships.  The name, the range and
        // the "is it itself" tests used to be written here as well; they are now inside
        // the one function, and the tests kept here are the platform's own.
        val target = VelaReferences.referenceTargetAt(
            file.text,
            leaf.startOffset,
            leaf.endOffset,
        ) ?: return EMPTY
        val resolved = file.findElementAt(target) ?: return EMPTY
        if (resolved === element || resolved.textRange == leaf) return EMPTY
        if (resolved.text != name) return EMPTY
        return arrayOf(VelaReference(element, name, resolved))
    }

    private val EMPTY = PsiReference.EMPTY_ARRAY
}

/**
 * A name and the declaration it names.
 *
 * The range is the leaf's leading name segment — the token's own text in every file
 * this plugin opens, since whitespace is a token of its own — and it is asked of
 * [VelaLeafManipulator] rather than computed a second time, so the reference and the
 * rename cannot disagree about which characters are the name.
 *
 * `handleElementRename` is the member that has to *write*: it rewrites those
 * characters through the document, inside a command, which is what makes the rename
 * one undoable step and what makes the platform re-lex the leaf afterwards instead
 * of holding a stale one.  The new name is written only when the write can actually
 * happen ([VelaLeafManipulator.canWrite]); a rename that silently does nothing would
 * be worse than no rename at all, which is why the contributor registers no
 * reference for leaves this cannot be done for.
 */
private class VelaReference(
    element: PsiElement,
    private val referencedName: String,
    private val target: PsiElement,
) : PsiReferenceBase<PsiElement>(element, true) {

    init {
        rangeInElement = VelaLeafManipulator.getRangeInElement(element)
    }

    override fun resolve(): PsiElement? {
        if (!target.isValid) return null
        // A stale target — the declaration has been renamed or deleted since this
        // reference was built — is no target at all, and the platform re-asks.
        if (target.text != referencedName) return null
        return target
    }

    override fun getRangeInElement(): TextRange =
        VelaLeafManipulator.getRangeInElement(element)

    override fun handleElementRename(newElementName: String): PsiElement {
        val leaf = element
        if (!VelaLeafManipulator.canWrite(leaf, newElementName)) return leaf
        val document = PsiDocumentManager.getInstance(leaf.project).getDocument(leaf.containingFile)
            ?: return leaf
        val start = leaf.textRange.startOffset
        val end = leaf.textRange.endOffset
        if (start < 0 || end > document.textLength || start > end) return leaf
        CommandProcessor.getInstance().executeCommand(
            leaf.project,
            { document.replaceString(start, end, newElementName) },
            "Rename Vela declaration",
            null,
            document,
        )
        PsiDocumentManager.getInstance(leaf.project).commitDocument(document)
        return leaf
    }
}

/**
 * How a name inside a leaf is written, for both the reference and the platform's own
 * rename machinery (`ElementManipulators`), which asks any manipulator it has for a
 * leaf this shape.
 *
 * The range is the leading name part of the token — the whole token for an
 * identifier — and the write goes through the document inside a command, so the
 * edit is one undo step and the tree is re-parsed from the buffer rather than
 * mutated behind the platform's back.  The two `canWrite` conditions are the ones a
 * rename must not proceed without: the element has to be in a live, writable file,
 * and the name it is being given has to be a name Vela can lex as one token.
 */
object VelaLeafManipulator : ElementManipulator<PsiElement> {

    override fun handleContentChange(
        element: PsiElement,
        range: TextRange,
        newContent: String,
    ): PsiElement {
        if (!element.isValid || !element.isWritable) return element
        val document = PsiDocumentManager.getInstance(element.project).getDocument(element.containingFile)
            ?: return element
        val start = element.textRange.startOffset + range.startOffset
        val end = element.textRange.startOffset + range.endOffset
        if (start < 0 || end > document.textLength || start > end) return element
        CommandProcessor.getInstance().executeCommand(
            element.project,
            { document.replaceString(start, end, newContent) },
            "Edit Vela name",
            null,
            document,
        )
        PsiDocumentManager.getInstance(element.project).commitDocument(document)
        return element
    }

    override fun handleContentChange(element: PsiElement, newContent: String): PsiElement =
        handleContentChange(element, getRangeInElement(element), newContent)

    /** The leading name characters of the element: the whole token for a name. */
    override fun getRangeInElement(element: PsiElement): TextRange {
        val text = element.text
        var i = 0
        while (i < text.length && isNamePart(text[i])) i++
        return if (i == 0) TextRange(0, text.length) else TextRange(0, i)
    }

    /** Can this element be renamed to `newName` at all? */
    fun canWrite(element: PsiElement, newName: String): Boolean {
        if (!element.isValid || !element.isWritable) return false
        // The shape of the name is `VelaReferences.isWritableName`'s question, and only
        // that function answers it, so the platform's rename dialog, the reference's
        // `handleElementRename` and the harness that measures a rename cannot disagree
        // about which names may be written.
        if (!VelaReferences.isWritableName(newName)) return false
        val range = getRangeInElement(element)
        return range.startOffset >= 0 && range.endOffset <= element.textLength
    }
}

/**
 * The IDE-free core of find-usages and rename: which name occurrences are references
 * to which declaration, and which names a rename is allowed to write.
 *
 * ## Why this is one function and not a rule stated twice
 *
 * The platform's find-usages search, "highlight usages in file" and the rename
 * refactoring all read the same answer: `PsiReference.resolve()` on every identifier
 * leaf. [VelaReferenceProvider] turns one leaf into one such reference;
 * `VelaFindUsagesProvider` decides whether a leaf can be searched at all.  Neither can
 * be asked without an IDE, so the decision they share is stated here, per leaf, over
 * the text:
 *
 *   * the candidate set is *every* `IDENTIFIER` leaf — which is exactly the pattern
 *     `VelaReferenceContributor` registers the provider against, and exactly what the
 *     words scanner behind Find Usages indexes (`getWordsScanner` in
 *     `VelaFindUsages.kt`).  No separate list of "reference-shaped things" exists;
 *   * the decision is [referenceTargetAt]: one leaf in, the offset of the declaration
 *     it names out, or null for a leaf that names nothing — a keyword, a builtin (the
 *     language declares it, no line of this file does), a number, a name the file does
 *     not declare, or the declaration itself.
 *
 * ## What measures it
 *
 * `tools/harness/src/RenameOracle.java` walks a file's identifier leaves, asks this
 * function about each one, and holds the resulting table to the compiler's own binding
 * set — measured by renaming one declaration and repairing the uses the compiler
 * complains about, one at a time, until the file compiles again.  That protocol is
 * `GotoOracle`'s (this plugin's go-to-declaration harness), reused rather than
 * reinvented: the set of uses the compiler names along the way is the *authority*, and
 * it verifies itself by the file compiling again at the end.  A reference this table
 * offers that the compiler does not bind is a false positive — the class of bug that
 * makes a rename change an unrelated variable of the same name in another scope.
 *
 * ## The two answers, and why neither is a guess
 *
 * `right, or nothing` is the rule [VelaTargets] is built on and this file repeats:
 * a leaf either resolves to the declaration the compiler would pick, or it resolves to
 * nothing at all.  Nothing here invents a target to be helpful, because a fabricated
 * reference is a usage the search will list and a rename will *write*.
 */
object VelaReferences {

    /**
     * The offset of the declaration the identifier leaf at `[start, end)` names, or
     * null when the leaf is not a reference at all.
     *
     * The leaf is a whole identifier token: `start` is its first character and `end`
     * one past its last, which is the half-open range `PsiElement.textRange` reports
     * and the range `VelaReferenceProvider` hands over.
     *
     * The checks, in the order they are asked — each is a shape of leaf this cannot
     * answer for:
     *
     *   1. the range is inside the text and not empty;
     *   2. the leaf's own characters spell a name (letters, digits, `_`) and do not
     *      start with a digit, so a number is not a name;
     *   3. `VelaDeclarations.declarationRange` answers — which is where the keyword and
     *      builtin rejections live, and where `VelaTargets` does the resolving (the
     *      tree-based resolution `GotoOracle` measured: 24,683 references judged, 0
     *      wrong);
     *   4. the target range really spells *this* name (the same test the provider made
     *      on the leaf it had resolved: a stale or mismatched target is no reference);
     *   5. the target is not the leaf itself: a declaration is not a usage of itself,
     *      and a reference equal to its own element is what makes a rename offer to
     *      rename the caret's own word.
     */
    fun referenceTargetAt(text: CharSequence, start: Int, end: Int): Int? {
        if (start < 0 || end > text.length || end <= start) return null
        val name = text.subSequence(start, end).toString()
        // The shape checks, the keyword and builtin rejections, the resolution and the
        // "is it itself" test are all inside `declarationTarget` -- one place, so the
        // reference and the results view cannot disagree about which word is a reference.
        val target = VelaDeclarations.declarationTarget(text, start, end, name) ?: return null
        // The same textual test the provider used to make on the leaf it had resolved:
        // a target that does not spell this name is no reference, whatever the model says.
        if (target.end - target.start != name.length) return null
        if (text.subSequence(target.start, target.end).toString() != name) return null
        return target.start
    }

    /**
     * May a name be *written* as a new name?  The pure half of
     * [VelaLeafManipulator.canWrite].
     *
     * The other half — "the leaf is live and its file is writable" — is the platform's
     * and needs an editor.  The half here is the shape of the name: a rename writes
     * these characters where a name was, so a name the lexer cannot read as one
     * identifier would leave a file whose text no longer means what it says.  This is
     * deliberately *not* a keyword check even though `def` is refused in the rename
     * dialog by the platform: this function answers what may be written into the
     * document, and a wrong-but-lexable name is the user's to correct, while a name
     * this returns true for and `VelaReference.handleElementRename` then refuses to
     * write is a rename that silently does nothing.
     */
    fun isWritableName(newName: String): Boolean =
        newName.isNotEmpty() && newName.all { isNamePart(it) } && !newName.first().isDigit()
}
