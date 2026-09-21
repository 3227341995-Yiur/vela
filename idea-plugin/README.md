# Vela for IntelliJ IDEA

**English** | [简体中文](README.zh-CN.md)

An IDEA plugin for `.vel` files (and `.vela`, the same name spelled out).  It is
thin on purpose: **the compiler is the only thing that decides what a Vela
program means**, and this plugin asks it.

| what | how |
|---|---|
| `.vel` / `.vela` file type + editor | `VelaFileType`, whose `extensions="vel;vela"` is load-bearing: the first release claimed only `.vela` and therefore did nothing at all on a real Vela file |
| syntax highlighting | `VelaLexer` + `VelaSyntaxHighlighter`, colours taken from the platform's own keys so it follows the user's scheme |
| semantic colouring | `VelaSemanticAnnotator` colours a *name* by what it means, from `VelaModel` |
| **errors and warnings, in the editor** | `VelaExternalAnnotator` runs `vm.exe check <file>` and draws exactly what it says, on the line it says |
| completion, hover, parameter info | `VelaCompletionContributor`, `VelaDocumentationProvider`, `VelaParameterInfoHandler` — all reading the same model |
| go to declaration, rename, find usages | `VelaGotoDeclarationHandler`, `VelaReferenceContributor`, `VelaLeafManipulator`, `VelaFindUsagesProvider` |
| inlay hints | `VelaParameterNameInlayHintsProvider` — the parameter name the callee declared, drawn beside the argument |
| Structure view, AST window | `VelaPsiStructureViewFactory`, the "Vela AST" tool window |
| formatting, folding, comments, brackets, typing | `VelaFormattingModelBuilder`, `VelaFoldingBuilder`, `VelaCommenter`, `VelaPairedBraceMatcher`, `VelaTypedHandlerDelegate`; indent size under Code Style → Vela |
| colour scheme page, live templates | `VelaColorsAndFontsPage` (the 14 lexer keys), templates `defn` / `main` / `struct` / `forr` / `whilee` / `ifel` / `parfor` |
| **running a file** | IDEA's own Run menu, gutter arrow and re-run history, through `VelaRunConfigurationProducer` — and Run **interprets**: `vm.exe run <file>`. No build step, no executable written beside the program, no C toolchain needed, output straight into the console.  That is deliberate: a Run button that compiles first is slow, leaves an artifact next to the source, and inherits the include-path problem class the first version died of (`fatal error C1083: cannot open include file: 'vela_runtime.h'`, from a project directory that is not the repository root).  There is no private "Vela" submenu and no "Check" action any more: the submenu duplicated IDEA's Run worse, and Check duplicated the live diagnostics, which are the compiler's own words earlier |
| debug | **refused on purpose.**  The compiler has a `debug` mode in its sources and no promoted binary that answers it, so the run configuration suppresses the Debug action instead of offering a button that would attach to nothing |
| where the compiler is | Settings → Languages & Frameworks → Vela, else `$VELA_VM`, else any directory up to six parents, else `PATH` |

As of **0.1.3**, everything in that table is **compiled and verified**: all 32
Kotlin sources build under the offline path, the artifact is
`dist\vela-idea-plugin-0.1.3.zip`, and `build-offline.ps1` ends with
`RESULT: PASS` — every structural, bytecode, platform, registration, linkage and
behavioural check green (`build\logs\verify.log` has the list,
`build\logs\surface.txt` the machine-written surface).  It was 0.1.2 that had never
been compiled; its first compile found four errors in two files, listed in
`CHANGELOG.md`.  What is still unverified is the **IDE itself**: these checks read
descriptors, bytecode and class files, and behavioural questions in the editor are
settled by installing the zip, not from here.  Every registration is verified
against the platform's own declaration — `PLUGIN_SURFACE.md` records the
declaration each one was read from — because three features in this plugin's short
life shipped finished and completely inert, once for a wrong attribute name.

## Why the annotations are the real thing

The compiler writes its refusal in two lines and this plugin reads those two
lines and nothing else:

