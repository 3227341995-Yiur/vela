package dev.vela.plugin

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import java.util.concurrent.ConcurrentHashMap

/**
 * The *names* in a Vela file: which words are names at all, and which name each
 * one is.
 *
 * The lexer highlighter (`VelaHighlighting.kt`) colours a Vela file by what a
 * word *is* — a keyword, a number, a string, an operator — and by nothing else,
 * so `fib` in `fib(10)`, `dot` in `p.dot(p)` and `Vec2` all come out as the same
 * grey identifier.  This file colours what those words *name*.  It is still not a
 * statement about meaning: both questions are read from the characters in front of
 * it with `VelaModel`, and whether a program is legal remains `vm.exe`'s answer
 * alone (`VelaAnnotator.kt` draws that one).  What this file must never do is
 * *guess*: a word the text does not explain as a name is left exactly as the
 * lexer drew it, so a reader never gets a colour the plugin cannot justify from
 * the file itself.
 *
 * The classifier is a plain object over a `CharSequence` on purpose.  It needs no
 * IDE, so the whole of it can be — and is — checked headlessly, and the annotator
 * and that check ask it the same question rather than each having its own idea of
 * where `Vec2` is.
 *
 * One thing only the text can decide is a *type*: the language's own type names
 * (`int`, `float`, `bool`, `str`, `u8`, `i32`, `Array`) are not keywords, so they
 * are ordinary identifiers to the lexer and to every other part of this plugin —
 * `int` can even be a variable's name.  What makes one of them a type is the
 * position it is written in, which is read here and nowhere else.
 */

enum class VelaNameKind {
    FUNCTION_DECLARATION,
    FUNCTION_CALL,
    STRUCT_DECLARATION,
    STRUCT_USE,
    FIELD,
    PARAMETER,

    /**
     * A name written where a type is expected, when it is one of the language's own
     * type names and not a struct this file declares — the last of those is
     * [STRUCT_USE], because it is drawn with a different colour key, and a kind that
     * could not tell the two apart would push that decision onto the annotator.
     */
    TYPE,
}

/** One classified name: the half-open range the lexer gives it, and what it names. */
data class VelaNameUse(val kind: VelaNameKind, val start: Int, val end: Int)

object VelaSemanticNames {

    /** No struct: `self` outside one, or a name whose declared type is not one. */
    private const val NO_STRUCT = -1

    /**
     * The language's own type names: the table the compiler resolves a type against
     * (`selfhost/parts/resolve.vel` — `int`, `float`, `bool`, `str`, `u8`, `i32`),
     * `Array` from the named type the parser builds (`parser.vel`), and SPEC §3.
     *
     * Not one of them is a lexer keyword — `None` is, and stays null here, drawn as
     * the keyword the lexer already says it is — so `int` is an ordinary identifier
     * to every other part of the plugin, and the only thing that can say it is a
     * *type* is the position it is written in.
     */
    private val TYPE_NAMES = setOf("int", "float", "bool", "str", "u8", "i32", "Array")

