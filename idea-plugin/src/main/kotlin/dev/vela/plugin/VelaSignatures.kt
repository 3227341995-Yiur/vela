package dev.vela.plugin

/**
 * Where a parameter list may come from.  Two sources, and nothing else.
 *
 * THE RULE THIS FILE EXISTS TO ENFORCE
 *
 * A name drawn or inserted beside an argument must be a name the *declaration* gives
 * for that argument.  Twice now the plugin has broken that rule by reading names out
 * of text that was not a parameter list:
 *
 *   * `symbolParameters` split `VelaSymbol.detail` — the signature as written in the
 *     source — between its first `(` and its first `)`.  For `def f(s, s, s) -> int`,
 *     whose parameters have no annotations and which the compiler refuses, that text
 *     is `s, s, s`, so completion inserted `(s, s, s)` into the user's own file.  The
 *     hint and the popup were moved off that function onto the tree; completion was
 *     not.
 *   * `builtinParameterNames` split a builtin's **prose description** from `(` to `)`.
 *     Every entry of that table happens to begin with its signature, so it worked by
 *     luck: one description that mentions a parenthesis first — "see also (`abs`)" —
 *     and every hint for that builtin would be named out of prose.
 *
 * So the two sources are here, together, with the two text-shape checks that make
 * "is this text a parameter list?" a question about its *form* rather than about its
 * position in an index:
 *
 *   [declaredParameterNames]  the parser's tree, which is what `vm.exe parse` is held
 *                             to.  Null when the text between the parentheses holds
 *                             more entries than the tree recorded `param` nodes for —
 *                             a recovered declaration, whose parentheses hold text the
 *                             parser never read as parameters.
 *   [parameterNamesInSignature]  a *signature*: text that begins with the name and its
 *                             `(`.  Prose that merely mentions a parenthesis does not
 *                             pass that check and answers null.
 *
 * NULL MEANS "DO NOT DRAW", NEVER "DRAW A GUESS", and it is the whole of both fixes.
 */
object VelaSignatures {

    /**
     * The parameter names a declaration in the tree declares, in order, or null.
     *
     * The answer is the tree's parameters, or null: a declaration is trusted only when
     * the text between its parentheses holds exactly as many top-level entries as the
     * tree recorded `param` nodes for it.  `def f(s, s, s) -> int` records none and
     * writes three, so this answers null and the caller draws nothing — where reading
     * the text gave `s, s, s`.
     */
    fun declaredParameterNames(
        tree: VelaSyntaxTree,
        text: CharSequence,
        def: VelaSyntaxNode,
    ): List<String>? {
        val names = ArrayList<String>()
        for (c in def.children) if (c.kind == VelaNodeKind.PARAM) names.add(c.name)
        val open = openParenTokenOf(tree, def)
        val close = closeParenToken(tree, def)
        if (open < 0 || close < 0) return null
        val entries = topLevelEntries(text.toString(), tree.toks[open].end, tree.toks[close].start)
        return if (entries == names.size) names else null
    }

    /**
     * The parameter names a *signature* writes, or null when [signature] is not a
     * signature of [name].
     *
     * The form check is the point: the text must begin with `name(` and close that
     * parenthesis, so a description that mentions a parenthesis before the signature —
     * or a symbol whose `detail` is prose — answers null rather than names out of the
     * wrong span of text.  An empty parameter list (`now()`) answers `emptyList()`,
     * which is a fact about the signature; null means "this is not a signature at all".
     */
    fun parameterNamesInSignature(name: String, signature: String): List<String>? {
        if (name.isEmpty() || !signature.startsWith(name + "(")) return null
        val open = name.length
        var depth = 0
        var close = -1
        var i = open
        while (i < signature.length) {
            val c = signature[i]
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0) {
                    close = i
                    break
                }
            }
            i++
        }
        if (close < 0) return null
        val inner = signature.substring(open + 1, close).trim()
        if (inner.isEmpty()) return emptyList()
        val out = ArrayList<String>(4)
        var level = 0
        val current = StringBuilder()
        for (c in inner) {
            when {
                c == '[' || c == '(' -> {
                    level++
                    current.append(c)
                }
                c == ']' || c == ')' -> {
                    level--
                    current.append(c)
                }
                c == ',' && level == 0 -> {
                    out.add(signatureParamName(current.toString()))
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        out.add(signatureParamName(current.toString()))
        // `print(...)`: a variadic slot is not a parameter to name an argument with.
        return out.filter { it.isNotEmpty() && it != "..." && it != "…" }
    }

    /**
     * The return type a *signature* writes after `->`, or "" when it writes none.
     *
     * Read from the signature rather than from a description for the same reason the
     * parameters are: `range(from: int, to: int)` has no return type, and finding one
     * in whatever text follows the signature would be inventing it.
     */
    fun returnTypeInSignature(name: String, signature: String): String {
        if (parameterNamesInSignature(name, signature) == null) return ""
        val arrow = signature.indexOf("->")
        if (arrow < 0) return ""
        return signature.substring(arrow + 2).trim().substringBefore(' ').trim()
    }

    /** `mut a: Array[int, 4]` -> `a`; `s` -> `s`; `...` -> `...`. */
    private fun signatureParamName(parameter: String): String {
        val beforeColon = parameter.substringBefore(':').trim()
        return beforeColon.removePrefix("mut ").trim()
    }

    /**
     * How many top-level, comma-separated entries the text between two offsets holds.
     * Blank is zero; `a: Array[int, 4], b: int` is two.
     */
    private fun topLevelEntries(src: String, from: Int, to: Int): Int {
        var count = 0
        var depth = 0
        var seenAny = false
        var i = from
        while (i < to && i < src.length) {
            val c = src[i]
            when {
                c == '[' || c == '(' -> depth++
                c == ']' || c == ')' -> depth--
                c == ',' && depth == 0 -> count++
                !c.isWhitespace() -> seenAny = true
            }
            i++
        }
        if (!seenAny) return 0
        return count + 1
    }

    /** The token index of the `(` that opens a declaration's parameter list, or -1. */
    private fun openParenTokenOf(tree: VelaSyntaxTree, n: VelaSyntaxNode): Int {
        val from = if (n.startTok >= 0) n.startTok else 0
        val to = if (n.endTok >= 0) n.endTok else tree.toks.size - 1
        var i = from
        while (i <= to && i < tree.toks.size) {
            val t = tree.toks[i]
            if (t.kind == VelaTokKind.OP && t.code == VelaOps.LPAREN) return i
            i++
        }
        return -1
    }

    /** The token index of the `)` that closes a declaration's parameter list, or -1. */
    private fun closeParenToken(tree: VelaSyntaxTree, n: VelaSyntaxNode): Int {
        val open = openParenTokenOf(tree, n)
        if (open < 0) return -1
        val to = if (n.endTok >= 0) n.endTok else tree.toks.size - 1
        var depth = 0
        var i = open
        while (i <= to && i < tree.toks.size) {
            val t = tree.toks[i]
            if (t.kind == VelaTokKind.OP && t.code == VelaOps.LPAREN) depth++
            if (t.kind == VelaTokKind.OP && t.code == VelaOps.RPAREN) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }
}