```
vela: safety error: 'parallel for' writes to 'a', which is not a mutable array local to this function
  at /home/me/sum.vela:7
```

So there is no second checker to drift out of date, and no pattern-matching on
English: the `kind` is a field, the message is the message.  Every rule Vela
enforces at build time — including the `parallel for` race proofs and the
"stricter than Python" refusals — is a squiggle while you type, for free.

## Build and install

### On this machine: offline, no Gradle, no network

```powershell
cd idea-plugin
.\build-offline.ps1
```

`gradlew buildPlugin` cannot run here: there is no JDK, no Gradle and no
`~/.gradle` cache on the PATH, and the IntelliJ Platform Gradle plugin wants to
download both Gradle and a second copy of the IDE.  What this machine *does* have
is an installed IDE, and an IDE carries everything needed to build a plugin for
itself:

| what | where |
|---|---|
| platform to compile against | `D:\JetBrains\IntelliJ IDEA 2026.2.1` (build IU-262.9437.185) |
| JDK (JetBrains Runtime) | `<ide>\jbr` — `javac` and `jar` included (OpenJDK 25.0.3) |
| Kotlin compiler | `<ide>\plugins\Kotlin\kotlinc\lib\kotlin-compiler.jar` (2.3.20) |

`build-offline.ps1` compiles the Kotlin sources against that platform's own 429
jars, packs the result, wraps it in the ZIP the IDE expects, and then *verifies*
the artifact — 60 checks, each printing `OK` or `FAIL`, ending in
`RESULT: PASS` / `RESULT: FAIL` and exiting non-zero when anything fails:

| layer | what it proves |
|---|---|
| 1 structure | `META-INF/plugin.xml`, both icons, and a `.class` in the jar for every FQN named by *any* class-carrying attribute in `plugin.xml` (`implementationClass`, `instance`, `factoryClass`, `class`, `implementation`, `serviceImplementation`) |
| 2 bytecode | 148 class files, highest class-file major 65 (Java 21) — measured on the 0.1.4 build of 2026-09-22 00:26 |
| 3 platform | the installed IDE's own descriptors are read and indexed — 2705 XML descriptors out of 2356 jars, giving 297 plugin/module ids, 1773 extension point ids and 5277 action/group ids (0.1.4 build) |
| 4 extensions | every `<extensions>` entry names an extension point the installed platform declares, or that another installed plugin registers an extension under |
| 5 depends | every `<depends>` module is one the installed platform provides |
| 6 file type | `<fileType extensions="vel;vela">`, `VelaFileType.getDefaultExtension() = vel`, `EXTENSIONS = [vel, vela]`, `isVelaFileName("x.vel")` / `("x.vela")`; and every `language="..."` attribute equals the id of the language the file type is bound to |
| 7 actions | every `<add-to-group group-id="X">` resolves to a **group** — one declared in `plugin.xml`, or one the installed platform defines; an id that is only an `<action>` fails here, which is what 0.1.0 shipped.  This plugin now registers **one action** (`Vela.NewFile` -> `NewGroup`) and **no group at all**: the `Vela` submenu and its two actions were deleted, and running a Vela file comes from `runConfigurationProducer` instead, so the platform supplies the Run menu, the gutter arrow, the Run window and Debug |
| 8 linkage | every named class loads out of the jar against the installed platform and implements the type its extension point requires — the requirement is taken from the platform's own `<with attribute=... implements=...>` declaration |
| 9 behaviour | the lexer on real Vela text, the diagnostic reader on the compiler's two-line format, and the path the user actually sees, end to end: a real `tests/build/*.vel` fed to `vm.exe check`, its stderr read the way `VelaAnnotator` reads it |

```
    OK  VelaLexer produced 54 tokens; first 16:
        VELA_COMMENT[0,9) WHITE_SPACE[9,10) VELA_KEYWORD[10,13) ...
    OK  arith_basics.vel (accepted)      -> 0 problem(s), which is what the editor would draw
    OK  truthiness.vel (refused)         -> 1 problem on line 3, kind "type error"
    OK  same refusal, 4 lines lower      -> problem moves to line 7 (line numbers are read, not guessed)
    OK  VelaCompiler.run+parse           -> exit 2, same 1 problem (the annotator's own call path)
```