    /**
     * What the name covering `offset` is, or null.
     *
     * A name covers the characters the lexer reads as one identifier, so `offset`
     * is the index of a character *inside* a word; a position outside every word —
     * whitespace, punctuation, the space between two names, an offset past the end
     * of the text — is null rather than the nearest name, because a colour belongs
     * to the word the position is *on*.  Null is also the answer for every word the
     * text does not explain: a keyword (including `range`, which the lexer and the
     * highlighter both call a keyword, and which stays a keyword here), a number, a
     * name written inside a string or a comment, a local binding (the enum has no
     * kind for one), and any name the file does not declare.
     *
     * A name written where a *type* is expected is the one place the file's
     * characters decide between two kinds without a declaration being present:
     * `int`, `float`, `bool`, `str`, `u8`, `i32` and `Array` are the language's own
     * type names and come back as [VelaNameKind.TYPE], while a struct this file
     * declares comes back as [VelaNameKind.STRUCT_USE] whether it is written as a
     * type or constructed.  A name of the same spelling anywhere else is a variable,
     * and a variable is not a type: in `mut int: int = 1` the first is nothing and
     * the second is [VelaNameKind.TYPE].
     */
    fun classify(text: CharSequence, offset: Int): VelaNameKind? {
        if (offset < 0 || offset >= text.length) return null
        val names = classifyAll(text)
        var low = 0
        var high = names.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val name = names[mid]
            when {
                offset < name.start -> high = mid - 1
                offset >= name.end -> low = mid + 1
                else -> return name.kind
            }
        }
        return null
    }

    /**
     * Every name in the file, in source order, classified in one pass.
     *
     * The whole file at once — rather than per name on demand — is what keeps the
     * annotator's cost linear.  The pass lexes the text once, asks `VelaModel` for
     * the declarations once (that list is cached by content, and hashing the buffer
     * per name would cost more than everything else here together), and then reads
     * each name against local state: which block it is in, and what is written
     * beside it.
     */
    fun classifyAll(text: CharSequence): List<VelaNameUse> {
        // The identity check first: a String cannot change, so a caller asking
        // about a hundred offsets of one buffer — which is what a check like this
        // one does — never hashes it twice.  `last` is one reference, so there is
        // no way to read one buffer's text beside another's answer.
        if (text is String) {
            val answered = last
            if (answered != null && answered.text === text) return answered.names
        }
        val key = contentKey(text)
        val cached = byContent[key]
        if (cached != null) {
            if (text is String) last = Answer(text, cached)
            return cached
        }
        val built = build(text)
        // Small on purpose: the platform hands a fresh String every pass, so the
        // entries are the files actually open, not a history of every edit.
        if (byContent.size > 16) byContent.clear()
        byContent[key] = built
        if (text is String) last = Answer(text, built)
        return built
    }

    private class Answer(val text: String, val names: List<VelaNameUse>)

    @Volatile
    private var last: Answer? = null

    private val byContent = ConcurrentHashMap<String, List<VelaNameUse>>()

    /**
     * The same content key `VelaModel` uses, and for the same reason: the key has
     * to be computed *in place*.  `toString()` on the buffer would allocate a copy
     * of a 355 KB file on every highlighting pass to avoid the work this cache
     * exists to avoid.  (It is a copy of a private function because there is no
     * public one; the two are the same function.)
     */
    private fun contentKey(text: CharSequence): String {
        var hash = 1125899906842597L
        for (i in 0 until text.length) hash = 31 * hash + text[i].code
        return text.length.toString() + ":" + hash
    }

    // ------------------------------------------------------------------ one pass

    private fun build(text: CharSequence): List<VelaNameUse> {
        // Exactly one call.  `symbols()` re-lexes on a miss and hashes the buffer on
        // every call, so a per-name question to the model is the one thing this
        // pass must not do.
        val symbols = VelaModel.symbols(text)

        val structByName = HashMap<String, Int>()
        val callables = HashSet<String>()
        val membersOfStruct = HashMap<Int, HashMap<String, VelaSymbol>>()
        val paramsOfCallable = HashMap<Int, HashMap<String, VelaSymbol>>()
        val fieldOrParamAt = HashMap<String, Int>()
        val callableAt = HashMap<String, Int>()
        for (index in symbols.indices) {
            val symbol = symbols[index]
            val at = symbol.line.toString() + ":" + symbol.name
            when (symbol.kind) {
                VelaSymbolKind.STRUCT -> structByName.getOrPut(symbol.name) { index }
                VelaSymbolKind.FUNCTION -> {
                    callables.add(symbol.name)
                    callableAt.getOrPut(at) { index }
                }
                VelaSymbolKind.METHOD -> {
                    // A method is callable by name as well: `resolveCall` in
                    // VelaNames.kt falls back to any declaration with the name when
                    // the receiver's type is not written down, and a name must not
                    // read as one thing in the editor and another in the parameter
                    // info popup.
                    callables.add(symbol.name)
                    callableAt.getOrPut(at) { index }
                    membersOfStruct.getOrPut(symbol.parent) { HashMap() }[symbol.name] = symbol
                }
                VelaSymbolKind.FIELD -> {
                    membersOfStruct.getOrPut(symbol.parent) { HashMap() }[symbol.name] = symbol
                    fieldOrParamAt.getOrPut(at) { index }
                }
                VelaSymbolKind.PARAMETER -> {
                    paramsOfCallable.getOrPut(symbol.parent) { HashMap() }[symbol.name] = symbol
                    fieldOrParamAt.getOrPut(at) { index }
                }
                VelaSymbolKind.ENUM -> structByName.getOrPut(symbol.name) { index }
                VelaSymbolKind.VARIANT -> {
                    // A variant name is a value: `Circle(2.0)` is a call and `Empty` is a
                    // bare name (SPEC.md §13), so it is neither a type nor a function --
                    // and this pass has no key for "variant", so the name is left to the
                    // default colour rather than being told as something it is not.
                    // Named here because it is a limit, and measured by nothing: the
                    // colour of a variant name is not part of any row's evidence.
                }
            }
        }
        val builtins = HashSet<String>(VelaModel.BUILTINS.size * 2)
        for (builtin in VelaModel.BUILTINS) builtins.add(builtin.name)

        val tokens = significantTokens(text)
        val opened = declarationBodies(
            tokens, structByName, callableAt, membersOfStruct, paramsOfCallable,
        )

        val names = ArrayList<VelaNameUse>((tokens.size / 3) + 8)
        val frames = ArrayList<Frame>(8)
        // How deep in `Array[T, N]`'s brackets the walk is: a name in there is an
        // element type, because the length beside it is a number literal and the
        // parser refuses anything else (parser.vel, `parse_type`).
        var typeBrackets = 0
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token.ch == '{' ->
                    frames.add(opened[i] ?: Frame(FrameKind.BLOCK, NO_STRUCT, emptyMap()))
                token.ch == '}' -> if (frames.isNotEmpty()) frames.removeAt(frames.size - 1)
                token.ch == '[' -> {
                    // The bracket of a type, not of an array literal: `Array[int, 4]`
                    // opens one and `= [0.0]` does not, which is decided by what
                    // stands in front of the bracket.
                    val opener = if (i > 0) tokens[i - 1] else null
                    val opensType = opener != null && opener.isName
                        && typePosition(tokens, i - 1, typeBrackets > 0)
                    if (opensType) typeBrackets++
                }
                token.ch == ']' -> if (typeBrackets > 0) typeBrackets--
                token.isName -> {
                    val name = token.word!!
                    val before = if (i > 0) tokens[i - 1] else null
                    val after = if (i + 1 < tokens.size) tokens[i + 1] else null
                    val declared = fieldOrParamAt[token.line.toString() + ":" + name]
                        ?.let { symbols[it] }
                    var kind: VelaNameKind? = when {
                        // The words `struct` and `def` introduce a declaration and
                        // are unambiguous, so the declaration site is read from
                        // them: a lookup by line would also match a *use* of the
                        // same name written on the same line (`return fib(n - 1)`
                        // on a one-line function).
                        before?.word == "struct" -> VelaNameKind.STRUCT_DECLARATION
                        before?.word == "def" -> VelaNameKind.FUNCTION_DECLARATION
                        after?.ch == ':' && declared?.kind == VelaSymbolKind.FIELD ->
                            VelaNameKind.FIELD
                        after?.ch == ':' && declared?.kind == VelaSymbolKind.PARAMETER ->
                            VelaNameKind.PARAMETER
                        else -> null
                    }
                    if (kind == null && typePosition(tokens, i, typeBrackets > 0)) {
                        // The language's own type names are ordinary identifiers to
                        // the lexer, so nothing but the position can say they are
                        // types.  A struct's name in the same position is left to the
                        // rule further down, which gives it STRUCT_USE; anything else
                        // in a type position is a name this file does not explain, and
                        // stays uncoloured.
                        if (TYPE_NAMES.contains(name)) kind = VelaNameKind.TYPE
                    }
                    if (kind == null && before?.ch == '.') {
                        // The receiver is the word in front of the dot; the dot is
                        // not one, so a stray `.x` with nothing before it has none.
                        val receiver = if (i >= 2) tokens[i - 2] else null
                        kind = memberKind(receiver, name, after, frames, structByName, membersOfStruct)
                    }
                    if (kind == null && after?.ch == '(') {
                        kind = when {
                            // `Vec2(3.0, 4.0)` names the type, not a function.
                            structByName.containsKey(name) -> VelaNameKind.STRUCT_USE
                            callables.contains(name) -> VelaNameKind.FUNCTION_CALL
                            builtins.contains(name) -> VelaNameKind.FUNCTION_CALL
                            else -> null
                        }
                    }
                    if (kind == null && structByName.containsKey(name)) {
                        // A struct's name is a type, and in Vela there is nowhere
                        // else for it to appear: `p: Vec2`, `-> Vec2`, `Vec2(...)`.
                        kind = VelaNameKind.STRUCT_USE
                    }
                    // A binding — `mut p: Vec2 = ...` or `p: Vec2 = ...` inside a
                    // body.  The model declares fields and parameters, not locals,
                    // and the enum has no kind for one, so this is not classified.
                    // It is recorded all the same, because the type *is* written
                    // down beside the name and that is what makes the call in
                    // `p.dot(p)` a call the plugin can name instead of guess.
                    if (after?.ch == ':' && declared == null && frames.isNotEmpty()) {
                        val type = if (i + 2 < tokens.size) tokens[i + 2] else null
                        val owner = if (type != null && type.isName) {
                            structByName[type.word] ?: NO_STRUCT
                        } else {
                            // `Array[T, N]`: the element type is inside brackets, and
                            // an indexed receiver (`a[i].x`) is a shape this pass does
                            // not resolve, so it stays unknown rather than guessed.
                            NO_STRUCT
                        }
                        frames[frames.size - 1].locals[name] = owner
                    }
                    if (kind != null) names.add(VelaNameUse(kind, token.start, token.end))
                }
            }
            i++
        }
        return names
    }

    /**
     * Is the name at `index` written where a type is expected?
     *
     * Vela writes a type in exactly five places, and `parser.vel`'s `parse_type` is
     * the whole list: after the `:` of a parameter, a field or a binding, after the
     * `->` of a signature, and inside the brackets of `Array[T, N]` — every one of
     * which has to be read from the tokens, because `int` is not a keyword.
     *
     * A `:` is never anything else in this language (SPEC §2: `if`, `while` and
     * `def` must be followed by `{`, and a `:` there is a syntax error), so a name
     * after one is a declared type and nothing else.  That is what tells the two
     * positions in `mut int: int = 1` apart: the first is a binding — no kind, the
     * enum has none for one — and the second is a type.
     */
    private fun typePosition(tokens: List<SemTok>, index: Int, insideArray: Boolean): Boolean {
        if (insideArray) return true
        val before = if (index > 0) tokens[index - 1] else null
        if (before?.ch == ':') return true
        return arrowBefore(tokens, index - 1)
    }

    /**
     * Is the token at `index` the `->` of a signature?
     *
     * `VelaLexer` reads `->` as one operator token, which is what `isArrow` records;
     * a lexer that split it would leave `-` then `>`, the same arrow (VelaModel
     * defends against both spellings of it), so that shape counts too.
     */
    private fun arrowBefore(tokens: List<SemTok>, index: Int): Boolean {
        val token = if (index >= 0) tokens.getOrNull(index) else null
        if (token == null) return false
        if (token.isArrow) return true
        return token.ch == '-' && tokens.getOrNull(index + 1)?.ch == '>'
    }

    /**
     * The `{` that opens each declaration's body, and what it opens.
     *
     * Only the two declarations whose body is a *scope* get a frame of their own.
     * A struct's body is where its members are visible, which is what `self.x`
     * needs; a callable's body is where its parameters are visible, which is what
     * `o.dot(...)` needs when `o` is one of them.  Anything else that opens a brace
     * is a plain block.
     */
    private fun declarationBodies(
        tokens: List<SemTok>,
        structByName: Map<String, Int>,
        callableAt: Map<String, Int>,
        membersOfStruct: Map<Int, Map<String, VelaSymbol>>,
        paramsOfCallable: Map<Int, Map<String, VelaSymbol>>,
    ): Map<Int, Frame> {
        val out = HashMap<Int, Frame>()
        var i = 0
        while (i < tokens.size) {
            val word = tokens[i].word
            if (word == "struct" || word == "def") {
                val name = if (i + 1 < tokens.size) tokens[i + 1] else null
                if (name != null && name.isName) {
                    val brace = if (word == "struct") {
                        // `struct S {` — nothing may stand between the name and the
                        // brace, so a file missing one declares no scope rather
                        // than donating a later body to this struct.
                        if (i + 2 < tokens.size && tokens[i + 2].ch == '{') i + 2 else -1
                    } else {
                        braceAfterSignature(tokens, i + 1)
                    }
                    if (brace >= 0) {
                        val index = if (word == "struct") {
                            structByName[name.word]
                        } else {
                            callableAt[name.line.toString() + ":" + name.word]
                        }
                        if (index != null) {
                            out[brace] = if (word == "struct") {
                                Frame(
                                    FrameKind.STRUCT,
                                    index,
                                    declaredTypes(membersOfStruct[index], structByName, true),
                                )
                            } else {
                                Frame(
                                    FrameKind.CALLABLE,
                                    NO_STRUCT,
                                    declaredTypes(paramsOfCallable[index], structByName, false),
                                )
                            }
                        }
                    }
                }
            }
            i++
        }
        return out
    }

    /**
     * The `{` after a callable's signature, or -1 when it has none (`extern c def`
     * declares a C function with no body).
     *
     * Read the way the declaration is written: the parameter list, then `-> T`,
     * then the body.  Stopping at the arrow instead would hand the *next*
     * function's body to an `extern`, and then `self` and the parameters of that
     * function would resolve against the wrong one.
     */
    private fun braceAfterSignature(tokens: List<SemTok>, nameIndex: Int): Int {
        var j = nameIndex + 1
        if (j >= tokens.size || tokens[j].ch != '(') return -1
        var depth = 0
        while (j < tokens.size) {
            when (tokens[j].ch) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            j++
        }
        if (j >= tokens.size) return -1
        j++
        if (j < tokens.size && tokens[j].ch == '-') {
            j += 2 // past `->` and the return type's name
            if (j < tokens.size && tokens[j].ch == '[') {
                var nested = 0
                while (j < tokens.size) {
                    when (tokens[j].ch) {
                        '[' -> nested++
                        ']' -> {
                            nested--
                            if (nested == 0) {
                                j++
                                break
                            }
                        }
                    }
                    j++
                }
            }
        }
        return if (j < tokens.size && tokens[j].ch == '{') j else -1
    }

    /**
     * The names a scope declares a type for: a callable's parameters, or a
     * struct's fields.  Only the ones whose type is a struct this file declares are
     * worth recording — the others are remembered as [NO_STRUCT] so that an inner
     * binding of the same name shadows an outer one honestly.
     */
    private fun declaredTypes(
        declarations: Map<String, VelaSymbol>?,
        structByName: Map<String, Int>,
        onlyFields: Boolean,
    ): Map<String, Int> {
        if (declarations == null) return emptyMap()
        val out = HashMap<String, Int>(declarations.size)
        for ((name, declaration) in declarations) {
            if (onlyFields && declaration.kind != VelaSymbolKind.FIELD) continue
            out[name] = structByName[declaration.type] ?: NO_STRUCT
        }
        return out
    }

    /**
     * What a name after a `.` is: a member of the struct the receiver's type
     * resolves to, or null when the text does not say which struct that is.
     *
     * A receiver resolves when its *type is written down* — `self` inside a struct,
     * a name declared beside a type (`o: Vec2`, `v: Vec2`, `mut p: Vec2`), or the
     * name of a struct.  Those are the receivers `VelaNames.structTypeOf` resolves for
     * hover, plus a name whose declared type this pass has seen in an enclosing
     * scope; anything else names nothing here, and a member the resolved struct
     * does not declare names nothing either — `o.x` is not a field of a struct
     * that has no `x`.
     */
    private fun memberKind(
        receiver: SemTok?,
        member: String,
        after: SemTok?,
        frames: List<Frame>,
        structByName: Map<String, Int>,
        membersOfStruct: Map<Int, Map<String, VelaSymbol>>,
    ): VelaNameKind? {
        if (receiver == null || !receiver.isName) return null
        val owner = receiverStruct(receiver.word!!, frames, structByName)
        if (owner == NO_STRUCT) return null
        val declared = membersOfStruct[owner]?.get(member) ?: return null
        return when {
            // A field is a field whatever follows it.  Vela has no field to call,
            // so `obj.field(` is not a call that means anything either.
            declared.kind == VelaSymbolKind.FIELD -> VelaNameKind.FIELD
            after?.ch == '(' -> VelaNameKind.FUNCTION_CALL
            // A method named without a call is not a call, and Vela has no
            // function values, so there is nothing honest to colour it as.
            else -> null
        }
    }

    /**
     * The struct a receiver names, or [NO_STRUCT].
     *
     * Read from the scopes the receiver is written in, innermost first: a binding
     * declared in an inner block shadows one outside it, and a field of the
     * enclosing struct is only visible inside that struct's methods.  Scoped by
     * braces rather than by line, so `self` written after a struct's closing brace
     * is not a member of that struct and a body that is inside no struct has no
     * `self` at all.
     */
    private fun receiverStruct(name: String, frames: List<Frame>, structByName: Map<String, Int>): Int {
        if (name == "self") {
            for (k in frames.indices.reversed()) {
                if (frames[k].kind == FrameKind.STRUCT) return frames[k].struct
            }
            return NO_STRUCT
        }
        for (k in frames.indices.reversed()) {
            val frame = frames[k]
            val local = frame.locals[name]
            if (local != null) return local
            val typed = frame.typed[name]
            if (typed != null) return typed
        }
        // The name of a struct is a type, for `Vec2.member`.
        if (name.firstOrNull()?.isUpperCase() == true) return structByName[name] ?: NO_STRUCT
        return NO_STRUCT
    }

    /** A scope: a struct's body, a callable's body, or a plain block. */
    private class Frame(
        val kind: FrameKind,
        /** The struct this frame is inside, or [NO_STRUCT]. */
        val struct: Int,
        /** The names this scope declares a type for: a struct's fields, a callable's parameters. */
        val typed: Map<String, Int>,
    ) {
        /** Bindings declared in this block: name -> struct, [NO_STRUCT] when not one. */
        val locals = HashMap<String, Int>()
    }

    private enum class FrameKind { STRUCT, CALLABLE, BLOCK }

    /**
     * A significant token: everything the lexer emits except whitespace and
     * comments.  The word is kept only for names and keywords — punctuation is
     * recognised by its first character — so a brace does not cost a `String`
     * apiece, and a name still comes from the same lexer that draws the rest of
     * the file.
     */
    private class SemTok(
        val word: String?,
        val ch: Char,
        /** This token is the `->` of a signature: what follows is a return type. */
        val isArrow: Boolean,
        val start: Int,
        val end: Int,
        val line: Int,
        val isName: Boolean,
    )

    /**
     * The file's tokens, once, with the line each one starts on — the same lexer
     * the editor colours with, so "is this a name?" and "is this inside a string?"
     * are answered by the code that decides them everywhere else in this plugin.
     * The whole pass reads this list and never re-lexes the file.
     */
    private fun significantTokens(text: CharSequence): List<SemTok> {
        val lexer = VelaLexer()
        lexer.start(text)
        val out = ArrayList<SemTok>(256)
        var line = 1
        var seen = 0
        while (lexer.tokenType != null) {
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            val type = lexer.tokenType
            if (type != null) {
                // A token's line is where it begins, which is the compiler's own
                // rule for a diagnostic and the model's rule for a declaration.
                for (i in seen until start) if (text[i] == '\n') line++
                if (type != TokenType.WHITE_SPACE && type != VelaTokenTypes.COMMENT) {
                    val isName = type == VelaTokenTypes.IDENTIFIER
                    val word = if (isName || type == VelaTokenTypes.KEYWORD) {
                        text.subSequence(start, end).toString()
                    } else {
                        null
                    }
                    // `->` and `-=` are both two characters starting with `-`, and
                    // one of them introduces a type while the other does not, so the
                    // arrow is recognised where the text is rather than by its length.
                    val isArrow = end - start == 2 && text[start] == '-' && text[start + 1] == '>'
                    out.add(SemTok(word, text[start], isArrow, start, end, line, isName))
                }
                for (i in start until end) if (text[i] == '\n') line++
                seen = end
            }
            lexer.advance()
        }
        return out
    }
}

