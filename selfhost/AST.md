# Vela self-hosted AST — implementation reference

Sources: **lexer** `selfhost/vela.vel` — the front half, which
`tools/link_selfhost.vel` splices into `selfhost/vm.vel`; **parser**
`selfhost/parts/parser.vel`; **resolver** `selfhost/parts/resolve.vel`;
**checker** `selfhost/parts/check.vel`; **interpreter**
`selfhost/parts/eval.vel` with `selfhost/parts/vm_state.vel`; **emitter**
`selfhost/parts/emit.vel`; **dump** `selfhost/parts/dump.vel`; **driver**
`selfhost/parts/vm_main.vel`; **runtime** `runtime/vela_runtime.h`. All parts
compile as one translation unit, so the pools and `Ctx` are shared by every stage.

**Read the `file:line` citations below as historical.** They were written when
`selfhost/parts/parser.vel` was about 1270 lines and the front end stopped after
the parser; the file has grown a great deal since, and every line number in this
document is now an approximation of where a thing used to be. What is accurate is
the *shape* — stride, word meanings, chain linking, name handles — and §9, which
lists what the AST still does not record. That list is the part that matters for
anything reading this document: `resolve.vel` and `check.vel` supply some of
those gaps at run time, and §9 has been kept honest about which.

---

## 1. Node pool

| pool | declaration | type | capacity | elements used |
|---|---|---|---|---|
| nodes | `vm_main.vel:59` | `Array[int, 655360]` | 655360 ints = 65536 nodes × stride 10 | ≤ 65536 (`parser.vel:91`) |
| tokens | `vm_main.vel:57` | `Array[int, 393216]` | 393216 ints = 65536 tokens × stride 6 | ≤ 65536 (`vela.vel:295`) |
| token floats | `vm_main.vel:58` | `Array[float, 65536]` | 65536 | one per FLOAT token |
| types | `vm_main.vel:60` | `Array[int, 262144]` | 262144 ints = 65536 types × stride 4 | ≤ 65536 (`parser.vel:241`) |
| floats | `vm_main.vel:61` | `Array[float, 65536]` | 65536 | ≤ 65536 (`parser.vel:229`) |

**Stride is 10** (`parser.vel:11`, `parser.vel:95`): node `n` occupies
`nd[n*10 .. n*10+9]`. `new_node` (`parser.vel:89`) allocates sequentially —
`n = cx.ni`, then `cx.ni += 1` (`parser.vel:106`) — and returns `n`. Node 0 is
always the module node (`parser.vel:1238`; `dump_module` starts there,
`dump.vel:542`), allocated before any statement. Node indices therefore increase
in *completion* order: a nested block is finished before the enclosing
`if`/`def`/`while`/`for` node is created (`parser.vel:526`, `:585`, `:604`,
`:679`), while expression operands get smaller indices than their operator node
(`parser.vel:946`, `:964`). Index order is not traversal order.

### 1.1 Word meanings

`nd[i*10 + k]`, names as fixed by the header (`parser.vel:11`–`parser.vel:13`):

| k | name | meaning |
|---|---|---|
| 0 | `kind` | node kind (§2) |
| 1 | `a` | first payload word (kind-dependent) |
| 2 | `b` | second payload word |
| 3 | `c` | third payload word |
| 4 | `d` | payload: the `if` else head (`parser.vel:589`) and the `for` step (`parser.vel:683`). Still `0` on every other kind |
| 5 | `line` | source line of the statement/expression's first token (`parser.vel:101`) |
| 6 | `flags` | bit 1 `mut`, bit 2 `declaration`, bit 4 `pure`, bit 1 of a `for` = `parallel` |
| 7 | `nx` | next link in the chain this node belongs to, or `-1` (`parser.vel:103`) |
| 8 | `e` | body head (`def`, `for`) or else-count (`if`) |
| 9 | `f` | body statement count (`def`, `for`) |

Initialisation is total (`parser.vel:96`–`parser.vel:105`): every word is
written at allocation — `0`, except word 5 (`line`) and word 7 (`nx = -1`).
Unused words are therefore **mostly `0`, not `-1`**. The only sentinels the
parser writes as `-1` are `return.a` (`parser.vel:417`), `assign.b`
(`parser.vel:760`) and `assign.c` for a non-declaration (`parser.vel:760`); a
declaration writes a real type index there (`parser.vel:724`, `:746`).

