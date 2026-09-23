package dev.vela.plugin

/**
 * What a name in a file refers to, resolved over the parser's tree.
 *
 * This is the answer go-to-declaration, find-usages and the reference provider all
 * need, and it is one function because they must not give two answers about one
 * name.  It reads the tree -- the one `ast-diff.ps1` holds to `vm.exe parse` (107
 * files, 115,451 node lines, no difference) -- rather than re-scanning tokens,
 * because the questions it answers are exactly the ones a token stream cannot
 * answer: *which* binding is in scope at this offset, *which* struct owns this
 * member, *which* block declares this name.
 *
 * THE RULE THE WHOLE THING IS BUILT ON: right, or nothing.
 *
 * A wrong target is worse than no target -- it sends the reader to a declaration
 * that does not explain the name they are looking at -- so every case below either
 * finds the declaration the compiler would, or returns null.  In particular a
 * receiver whose declared type the file does not write down resolves to nothing,
 * and a name that is not declared anywhere gives nothing.
 *
 * What resolves, in the order the cases are tried (each is stricter than the next,
 * and the first that fits wins):
 *
 *  1. `x` after a dot -- `p.x`, `self.x`, `o.dot` -- is a member of the receiver's
 *     struct, when the file says what that struct is: a parameter or local whose
 *     declared type names a struct, `self`, or a struct's own name.  The member is
 *     the field or method of that struct with this name.
 *  2. a loop variable (`for i in range(...)`) enclosing the use.
 *  3. a local binding (`mut s: int = ...`, `s: int = ...`) that *precedes* the use
 *     in the same block or in an enclosing one -- so an inner binding shadows an
 *     outer one, which is the compiler's rule.
 *  4. a parameter of the enclosing function or method, by name.
 *  5. a call `f(...)`: the file's own function, or the method of a struct when
 *     there is a receiver, or nothing for a builtin (the language declares it and
 *     there is no line in this file to go to).
 *  6. a file-level function or struct by name -- which is also what a struct name
 *     written as a type (`o: Vec2`) resolves to.
 *
 * Null for: a keyword; a builtin name used as a value; a number; a name that is
 * itself a declaration (the platform offers the caret its own element); a receiver
 * whose type is not written down; a member its struct does not declare; and any
 * name that is not declared anywhere.
 */
enum class VelaTargetKind {
    FUNCTION, METHOD, STRUCT, FIELD, LOCAL, PARAMETER, LOOP_VARIABLE,

    /*
     * SPEC.md §13.  Two more names a file declares and a reader can click:
     * an enum's own name (written in a type annotation, `c: Shape`) and a
     * variant's (written as a construction, `Circle(2.0)`, in an arm pattern,
     * `Circle(r) { ... }`, and as a bare value, `Empty`).  A variant is
     * file-global by the language's own rule, so one lookup by name is the whole
     * answer -- the compiler refuses two enums claiming one variant name.
     */
    ENUM, VARIANT,
}

/** A declaration a name refers to: what it is, what it is called, and where it is. */
class VelaTarget(
    val kind: VelaTargetKind,
    val name: String,
    /** The *name* of the declaration, so a navigation lands on the word. */
    val start: Int,
    val end: Int,
)

object VelaTargets {

    /**
     * The parameter names a call's callee *declares*, in order, or null.
     *
     * This is where the hint engine and the parameter-info popup get their names
     * from, and the reason it is here rather than in a string split is the defect
     * that made it necessary.  `symbolParameters` used to read a symbol's `detail`
     * -- the declaration's text -- between its first `(` and its first `)` and call
     * whatever it found there a parameter name.  For a declaration the compiler
     * refuses because its parameters have no type annotations, the parser records no
     * parameter nodes at all but the text is still there, so the names were read out
     * of a parameter list that does not exist.  Measured, not reasoned: calling
     * `f(1, 2, 3)` after writing
     *
     *     def f(s, s, s) -> int { ... }
     *
     * drew the hints `s: s: s: ` -- the report that started this.  (Both shapes are
     * refused by the compiler: unannotated parameters are a syntax error, and
     * duplicate parameter names a type error, so no *valid* program can produce that
     * hint today.  A refused file is exactly when the user is typing.)
     *
     * THE RULE, AND IT IS THE WHOLE FIX: the answer is the tree's parameters, or
     * null.  Null means "do not draw", never "draw a guess".  A declaration is
     * trusted only when the text between its parentheses holds exactly as many
     * top-level entries as the tree recorded `param` nodes for it -- so a recovered
     * declaration, whose parentheses hold text the parser did not read as
     * parameters, answers null and the call gets no hint at all.
     *
     * [calleeNameStart] is the offset of the callee's own name: for `f(1)` and
     * `p.dot(q)` that is the offset of `f` and of `dot`, which is the name the
     * declaration must have.
     */
    fun declaredParameterNames(text: CharSequence, calleeNameStart: Int): List<String>? {
        if (calleeNameStart < 0) return null
        val src = text.toString()
        val name = identifierAt(src, calleeNameStart) ?: return null
        val tree = VelaSyntaxParser.parse(src)
        val def = declarationOfCallable(tree, name) ?: return null
        return VelaSignatures.declaredParameterNames(tree, text, def)
    }

