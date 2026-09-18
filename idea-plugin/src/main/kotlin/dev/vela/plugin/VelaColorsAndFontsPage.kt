// This file is a tombstone, and it exists because of a mistake of mine, not of the
// agent that wrote it.
//
// Two agents were told to write the Vela colour settings page, and both did:
// this file's `VelaColorsAndFontsPage` and the one in `VelaColorSettingsPage.kt`
// declare the *same* class in the *same* package.  Kotlin reports that as a
// redeclaration error, so the plugin would not have compiled — a collision caused
// by the work being assigned twice, which is an orchestrator's error and worth
// writing down rather than quietly deleting.
//
// **The survivor is `VelaColorSettingsPage.kt`**, for one measured reason: it lists
// all **14** keys of `VelaColors` (`KEYWORD`, `IDENT`, `NUMBER`, `STRING`, `COMMENT`,
// `OP`, `BRACES`, `BRACKETS`, `PARENS`, `COMMA`, `DOT`, `COLON`, `SEMI`, `BAD` —
// read from `VelaHighlighting.kt`, where `getTokenHighlights` maps 13 token kinds
// plus `TokenType.BAD_CHARACTER`), while the version that lived in this file listed
// 13 and therefore had no row for one of them.  A settings page with a missing row
// is a setting the reader cannot change and cannot explain.
//
// This file keeps a note rather than a second definition so the tree compiles; it
// should be deleted — with `Remove-Item`, once a shell exists — rather than left
// here forever, because an empty Kotlin file in a source set is a question the
// next reader has to answer.
//
// Nothing else was lost: `VelaFindUsages.kt` from the same batch is unique, it
// implements `FindUsagesProvider` with a real `WordsScanner`, and it stays.