---

## 2. Node kinds

There are **no named kind constants anywhere**; every kind is a bare `int`
literal at a `new_node` call site, documented only by the comment block
`parser.vel:22`–`parser.vel:52`.

| kind | name | a (1) | b (2) | c (3) | flags (6) | rest | defined |
|---|---|---|---|---|---|---|---|
| 1 | module | first stmt head, `-1` if empty | stmt count | — | — | root, `nx=-1` | `parser.vel:22`, `:1238`, `:1267` |
| 2 | def | name handle | return type idx | first param head | 4 = `pure` | `e`=body head, `f`=stmt count | `parser.vel:23`, `:526`, `:531` |
| 3 | param | name handle | type idx | — | 1 = `mut` | `nx` links param list | `parser.vel:25`, `:491`, `:494` |
| 4 | struct | name handle | body head | body count | — | — | `parser.vel:26`, `:554` |
| 5 | field | name | type | — | — | **never emitted** (§2.1) | `parser.vel:27` |
| 6 | assign/decl | target node | value node or `-1` | declared type idx or `-1` | 1=`mut`, 2=declaration | — | `parser.vel:28`, `:719`, `:741`, `:757` |
| 7 | augassign | target node | value node | base operator code | — | — | `parser.vel:30`, `:767` |
| 8 | expr stmt | expression node | — | — | — | — | `parser.vel:31`, `:773` |
| 9 | return | value node or `-1` | — | — | — | — | `parser.vel:32`, `:416` |
| 10 | if | cond | then head | then count | — | **`d`(4)=else head** (`-1` if none), `e`(8)=else count | `parser.vel:33`, `:585`–`:590` |
| 11 | while | cond | body head | body count | — | — | `parser.vel:34`, `:604` |
| 12 | for | var name handle | start node | stop node | 1 = `parallel` | `d`(4)=step or `-1`, `e`=body head, `f`=count | `parser.vel:35`, `:679`, `:684` |
| 13 | break | — | — | — | — | — | `parser.vel:37`, `:431` |
| 14 | continue | — | — | — | — | — | `parser.vel:37`, `:439` |
| 15 | pass | — | — | — | — | — | `parser.vel:37`, `:443` |
| 20 | int | value | — | — | — | — | `parser.vel:38`, `:1121` |
| 21 | float | float-pool idx | src offset | src length | — | — | `parser.vel:39`, `:1130` |
| 22 | str | src offset | src length | — | 1 = has escapes | — | `parser.vel:40`, `:1144`, `:1147` |
| 23 | bool | 0 or 1 | — | — | — | — | `parser.vel:41`, `:1159`, `:1165` |
| 24 | none | — | — | — | — | — | `parser.vel:42`, `:1171` |
| 25 | name | name handle | — | — | — | — | `parser.vel:43`, `:1153` |
| 26 | call | callee node | first arg head | arg count | — | `nx` chains args | `parser.vel:44`, `:1047` |
| 27 | binop | operator code | left | right | — | — | `parser.vel:45` |
| 28 | unary | operator code | operand | — | — | — | `parser.vel:46`, `:984` |
| 29 | boolop | 13 `and` / 14 `or` | left | right | — | left-assoc, loop-built | `parser.vel:47`, `:798`, `:815` |
| 30 | cmp | operator code | left | right | — | one comparison only | `parser.vel:48`, `:859`, `:855` |
| 31 | index | value node | index node | — | — | — | `parser.vel:49`, `:1084` |
| 32 | attr | value node | name handle | — | — | — | `parser.vel:50`, `:1099` |
| 33 | list literal | first elem head | elem count | — | — | empty list is an error, so head is never `-1` | `parser.vel:51`, `:1223`, `:1219` |
| 34 | slice | value node | lower or `-1` | upper or `-1` | — | same number as the `}` **operator** code — unrelated namespaces | `parser.vel:52`, `:1072` |

Kinds 16–19 do not exist. An unknown kind makes `dump.vel:534` print
`unknown-node-<k>`.

### 2.1 Kind 5 (`field`) is dead code

`parser.vel:27` documents kind 5 and `dump.vel:180`–`dump.vel:188` prints it,
but **no `new_node(nd, cx, 5, …)` call exists in `parser.vel`**. A struct field
`name: T` goes through the generic declaration path
(`parser.vel:728`–`parser.vel:748`) and yields kind **6** with `flags = 2`,
`b = -1`, `c` = type index, and `a` = a fresh kind-25 name node. `dump.vel:246`
looks for `nd[c*10] == 6` in a struct body, and `dump.vel:186` reads the field
type from word **3** of that assign node.

