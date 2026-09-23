package dev.vela.plugin

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * One parameter, as a hover shows it: the name the declaration writes, the type
 * beside it, and whether the declaration writes `mut` in front of it.
 *
 * The three fields are the three things a parameter declaration *says*, and nothing
 * else is added to them: a hover that names a parameter no declaration names, or
 * types it with a type no declaration writes, is the same defect the completion path
 * had (`(s, s, s)`), one surface over.
 */
class VelaDocParam(val name: String, val type: String, val mutable: Boolean)

/**
 * What a hover says, as **data** rather than as markup.
 *
 * WHY THIS IS A CLASS AND NOT A STRING BUILDER
 *
 * A hover that is only an HTML string can be measured by nothing except reading it:
 * a harness would have to parse the markup it just built, and the parser would then
 * be the thing under test.  Here the claims are fields — this name, this kind, these
 * parameters with these types, this return type, this line — so a harness can hold
 * each one against the compiler's own dump (`vm.exe parse`) and against `SPEC.md`,
 * and can print the hover's own text beside them.  `HoverTruth` is that harness.
 *
 * NULL IS "DO NOT SAY", NEVER "GUESS".  [parameters] is null exactly when the model
 * could not read the parameter list, and then the hover lists **no** names: not a
 * name from the text between the parentheses, not one from anywhere else.  The field
 * is a `List<VelaDocParam>?` rather than an empty list for that reason — "declares
 * nothing" and "cannot be read" are different facts and are drawn differently.
 */
class VelaDoc(
    val name: String,
    /** The word the hover prints for what this is: `struct`, `def`, `field`, `param`, `builtin`. */
    val kind: String,
    /** The declaration as it is written: a callable's signature, a field's `name: type`. */
    val signature: String,
    /** The parameters the declaration declares, or null when that cannot be read. */
    val parameters: List<VelaDocParam>?,
    /** `print(...)`: a variadic signature declares no parameter to name. */
    val variadic: Boolean,
    /** The return type the declaration writes, or "" when it writes none. */
    val returns: String,
    /** A struct's fields, in the order the file writes them. */
    val fields: List<VelaDocParam>,
    /**
     * What this declaration is a member of: the struct for a field or a method, the
     * callable for a parameter, "" for a module-level name and for a builtin.
     */
    val owner: String,
    /** One sentence.  Every word of it is a fact [VelaModel] holds; see [summaryOf]. */
    val summary: String,
    /** 1-based, like the compiler's diagnostics; 0 when the name is not declared in this file. */
    val line: Int,
)

/**
 * Hover documentation: what the name under the caret is, as it is written, plus the
 * three things a reader needs beside it — **which parameters it declares** (names and
 * types, in order), **what it returns**, and **one sentence of what the model records
 * about it**.
 *
 * WHERE EVERY WORD COMES FROM, AND WHAT IS DELIBERATELY ABSENT
 *
 * The Vela parser produces a flat tree of token leaves, so the element handed to this
 * provider is a leaf (or, for a whole-file request, the file).  Both cases are handled
 * by reading *the file's text* and asking [VelaModel] what is at the caret's offset —
 * the same model completion, parameter info and the structure view read, so no two
 * features can disagree about what `Vec2` is.
 *
 * A declaration is drawn from the model's own record of it: [VelaSymbol.detail] (the
 * signature *as written*), [VelaSymbol.type], [VelaSymbol.line], the model's
 * `PARAMETER` and `FIELD` symbols, and — for a name the file does not declare — the
 * language's table [VelaModel.BUILTINS], whose separate `signature` and `prose` fields
 * exist so that a parameter list is never read out of prose.
 *
 * What this never does is *explain* a name it has no sentence for.  Whether a call
 * type-checks is the compiler's answer, drawn by `VelaExternalAnnotator` from what
 * `vm.exe check` printed.  For a `def` in the user's own file the model holds no prose
 * at all, so the sentence is the model's own facts about the declaration (what it is,
 * what it belongs to, what it returns) and never a guess at what the code *means*.  A
 * name the file does not declare and the language does not provide gets **nothing**:
 * no invented kind, no invented signature.
 */
class VelaDocumentationProvider : DocumentationProvider {

