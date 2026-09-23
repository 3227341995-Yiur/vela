package dev.vela.plugin

/**
 * Resolution over [VelaModel]: turning the characters in front of the caret into
 * the declaration they name.
 *
 * Completion, hover and parameter info all ask the same question — "which
 * declaration is this name?" — and all three must answer it the same way, or the
 * plugin would tell the user two different things about one character.  So the
 * question is answered once, here, on top of the lexer-driven model, and the
 * three features only decide how to *draw* the answer.
 *
 * Everything in this file is deliberately local: one file's text in, one
 * declaration out.  There is no index, no scope analysis and no inference,
 * because the model this plugin is built on has none — it has names, where they
 * are written, and what is written beside them.
 */

/**
 * The struct a receiver expression names, or null when the text does not say.
 *
 * Vela is immutable and has no local type annotations, so the type of `x` in
 * `x.member` is genuinely not in a file's character stream — only declarations
 * are.  Three receivers *are* written down, and exactly those three resolve:
 *
 *   * `self`, inside a struct, is that struct;
 *   * a parameter's type is in its own declaration (`o: Vec2`), so a parameter
 *     name resolves to the struct of its declared type;
 *   * a struct's own name is its type, for the static call `Vec2.member`.
 *
 * Anything else returns null, and the caller then offers nothing rather than
 * something invented.
 */
fun structTypeOf(text: CharSequence, offset: Int, receiver: String): VelaSymbol? {
    if (receiver == "self") {
        return VelaModel.enclosingStruct(text, offset)
    }
    // A parameter (or any visible declaration) whose declared type is a struct
    // in this file.
    for (s in VelaModel.visibleSymbols(text, offset)) {
        if (s.kind != VelaSymbolKind.PARAMETER || s.name != receiver) continue
        structNamed(text, s.type)?.let { return it }
    }
    // `Vec2.` — the name of a struct is a type.
    if (receiver.firstOrNull()?.isUpperCase() == true) {
        structNamed(text, receiver)?.let { return it }
    }
    return null
}

/** The struct declared under this type name, unwrapping `mut` as written. */
fun structNamed(text: CharSequence, typeName: String): VelaSymbol? {
    val name = typeName.trim().removePrefix("mut ").trim()
    return VelaModel.symbols(text).firstOrNull {
        it.kind == VelaSymbolKind.STRUCT && it.name == name
    }
}

/**
 * The callable a call site names: a method of the receiver's struct when there is
 * a `.receiver`, else the file's own function, else one of the language's
 * builtins.  Null when none of those match, so parameter info shows no popup
 * rather than an empty one.
 *
 * Field access resolves to the field, which is what makes `obj.field(` — if it
 * ever existed — at least name a real declaration instead of a guess.
 */
fun resolveCall(text: CharSequence, call: VelaCall): VelaSymbol? {
    // A declaration's own signature is not a call, so parameter info stays closed
    // while the parentheses are being written.
    if (keywordNameBefore(text, call.openParen)) return null

    if (call.receiver != null) {
        val owner = structTypeOf(text, call.openParen, call.receiver)
        val member = owner?.let { struct ->
            VelaModel.membersOf(text, struct).firstOrNull { it.name == call.name }
        }
        // A method's signature, as written, already lists its parameters including
        // the receiver, so no adjustment is needed here.
        if (member != null) return member
    }

    VelaModel.symbols(text).firstOrNull {
        it.name == call.name && (it.kind == VelaSymbolKind.FUNCTION || it.kind == VelaSymbolKind.METHOD)
    }?.let { return it }

    // The language's own names: their declaration is the *signature* field of the
    // builtin table, and both the parameter names and the return type are read from
    // that signature rather than from the sentence beside it.  A builtin whose
    // signature cannot be read answers no parameter names, and then completion inserts
    // `()` rather than names from prose.
    val builtin = VelaModel.BUILTINS.firstOrNull { it.name == call.name }
    if (builtin != null) {
        return VelaSymbol(
            kind = VelaSymbolKind.FUNCTION,
            name = builtin.name,
            detail = builtin.signature,
            type = VelaSignatures.returnTypeInSignature(builtin.name, builtin.signature),
            line = 0, // declared by the language, not on a line of this file
            parent = -1,
            parameters = VelaSignatures.parameterNamesInSignature(builtin.name, builtin.signature),
        )
    }
    return null
}

/**
 * Is the word that *starts* at `at` a keyword?  `def f(` and `pure def fib(`
 * spell a declaration, and a declaration is not a call.
 */
private fun keywordNameBefore(text: CharSequence, at: Int): Boolean {
    var i = at
    while (i > 0 && isNamePart(text[i - 1])) i--
    if (i == at) return false
    // `subSequence` keeps it a CharSequence; `KEYWORDS` is a Set<String>.
    val word = text.subSequence(i, at)
    return VelaTokenTypes.KEYWORDS.any { it.contentEquals(word) }
}

/**
 * The identifier at `offset` in `text`, the name a hover is about.  Returns null
 * unless the caret is *on* a name — hovering whitespace, an operator or a number
 * must produce nothing rather than the nearest declaration.
 *
 * The one case treated as "on a name" without being literally so is the offset one
 * past the end of the text, which is where the platform puts a caret after a name
 * has been typed at the end of a file and where the hover is still about that name.
 * It is deliberately not generalised to "any position not in a name": that would
 * turn a hover on the space between two names into a hover on the *previous* one,
 * which is the kind of near-miss this provider exists not to make.
 */
fun nameAt(text: CharSequence, offset: Int): String? {
    val end = offset.coerceIn(0, text.length)
    if (end == text.length || !isNamePart(text[end])) {
        // Not on a name character: the only remaining honest reading is a caret just
        // past the end of a name at the very end of the text.
        if (end != text.length || end == 0 || !isNamePart(text[end - 1])) return null
        var back = end
        while (back > 0 && isNamePart(text[back - 1])) back--
        val word = text.subSequence(back, end).toString()
        return if (word.isEmpty() || word.first().isDigit()) null else word
    }
    var start = end
    var stop = end + 1
    while (start > 0 && isNamePart(text[start - 1])) start--
    while (stop < text.length && isNamePart(text[stop])) stop++
    val word = text.subSequence(start, stop).toString()
    return if (word.isEmpty() || word.first().isDigit()) null else word
}