So a field declaration and a local declaration are byte-identical node shapes
(`kind 6, flags 2, b = -1`). Nothing on the node says "field": you must know you
are walking a kind-4 body to interpret it as one. Methods in a struct are
ordinary `def` nodes (`dump.vel:253`).

### 2.2 Operator codes

One numbering for the whole front end (`parser.vel:160`), table at
`vela.vel:49`–`vela.vel:53`: `1 +` `2 -` `3 *` `4 /` `5 %` `6 <` `7 >` `8 =`
`9 (` `10 )` `11 ,` `12 :` `13 .` `14 [` `15 ]` `16 !` `17 &` `18 |` `19 ^`
`20 //` `21 **` `22 ==` `23 !=` `24 <=` `25 >=` `26 ->` `27 +=` `28 -=` `29 *=`
`30 /=` `31 %=` `32 ~` `33 {` `34 }` `35 <<` `36 >>` `37 <<=` `38 >>=`
`39 &=` `40 |=` `41 ^=` `42 **=` `43 //=`.

Augmented assignment is lowered in the parser: word 3 of the kind-7 node holds
the *base* code from `aug_base` (`parser.vel:179`–`parser.vel:214`) — `+=`→`1`,
`**=`→`21`, `//=`→`20`. One statement kind to execute instead of twelve
(`parser.vel:177`), but the interpreter performs the read-modify-write itself.

Precedence is the call graph, loosest outermost (`parser.vel:779`–`parser.vel:782`):
`or` → `and` → `not` → `cmp` → `|` → `^` → `&` → `<<`,`>>` → `+`,`-` →
`*`,`/`,`%`,`//` → unary → `**` (right-associative: right operand via
`parse_unary`, `parser.vel:1002`) → postfix (call, index, slice, attr) → atom.
Chained comparison is refused (`parser.vel:855`). Unary accepts `-`(2), `+`(1)
and `~`(32) (`parser.vel:979`).

---

## 3. Linkage and ordering of children

Every chain is a singly linked list through word 7, terminated by `nx == -1`.
**A "head" is the node index of the first link; `-1` is the null chain**
(`parser.vel:16`–`parser.vel:20`) — the head is never `0`, because node 0 is the
module node. The one producer pattern (`parser.vel:343`–`:351`,
`parser.vel:1253`–`:1261`): if `head < 0` then `head = s`, else
`nd[tail*10+7] = s`; then `tail = s`, `count += 1`.

| construct | head | count | order |
|---|---|---|---|
| module statements | `nd[1]` (`parser.vel:1267`) | `nd[2]` | textual |
| block statements | `parse_body` return (`parser.vel:524`, `:552`, `:601`, `:676`) | `cx.rslot` (`parser.vel:365`) | textual, `NEWLINE` skipped (`parser.vel:338`) |
| function parameters | def word 3 (`parser.vel:529`) | def word 4 (`parser.vel:530`) | signature order |
| call arguments | call word 2 (`parser.vel:1049`) | call word 3 (`parser.vel:1050`) | call order, `,`-separated (`parser.vel:1035`) |
| array elements | list word 1 (`parser.vel:1224`) | list word 2 (`parser.vel:1225`) | `[` … `]` order |
| struct members | struct word 2 (`parser.vel:556`) | struct word 3 (`parser.vel:557`) | **source order, fields and `def`s interleaved** |
| `if` then / else | words 2 / 4 (`parser.vel:587`, `:589`) | words 3 / 8 | else is a block chain **or** one nested `if` node |
| `while` body | word 2 (`parser.vel:606`) | word 3 | — |
| `for` / `def` body | word 8 (`parser.vel:685`, `:532`) | word 9 (`parser.vel:686`, `:533`) | — |

Consequences:

* A chain's indices increase but its members are **not contiguous**: nested
  blocks are allocated between an enclosing statement's node and the next
  statement of the same chain — which is why chains are linked rather than
  packed (`parser.vel:18`–`parser.vel:20`). Never index into a statement list;
  walk `nx`.
