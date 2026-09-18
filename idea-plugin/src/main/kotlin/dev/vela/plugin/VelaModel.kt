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
) {
    val isCallable: Boolean
        get() = kind == VelaSymbolKind.FUNCTION || kind == VelaSymbolKind.METHOD
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
     * `selfhost/parts/resolve.vel`.  Kept as data rather than as prose so
     * completion can offer them and hover can explain them.
     */
    val BUILTINS: List<Pair<String, String>> = listOf(
        "print" to "print(...) -> None — write values, separated by spaces",
        "emit_str" to "emit_str(s: str) -> None — write a string, no newline",
        "emit_int" to "emit_int(n: int) -> None — write an integer",
        "emit_float" to "emit_float(x: float) -> None — write a float",
        "emit_nl" to "emit_nl() -> None — write a newline",
        "warn_str" to "warn_str(s: str) -> None — write a string to stderr",
        "warn_int" to "warn_int(n: int) -> None — write an integer to stderr",
        "warn_nl" to "warn_nl() -> None — write a newline to stderr",
        "len" to "len(a: Array[T, N] | str) -> int — the length, a constant for arrays",
        "to_float" to "to_float(n: int) -> float",
        "to_int" to "to_int(x: float) -> int — truncates toward zero",
        "sqrt" to "sqrt(x: float) -> float",
        "fabs" to "fabs(x: float) -> float",
        "floor" to "floor(x: float) -> float",
        "pow" to "pow(b: float, e: float) -> float",
        "abs" to "abs(n: int) -> int",
        "min_int" to "min_int(a: int, b: int) -> int",
        "max_int" to "max_int(a: int, b: int) -> int",
        "min_float" to "min_float(a: float, b: float) -> float",
        "max_float" to "max_float(a: float, b: float) -> float",
        "bytes_at" to "bytes_at(s: str, i: int) -> int — one byte, checked",
        "substr" to "substr(s: str, from: int, to: int) -> str — a slice, not a copy",
        "concat" to "concat(a: str, b: str) -> str — the only way to join strings",
        "unescape" to "unescape(s: str) -> str",
        "intern" to "intern(s: str) -> int — a stable handle for a string",
        "interned" to "interned(h: int) -> str",
        "argc" to "argc() -> int — the program's argument count",
        "arg" to "arg(i: int) -> str — argument i, checked",
        "read_text" to "read_text(path: str) -> str — the whole file, or empty",
        "write_text" to "write_text(path: str, body: str) -> bool",
        "panic" to "panic(msg: str) -> None — stop the program, with the message",
        "now" to "now() -> float — seconds, for timing",
        "run_command" to "run_command(cmd: str) -> int — exit status, -1 if the line is too long",
        "env" to "env(name: str) -> str — an environment variable, empty when unset",
        "range" to "range(from: int, to: int) — the only loop range; step 1",
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
        val built = scan(text)
        if (cache.size > 32) cache.clear()
        cache[key] = built
        return built
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
        out.add(VelaSymbol(kind, name, sig, ret, nameLine, parent))
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
