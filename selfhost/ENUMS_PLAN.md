# Enums with payloads and `match` — the design, before any code

Status: **not started.** This is the specification for stream 2, item 1 of
`ROADMAP.md` ("enums with payloads + `match`"), written first because the feature
is going into a compiler that is written in Vela and must keep compiling itself.
A change of this size without a written contract and an acceptance test is how a
self-hosted compiler acquires a regression nobody can bisect.

## Why this one is first

Not because it is the most requested, but because **the compiler needs it more
than the language does**. The AST's node kinds are integers today: `check.vel`
dispatches on `nd_kind(nd, e)` and compares against literals (`k == 31` for an
index node, `k == 25` for a name, `26` for a call, `33` for a list, `34` for
whatever is next), and the same numbers appear again in `eval.vel` and in
`emit.vel`. Three files, one implicit enum, no exhaustiveness, and a new node
kind that is forgotten in one of the three fails silently — the checker accepts a
program the emitter then drops on the floor.

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

Acceptance for this step: every `run` case in the corpus is executed by **both**
paths — compiled and interpreted — and the outputs match byte for byte, as the
suite already demands of every `run` case today.

## Order of work, with the fixpoint as the gate at each step

1. **Parse** `enum` declarations and `match` statements into new node kinds. No
   semantics; the emitter and interpreter refuse them with a clear message. The
   compiler still compiles itself, because no existing program uses them.
2. **Resolve and check**: the enum table, the type of a `match` subject, binding
   types, and **exhaustiveness** (a missing variant is an error that names it; a
   duplicate arm is an error; `else` must be last).
3. **Interpreter**: tagged values, pattern binding, and `match` execution.
4. **Emitter**: the tagged struct and the `switch`, with a refuse-case proving an
   exhaustive match emits no `default`.
5. **Dogfood**: the AST node kinds become an enum, and `check.vel`, `eval.vel` and
   `emit.vel` dispatch on it with `match`. This is the step that pays for the
   feature, and it is *last* so that the language feature is proven by the corpus
   before the compiler depends on it.
6. **Fixpoint after every step**, and the suite after steps 2, 3, 4 and 5.

## Acceptance tests, written as cases

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
| `node_kinds_are_enum` | fixpoint | after step 5, the compiler compiles itself with the node kinds as an enum |

## What this deliberately does not claim

It does not make Vela faster. It does not add generics (a payload cannot be a type
parameter in v1), collections, or error handling — it is the thing those three are
built out of. And it does not remove a single check from the back end; the
performance lever is stream ⑤, which is independent of this.
