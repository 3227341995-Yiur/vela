# Vela IDEA plugin — changelog

**The rule, because the user asked for it explicitly: every plugin update bumps
the version, and every bump gets an entry here.**

The version lives in one place —
`src\main\resources\META-INF\plugin.xml`'s `<version>` — and everything else is
kept in step with it:

| where | how |
|---|---|
| `src\main\resources\META-INF\plugin.xml` | `<version>` — **the source of truth** |
| `build-offline.ps1` | reads the version out of `plugin.xml` and names the dist zip from it, so it cannot drift |
| `build.gradle.kts` | `version` and `sinceBuild`, for the Gradle path, kept in step by hand |
| this file | an entry saying what changed, **and what was verified and what was not** |

That last column is not ceremony. This project's older rule is that a claim
nobody measured does not exist, and this plugin has already shipped three
features that were finished and did nothing at all — a wrong file extension, an
action registered as a group, and three extension points that were never
registered (or registered under the wrong attribute name). An entry that says
"added X, unverified" is worth more than one that says "added X".

## 0.1.3 — built and verified; the first version that compiles

**Why there is a 0.1.3 at all.** 0.1.2's entry below says "unverified, not yet
built", and it was written that way honestly: the harness that runs commands on
this machine was dead for that whole round. The first round with a working shell
put 0.1.2's sources through `build-offline.ps1`, and they did **not** compile —
four errors, in two files, of a kind no amount of reading finds:

- `VelaFormatter.kt` — on this platform (`IU-262.9437.185`, kotlinc
  2.3.20) `ASTBlock.getSpacing` takes `Block?` as its first parameter, not
  `Block`: `VelaRootBlock` and `VelaLeafBlock` were reported both as "does not
  implement abstract member" and as "overrides nothing". Two signatures fixed.
- `VelaFindUsages.kt` — `private val wordsScanner` generated a
  `getWordsScanner()` accessor that clashes at the JVM level with the very
  interface method (`getWordsScanner`) the class overrides. The field is now
  `scannerOnce`, with the reason written at the declaration.

0.1.2 was therefore never built and never shipped; the four fixes are recorded
here rather than by editing 0.1.2's own entry, because a version's entry is what
that version was, not what it should have been.

**What was measured, in this version**

- All 32 Kotlin sources compile under the offline path (`kotlinc` from the
  installed IDE, no Gradle, no network). It is the first time anything in this
  plugin has been compiled at all.
- `jar` 256,668 bytes; `dist\vela-idea-plugin-0.1.3.zip` 240,264 bytes.
- The verifier's `RESULT: PASS` — every structural, bytecode, platform,
  registration, linkage and behavioural check (11.0 s; the full list is
  `build\logs\verify.log`, the machine-written surface is
  `build\logs\surface.txt`).
- **A verifier defect was found and fixed while getting there**, and it is worth
  naming because it is the same class of bug the verifier exists to catch: the
  `<with attribute="..." implements="...">` scan read a fixed 4000-character
  window after each `<extensionPoint>` and stopped only after 400, so the *next*
  declaration's attributes were credited to this one. `configurationType` is
  declared with `interface=` and has no `<with>` of its own, so it inherited its
  neighbour's spelling and eight of this plugin's registrations were reported as
  unbound attributes. The scan now reads only the declaration's own body — from
  the end of the tag to the first of `</extensionPoint>` and the next
  `<extensionPoint`, and nothing at all for a self-closing declaration. The eight
  are the platform's own spelling, checked against the platform's own plugins:
  `implementation=` for `configurationType`, `runConfigurationProducer`,
  `gotoDeclarationHandler`, `colorSettingsPage`, `typedHandler`,
  `enterHandlerDelegate`, `codeStyleSettingsProvider` and
  `langCodeStyleSettingsProvider` — every one of those points is registered that
  way, dozens of times, inside `D:\JetBrains\IntelliJ IDEA 2026.2.1\lib\*.jar`
  and `plugins\**\*.jar`.

**What is still not verified, and cannot be from here**: the plugin has never
been loaded into a running IDE. `ActionManager` and the extension registry need a
booted application, so folding, typing behaviour, the formatter's output and the
Run console are checked structurally, against bytecode and against the
descriptors — not by driving the editor. Installing the zip is what settles that.

## 0.1.2 — unverified, not yet built

Everything below exists as source in this tree and **nothing has been compiled or
run**: the harness that runs commands on this machine died after a build (a
detached `cl.exe` helper keeps its Windows job object non-empty), and it stayed
dead through the work. `idea-plugin/BUILD_CHECKLIST.md` holds the compile gate,
the most likely error sites, and the acceptance tests, so the first round with a
working shell can settle all of it in one pass.