* **Field order is source order in the chain, not a separate list.** `dump.vel`
  deliberately prints fields first and then methods by walking the same chain
  twice (`dump.vel:243`–`dump.vel:257`). An interpreter wanting declaration
  order for field offsets must walk once and classify by `nd[c*10]`
  (`6` = field, `2` = method).
* **`else if` is not a block.** `parse_if` recursion stores the inner `if` node
  directly in word 4 and sets else-count `1` (`parser.vel:578`–`:579`);
  `dump.vel:332` disambiguates by testing `nd[e*10] == 10`. Test the kind, not
  the count.
* Counts are stored but never cross-checked by any reader; `dump.vel` walks the
  `-1` chain and ignores every count. On an error path the count can be stale
  (§8): **trust the chain, not the count.**

---

## 4. Name handles

Token layout (`vela.vel:40`–`vela.vel:43`): `tk[i*6+0]` kind, `+1` offset,
`+2` length, `+3` value (int value / op code / keyword id), `+4` line, `+5` flag;
accessors `tk_kind` `vela.vel:58`, `tk_off` `:62`, `tk_len` `:66`, `tk_ival`
`:70`, `tk_line` `:74`, `tk_flag` `:78`. **The "six ints per token" prose at
`vela.vel:12` is stale** — the stride is 6, as the accessors and `push_tok`
(`vela.vel:299`–`:306`) show. Token kinds `vela.vel:48`: `1 NAME 2 INT 3 FLOAT
4 STR 5 OP 6 NEWLINE 9 EOF`; keyword ids `vela.vel:54`–`:56`: `1 def 2 return
3 if 4 elif 5 else 6 while 7 for 8 in 9 range 10 break 11 continue 12 pass
13 and 14 or 15 not 16 True 17 False 18 None 19 mut 20 pure 21 struct
22 parallel`. A keyword token is a NAME token whose `tk_ival` is the keyword id
(`vela.vel:422`), and a non-keyword identifier has `tk_ival == 0`
(`parser.vel:138`).

A name is stored on a node as an **interned handle** (an `int`), produced by
`name_id` (`parser.vel:154`–`:157`) =
`intern(substr(src, tk_off, tk_off+tk_len))` with `cx.ti` on the NAME token. It
is used for function names (`:458`), parameter names (`:480`), struct names
(`:550`), declared names (`:701`, `:729`), loop variables (`:620`), attribute
names (`:1097`) and name expressions (`:1151`).

The table is the runtime's: `VELA_INTERN_CAP 8192`
(`runtime/vela_runtime.h:431`), backing `vela_intern_tab[8192]` (`:432`),
`vela_intern` linear-search-then-append (`:435`), `vela_interned(h)` a
bounds-checked lookup (`:450`). It is **content-addressed**, so **handle
equality is string equality** (`parser.vel:75`–`:76`), and handles live for the
process. Exhausting 8192 names is a hard `panic("string table full")` (`:442`).

From inside Vela, recover characters with the builtin `interned(h) -> str`
(`SPEC.md:296`; `dump.vel:149` and `:102` do exactly that for nodes and type
records). `interned` is pure, `intern(s)` is impure (`SPEC.md:296`, `:306`) and
can panic — an interpreter that only compares names should compare
`interned` forms rather than interning at run time.

---

## 5. Literal values and source text

`src` is read once (`vm_main.vel:49`) and passed to parser (`:83`) and dumper
(`:87`), so token and node offsets are byte offsets into that exact string.
`len(str)` is a byte count, `bytes_at` a checked byte read, `substr` a
non-owning safe slice (`SPEC.md:287`, `:293`, `:294`).

* **int** (20): value in word 1 (`parser.vel:1122`) from `tk_ival`
  (`parser.vel:1119`). Base-2/8/16 prefixes and `_` separators are resolved by
  the lexer (`vela.vel:318`–`:347`, `:353`), so the AST always holds the final
  integer.
* **float** (21): word 1 = index into the float pool (`parser.vel:1131`, value
  stored by `flt_add` `:233`); words 2/3 = source offset/length of the literal
  as written (`:1135`, `:1136`). Value: `flt[nd[n*10+1]]`. Written spelling:
  `substr(src, off, off+len)`. The dumper prefers the source text and falls back
  to `emit_float` only when `len <= 0` (`dump.vel:400`–`:406`) — unreachable
  through this parser.
