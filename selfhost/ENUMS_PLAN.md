# Enums with payloads and `match` — the design, before any code

**English** | [简体中文](ENUMS_PLAN.zh-CN.md)

Status: **not started.** This is the specification for stream 2, item 1 of
`ROADMAP.md` ("enums with payloads + `match`"), written first because the feature
is going into a compiler that is written in Vela and must keep compiling itself.
A change of this size without a written contract and an acceptance test is how a
self-hosted compiler acquires a regression nobody can bisect.

**This plan cites function names, not line numbers.** Line numbers in
`selfhost/parts/*.vel` drift while several agents edit the tree at once, and they
drifted fastest in exactly the documents that quoted them. Every source reference
below names the function and the file; the `file:line` pairs were re-anchored on
2026-09-24 against a named revision of each file (SHA-256 and byte counts in
`ENUMS_PLAN_AUDIT.md`, which also records the delta for each one). Read a line
number here as "where it was on that revision", and the name as the thing to
search for.

## Why this one is first

Not because it is the most requested, but because **the compiler needs it more
than the language does**. The AST's node kinds are integers today: `check.vel`
dispatches on `nd_kind(nd, e)` and compares against literals (`k == 31` for an
index node, `k == 25` for a name, `26` for a call, `33` for a list, `34` for
whatever is next), and the same numbers appear again in `eval.vel` and in
`emit.vel`. `parser.vel:1301`). The five the prose names are the parser's own kind table: 31
index, 25 name, 26 call, 33 list, 34 slice, documented in the header comment of
`parser.vel` (`parser.vel:23`–`:53`) and written by `new_node(nd, cx, 34, …)` in
`parse_postfix` (`parser.vel:1144`), `new_node(nd, cx, 31, …)` at
`parser.vel:1156`, `32` (attr) at `parser.vel:1171`, `new_node(nd, cx, 26, …)` at
`parser.vel:1119` and `new_node(nd, cx, 33, …)` in `parse_atom`
(`parser.vel:1301`). **Six** files carry the same implicit enum, not three:
`resolve.vel`, `check.vel`, `eval.vel`, `emit.vel`, `emit_llvm.vel` and
`dump.vel`, and a new node kind that is forgotten in one of them fails silently —
the checker accepts a program the emitter then drops on the floor, and `d_node`
prints `unknown-node-<k>` (`dump.vel:536`–`:539`) instead of failing.
(`emit_llvm.vel` is the second code generator of `LLVM_PLAN.md`; this plan was
written before it existed, and step 4 below therefore covers both back ends.)

A real enum plus an exhaustive `match` turns that into a compile error at every
dispatch site. That is the acceptance test for the whole feature, and it is
measurable: **add one variant to the node-kind enum and the build must fail at
every place that needs to handle it, with no other changes.**

## Surface syntax

```vela
enum Shape {
    Circle(radius: float)
    Rect(w: float, h: float)
    Empty
}

def area(s: Shape) -> float {
    match s {
        Circle(r) {
            return 3.14159 * r * r
        }
        Rect(w, h) {
            return w * h
        }
        Empty {
            return 0.0
        }
    }
}
```

Decisions, each with its reason:

- **Arms are blocks with braces**, like every other block in Vela. Python's
  indentation is exactly what this language refuses, and giving `match` its own
  layout rule would be the one place where that refusal is quietly revoked.
- **Binding is positional** in this version: `Circle(r)` binds the variant's
  single field; `Rect(w, h)` binds in declaration order. Named binding
  (`Circle(radius: r)`) is deliberately *not* in v1 — it needs the same
  "argument name" machinery calls have, and the first version should be the one
  that cannot be misread.
- **No nested patterns and no guards** in v1. `Circle(Circle(r))` and
  `Circle(r) if r > 0` are refuse-cases, not gaps to be discovered later.
- **`else` is the escape hatch for exhaustiveness**, and it takes no binding:
  `else { ... }` matched last. Without it, a `match` over an enum must name every
  variant.
- **A `match` is a statement, not an expression** in v1, consistent with Vela's
  rule that a value-producing expression cannot be a statement.

## Meaning

An enum value is a **tag plus at most one payload variant**, with value
semantics — the same as a struct. That keeps four properties the language already
promises:

1. **No heap.** The value lives in the frame, like a struct; the arena is not
   involved and nothing can outlive anything.
2. **No `None`, no null variant.** `Empty` is a variant like any other, and it is
   not a magic value: a program that forgets to handle it does not compile.
3. **No exceptions.** `Result[T, E]` (stream ④.3) is just an enum with two
   variants, which is why this feature comes before error handling rather than
   after it.
4. **Fixed size.** `sizeof` is known at compile time, so an enum works inside an
   `Array`, as a struct field, and as a parameter, with no special cases.

