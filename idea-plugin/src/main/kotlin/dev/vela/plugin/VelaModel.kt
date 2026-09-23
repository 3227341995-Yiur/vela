package dev.vela.plugin

import com.intellij.lexer.Lexer
import java.util.concurrent.ConcurrentHashMap

/**
 * What a Vela file declares, read with the *lexer*.
 *
 * The plugin's rule is that the compiler is the only thing that decides what a
 * program *means*.  This file does not decide anything: it answers "what names
 * exist, where, and what is written beside them", which is a question about
 * characters in a buffer — the same kind of question the platform's own word
 * scanner answers for every language, and the kind the lexer can answer without a
 * parser, without starting a process, and without a stale index.
 *
 * Everything the plugin offers that is *not* meaning — the structure view,
 * completion, hover, parameter info — is built from this one model, so they
 * cannot disagree with each other about where `Vec2` is.
 *
 * The compiler is still asked about meaning: `VelaExternalAnnotator` runs
 * `vm.exe check` and draws exactly what it says, and the syntax-tree tool window
 * shows exactly what `vm.exe parse` prints.
 */

enum class VelaSymbolKind(val title: String) {
    STRUCT("struct"),
    METHOD("def"),
    FUNCTION("def"),
    FIELD("field"),
    PARAMETER("param"),

    /*
     * SPEC.md §13.  An enum is a *type* like a struct, and a variant is a name of its
     * own: a variant name is file-global ("Every variant is reachable by name, and only
     * one enum may claim one"), and `Circle(2.0)` is a call while `Empty` is a bare
     * name -- so a variant is neither a struct nor a function, and giving it its own
     * kind is what keeps the completion from writing `Empty()`.
     */
    ENUM("enum"),
    VARIANT("variant"),
}

data class VelaSymbol(
    val kind: VelaSymbolKind,
    val name: String,
    /** What is written beside the name: a field's type, a callable's signature. */
    val detail: String,
    /** A parameter's type, for parameter info and hover. */
    val type: String,
    /** 1-based, like the compiler's diagnostics. */
    val line: Int,
    /** Index of the enclosing declaration in the same list, or -1. */
    val parent: Int,
    /**
     * The parameter names this callable declares, **read from a declaration** — the
     * tree for a definition in the file ([VelaSignatures.declaredParameterNames]), the
     * declared signature for one of the language's own names
     * ([VelaSignatures.parameterNamesInSignature]).
     *
     * Null means the parameter list could not be read, and the answer to "which name
     * goes with this argument?" is then **nothing**: completion inserts `()`, which is
     * honest, rather than a name taken out of [detail].  This field exists because
     * reading [detail] did exactly that — `def f(s, s, s) -> int` is refused by the
     * compiler for its unannotated parameters, its `detail` is the text `f(s, s, s)`,
     * and splitting that text gave `["s", "s", "s"]`, which completion wrote into the
     * user's document as `(s, s, s)`.
     */
    val parameters: List<String>? = null,
) {
    val isCallable: Boolean
        get() = kind == VelaSymbolKind.FUNCTION || kind == VelaSymbolKind.METHOD
}

/**
 * One of the language's own functions, as *data* rather than as one prose string.
 *
 * [signature] is the declaration — `substr(s: str, from: int, to: int) -> str` — and
 * it is the only thing a parameter list for a builtin may be read from.  [prose] is
 * the sentence a hover shows, and it is deliberately a *separate field*: the table used
 * to be `name to "signature — prose"`, and `builtinParameterNames` found the parameter
 * list with `indexOf('(')` over that whole string.  It worked only because every entry
 * happened to begin with its signature; one description that mentions a parenthesis
 * first would have had names read out of prose, and no harness would have noticed
 * because the oracle called the same function.
 */
class VelaBuiltin(
    val name: String,
    val signature: String,
    val prose: String,
) {
    /** The signature and the sentence together, which is what a hover or a tail shows. */
    val description: String
        get() = if (prose.isEmpty()) signature else "$signature — $prose"
}

private data class Tok(
    val text: String,
    val start: Int,
    val end: Int,
    val line: Int,
    val isKeyword: Boolean,
    val isIdentifier: Boolean,
)

object VelaModel {

