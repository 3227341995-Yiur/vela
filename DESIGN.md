# Vela — design notes

Why the language is shaped the way it is, and how the parts fit. This is the
document to read before changing anything: most of the unusual decisions below
are load-bearing, and several of them are the direct result of bugs found while
building it.

---

## 1. The three claims, and how each one is made true

### 1.1 "Faster than C++"

The claim is not "Vela has a better optimiser" — it does not. The claim is that
**a language can hand a C compiler facts that a C++ compiler has to guess**, and
those facts are worth more than anything a hand-written backend would add.

| fact Vela knows | what it buys |
|---|---|
| the exact length in the type | `len()` is a constant; no length bookkeeping |
| arithmetic is checked, always | the checks are calls (`vela_add_range`) the C compiler can see through, and no flag in this compiler removes them |
| an iteration space is race-free | a real `#pragma omp parallel for` — emitted only on proof, with `/openmp` passed to the C compiler only when the C carries a pragma |

Two rows this table used to have are intentions rather than code, and they are
named here rather than assumed: **arrays never alias, but the back end does not
emit `restrict` yet**, and **the interval analysis does not delete a bounds or
overflow check yet** — what it buys today is the `parallel for` proof and the
refusal of a constant index that cannot be in range (§6.1, §7.5). The emitted C
is deliberately conservative in the meantime.

Underneath, the back end is C11 (`/O2` on MSVC, `-O3` on gcc/clang), so the
register allocation, vectorisation and instruction selection are whatever a
decades-old optimiser does. That is deliberate: competing with LLVM's backend is
not a thing a language needs to do to be fast, and pretending otherwise is how
hobby compilers end up slow.

The measured outcome is in `bench/RESULTS.md`, and it reports every variant rather
than a score: the serial matmul is faster than either C++ serial build, the
OpenMP matmul is about 17% behind the C++ build that also uses OpenMP because
Vela's inner loop keeps its overflow checks, and every row carries the answer it
must print so a fast wrong answer cannot pass. (That file's account of *why* the
serial row wins still credits `restrict` and proved-away bounds checks; the
compiler does neither yet, and the numbers are the numbers either way.)

### 1.2 "Safer than Rust"

Rust's safety is an ownership discipline plus `unsafe`. Vela's is arithmetic:
every rule is a proof obligation discharged by a checker small enough to audit —
`selfhost/parts/check.vel`, one file — and there is no construct that can be used
to opt out.

* one arena, no `free` → double-free and use-after-free are not expressible;
* no function may return its own array → no pointer outlives its frame;
* `str` is a literal or permanent host memory → a returned string cannot dangle;
* a check is removed only by proof, never by a flag or an attribute — and there is
  no flag: this compiler has no `--fast-int`, no `-O`, and nothing else that turns
  the checks off, so "fast but undefined" is not on offer either;
* integer division by a constant zero and a constant out-of-range index are
  compile errors.

The safety rules the user actually feels — immutability by default, no implicit
conversion, no truthiness, no silently-useless expression statements, no
`elif`, no tuples — are strictness, not safety, and they are in `SPEC.md` §4.

### 1.3 "Syntax like Python, stricter"

The syntax keeps Python's surface (readable words, `for i in range(...)`,
indentation *in practice* because people indent anyway) and removes Python's
ambiguity:

```
indentation   -> braces: structure is visible, refactoring cannot silently re-nest
implied types -> every parameter and return annotated, always
truthiness    -> conditions must be bool
rebinding     -> immutable by default, mut to opt in
"a" "b"       -> refused: no implicit concatenation
0 < x < 10    -> refused: chained comparison
x + 1         -> refused when it is a statement: it does nothing
```

**Why braces, and not indentation, in the end.** Two reasons, one of them
decisive for this project:

1. Indentation is *invisible structure*. Any tool that rewrites a file (a
   formatter, a migration script, a patch) can change meaning by accident. In
   this very repository, a formatter bug turned `total += i` into `total + i`;
   with braces, the same class of bug is caught by the compiler because the
   statement is visibly a no-op — and the repair script that fixed that damage
   is gone with the migration tools it belonged to (§6), while the refusal that
   makes the class of bug visible stayed: it is a case in the corpus.
2. **A self-hosted lexer does not need an indentation stack.** Vela intends to
   compile itself, and every piece of machinery it does not need is machinery it
   does not have to reimplement in Vela. The first version of `selfhost/vela.vel`
   carried an indentation stack, blank-line special cases and INDENT/DEDENT
   tokens; the current one has none of that.

---

## 2. Pipeline

```
  source.vel
      |  selfhost/vela.vel        tokens (no INDENT/DEDENT at all)
      |  parts/parser.vel         AST: one node pool, stride 10, chains linked
      |  parts/resolve.vel        scopes, frame slots, which function a call reaches
      |  parts/check.vel          types + interval analysis + safety proofs
      |  parts/emit.vel           C11 (arena, checked arithmetic, omp only on proof)
      |  MSVC / gcc / clang       machine code
      v
   executable  (file.c is kept beside the source: it is the evidence for the proofs)
```

`selfhost/build/vm.exe` is that whole pipeline in one binary — a mode per box
(`lex`, `parse`, `check`, `emit-c`, `build`), plus `run` for the interpreter and
`count`/`nodes`/`debug` for the tooling — and it is the compiler in the tree.
`tools/link_selfhost.vel` splices the parts into `selfhost/vm.vel`, because Vela
0.1 has no module system (§7.1).