Two routes, named rather than blurred: structure, bytecode and linkage are proven
by loading the built jar; extension points, module ids and action-group ids are
proven against the installed IDE's own descriptors — a *descriptor scan*, not a
live `ActionManager`, because the platform's `ActionManager` and extension
registry need a booted application and cannot be asked headlessly.  A group id
registered only from code would therefore be reported as unknown.

**Current verdict, 0.1.4 — re-measured, not carried forward.** Every number below
was read back out of the build of 2026-09-22 00:26 and is named with the version
it belongs to, because this block said `0.1.1` and `60 OK` for three releases
while `dist\` held `0.1.3`, which is the kind of stale line a reader is entitled
to be angry about:

```
build-offline.ps1            exit 0, RESULT: PASS   (build\logs\verify.log)
artifact                     dist\vela\lib\vela-idea-plugin.jar   344 619 bytes
                             dist\vela-idea-plugin-0.1.4.zip      324 186 bytes
                             sha256 a02f79759ea8a7b917f530a772b2a2b179dedcaed19dc6b059e31c563abc3033
sources                      36 Kotlin file(s) -> 148 class file(s), highest major 65 (Java 21)
compiler warnings            none ("no warnings, no errors")
dead classes                 0 (98 concrete top-level classes: 29 named by plugin.xml, 69 reached)
surface inventory            build\logs\surface.txt (36 fact(s))
```

`0.1.1`'s record, for the history and not for the current claim: 60 OK, jar
180 613 bytes, `dist\vela-idea-plugin-0.1.1.zip` 169 056 bytes, 19 Kotlin
sources. The `0.1.1` zip is still in `dist\`, which is why the version had to be
named in this block rather than inferred from the directory.

The inventory the verifier reads 鈥?every extension point id with the class it
names, the action, the `add-to-group` target, the file type's suffixes and its
default extension 鈥?is written to `build\logs\surface.txt`, one sorted fact per
line.  A summary can quote that instead of transcribing, and the next change to
`plugin.xml` diffs against the last verified run.

**What the checks are for**: four registrations in this file have now been wrong
in the same way — an id the platform does not have, which makes the plugin load
and *do nothing*, with no error anyone can see from inside:

| the bug | how it was found |
|---|---|
| `extensions="vela"` with no `vel` (0.1.0): the plugin did nothing on a real `.vel` file | caught by mutation test A (`build\tools\negative-tests.ps1`) |
| `add-to-group group-id="Vela.BuildAndRun"` (0.1.0): an `<action id>` used as a group, `SEVERE ... should be instance of DefaultActionGroup`, neither action in any menu | caught by mutation test B |
| `<parserDefinition ...>`: id `com.intellij.parserDefinition`, declared by nothing; the PSI tree never existed | caught on the first real run, fixed to `<lang.parserDefinition>` |
| `<lang.structureViewBuilder language="Vela" .../>`: id `com.intellij.lang.structureViewBuilder`, in none of the 1987 jars | caught on the first real run, fixed to `<lang.psiStructureViewFactory>` |

The two 0.1.0 bugs are proven caught rather than asserted: `build\tools\negative-tests.ps1`
rebuilds the jar with each bug re-introduced (plus a missing class, an unknown
group id, a wrong `language=`, the bare `<parserDefinition>`, and the `.vela`-only
extensions) and requires the verifier to fail every one of them.  Last run: **6/6
caught, 0 missed** (`build\logs\negative-tests.log`).  It is not part of the build,
because the build should stay fast; run it after changing the verifier.

A further gap was closed rather than found: the previous verifier looked only at
`<action class=...>`, `implementationClass` and `instance`, so
`<configurationType implementation="..."/>` and `<toolWindow factoryClass="..."/>`
named classes nothing ever loaded.  Every class-carrying attribute is checked now,
and each class must also *implement* the type its extension point requires.

### The `since-build="253"` claim, measured

`plugin.xml` says `since-build="253"`, which claims that every platform class and
member the plugin uses exists in PyCharm 2025.3 as well as in IntelliJ IDEA
2026.2.  Both halves are now checked, because PyCharm 2025.3 *is* installed on
this machine (`D:\JetBrains\PyCharm 2025.3`, build `PY-253.28294.336`, 139
platform jars):

```
compile against PyCharm 2025.3 (253),  139 jars  -> rc=0, no errors
compile against IntelliJ IDEA 2026.2 (262), 429 jars -> rc=0, no errors, no warnings

