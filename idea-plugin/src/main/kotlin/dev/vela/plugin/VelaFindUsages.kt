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
 * file's own characters with `VelaModel`, exactly as completion and hover are.
 * Nothing here decides meaning; `vm.exe` still owns that.
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
     * Whether this element is something whose usages can be looked for: a name the
     * model resolves to a declaration, or one the language provides.
     *
     * `readAt` has already established that the element is a name token (not text
     * inside a string, not a punctuation leaf), so what remains is whether the name
     * means anything: a keyword is a name token the compiler owns, and a word the
     * file neither declares nor the language provides has no usages to find.
     */
    override fun canFindUsagesFor(element: PsiElement): Boolean {
        val found = readAt(element) ?: return false
        val (text, offset, name) = found
        return declarationOf(text, offset, name) != null || isBuiltin(name)
    }

    /** What kind of thing the name is, in the words the Find Usages view uses. */
    override fun getType(element: PsiElement): String {
        val found = readAt(element) ?: return "name"
        val (text, offset, name) = found
        declaredKind(text, offset, name)?.let { return it }
        return if (isBuiltin(name)) "builtin" else "name"
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

    /** The declaration this name resolves to, if the file declares one. */
    private fun declarationOf(text: String, offset: Int, name: String): VelaSymbol? =
        VelaModel.symbolAt(text, offset)
            ?: VelaModel.visibleSymbols(text, offset).firstOrNull { it.name == name }
            ?: VelaModel.symbols(text).firstOrNull { it.name == name }

    /** The kind, for `getType`; null when the name is not a declaration here. */
    private fun declaredKind(text: String, offset: Int, name: String): String? =
        declarationOf(text, offset, name)?.let { kindWord(it.kind) }

    private fun kindWord(kind: VelaSymbolKind): String = when (kind) {
        VelaSymbolKind.STRUCT -> "struct"
        VelaSymbolKind.METHOD -> "method"
        VelaSymbolKind.FUNCTION -> "function"
        VelaSymbolKind.FIELD -> "field"
        VelaSymbolKind.PARAMETER -> "parameter"
    }

    private fun isBuiltin(name: String): Boolean =
        VelaModel.BUILTINS.any { it.name == name }
}