### 2.1 Why Python for stage 0

Bootstrapping a language always begins in another language: Rust began in OCaml,
Go in C, Swift and Zig in C++, Nim in Pascal, C++ in C. The host language of the
compiler has **no effect on the speed of the code it produces** — that was the
question the design had to answer honestly, and the benchmarks answer it.

Python was chosen because the front end is where the interesting work is (types,
proofs, diagnostics), it can be written and changed quickly, and the resulting
binary is produced by the same C compiler C++ users get.

Stage 4 deleted it, and this is the part of the design that has to be said out
loud: there is **no Python in this repository** — `vela/` (lexer, parser, checker,
emitter, CLI), `vela/ide/`, the Python build tools and the differential suites
that gated the port are gone, and `Get-ChildItem -Recurse -Include *.py` returns
nothing. Everything Python answered is now answered by `vm.exe`; what an *editor*
needs is answered by running the compiler rather than by a second implementation
of the language (§5).

---

## 3. The checker

### 3.1 Interval analysis (`ck_iv_*` in `selfhost/parts/check.vel`)

An `Iv` is one interval: a `lo`/`hi` pair with a flag for each end, because "at
least 0, unbounded above" has to be expressible — which is what a range with a
runtime bound gives. Every integer expression gets one, and a limit that would
overflow is clamped to "unbounded" rather than wrapped, so a proof can never be
built on a bound that is a lie.

What the intervals are *for* today is permission and refusal, not deletion: they
are what admits a `parallel for` (§7.5) and what refuses a constant index that
cannot be in range (§6.1 of `SPEC.md`). Deleting a check by proof is designed and
not built — the honest state of it is in `SPEC.md` §6.1.

The single most important rule is **invalidation at loop entry**: before any
loop body is analysed, every variable assigned inside the body becomes unknown,
and then the loop condition narrows what it can. This is what keeps the
elimination sound across back-edges; without it, `i` could be assumed to be its
entry value and the compiler would happily delete a check that is needed on the
second iteration.

Leaving a block (`pop`) propagates a dirty set outward, so facts discovered
inside a branch never leak out of it.

### 3.2 Bounds checks

The length is part of the array's type, so `ck_index` compares a *constant* index
with it and refuses the program when the index cannot be in range — that is what
`const_index_out_of_range` and `negative_const_index` in the corpus hold. Every
other index is checked where it is used: `vela_bounds_check(i, N, file, line)`
panics with the index, the length and the line it came from. Stage 0 deleted those
checks by proof, and `SPEC.md` §6.1 kept the table of proofs; this back end does
not delete one yet, which is why the emitted C is conservative on purpose.

### 3.3 Parallel proof (`ck_parallel` in `check.vel`)

See `SPEC.md` §7. The rules exist because the failure mode is silent: the C++
twin of the matmul benchmark produced garbage (`checksum 576295424`) when the
inner indices were shared between threads, and nothing in C++ said a word. This
proof is also what the back end's `#pragma omp parallel for` rests on — the pragma
is emitted only for a loop it admitted, and the driver asks the emitted C whether
it carries one before passing `/openmp` to the host compiler (§7.5).

**Where the rule comes from, and what it deliberately does not do.** The
aliasing half of the proof — an array the body writes may be read in that body
only at the write's own index expression — is the language-level form of the
borrow rule Rust's `par_iter_mut` encodes in its *type*: every iteration is handed
its own `&mut`, so no iteration can reach another's element. Vela has no lifetimes
to spell that with, so the checker proves the same thing about the loop it is
looking at, and the cost is a refusal rather than a crippled language: this is
one index expression per written array, not one index expression per array. A
read-only array stays free to index however the program likes —
`out[i] = a[i] * 2` is the shape the rule exists to keep, and it compiles to a
real `#pragma omp parallel for` with `a` read by every thread. What is refused is
the loop-carried dependence: `a[i] = a[i - 1] + 1` reads what a neighbour is
writing, and over it OpenMP computes a different answer on every run (measured:
nine distinct wrong values in twenty runs of a 64-element prefix sum, against 63
from the interpreter).  Refusing it is the port's own decision; stage 0 emitted
the same loop with no pragma and therefore no race, which is why no golden
covered this shape.

Two limits of the proof, named rather than hidden: the comparison is *structural*
(`ck_same_expr`), so `a[i]` and `a[i * 1]` are treated as different expressions
(refused — the safe direction), and a name that a nested block *shadows* compares
equal to the loop variable it shadows, in the read rule and in the write rule
alike, because both compare interned handles rather than resolved binding slots.

---

## 4. Runtime (`runtime/vela_runtime.h`)

A single header, no build system, ~450 lines:

* **arena**: 256 MB by default (`VELA_ARENA_MB`), 16-byte aligned, always zeroed
  — which is why a bare array declaration is safe;
* **frames**: a function that declares an array takes a `vela_arena_mark` and
  releases it on every return path; no function may return an array, so nothing
  can point into a released frame;
* **permanent region**: strings from the host (`read_text`, `intern`) live here
  and are never released, which is what makes `str` safely returnable and
  `substr` safe without ownership tracking;