    /**
     * Names the language provides, with the arity the compiler enforces in
     * `selfhost/parts/resolve.vel`, held as [VelaBuiltin]s.
     *
     * Each entry separates the two things the table used to hold in one string: the
     * **signature**, which is the declaration a parameter name is read from, and the
     * **prose**, which is what a hover says about it.  Keeping them apart is a
     * correction, not a tidy-up: `builtinParameterNames` found the parameter list with
     * `indexOf('(')` over the combined string and worked only because every entry began
     * with its signature.  A description that mentioned a parenthesis before the
     * signature would have named arguments out of prose, and the harness would not have
     * seen it — its oracle called the same function.
     */
    val BUILTINS: List<VelaBuiltin> = listOf(
        VelaBuiltin("print", "print(...) -> None", "write values, separated by spaces"),
        VelaBuiltin("emit_str", "emit_str(s: str) -> None", "write a string, no newline"),
        VelaBuiltin("emit_int", "emit_int(n: int) -> None", "write an integer"),
        VelaBuiltin("emit_float", "emit_float(x: float) -> None", "write a float"),
        VelaBuiltin("emit_nl", "emit_nl() -> None", "write a newline"),
        VelaBuiltin("warn_str", "warn_str(s: str) -> None", "write a string to stderr"),
        VelaBuiltin("warn_int", "warn_int(n: int) -> None", "write an integer to stderr"),
        VelaBuiltin("warn_nl", "warn_nl() -> None", "write a newline to stderr"),
        VelaBuiltin("len", "len(a: Array[T, N] | str) -> int", "the length, a constant for arrays"),
        VelaBuiltin("to_float", "to_float(n: int) -> float", ""),
        VelaBuiltin("to_int", "to_int(x: float) -> int", "truncates toward zero"),
        VelaBuiltin("sqrt", "sqrt(x: float) -> float", ""),
        VelaBuiltin("fabs", "fabs(x: float) -> float", ""),
        VelaBuiltin("floor", "floor(x: float) -> float", ""),
        VelaBuiltin("pow", "pow(b: float, e: float) -> float", ""),
        VelaBuiltin("abs", "abs(n: int) -> int", ""),
        VelaBuiltin("min_int", "min_int(a: int, b: int) -> int", ""),
        VelaBuiltin("max_int", "max_int(a: int, b: int) -> int", ""),
        VelaBuiltin("min_float", "min_float(a: float, b: float) -> float", ""),
        VelaBuiltin("max_float", "max_float(a: float, b: float) -> float", ""),
        VelaBuiltin("bytes_at", "bytes_at(s: str, i: int) -> int", "one byte, checked"),
        VelaBuiltin("substr", "substr(s: str, a: int, b: int) -> str", "a slice, not a copy"),
        VelaBuiltin("concat", "concat(a: str, b: str) -> str", "the only way to join strings"),
        VelaBuiltin("unescape", "unescape(s: str) -> str", ""),
        VelaBuiltin("intern", "intern(s: str) -> int", "a stable handle for a string"),
        VelaBuiltin("interned", "interned(h: int) -> str", ""),
        VelaBuiltin("argc", "argc() -> int", "the program's argument count"),
        VelaBuiltin("arg", "arg(i: int) -> str", "argument i, checked"),
        VelaBuiltin("read_text", "read_text(path: str) -> str", "the whole file, or empty"),
        VelaBuiltin("write_text", "write_text(path: str, s: str) -> bool", ""),
        VelaBuiltin("panic", "panic(msg: str) -> None", "stop the program, with the message"),
        VelaBuiltin("now", "now() -> float", "seconds, for timing"),
        VelaBuiltin("run_command", "run_command(cmd: str) -> int",
            "exit status, -1 if the line is too long"),
        VelaBuiltin("env", "env(name: str) -> str", "an environment variable, empty when unset"),
        VelaBuiltin("range", "range(from: int, to: int)", "the only loop range; step 1"),
    )

    /** Tokenising is the only cost here, and files are re-lexed on every keystroke. */
    private val cache = ConcurrentHashMap<String, List<VelaSymbol>>()