    /**
     * Is there a `def` of that name at all -- whether or not its parameter list can be
     * read?
     *
     * The caller needs this to decide *where* to look next.  A name this file declares
     * must be answered by that declaration or by nothing; falling back to the
     * language's own table for a name the file has written would name an argument
     * after a builtin the user is not calling.  Measured: `tests/build/extern/
     * extern_c_probe.vel` declares `extern c def abs(x: i32) -> i32` and calls
     * `abs(-7)`; the builtin table's `abs(n: int)` named that argument `n:`.
     */
    fun declaresFunction(text: CharSequence, calleeNameStart: Int): Boolean {
        if (calleeNameStart < 0) return false
        val src = text.toString()
        val name = identifierAt(src, calleeNameStart) ?: return false
        val tree = VelaSyntaxParser.parse(src)
        return declarationOfCallable(tree, name) != null
    }

    /** The identifier starting at [offset], or null. */
    private fun identifierAt(src: String, offset: Int): String? {
        if (offset < 0 || offset >= src.length) return null
        if (!isNamePart(src[offset])) return null
        var end = offset
        while (end < src.length && isNamePart(src[end])) end++
        return src.substring(offset, end)
    }

    /**
     * The `def` a call names: the module-level one first, else the first declaration
     * of that name anywhere (a method, reached through a receiver).
     */
    private fun declarationOfCallable(tree: VelaSyntaxTree, name: String): VelaSyntaxNode? =
        fileFunction(tree, name)
            ?: findDecl(tree.root) { it.kind == VelaNodeKind.DEF && it.name == name }

    /*
     * `topLevelEntries`, `openParenTokenOf` and `closeParenToken` moved to
     * [VelaSignatures] with the rule they implement: the tree's `param` nodes are the
     * answer when the declaration's parentheses hold exactly as many entries, and the
     * model's own symbol list now asks that same question from the same place.  One
     * reader, so the symbol completion inserts from and the name the hint draws cannot
     * come from different readings of one declaration.
     */

    /**
     * The parameter names of one of the language's own names, or null.
     *
     * A builtin has no declaration in the file, so the language's own table,
     * `VelaModel.BUILTINS`, is the declaration — and this reads the parameter list out
     * of that entry's **signature field**, never out of its prose.  The two used to be
     * one string, and the names were found with `indexOf('(')` over the whole of it:
     * that worked only because every entry happened to begin with its signature, and a
     * description that mentioned a parenthesis first would have had every hint for that
     * builtin named out of prose — with the harness agreeing, because its oracle called
     * this same function.  Null for a name the table does not describe, and for an entry
     * whose signature cannot be read, which is what keeps a call to one from being named
     * by a guess.
     */
    fun builtinParameterNames(name: String): List<String>? {
        val entry = VelaModel.BUILTINS.firstOrNull { it.name == name } ?: return null
        return signatureParameterNames(name, entry.signature)
    }

    /**
     * The parameter names a *signature* writes, or null when the text is not a signature
     * of [name].
     *
     * Exposed rather than private because it is the rule a harness has to be able to ask
     * about: `ParamNames` feeds it a builtin's **prose** and requires null, which is what
     * fails if a parameter list is ever read from anything but a signature again.
     */
    fun signatureParameterNames(name: String, signature: String): List<String>? =
        VelaSignatures.parameterNamesInSignature(name, signature)