* **checked arithmetic**: `vela_add_range` and friends, always. This compiler
  emits no wrapping mode at all — the runtime still carries `vela_wrap_*`, and
  the deleted `--fast-int` mode is the only thing that ever called them;
* **floor division**: `vela_floor_div_u` / `vela_floor_mod_u`, because Python's
  `//` and `%` on negatives are not C's and users notice;
* **host I/O**: `read_text`, `write_text`, `bytes_at`, `substr`, `argc`, `arg`,
  `intern`/`interned`, `unescape`, `panic`, `emit_*`, `warn_*`.

No operation in the runtime has undefined behaviour, including the wrapping ones
the deleted `--fast-int` mode used.

---

## 5. The IDE: the browser editor was deleted, the IDEA plugin replaced it

Stage 0 shipped a web IDE — `python -m vela ide`, a loopback-only HTTP server over
`vela/ide/web/` answering JSON queries (`/api/check`, `/api/complete`,
`/api/hover`, `/api/definition`, `/api/references`, `/api/signature`,
`/api/outline`, `/api/format`, `/api/tree`, `/api/file`, `/api/search`, the file
operations, `/api/run` and a job API) by running the real compiler, with a
dependency-free page that had no build step. It ran on the Python language
service, so stage 4 deleted it with everything else Python: `vela/ide/`, the page,
and the Python suites that drove it are gone. There is no HTTP server in this tree
and no `ide` mode in the compiler.

What exists now is `idea-plugin/`: an IntelliJ IDEA plugin for `.vel`/`.vela`
files — file type, highlighting, and diagnostics from
`selfhost\build\vm.exe check <file>`, drawn exactly where the compiler says. The
"Vela" submenu and its Check/Build-and-Run actions were deleted: Run comes from
IDEA's own Run menu through a `runConfigurationProducer` and interprets the file,
and Check duplicated the live diagnostics. One decision survived the deletion and
is why the plugin is thin:

* **The editor never invents language facts.** The plugin reads the compiler's
  two-line refusal (a message and a `file:line`) and nothing else — no second
  checker to drift, no pattern-matching on English — so every rule Vela enforces,
  including the `parallel for` proofs, is a squiggle for free.

The rest of what the deleted IDE did (completion, hover, references, outline,
formatting) is not replaced by a re-implementation; it would come from the
compiler exposing more of its own answers, which is not built (§7.5).

---

## 6. Tools

| tool | why it exists |
|---|---|
| `tools/build.ps1` | the whole build, in six steps: bootstrap `vm.exe` from the checked-in C, link the parts, compile the compiler with the compiler, build the standalone lexer, check the byte-identical fixpoint, and (with `-Suites`) run the suite. Its transcript lands in `..\vela-build-report.txt` |
| `tools/link_selfhost.vel` | splices the self-hosted parts into `selfhost/vm.vel` (a stand-in for modules). It is built by `vm.exe build` and then run, so the linker needs no Python either |

The Python tools that did this work — the formatter (`velafmt.py`), the
indentation-to-braces migrator, the `+=` repair script, `link_selfhost.py`,
`bootstrap.py` / `rebuild.py` / `build_selfhost_by_vela.py` — were deleted with
stage 0, and so was `tools/legacy/`, the frozen indentation front end the
migration round-tripped through. What the migration left behind is the language
in `SPEC.md`, not a tool: nothing in this tree parses Vela except the compiler.

---

## 7. Bootstrap plan

| stage | content | status |
|---|---|---|
| 0 | Python front end + C11 back end | **deleted in stage 4**: the port was certified against it (70/70 verdict agreement on every program *and* every reject case in the tree, 42 of them with the message byte for byte) and then it went — `vela/`, `vela/ide/`, the Python tools and the differential suites. Nothing in this tree is Python |
| 1 | `selfhost/vela.vel` — **lexer in Vela**, verified token-for-token against stage 0 | done, and it stayed: the lexer is part of the compiler in the tree |
| 2 | **parser in Vela** (`selfhost/parts/parser.vel` + `dump.vel` + `vm_main.vel`, linked into `selfhost/vm.vel`), verified tree-for-tree against stage 0 | done |
| 2.5 | interpreter in Vela, so `vm.exe run file.vel` needs neither Python nor a C compiler | **done**: `parts/{vm_state,resolve,eval}.vel`; the corpus's `run` cases hold it to the compiled twin's output, program by program |
| 3 | C11 emitter in Vela (`parts/emit.vel`) and the driver that uses it: `vm.exe build file.vel` | **done**: the corpus's `dumps` cases compare the C it writes and the `native` cases run what it produces; the compiler compiles itself (`vm.exe build selfhost/vm.vel`); `tools/build.ps1` step 5 checks the fixpoint byte for byte; and `vm.exe build` drives the host's C compiler itself, which is how that script builds everything it builds |
| 4 | the checker in Vela (`parts/check.vel`), then stage 0 out of the toolchain | **done**: the corpus's `refuse` cases and their goldens (`tests/golden/*.err`) are the record of it. `check` runs before every `emit-c`, `run` and `build` (`vm_main.vel`), so the front end refuses rather than emitting C for a program it would refuse, and `vm.exe` is the only driver there is |
| 5 | foreign functions (C only, C++ deliberately out of scope — §9.3) | **done, in the small shape §9 describes**: `extern c [pure] def` for scalar C functions, refused at the declaration *and* at the call site, with no foreign call in the interpreter.  Left out deliberately: blocks, `cstr`, `(T*, N)` arrays, `extern struct`, variadics, callbacks (§9.5) |
What already works, and can be run:

