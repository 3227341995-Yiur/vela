# The Vela language, version 0.1

Vela is a compiled systems language. It reads like Python, but no rule in the
grammar depends on whitespace, and no construct is optional: if you write
something ambiguous, the compiler refuses it and tells you why.

This document is normative for the self-hosted compiler in `selfhost/`, which is
the only implementation in this tree — `vm.exe check` is the rule-by-rule
authority, and the Python front end that used to sit in `vela/` was deleted in
stage 4 (`DESIGN.md` §7). Where the compiler does not yet implement a stated
rule, this document says so explicitly, and §6.1 and §3.1 are the two places
that matter most.

---

## 1. Lexical structure

### 1.1 Lines and statements

* A **statement** ends at a newline.
* Two statements may not share a line: `mut a: int = 1 mut b: int = 2` is a
  syntax error. (Vela never guesses where a statement ends.)
* Newlines are **insignificant inside `(` and `[`**, so a long call or array
  literal may be laid out over several lines. They are *significant* inside
  `{ }`, because inside a block a newline separates statements.
* A trailing `\` immediately followed by a newline also continues a line.
* **Indentation is formatting, never syntax.** Tabs and spaces are equivalent
  whitespace; the lexer keeps no indentation stack, and there are no INDENT or
  DEDENT tokens.
* A **byte-order mark** (`U+FEFF`) at the very start of a file is ignored: it is
  an artefact of the tool that wrote the file (PowerShell's `Set-Content` and
  several editors add one), not part of the program. The compiler never writes
  one.

### 1.2 Comments

`#` to the end of the line. There are no block comments (a decision: nested
block comments are a documented source of bugs in C-family languages).

### 1.3 Identifiers and keywords

Identifiers are `[A-Za-z_][A-Za-z0-9_]*`. Keywords:

```
def return if elif else while for in range break continue pass
and or not True False None mut struct parallel pure
extern                         # `extern c`, implemented in 0.1 — see §12
```

`elif` is lexed but **refused by the parser**: Vela writes `else if`, and the
diagnostic says so.

`import`, `from` and `as` are **not** keywords in 0.1: they are ordinary
identifiers (`mut import: int = 1` is a legal declaration) and nothing refuses
them. A module system would claim them, so they are reserved by documentation
and not by the lexer — a program that uses them as names will need renaming the
day one arrives.

### 1.4 Numbers

* Decimal, with `_` separators: `1_000_000`
* Hexadecimal `0x1F`, binary `0b1010`, octal `0o17`
* Floats: `1.0`, `2.5e3`, `1e-9`. A leading `.5` is a syntax error.
* Integer literals must fit in signed 64 bits.

### 1.5 Strings

* `"..."` or `'...'`, single-line only — a literal may not contain a raw
  newline.
* Escapes: `\n \t \r \0 \\ \" \' \a \b \f \v \xNN \uNNNN`.
* The **value** of a literal is the decoded text, not the text that was written:
  `"\n"` is one newline byte, and `len("\n")` is 1. `unescape` does that decoding
  explicitly, and a back end that emits the source text while claiming the
  decoded length emits the wrong string *and* the wrong length.
  (`DESIGN.md` §7.3 — this is what the first self-hosted build got wrong.)
* **Adjacent literals are refused**: `"a" "b"` is an error, because in Python
  that is a silent concatenation and in C it is not.
* There is no string concatenation operator in 0.1, and no slicing.

### 1.6 Operators

```
( ) [ ] { } , : . -> = == != < <= > >=
+ - * / // % ** & | ^ ~ << >>
+= -= *= /= //= %= &= |= ^= <<= >>= **=
```

---

## 2. Blocks

Blocks are delimited by braces and are always required:

```vela
if n < 2 {
    return n
} else if n == 2 {
    return 1
} else {
    return fib(n - 1) + fib(n - 2)
}
```

* A block may be empty (`{}`); `pass` is also accepted and means the same.
* `if`, `while`, `for` and `def` must be followed by `{`; a `:` is a syntax
  error with the message *"expected '{' to open a block"*.
* `else if` continues the chain without extra braces. A `}` that continues with
  `else` must be on the same line as the `else` (this is the one place a
  newline matters — the alternative is ambiguous inside blocks).
* Every `{` needs a `}`; the lexer reports *"missing '}': a block is never
  closed"* at the end of the file.

---

## 3. Types