    fun symbols(text: CharSequence): List<VelaSymbol> {
        // A tiny cache keyed by content: the platform asks for the structure view,
        // completion and hover within the same keystroke, and each one lexes the
        // whole file otherwise.  The key is hashed *in place* rather than via
        // toString(): the compiler's own source is 338 KB, and allocating a copy
        // of it on every keystroke to compute a cache key would cost more than
        // the lexing it is meant to avoid.
        val key = contentKey(text)
        cache[key]?.let { return it }
        val built = scanFromTree(text)
        if (cache.size > 32) cache.clear()
        cache[key] = built
        return built
    }

    /**
     * The declaration list, read from the plugin's parser's tree.
     *
     * This is the change that moved six features onto the real tree at once: the
     * structure view, completion, go-to-declaration, parameter info, the
     * documentation provider and the parameter-name inlay hints all read *this*
     * list, so all six now ask a tree that `ast-diff.ps1` holds to `vm.exe parse`
     * (107 files, 115,459 node lines, no difference) rather than reconstructing
     * declarations from a token stream by counting braces.
     *
     * What changes for the reader, and it is not cosmetic: a nested `def` inside a
     * function body, a `struct` field written as `mut x: T`, a field whose type is
     * `Array[int, 4]`, and a declaration that is only *partly* written (the state a
     * file is in whenever someone is typing) are all things the token scan had to
     * guess at and the tree simply says.  `SymbolDiff` measures the two against each
     * other over the corpus, so the difference is a number and not a claim.
     */
    private fun scanFromTree(text: CharSequence): List<VelaSymbol> {
        val src = text.toString()
        val tree = VelaSyntaxParser.parse(src)
        val out = ArrayList<VelaSymbol>(32)
        val module = tree.root.children.firstOrNull { it.kind == VelaNodeKind.MODULE_BLOCK }
            ?: return out
        for (statement in module.children) {
            when (statement.kind) {
                VelaNodeKind.STRUCT -> readStructFromTree(statement, tree, text, out, -1)
                VelaNodeKind.DEF -> readCallableFromTree(statement, tree, text, out, -1,
                    VelaSymbolKind.FUNCTION)
                VelaNodeKind.ENUM -> readEnumFromTree(statement, tree, text, out, -1)
                else -> Unit
            }
        }
        return out
    }

    /**
     * An enum and its variants, in source order -- SPEC.md §13.
     *
     * A variant's payload fields are deliberately **not** symbols of this model: the
     * language has no way to name a payload field (an arm binds new names positionally),
     * so nothing in a file refers to one, and a symbol for it would be a declaration no
     * reader could navigate to.
     *
     * The payload's field names *do* travel on the variant symbol, as its `parameters`,
     * because they are exactly what completion must write between the parentheses:
     * `Circle` becomes `Circle(radius)`.  A payload-free variant carries an **empty**
     * list, which is the honest reading of "this variant declares no fields" -- `null`
     * would say the list could not be read, and the completion would then have to refuse
     * to write anything, which is right for `Empty` and wrong for `Circle`.
     */
    private fun readEnumFromTree(n: VelaSyntaxNode, tree: VelaSyntaxTree, text: CharSequence,
                                 out: ArrayList<VelaSymbol>, parent: Int) {
        val me = out.size
        out.add(
            VelaSymbol(
                VelaSymbolKind.ENUM, n.name, "enum " + n.name, "",
                lineAt(tree, n, text), parent,
            )
        )
        for (v in n.children) {
            if (v.kind != VelaNodeKind.VARIANT) continue
            val fields = v.children.filter { velaIsVariantField(it) }
            val detail = if (fields.isEmpty()) v.name
            else v.name + "(" + fields.joinToString(", ") { it.name + ": " + it.typeText } + ")"
            out.add(
                VelaSymbol(
                    VelaSymbolKind.VARIANT, v.name, detail, "",
                    lineAt(tree, v, text), me,
                    fields.map { it.name },
                )
            )
        }
    }