```powershell
powershell -ExecutionPolicy Bypass -File tools\build.ps1          # bootstrap, link, self-compile, fixpoint
selfhost\build\vm.exe parse  bench\matmul.vel                     # the tree, from Vela, in Vela
selfhost\build\vm.exe lex    selfhost\vm.vel
selfhost\build\vm.exe emit-c bench\matmul.vel                     # the C, with its checks and pragma
powershell -ExecutionPolicy Bypass -File tools\build.ps1 -Suites  # ... and the suite
```

### 7.1 The shape stage 2 has to take

Vela has no pointers, no generics, no heap objects, and — importantly — **no
arrays inside structs**. A textbook compiler written in C++ would use
`vector<Node>` and be done; in Vela the equivalent is:

* **stride packing**: one `Array[int, 327680]` where node `i` occupies
  `i * 10 + 0 .. 9` (kind, four arguments, line, flags, *next sibling*, two
  more), plus `Array[float, 16384]` for float literals and
  `Array[int, 65536]` for type records;
* **linked chains instead of packed children**: a node's children are a chain
  threaded through its `nx` word. A run of children cannot be packed
  contiguously, because a statement list is parsed *around* its nested blocks —
  the nested statements are appended to the same pool in between. Links do not
  care, and every walk is a `while` loop;
* **a `Ctx` struct of ints only** carrying every cursor (position, line, token
  index, node index, type-pool cursor, float-pool cursor, error flag). The
  parser reuses the lexer's dead fields rather than growing the struct: `aux`
  becomes the loop depth, so `break` outside a loop is still refused;
* **explicit threading**: every recursive function takes the arrays it needs as
  `mut` parameters, because there are no globals and no closures;
* **a string table** (`intern`/`interned`) so names are int handles and name
  equality is integer equality — a `str` cannot live in an `Array[int, N]`.

That is not a workaround for a missing feature — it is the only shape the
language permits, and it is the reason Vela needs no `unsafe` block to implement
itself. `selfhost/vela.vel` (lexer) and `selfhost/parts/parser.vel` (parser) are
the working proof of the design.

The interpreter needed three things the language did not have in its
interpreted subset, and two of them are now in it:

* a **value pool for array elements** — done: an array value is a descriptor
  (pool tag, base, length) and the pools are bump-allocated per frame, so the
  interpreter's memory obeys the same arena rule as the compiled program's;
* a **field-layout table for structs** — done: the resolver records each struct's
  fields in declaration order and each method against its owning struct, which is
  exactly the information the parser does not record (`AST.md` §9);
* an **integer width conversion** — *not* done, and it is a language gap rather
  than an interpreter one: `b[0]` where `b: Array[u8, …]` is accepted by the
  checker and widened by the compiled program (C widens implicitly), while this
  interpreter has no width to widen into — Vela offers no `to_int(u8)`, and a
  `u8` element has no value shape here. So any program with an `Array[u8]` is
  refused, declaration included, with that reason; the fix is a rule in SPEC §3.1
  (a width conversion) rather than a change here, and §3.1 says what the two back
  ends actually do about widths today.

### 7.2 What the differential test caught

The Python suite that gated the port (since deleted with stage 0) compiled each
program with stage 0, ran it, and ran the interpreter on the same source,
requiring the standard output to be identical. Writing the interpreter with that
test in place found four bugs that no amount of reading the code would have:

1. **the `if` else-word is word 4, not word 8.** Word 8 is the else *count*
   (`parser.vel:589`–`:590`). The interpreter read the count as a node index, so
   a program with an `if` and no `else` executed node 0 — the module node.
2. **a chain terminator is not always `-1`.** An unused word is initialised to
   `0`, which is not a node index because node 0 is the module node — assuming
   `-1` everywhere is wrong, and `<= 0` is the only safe test.
3. **an argument list is not contiguous unless it is made contiguous.**
   Evaluating `f(g(1), 2)` grows the stack while evaluating `g(1)`, so `2` did
   not land beside it, while the callee copied parameters positionally.
4. **`break` has to reach the loop statement.** A helper that mapped `C_BREAK`
   to `C_NEXT` on the way out was enough to turn every `break` into a `continue`.

None of these are exotic; all four are the kind of mistake that makes a
self-hosted component quietly disagree with the original, which is why that
differential test existed until stage 4 deleted it — the same disagreement is what
the corpus's `run` cases watch for today (each one is compiled *and* interpreted,
and both must print the golden output).

### 7.3 What compiling the compiler caught

