# Modules and `import` — the plan, the decisions, and what each step has to prove

**English** | [简体中文](MODULES_PLAN.zh-CN.md)

`ROADMAP.md` Stream 2 item 2 states the goal and the acceptance in one place, so
this file does not restate the goal; it turns it into a ladder and writes down the
decisions that cannot be discovered by writing the first function.  It exists
before the code for the reason this repository keeps re-learning: **a claim that
is not measured does not exist**, and a design that is only in an agent's head is
not a design a reader can disagree with.

## 1. Where the language says it stands (the documents)

| what | where | the words |
|---|---|---|
| the reserved words | `SPEC.md` §1.3, lines 62-66 | "`import`, `from` and `as` are **not** keywords in 0.1: they are ordinary identifiers (`mut import: int = 1` is a legal declaration) and nothing refuses them.  A module system would claim them, so they are reserved by documentation and not by the lexer — a program that uses them as names will need renaming the day one arrives." |
| no module system, as a design fact | `DESIGN.md` §2, lines 123-124 | "`tools/link_selfhost.vel` splices the parts into `selfhost/vm.vel`, because Vela 0.1 has no module system (§7.1)." |
| the workarounds, in their own words | `tools/link_selfhost.vel` lines 1-31 | "Vela 0.1 has no module system (`import` is reserved and unimplemented), so a multi-file Vela program is a build-time concatenation — the same trick C plays with `#include`, and **it disappears the day Vela grows modules**." |
| the acceptance this file exists for | `ROADMAP.md` Stream 2 item 2 | "a two-file program compiles; the self-hosting build no longer needs the linker; the IDE resolves a name defined in another file" |
| the same sentence, from the parser's side | `selfhost/vm.vel` line 1320 (generated from `selfhost/parts/parser.vel`) | "…system (`import` is reserved but not implemented), so `tools/link_selfhost.vel`…" |

## 2. What is true today, measured (2026-09-25, `selfhost/build/vm.exe` 870 400 B)

Every line below was run, not read.  The commands are given so the next reader can
re-take any of them.

**2.1 `import` is an ordinary identifier, exactly as `SPEC.md` §1.3 says.**  A
program with `mut import: int = 1`, `mut from: int = 2`, `mut as: int = 3` then
`print(import + from + as)`:

    vm.exe run   <that program>   ->  exit 0, prints 6
    vm.exe check <that program>   ->  exit 0, prints "ok"

**2.2 The syntax a module system wants is a syntax error today**, and the message
is about the wrong thing:

    vm.exe check <import "other.vel" inside main()>   ->  exit 2
        vela: syntax error: expected a newline between statements, found 'other.vel'
          at line 3

`import` parsed as a *name expression* and the string literal after it had nowhere
to go.  The complaint names the string's contents, not the construct.

**2.3 The command line takes exactly one file, in every mode.**  `vm.exe` with no
arguments prints:

    usage: vela <lex|count|parse|nodes|emit-c|run|check|debug|build|build-c|emit-llvm|build-llvm> <file.vel>

and `main()` (`selfhost/parts/vm_main.vel` lines 1189-1216) reads one `arg(2)` into
one `src: str`, then declares every pool on its own stack frame: tokens 1 572 864,
token floats 65 536, nodes 1 310 720 ints (stride 10 → 131 072 nodes), types
262 144, literal floats 65 536.

**2.4 There is headroom for a merged program, measured.**  The largest input this
tree ever compiles is the *linked* compiler, `selfhost/vm.vel`:

    vm.exe count selfhost/vm.vel            ->  tokens 130526
    vm.exe count selfhost/parts/parser.vel  ->  tokens 12641
    vm.exe count selfhost/parts/check.vel   ->  tokens 23750
    vm.exe count selfhost/vela.vel          ->  tokens 4164

130 526 of 1 572 864 is **12×** headroom, so loading several files into one token
array is not a pool question.  It is also the reason the *linker's* own problem does
not transfer: `tools/link_selfhost.vel` had to be built and run rather than
interpreted because *it assembles a 338 KB file out of a thousand concatenations*
and exhausts the interpreter's string table.  A loader reads N files and calls
`read_text` N times; it does not concatenate.

**2.5 The limit that *is* real: 8192 interned names.**  `VELA_INTERN_CAP 8192`
(`runtime/vela_runtime.h` lines 688-698) is content-addressed and exhausting it is
a hard `panic("string table full")`.  A module system adds names only if the
imported files declare names the program did not already have, and the *whole tree*
currently fits, so this is a risk to measure at step 5 (the compiler imports
itself) rather than a blocker here.  One side-note found while measuring:
`selfhost/AST.md` line 304 cites the cap as `:442`, which is not where it lives.