    /**
     * A struct, and the members written between its braces -- in source order, which
     * is the order the reader wrote them.  (The compiler's *dump* prints fields
     * first and methods second; that is a printer's choice, not the file's order.)
     */
    private fun readStructFromTree(n: VelaSyntaxNode, tree: VelaSyntaxTree, text: CharSequence,
                           out: ArrayList<VelaSymbol>, parent: Int) {
        val me = out.size
        out.add(
            VelaSymbol(
                VelaSymbolKind.STRUCT, n.name, "struct " + n.name, "",
                lineAt(tree, n, text), parent,
            )
        )
        for (member in n.children) {
            if (velaIsFieldMember(member)) {
                out.add(
                    VelaSymbol(
                        VelaSymbolKind.FIELD, member.name, member.typeText, member.typeText,
                        lineAt(tree, member, text), me,
                    )
                )
            } else if (member.kind == VelaNodeKind.DEF) {
                readCallableFromTree(member, tree, text, out, me, VelaSymbolKind.METHOD)
            }
        }
    }

    /**
     * A callable and its parameters.
     *
     * `detail` is the signature *as written*, sliced out of the source from the name
     * to the `)` that closes the parameter list, because that is what the reader
     * wants to see in the structure view, hover and parameter info -- reconstructing
     * it would silently reformat the file's own spacing.  The slice is found through
     * the parser's token list, which is the tree's own record of which tokens belong
     * to this declaration, so a multi-line parameter list is sliced correctly.
     */
    private fun readCallableFromTree(n: VelaSyntaxNode, tree: VelaSyntaxTree, text: CharSequence,
                             out: ArrayList<VelaSymbol>, parent: Int, kind: VelaSymbolKind) {
        val me = out.size
        val nameTok = firstPlainNameToken(tree, n)
        val line = if (nameTok >= 0) lineOfOffset(text, tree.toks[nameTok].start)
        else lineAt(tree, n, text)
        val params = n.children.filter { it.kind == VelaNodeKind.PARAM }
        val ret = n.retType.ifEmpty { "None" }
        val close = closeParenToken(tree, n)
        val sig = if (nameTok >= 0 && close >= 0) {
            text.subSequence(tree.toks[nameTok].start, tree.toks[close].end).toString()
                .replace('\n', ' ').replace(Regex(" +"), " ") + " -> " + ret
        } else {
            n.name + " -> " + ret
        }
        // The parameter names come from the tree -- the same reader the hint and the
        // popup use -- and are null when the declaration's parameter list cannot be
        // read.  Null is what stops completion writing `(s, s, s)` into the document;
        // nothing here reads `sig`, which is display text.
        val declared = VelaSignatures.declaredParameterNames(tree, text, n)
        out.add(VelaSymbol(kind, n.name, sig, ret, line, parent, declared))
        for (p in params) {
            val pName = firstPlainNameToken(tree, p)
            val pLine = if (pName >= 0) lineOfOffset(text, tree.toks[pName].start)
            else lineAt(tree, p, text)
            val mut = if (p.flags and 1 != 0) "mut " else ""
            out.add(
                VelaSymbol(
                    VelaSymbolKind.PARAMETER, p.name,
                    mut + p.name + ": " + p.typeText, p.typeText, pLine, me,
                )
            )
        }
    }

    /**
     * The token index of a declaration's *name*, or -1.
     *
     * Not simply "the first identifier": `extern c def abs(...)` has one identifier
     * before the name -- the language the symbol comes from -- and taking it made
     * every `extern` declaration's signature read `c def abs(x: i32) -> i32` in the
     * structure view and in hover, which `SymbolDiff` reported on 18 files.  The name
     * is the first plain identifier *after* the `def` keyword.
     */
    private fun firstPlainNameToken(tree: VelaSyntaxTree, n: VelaSyntaxNode): Int {
        val from = if (n.startTok >= 0) n.startTok else 0
        val to = if (n.endTok >= 0) n.endTok else tree.toks.size - 1
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
        // A parameter has no `def` keyword: its name is its first plain identifier.
        i = from
        while (i <= to && i < tree.toks.size) {
            val t = tree.toks[i]
            if (t.kind == VelaTokKind.NAME && t.code == 0 && t.start < t.end) return i
            i++
        }
        return -1
    }

    /** The token index of the `)` that closes a declaration's parameter list, or -1. */
    private fun closeParenToken(tree: VelaSyntaxTree, n: VelaSyntaxNode): Int {
        val from = if (n.startTok >= 0) n.startTok else 0
        val to = if (n.endTok >= 0) n.endTok else tree.toks.size - 1
        var i = from
        var depth = 0
        var opened = false
        while (i <= to && i < tree.toks.size) {
            val t = tree.toks[i]
            if (t.kind == VelaTokKind.OP && t.code == VelaOps.LPAREN) {
                depth++
                opened = true
            } else if (t.kind == VelaTokKind.OP && t.code == VelaOps.RPAREN) {
                depth--
                if (opened && depth == 0) return i
            }
            i++
        }
        return -1
    }