The stage-3 emitter translates every program in the corpus to C that means what
stage 0's C meant, and then translates *the compiler*: `vm.exe build
selfhost/vm.vel` writes `selfhost/vm.c` and the host's C compiler turns it into
`selfhost/vm.exe`, which the build promotes over `selfhost/build/vm.exe`. The
property that has to hold is the fixpoint — the compiler it built must re-emit
`selfhost/vm.vel` as the same C, byte for byte — and `tools/build.ps1` step 5
checks exactly that (it writes `selfhost/build/_fixpoint_gen2.c` and compares its
hash with `selfhost/build/vm.c` and `selfhost/vm.c`; they are the same today,
`9CD51090CBCFBF3B`, 759 600 bytes). The corpus has a `fixpoint` case that says the
same thing. The Python differential suites that first caught this are gone with
stage 0; the check they performed is the build's own.

Corpus programs would not have found the bug this exposed, because none of them
put an escape in a string:

```vela
print("nl:\nsecond")        # one newline byte, length 1
```

The literal's **value** is the decoded text; the emitter was writing its **raw
source text** (backslash and `n` both intact) while giving `vela_str_lit` the
length of the decoded value. C then read the escaped text as two bytes and the
count cut it to one, so every literal carrying an escape lost its last byte —
the first self-hosted build printed `\` wherever a newline belonged, in its own
output. The fix is one idea: emit the value, and the count of the value's bytes.
The corpus's `string_escapes` case now fails if those two ever separate again, and
the self-hosting build is what makes that class of mistake visible at all — a
compiler that only ever compiles toy programs is never asked the question.

### 7.4 Known limits of the interpreter

Stated here rather than discovered later:

* `Array[u8]` is refused (the width-conversion gap above). `bench/sieve.vel`
  therefore does not run under the interpreter yet.
* `Array[i32]` stores through `int32_t` truncation, but no corpus program
  exercises it.
* An augmented assignment through an index (`a[i] += 1`) evaluates the *place*
  twice. Vela has no assignment expressions, so this is only observable if the
  index expression calls something impure — but it is a deviation, not a proof.
* Limits, all reported as clean refusals rather than crashes: 500 call frames,
  1 000 000 000 interpreted steps, 262 080 stack cells, 524 288 heap cells,
  2 097 152 int elements, 1 048 576 float elements, 33 554 432 bytes, and the
  runtime's 8192-entry intern table (which is also where interpreted strings
  live, because interning is content-addressed and a handle *is* the string).
* The self-hosted front end checks a program before it runs or emits for it
  (stage 4): `run`, `emit-c` and `build` all call `ck_module` first, so a program
  the checker refuses is a refusal and not a run-time surprise. What the checker
  does not yet prove is listed in §7.5.
* **A foreign call is not something the interpreter can do.** It evaluates the
  arguments and then produces nothing for the result: *using* it traps (`this
  value cannot be printed`), and a call whose result is thrown away — a `void` C
  function called for its side effect, say — is silently a no-op, side effects
  included. The first half is the boundary `tests/golden/extern_c_probe_interp.err`
  records; the second half is a gap in `eval.vel`, named here rather than hidden
  (§9.2).
* `parallel for` is executed in order. The correctness of a `parallel for` rests
  on a proof of race-freedom, and a sequential execution of a race-free loop is
  the same computation; there is no parallelism in the interpreter itself.

### 7.5 What stage 4 left, exactly

Written down because "the port is done" and "the port is done except for these"
are different claims, and only the second one is true:

* **`extern` was left unimplemented by stage 4, and has since been built in the
  small shape §9 describes** (stage 5): `extern c [pure] def` declares a C
  function whose parameters and result are scalars. A `str`, an array and a
  struct are refused at the declaration *and* at the call site, the argument
  kinds are checked against the declaration, and the interpreter still cannot
  make a foreign call at all (§7.4, §9.2). What is deliberately still missing —
  blocks, `cstr`, `(T*, N)` arrays, `extern struct`, variadics, callbacks — is
  §9.5's list.
* **The editor's services come from the compiler, and that is all that is left of
  the IDE** (`idea-plugin/`, §5). The browser IDE that ran on stage 0's Python
  language service was deleted with stage 0; the plugin runs `vm.exe check` and
  draws what it says. Completion, hover, references, outline and formatting were
  the deleted service's work and are not replaced by a re-implementation — they
  would come from the compiler exposing more of its own answers, and `vm.exe nodes`
  (the raw node pool) is the beginning of that, not yet enough.
* **The checker's remaining narrowings**, all in the safe direction (they can
  refuse a program stage 0 accepts, never accept one it refuses), all named in
  `selfhost/parts/check.vel`'s comments: the interval environment for a
  `parallel for` holds the loops *inside* the body and not the ones it is nested
  in; an impure *method* is only recognised when the receiver is a plain name;
  the *result* of a call is unknown to `ck_etype`, so a type rule with a call
  operand stays quiet (the foreign-call rule is the one exception, and it reads
  the return kind its declaration filed rather than widening `ck_etype` — §9.2);
  and the operator table is checked for binary operators, not yet for unary ones
  or comparisons (`-x` on a string, `"a" < 1`).
* **The self-hosted emitter emits checked arithmetic, always — and OpenMP is now
  its own work.** There is no `--fast-int` in this compiler and nothing that turns
  the checks off; by contrast a `parallel for` the checker admits becomes a real
  `#pragma omp parallel for` (the induction variable declared outside the loop and
  a plain-assignment init, because MSVC's `/openmp` is OpenMP 2.0), and the driver
  passes `/openmp` — `-fopenmp` elsewhere — only when the C it just wrote contains
  a pragma.
* **Proof does not delete a check yet.** The interval analysis is what admits a
  `parallel for` and what refuses a constant index that cannot be in range; the
  emitted C carries every bounds and overflow check (`SPEC.md` §6.1), and no array
  parameter is marked `restrict`. That is a back-end gap, not a rule change: no
  flag can remove a check, and adding the elision back is the work of whoever
  takes the back end next.