**2.6 Diagnostics do not agree about the file name, and that is the crux.**  The
parser and lexer print a bare line:

    vela: syntax error: adjacent string literals are not allowed
      at line 3

(`selfhost/parts/parser.vel` lines 89-100).  The resolver, the checker and the
interpreter's traps print a path **and** a line:

    vela: safety error: cannot rebind array 'a'
      at tests/build/check_cases/array_rebind.vel:4

(`selfhost/parts/resolve.vel` lines 35-46; `selfhost/parts/eval.vel` lines 27-36).
The goldens record that text verbatim — `tests/golden/adjacent_string_literals.err`
and `tests/golden/array_rebind.err` are the two examples above — so **any change to
a single-file diagnostic is a corpus regression by definition.**

**2.7 The emitted C embeds the path at every call site.**  `emit_location`
(`selfhost/parts/emit.vel` lines 339-349) writes the source path as a C literal for
the runtime's panic messages, and the generated C shows it:

    vela_add_range((vl_tk_off(...)), (1LL), INT64_MIN, INT64_MAX, "selfhost/vm.vel", 1519)

One program, one path, thousands of call sites.  With two files, every site in the
imported file would carry the *root* file's path.

**2.8 The build's shape, because step 5 changes it.**  `tools/build.ps1` runs eight
steps; the ones this plan touches are step 3 (`build.ps1` lines 613-615: build
`tools/link_selfhost.vel`, then run it to write `selfhost/vm.vel`), step 5 (line
819: build the standalone lexer `selfhost/vela.vel`), and step 6 (the fixpoint,
which asks two generations of the compiler for the C of `selfhost/vm.vel` and
compares the bytes and hashes).

**2.9 The lexer's keyword table is a numbered list** (`selfhost/vela.vel` lines
158-244): `def`=1 … `extern`=23, `enum`=24, `match`=25, and the comment above 24-25
records the practice this plan must follow — before `enum`/`match` became keywords
an agent checked every `.vel` file in the tree for the words used as names, and the
comment says so.

## 3. The decisions

Each decision names the alternative it rejected and the reason, because a decision
without a rejected alternative is a preference.

**D1 — the syntax is `import "relative/path.vel"` at top level.**  A path *string*,
not a bare name.  A bare name (`import helpers`) needs a search rule — current
directory? a root? a list? — and this repository's rule is that the compiler does
not guess.  A string makes the file identity explicit, makes the relative-to-the-
importing-file rule the only rule, and gives the loader exactly the string the
loader needs.  `import` becomes a **keyword** (D2); the `from`/`as` forms are out of
scope (§6) and stay ordinary identifiers until their own step.