    /** The 1-based line a node starts on: where its first token begins. */
    private fun lineAt(tree: VelaSyntaxTree, n: VelaSyntaxNode, text: CharSequence): Int {
        val i = n.startTok
        if (i >= 0 && i < tree.toks.size) return lineOfOffset(text, tree.toks[i].start)
        return 1
    }

    /** 1-based line of a character offset, the compiler's own convention. */
    private fun lineOfOffset(text: CharSequence, offset: Int): Int {
        var line = 1
        var i = 0
        val end = if (offset > text.length) text.length else offset
        while (i < end) {
            if (text[i] == '\n') line++
            i++
        }
        return line
    }

    private fun contentKey(text: CharSequence): String {
        var h = 1125899906842597L
        for (i in 0 until text.length) h = 31 * h + text[i].code
        return text.length.toString() + ":" + h
    }

    /** The declaration whose *name* covers `offset`. */
    fun symbolAt(text: CharSequence, offset: Int): VelaSymbol? {
        val toks = tokenize(text)
        for (i in toks.indices) {
            val t = toks[i]
            if (!t.isIdentifier) continue
            if (offset < t.start || offset > t.end) continue
            return declarationFor(text, toks, i)
        }
        return null
    }

    /** The callable whose body contains `offset` — what parameter info needs. */
    fun enclosingCallable(text: CharSequence, offset: Int): VelaSymbol? {
        val all = symbols(text)
        val line = lineOf(text, offset)
        var best: VelaSymbol? = null
        for (s in all) {
            if (!s.isCallable) continue
            if (s.line > line) continue
            // the innermost callable starting at or before this line
            val better = best == null || s.line > best.line
            if (better) best = s
        }
        return best
    }

    /** The struct whose body contains `offset`, so `self.` can be completed. */
    fun enclosingStruct(text: CharSequence, offset: Int): VelaSymbol? {
        val line = lineOf(text, offset)
        val all = symbols(text)
        var best: VelaSymbol? = null
        for (s in all) {
            if (s.kind != VelaSymbolKind.STRUCT) continue
            if (s.line <= line && (best == null || s.line > best.line)) best = s
        }
        return best
    }

    /** Members of `self` inside the struct at `offset`: fields, then methods. */
    fun membersOf(text: CharSequence, struct: VelaSymbol): List<VelaSymbol> {
        val all = symbols(text)
        val idx = all.indexOf(struct)
        if (idx < 0) return emptyList()
        return all.filter { it.parent == idx }
    }