* **`mut` on a scalar parameter does not write through** — a gap between the
  implementation and `SPEC.md:148`, in both front ends, so not a divergence
  between them. `tests/probes/` holds the two-line proof of it and its struct
  twin, which does write through.
* **The front end's pools are fixed arrays** (`selfhost/vela.vel`): 131 072
  tokens, 65 536 syntax nodes. Real programs are nowhere near them; the
  compiler's own source is, which is the honest reason the token pool was
  doubled rather than sized. A growable pool needs an allocation the language
  does not have yet.

---

## 8. Testing

| what | how | what it proves |
|---|---|---|
| the corpus (`tests/run_tests.vel`) | a Vela program, built by `vm.exe build`; `tests/cases.txt` is one case per line, `tests/golden/` holds what each case must produce | programs compile, run and print exactly what they should (`run`, `native`, `panic`), **and** the programs that must be refused are refused with the right reason, byte for byte (`refuse`, `refuse-interp`) — including the cases where a conservative compiler would wrongly refuse a valid program (`ok`) |
| the same corpus, `dumps` cases | `lex`, `parse` and `emit-c` digests for one file | a change in one stage cannot hide behind an unchanged other stage |
| `fixpoint` cases | the compiler builds itself through its own driver | the compiler it built writes the C it was built from |
| `tools/build.ps1 -Suites` | step 6 of the build | the suite runs as part of the build, so a change that breaks a case breaks the build |
| `bench/run_bench.vel` | a Vela program, built by the compiler it measures | performance, with every variant's answer cross-checked before any time is reported |

The case that matters most is the *reject* half: a language that refuses the wrong
things is not strict, it is broken. The differential suites that used to hold the
self-hosted stages against stage 0 (token-for-token, tree-for-tree, program by
program, verdict by verdict) did that job while the port was happening and are
gone with stage 0 — the corpus and the build's own fixpoint are what is left, and
`tests/golden/` records what they certified.

---

## 9. Foreign functions: C libraries (what 0.1 does, and what it refuses)

The requirement is that a Vela program can use a C library *natively*, without
the program being rewritten around the boundary. C++ is deliberately excluded
(§9.3). This is the one feature that can end the safety claim, so what was built
is the smallest thing that deserves the name "C boundary", and every refusal in
§9.2 is a sentence with a probe under `tests/probes/extern_*.vel` behind it.

### 9.1 What `extern c` is

```vela
extern c def abs(x: i32) -> i32            # the C prototype `int abs(int)`
extern c pure def sqrt(x: float) -> float  # `double sqrt(double)`, no side effects
```

SPEC §6 rule 1 ("no pointers, no unsafe, no FFI") is what makes Vela's promise
checkable rather than aspirational. Raw FFI would end it, so the promise is
narrowed instead of abandoned:

* **still true:** no undefined behaviour is expressible *in Vela source*. No
  pointer type, no pointer arithmetic, no `free`, no uninitialised values, no
  casts, no callbacks — and nothing the boundary accepts puts a pointer in a
  declaration (§9.2), so a foreign signature is spellable without one.
* **a declaration is a prototype, not a block.** There is no library name to
  write and no header to include: the declaration *is* the C prototype, and the
  declared name *is* the C name — the back end calls it by its bare name, with
  none of the `vl_` prefix a Vela function gets. One line per function is the
  whole review surface, which is the opposite of `unsafe` blocks scattered
  through a codebase.
* **the types are the ABI's own handful of scalars.** `int` is C's `long long`
  (`int64_t`), `i32` is C's `int`, `u8` is `unsigned char` (`uint8_t`), `float`
  is C's `double`, `bool` is C's `bool`, and a `-> None` result is C's `void`.
  `i32` and `u8` exist so a C prototype can be written *exactly*: whether the
  declaration matches the library is the user's to get right, and the compiler
  spells what they wrote and nothing more.
* **`pure` is the user's word for the side effects the compiler cannot see.** A
  foreign function is exactly the one whose body the compiler does not have, so
  `extern c pure def sqrt(...)` is what lets a C function be called from a `pure
  def` or a `parallel for`; without it the call is refused like any other impure
  one (`a 'pure' function may not call 'f', which is not declared 'pure'`). The
  word is not verified and cannot be — and it is load-bearing: `pure` is what
  puts a foreign call inside a `parallel for`, where a function that does have
  side effects will race. A wrong `pure` is a wrong declaration, in the same
  family as a prototype that does not match the library (§9.5).
* **newly at risk, and said out loud:** whatever the library does with what it is
  given. A C function can corrupt memory, and one that calls `abort()`, `exit()`
  or `longjmp()` still does — an interpreted or compiled Vela program cannot
  catch it, and pretending otherwise would be a lie about control flow. Vela's
  job is to make the boundary small, explicit and typed, and to say exactly what
  it checked.

### 9.2 What the checker refuses, and why

Both halves of the boundary are refusals that name a line, and the line is the
one the reader has to change.

**At the declaration.** The rule is one sentence: **the types are scalars**.

* a **`str`** parameter or result — a Vela `str` is a length plus a pointer into
  Vela's own string region, not a `char *`. Handing one over would mean inventing
  an ABI (a NUL-terminated copy whose length C is then free to misread), and a
  `char *` coming back has no length, so Vela could not make a `str` of it:
  `'extern c' parameter 's' has type str`, `'extern c' function 'name' may not
  return str`.