* **str** (22): words 1/2 = source offset and length **excluding the quotes**
  (`parser.vel:1140`–`:1146`; `scan_string` starts after the opening quote,
  `vela.vel:429`); word 6 = the lexer's escape flag (`parser.vel:1147`, set when
  a backslash was seen, `vela.vel:437`–`:439`). **Strings are neither interned
  nor decoded.** The interpreter must call `unescape(s)` when the flag is `1`,
  exactly as `dump.vel:120`–`:122` does; with flag `0` the raw bytes are the
  value. `unescape` is pure (`SPEC.md:295`) and decodes
  `\n \t \r \0 \a \b \f \v \\ \" \' \xNN \uNNNN`
  (`runtime/vela_runtime.h:467`–`:520`). Two spellings of the same value
  therefore have different ASTs: `"ab"` and `"a\x62"` are not the same bytes, so
  nothing can be deduped by node identity.
* **bool/none** (23, 24): word 1 is `1`/`0` (`parser.vel:1160`, `:1166`); `none`
  carries nothing.
* Single- and double-quoted strings are lexed identically (`vela.vel:602`,
  `:425`–`:428`) and the AST does not record which quote was used.

---

## 6. Type records

Pool `Array[int, 262144]` (`vm_main.vel:60`), cursor `cx.sp` (`vela.vel:32`),
capacity 65536 (`parser.vel:241`), allocated by `ty_add` (`parser.vel:238`–
`:251`), stride **4** (`parser.vel:55`, `:245`–`:248`):

| k | meaning |
|---|---|
| 0 | kind — **always `1`**; hard-coded (`parser.vel:245`), no other value exists in the front end |
| 1 | name handle of the base type name (`parser.vel:246`) |
| 2 | element type index, `-1` if not an array (`parser.vel:247`) |
| 3 | array length, `-1` if absent (`parser.vel:248`) |

`Array[int, 8]` is not a distinct record kind: it is a record named `"Array"`
whose element is an `int` record and whose length is 8 (`parser.vel:59`–`:61`).
`dump.vel:95`–`:113` prints `name`, then `[elem]` or `[elem,size]` when
`elem >= 0`, and `-` for the index `-1` ("no type", e.g. a bare `return`,
`parser.vel:417`).

`parse_type` (`parser.vel:256`–`:296`):

* `None` is a **keyword** (`parser.vel:262`) and becomes an ordinary record
  named `"None"` with `elem = size = -1` (`:264`) — nothing marks it specially,
  and it is indistinguishable from a user type literally named `None`.
* A bare name becomes `ty_add(base, -1, -1)` (`:295`).
* `name[E]` / `name[E, N]`: `[`=14, `]`=15, `,`=11 (`:274`, `:280`, `:289`).
  The length must be an **INT token** (`tk_kind == 2`, `:282`), taken from
  `tk_ival` (`:286`) — a literal, not a symbol or expression. Element parsing is
  recursive, so `Array[Array[int,4],8]` nests records.
* Every call appends; **there is no deduplication and no type cache** — three
  `int` annotations produce three equal records.

Type indices appear on exactly three words:

| node | word | line |
|---|---|---|
| param | 2 | `parser.vel:493` |
| def return type | 2 | `parser.vel:528` |
| declaration — `mut x: T`, `x: T = …`, and struct fields | 3 | `parser.vel:724`, `:746`, read at `dump.vel:186` |

Nowhere else: **no expression, loop variable, array literal, call, index or
return value carries a type.**

---

## 7. Program-level structure

* `parse_module` (`parser.vel:1235`–`:1269`) allocates the module node first
  with line `1` (`:1238`), parses until `at_eof` (`:1242`) and stores head/count
  in words 1/2 (`:1267`). It returns that node index; the driver ignores it and
  dumps node 0 (`vm_main.vel:83`, `dump.vel:542`).
* **There is no function list and no struct list.** Walk the module statement
  chain and test `nd[s*10]`: `2` = def, `4` = struct (cf. `dump.vel:196`–`:259`).
  Everything else at top level is a statement shared with block bodies.
* **`def main`**: no flag, no index. Compare the interned name handle of every
  kind-2 node (word 1, `parser.vel:527`) against the name `main`
  (`interned`, `dump.vel:205`/`:149`). The parser checks neither the name nor the
  arity nor the return type, although the entry point is required to be
  `def main() -> None` (`SPEC.md:320`).