    /**
     * The declaration a name means.
     *
     * The order is the order of how *specific* the answers are, and it is the
     * difference between a hover that points at the declaration under the caret and
     * one that points at a same-spelled name somewhere else:
     *
     *   1. the declaration written *on this line* — hovering a declaration's own name
     *      is then about that declaration even when another name is spelled the same;
     *   2. a member of the enclosing struct, because inside a struct `x` is that
     *      struct's field — the only shadowing Vela's own scoping has;
     *   3. a parameter of the callable the caret is inside: a `k` in `f`'s body is
     *      `f`'s `k` and not the `k` of some other function written earlier;
     *   4. anything else visible at the caret, then any declaration of this file.
     *
     * Nothing here infers a type the text does not write: the model has names, where
     * they are written, and what is written beside them, and a name it does not hold
     * answers null.
     */
    private fun declarationOf(text: CharSequence, offset: Int, name: String): VelaSymbol? {
        val all = VelaModel.symbols(text)
        val line = VelaModel.lineOf(text, offset)
        all.firstOrNull { it.name == name && it.line == line }?.let { return it }
        val struct = VelaModel.enclosingStruct(text, offset)
        if (struct != null) {
            VelaModel.membersOf(text, struct).firstOrNull { it.name == name }?.let { return it }
        }
        val callable = VelaModel.enclosingCallable(text, offset)
        if (callable != null) {
            val owner = all.indexOf(callable)
            all.firstOrNull { it.parent == owner && it.name == name }?.let { return it }
        }
        VelaModel.visibleSymbols(text, offset).firstOrNull { it.name == name }?.let { return it }
        // A name the file declares further down is still a declaration of this file.
        return all.firstOrNull { it.name == name }
    }

    /** The builtin named `name`, with the signature and the sentence [VelaModel] holds. */
    private fun builtin(name: String): VelaBuiltin? =
        VelaModel.BUILTINS.firstOrNull { it.name == name }

    /**
     * What the hover says about the name at `offset`, or null when the model has
     * nothing to say about it.
     *
     * PSI-FREE ON PURPOSE.  Everything this decides is a question about the file's
     * characters and the model built from them, so it is decided here, on a
     * `CharSequence` and an offset, where a harness can call it with neither a
     * project nor an editor — the same shape `VelaHints.parameterHints` has, for the
     * same reason: an unmeasurable feature is an unverified one.
     */
    fun documentationAt(text: CharSequence, offset: Int): VelaDoc? {
        val name = nameAt(text, offset) ?: return null
        // The file's own declaration first: a file that declares its own `abs` is
        // hovered about *that* `abs`, and only a name this file does not declare can
        // be the language's.
        declarationOf(text, offset, name)?.let { return declarationDoc(text, it) }
        builtin(name)?.let { return builtinDoc(it) }
        return null
    }

    /** The documentation for a declaration of this file. */
    private fun declarationDoc(text: CharSequence, sym: VelaSymbol): VelaDoc {
        val all = VelaModel.symbols(text)
        val me = all.indexOf(sym)
        val kind = sym.kind.title
        // The parameters: the model's `PARAMETER` symbols, which carry the type the
        // declaration writes, and the tree's own reading of *which* names the list
        // declares.  THE TWO MUST AGREE; when they do not, the hover gives no names at
        // all rather than the ones it could find -- a partial list is a list a reader
        // would take for complete.
        val paramSymbols = if (me < 0) emptyList() else all.filter { it.parent == me && it.kind == VelaSymbolKind.PARAMETER }
        val declared = sym.parameters
        var parameters: List<VelaDocParam>? = null
        // A declaration in this file is never variadic: `def f(x: int)` names every
        // parameter it takes, and a name the file does not declare is a builtin.
        val variadic = false
        if (sym.isCallable) {
            parameters = if (declared == null || declared.size != paramSymbols.size
                || declared.withIndex().any { (i, n) -> paramSymbols[i].name != n }) {
                null
            } else {
                paramSymbols.map { p ->
                    VelaDocParam(p.name, p.type, p.detail.startsWith("mut "))
                }
            }
        }
        val fields = if (me < 0) emptyList() else
            all.filter { it.parent == me && it.kind == VelaSymbolKind.FIELD }
                .map { VelaDocParam(it.name, it.type, false) }
        val ownerSymbol = if (sym.parent >= 0) all.getOrNull(sym.parent) else null
        val owner = when (sym.kind) {
            VelaSymbolKind.FIELD, VelaSymbolKind.METHOD -> ownerSymbol?.name ?: ""
            VelaSymbolKind.PARAMETER -> ownerSymbol?.name ?: ""
            else -> ""
        }
        val signature = when (sym.kind) {
            VelaSymbolKind.STRUCT -> "struct ${sym.name}"
            VelaSymbolKind.FIELD -> if (sym.type.isEmpty()) sym.name else "${sym.name}: ${sym.type}"
            else -> sym.detail
        }
        val returns = if (sym.isCallable) sym.type else ""
        return VelaDoc(
            name = sym.name,
            kind = kind,
            signature = signature,
            parameters = parameters,
            variadic = variadic,
            returns = returns,
            fields = fields,
            owner = owner,
            summary = summaryOf(sym.kind, owner, returns, parameters, fields),
            line = sym.line,
        )
    }