    /**
     * The type **written beside the binding** of [name] that is in scope at [at], or null
     * when no local binding of that name is in scope there.
     *
     * This answers the question `VelaNames.structTypeOf` could not, for the most ordinary
     * way a local is written down in this language:
     *
     *     mut p: Vec2 = Vec2(3.0, 4.0)
     *     q: Vec2 = Vec2(1.0, 2.0)
     *     print(p.dot(q))        -- `p.` completed nothing, `p.dot(` opened no popup
     *
     * `VelaModel` declares structs, defs, fields, methods and parameters and **no locals
     * at all**, so a binding is invisible to the model reader and `p.` resolved to nothing
     * -- 29 after-dot positions and 6 parameter-info calls in the corpus were positions
     * `PlatformEntry` could only count.  The type is in the file's own characters
     * (`p: Vec2`), the tree records it on the `decl` node, and this reads it from there:
     * the model path reads the same tree, so the two cannot disagree about a declaration.
     *
     * **Inner-first**, because a binding in an inner block shadows an outer one -- the
     * compiler's rule (SPEC.md 6.2), the rule [declarationFor] case 3 follows for uses,
     * and now the rule `VelaNames.structTypeOf` inherits by asking this.
     *
     * Three answers, and the caller has to tell them apart:
     *
     *   * a type, when the binding writes one (`q: Vec2`);
     *   * the **empty string**, when a binding of that name is in scope but writes no
     *     type.  The compiler refuses such a file -- `binding 'n' has no type annotation`
     *     -- so this is a file being typed, which is exactly when a reader has to be
     *     careful: the local still shadows whatever the outer name meant, so the honest
     *     answer is "the file does not say" and the caller must not fall back to a
     *     parameter's type of the same name;
     *   * null, when no binding of that name is in scope, which is what lets the
     *     parameter and struct-name readings below it run.
     *
     * A `for` variable is deliberately **not** read here: `for i in range(...)` writes no
     * type, and the model's own answer for such a receiver is nothing either, so reading
     * it would change no answer while adding a scope rule (the variable is not in scope
     * in its own `range(...)` header) that nothing has measured.
     */
    fun localBindingType(text: CharSequence, at: Int, name: String): String? {
        val src = text.toString()
        val tree = VelaSyntaxParser.parse(src)
        val chain = chainAt(tree, src, at) ?: return null
        for (node in chain.reversed()) {
            if (node.kind != VelaNodeKind.BLOCK && node.kind != VelaNodeKind.MODULE_BLOCK) continue
            var best = -1
            var bestType = ""
            for (child in node.children) {
                if (child.kind != VelaNodeKind.DECL) continue
                if (child.name != name) continue
                val start = sourceStart(tree, child, src)
                if (start < 0 || start >= at) continue   // declared after this use
                if (start > best) {
                    best = start
                    bestType = child.typeText
                }
            }
            if (best >= 0) return bestType
        }
        return null
    }