* **def** (kind 2): name handle word 1 (`:527`), return type word 2 (`:528`),
  first param word 3 (`:529`), param count word 4 (`:530`), flag bit 4 = `pure`
  (`:385`, `:531`), body head word 8 (`:532`), body count word 9 (`:533`).
* **param** (kind 3): name handle word 1 (`:492`), type word 2 (`:493`), flag 1
  = `mut` (`:471`–`:474`, `:494`); a missing type annotation is a hard error
  (`:483`); `nx` links the list (`:498`).
* **struct** (kind 4): name handle word 1 (`:555`), body head word 2 (`:556`),
  body count word 3 (`:557`). The body is parsed as an ordinary block (`:552`),
  so members arrive as the same nodes a function body would produce
  (`:539`–`:540`). An empty struct body is not rejected by the parser.

---

## 8. `Ctx`

Declared `vela.vel:26`–`:37`; constructed positionally as
`Ctx(0, 1, 0, 0, 0, 0, 0, 0, 0, 0)` (`vm_main.vel:62`) — order is declaration
order, so `pos = 0`, `line = 1`, all else `0`.

| # | field | meaning | written | read |
|---|---|---|---|---|
| 0 | `pos` | lexer byte cursor | `vela.vel:566`, `:579`, `:581`, `:584`, `:601`, `:628` | loop test `vela.vel:569`, `:588` |
| 1 | `line` | current line, starts at 1 | `vela.vel:580`, `:594`, `:304` | token lines; `report` `:288` |
| 2 | `ntok` | tokens emitted | `push_tok` `vela.vel:306` | `vm_main.vel:16`, `:77` |
| 3 | `ti` | parser token cursor | reset `vm_main.vel:82`; incremented ~50× in `parser.vel` | `ti_*` (`parser.vel:113`–`:123`); `at_op2` peeks `ti+k` (`:130`) |
| 4 | `ni` | node allocator cursor | `parser.vel:106` | `parser.vel:90`, bound `:91` |
| 5 | `sp` | type pool cursor = number of type records | `parser.vel:249` | `parser.vel:240` |
| 6 | `fp` | float pool cursor = number of float literals | `parser.vel:234` | `parser.vel:228` |
| 7 | `rslot` | **out-parameter**: item count of the chain just parsed | `parser.vel:325` (=0), `:365` (=count) | callers of `parse_body`: `:525`, `:553`, `:568`, `:582`, `:602`, `:677` |
| 8 | `err` | sticky flag, `0` ok / `1` failed; only the first error is reported | `perr` `parser.vel:80`, `report` `vela.vel:284` | most parser loops and the driver `vm_main.vel:65`, `:84` |
| 9 | `aux` | **reused**: bracket depth while lexing, loop depth while parsing | `vela.vel:544`, `:547`; `parser.vel:600`, `:603`, `:675`, `:678` | `vela.vel:574` (suppress newlines inside `(`/`[`); `parser.vel:426`, `:434` (`break`/`continue` outside a loop) |

Reuse and hazards:

* The two meanings of `aux` never overlap: lexing ends before `parse_module`
  (`vm_main.vel:64`, `:83`), and the parser decrements on both loop exits
  (`parser.vel:603`, `:678`). An interpreter sharing `Ctx` is bound by the same
  contract.
* `pos`/`line` are dead in the parser; `ntok` is dead in the parser (only
  `lex`/`count` read it); `ti`, `ni`, `sp`, `fp`, `rslot`, `err` are dead in the
  lexer.
* `err = 1` is not an exception. Parse functions keep running after an error and
  return `-1`; a caller that treats `-1` as a node index would index
  `nd[-10 …]`. The dumper is only reached when `cx.err == 0` (`vm_main.vel:84`);
  an interpreter should keep that guard.
* `rslot` is the one stale value a caller can read: `parse_body` returns the
  partial head at `parser.vel:358` and `:362` without setting it, so an error
  path leaves the previous successful `parse_body`'s count in place.

---

## 9. Gaps — what the AST does not record

1. **No expression carries a static type.** `parse_expr` and every level below
   return a bare node index and never write a type index. A consumer must do its
   own inference or be fully dynamic, which is exactly what `eval.vel` does.
   (Stage 0's Python checker computed expression types, but they lived in its own
   AST objects and never entered these pools; that front end was deleted in
   stage 4, and `selfhost/parts/check.vel` is what does the judging now.)