Out of scope for v1, and named so nobody assumes otherwise: equality between two
enum values (`==`), hashing, printing an enum with `print`, generic payloads, and
recursion through a payload (`enum Tree { Node(Tree) }` — a value type cannot
contain itself; that needs either a boxed representation or an explicit array).

## Representation in the C backend

A tagged struct, emitted per enum:

```c
typedef struct {
    int64_t vl_tag;                      /* 0, 1, 2 … in declaration order */
    union {
        struct { double radius; } Circle;
        struct { double w; double h; } Rect;
    } vl_u;
} vl_Shape;
```

`match` becomes a `switch (v.vl_tag)` whose arms are the arm blocks. The switch
is emitted **without a `default`** when the match is exhaustive, so a variant added
to the enum without its arm produces a compiler warning from `cl` on the emitted
C — a second net under the checker's own exhaustiveness rule.

The C spelling has to be honest about one thing: a union with a constructor is
what C does well and Vela has no constructors, so the value is built by assigning
the tag and then the payload fields, and read the same way. That is verbose in the
emitted C and invisible in Vela, which is the right trade.

## The interpreter

This is the real work, and it should be said plainly: the tree-walking
interpreter's values are scalars, strings, arrays and structs, and an enum needs a
**tagged value** — a tag plus a payload that may itself be a struct or an enum.
The interpreter is part of the compiler, so this is a change to the thing that is
compiling the language, made by the language. Expect this step to be the one that
takes the time, and expect it to be the one that finds the bugs in the
representation above.

One thing the code already decides for you, in the plan's favour: a struct value
is a **cell** carrying `K_ST()` (kind 12), a heap base in word 1, the struct id in
word 2 and the field count in word 3, built by `exec_construct`
(`eval.vel:1357`–`:1396`) and given its value semantics by `copy_value`
(`eval.vel:133`). An enum's payload is therefore not a new machinery: it is the
same "heap cells plus a copy" path, with the **tag** in one of the words a struct
uses for its field count. The construction site is `exec_construct`'s caller in
`eval_call` (`eval.vel:1478`), and the statement the arms execute is
`exec_stmt` (`eval.vel:1851`, the one function every statement passes through).

Acceptance for this step: every `run` case in the corpus is executed by **both**
paths — compiled and interpreted — and the outputs match byte for byte, as the
suite already demands of every `run` case today.

## Order of work, with the fixpoint as the gate at each step