    /**
     * The declaration the name at [offset] refers to, or null.
     */
    fun declarationFor(text: CharSequence, offset: Int): VelaTarget? {
        val src = text.toString()
        val tree = VelaSyntaxParser.parse(src)
        val range = nameRangeAt(text, offset) ?: return null
        val name = text.subSequence(range.first, range.last + 1).toString()
        if (name.isEmpty() || name.first().isDigit()) return null
        if (name in VELA_KEYWORDS) return null
        val at = range.first
        val chain = chainAt(tree, src, at) ?: return null

        // 1. A member after a dot.
        if (at > 0 && src[at - 1] == '.') {
            val receiver = identifierEndingAt(src, at - 1) ?: return null
            val owner = structFor(tree, src, chain, at, receiver.text) ?: return null
            return member(tree, owner, name)
        }

        // A name *before* a dot is a struct's own name: `Vec2.zero`, `P.static`.
        val after = range.last + 1
        if (after < src.length && src[after] == '.' && name.first().isUpperCase()) {
            fileStruct(tree, name)?.let { return targetAt(tree, it, VelaTargetKind.STRUCT) }
            fileFunction(tree, name)?.let { return targetAt(tree, it, VelaTargetKind.FUNCTION) }
            return null
        }

        // 2. A loop variable.
        for (node in chain) {
            if (node.kind != VelaNodeKind.FOR) continue
            val tok = nameTokenOf(tree, node)
            if (tok >= 0 && tree.toks[tok].text(src) == name) {
                return targetAt(tree, node, VelaTargetKind.LOOP_VARIABLE)
            }
        }

        // 3. A local binding that precedes this use, innermost block first.
        //
        // `chainAt` returns the chain outermost first, innermost last, so the walk has to
        // run BACKWARDS for the header of this case to be true.  Walking it forwards looked
        // up the outermost matching block first and stopped there, which is the opposite of
        // the language's shadowing rule (SPEC.md 6.2: "Shadowing across blocks stays legal",
        // and the file's own comment one line above).  The cost was measured, not argued:
        // `tests/safety/cases/scope_shadow_across_blocks_ok.vel` has an outer `x` on line 5
        // and an inner `x` on line 7; with the forward walk the `print(x)` on line 8 -- inside
        // the inner block, so bound to the inner declaration by the compiler -- was credited
        // to the OUTER `x` (RenameOracle: "the table claims line 8 (offset 292) ... NOT-REQUIRED"),
        // so a rename of the outer binding rewrote the inner block's use and a rename of the
        // inner one left it behind.  Inner-first puts the inner binding back in charge of the
        // uses inside its own block.
        for (node in chain.reversed()) {
            if (node.kind != VelaNodeKind.BLOCK && node.kind != VelaNodeKind.MODULE_BLOCK) continue
            var best: VelaSyntaxNode? = null
            for (child in node.children) {
                if (child.kind != VelaNodeKind.DECL) continue
                val tok = nameTokenOf(tree, child)
                if (tok < 0) continue
                if (tree.toks[tok].text(src) != name) continue
                if (sourceStart(tree, child, src) >= at) continue      // declared after the use
                if (best == null || sourceStart(tree, child, src) > sourceStart(tree, best!!, src)) {
                    best = child
                }
            }
            if (best != null) return targetAt(tree, best!!, VelaTargetKind.LOCAL)
        }

        // 4. A parameter of the enclosing function or method.
        for (node in chain) {
            if (node.kind != VelaNodeKind.DEF) continue
            for (child in node.children) {
                if (child.kind != VelaNodeKind.PARAM) continue
                val tok = nameTokenOf(tree, child)
                if (tok < 0) continue
                if (tree.toks[tok].text(src) == name) {
                    return targetAt(tree, child, VelaTargetKind.PARAMETER)
                }
            }
        }

        // 5. A call: a variant's construction first (SPEC.md §13 -- `Circle(2.0)` is the
        // shape a call already has, and an arm's pattern `Circle(r) { ... }` is the same
        // shape), then the file's own function, then nothing (a builtin is declared by the
        // language, and a method needs a receiver, which case 1 answered already).
        if (after < src.length && src[after] == '(') {
            fileVariant(tree, name)?.let { return targetAt(tree, it, VelaTargetKind.VARIANT) }
            return callTarget(tree, name)
        }

        // 6. A file-level declaration by that name: a function, a struct, an enum, or a
        // variant.
        fileFunction(tree, name)?.let { return targetAt(tree, it, VelaTargetKind.FUNCTION) }
        // A bare `Vec2`, written as a type.
        fileStruct(tree, name)?.let { return targetAt(tree, it, VelaTargetKind.STRUCT) }
        // A bare `Shape` in a type position (`c: Shape`, `Array[Shape, 4]`), or a
        // payload-free variant written as a value (`Empty`).
        fileEnum(tree, name)?.let { return targetAt(tree, it, VelaTargetKind.ENUM) }
        fileVariant(tree, name)?.let { return targetAt(tree, it, VelaTargetKind.VARIANT) }
        return null
    }

    // ------------------------------------------------------------------ calls

    /**
     * The declaration a call names: a method of the receiver's struct when there is
     * a `.receiver`, else the file's own function, else nothing (a builtin, which is
     * declared by the language).
     */
    private fun callTarget(tree: VelaSyntaxTree, name: String): VelaTarget? =
        fileFunction(tree, name)?.let { targetAt(tree, it, VelaTargetKind.FUNCTION) }