2. **No struct field offsets** and no field index. Fields exist only as the
   source-ordered chain of kind-6 nodes inside a struct body. Layout, padding,
   ordering and the no-array-field rule are the resolver's and the interpreter's
   problem — `resolve.vel` builds the field table, and `check.vel` refuses an
   array field.
3. **No method ownership.** A method is a plain `def` with no pointer to its
   struct. Two structs may each define `def get(...)` and their name handles are
   *equal* (content-addressed interning, §4). Build a `(struct, name)` map
   yourself.
4. **Array length and element type are not attached to uses.** The declaring
   type record has `elem`/`size` (`parser.vel:247`–`:248`), but nothing links an
   `index` node (31) or an array literal (33) to it. Asymmetry worth noting: an
   array *parameter* annotation is recorded and a `list` literal holds only a
   head and a count — the reason an empty literal is a hard error is that there
   is no element type to store (`parser.vel:1219`).
5. **`for` is `range()`-only and carries no iterable type or `mut` flag.**
   Any other callee is refused (`parser.vel:627`–`:634`); there is no `range`
   node — only `start`/`stop`/`step` expressions (`:681`–`:683`). A one-argument
   `range(n)` is desugared with a synthetic int-0 node allocated *after* the
   real arguments (`:670`–`:672`), so `start` is always a node, never `-1`.
   `parallel` is only flag bit 1 (`:684`) with no further semantics recorded.
   `for i in range(n)` gives no indication whether `i` is mutable.
6. **No variable binding information.** A name node holds only a handle
   (`:1154`): no scope id, no slot, no local index, no distinction between
   local, parameter, field and function. All resolution is the interpreter's.
7. **Mutability is recorded but never enforced *by the parser*.** `mut` sets bit 1
   on a declaration or parameter; a plain `x = e` is kind 6 with `flags = 0` and
   no type. The parser accepts an undeclared target, so `undeclared = 1` parses
   and dumps as `assign`. Assignment to a non-`mut` name is refused later, by
   `check.vel`, which is where the binding and mutability diagnostics live now.
8. **Assignment targets are not validated *by the parser*.** It accepts any
   expression in word 1 of kinds 6/7, so `1 = 2`, `f(x) = 1` and `(a+b) = c` all
   parse. `a[0] = x` degenerates into an `index` node where a place was expected —
   shape-identical to whatever an array store would be — and Vela has no
   user-defined subscripting. `check.vel` refuses the ones that are not places.
9. **No call resolution and no arity, *in the node*.** A call is only
   `(callee, args, count)`. Nothing on the node says whether the callee is a
   builtin (`len`, `substr`, `intern`, …), a user function or `range`; there is no
   callee-kind word and no link from a call to a def. `resolve.vel` binds every
   call to a function record and is where arity is refused; the `pure` flag lives
   on the `def`, and the pass that decides whether a body may call it is
   `check.vel`.
10. **Literal kinds are the only type information there is.** An int literal is
    kind 20 with a value; a float literal kind 21; nothing records that `1 + 2`
    is an int. Constant folding is yours.
11. **Word 4 (`d`) is uninitialised dead space** in every node (`parser.vel:13`
    names it; no writer exists; `new_node` zeroes it at `:100`). It is free for
    an interpreter to use, at the cost of meaning something different from the
    `0` it holds today.
12. **Counts and `nx` can disagree on error paths** (§3, §8): never size a loop
    by a count, always follow `nx` to `-1`.
13. **No position information beyond a statement line.** Word 5 is per
    statement, ignored by the dumper, and there is no column or end offset. Only
    float and string literals carry exact offsets and lengths (`:1135`, `:1145`),
    so run-time diagnostics can usually cite a line at best.
14. **The string representation is not canonical** (§5): the same value written
    two ways yields two different ASTs and neither is decoded; dedupe and
    `unescape` are yours.
15. **Name handles are process-global and capped at 8192**
    (`runtime/vela_runtime.h:431`–`:442`). A tree-walker that interns names for
    its own symbol tables shares that budget with the program's names and will
    panic when it runs out.
16. **Shapes the parser rejects, which no later stage will thus ever see**:
    `elif`, `pure` not followed by `def`, `parallel` not followed by `for`, an
    unannotated parameter, a missing return type, chained comparison, tuples, an
    empty array literal, a missing closing brace/`]`/`)`. What the later stages
    *will* see is the unchecked targets of gap 8 and the unresolved names of gaps
    6 and 7.
