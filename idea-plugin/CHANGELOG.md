# Vela IDEA plugin — changelog

**English** | [简体中文](CHANGELOG.zh-CN.md)

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

## 0.1.4 — the verifiers are made falsifiable, and the AST window grows a second mode

**What changed, and why each one was a bug rather than a feature.**

1. **The go-to-declaration oracle was crashing on every file with an `extern`
   declaration, and the crash was invisible.** `GotoOracle.bindUseLines` answers
   `null` for "the compiler noticed nothing about renaming this declaration" and
   an empty list for "not verified", and the call site used the answer as if it
   were always a list. The enhanced-for over `null` threw
   `NullPointerException: Cannot invoke "java.util.List.iterator()"`, the per-file
   `catch (Throwable)` recorded it as "the oracle could not run", and the run
   still ended with `VERDICT: no reference goes to a declaration the compiler
   does not bind it to`. Nine files were never judged —
   `tests/probes/extern_*.vel`, `tests/build/extern/extern_c_probe.vel`,
   `selfhost/vela.vel`, `tests/run_tests.vel`, and two
   `tests/safety/cases/hole_*` files — and the verdict was a pass over whatever
   happened to survive. Fixed, and the `-` cache spelling the cached branch
   already understood is now what the null case writes.

2. **Every verifier now ends with a coverage triple, not a bare verdict.**
   `COVERAGE: ran <n> / skipped <n> (<why> <n>, …) / wrong <n>`. Every category a
   tool can skip for is **declared up front and printed even at zero**, because
   "0" and "this category was never counted" must not look the same, and
   `ran + skipped` is asserted against the corpus so a file that is neither
   compared nor categorised is a printed NOTE rather than silence. This is the
   general repair of the failure in (1): a verdict is now falsifiable by its own
   measurement.

3. **The verdicts now have exit codes.** `AstDiff`, `SymbolDiff`, `FoldDiff` and
   `HintNames` exited 0 unconditionally, so `VERDICT: FAIL` and
   `VERDICT: PASS` were indistinguishable to a build. Now: 1 = the plugin is
   wrong, 3 = the harness or the corpus is at fault, 0 = a pass. `GotoOracle`
   exits 3 for a crashed file instead of reporting a pass.

4. **A crash and a disagreement are counted apart.** `SymbolDiff` and `FoldDiff`
   counted a thrown exception as `differ`; `HintDiff` counted an argument
   position with no hint drawn as nothing at all. Both are now their own
   counters, and the missing-hint case is a finding.

5. **`AstDiff` names the manifest line for a corpus entry that points at
   nothing.** `tests/cases.txt:174` names `tests/build/lexer_error.vel`, which is
   not on disk, so the harness was FAIL with **zero** tree differences — 108 files
   identical over 119,923 node lines and one MISSING row. That is a defect of the
   corpus, which this plugin's owner does not own; it is now its own category
   (`missing-corpus-file`) with its own exit code, and it is reported rather than
   absorbed.

6. **`VelaSyntaxDump` is now reachable, so the "inert feature" check is
   satisfied honestly and not by an allowlist.** The verifier had begun failing it
   under `[check unregisteredImplementations]`: it compiled, shipped, and nothing
   in the jar could call it. It is now the **second mode of the Vela AST tool
   window** — `Live parse (editor buffer)` — which parses the text the editor is
   holding (unsaved edits included, and files that were never saved) and prints it
   in the compiler's own tree format. `vm.exe parse` can only ever describe the
   last saved bytes, so this is a capability and not a duplicate. The window has
   one view and one active mode, so the two modes cannot race for the same tree
   model; `Compiler (vm.exe parse)` is the default and the behaviour is unchanged.

7. **The oracle's cache write is safe under its own concurrency.** Eight threads
   called `Files.write` on one path every 200 new entries, so two could tear the
   file — and a torn cache is not a crash, it is a *wrong oracle* on the next run,
   because the value is a list of line numbers keyed by a declaration hash. It is
   now one writer at a time, through a temporary file and a move.

**What was measured, in this version**

- The four version locations read back at `0.1.4` (plugin.xml `<version>`,
  `build.gradle.kts` `version`, the dist zip's own name, the heading in this file).
- `build-offline.ps1` exit 0, `RESULT: PASS`, and the artifact it wrote:
  `dist\vela\lib\vela-idea-plugin.jar` **344,626 bytes** (sha256
  `0a36128e032a1b4fbd67e82d9d2f3fffe827d5e8905db762ed0072e152f0896e`) and
  `dist\vela-idea-plugin-0.1.4.zip` **324,198 bytes** (sha256
  `55bf14a3b68e5ccc3486bf9a6ec669fd2ce1dc48c859881704616128cb1409f7`, one entry:
  `vela/lib/vela-idea-plugin.jar`).  36 Kotlin sources, 145 class files, highest
  bytecode major 65 (Java 21).
- **What could NOT be verified about installability**: no IDE was launched, so
  "install this zip and it loads" is not measured here.  What *is* measured is
  everything short of that: the descriptor inside the jar is byte-identical to the
  source descriptor, every class named by `plugin.xml` is in the jar, every
  extension point id resolves to a declaration in the installed platform, the
  attribute each registration uses is the one the platform's own descriptor binds,
  all 30 registered classes are re-read out of the jar as instances of the exact
  interface the platform requires, and the class file major version is one IDEA
  2024.2+ accepts.
- The verifier's `RESULT: PASS`: 36 Kotlin sources, 145 class files, every
  registration read back against the platform's own declaration of its extension
  point, and the headless behaviour checks.
- `FEATURE_PARITY.md` is new: one row per user-visible capability, with what the
  Python plug-in does (grep out of `python-ce.jar`/`python.jar`/`python-dap.jar`
  at `PythonCore 253.28294.336`), Vela's state, the file that implements it, and
  the harness or platform registration that backs it.
- **`vm.exe debug` stays `refused-deliberately`.** It is not advertised anywhere
  in the plugin: `VelaRunConfiguration` implements
  `RunConfigurationWithSuppressedDefaultDebugAction` and the configuration is
  written to accept any profile that is not `Debug`, so IDEA shows the refusal
  instead of a button that would attach to nothing. No debugger was implemented
  and none is claimed.

**What was NOT verified, in this version**

- No IDE was launched. No IntelliJ instance can be started on this machine in
  this session, so *nothing* here is evidence that the plugin loads, that a
  tool window appears, that a menu entry is where it should be, or that any of
  these features looks right on screen. The strongest available evidence is the
  platform-registration read-back plus the headless differentials, and it is
  labelled as such in `FEATURE_PARITY.md`.
- The `Live parse (editor buffer)` mode is compiled and reachable; it has **not**
  been run inside an IDE. What is verified is that its parse is the compiler's
  parse (that is `ast-diff.ps1`), not that the Swing panel renders it.
- `classRegisteredNowhere` is now caught only where the class implements a
  contract an extension point requires; see `FEATURE_PARITY.md`'s verifier
  section for the precise remaining limit.



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
  ends in `.vel`, so the plugin did nothing at all on a real file. Now the
  descriptor reads `extensions="vel;vela"`, and `VelaFileType.EXTENSIONS` is
  `[vel, vela]` with `getDefaultExtension()` returning `vel` -- measured by
  `VerifyPlugin` section 6, which reads the attribute out of the jar's descriptor
  and the two constants out of the loaded class. (This entry said `vela;vela`
  until 0.1.4: the first suffix was wrong here while `plugin.xml`, `README.md` and
  the class itself all said `vel`. Corrected against the measurement, not against
  whichever spelling appeared first.)
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
