package dev.vela.plugin

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Closing what was opened: `{` writes `}`, `(` writes `)`, `[` writes `]`, and a
 * quote writes its own quote.
 *
 * ## Why this is a second pair of brackets and not a conflict
 *
 * The platform inserts the matching bracket itself for a language that has a
 * `PairedBraceMatcher` and for a language that has a `QuoteHandler`, and it
 * decides *before* the typed character reaches the document.  This handler runs
 * in `charTyped`, i.e. after the character is in the document, and it does
 * nothing at all when the closing character is already the next character:
 *
 *     if (caret < text.length && text[caret] == closer) return CONTINUE
 *
 * So whichever of the two gets there first, the result is one pair and never two.
 * What this adds over the platform's own path is the case the platform cannot
 * know about: Vela strings are lexed by `VelaLexer` from `"` *and* `'`, and a
 * `QuoteHandler` is not registered for Vela, so without this a quote would not
 * close itself.  Nothing here depends on the platform's behaviour and nothing
 * here fights it.
 *
 * ## Why the lexer decides, and not the surrounding characters
 *
 * "Do not fire inside a string or a comment" is not a special case in this code:
 * it is the whole test. The question asked is *what token did the character I
 * just typed open?* — and the answer comes from `VelaLexer` over the current
 * line:
 *
 *  * `{` typed into a comment lexes as `COMMENT`, not `BRACES` → no closer.
 *  * `{` typed inside a string lexes as `STRING` → no closer.
 *  * A `"` that opens a string lexes as a `STRING` token *starting at that
 *    offset*; a `"` that closes one lexes as a `STRING` that started earlier →
 *    only the first is an opener.
 *
 * Only the current line is lexed. That is not an approximation: `SPEC.md` §1 says
 * a literal may not contain a raw newline and a `#` comment ends at the newline,
 * so no Vela token can span a line.
 */
class VelaTypedHandlerDelegate : TypedHandlerDelegate() {

    override fun charTyped(c: Char, project: Project, editor: Editor, file: PsiFile): TypedHandlerDelegate.Result {
        if (!velaIsVelaFile(file)) return CONTINUE
        val closer = closerFor(c) ?: return CONTINUE

        val document = editor.document
        val caret = editor.caretModel.offset
        val typedAt = caret - 1
        if (typedAt < 0) return CONTINUE
        val text = document.charsSequence
        if (typedAt >= text.length) return CONTINUE
        // Something moved the caret away from what was typed: not ours to close.
        if (text[typedAt] != c) return CONTINUE

        val line = velaLineAt(document, typedAt)
        if (!velaOpens(c, line.text, typedAt - line.start)) return CONTINUE

        // The platform, the user, or an earlier handler may have written the
        // closing character already. Two brackets is a bug; one is the point.
        if (caret < text.length && text[caret] == closer) return CONTINUE

        document.insertString(caret, closer.toString())
        // The caret stays between the pair. Inserting at the caret offset can move
        // the caret past the inserted text depending on marker bias, so say where
        // it goes rather than relying on that.
        editor.caretModel.moveToOffset(caret)
        return CONTINUE
    }
}

/**
 * `Enter`, in a language whose blocks are braces.
 *
 * Two things happen here, and both replace what the platform's own smart indent
 * did rather than adding to it:
 *
 *  1. **Enter right after a `{`** writes the shape people mean:
 *     the body line, indented one level, with the closing brace on the line after
 *     it and the caret on the body line. This is the `def` / `if` / `while` / `for`
 *     case, and it is the one place where a single keystroke is expected to
 *     produce three lines.
 *  2. **Enter anywhere else inside a block** sets the caret line's indentation to
 *     the brace depth at that point — the same number `VelaFormatRules` indents
 *     by, counted with the lexer so a `{` inside a string or a comment does not
 *     count as a block.
 *
 * `postProcessEnter` runs after the line feed exists, so both branches rewrite the
 * caret line's leading whitespace instead of predicting it. The result does not
 * depend on whether the platform indented it as Vela or as plain text.
 *
 * One honest limitation: the indent step is `VelaFormatRules.INDENT_SIZE` (four),
 * not the reader's setting from the code style page. Reformat Code reads the
 * setting; this path would need a `Project` → `CodeStyleSettings` lookup, and the
 * plugin already reads settings in exactly one place (the formatter). An editor
 * path that indents by four while the formatter indents by two would be a real
 * inconsistency, and it is reported rather than hidden.
 */
class VelaEnterHandlerDelegate : EnterHandlerDelegate {

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        if (!velaIsVelaFile(file)) return DEFAULT
        val document = editor.document
        val caret = editor.caretModel.offset
        if (caret <= 0) return DEFAULT