build-offline.ps1 -PlatformHome "D:\JetBrains\PyCharm 2025.3"      (0.1.4, re-measured)
    RESULT: PASS, exit 0              (build\logs\verify-253.log)
    139 jars on the compile classpath; verified against 253's own descriptors:
    1656 descriptors out of 1249 jars, 246 plugin/module ids, 1259 extension points,
    4879 action/group ids, 1079 required contract types
    jar 344 574 bytes, zip 324 147 bytes
    sha256 of that zip: b43c480b71e59843b85002e183cc1aa04f38b4eeb03d8926ef9d5bccda206fc2

build-offline.ps1                      (IntelliJ 2026.2 -- the artifact in dist\)
    RESULT: PASS, exit 0              (build\logs\verify.log)
    429 jars on the compile classpath; 2705 descriptors out of 2356 jars,
    297 plugin/module ids, 1773 extension points, 5277 action/group ids
    jar 344 619 bytes, zip 324 186 bytes
    sha256 of that zip: a02f79759ea8a7b917f530a772b2a2b179dedcaed19dc6b059e31c563abc3033
```

**This block was a promise until 0.1.4, and keeping it found a real defect.** The two
commands above are now actually re-run against the final shape, and the first re-run
**failed**: PyCharm 2025.3 rejected the sources with
`VelaRunConfig.kt:569:31: error: unresolved reference 'isSystem'` --
`ProcessOutputType.isSystem(Key)` exists in 262 and not in 253, so the sources did not
compile against the platform `plugin.xml` claims to support and `since-build="253"` was
**false**. The line now asks the same question with an identity comparison
(`outputType === ProcessOutputType.SYSTEM`), which exists on both platforms and cannot
throw the way `ProcessOutputType.fromKey` does. Both builds pass above, from this same
tree. The two jars differ by 45 bytes (compiler metadata), which is why both sizes are
quoted rather than one standing for both.

What this does **not** prove: runtime behaviour in either IDE.  Compiling and
linking against both platforms says that every class and member the plugin names
is present in both; it says nothing about what those APIs do once the IDE is
running.  That gap is the one named in "What the offline checks do not cover", below.
(Both blocks above were re-measured against the final shape as of 0.1.4; the note
under them is where the `isSystem` defect was found.)


Install with **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick
`dist\vela-idea-plugin-0.1.4.zip` (or point the IDE at the unpacked `dist\vela\`).
Every class `plugin.xml` names is loaded out of the built jar and linked against
the platform it will run in — the failure that otherwise surfaces only as "the
plugin does not load", with no explanation of why.

### Anywhere else: Gradle

`build.gradle.kts` is kept, and works wherever Gradle can reach the network:
`./gradlew buildPlugin` produces the same plugin under `build/distributions/`.
`intellijIdeaCommunity(...)` targets IntelliJ IDEA Community 2024.2.4 with
`sinceBuild = 242`; bump both together when the target IDE moves.  Not verified
here — nothing on this machine can run it.

### Point it at the compiler

**Settings → Languages & Frameworks → Vela → Compiler**, e.g.
`C:\Users\lu\Downloads\vela\selfhost\build\vm.exe`.  Leave it empty and the plugin
looks at `$VELA_VM`, then `<project>/selfhost/build/vm.exe`, then `PATH`.

Building the compiler itself needs no Python and no Gradle — it is one PowerShell
script from a checked-in C file:

```powershell
powershell -File ..\tools\build.ps1 -Suites
```

## What it does not do yet

Named rather than forgotten — each is a real feature, and each has a reason:

* **No go-to-definition, no rename.**  Both need a PSI, which means a parser *in
  the plugin* or a structured dump *from the compiler*.  The right answer is the
  second one — `vm.exe parse` already walks the tree, and a JSON-shaped version of
  it is a plugin feature and a compiler feature at once — and it is the next thing
  to do here.  Completion, hover, parameter info and the structure view are
  answered from the *lexer* instead (`VelaModel.kt`), which is why they are name-level
  and honest about it: they show where a name is written, not what it means.
* **No formatter**, and no folding beyond what the platform derives.  (The
  formatter that used to ship with the deleted web IDE went with it; a formatter
  belongs here, not in a toolchain script.)
* Annotations are **whole-line**, not a caret under the exact token; the
  compiler reports a line, and inventing a column would be inventing data.
* No debugger.  `extern c` (stage 5) is now real for scalar parameters, so a
  C library can be *called* — but there is no debugger to step into it with.

### What the offline checks do not cover

Named plainly, because an unverified claim is worse than a known gap: these
checks load the jar, not the IDE.  They do **not** boot an IntelliJ application,
so they cannot see whether a menu entry actually appears, whether a squiggle is
actually drawn where the compiler's line says, whether the tool window opens, or
whether completion pops up under a caret.  Action-group and extension-point ids
are resolved from the installed IDE's own descriptors rather than from a live
`ActionManager`, and a group registered only from code would be reported as
unknown.  The one path that *is* proven end to end is diagnostics: a real
`tests/build/*.vel`, the real `vm.exe check`, and the plugin's own reader over its
stderr.  Everything else in this table is a statement about the artifact, the
platform's API surface, and the plugin's own functions.

## Layout

```
build-offline.ps1, build\tools\src\*.java                  the offline build + VerifyPlugin
build.gradle.kts, settings.gradle.kts, gradle.properties   the Gradle build (cannot run here)
src/main/resources/META-INF/plugin.xml                     every extension point
src/main/kotlin/dev/vela/plugin/VelaLanguage.kt            language, file type, lexer, token types
src/main/kotlin/dev/vela/plugin/VelaHighlighting.kt        colours
src/main/kotlin/dev/vela/plugin/VelaCompiler.kt            find the compiler, run it, read it
src/main/kotlin/dev/vela/plugin/VelaDiagnostics.kt         the problems the annotator draws
src/main/kotlin/dev/vela/plugin/VelaAnnotator.kt           the external annotator + settings page
src/main/kotlin/dev/vela/plugin/VelaActions.kt             Check, Build and Run
src/main/kotlin/dev/vela/plugin/VelaModel.kt               names, read with the lexer
src/main/kotlin/dev/vela/plugin/VelaNames.kt               what a name at an offset could mean
src/main/kotlin/dev/vela/plugin/VelaParserDefinition.kt    a flat token PSI: no grammar, no second opinion
src/main/kotlin/dev/vela/plugin/VelaCompletion.kt          completion
src/main/kotlin/dev/vela/plugin/VelaDocumentation.kt       hover
src/main/kotlin/dev/vela/plugin/VelaParameterInfo.kt       parameter hints
src/main/kotlin/dev/vela/plugin/VelaStructureView.kt       the Structure view
src/main/kotlin/dev/vela/plugin/VelaAstToolWindow.kt       the syntax-tree tool window
src/main/kotlin/dev/vela/plugin/VelaRunConfig.kt           the run configuration type
src/main/kotlin/dev/vela/plugin/VelaNewFile.kt             New Vela File
```

The lexer here is the *highlighter's* lexer, written to the same rules as
`selfhost/vela.vel` (same keywords, same operators, same escapes).  If the two
ever disagree the compiler still wins — it is the one that gets asked.