/**
 * The names, drawn with the platform's own colour keys.
 *
 * A key rather than a colour, for the reason `VelaColors` gives: a reader who has
 * tuned their scheme for every other language gets Vela looking like the rest of
 * their editor.  Nothing here is a diagnostic — every annotation is a silent
 * information-level one, so the only thing that ever reports an error in a Vela
 * file stays `vm.exe`, through `VelaExternalAnnotator`.
 */
class VelaSemanticAnnotator : Annotator {
    /**
     * The file is the whole unit of work: the platform calls this once per element
     * and there is no tree to descend (Vela's PSI is flat — one leaf per token), so
     * everything below the file is skipped and the file's names are classified in
     * one pass.  `classifyAll` caches by content, so an unchanged file is not
     * re-lexed on the next highlighting pass.
     */
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiFile) return
        val text = element.text
        if (text.isEmpty()) return
        for (name in VelaSemanticNames.classifyAll(text)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(name.start, name.end))
                .textAttributes(colourOf(name.kind))
                .create()
        }
    }
}

/**
 * The platform key each kind is drawn with, following the user's theme.
 *
 * A type name the language provides takes the keyword colour, which is what a
 * builtin type is drawn with in the platform's own languages and what the lexer
 * already gives Vela's keywords; a struct this file declares stays a class name,
 * beside the types the user writes themselves.
 */
private fun colourOf(kind: VelaNameKind): TextAttributesKey = when (kind) {
    VelaNameKind.FUNCTION_DECLARATION -> DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
    VelaNameKind.FUNCTION_CALL -> DefaultLanguageHighlighterColors.FUNCTION_CALL
    VelaNameKind.STRUCT_DECLARATION -> DefaultLanguageHighlighterColors.CLASS_NAME
    VelaNameKind.STRUCT_USE -> DefaultLanguageHighlighterColors.CLASS_NAME
    VelaNameKind.FIELD -> DefaultLanguageHighlighterColors.INSTANCE_FIELD
    VelaNameKind.PARAMETER -> DefaultLanguageHighlighterColors.PARAMETER
    VelaNameKind.TYPE -> DefaultLanguageHighlighterColors.KEYWORD
}