1. **Parse** `enum` declarations and `match` statements into new node kinds. No
   semantics; the emitter and interpreter refuse them with a clear message. The
   compiler still compiles itself, because no existing program uses them.
   Two facts about today's parser decide how this step starts. First, `enum` and
   `match` are **not keywords**: `keyword_id` (`selfhost/vela.vel:155`–`:226`)
   ends at `extern` = 23, so both spellings lex as ordinary NAME tokens
   (`tk_ival == 0`, `parser.vel:166`–`:168`). That gives the step a cheap order —
   the *statement* forms can be recognised without touching the lexer at all, and
   the keyword is a later, separate change. Second, the dispatcher to extend is
   `parse_stmt` (`parser.vel:403`–`:509`), which is one `if at_kw(...)` arm per
   statement kind, with `parse_simple` (`parser.vel:763`) as its fall-through for
   an assignment or an expression statement. A `match` arm is a keyword at the
   head of a statement and belongs in that dispatcher; a variant construction
   `Circle(1.0)` needs no new syntax at all, because `parse_postfix` already
   builds a kind-26 call node for it (`parser.vel:1119`).
   New kind numbers: the highest in use is 34 and 16–19 have never been used
   (`dump.vel`'s final fall-through, not a reservation), so 35 and 36 cost nothing
   and change no existing node — but do **not** put them in the 16–19 hole.
2. **Resolve and check**: the enum table, the type of a `match` subject, binding
   types, and **exhaustiveness** (a missing variant is an error that names it; a
   duplicate arm is an error; `else` must be last).
   "Resolve" is not optional here: a struct is registered by `add_struct` in
   `resolve.vel` (`resolve.vel:307`) into the struct table `vm_state.vel` indexes
   through `stb_*` (`vm_state.vel:389`–`:403`), and an enum needs the same
   treatment — a table built before bodies are judged, because the checker that
   judges a `match` is a different pass from the resolver that walked the
   program. The two dispatch points a `match` reaches are the statement
   dispatcher `ck_stmt` (`check.vel:2932`), by way of `ck_block`/`ck_body`
   (`check.vel:2913`/`:2924`), and, for a variant construction and for the
   subject expression, `ck_expr` (`check.vel:1281`).
3. **Interpreter**: tagged values, pattern binding, and `match` execution.
4. **Emitter**: the tagged struct and the `switch`, with a refuse-case proving an
   exhaustive match emits no `default`.
   **Plural today.** There are two code generators, not one: the C11 emitter in
   `emit.vel` and the LLVM backend of `LLVM_PLAN.md` in `emit_llvm.vel`, which is
   spliced into the linked compiler as its own part (`tools/link_selfhost.vel`,
   `part_name(7)`). The statement dispatchers are `emit_stmt` (`emit.vel:1628`)
   and `ll_stmt` (`emit_llvm.vel:2783`); an enum that lands in the C backend only
   makes every `llvm-*` case refuse, which is not the same as the feature being
   implemented. Decide per backend whether the step ships both or the LLVM one
   refuses out loud (the LLVM backend's own convention: it refuses rather than
   guesses), and say which in the closing line of the step.
5. **Dogfood**: the AST node kinds become an enum, and `check.vel`, `eval.vel` and
   `emit.vel` dispatch on it with `match`. This is the step that pays for the
   feature, and it is *last* so that the language feature is proven by the corpus
   before the compiler depends on it.
   Six dispatch sites have to be rewritten for the acceptance test below to mean
   anything, and three of them are not in the sentence above: `resolve.vel`
   (`walk_stmt` `resolve.vel:552`, `walk_expr` `:422`, `add_struct` `:307`),
   `emit_llvm.vel` (`ll_stmt` `:2783`, `ll_expr` `:2270`) and `dump.vel`
   (`d_node` `dump.vel:195`), whose fall-through prints `unknown-node-<k>`
   (`dump.vel:536`). Measured on 2026-09-24: 83 lines comparing a kind against a
   literal in `check.vel`, 58 in `emit.vel`, 41 in `emit_llvm.vel`, 30 in
   `dump.vel`, 29 in `eval.vel`, 23 in `resolve.vel`. A `match` that is not
   exhaustive over **all six** buys the compiler nothing at the site that was
   left out.
6. **Fixpoint after every step**, and the suite after steps 2, 3, 4 and 5.
   There is a second, weaker gate that no step may skip: `tools/link_selfhost.vel`
   splices the ten `parts/*.vel` files into `selfhost/vm.vel`, and the linked file
   is what the fixpoint compiles. Relink (`tools/link_selfhost.exe --check` reports
   whether it is stale) before calling any step green, or the fixpoint is measuring
   the previous revision of the part that was just edited.

## Acceptance tests, written as cases

**Most of this table is already written and has been for a while.** Twelve
`.vel` files sit in `tests/accept/` (they moved there out of the plugin's corpus
in commit `778ff3a`), covering every case below: `enum_payload_bind`,
`enum_match_no_else`, `enum_missing_variant`, `enum_duplicate_arm`,
`enum_binding_arity`, `enum_unknown_variant`, `enum_nested_pattern`,
`enum_in_array`, `enum_self_payload`, `enum_exhaustive_switch`, plus
`enum_else_not_last` and `enum_match_non_enum`, which the table below does not
list. So the work in this column is **not** writing the cases — it is (a) the two
gaps named after the table, and (b) **wiring `tests/accept/` into a harness**:
nothing reads that directory today. It is not in `tests/cases.txt` (which has no
`enum` line at all), and no script under `tools/` walks it — `tests/cases.txt` and
`tests/run_tests.vel` are what the suite runs. A case that no runner runs is a
file, not a test.

| case | mode | what it proves |
|---|---|---|
| `enum_payload_bind` | run | each variant's payload binds positionally and prints |
| `enum_match_no_else` | run | an exhaustive match over all variants runs |
| `enum_missing_variant` | refuse | a match that omits one names it in the diagnostic |
| `enum_duplicate_arm` | refuse | a repeated variant is refused |
| `enum_binding_arity` | refuse | `Circle(r, s)` for a one-field variant is refused |
| `enum_unknown_variant` | refuse | `Square(x)` for an enum that has no `Square` |
| `enum_nested_pattern` | refuse | `Circle(Circle(r))` — out of scope in v1, refused rather than half-working |
| `enum_in_array` | run | an enum inside an `Array[T, N]` with value semantics |
| `enum_self_payload` | refuse | `enum Tree { Node(Tree) }` — an infinite value type |
| `enum_exhaustive_switch` | native + dumps | the emitted C has no `default` in the exhaustive switch |
| `enum_else_not_last` | refuse | **not in the original table**: `else` before a variant arm is refused (design decision: `else` matches last) |
| `enum_match_non_enum` | refuse | **not in the original table**: `match` over a non-enum subject is refused, not silently unhandled |
| `node_kinds_are_enum` | fixpoint | after step 5, the compiler compiles itself with the node kinds as an enum |

## What this deliberately does not claim

It does not make Vela faster. It does not add generics (a payload cannot be a type
parameter in v1), collections, or error handling — it is the thing those three are
built out of. And it does not remove a single check from the back end; the
performance lever is stream ⑤, which is independent of this.