**D2 — `import` becomes a keyword, and that is a breaking change this plan takes
knowingly.**  `SPEC.md` §1.3 already promises it ("a program that uses them as
names will need renaming the day one arrives") and 2.1 measures that such programs
are legal *today*.  The alternative — recognising a statement-position `import` —
is the ambiguity `enum`/`match` already paid for (`selfhost/vela.vel` lines 228-236:
`match(x)` is a call and `match = 1` is an assignment).  The step that makes it a
keyword owes the tree a measurement: which `.vel` files change verdict, if any.

**D3 — the semantics are flat and whole-file, which is the linker's semantics at
the AST level.**  `import "b.vel"` makes **every** top-level declaration of `b.vel`
visible to the entire program, including `b.vel`'s own imports, transitively.  There
is no qualified access and no per-module namespace.  This is deliberately the
smallest semantics that can retire the linker: today's concatenation produces
exactly this visibility, so step 5 is a change of *mechanism* and not of meaning,
and the fixpoint is evidence about it.  Qualified names, privacy and selective
imports are §6.

**D4 — declaration order is the loader's, not the importer's.**  Imported files'
declarations come **first**, outermost import first, then the root file's own — the
order the linker produces today, and the order `tools/link_selfhost.vel` says it
needs ("a function has to be declared before it is called").  So an `import` line's
position inside the importing file carries no meaning beyond the import itself; a
reader who expects Python's ordering semantics gets the same *visibility* and a
different *order*, and the plan says so rather than leaving it to be discovered.

**D5 — `main` belongs to the root file only.**  A program is one program, so a
second `main` in an imported file is refused by name (with both paths) rather than
silently shadowing or silently winning.  The loader knows which file is the root
because it was given on the command line.

**D6 — each path is loaded once, and a cycle is refused by name.**  The loader keys
on the path after resolving it against the importing file's directory; a second
import of the same path is a no-op (the alternative — refusing duplicates — breaks
diamond imports without buying anything), and `a → b → a` is refused with the cycle
printed as a chain, because a flat merge has no other honest answer.

**D7 — file identity is a line *base*, not a new node field.**  This is the hard
part and the answer is forced by two measured facts.  Nodes are stride 10
(`selfhost/parts/vm_state.vel` lines 543-561: kind, a, b, c, `d`, line, flags, nx,
e, f) and word 4 (`d`) is not free — it is a `for` node's step; `e`/`f` are the
resolver's.  `struct VM` may not gain an array field ("Vela 0.1 struct fields may
not be arrays", `vm_state.vel` lines 420-422) and fields must be *appended* because
`vm_new()` is a positional constructor (lines 436-443).  So: **the loader lexes file
k with a line base**, `BASE_0 = 0`, `BASE_k = BASE_{k-1} + lines(file k-1) + 1`, and
keeps a small table of `(base, path)` pairs — the table in a reserved region of
`mem` (the pattern the debugger already uses for its breakpoint list, `vm_state.vel`
lines 420-422), the count and cursor appended to `struct VM`.  Every site that
prints or emits a location asks `path_for_line(vm, mem, line)`.  With one file the
table has exactly one entry whose base is 0 and whose path is the argument, so
**every existing golden and every emitted C byte is unchanged** — which is the only
property that makes this step safe to take in this tree.

**D8 — the back ends move one at a time, and the default path refuses rather than
lies.**  `build` is the LLVM path now (`ROADMAP.md`'s "pure-bred" row), so a
multi-file program must reach the LLVM back end before step 5 can happen; until it
does, `build`, `build-llvm` and `emit-llvm` refuse a multi-file program **by name**,
with a row in `tests/llvm-refusals.txt` — the same two-sided ledger the seven
`parallel for` refusals use.  A loop that says it is parallel and runs serially is
the one thing this project must never ship; a program that says it is two files and
compiles as one is the same class of lie.

## 4. The ladder

Each step names what changes, the command that decides it, the acceptance, and the
documents that become false the moment it lands.

### Step 1 — the syntax, and a refusal that names itself

*Changes.*  `import` joins `keyword_id` as 26 (`selfhost/vela.vel` lines 158-244);
the parser accepts `import "path"` at top level only and builds a node for it
(after `parse_module`, `selfhost/parts/parser.vel` lines 1611-1646, which links
top-level statements through `nx`); every mode that would have to *act* on it
refuses by name.
*Commands.*  `tools\build.ps1` (the change has to survive the fixpoint), then
`tools\refreeze.ps1` (the 196-case corpus), then `vm.exe check` over every `.vel` in
the tree to answer D2's debt: **which files changed verdict, if any**.
*Acceptance.*  A program with `import "b.vel"` is refused with a message that names
the construct and the path, not "expected a newline"; `mut import: int = 1` is now
refused as a keyword collision (and that refusal is a corpus case, since it is the
observable cost of D2); the corpus is green; the verdict-change list is in the
step's evidence file.
*Documents.*  `SPEC.md` §1.3's paragraph, and its Chinese twin; the "reserved but not
implemented" comments in `selfhost/vela.vel` / `parser.vel`.

### Step 2 — load, merge, interpret

*Changes.*  The loader (driver-side, `selfhost/parts/vm_main.vel`, because that is
the only part that does I/O today): resolve the path against the importing file's
directory, read, lex with a line base, parse into the same pools, splice the chain
in front, recurse.  The sources table and `path_for_line` (D7).  `check`, `run` and
`parse` accept a multi-file program.
*Commands.*  `vm.exe run <two-file program>`; `vm.exe check` on the same; the
corpus.
*Acceptance.*  A two-file program prints the same answer as its one-file twin; a
missing file, a cycle, a duplicate top-level name and a second `main` are each
refused with a message naming the file that caused it; a diagnostic raised inside
the imported file says `at tests/build/import_helper.vel:3` and not the root path;
the corpus is green and the C/LLVM modes still refuse a multi-file program by name.
*Documents.*  `DESIGN.md` §2 line 124, `README.md`'s "no module system" paragraph.

### Step 3 — the C back end

*Changes.*  `emit_location` and the runtime-call sites carry the *node's own* path
(D7's table); `build-c` and `emit-c` accept a multi-file program.
*Commands.*  `vm.exe build-c <two-file program>`, run the executable; compare its
bytes against `vm.exe run`'s and against the one-file twin; grep the emitted C for
the imported file's name at the right line numbers.
*Acceptance.*  Three-way agreement (interpreter, C, one-file twin); a runtime trap
inside the imported file prints the imported file's path; the fixpoint and the
corpus still hold.

### Step 4 — the LLVM back end

*Changes.*  Same as step 3 for `emit_llvm.vel`, whose module-global path is a single
`pfile` field (`selfhost/parts/emit_llvm.vel` line 13846 region) and therefore needs
the same treatment.  Until this step lands, `build` refuses a multi-file program by
name (D8).
*Commands.*  `vm.exe build <two-file program>`; `tools\llvm-no-cl.ps1`; the
`tests\llvm-refusals.txt` ledger.
*Acceptance.*  The default path builds the two-file program with no C compiler
started, and its output matches the interpreter's byte for byte.

### Step 5 — the self-hosting switch, which is where the linker retires

*Changes.*  `selfhost/vm.vel` stops being generated: it becomes a small file that
imports the lexer half and the ten parts, in the order `tools/link_selfhost.vel`
lists today (state, parser, resolve, check, eval, emit, llvm_shim, emit_llvm, dump,
vm_main).  The lexer's driver moves out of `selfhost/vela.vel` into its own file, so
that the imported lexer has no `main` (D5) — that is the half-split rule
disappearing together with the reason for it.  `tools/build.ps1` step 3 changes
from "build and run the linker" to "build the compiler", and `tools/link_selfhost.vel`
retires.
*Commands.*  `tools\build.ps1` (all eight steps, including the fixpoint), then
`tools\refreeze.ps1`, then `tools\llvm-no-cl.ps1`, then `tools\check-coherence.ps1`.
*Acceptance.*  The fixpoint still holds: two generations of the compiler emit
byte-identical C for `selfhost/vm.vel`.  `selfhost/vela.vel`'s standalone lexer still
builds and lexes.  The 8192-intern measurement (2.5) is taken on the real thing.
`RESULT: ok`.

### Step 6 — the editor

*Changes.*  The plugin's parser learns `import` (it reproduces the compiler's parse
tree node for node, `FEATURE_PARITY.md` row 5) and its name resolution reaches
across files (row 10's `VelaTargets.declarationFor`).
*Commands.*  `idea-plugin\ast-diff.ps1`, `psi-tree-diff.ps1`, `harness.ps1`, and the
plugin's own gates; the acceptance is written when the compiler's step 2 shape is
frozen, because the two trees have to agree exactly.

## 5. What would make this plan wrong

| assumption | how it would show | what decides it |
|---|---|---|
| a merged program fits the pools | a pool exhaustion panic naming the pool | step 5 (the compiler importing itself is the largest input this tree has) |
| one line-base table is enough for identity | a trap or diagnostic naming the wrong file | steps 2-4, including the emitted C grep in step 3 |
| the interpreter can load N files | `string table full` while loading | step 5's measurement (2.5) |
| a flat merge really is the linker's semantics | a program that compiles today and not after step 5, or the reverse | the fixpoint at step 5, plus `tools\refreeze.ps1` |
| no program in the tree uses `import`/`from`/`as` as a name | a file whose verdict changes at step 1 | step 1's verdict sweep (D2) |
| the plugin's parser can follow | `ast-diff.ps1` reporting a difference it cannot explain | step 6 |

## 6. Deliberately not in this plan

Packages and any notion of a registry; search paths; qualified access
(`mod.name`); privacy (`pub`/`export`); selective import (`from "a.vel" import x`);
aliasing (`as`); re-export; conditional compilation; incremental or parallel
compilation; cyclic imports (refused, D6).  Each is a language decision with its own
evidence burden, and none of them is needed for the acceptance `ROADMAP.md` already
writes down.  `from` and `as` keep their status as documentation-reserved
identifiers until the step that claims them.

## 7. The ledger

| step | state | the evidence |
|---|---|---|
| 0. the design, against measurements | **done** 2026-09-25 | §2 above: every number run on `selfhost/build/vm.exe` 870 400 B |
| 1. syntax + a named refusal | not started | — |
| 2. load, merge, interpret | not started | — |
| 3. the C back end | not started | — |
| 4. the LLVM back end | not started | — |
| 5. the self-hosting switch | not started | — |
| 6. the editor | not started | — |