* an **array** parameter or result — storage whose length Vela owns. Returning
  one would hand the caller memory that is released as the frame unwinds, and
  passing one would drop the length, which is half of what Vela knows:
  `'extern c' parameter 'a' has type Array[int, 4]`.
* a **struct** — C would need a layout the user never declared
  (`'extern c' parameter 'p' has type struct`). This is also why there are **no
  foreign methods**: a method's first parameter is its receiver, and the receiver
  of a method on `P` is a `P`, so `extern c` on a method is refused by the same
  parameter rule, pointed at the parameter nobody wrote
  (`'extern c' parameter 'self' has type struct`).
* a result the ABI cannot carry — `None` in the result position is C's `void` and
  is accepted (the emitted prototype is `void f(int32_t);`); everything else is
  the same three refusals in the other direction
  (`'extern c' function 'name' may not return str`).

**At the call site.** The other half of the same promise, and the one that
catches the mistake a foreign call actually makes — the one C will not catch for
you, because a C compiler converts an `int` to a `float`, a `float` to an `int`
and a pointer to an `int` without a word, so `abs(1.5)` would print 1 and
`abs(some_array)` would truncate an address. The rule is **an argument crosses
only as the kind the parameter declares**, with the one exception SPEC §3.1 gives
the whole language: an int *constant* whose value fits (`abs(-7)`, and `i32 + 1`).
Everything else is a type error at the line of the call:

* `'extern c' argument to 'abs' has type float, but parameter 'x' has type i32`
  — `abs(1.5)`, `abs(f)` for a `float` `f`, and `abs(half())` where `half()`
  returns a `float`: a call's result is known because every declaration's packed
  return kind is filed in the node table before any body is walked
  (`ck_record_decls`), so the verdict does not depend on where the `extern c`
  line sits in the file.
