package dev.vela.plugin

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.TokenSet

/**
 * Find Usages: which names in a Vela file are the same name.
 *
 * The platform asks this class only about *names* — the question arrives after a
 * reference has resolved to something, or after the search index has offered a
 * candidate — so every answer here is a statement about a name, read from the
 * file's own characters, exactly as completion and hover are.  Nothing here decides
 * meaning; `vm.exe` still owns that.
 *
 * THE ANSWERS ARE [VelaUsageSearch]'S, AND THIS CLASS IS THE PLATFORM'S DOORWAY TO
 * THEM.  `canFindUsagesFor` and `getType` are one-line delegations because the same
 * two questions are what `tools/harness/src/RenameOracle.java` measures — a harness
 * cannot make a `PsiElement`, so the questions have to have an answer that is a
 * function of the text, or the shipped answer and the measured answer are two
 * different pieces of code.  `getDescriptiveName` is not among them: it is a
 * *rendering* of a declaration's own text, and the model is the right source for it.
 *
 * ## Why the words scanner is the member that matters
 *
 * `getWordsScanner` has a default implementation that returns null, and the
 * platform responds to null by not searching at all: the feature reports "no
 * usages found" for every name in the file.  That is worse than not having the
 * feature, because it is a confident wrong answer rather than a missing one.  So
 * the scanner is real, built over the plugin's *own* lexer — the same one the
 * editor colours with — which is what keeps "which words are names" a single
 * decision in this plugin rather than two that can drift.
 *
 * ## The honest limits
 *
 * Vela's PSI is flat — one leaf per token, no composite nodes (`VelaParserDefinition`)
 * — so an element handed here is a leaf, and everything below is read from the
 * document at the leaf's offset rather than from a tree.  `getHelpId` returns null
 * on purpose: there is no help topic for this language, and a made-up id would be
 * a link to a page that does not exist.  Words inside string literals are indexed
 * as searchable text (they are words), but they are not *names*: `canFindUsagesFor`
 * answers false for them, because the model resolves no declaration from text
 * inside a string.
 */
class VelaFindUsagesProvider : FindUsagesProvider {

    /**
     * The lexer's own idea of what can be searched, so the index and the editor
     * agree about which words exist.  Identifiers are what a name is; comments are
     * searchable text; Vela has no string-literal *element* to offer the parser
     * definition (`VelaParserDefinition` leaves `getStringLiteralElements` empty on
     * purpose, because a literal's meaning is the compiler's), but a string's body
     * is still words a user may search for, so the lexer's string token is offered
     * to the scanner as literal text.  Indexing words is not the same as having an
     * opinion about what they mean.
     *
     * Held in a field because the platform may ask more than once and a scanner is
     * pure: it holds a lexer and three token sets and no state of its own.
     *
     * (The field is named `scannerOnce`, not `wordsScanner`: a Kotlin property
     * called `wordsScanner` would generate a `getWordsScanner()` accessor that
     * clashes at the JVM level with the `org.intellij.lang.findUsages` interface
     * method this class overrides.)
     */
    private val scannerOnce: WordsScanner by lazy {
        DefaultWordsScanner(
            VelaLexer(),
            TokenSet.create(VelaTokenTypes.IDENTIFIER),
            TokenSet.create(VelaTokenTypes.COMMENT),
            TokenSet.create(VelaTokenTypes.STRING),
        )
    }

    override fun getWordsScanner(): WordsScanner = scannerOnce

    /**
     * Whether this element is something whose usages can be looked for.
     *
     * The answer is `VelaUsageSearch.canSearchAt`, and it is asked of the text rather
     * than of the model, because the model's symbol list is a list of *declarations*
     * and the question is about *any* name: a parameter, a loop variable, a local
     * binding and a field all have usages and none of them is a file-level symbol.
     * Asking the model first — which is what this method used to do — answered *no*
     * for every one of those, and the platform reads *no* as "there is nothing to
     * search for here", so Find Usages refused the very names a reader is most likely
     * to look up.
     *
     * `readAt` has already established that the element is a name token the lexer
     * produced (not text inside a string, not a punctuation leaf), so what remains is
     * whether the name means anything — see [VelaUsageSearch.canSearchAt].
     */
    override fun canFindUsagesFor(element: PsiElement): Boolean {
        val found = readAt(element) ?: return false
        val (text, offset, name) = found
        return VelaUsageSearch.canSearchAt(text, offset, offset + name.length)
    }

    /** What kind of thing the name is, in the words the Find Usages view uses. */
    override fun getType(element: PsiElement): String {
        val found = readAt(element) ?: return "name"
        val (text, offset, name) = found
        return VelaUsageSearch.typeAt(text, offset, offset + name.length)
    }

