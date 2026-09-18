package dev.vela.plugin

import com.intellij.application.options.IndentOptionsEditor
import com.intellij.application.options.SmartIndentOptionsEditor
import com.intellij.lang.Language
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider

/**
 * Settings → Editor → Code Style → **Vela**: the page, and the defaults it shows.
 *
 * ## Why a page appears at all
 *
 * `LanguageCodeStyleSettingsProvider` is the extension the platform collects
 * language pages from — the CSS plugin registers this one and nothing else and
 * gets a page — so this is what puts "Vela" in the list. `getIndentOptionsEditor`
 * is what puts **indent size** on it, which is the thing the brief asks to be
 * visible: the editor is the platform's `IndentOptionsEditor` for a language, and
 * returning it is what makes Tab size / Indent / Continuation indent editable
 * rather than a read-only table.
 *
 * Every number written below is the same number `VelaFormatRules` uses when it
 * has no settings to read, and `VelaFormattingModelBuilder` reads the *settings*
 * for the indent step — so editing the page changes what Reformat Code does. That
 * is the whole reason the defaults are set in code instead of being left to the
 * platform's generic four.
 *
 * ## What is deliberately not here
 *
 * `customizeSettings` is not overridden, so the Spaces / Wrapping / Blank Lines
 * tabs of this page are empty: populating them means naming
 * `CodeStyleSettingsCustomizable`'s option constants, and a guessed constant name
 * is a compile error rather than a missing option. Indent size — the visible
 * setting the brief asks for — comes from the indent options editor and is
 * unaffected. This is a gap, and it is reported as one.
 */
class VelaLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage(): Language = VelaLanguage

    /**
     * The sample the settings dialog previews, in the layout the formatter
     * enforces: four spaces, `{` on the statement's own line, no space inside
     * brackets, `, ` between arguments, spaces around binary operators.
     */
    override fun getCodeSample(settingsType: LanguageCodeStyleSettingsProvider.SettingsType): String = CODE_SAMPLE

    /** Indent size, continuation indent and tab size, on the Vela page. */
    override fun getIndentOptionsEditor(): IndentOptionsEditor = SmartIndentOptionsEditor()

    /**
     * The language's defaults.  The platform builds the object — this only writes
     * Vela's numbers over it, so the part that must not be guessed (which fields
     * exist, which are legal for a new language) is the platform's.
     */
    override fun getDefaultCommonSettings(): CommonCodeStyleSettings {
        val defaults = super.getDefaultCommonSettings()
        applyVelaDefaults(defaults)
        return defaults
    }

    /**
     * The switches that describe what this formatter does, set to what it does.
     * They are the *shared* common settings — the ones the platform also reads
     * when it inserts a bracket, comments a line or wraps text — so leaving them
     * at a language-neutral default would make those other features disagree with
     * Reformat Code about the same language.
     */
    private fun applyVelaDefaults(settings: CommonCodeStyleSettings) {
        val indent = settings.initIndentOptions()
        indent.INDENT_SIZE = VelaFormatRules.INDENT_SIZE
        indent.CONTINUATION_INDENT_SIZE = VelaFormatRules.INDENT_SIZE
        indent.TAB_SIZE = VelaFormatRules.INDENT_SIZE
        indent.USE_TAB_CHARACTER = false

        // Statements end at a newline; removing one would join two statements.
        settings.KEEP_LINE_BREAKS = true
        settings.KEEP_BLANK_LINES_IN_CODE = 1

        settings.SPACE_AROUND_ASSIGNMENT_OPERATORS = true
        settings.SPACE_AROUND_LOGICAL_OPERATORS = true
        settings.SPACE_AROUND_EQUALITY_OPERATORS = true
        settings.SPACE_AROUND_RELATIONAL_OPERATORS = true
        settings.SPACE_AROUND_BITWISE_OPERATORS = true
        settings.SPACE_AROUND_ADDITIVE_OPERATORS = true
        settings.SPACE_AROUND_MULTIPLICATIVE_OPERATORS = true
        settings.SPACE_AROUND_SHIFT_OPERATORS = true
        settings.SPACE_AROUND_UNARY_OPERATOR = false

        settings.SPACE_AFTER_COMMA = true
        settings.SPACE_BEFORE_COMMA = false

        // `range(0, n)`, `Array[int, 4]`, `buf[i]`.
        settings.SPACE_WITHIN_PARENTHESES = false
        settings.SPACE_WITHIN_BRACKETS = false
        settings.SPACE_WITHIN_BRACES = false

        // `x: int` — the colon is written against the name.
        settings.SPACE_BEFORE_COLON = false
        settings.SPACE_AFTER_COLON = true

        settings.SPACE_BEFORE_METHOD_LBRACE = true
        settings.SPACE_BEFORE_IF_LBRACE = true
        settings.SPACE_BEFORE_WHILE_LBRACE = true
        settings.SPACE_BEFORE_FOR_LBRACE = true
        settings.SPACE_BEFORE_ELSE_LBRACE = true

        // `#` is the comment; the platform's "add a space after the prefix"
        // post-processor would rewrite `#note` into `# note`, and the plugin does
        // not rewrite a reader's comment text.
        settings.LINE_COMMENT_ADD_SPACE = false
        settings.LINE_COMMENT_AT_FIRST_COLUMN = false
    }

    companion object {
        /**
         * A legal Vela program, and a deliberate one: it exercises every token the
         * highlighter colours, and the excerpt a reader sees is the layout the
         * formatter produces from it.
         */
        const val CODE_SAMPLE: String = """struct Vec2 {
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
    mut buf: Array[float, 4]
    for i in range(0, 4) {
        buf[i] = 2.5 + float(i)   # element i
    }

    while total < 1.0 and not false {
        total = total + 1.0
    }

    if total == 0.0 or total > 4.0 {
        print("empty", 'x')
    } else {
        pass
    }

    p: Vec2 = Vec2(1.0, 2.0)
    print(p.dot(p))
}"""
    }
}

/**
 * The `codeStyleSettingsProvider` half.
 *
 * Registered because the brief asks for both providers, and because this is what
 * the platform reads to answer "which language is this settings object about".
 * The platform's own JSON plugin registers both of these for the same language —
 * `<codeStyleSettingsProvider implementation="com.intellij.json.formatter.JsonCodeStyleSettingsProvider"/>`
 * next to `<langCodeStyleSettingsProvider implementation="…JsonLanguageCodeStyleSettingsProvider"/>`
 * in `intellij.json.jar!META-INF/plugin.xml` — which is why both are registered
 * here rather than one.
 *
 * The configurable is the language provider's own, not a second implementation:
 * if the platform builds a page from this provider *and* from the language
 * provider, the two pages are the same page, and if it builds one, that one is
 * right. A separate configurable here would be a second definition of the Vela
 * page, which is how two pages drift apart.
 */
class VelaCodeStyleSettingsProvider : CodeStyleSettingsProvider() {

    override fun getLanguage(): Language = VelaLanguage

    override fun getConfigurableDisplayName(): String = "Vela"

    override fun createConfigurable(
        settings: CodeStyleSettings,
        cloneSettings: CodeStyleSettings,
    ): CodeStyleConfigurable = VelaLanguageCodeStyleSettingsProvider().createConfigurable(settings, cloneSettings)
}