    /** A struct member by name: a field first, then a method. */
    private fun member(tree: VelaSyntaxTree, owner: VelaSyntaxNode, name: String): VelaTarget? {
        for (child in owner.children) {
            if (velaIsFieldMember(child) && child.name == name) {
                return targetAt(tree, child, VelaTargetKind.FIELD)
            }
        }
        for (child in owner.children) {
            if (child.kind == VelaNodeKind.DEF && child.name == name) {
                return targetAt(tree, child, VelaTargetKind.METHOD)
            }
        }
        return null
    }

    // ------------------------------------------------------------- the helpers

    /** A name occurrence and the name it spells. */
    private class Word(val text: String, val start: Int, val end: Int)

    /** The identifier that ends just before [at] (a dot position), or null. */
    private fun identifierEndingAt(src: String, at: Int): Word? {
        var i = at - 1
        while (i >= 0 && isSpace(src[i])) i--
        val end = i + 1
        while (i >= 0 && isNamePart(src[i])) i--
        val start = i + 1
        if (end <= start) return null
        return Word(src.substring(start, end), start, end)
    }

    /** `Vec2` -> the struct whose name is `name`, at file level or in any struct. */
    private fun fileStruct(tree: VelaSyntaxTree, name: String): VelaSyntaxNode? =
        findDecl(tree.root) { it.kind == VelaNodeKind.STRUCT && it.name == name }

    /** `Shape` -> the enum whose name is that -- SPEC.md §13, a type like a struct. */
    private fun fileEnum(tree: VelaSyntaxTree, name: String): VelaSyntaxNode? =
        findDecl(tree.root) { it.kind == VelaNodeKind.ENUM && it.name == name }

    /**
     * `Circle` -> the variant whose name is that, in whichever enum declares it.
     *
     * A *file-global* lookup, and it is the language that makes it unambiguous rather than
     * this function guessing: SPEC.md §13 says "a variant name is file-global, because v1
     * has no expected-type machinery ... a second enum declaring the same variant name is
     * refused, naming both".  So on any file the compiler accepts there is at most one
     * variant of a given name, and on a file it refuses the answer does not matter.
     */
    private fun fileVariant(tree: VelaSyntaxTree, name: String): VelaSyntaxNode? =
        findDecl(tree.root) { it.kind == VelaNodeKind.VARIANT && it.name == name }

    /**
     * A `def` written at file level with this name.
     *
     * Module level only, deliberately: a method is reached through its receiver, and
     * a bare `dot(...)` inside a struct is not a call the compiler accepts, so
     * answering with a method here would be a target for a name that has none.
     */
    private fun fileFunction(tree: VelaSyntaxTree, name: String): VelaSyntaxNode? {
        val module = tree.root.children.firstOrNull { it.kind == VelaNodeKind.MODULE_BLOCK }
            ?: return null
        for (child in module.children) {
            if (child.kind == VelaNodeKind.DEF && child.name == name) return child
        }
        return null
    }

    private fun findDecl(n: VelaSyntaxNode, match: (VelaSyntaxNode) -> Boolean): VelaSyntaxNode? {
        if (match(n)) return n
        for (c in n.children) {
            val found = findDecl(c, match)
            if (found != null) return found
        }
        return null
    }

    /**
     * The struct a receiver expression denotes, or null when the file does not say.
     *
     * `self` is the enclosing struct; a parameter or local whose declared type names
     * a struct in this file is that struct; a capitalised name is a struct's own name
     * (the static reading).  Anything else -- a call result, an element, a field of a
     * field -- is *not* resolvable from the text, and answers null.
     */
    private fun structFor(tree: VelaSyntaxTree, src: String, chain: List<VelaSyntaxNode>,
                          at: Int, receiver: String): VelaSyntaxNode? {
        if (receiver == "self") {
            for (node in chain) if (node.kind == VelaNodeKind.STRUCT) return node
            return null
        }
        // A parameter of the enclosing function, or a local, whose type is a struct.
        for (node in chain) {
            if (node.kind != VelaNodeKind.DEF && node.kind != VelaNodeKind.BLOCK &&
                node.kind != VelaNodeKind.MODULE_BLOCK
            ) {
                continue
            }
            for (child in node.children) {
                if (child.kind != VelaNodeKind.PARAM && child.kind != VelaNodeKind.DECL) continue
                if (child.name != receiver) continue
                val type = child.typeText.trim().removePrefix("mut ").trim()
                if (type.isEmpty()) continue
                fileStruct(tree, type)?.let { return it }
            }
        }
        if (receiver.firstOrNull()?.isUpperCase() == true) return fileStruct(tree, receiver)
        return null
    }

