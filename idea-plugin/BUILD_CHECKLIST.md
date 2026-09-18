# The compile gate — what it found, and what to look at next time

Written while the command runner on this machine was wedged, then **run**.  The
gate is no longer a hope: §0 records what the first real compile found.  Everything
below §0 remains as the list of places to look when the platform changes, because
the same class of error will come back the next time the IDE is upgraded.

## 0. Result of the first compile (0.1.3, platform IU-262.9437.185, kotlinc 2.3.20)

The gate was run as written — the whole source set, the entire output, nothing
filtered — and it took **three** passes to get to green:

| pass | what it said | what it was |
|---|---|---|
| 1 | `VelaFormatter.kt:95/137/158/172` | `ASTBlock.getSpacing` takes `Block?` as its first parameter on this platform, not `Block`.  Both classes were reported twice: "does not implement abstract member" *and* "overrides nothing".  §2 item 1 predicted exactly this shape of failure, and was right |
| 2 | `VelaFindUsages.kt:56/65` | `private val wordsScanner` generated a `getWordsScanner()` accessor clashing at the JVM level with the `FindUsagesProvider` method the class overrides.  Renamed `scannerOnce` |
| 3 | — | `RESULT: PASS`, all checks green, `dist\vela-idea-plugin-0.1.3.zip` |

Two of the four predictions below were right, one file's problem was a different
one entirely (the JVM signature clash — nothing to do with nullability), and the
rest were never reached because the compile stopped before the verifier could ask.
That is the honest score of a checklist written without a compiler.

**The counts, scoped, because they read as a contradiction until someone checked.**
This file is about **one tranche**: the editor-mechanics work, eight items written
by a single agent as 10 Kotlin sources, one resource file and eleven registrations.
Other agents wrote other plugin files during the same outage — go to declaration,
semantic colouring, parameter-name inlay hints, find usages, and the Run path's
restructure — and `CHANGELOG.md` counts those. So "eight features, ten files" here
and "about thirteen features" there are both right; the unscoped version is what
looked wrong. The honest state then was "source exists, feature unproven"; as of
0.1.3 the compile and the whole verifier are green, and what is still unproven is
behaviour in a **running** IDE.

`PLUGIN_SURFACE.md` holds the verified extension-point table; this file holds the
gate, what it found, and what it cannot settle from here.

## 1. How to run the gate

There **is** a known-good compile now — the one §0 describes — so re-run it the
same way: the whole source set, the entire output pasted unfiltered.  One property
of that first run is worth copying down: the compiler stopped at the first file
with errors, so a green report from a *filtered* log proves less than it looks.
Then compile again against `D:\JetBrains\PyCharm 2025.3` (253), because the
descriptor claims `since-build="253"` and only the 0.1.1 source set has been
through that compile.

## 2. The most likely error sites, in the order I would look

1. **Nullability annotations that were assumed rather than read** — `Block`'s
   `getIndent`/`getSpacing`/`getWrap`/`getAlignment`, `ChildAttributes(…, null)`,
   the second parameter of `isPairedBracesAllowedBeforeType`, and
   `AttributesDescriptor(String, TextAttributeKey)`'s String overload (possibly
   deprecated, and warnings are not acceptable). These are standard-plugin idioms,
   not dumps, and Kotlin turns a nullable mismatch into an error.
2. **`CodeStyleSettings.getCommonSettings(Language)`** — its existence is inferred
   from the JSON formatter's bytecode calling `getCommonSettings`, but its
   overloads were never listed, so the `Language`-vs-`FileType` choice is
   unverified. One call site, `VelaFormatter.kt`.
3. **The `Result` enum workaround.** `EnterHandlerDelegate$Result` was read as
   `Default, Continue, DefaultForceIndent, DefaultSkipIndent, Stop` (PascalCase;
   older releases used `CONTINUE`). The shell died before the same probe could be
   run for `TypedHandlerDelegate$Result` or either enum under 253 — and this source
   set is compiled against both platforms, so hard-coding either spelling is a coin
   flip. The values are therefore looked up **by name at class load**, preferring
   `Continue` and falling back to the first constant. That compiles under both
   spellings; it is a workaround for an unverified identifier and is labelled as
   one in the source.
4. **The live-template XML schema.** `liveTemplates/Vela.xml` was written from the
   standard form (`<templateSet group>`, `<template name value description
   toReformat toShortenFQNames context>`, `<variable name expression defaultValue
   alwaysStopAt>`) and never diffed against a bundled file. The first thing to
   check is `context="VELA"` versus the older
   `<context><option name="VELA" value="true"/></context>` child form.

## 3. The eleven registrations to verify against the platform's own declaration