    /** Everything a name at `offset` could mean: the file's own, then the language's. */
    fun visibleSymbols(text: CharSequence, offset: Int): List<VelaSymbol> {
        val all = symbols(text)
        val line = lineOf(text, offset)
        val struct = enclosingStruct(text, offset)
        val out = ArrayList<VelaSymbol>()
        for (s in all) {
            when (s.kind) {
                VelaSymbolKind.STRUCT, VelaSymbolKind.FUNCTION -> out.add(s)
                // An enum is a type the whole file shares, like a struct, and a variant
                // name is file-global by the language's own rule (SPEC.md 13: "Every
                // variant is reachable by name, and only one enum may claim one"), so
                // both are visible everywhere rather than only inside a body.
                VelaSymbolKind.ENUM, VelaSymbolKind.VARIANT -> out.add(s)
                VelaSymbolKind.FIELD, VelaSymbolKind.METHOD ->
                    if (struct != null && s.parent == all.indexOf(struct)) out.add(s)
                VelaSymbolKind.PARAMETER ->
                    if (s.line <= line) out.add(s)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ scanning

    private fun tokenize(text: CharSequence): List<Tok> {
        val lexer: Lexer = VelaLexer()
        lexer.start(text)
        val out = ArrayList<Tok>(256)
        var line = 1
        var seen = 0
        while (lexer.tokenType != null) {
            val s = lexer.tokenStart
            val e = lexer.tokenEnd
            val type = lexer.tokenType
            if (type != null) {
                // count newlines up to the token start, so a token's line is where
                // it begins (the compiler's own rule for diagnostics)
                for (i in seen until s) if (text[i] == '\n') line++
                seen = s
                val body = text.subSequence(s, e).toString()
                val isSpace = body.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
                if (!isSpace && type != VelaTokenTypes.COMMENT) {
                    out.add(
                        Tok(
                            text = body, start = s, end = e, line = line,
                            isKeyword = type == VelaTokenTypes.KEYWORD,
                            isIdentifier = type == VelaTokenTypes.IDENTIFIER,
                        )
                    )
                }
                for (i in s until e) if (text[i] == '\n') line++
                seen = e
            }
            lexer.advance()
        }
        return out
    }

    /**
     * The declaration list as the *old* token scan produced it.
     *
     * Kept only so the conversion to the tree can be measured: `SymbolDiff` runs both
     * over the whole corpus and reports every file where they disagree, so "the
     * features now read the tree" is a table of differences rather than a claim in a
     * comment.  Nothing in the plugin calls this; if the differential is retired, this
     * method and the four private helpers below it should go with it.
     */
    fun referenceSymbols(text: CharSequence): List<VelaSymbol> = scan(text.toString())

    private fun scan(text: CharSequence): List<VelaSymbol> {
        val toks = tokenize(text)
        val out = ArrayList<VelaSymbol>(32)
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t.isKeyword && t.text == "struct") {
                i = readStruct(text, toks, i, out, -1)
                continue
            }
            if (t.isKeyword && t.text == "def") {
                i = readCallable(text, toks, i, out, -1, VelaSymbolKind.FUNCTION)
                continue
            }
            i++
        }
        return out
    }

    private fun readStruct(text: CharSequence, toks: List<Tok>, at: Int,
                           out: ArrayList<VelaSymbol>, parent: Int): Int {
        var i = at + 1
        if (i >= toks.size || !toks[i].isIdentifier) return at + 1
        val name = toks[i].text
        val me = out.size
        out.add(VelaSymbol(VelaSymbolKind.STRUCT, name, "struct $name", "", toks[at].line, parent))
        i++
        while (i < toks.size && toks[i].text != "{") i++
        if (i >= toks.size) return i
        i++
        while (i < toks.size) {
            val t = toks[i]
            if (t.text == "}") return i + 1
            if (t.isKeyword && t.text == "def") {
                i = readCallable(text, toks, i, out, me, VelaSymbolKind.METHOD)
                continue
            }
            // a field: `name: Type`
            if (t.isIdentifier && i + 2 < toks.size && toks[i + 1].text == ":" &&
                toks[i + 2].isIdentifier
            ) {
                var j = i + 2
                var type = toks[j].text
                // `Array[int, 4]` and friends: take the brackets as written
                if (type == "Array" && j + 1 < toks.size && toks[j + 1].text == "[") {
                    val open = j + 1
                    var depth = 0
                    j = open
                    while (j < toks.size) {
                        if (toks[j].text == "[") depth++
                        if (toks[j].text == "]") {
                            depth--
                            if (depth == 0) break
                        }
                        j++
                    }
                    type = text.subSequence(toks[open].start, toks[j].end).toString()
                }
                out.add(
                    VelaSymbol(
                        VelaSymbolKind.FIELD, t.text, type, type, t.line, me,
                    )
                )
                i = j + 1
                continue
            }
            i++
        }
        return i
    }

    private fun readCallable(text: CharSequence, toks: List<Tok>, at: Int,
                             out: ArrayList<VelaSymbol>, parent: Int,
                             kind: VelaSymbolKind): Int {
        var i = at + 1
        if (i >= toks.size || !toks[i].isIdentifier) return at + 1
        val name = toks[i].text
        val nameLine = toks[i].line
        val nameTok = toks[i]
        val me = out.size
        i++
        if (i >= toks.size || toks[i].text != "(") {
            out.add(VelaSymbol(kind, name, name, "", nameLine, parent))
            return i
        }
        // parameters, and the text of the whole `( ... )` for the signature
        val open = i
        val params = ArrayList<VelaSymbol>()
        i++
        var depth = 1
        while (i < toks.size && depth > 0) {
            if (toks[i].text == "(") depth++
            if (toks[i].text == ")") {
                depth--
                if (depth == 0) break
            }
            var j = i
            var mut = false
            if (toks[j].isKeyword && toks[j].text == "mut") {
                mut = true
                j++
            }
            if (j + 2 < toks.size && toks[j].isIdentifier && toks[j + 1].text == ":" &&
                toks[j + 2].isIdentifier
            ) {
                var k = j + 2
                var type = toks[k].text
                if (type == "Array" && k + 1 < toks.size && toks[k + 1].text == "[") {
                    val o = k + 1
                    var d = 0
                    k = o
                    while (k < toks.size) {
                        if (toks[k].text == "[") d++
                        if (toks[k].text == "]") {
                            d--
                            if (d == 0) break
                        }
                        k++
                    }
                    type = text.subSequence(toks[o].start, toks[k].end).toString()
                }
                params.add(
                    VelaSymbol(
                        VelaSymbolKind.PARAMETER, toks[j].text,
                        (if (mut) "mut " else "") + toks[j].text + ": " + type,
                        type, toks[j].line, parent,
                    )
                )
                i = k + 1
                while (i < toks.size && toks[i].text != "," && toks[i].text != ")") i++
                if (i < toks.size && toks[i].text == ",") i++
                continue
            }
            i++
        }
        val close = if (i < toks.size) i else open
        var ret = "None"
        var j = close + 1
        // `->` is one operator token in this lexer (`selfhost/vela.vel` lexes it
        // whole), and looking for `-` then `>` found nothing — which made every
        // typed function hover as `-> None`.  Accept both spellings: a lexer may
        // split it, and the check costs one comparison.
        val arrow = j < toks.size && toks[j].text == "->"
        if (arrow || (j + 1 < toks.size && toks[j].text == "-" && toks[j + 1].text == ">")) {
            j += if (arrow) 1 else 2
            if (j < toks.size) {
                ret = toks[j].text
                if (ret == "Array" && j + 1 < toks.size && toks[j + 1].text == "[") {
                    val o = j + 1
                    var d = 0
                    var k = o
                    while (k < toks.size) {
                        if (toks[k].text == "[") d++
                        if (toks[k].text == "]") {
                            d--
                            if (d == 0) break
                        }
                        k++
                    }
                    ret = text.subSequence(toks[o].start, toks[k].end).toString()
                }
            }
        }
        val sig = text.subSequence(nameTok.start, toks[close].end).toString()
            .replace('\n', ' ').replace(Regex(" +"), " ") + " -> " + ret
        // THE LEGACY PATH KEEPS THE LEGACY READING, deliberately.  This token scan is
        // what 0.1.3 shipped and `HintDupes --old` measures it against the tree; its
        // parameter names come from its own token scan, which is exactly the reading
        // that produced `(s, s, s)`.  Filling this field with the tree's answer here
        // would make the old path look fixed, and the comparison would measure nothing.
        out.add(VelaSymbol(kind, name, sig, ret, nameLine, parent, params.map { it.name }))
        for (p in params) out.add(p.copy(parent = me))
        // skip to the end of the body, so a nested `def` is not read as a second one
        var k = close
        while (k < toks.size && toks[k].text != "{") k++
        if (k >= toks.size) return k
        var d2 = 0
        while (k < toks.size) {
            if (toks[k].text == "{") d2++
            if (toks[k].text == "}") {
                d2--
                if (d2 == 0) return k + 1
            }
            k++
        }
        return k
    }

    private fun declarationFor(text: CharSequence, toks: List<Tok>, idx: Int): VelaSymbol? {
        val t = toks[idx]
        val all = symbols(text)
        for (s in all) {
            if (s.name == t.text && s.line == t.line) return s
        }
        // fall back to a name match on the nearest preceding line
        var best: VelaSymbol? = null
        for (s in all) {
            if (s.name != t.text) continue
            if (s.line > t.line) continue
            if (best == null || s.line > best.line) best = s
        }
        return best
    }

    fun lineOf(text: CharSequence, offset: Int): Int {
        var line = 1
        var i = 0
        val n = minOf(offset, text.length)
        while (i < n) {
            if (text[i] == '\n') line++
            i++
        }
        return line
    }
}