    /** The documentation for one of the language's own names. */
    private fun builtinDoc(entry: VelaBuiltin): VelaDoc {
        val names = VelaSignatures.parameterNamesInSignature(entry.name, entry.signature)
        val types = builtinTypes(entry.signature)
        // `...` is filtered out of the names by [VelaSignatures]; a signature that
        // writes it declares no parameter a reader could be told about, and the hover
        // says *that* rather than printing an empty list as if the list were known.
        val variadic = entry.signature.contains("...")
        val parameters = names?.mapIndexed { i, n -> VelaDocParam(n, types.getOrElse(i) { "" }, false) }
        return VelaDoc(
            name = entry.name,
            kind = "builtin",
            signature = entry.signature,
            parameters = if (variadic) null else parameters,
            variadic = variadic,
            returns = VelaSignatures.returnTypeInSignature(entry.name, entry.signature),
            fields = emptyList(),
            owner = "",
            summary = if (entry.prose.isNotEmpty()) entry.prose
            else "provided by the language; the model carries no sentence for it",
            line = 0,
        )
    }

    /**
     * The types a builtin's signature writes, in order.
     *
     * Read from the signature and nowhere else: a parameter without a `: type` gets
     * "" here, and the hover then prints the name without inventing a type for it.
     */
    private fun builtinTypes(signature: String): List<String> {
        val open = signature.indexOf('(')
        val close = signature.indexOf(')', open + 1)
        if (open < 0 || close < 0) return emptyList()
        val inner = signature.substring(open + 1, close)
        if (inner.trim().isEmpty()) return emptyList()
        val out = ArrayList<String>(4)
        var depth = 0
        val current = StringBuilder()
        for (c in inner) {
            when {
                c == '[' || c == '(' -> { depth++; current.append(c) }
                c == ']' || c == ')' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { out.add(typeOf(current.toString())); current.setLength(0) }
                else -> current.append(c)
            }
        }
        out.add(typeOf(current.toString()))
        return out
    }

    /** The `T` of `name: T`; "" when the entry writes no type. */
    private fun typeOf(parameter: String): String {
        val colon = parameter.indexOf(':')
        return if (colon < 0) "" else parameter.substring(colon + 1).trim()
    }

    /**
     * One sentence, every word of it a fact the model holds about the declaration.
     *
     * There is no prose anywhere in the model for a name a *file* declares, and this
     * method does not invent any: it says what the declaration is (from
     * [VelaSymbolKind]), what it belongs to, what its parameters are called and what
     * it returns.  The one place a real sentence exists is a builtin's `prose` field,
     * and that sentence is used as it is written there.
     */
    private fun summaryOf(
        kind: VelaSymbolKind,
        owner: String,
        returns: String,
        parameters: List<VelaDocParam>?,
        fields: List<VelaDocParam>,
    ): String {
        val head = when (kind) {
            VelaSymbolKind.STRUCT ->
                "A struct declared in this file, with " + fields.size + " field(s)."
            VelaSymbolKind.METHOD ->
                if (owner.isEmpty()) "A method declared in this file."
                else "A method of the struct `$owner`."
            VelaSymbolKind.FUNCTION -> "A function declared in this file."
            VelaSymbolKind.FIELD ->
                if (owner.isEmpty()) "A field declared in this file."
                else "A field of the struct `$owner`."
            VelaSymbolKind.PARAMETER ->
                if (owner.isEmpty()) "A parameter declared in this file."
                else "A parameter of `$owner`."
        }
        if (parameters != null || !kind.isCallableKind()) return head
        return "$head The parameter list cannot be read, so no parameter names are given."
    }