```
lang.formatter            implementationClass   language="Vela"
codeStyleSettingsProvider implementation
langCodeStyleSettingsProvider implementation
lang.foldingBuilder       implementationClass   language="Vela"
lang.commenter            implementationClass   language="Vela"
lang.braceMatcher         implementationClass   language="Vela"
typedHandler              implementation
enterHandlerDelegate      implementation        order="first"
colorSettingsPage         implementation
defaultLiveTemplates      file="liveTemplates/Vela.xml"
liveTemplateContext       implementation        contextId="VELA"
```
Three of them are the shape people get wrong, and each has a documented reason:
`typedHandler`/`enterHandlerDelegate` are declared with `interface=`, so they take
**`implementation`** and are **not language-keyed** (there is no `language`
attribute — the classes test `isVelaFileName` themselves); `liveTemplateContext`
is declared `beanClass="…LiveTemplateContextBean"` with
`<with attribute="implementation" …>`, so `implementationClass` there would be the
fourth silent no-op in this plugin's history.

## 4. The formatter's acceptance test, and what would falsify it

Run against a **scratch** plugin dir under `vela\.work\editor\` — never `dist\`:

1. compile the source set to a scratch out dir;
2. assemble `classes + src\main\resources` so `META-INF/plugin.xml` and
   `liveTemplates/Vela.xml` travel with it;
3. copy `tests\build\*.vel` to a scratch corpus;
4. `D:\JetBrains\IntelliJ IDEA 2026.2.1\bin\format.bat -r -allowDefaults` over it,
   with `IDEA_PROPERTIES` pointing config/system/log/plugins at the scratch tree
   (proven to boot before the outage: it printed its usage and exited 0);
5. run it a second time and diff — **idempotence**;
6. tokenise before and after with the plugin's own `VelaLexer` and require an
   identical sequence of (type, text) — **token preservation**.

Falsifiers, in the order they should be believed:

| observation | what it means |
|---|---|
| the corpus is byte-identical before and after | the `lang.formatter` registration is not being reached at all — the "registered but inert" failure this plugin has shipped three times |
| engine output differs from `VelaFormatRules` applied by hand | the reading of `Spacing`/`Indent` is wrong (which is why the rules are a pure function of the token sequence and *can* be applied by hand) |
| format twice ≠ format once | a rule is order-dependent |
| a token changed | the meaning-preservation claim is dead |

## 5. Gaps that are known, deliberate, and must not be described as done

- **`customizeSettings` is not overridden**, so the Code Style page's Spaces,
  Wrapping and Blank Lines tabs are empty. Filling them means naming
  `CodeStyleSettingsCustomizable`'s option constants, which were never dumped —
  a guessed constant is a compile error, so it was left undone rather than
  guessed. **Unverified: whether registering both `codeStyleSettingsProvider` and
  `langCodeStyleSettingsProvider` yields one Vela page or two** (the JSON plugin
  registers both; the CSS plugin registers only the language one).
- **The semantic annotator's colours are not on the colour settings page.** The
  page lists every key `VelaSyntaxHighlighter` can return (14 rows, each naming
  the same `TextAttributesKey` object the highlighter returns, so a row is a real
  setting rather than a decoy). `VelaSemanticHighlighting` colours *names by
  meaning* and declares no keys of its own — showing those needs keys that do not
  exist yet, in a file owned by someone else.
- **`<` and `>` are not registered as a brace pair**, and cannot be: the plugin's
  lexer gives every operator the single type `VelaTokenTypes.OPERATOR`, so a pair
  would match `+` with `<`. For the same reason every pair the matcher *can*
  register has left == right type (`(`/`)` both `PARENS`, `[`/`]` both `BRACKETS`,
  `{`/`}` both `BRACES`), which leaves the left-vs-right discrimination to the
  platform's brace-matching layer. **That last claim was not verified from the
  bytecode.** Cosmetic consequence: a caret between two identical brackets (`((`)
  may be highlighted as a pair.
- **Enter's indentation uses the constant 4**, not the reader's configured indent
  size: that would need a `Project`→`CodeStyleSettings` read and a second settings
  read site. Stated in the source rather than hidden.
- **The commenter contributes only the contract.** `#` as the line prefix, the
  other four members null, and the comment/uncomment *behaviour* is the platform's
  own `CommentByLineCommentAction`. Nothing of the plugin's is exercised beyond
  the prefix string. (`Commenter` has no constants in 262 — there is no
  `END_OF_LINE_COMMENT` field — so `"#"` is a literal.)
- **Postfix templates are deliberately absent**, with the reason in the template
  file: a postfix template earns its place when a type expression can be appended
  to an existing expression, and Vela has no null-safe operators and no
  lambdas-appending constructs; its only suffix, `: T`, is a declaration.

## 6. Scratch directories

The standing rule is that every working directory lives under `vela\`.
`C:\Users\lu\Downloads\_editor\` was the one violation — it holds read-only API
dumps and three listing tools, nothing that is a deliverable, and every attempt to
move it died with the wedged shell. Move it to `vela\.work\editor\` first when
commands work again.