**Editor mechanics — 10 Kotlin files, 11 registrations**
- Formatting: `VelaFormatRules.kt` (a pure function of the token sequence),
  `VelaFormatter.kt`, `VelaTokenScan.kt`, and code-style settings
  (`VelaCodeStyle.kt`) so the indent size is a visible setting.
- Folding (`VelaFolding.kt`), comments (`VelaCommenter.kt`), brace matching
  (`VelaBraceMatcher.kt`, `{ }` `( )` `[ ]` only), typed and enter handling
  (`VelaTypedHandler.kt`), a colour scheme page (`VelaColorSettingsPage.kt`), and
  live templates (`VelaLiveTemplates.kt` plus `resources\liveTemplates\Vela.xml`:
  `defn`, `main`, `struct`, `forr`, `whilee`, `ifel`, `parfor`).

**Navigation and insight**
- Go to declaration (`VelaGotoDeclaration.kt`), semantic colouring
  (`VelaSemanticHighlighting.kt`), parameter-name inlay hints
  (`VelaInlayHints.kt`), find usages (`VelaFindUsages.kt`, with a real
  `WordsScanner`), and the reference/rename plumbing (`VelaReferenceContributor`,
  `VelaLeafManipulator`).

**Registrations corrected**
- `psi.referenceContributor` was registered under `implementationClass`, which the
  platform ignores — the point's bean annotates its field as
  `@Attribute("implementation")`, so rename and find-usages were dead while
  Ctrl+Click worked. Fixed, with the three-way proof recorded in `plugin.xml`.
- Three classes were finished and never registered at all: the semantic annotator,
  the go-to-declaration handler, and the inlay-hints provider. All three are
  registered now, and the verifier is being taught to catch the class of bug.

**Run configuration**
- Run now interprets the file directly; no executable is produced, and the console
  prints the command line, the interpreter's output and the exit status.
- The console showed nothing at all, by construction: the configuration built its
  own `ConsoleView` and printed into that, while the Run toolwindow shows the one
  the platform creates for the handler. Output now goes through
  `notifyTextAvailable`, so the platform's console is the only console and the
  child's own stream type is carried through (diagnostics keep their error
  colour).
- **Run was the wrong shape and is now one child, not two.** It built first
  (`vm.exe build`) and then started the `.exe`, which needs a C compiler and
  MSVC's environment, writes `.c`/`.obj`/`.exe` beside the user's source, and
  inherits an include-path problem: the shipped `vm.exe` resolves its runtime
  include directory to the *relative* string `runtime`, so from the user's project
  directory — `vela\tests` — it died with
  `fatal error C1083: cannot open include file: 'vela_runtime.h'`, exit 2, and
  nothing reached the screen. `vm.exe run <file>` needs none of that. The run
  path is now: print the exact command line, start the interpreter, forward every
  byte it writes, propagate its exit status.
- The price is stated rather than hidden: what the console shows is the
  *interpreter's* output, which is slower than a compiled binary (the corpus's
  `run` cases measure both; the README puts the interpreter at ~76x on a loop MSVC
  cannot vectorise). Compiling remains `Vela.BuildAndRun`.
- A missing compiler is now reported **in the console** rather than only as a
  dialog: `checkConfiguration` still refuses a missing *file*, but the compiler is
  the run path's own business, so a run that cannot start explains itself in the
  place the user is looking.

**Known and deliberate gaps**, all named in `BUILD_CHECKLIST.md`: the Code Style
page's Spaces/Wrapping tabs are empty, the colour page lists the 14 lexer keys and
not the semantic kinds, Enter indents by a constant 4 rather than the reader's
setting, `<`/`>` are not paired (the lexer gives every operator one token type),
and postfix templates are absent with the reason written down.

## 0.1.1 — built and verified

- The file type was `extensions="vela"` only. Every Vela file in this repository
  ends in `.vel`, so the plugin did nothing at all on a real file. Now
  `vela;vela`.
- `<add-to-group group-id="Vela.BuildAndRun">` named an *action* as if it were a
  group: the platform logged
  `SEVERE ... should be instance of DefaultActionGroup` and neither action ever
  reached a menu. Replaced by one action in the platform's own `NewGroup`.
- `<parserDefinition>` named an id no installed descriptor declares (the real one
  is `lang.parserDefinition`), and `<lang.structureViewBuilder>` named a point
  that does not exist — both silent no-ops, both found by the verifier.
- Registered `runConfigurationProducer`, so IDEA's own Run menu, gutter arrow and
  re-run history offer a `.vel` file.

## 0.1.0 — built and verified

- First build: file type, syntax highlighting, diagnostics from the real compiler,
  a structure view, an AST tool window, a settings page for the compiler path, and
  a "Vela File" entry in the New menu.