    private fun VelaSymbolKind.isCallableKind(): Boolean =
        this == VelaSymbolKind.FUNCTION || this == VelaSymbolKind.METHOD

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        val target = originalElement ?: element
        val file = containingVela(target) ?: return null
        val text = fileText(file) ?: return null
        val offset = target.textRange.startOffset
        val doc = documentationAt(text, offset) ?: return null
        return hoverHtml(doc)
    }

    /** Ctrl-hover: the same one-liner, so the two hovers cannot say different things. */
    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        // The element to read a name from: the one the platform points at, falling
        // back to the original copy — either can be the file itself.
        val target = originalElement ?: element ?: return null
        val file = containingVela(target) ?: return null
        val text = fileText(file) ?: return null
        val offset = target.textRange.startOffset
        val doc = documentationAt(text, offset) ?: return null
        return quickInfo(doc)
    }

    /*
     * The provider has no link targets of its own: everything it says is a
     * declaration in this file, and navigation to it is the platform's own
     * word/documentation machinery, which works off the leaf's range.  Returning
     * null is the honest answer — an invented PSI target would navigate a reader
     * somewhere the text does not point.
     */
    override fun getDocumentationElementForLookupItem(
        psiManager: PsiManager,
        obj: Any?,
        element: PsiElement?,
    ): PsiElement? = null

    override fun getDocumentationElementForLink(
        psiManager: PsiManager,
        link: String?,
        context: PsiElement?,
    ): PsiElement? = null

    override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): MutableList<String>? = null

    // ------------------------------------------------------------------ drawing

    /** The one-line form: what it is, its signature, and where it is. */
    fun quickInfo(doc: VelaDoc): String {
        val suffix = if (doc.line > 0) " (line ${doc.line})" else " (not declared in this file)"
        return "${doc.kind} ${doc.signature}$suffix"
    }

    /**
     * The popup body: the signature as written, one sentence of what the model
     * records, the parameter names and types in order, a struct's fields, and the
     * return type.
     *
     * `DocumentationMarkup`'s own constants are used rather than a hand-written
     * `<html>` wrapper, so the popup looks like every other language's.  A builtin has
     * no line in this file, and rather than invent one the line is omitted and the
     * footer says where the name comes from.
     */
    fun hoverHtml(doc: VelaDoc): String = buildString {
        append(DocumentationMarkup.DEFINITION_START)
        append(escape(doc.signature))
        append(DocumentationMarkup.DEFINITION_END)
        append(DocumentationMarkup.CONTENT_START)
        append(escape(doc.summary))
        if (doc.parameters == null) {
            if (doc.variadic) {
                append("<br/>")
                append(escape("Variadic: the declaration names no parameter to name an argument with."))
            }
        } else {
            if (doc.parameters.isNotEmpty()) {
                append("<br/>")
                append(escape("Parameters: " + doc.parameters.joinToString(", ") { paramText(it) }))
            } else if (doc.kind == "def" || doc.kind == "builtin") {
                append("<br/>")
                append(escape("Parameters: none."))
            }
        }
        if (doc.fields.isNotEmpty()) {
            append("<br/>")
            append(escape("Fields: " + doc.fields.joinToString(", ") { paramText(it) }))
        }
        if (doc.returns.isNotEmpty() && (doc.kind == "def" || doc.kind == "builtin")) {
            append("<br/>")
            append(escape("Returns `${doc.returns}`."))
        }
        append(DocumentationMarkup.CONTENT_END)
        append(DocumentationMarkup.SECTIONS_START)
        append(DocumentationMarkup.GRAYED_START)
        val where = if (doc.line > 0) "line ${doc.line}" else "not declared in this file"
        append(escape("${doc.kind} · $where"))
        append(DocumentationMarkup.GRAYED_END)
        append(DocumentationMarkup.SECTIONS_END)
    }

    /** `mut k: float`, `a: int`, or just `a` when no type is written. */
    private fun paramText(p: VelaDocParam): String {
        val mut = if (p.mutable) "mut " else ""
        return if (p.type.isEmpty()) "`$mut${p.name}`" else "`$mut${p.name}: ${p.type}`"
    }

    // ------------------------------------------------------------------ help

    /**
     * The Vela file an element belongs to, or null when it is not one.  A leaf
     * inside a `.vel` file is the normal case; a directory, a foreign language, or
     * a file with no name gives null, and the caller then says nothing.
     */
    private fun containingVela(element: PsiElement): PsiFile? {
        val file = element.containingFile ?: (element as? PsiFile) ?: return null
        if (file.virtualFile?.let { !isVelaFileName(it.name) } == true) return null
        return file
    }

    /**
     * The whole file's text.
     *
     * `file.text` is what the model wants, but for a leaf inside a file whose PSI
     * is not fully built (the flat token tree has no committed document), the
     * document is the only complete source.  The offsets the platform reports are
     * document offsets, so the document is preferred when there is one.
     */
    private fun fileText(file: PsiFile): CharSequence? {
        val project = file.project
        if (!project.isDefault) {
            val document = PsiDocumentManager.getInstance(project).getDocument(file)
            if (document != null) return document.immutableCharSequence
        }
        val text = file.text
        return if (text.isEmpty()) null else text
    }

    /** Vela source text is displayed, not evaluated: `<` and `&` must stay characters. */
    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