    /**
     * What to put beside a usage in the results list.
     *
     * A declaration is written the way the language writes it — `struct Vec2`,
     * the whole `dot(self: Vec2, o: Vec2) -> float` signature, `x: float` — so a
     * reader can tell two same-named things apart.  A *use* of a name has no such
     * text of its own, so it is shown as the name, which is the truth about it.
     */
    override fun getDescriptiveName(element: PsiElement): String {
        val found = readAt(element) ?: return ""
        val (text, offset, name) = found
        val declared = declarationOf(text, offset, name)
        val written = element.text.trim()
        if (declared == null) return if (written.isEmpty()) name else written
        return when (declared.kind) {
            VelaSymbolKind.STRUCT -> "struct ${declared.name}"
            VelaSymbolKind.METHOD, VelaSymbolKind.FUNCTION -> declared.detail
            VelaSymbolKind.FIELD -> "${declared.name}: ${declared.type}"
            VelaSymbolKind.PARAMETER -> "${declared.name}: ${declared.type}"
        }
    }

    /**
     * The text of the element itself: the name as the user sees it.  The platform
     * calls this for a leaf, so the leaf's own text is the whole answer, and the
     * link range the results view draws underlines exactly that.
     */
    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        element.text.trim()

    /**
     * No help topic exists for this language.  The platform treats null as "no
     * link", which is the honest answer; inventing an id would produce a help link
     * that opens nothing.
     */
    override fun getHelpId(element: PsiElement): String? = null

    // ------------------------------------------------------------------ reading

    /**
     * The name at an element's offset, with the file text it was read from.
     *
     * The document is preferred over `file.text` because the platform's offsets —
     * and therefore the offset of the leaf it hands us — are *document* offsets,
     * and a flat token tree can be read while its document is newer than its PSI.
     */
    private fun readAt(element: PsiElement): Triple<String, Int, String>? {
        val file = element.containingFile ?: (element as? PsiFile) ?: return null
        val name = file.virtualFile?.name
        if (name != null && !isVelaFileName(name)) return null
        val text = fileText(file) ?: return null
        val offset = element.textRange.startOffset
        val word = nameAt(text, offset) ?: return null
        // The element must *be* the name, not merely start at one.  The platform
        // walks real leaves, and a leaf whose text is not exactly this word is not
        // the name node — answering yes about it would put a usage in the results
        // list that Find Usages cannot actually underline.
        if (!isVelaWord(text, offset, word)) return null
        return Triple(text, offset, word)
    }

    private fun fileText(file: PsiFile): String? {
        val project = file.project
        val virtualFile = file.virtualFile
        if (!project.isDefault && virtualFile != null) {
            val document = FileDocumentManager.getInstance().getDocument(virtualFile)
            if (document != null) return document.charsSequence.toString()
        }
        val text = file.text
        return if (text.isEmpty()) null else text
    }

    /**
     * A word the *lexer* calls a name at this offset.
     *
     * `nameAt` reads characters, so it happily returns a word written inside a
     * string or a comment — both of which are text about the program rather than
     * the program.  Re-lexing the leaf's own text is what separates the two: an
     * element the lexer really did tokenise as an identifier is a name, and one
     * inside a literal is not, whatever characters it happens to contain.
     */
    private fun isVelaWord(text: String, offset: Int, name: String): Boolean {
        val end = (offset + name.length).coerceAtMost(text.length)
        if (offset >= end) return false
        val lexer = VelaLexer()
        lexer.start(text, offset, end)
        val type = lexer.tokenType
        if (type != VelaTokenTypes.IDENTIFIER) return false
        // The whole leaf must be one identifier, not a prefix of a longer word.
        return lexer.tokenEnd - offset == name.length &&
            text.regionMatches(offset, name, 0, name.length)
    }

    /**
     * The declaration this name resolves to, read from the model, if the file
     * declares one.
     *
     * One caller now: [getDescriptiveName], which needs the declaration's *text*
     * (`struct Vec2`, the whole signature, `x: float`) to put beside a usage in the
     * results list.  `canFindUsagesFor` and `getType` used to ask this too, and the
     * model cannot answer the question they were really asking: `VelaModel.symbols`
     * lists functions, structs, fields and parameters, so a loop variable or a local
     * binding — names with plenty of usages — had no symbol at all.  See
     * [VelaUsageSearch.canSearchAt] for what answers that now.
     */
    private fun declarationOf(text: String, offset: Int, name: String): VelaSymbol? =
        VelaModel.symbolAt(text, offset)
            ?: VelaModel.visibleSymbols(text, offset).firstOrNull { it.name == name }
            ?: VelaModel.symbols(text).firstOrNull { it.name == name }

    private fun isBuiltin(name: String): Boolean =
        VelaModel.BUILTINS.any { it.name == name }
}