* `... has type int, but parameter 'x' has type i32` — a *value* of Vela's `int`
  (a loop variable, an int binding, the result of a Vela function returning
  `int`) is not narrowed to `i32` behind the reader's back; `... has type int, but
  parameter 'x' has type float` is `sqrt(4)`, because there is no implicit
  int-to-float in Vela and `to_float` exists for exactly that (the working probe
  spells `sqrt(to_float(i))`).
* `'extern c' argument 300 to 'double_byte' does not fit in u8` — the constant is
  visible and does not survive the conversion. A constant too large for an `i32`
  is caught by the rule one step earlier (`abs(2147483648)` is reported as `has
  type int, but parameter 'x' has type i32`), because `ck_foldable` caps what the
  pass will fold at 2^30 — both verdicts are right, and neither is silent.
* the same shape for an array, a struct, a `str` and a `bool`: the message spells
  both kinds, and an array is spelled with its length (`Array[int, 4]`).

**Arity** is the resolver's verdict, the same one an ordinary Vela function gets
— `vela: type error: wrong number of arguments`, at the line of the call. That
message is stage 0's byte for byte and is not the boundary's to rewrite.

**The interpreter.** Vela has no FFI and is not going to grow one, and this is
where that shows: an interpreted program has no way to call a C function. The
interpreter evaluates the arguments and then produces nothing for the result, so
*using* it traps (`this value cannot be printed`) and a call whose result is
thrown away is silently a no-op — side effects included. The first half is the
promise `tests/golden/extern_c_probe_interp.err` records; the second half is a
gap, not a promise, and it is named in §7.4.

### 9.3 C++ is out of scope, deliberately

C++ has no stable ABI: name mangling, `std::` layouts, inline and template
instantiation, virtual dispatch and exceptions are per-compiler. Reaching a C++
class directly means calling a mangled symbol with a `this` pointer and following
a vtable — which puts a raw pointer into the language, cannot reach inline or
template members, and hard-codes one compiler's ABI. The alternative (generating
an `extern "C"` wrapper per library) is real work for a boundary that already has
a stable answer: write the wrapper once, in C, and call it through the C path
above.

So the decision is: **C libraries directly; C++ libraries through whatever C ABI
they expose.** No mangled-symbol calls, no generated C++ glue, no second front
end for a language Vela does not model. A C++ library that wants to be usable
from Vela ships (or gets) a small `extern "C"` shim, and that shim is a C
library.

### 9.4 Why the host surface grew by three for stage 3

`vm.exe build file.vel` has to emit C and then get a C compiler to compile it, and
Vela had no way to start a process at all. That deserved care, because this is the
one feature that could quietly end the safety claim — so the surface grew by
exactly three calls, each answering a question the language otherwise cannot ask,
each documented in SPEC §8:

| call | why nothing smaller works |
|---|---|
| `concat(a, b)` | A driver has to name a file and build a command line. Vela refuses `a + b` on two strings (SPEC §1.5) and `substr` can only cut, so without a way to *make* a string the driver cannot be written in Vela at all. It is a named builtin and not an operator, so the cost stays visible at the call site. |
| `env(name)` | The driver has to know whether it is on Windows and which C compiler the user wants (`VELA_VCVARS`, `VELA_CC`). Unset and empty are the same answer — `""` — because Vela has no `None` to hand back. |
| `run_command(cmd)` | Hand the command line to the host's shell, get back one exit status (`0` = it worked). The command's own output goes straight to this process's stdout/stderr, uncaptured. |

What none of them does: return a pointer, return a handle into the language's own
memory, load a library, or keep state that outlives the call. Safe Rust has
`std::process::Command` and `std::env`; this is that class of surface cut down to
its smallest useful shape. It is also what makes a *single binary* the whole
toolchain: `vm.exe build` re-enters itself in `emit-c` mode (the shell redirects,
so the back end keeps one output target), finds MSVC through `VELA_VCVARS` or the
standard install directories, and falls back to `$VELA_CC`, `cc`, `gcc` or
`clang`. What holds it to account is the build itself: `tools/build.ps1` builds
every stage with this driver and checks the fixpoint, and `vm.exe build
selfhost/vm.vel` is the largest program it will ever be handed.

Three host quirks are recorded here because they cost real debugging time:

* **`cmd.exe /c` strips one leading and one trailing quote** unless the whole
  command line is exactly one quoted executable. Every command this driver builds
  quotes both its program and its arguments, so on Windows it wraps the entire
  command in one more pair. The symptom otherwise: *"The system cannot find the
  path specified"* for a path that plainly exists.
* **MSVC prints diagnostics in the console code page.** A tool that decodes them
  as UTF-8 gets replacement characters: the first Python suites tried, and one of
  them died of its own report (`UnicodeEncodeError` on a `cp936` console) until
  their streams were reconfigured to `errors="replace"`. The current suite
  captures cl.exe output to a file for exactly this reason rather than parsing it
  in flight.
* **Two suites sharing a build directory lose a race**, and report it as a
  back-end failure. The Python suites each built stage 0 into their own directory
  because of it; the cost today is that two `tools/build.ps1` runs at once fight
  over `selfhost/build/vm.exe` (the second one's C link fails with `LNK1104:
  cannot open ... vm.exe`). One build at a time.

Stage 5 needed no *stub table* on the interpreted path: a language with no unsafe
surface has no `dlsym` either, and rather than invent one the interpreter has no
foreign call at all (§9.2) — the boundary the `refuse-interp` case in the corpus
asserts.

### 9.5 What is deliberately left undone

The boundary in §9.1 is much smaller than the design this section first carried,
and the difference *is* the design decision, not an oversight:

```vela
extern c "libm" {                          # the shape that was designed, not built
    def c_sqrt(x: float) -> float
    def c_snprintf(buf: mut Array[u8, 256], n: int, fmt: cstr, ...) -> int
}
```

* **No library name, no header, no `extern c "..." { ... }` block.** The link
  step belongs to Vela's own driver (`vm.exe build` hands the emitted C to
  cl.exe), and a library name Vela never passes to a linker would be a promise
  about a toolchain Vela does not drive. One declaration per function says the
  same thing with less to get wrong.
* **No `cstr`, and no `Array[T, N]` crossing as `(T*, N)`.** These are the two
  rules that would make the boundary useful for the libraries people actually
  want (`printf`, `memcpy`) — and the two that put a pointer into Vela source.
  Each needs a lifetime and an ownership story Vela 0.1 does not have (borrowed
  for one call? copied? who frees it?); until then the honest spelling of a
  non-scalar boundary is a C shim with a scalar-only prototype, which is exactly
  what the two functions the probe calls (`abs`, `sqrt`) already are.
* **No `extern struct`, no variadics, no callbacks, no library-retained
  pointers.** Each is a real design — layout pinning, varargs, ownership and
  lifetime — and none of them is 0.1 work.
* **No unsigned wider than `u8`.** There is no `u32`, so `void srand(unsigned
  int)` cannot be declared *exactly*; a declaration can still lie, but the
  compiler will not help it do so, and that is the only place a scalar C
  signature is out of reach.
* **Nothing checks a declaration against the library.** A Vela declaration that
  disagrees with the C prototype is a mistake this compiler cannot see — and two
  declarations of one name are not refused either: like two Vela definitions of
  one function, the last one read wins, and if their prototypes conflict it is
  the C compiler that says so, in C's words rather than Vela's.
* **No width to write, so the boundary holds widths to the letter.** Vela has no
  `to_i32`/`to_u8`, and a *binding* is not a place where a width is checked at all
  (§3.1) — at a *call* it is: `int`/`i32`/`u8` arguments are held to the
  parameter's kind, so a value of Vela's `int` does not become an `i32` at a C
  call. The narrow way through is a binding whose declared type says the width
  (`mut n: i32 = big`), and the real fix is Vela 0.2's width conversion, not a
  rule of the boundary.
* **A field read, an array element and a method call are unknown to the call-site
  rule.** `ck_arg_kind` answers from what the checker can see without the type
  pool — a literal, a bound name, an operator, an indexed array, and (for a call)
  the return kind the declaration filed — and stays quiet for the rest, which is
  the same honesty as the rest of the pass (§7.5). A *builtin*'s result is
  quieter still: `len`, `argc` and `to_int` are not functions in the table, so
  their kind is unknown and `abs(len(s))` is accepted, with C narrowing it. That
  is the one place the boundary is silent where it would rather not be, and it is
  silence on the safe side: a value with no kind the checker can name is a value
  it will not guess at.
* **`mut` on a foreign parameter is accepted and means nothing.** C parameters
  are a copy, and `mut` on a parameter only says what the (absent) body may do —
  the same gap SPEC:148 has for a Vela scalar parameter, which the probes in this
  directory record for the language's own functions.