| type | meaning |
|---|---|
| `int` | signed 64-bit |
| `i32` | signed 32-bit |
| `u8` | unsigned 8-bit |
| `float` | IEEE-754 double |
| `bool` | `True` / `False` |
| `str` | byte string: pointer + length, no NUL, no terminator to overrun |
| `Array[T, N]` | fixed-size array of `N` elements of type `T` |
| `Name` | a struct type |
| `None` | the empty type, used as a function return type |

### 3.1 Conversion

* **No implicit conversion between the types a rule can compare.** `1 + 2.5` is
  an error; write `to_float(1) + 2.5`. The operator table compares both operands,
  so widths do not mix there either — `x: i32` plus `y: int` is `operator '+'
  mixes i32 and int` — and a foreign call compares its argument with the kind its
  parameter declares (§12).
* The single exception, in both of those places: an **integer literal** adapts to
  the other integer type when the value fits, so `comp[i] == 0` works for a `u8`
  array and `abs(-7)` works for an `i32` parameter.
* `to_float(int) -> float` and `to_int(float) -> int`.
* **A binding is not type-checked, and that is where the width question stops.**
  `mut n: int = comp[i]` — a `u8` element read out of an `Array[u8, N]` — is
  accepted and compiles to the widening itself (`int64_t vl_n = ... vl_a[0LL]`),
  and `mut x: i32 = big` passes even when the value does not fit. An ordinary Vela
  call is not type-checked either (`f("s")` for `def f(x: int)` is accepted, and
  the mistake surfaces later in the C compiler's words), which is exactly why the
  **foreign** call is the careful one: a width is load-bearing where a rule
  compares two types — an operator, or the C boundary — and a binding is not such
  a place. A width conversion the compiler can see is Vela 0.2 work.
* **The interpreter is where the cost of that shows.** `vm.exe run` runs a `u8`
  *scalar* into an `int` binding (the compiled twin agrees), but refuses any
  program that has an `Array[u8]` at all — declaring one is enough, and a `u8`
  read out of one is the case its message names:
  `vela: interpreter: Array[u8] needs an integer width conversion (SPEC 3.1)`.
  It states that refusal rather than guessing at the widening, and it is the
  reason `bench/sieve.vel` has a compiled result and no interpreted one
  (`DESIGN.md` §7.4).
* Arrays do not decay, do not resize, and their length is part of the type.

### 3.2 Declarations

```vela
x: int = 3          # immutable binding, annotated
mut y: int = 0      # mutable binding
mut buf: Array[float, 1024]    # zero-filled array; scalars need an initialiser
```

* Bindings are **immutable by default**. Assigning to a non-`mut` binding is a
  *safety error*, not a silent rebind — this is the single most common Python
  bug class that Vela deletes.
* A scalar may not be declared without an initialiser; an array may, and is
  zero-filled (arena memory is always zeroed).
* An array literal shorter than the declared length is zero-padded;
  `mut a: Array[float, 4] = [0.0]` is legal.
* Struct fields and parameters are declared as `name: T`; `mut` before a
  parameter means the callee may write through it — **and this implementation does
  not keep that promise for a scalar.** What it does instead, measured 2026-09-20
  on `selfhost\build\vm.exe` (634368 bytes) with the interpreter and the C backend
  printing identical bytes, exit 0, and no diagnostic:

  | passed to a `mut` parameter | written back? | measured |
  |---|---|---|
  | a scalar (`int`) | **no** — the callee has a copy | `scalar after bump: 1` (this document promises 2) |
  | an array *element* (`a[0]` into `mut int`) | **no** — the same copy | `array element after bump: 5` (promises 6) |
  | an array (`mut a: Array[int, 2]`) | yes — an array is already a pointer | `array parameter after fill: 42` |
  | a struct (`mut b: Box`, `mut self: Box`) | yes | `struct field after free function: 2` |

  The probes are `tests/probes/mut_scalar_parameter.vel` and
  `tests/probes/mut_struct_parameter.vel`; `DESIGN.md` §7.5 carries the same table
  beside the source's own note about the gap. This paragraph is in the
  specification rather than only in a design note for one reason: **a dropped
  scalar write is a wrong number with exit 0**, so a reader who trusts the sentence
  above it will write a silently wrong program and get no help from the compiler.

---

## 4. Statements

| form | notes |
|---|---|
| `def f(a: T, mut b: U) -> R { ... }` | every parameter and the return type annotated, always |
| `pure def f(...) -> R { ... }` | promises no side effects; required to call it from a `parallel for` |
| `extern c def f(x: T) -> R` / `extern c pure def ...` | a C function, declared: no body, scalar types only, see §12 |
| `struct S { field: T ... def m(self: S, ...) -> R { ... } }` | value type with methods; **fields may not be arrays** |
| `x: T = e` / `mut x: T = e` / `x = e` / `x += e` | declaration, re-assignment, augmented assignment |
| `a[i] = e`, `s.field = e` | indexed and field assignment |
| `if c { } else if c { } else { }` | `c` must be `bool` |
| `while c { }` | `c` must be `bool` |
| `for i in range(a, b)` / `range(a, b, step)` | the only iterable form in 0.1 |
| `parallel for i in range(...) { }` | see §7 |
| `return e` / `break` / `continue` / `pass` | `break`/`continue` only inside a loop |

### 4.1 Refused statements

* An expression statement must be a **call**. `x + 1` on its own line is an
  error: *"this expression statement has no effect"*. This is the compile-time
  version of a very common run-time bug.
* Chained comparison `0 < x < 10` is refused (in Python it means something
  surprising, in C it means something else).
* Tuples do not exist; `(1, 2)` is refused with a message that says so.
* Slices do not exist in 0.1.
* Nested functions and closures do not exist; so a block's declarations are
  visible only inside that block.

---

## 5. Expressions

Precedence, loosest first:

```
or
and
not
==  !=  <  <=  >  >=          (non-associative: no chaining)
|
^
&
<<  >>
+  -
*  /  //  %
unary -  ~
**
calls f(x), indexing a[i], field access s.f
```

* `and` / `or` short-circuit and yield `bool` (not a value).
* `/` is float division and requires two floats; `//` and `%` are integer and
  use **Python's floor semantics**, not C's truncation, for negatives:
  `-7 // 2 == -4`, `-7 % 2 == 1`.
* `**` is integer power for ints, `pow(float, float)` for floats.

---

## 6. Safety rules

Vela's claim is mechanical, not stylistic:

1. **No `unsafe`, no pointers, no `free`, no raw FFI.** There is no syntax that
   can produce undefined behaviour. A C function may be *declared* and called,
   but only as a scalar-to-scalar call (§12): there is no pointer type to write,
   so a foreign signature cannot put one in the source, and the boundary the
   compiler cannot describe honestly — a `str`, an array, a struct — is refused
   instead of guessed at.
2. **All heap storage is one arena.** `free` does not exist, so double-free and
   use-after-free cannot be written. A function's frame is released only when
   it exits.
3. **A function may not return its own array** (the checker refuses it), so an
   arena pointer cannot outlive its frame. `str` storage is either a literal or
   permanent host memory, so a returned string can never dangle.
4. **Indexing is checked.** `a[i]` panics with file, line and reason if `i` is
   out of range, and a *constant* index that cannot be in range is refused before
   the program runs (§6.1).
5. **Integer arithmetic is checked for overflow, always.** There is no flag that
   turns the checking off — no `--fast-int`, no `-O`, nothing — and a check
   disappears only when the compiler proves it cannot fire (§6.1).
6. **Division by a constant zero is a compile error**; a runtime divisor is
   checked.
7. **A constant index that is provably out of range is a compile error.**
8. `parallel for` is accepted **only when race-freedom is proved** (§7).

### 6.1 What gets proved away

Checks are removed only by proof, never by a flag or an annotation — there is no
`unsafe`, no attribute, and no switch. How far the proof machinery actually goes
today, because this document is about the language as it is:

| proof | what it is used for |
|---|---|
| interval analysis on every integer expression | admitting a `parallel for` (§7) and refusing a constant index that cannot be in range |
| loop-variable tracking (`0 <= i < N`) | the same proof: a loop whose write it cannot place is refused rather than parallelised |
| invalidation at loop entry of anything assigned inside | keeping those interval facts *sound* across back-edges |
| condition narrowing (`if i < len(a)`) | designed, not built |
| constant folding of `len(a)` | the length computation itself, so `len` is a constant in the emitted C |

**Deleting a check by proof is not implemented yet.** The back end emits every
bounds and overflow check — `vela_bounds_check`, `vela_add_range` and friends —
including for an index it could obviously prove, and no array parameter is marked
`restrict`. The claim "removed only by proof" is therefore a *rule* the language
keeps (nothing else can remove one) and not yet a fact about the C this compiler
writes: `vm.exe emit-c bench/matmul.vel` shows the checks it kept. What the
proofs buy today is permission and refusal, and they are worth it for those
alone — a `parallel for` that is not proved race-free is refused rather than
emitted and hoped for.

`DESIGN.md` §7.5 lists this among what the self-hosted back end still owes.

### 6.2 Scope rules

A block introduces a scope. Declaration inside a block is invisible outside;
assignment to a name that is not declared in the current function is an error;
declaring the same name twice in one scope is an error. The compiler's own words
for these, taken from the messages it prints and from the golden files that lock
them — the previous version of this paragraph quoted a sentence that appears
**nowhere** in the compiler, which a byte search of `selfhost\build\vm.exe`
(634368 bytes, 2026-09-20) settles: `'different meaning'` and `'one scope'` are
absent, `'cannot rebind'`, `'already declared in this scope'` and `'declared
immutable'` are all present.

| refused | the message |
|---|---|
| the same name declared twice in one scope | `'x' is already declared in this scope` |
| assigning to an immutable binding | `cannot assign to 'x': it was declared immutable` |
| rebinding an array name | `cannot rebind array 'a'` |

Shadowing across blocks stays legal; it is a *redeclaration in one scope* that is
refused, and the message says which name and which scope.

---

## 7. `parallel for`

```vela
parallel for i in range(0, n) {
    out[i] = a[i] * 2
}
```

The checker accepts it only if **all** of these hold:

* the loop must be a `range(...)` loop, so the iteration space is known;
* every array written must be a local `mut` array (never a parameter, never
  shared state);
* an array is written through **one** index expression, and that expression must
  be provably **injective** over the iteration space — either `c * i + rest` with
  `|c|` larger than the range of `rest`, or the row-major form `i * N + j` with
  `j in range(0, N)`;
* **an array the body writes may be read inside the same loop only at that same
  index expression.** This is the aliasing rule, and it is the language-level form
  of what Rust's `par_iter_mut` encodes in a type: an iteration owns its element.
  `out[i] = a[i] * 2` is legal, because `a` is only read here and nothing writes
  it; `a[i] = a[i] + 1` is legal, because each iteration reads exactly what it
  writes; and `a[i] = a[i - 1] + 1` is refused, because it reads an element
  another iteration is writing — the loop carries a dependence, and the compiled
  program prints a different answer on every run. Reads of an array the body does
  **not** write are free, at any index;
* scalars written inside the body must be private to the iteration (declared in
  the body), otherwise the write is shared state and the loop is refused;
* calls inside the body must be `pure`: `print`, `read_text` and friends are
  refused.

Anything else produces a safety error that names the reason, rather than a
`#pragma omp parallel for` handed to the C compiler in the hope that the writes do
not collide: the pragma is emitted only for a loop these rules admitted, and the
build passes `/openmp` (or `-fopenmp`) only when the emitted C carries one. (The
C++ twin of the matmul benchmark in `bench/matmul.cpp` shows the failure mode this
rule prevents: with the inner indices hoisted out of the parallel body, it
silently produces garbage.)

---

## 8. Built-in functions

Pure, and callable from `pure` functions:

| builtin | type |
|---|---|
| `len(a)` | `Array[T, N] -> int` (folded to a constant), `str -> int` |
| `to_float`, `to_int` | `int -> float`, `float -> int` |
| `sqrt`, `fabs`, `floor` | `float -> float` |
| `pow` | `(float, float) -> float` |
| `abs` | `int -> int` |
| `min_int`, `max_int`, `min_float`, `max_float` | two arguments, same type |
| `bytes_at(s, i)` | checked byte value 0..255 |
| `substr(s, a, b)` | slice of a string (non-owning, and safe because every `str` is either a literal or permanent memory — §6 rule 3) |
| `unescape(s)` | decode backslash escapes |
| `concat(a, b)` | the two strings joined. Deliberately a *named* builtin and not `+`: `a + b` on two strings stays refused (§1.5), but a compiler that has to name a file, build a command line or write a message needs some way to make a string, and there is no other. The result is permanent memory, like a literal |
| `interned(h)` | string table lookup |
| `env(name)` | what the environment says, or `""` when it says nothing |
| `argc()`, `arg(i)` | process arguments |

Impure, so **not** allowed inside `pure` or `parallel for`:

| builtin | type |
|---|---|
| `print(...)` | values separated by spaces, then a newline |
| `read_text(path)` | whole file as `str` (empty string if unreadable) |
| `write_text(path, s)` | `bool` |
| `intern(s)` | a stable int handle for a string (content-addressed table) |
| `run_command(cmd)` | hand a command line to the host through the C library's `system()`, and answer `0` when it reported success. The command's own output goes to this process's stdout/stderr, uncaptured. It is **the one capability that is not about data**, and it exists for exactly one reason: a compiler that has just written C has to be able to start a C compiler, or `vm.exe build file.vel` would need a driver in some other language — which is the thing self-hosting deletes (DESIGN §9.4) |
| `panic(msg)` | print to stderr and abort |
| `emit_str/emit_int/emit_float/emit_nl/warn_str/warn_int/warn_nl` | unseparated stdout/stderr output |
| `now()` | wall-clock seconds |

`concat`/`intern`/`interned`/`unescape`/`read_text`/`write_text`/`env`/`run_command`/
`arg`/`argc` exist for one concrete reason: **they are what a Vela-written
compiler needs and cannot express without pointers, globs or a process API.**
They are the smallest host surface that makes self-hosting possible (§10), and
none of them hands back a pointer, a handle into the language's own memory, or a
capability that outlives the call — `run_command` returns one exit status and
keeps nothing. Safe Rust has `std::process::Command`; this is the same kind of
surface, cut down to its smallest useful shape.

---

## 9. Programme entry point

A program has a `def main() -> None` at top level. The compiler generates
`int main(int argc, char **argv)` and calls it; the process exits 0 unless a
checked operation fails, which panics with file and line.

---

## 10. Self-hosting

Vela does not use indentation, and that is not a cosmetic decision: a self-hosted
lexer needs no indentation stack, no INDENT/DEDENT tokens and no blank-line
special cases.

The compiler is Vela, compiled by itself. `selfhost/vela.vel` (the lexer) and
`selfhost/parts/*.vel` (shared state, parser, resolver, checker, interpreter,
C11 emitter, tree dumper, driver) are spliced into the single file
`selfhost/vm.vel` by `tools/link_selfhost.vel` — a build-time concatenation,
because Vela 0.1 has no module system — and `vm.exe build selfhost/vm.vel` turns
that back into the compiler. The stages that got here, and what certified each one
against the Python front end that no longer exists, are in `DESIGN.md` §7.

Everything the compiler does is reachable from its own command line, which §11
lists: `lex`, `count`, `parse` and `nodes` for the front end's own view, `check`
for the verdicts, `emit-c` and `build` for the C and the binary, `run` for the
interpreter. A test that wants the compiler's opinion of a program *and* the
program's own output runs two modes and compares them, which is what the corpus
in `tests/run_tests.vel` does: its `run` cases require the interpreted and the
compiled program to print the same thing.

---

## 11. Command line

The compiler is one binary and its command line is a mode and a file:

```
selfhost\build\vm.exe lex    file.vel    dump the token stream
selfhost\build\vm.exe count  file.vel    how many tokens the file has
selfhost\build\vm.exe parse  file.vel    the syntax tree, in the canonical dump format
selfhost\build\vm.exe nodes  file.vel    the raw node pool, one line per node
selfhost\build\vm.exe check  file.vel    refuse the program if it is not allowed to exist
selfhost\build\vm.exe emit-c file.vel    the C11 the program compiles to, on stdout
selfhost\build\vm.exe run    file.vel [program arguments...]
                                         interpret the program
selfhost\build\vm.exe build  file.vel [runtime-dir]
                                         emit the C and hand it to the host's C compiler,
                                         producing file.c and file.exe beside the source
```

* **No mode has options**, with one exception: `build`'s optional second argument
  is the directory holding `vela_runtime.h` — `runtime` by default, handed to the
  C compiler as its include directory. There is no `-O`, no `-o`, no
  `--fast-int`, no `--no-omp`.
* `check` prints `ok` and exits 0 when the program is accepted, and writes the
  diagnostic to stderr and exits non-zero when it is refused. `run` and `build`
  run the same check first, so no mode emits or executes a program the checker
  would refuse.
* `run` hands the program its arguments: `arg(0)` is the source path and
  `argc() - 1` is the number of arguments after it.
* `build` is the whole toolchain in one binary — no driver script, no Python, and
  no second process other than the C compiler. It emits the C by re-entering
  itself in `emit-c` mode (so the back end keeps one output target and the shell
  does the redirecting), finds MSVC through `VELA_VCVARS` or the standard install
  locations, and falls back to `$VELA_CC`, `cc`, `gcc` or `clang`. It asks the C
  it just wrote whether that C contains a `#pragma omp` and passes `/openmp` (or
  `-fopenmp`) only when it does.
* A missing mode, or one the compiler does not know, prints that list and exits
  non-zero.
* The sources also carry a **`debug` mode** — the same interpreter, stopped, with
  events on **stderr** and commands read from files in a directory the caller
  names (`vm.exe debug file.vel <cmddir> [program arguments...]`).
  `DESIGN.md` §10 is the protocol: the `cmd.NNN` file channel, the events, the
  commands, the command line, and the two defects measured on 2026-09-20 that
  still stand between it and an editor — `continue` disarms the breakpoints of
  the frame it was issued from, and `vars` reports `locals 0` at a stop where a
  local is in scope. This document records that the mode exists and what it
  refuses to promise; the editor-facing verdict is §10.6's.

Building the compiler itself is `tools\build.ps1` (§10, `DESIGN.md` §7): one
command that bootstraps the compiler from the checked-in C, links the parts,
compiles the compiler with the compiler, and checks the result. The suite is
`tests\run_tests.vel`, a Vela program built by that same compiler.

---

## 12. Foreign functions: `extern c`

Vela can call C functions — and only C functions, and only scalar ones. The
spelling is a declaration with no body:

```vela
extern c def abs(x: i32) -> i32              # the C prototype `int abs(int)`
extern c pure def sqrt(x: float) -> float    # `double sqrt(double)`, no side effects
```

* **The declaration is the prototype.** There is no header to include and no
  library to name, and the declared name *is* the C symbol: the back end calls it
  by its bare name, with none of the `vl_` prefix a Vela function gets.
* **The types are scalars.** `int` is C's `long long`, `i32` C's `int`, `u8` C's
  `unsigned char`, `float` C's `double`, `bool` C's `bool`; `-> None` is C's
  `void` result. A `str`, an array and a struct are refused where they are
  written, at the line of the declaration — Vela would have to invent an ABI to
  hand any of them over, and inventing an ABI is how a safe language stops being
  one. A pointer is not expressible at all, which is why the list stops there.
* **`pure`** says the foreign function has no side effects. The compiler cannot
  see a foreign body, so this is the only word available for it, and it is what
  lets a C function be called from a `pure def` or inside a `parallel for`.
* **An argument crosses only as the kind the parameter declares**, checked at the
  line of the call. The one exception is the one §3.1 allows where a rule compares
  two types: an int *constant* whose value fits (`abs(-7)` is legal, `abs(1.5)` is
  not). Without that rule the C compiler would convert silently — `abs(1.5)`
  would print 1, `abs(a)` would truncate an address — and a boundary is the one
  place Vela promised to spell out what crosses. Arity is checked exactly as it is
  for a call to a Vela function (`wrong number of arguments`).
* The **interpreter has no foreign call**: `vm.exe run` evaluates the arguments
  and then produces nothing for the result. Using the result is an error
  (`this value cannot be printed`), and a call whose result is thrown away is
  silently a no-op, side effects included — a gap in `eval.vel`, named in
  `DESIGN.md` §7.4 and §9.2 rather than hidden.
* **Deliberately absent:** `extern c "lib" { ... }` blocks, `cstr`, arrays
  crossing as `(T*, N)`, `extern struct`, variadics, callbacks, library-retained
  pointers, and any unsigned type wider than `u8`. `DESIGN.md` §9 has the
  reasons for each, and the gaps.

The safety claim in §6 stays *mechanical* because of how small this is: what a
declaration can say is a scalar C call and nothing else, so no pointer needs a
type, nothing can be freed, and nothing can be stored. What no boundary can
promise is what the library does with what it is given — a C function can corrupt
memory, and one that calls `abort()` still aborts. Rust has FFI too, and that is
exactly why `unsafe` exists there. C++ is out of scope by decision, because it
has no stable ABI (`DESIGN.md` §9.3).
