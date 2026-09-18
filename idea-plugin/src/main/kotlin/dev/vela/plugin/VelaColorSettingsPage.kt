package dev.vela.plugin

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Settings → Editor → Color Scheme → **Vela**.
 *
 * The page lists one row per key the syntax highlighter can produce, and each row
 * is the *same* `TextAttributesKey` object `VelaSyntaxHighlighter` returns for a
 * token — `VelaColors.KEYWORD` here is the key that colours a `def` in the editor,
 * not a copy of its name.  That is the whole mechanism: the platform indexes
 * schemes by the key, so a row that names the key is a row that changes what the
 * editor draws, and a row that names a *different* key would be a setting that
 * does nothing.
 *
 * `getColorDescriptors` returns `ColorDescriptor.EMPTY_ARRAY` because a language
 * highlighter here has no colour values of its own — Vela has no `#rrggbb`
 * literals to preview, which is what that list is for.
 *
 * The demo text is not a program: it is a sample chosen so that every row above
 * has something to colour.  Its last line holds the two token kinds no real Vela
 * source reaches — `;`, which `VelaLexer` tokenises but `SPEC.md` never uses as a
 * statement separator, and a character outside Vela's alphabet, which is the one
 * `BAD_CHARACTER` the highlighter colours.  A reader who wants to know which of
 * the rows can never appear in their code should be able to see that here instead
 * of guessing.
 */
class VelaColorsAndFontsPage : ColorSettingsPage {

    override fun getIcon(): Icon =
        IconLoader.getIcon("/icons/vela.svg", VelaColorsAndFontsPage::class.java)

    override fun getDisplayName(): String = "Vela"

    override fun getHighlighter(): SyntaxHighlighter = VelaSyntaxHighlighter()

    /**
     * One row per key `VelaSyntaxHighlighter.getTokenHighlights` can return, in
     * the order the keys are declared — so the two lists can be read side by side.
     */
    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = arrayOf(
        AttributesDescriptor("Keyword", VelaColors.KEYWORD),
        AttributesDescriptor("Identifier", VelaColors.IDENTIFIER),
        AttributesDescriptor("Number", VelaColors.NUMBER),
        AttributesDescriptor("String", VelaColors.STRING),
        AttributesDescriptor("Comment", VelaColors.COMMENT),
        AttributesDescriptor("Operator", VelaColors.OPERATOR),
        AttributesDescriptor("Braces", VelaColors.BRACES),
        AttributesDescriptor("Brackets", VelaColors.BRACKETS),
        AttributesDescriptor("Parentheses", VelaColors.PARENS),
        AttributesDescriptor("Comma", VelaColors.COMMA),
        AttributesDescriptor("Dot", VelaColors.DOT),
        AttributesDescriptor("Colon", VelaColors.COLON),
        AttributesDescriptor("Semicolon", VelaColors.SEMICOLON),
        AttributesDescriptor("Bad character", VelaColors.BAD),
    )

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    /**
     * No extra tags: the semantic annotator's colours are keyed by what a name
     * *means*, and a tag in a demo string would be inventing a syntax to display
     * them.  Empty rather than `null`, which the platform accepts either way and
     * which reads as "none" rather than "not implemented".
     */
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = emptyMap()

    override fun getDemoText(): String = DEMO_TEXT

    companion object {
        /**
         * The sample.  Not legal Vela by design — see the class comment — but
         * written so that a reader can tell what each row looks like in context.
         */
        const val DEMO_TEXT: String = """# Every kind of token the Vela highlighter defines.
# This sample is not compiled; the last line holds the two kinds
# that no real Vela program reaches.
struct Vec2 {
    x: float
    y: float

    def dot(self: Vec2, o: Vec2) -> float {
        return self.x * o.x + self.y * o.y
    }
}

pure def scale(v: float, k: float) -> float {
    return v * k
}

def main() -> None {
    mut total: float = 0.0
    mut buf: Array[float, 4]     # an array may be declared uninitialised
    for i in range(0, 4) {
        buf[i] = 2.5e-3 + 0x10
    }

    while total < 1.0 and not false {
        total = total << 1
    }

    if total == 0.0 or total > 4.0 {
        print("nothing here", 'or here')
    } else {
        pass
    }

    print(total)
}

;  @"""
    }
}