        val lineStart = document.getLineStartOffset(document.getLineNumber(caret))
        if (lineStart <= 0) return DEFAULT
        val previousLineStart = document.getLineStartOffset(document.getLineNumber(lineStart - 1))
        val previousLineEnd = lineStart - 1
        if (previousLineEnd <= previousLineStart) return DEFAULT

        val text = document.charsSequence
        val previousLine = text.subSequence(previousLineStart, previousLineEnd)
        val openIndent = velaLeadingWhitespace(previousLine)
        val body = previousLine.trimEnd()

        if (body.isNotEmpty() && body[body.length - 1] == '{' &&
            velaOpens('{', body, body.length - 1)
        ) {
            // `{` then Enter: body line, closing brace, caret between them.
            val bodyIndent = openIndent + velaIndent(1)
            document.replaceString(lineStart, caret, bodyIndent)
            val insertAt = lineStart + bodyIndent.length
            document.insertString(insertAt, "\n" + openIndent + "}")
            editor.caretModel.moveToOffset(insertAt)
            // Both lines and the caret are now exactly where they belong; no other
            // enter handler has anything to add.
            return STOP
        }

        val depth = velaBraceDepth(text, lineStart)
        val wanted = velaIndent(depth)
        if (text.subSequence(lineStart, caret).toString() != wanted) {
            document.replaceString(lineStart, caret, wanted)
            editor.caretModel.moveToOffset(lineStart + wanted.length)
        }
        // Not `Stop`: the line feed and the indentation are ours, but nothing else
        // about the enter was (a comment continuation handler, for instance, may
        // still want to run) — and `Stop` would be a claim about all of it.
        return DEFAULT
    }
}

// ---------------------------------------------------------------------- helpers

/**
 * Does the character at `offset` in `line` actually open something?
 *
 * `line` is one line of the document, lexed by the plugin's own `VelaLexer`; see
 * the class comment for why a line is the right unit and why the lexer is asked
 * rather than the surrounding characters.
 */
private fun velaOpens(typed: Char, line: CharSequence, offset: Int): Boolean {
    if (offset < 0) return false
    val token = velaTokenAt(line, offset) ?: return false
    // The token has to *start* here: a `{` in the middle of a string or comment is
    // inside that token, not at the start of one.
    if (token.start != offset) return false
    return when (typed) {
        '{' -> token.type == VelaTokenTypes.BRACES
        '(' -> token.type == VelaTokenTypes.PARENS
        '[' -> token.type == VelaTokenTypes.BRACKETS
        '"', '\'' -> {
            if (token.type != VelaTokenTypes.STRING) {
                false
            } else {
                // An opener, unless the token before it ends exactly here and is
                // something a quote can close — `"ab"` followed by `"` is a second
                // string, but `x"` is not an opener at all.
                val before = velaTokenAt(line, offset - 1) ?: return true
                if (before.end != offset) {
                    true
                } else {
                    val type = before.type
                    !(type == VelaTokenTypes.STRING || type == VelaTokenTypes.IDENTIFIER ||
                        type == VelaTokenTypes.NUMBER || type == VelaTokenTypes.KEYWORD)
                }
            }
        }
        else -> false
    }
}

/** The character that closes [open], or `null` if it opens nothing. */
private fun closerFor(open: Char): Char? = when (open) {
    '{' -> '}'
    '(' -> ')'
    '[' -> ']'
    '"' -> '"'
    '\'' -> '\''
    else -> null
}

/**
 * `TypedHandlerDelegate.Result`, found by name.
 *
 * The platform renamed the constants of these two enums between releases
 * (`CONTINUE`/`CHANGE` in older builds, `Continue`/`Change` in this one), and this
 * source set is compiled against two platforms — 262 and the 253 of PyCharm
 * 2025.3, which is what the plugin's `since-build` claims. Looking the value up by
 * name is the one form that compiles and means the same thing in both, and it is
 * looked up once, when the class loads, not per keystroke.
 *
 * The fallback is the first constant, which for both enums is the "carry on"
 * value (`EnterHandlerDelegate.Result` is `Default, Continue, …` in 262).
 */
private fun typedContinue(): TypedHandlerDelegate.Result {
    val values = TypedHandlerDelegate.Result.values()
    for (candidate in values) {
        if (candidate.name.equals("Continue", ignoreCase = true)) return candidate
    }
    return values[0]
}

private fun enterResult(vararg names: String): EnterHandlerDelegate.Result {
    val values = EnterHandlerDelegate.Result.values()
    for (name in names) {
        for (candidate in values) {
            if (candidate.name.equals(name, ignoreCase = true)) return candidate
        }
    }
    return values[0]
}

private val CONTINUE: TypedHandlerDelegate.Result = typedContinue()

private val DEFAULT: EnterHandlerDelegate.Result = enterResult("Default", "Continue")

private val STOP: EnterHandlerDelegate.Result = enterResult("Stop")