    /** The chain of nodes containing [at], outermost first, innermost last. */
    private fun chainAt(tree: VelaSyntaxTree, src: String, at: Int): List<VelaSyntaxNode>? {
        val out = ArrayList<VelaSyntaxNode>(16)
        var node = tree.root
        out.add(node)
        while (true) {
            var next: VelaSyntaxNode? = null
            for (child in node.children) {
                val start = sourceStart(tree, child, src)
                val end = sourceEnd(tree, child, src)
                if (start < 0 || end < 0) continue
                if (at >= start && at < end) {
                    next = child
                    break
                }
            }
            if (next == null) return out
            out.add(next)
            node = next
        }
    }

    /** A node's first source offset, or -1. */
    private fun sourceStart(tree: VelaSyntaxTree, n: VelaSyntaxNode, src: String): Int {
        val i = n.startTok
        if (i >= 0 && i < tree.toks.size) return tree.toks[i].start
        if (n.charStart >= 0) return n.charStart
        var best = -1
        for (c in n.children) {
            val s = sourceStart(tree, c, src)
            if (s >= 0 && (best < 0 || s < best)) best = s
        }
        return best
    }

    /** The offset just past a node's last token, or -1. */
    private fun sourceEnd(tree: VelaSyntaxTree, n: VelaSyntaxNode, src: String): Int {
        val i = n.endTok
        if (i >= 0 && i < tree.toks.size) return tree.toks[i].end
        if (n.charEnd >= 0) return n.charEnd
        var best = -1
        for (c in n.children) {
            val e = sourceEnd(tree, c, src)
            if (e > best) best = e
        }
        return best
    }

    /** The token index of a declaration's *name*: after `def`, else the first plain name. */
    private fun nameTokenOf(tree: VelaSyntaxTree, n: VelaSyntaxNode): Int {
        val from = if (n.startTok >= 0) n.startTok else 0
        val to = if (n.endTok >= 0) n.endTok else tree.toks.size - 1
        if (n.kind == VelaNodeKind.DEF) {
            var i = from
            while (i <= to && i < tree.toks.size) {
                val t = tree.toks[i]
                if (t.kind == VelaTokKind.NAME && t.code == VelaKw.DEF) {
                    var j = i + 1
                    while (j <= to && j < tree.toks.size) {
                        val u = tree.toks[j]
                        if (u.kind == VelaTokKind.NAME && u.code == 0 && u.start < u.end) return j
                        j++
                    }
                    return -1
                }
                i++
            }
            return -1
        }
        var i = from
        while (i <= to && i < tree.toks.size) {
            val t = tree.toks[i]
            if (t.kind == VelaTokKind.NAME && t.code == 0 && t.start < t.end) return i
            i++
        }
        return -1
    }


    private fun targetAt(tree: VelaSyntaxTree, node: VelaSyntaxNode, kind: VelaTargetKind): VelaTarget? {
        val tok = nameTokenOf(tree, node)
        if (tok < 0) return null
        return VelaTarget(kind, node.name, tree.toks[tok].start, tree.toks[tok].end)
    }

    private fun VelaTok.text(src: String): String =
        if (start >= end || end > src.length) "" else src.substring(start, end)

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    /**
     * The range of the name [offset] is on, or null.
     *
     * One past the end of a name still belongs to it -- that is where the platform
     * puts a caret after `fib<caret>` -- and nothing else does: a position that only
     * *touches* a word is on nothing.
     */
    fun nameRangeAt(text: CharSequence, offset: Int): IntRange? {
        val end = offset.coerceIn(0, text.length)
        val inside = end < text.length && isNamePart(text[end])
        if (!inside && (end == 0 || !isNamePart(text[end - 1]))) return null
        var start = if (inside) end else end - 1
        var stop = if (inside) end + 1 else end
        while (start > 0 && isNamePart(text[start - 1])) start--
        while (stop < text.length && isNamePart(text[stop])) stop++
        if (text[start].isDigit()) return null
        return start until stop
    }
}