/**
 * The IDE-free half of Find Usages: whether a name can be searched for at all, and
 * what kind of thing it is.
 *
 * ## What the platform asks, and what can be answered without it
 *
 * Find Usages is three questions:
 *
 *   1. *can this element be searched for?* — `FindUsagesProvider.canFindUsagesFor`,
 *      asked about the element under the caret.  Answered here by [canSearchAt];
 *   2. *what does the results list call it?* — `getType`.  Answered by [typeAt];
 *   3. *which occurrences resolve to it?* — the platform's own search, which asks
 *      every identifier leaf for its `PsiReference` and keeps the ones whose
 *      `resolve()` is the element searched for.  That loop is the platform's and
 *      cannot be run headlessly; the decision it is built on is
 *      `VelaReferences.referenceTargetAt`, one leaf at a time, and it is the same
 *      function `VelaReferenceProvider` answers with.
 *
 * The search itself is therefore not restated here — a second list of "things that
 * might be a usage" is exactly how a search and a rename come to disagree.  What a
 * harness can do, and what `tools/harness/src/RenameOracle.java` does, is walk the
 * `IDENTIFIER` leaves — the candidate set the provider is registered over and the
 * words scanner indexes — ask question 3's function about each, and hold the resulting
 * table to the compiler's own binding set.
 *
 * ## Why the model is not the answer
 *
 * The three methods here read the *text*, not `VelaModel.symbols`.  A model symbol is
 * a declaration the model knows (a function, a struct, a field, a parameter); a name
 * can be declared by a loop header or by a `mut` binding as well, and the question here
 * is about *any* name.  Answering from the model meant `canFindUsagesFor` said no to a
 * loop variable, a local and a plain field — the platform reads no as "there is nothing
 * here to search for", so those names had no Find Usages at all.
 */
object VelaUsageSearch {

    /**
     * Can usages be looked for at the identifier leaf `[start, end)`?
     *
     * Yes when the name is one of the language's own (a builtin: the declaration is the
     * language's, and every call to it in the file is a usage), yes when the leaf is a
     * *reference* to a declaration [VelaReferences.referenceTargetAt], and yes when the
     * leaf is the name *of* a declaration ([VelaDeclarations.isDeclarationNameAt]) —
     * because that is the element a reader puts the caret on before asking for its
     * usages, and it is deliberately not a reference to itself.
     *
     * No for a keyword (the compiler's name, not the file's), for a name the file
     * neither declares nor uses, and for text inside a string or a comment — the last
     * of which is already excluded by the caller, which re-lexes the leaf.
     */
    fun canSearchAt(text: CharSequence, start: Int, end: Int): Boolean {
        if (start < 0 || end > text.length || end <= start) return false
        val name = text.subSequence(start, end).toString()
        if (name.isEmpty() || !name.all { isNamePart(it) } || name.first().isDigit()) return false
        if (isBuiltinName(name)) return true
        if (VelaReferences.referenceTargetAt(text, start, end) != null) return true
        return VelaDeclarations.isDeclarationNameAt(text, start, end)
    }

    /**
     * What the results list calls this name: the kind of the declaration the name
     * stands for, in the words the Find Usages view uses.
     *
     * A *use* is answered by the same resolution the reference provider asks
     * (`VelaDeclarations.declarationTarget`), which is where the kind is known — a
     * field reached through a receiver is a `field`, a binding read in a body is a
     * `variable`, a struct's constructor call is a `struct`.  Asking that one function
     * rather than `VelaTargets` directly is what makes the label agree with the
     * reference: the two call shapes `declarationTarget` answers on top of the tree
     * (`P(1)`, `o.inner.bump()`) would otherwise be a reference whose label is `name`,
     * which is how `RenameOracle` reported 5 struct usages as mislabelled.
     *
     * A name that is a *declaration* and does not resolve to itself (the model does not
     * list local bindings, so a `mut n: int = 0` has no `VelaTarget`) falls back to
     * "name", which is the honest word for "this text says it is declared here and
     * nothing more"; a caller that needs the exact kind of *that* name has to read the
     * tree, and nothing does.
     */
    fun typeAt(text: CharSequence, start: Int, end: Int): String {
        if (start < 0 || end > text.length || end <= start) return "name"
        val name = text.subSequence(start, end).toString()
        if (name.isEmpty() || !name.all { isNamePart(it) }) return "name"
        if (isBuiltinName(name)) return "builtin"
        // A use: the declaration it names, with its kind.
        VelaDeclarations.declarationTarget(text, start, end, name)?.let { return wordOf(it.kind) }
        // The name *of* a declaration, for the kinds the tree resolves to themselves.
        VelaTargets.declarationFor(text, start)?.let {
            if (it.start == start && it.end == end) return wordOf(it.kind)
        }
        return "name"
    }

    /** The word the Find Usages view shows for one kind of declaration. */
    fun wordOf(kind: VelaTargetKind): String = when (kind) {
        VelaTargetKind.FUNCTION -> "function"
        VelaTargetKind.METHOD -> "method"
        VelaTargetKind.STRUCT -> "struct"
        VelaTargetKind.FIELD -> "field"
        VelaTargetKind.LOCAL -> "variable"
        VelaTargetKind.PARAMETER -> "parameter"
        VelaTargetKind.LOOP_VARIABLE -> "loop variable"
    }

    /** A name the language itself declares.  No line of this file is its declaration. */
    private fun isBuiltinName(name: String): Boolean =
        VelaModel.BUILTINS.any { it.name == name }
}
