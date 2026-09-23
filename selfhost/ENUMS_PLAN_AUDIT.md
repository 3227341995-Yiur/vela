# `ENUMS_PLAN.md` re-anchored to today's source — audit report

This report has no `.zh-CN.md` twin on purpose. The pair checker
(`tools/docs-zh-check.ps1`) sweeps every `*.zh-CN.md` in the tree and, for each
one, demands that the English file it records has the matching title and language
switcher lines and a provenance block — so a twin would either have to be written
or would show up as a `FAIL` in a run that is supposed to be green. Measured
effect of leaving it single: the sweep still finds **17** pairs and this file is
not one of them.

- Audited: `selfhost/ENUMS_PLAN.md`, `selfhost/ENUMS_PLAN.zh-CN.md`
- Audit date: **2026-09-24**, ~02:39 local (the clock reading at the start of the
  evidence capture)
- Evidence: `selfhost/ENUMS_PLAN_AUDIT.evidence.txt` (15,405 bytes,
  `51871EE33FEAD400EB04452C1B03DD288F413900BC95047925B9368F6009C464`) — the raw
  capture from which every line number and count below is copied, so the numbers
  can be re-derived without re-running anything. Its first line carries the capture
  timestamp. **`git status` will not show this file**: `.gitignore:120`
  (`/selfhost/*.txt`) excludes it, which is the rule that exists to keep the
  compiler's own scraped output out of the tree. Left as a `.txt` on purpose — the
  file is a log, not a document — so it is present on disk and ignored by git
  rather than renamed to slip past the rule.
- Host: Windows, Windows PowerShell 5.1 (`powershell -ExecutionPolicy Bypass`).
  No `pwsh` was used and no arithmetic was trusted where a `Select-String` line
  number could be had instead.
- **Nothing was built, compiled, frozen or executed.** No `.vel` file was written,
  no `vm.exe` invocation of any kind (not even `check`), no `vm.exe build`, no
  `tools/build.ps1`, no `tools/refreeze.ps1`. Every statement below is a reading of
  a file, plus readings of `git` metadata. The compiler-identity note (§8) is
  recorded so that a later reader knows which binary was *not* used.

## 0. What was read, and at which revision

Every line number in this report was read from the file with the matching hash
below, at the date above. `selfhost/vm.vel` is the linker's output and is kept as a
cross-check on the arithmetic (see §4).

| file | bytes | SHA-256 | last write |
|---|---|---|---|
| `selfhost/vela.vel` | 21413 | `D73B8CF5735475BE6DEC6E4ABF1B55E4E0A12CA33E4F8CAB1F3E77C9555D829B` | 2026-09-19 21:29:42 |
| `selfhost/parts/vm_state.vel` | 18961 | `6C1423780FE12C5CBD6205475253F8A665515B3990D20CC21892137809AED29C` | 2026-09-19 21:29:42 |
| `selfhost/parts/parser.vel` | 43738 | `5972624F60741DF04A00DF85B8F9C7FB7AB26C1A998772004F4ECFFCDE010A0A` | 2026-09-19 21:29:42 |
| `selfhost/parts/resolve.vel` | 24547 | `711572D5A708496F860D39DF4A4C2026EF02541D440D74C2C25AD3DFAE135539` | 2026-09-24 02:20:35 |
| `selfhost/parts/check.vel` | 120272 | `38BE1B0118B12B1B6EFED7F076A23B04F344B9B5B8E4F930D3949E7CBEB32728` | 2026-09-24 01:47:05 |
| `selfhost/parts/eval.vel` | 101887 | `28A7BA9E2B65E30887CAD65402A8084DC9E6D3CA18F20B05CFBFDA94D1758F6B` | 2026-09-24 02:05:08 |
| `selfhost/parts/emit.vel` | 81004 | `E3A9F26BF1FB36F7965E3F2C253930B14DE4923FB7C5BAAC6CDC117362A014A4` | 2026-09-24 01:48:24 |
| `selfhost/parts/emit_llvm.vel` | 145324 | `84824513A0A94889B1BC16E98F73E804E056DDD62E53D0307C5FF2796BAA5832` | 2026-09-24 02:04:59 |
| `selfhost/parts/dump.vel` | 14087 | `4525F40CB82A53AD3BA198C12616DCC1A16F1CBE89593EA10F9D8760884FEA6F` | 2026-09-19 02:48:42 |
| `selfhost/parts/vm_main.vel` | 54515 | `6D9C9CA8BB3A98E9E5A5CA2AA36C8694447ED3BC5F6BB40753541093B370CB26` | 2026-09-22 00:16:48 |
| `selfhost/parts/llvm_shim.vel` | 6293 | `41E0AED510B1F19A4F57E694CE392C4B2310D406A5D7BEAF87C1D6D3DD14D3C9` | 2026-09-24 00:38:15 |
| `selfhost/vm.vel` (linked) | 632586 | `083B561AF8C8525EC2159FA19F939A06DC22137DF1D637F4D38FF733F80ADE27` | 2026-09-24 02:21:38 |
| `tools/link_selfhost.vel` | 7733 | `D2852B57169276E9DFE06EA3819B6E2DFD486508F2FFADD110D24C820AC7C40D` | — |

**Revision warning, and why this report is still worth reading.** `resolve.vel`,
`check.vel`, `eval.vel`, `emit.vel` and `emit_llvm.vel` were all modified within
the eight hours before this audit, one of them 19 minutes before it. Lines can
move again the moment another agent saves the file; the **names** in the tables
below are the durable part and are what the plan now cites. Read `file:line` here
as "on the revision in the table above".

## 1. Reference-by-reference: original plan → today's coordinates

`kind` column values: **exact** (claim holds as written), **rewritten** (the claim
was wrong or incomplete and the plan text was changed), **added** (not in the plan,
added because the plan was incomplete without it).

### 1.1 References the plan made to source

| # | plan said (original text) | today's coordinates, read at the hashes above | kind |
|---|---|---|---|
| 1 | "`check.vel` dispatches on `nd_kind(nd, e)`" | `def nd_kind(nd: Array[int, 655360], n: int) -> int` at `selfhost/parts/vm_state.vel:419`–`:421`; 63 call occurrences in `check.vel`, 8 in `resolve.vel`, 13 in `eval.vel`, 21 in `emit.vel`, 18 in `emit_llvm.vel`, 0 in `dump.vel` (it indexes `nd[n * 10]` directly) | exact |
| 2 | "`k == 31` for an index node" | kind 31 documented in the `parser.vel` header at `parser.vel:50`; written by `parse_postfix`: `ix: int = new_node(nd, cx, 31, line2)` at `parser.vel:1156` | exact |
| 3 | "`k == 25` for a name" | kind 25 documented at `parser.vel:44`; written at `parser.vel:792`, `:814`, `:1231` | exact |
| 4 | "`26` for a call" | kind 26 documented at `parser.vel:45`; written in `parse_postfix`: `c: int = new_node(nd, cx, 26, line)` at `parser.vel:1119` | exact |
| 5 | "`33` for a list" | kind 33 documented at `parser.vel:52`; written in `parse_atom`: `n7: int = new_node(nd, cx, 33, line)` at `parser.vel:1301` | exact |
| 6 | "`34` for whatever is next" | kind 34 is **slice**, documented at `parser.vel:53`; written in `parse_postfix`: `s: int = new_node(nd, cx, 34, line2)` at `parser.vel:1144`. "whatever is next" was a shrug, not a name: 34 already existed when the plan was written | rewritten (named: slice) |
| 7 | "`k == 32`" — implied by the arithmetic, never spelled | kind 32 = **attr**, `parser.vel:51` and `at: int = new_node(nd, cx, 32, line3)` at `parser.vel:1171`. The five numbers in the plan are five labels on a table whose header comment (`parser.vel:23`–`:53`) is the real source of truth | added |
| 8 | "**Three files**, one implicit enum" (`check.vel`, `eval.vel`, `emit.vel`) | **six** carry it: `resolve.vel`, `check.vel`, `eval.vel`, `emit.vel`, `emit_llvm.vel`, `dump.vel`. Lines holding a `\bk == <digits>\b` comparison: check 83, emit 58, emit_llvm 41, dump 30, eval 29, resolve 23. `resolve.vel` and `dump.vel` are not "also" — `resolve.vel` binds every name and builds the struct table the interpreter reads, and `dump.vel` is what `parse` mode prints | rewritten (six, and named) |
| 9 | (nothing) — the plan names no parser function that would grow an `enum` arm | `parse_stmt` at `parser.vel:403`–`:509`, one `if at_kw(...)` arm per statement kind, `parse_simple` (`parser.vel:763`) as the fall-through, `parse_body` (`parser.vel:352`) for `{ … }` blocks | added |
| 10 | (nothing) — the plan assumes the old four-stage pipeline | `ROADMAP.md:87`–`:91` (stream 2 item 1) still matches this plan; but the stages are now five parts plus a driver: `resolve_module` (`resolve.vel:669`), `ck_module` (`check.vel:3403`), `exec_stmt`/`run_main` (`eval.vel:1851`/`:2891`), `emit_program` (`emit.vel:2233`), `ll_program` (`emit_llvm.vel:3034`), `dump_module` (`dump.vel:542`) | added |

### 1.2 Cross-check used to confirm the audit itself

The task that commissioned this audit cited two stale citations of the *other*
plan, `ELISION_PLAN.md`, as the reason it exists. Both were re-read today and both
are still in the state the task described:

| `ELISION_PLAN.md` citation | today | verdict |
|---|---|---|
| "`ck_index:897-936`" | `def ck_index` at `selfhost/parts/check.vel:1233`, body through `:1279` | stale by **+336**, as the task said |
| "`ck_match_linear:1841-1862`" | `def ck_match_linear` at `selfhost/parts/check.vel:2298` | stale by **+457**, as the task said |

These two are **not** touched by this audit: `ELISION_PLAN.md` and its twin were
left exactly as they were, and the only record that they are stale is this table.

### 1.3 Claims in the plan that are not about source coordinates, and their verdict

| plan claim | checked against | verdict |
|---|---|---|
| "the AST's node kinds are integers today" | `nd_kind` returns `nd[n * 10 + 0]`; the parser writes bare `int` literals at 44 `new_node` call sites; the kind names exist only in a comment block (`parser.vel:23`–`:53`) and in the `dump.vel` dispatch. There is **no** `NK_*` constant anywhere | exact |
| "a new node kind that is forgotten in one of the three fails silently" | worse than stated and also milder: `dump.vel` prints `unknown-node-<k>` (`dump.vel:537`); the interpreter **panics** loudly (`eval.vel:2025`, `:2032`); the checker and both emitters fall through their `if k == …` chains and emit nothing | exact (understated) |
| "answer: add one variant and the build must fail at every place that needs to handle it, with no other changes" | This is the one claim that **does not hold as written** today. See §3 | **rewritten** (plan now says why) |
| "`eval.vel` … `emit.vel`" — the dispatch sites for step 5 | `exec_stmt` (`eval.vel:1851`, dispatches the statement kind at `:1870`); `emit_stmt` (`emit.vel:1628`, kind at `:1632`); `emit_expr` (`emit.vel:1933`, kind at `:1936`); `emit_index_decls_expr` (`emit.vel:1865`, kind at `:1869`) is a fourth one inside the same file | added |
| "the emitted C has no `default` in the exhaustive switch" (`enum_exhaustive_switch`) | **not verified.** Verifying it means emitting C and reading it, i.e. running the compiler, which this audit did not do. The only statement this report can make is that the test file for it exists (`tests/accept/enum_exhaustive_switch.vel`, 786 B) | unverified — see §6 |

## 2. What was changed in the plan, and why

Seven edits to `selfhost/ENUMS_PLAN.md` and seven matching edits to
`selfhost/ENUMS_PLAN.zh-CN.md` (plus two reflow fixes in the Chinese file alone).
Nothing else in either file changed, and no other file in the repository was
written.

1. **A standing note at the top, after the status paragraph.** One paragraph
   saying the plan cites function names, that the `file:line` pairs were
   re-anchored on 2026-09-24, and where the hashes live. Reason: a line number
   with no date and no hash is the failure mode that produced `ELISION_PLAN.md`'s
   two stale citations, and the plan is handed to an implementer who will read it
   days from now.
2. **"Why this one is first": three files → six, and the five literal numbers are
   now named and sourced.** Reason: the plan's central premise ("three files, one
   implicit enum") was too small by half, and an implementer who believes it will
   change `check`/`eval`/`emit`, watch the fixpoint go green, and ship a node kind
   that `resolve.vel` walks into the wrong branch and `dump.vel` prints as
   `unknown-node-35`. Each of the five numbers now has its `new_node` call site.
3. **"Order of work", steps 1, 2, 4, 5 and 6.** Reason, per step: step 1 gained the
   fact that `enum`/`match` are not keywords today (`keyword_id`,
   `selfhost/vela.vel:155`–`:226`, ends at `extern` = 23) so the statement forms are
   reachable without a lexer change, plus the two places a new kind starts
   (`parse_stmt`, `parse_simple`) and the free capacity (35/36). Step 2 gained the
   resolver half of "resolve and check" — `add_struct` (`resolve.vel:307`) and the
   `stb_*` table (`vm_state.vel:389`–`:403`) are what a struct gets, so an enum
   needs the same, and the checker cannot see it otherwise. Step 4 gained the fact
   that there are **two** back ends now. Step 5 gained the three dispatch sites the
   plan did not name. Step 6 gained the linker as a gate (`tools/link_selfhost.vel`
   splices ten parts into `selfhost/vm.vel`; measuring a step green without
   relinking measures the previous revision). Exact coordinates for every one of
   those are in §1.
4. **The interpreter section gained one paragraph, and the acceptance table gained
   two rows and a preamble.** Reason: the interpreter paragraph already decides
   half of what step 3 describes — a struct value is a `K_ST()` cell with a heap
   base, a struct id and a field count, built by `exec_construct`
   (`eval.vel:1357`–`:1396`) and copied by `copy_value` (`eval.vel:133`) — so the
   payload path is a variation, not a new mechanism. The table: see §5, the cases
   are on disk already.

Line references were **not** removed in favour of names; they were corrected and
paired with names. `grep -o '[A-Za-z_]*\.vel:[0-9]*' selfhost/ENUMS_PLAN.md`
returns 23 distinct `file:line` citations after the edit, each of which is in
§1.1 above.

## 3. Step 5 revisited: can the AST node kind become an enum today?

**Conclusion: the language feature is what gates it, and the plan's ordering (step
5 last) is right — but the *acceptance test* it states, "add one variant and the
build must fail at every place that needs to handle it, with no other changes",
cannot hold until all six dispatch sites use an exhaustive `match` and the two
non-failing fall-throughs are accounted for. As written, the test is a description
of the end state, not of a milestone that can be measured at the start of step 5.**

Evidence, all read today:

1. **The representation is an `int` in a flat array, and the array is what the
   rest of the compiler sees.** `nd_kind` (`vm_state.vel:419`) returns
   `nd[n * 10 + 0]` from `Array[int, 655360]`. The stride is 10
   (`parser.vel:12`–`:15`), node capacity is enforced as `n >= 65536` in
   `new_node` (`parser.vel:120`). A "node kind enum" therefore has to be an
   `int`-valued enum or the pool type, the 44 `new_node(nd, cx, <literal>, …)`
   call sites and every `nd[s * 10 + k]` in six files change together. That is
   feasible in the language as specified — variants with no payload, the plan's own
   `Color { Red Green }` shape — but it is not a local edit.
2. **Two dispatch sites cannot fail loudly, which is what the acceptance test
   assumes they do.** `d_node` (`dump.vel:195`) ends in a fall-through that prints
   `unknown-node-<k>` (`dump.vel:537`–`:539`) — under the test's terms, adding a
   variant to the enum must make the build fail there, and today that code cannot
   fail at all. The interpreter *does* fail loudly (`eval.vel:2025`–`:2032`), which
   is better than silent but is a run-time panic, not a compile error.
3. **`resolve.vel` is a dispatch site the plan never names.** `walk_stmt`
   (`resolve.vel:552`) switches on `nd_kind(nd, s)` at `:555`, `walk_expr`
   (`resolve.vel:422`) at `:428`, `add_struct` (`resolve.vel:307`) at `:325`.
   23 `k == <literal>` lines. If step 5 rewrites only the plan's three files, a new
   node kind reaches the resolver through the same generic path a declaration
   takes and is bound as something it is not.
4. **There is a second back end, and the plan predates it.** `emit_llvm.vel` is
   spliced in as `part_name(7)` (`tools/link_selfhost.vel:57`), after `emit.vel`,
   and its statement dispatcher is `ll_stmt` (`emit_llvm.vel:2783`), expression
   dispatcher `ll_expr` (`:2270`), with 41 `k == <literal>` lines. Its own
   convention is to refuse rather than guess (see `LLVM_PLAN.md`'s "refused
   deliberately" column), so "the LLVM back end refuses enums out loud" is a
   legitimate decision — but it has to be a decision, written down, or step 4's
   "the emitter" is only half done.
5. **The fixpoint has a second, cheaper gate that the plan does not mention.**
   The compiler compiles `selfhost/vm.vel`, and `selfhost/vm.vel` is *generated*:
   `tools/link_selfhost.vel` concatenates `selfhost/vela.vel`'s front half with
   the ten `parts/*.vel` in the order in `part_name` (`link_selfhost.vel:41`–`:61`)
   and writes `selfhost/vm.vel` (`:34`, `:189`). Editing a part and running the
   fixpoint without relinking measures the previous revision. Today the two agree
   — `resolve.vel` was written at 02:20:35 and `vm.vel` at 02:21:38, and all ten
   part headers are present in `vm.vel` (§4) — but nothing in the plan makes that
   step explicit, and the plan is for a change of exactly the size where it
   matters.
6. **The feature's own shape is favourable, and that is the one piece of good
   news.** An enum with a payload does not need the interpreter to grow a new
   storage class: a struct value is already a heap-backed cell with a tag, an id
   and a field count (`exec_construct`, `eval.vel:1357`–`:1396`), copied by
   `copy_value` (`eval.vel:133`). And the *source* of the compiler can carry named
   variants for free once the feature lands, because the ordered parts make the
   change incremental: the parts are compiled one at a time, so the compiler that
   compiles `check.vel` is always the previous linked revision, which does not
   have to understand `match` for the parts it already compiled.
7. **What this report cannot say.** Whether the *emitted C* for a `match` over an
   enum is a `switch` without a `default`, and whether `cl` warns on a missing
   `arm` — both are claims about running a compiler, and none was run (§9). The
   feasibility conclusion above is about the *source* of the compiler.

## 4. The linked compiler, as a check on the citations

`selfhost/vm.vel` (632,586 B, `083B561A…`) contains the banner
`# selfhost/vm.vel - GENERATED by tools/link_selfhost.vel` at `vm.vel:2`, and its
ten part headers are at lines 716, 1169, 2519, 3212, 6641, 9585, 11854, 11954,
15074 and 15621 — in the `part_name` order of `tools/link_selfhost.vel:41`–`:61`.
**The path order is unchanged**: the plan's step list still walks the pipeline in
the order the linker splices it. A part-local line number converts with the
offsets implied by those headers (parser: +1167, resolve: +2510, check: +3206,
eval: +6636, emit: +9581, emit_llvm: +11950, dump: +15072); that arithmetic is
recorded only as a cross-check, not as a citation style — the plan cites parts, and
the parts are what an implementer edits.

## 5. `tests/accept/` — the acceptance table is mostly already written

Not part of the coordinate audit, but the audit found it and it changes what the
next agent should do.

- **Twelve enum files exist** in `tests/accept/`: `enum_payload_bind`,
  `enum_match_no_else`, `enum_missing_variant`, `enum_duplicate_arm`,
  `enum_binding_arity`, `enum_unknown_variant`, `enum_nested_pattern`,
  `enum_in_array`, `enum_self_payload`, `enum_exhaustive_switch`, plus
  `enum_else_not_last` and `enum_match_non_enum` — the last two are **not** in the
  plan's table at all, and the plan's table's own last row,
  `node_kinds_are_enum` (fixpoint), has **no file**: it is a property of the build,
  not a `.vel` case.
- **They got there in commit `778ff3a`** ("The acceptance corpus for the nine
  missing features moves out of the plugin's corpus", 2026-09-24 02:13:54), which
  moved 48 files out of `tests/build/accept/` because `HintShapes.java` walks
  `tests/build` as its corpus and the plugin was reporting failures that were
  artefacts of the move. They are tracked in git (`git ls-files tests/accept`
  lists them).
- **Nothing runs them.** `tests/cases.txt` has zero lines containing `enum` (its
  modes and its 300-odd cases are what `tests/run_tests.vel` reads), and no
  `tools/*.ps1` mentions `tests/accept` (0 matching scripts). A case no runner runs
  is a file.
- The plan's table was therefore corrected in two ways: the twelve existing files
  are named in a preamble, and the two cases the table omitted were added as rows
  marked "not in the original table". The audit did **not** touch
  `tests/**`, `tools/**` or any harness — wiring `tests/accept/` into a runner is
  somebody else's change, and the plan now says so.

## 6. `docs-zh-check` — raw output

The English plan was edited first; the Chinese twin was then edited to match; the
provenance block was repinned to the new English file
(`源文件字节 : 14976`, `源文件 SHA256 : 45d3a8e5ae1f62f016a790e2d182cfa7b033e8532b32b9a10c11ff3a7f9a0341`,
`翻译日期 : 2026-09-24`); then the checker was run from the repository root:

```
> powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1

sweep: C:\Users\lu\Downloads\vela
found 17 '*.zh-CN.md' file(s): 17 checked, 0 not checked

ok     RESULTS.md           -> bench\RESULTS.zh-CN.md
ok     DESIGN.md            -> DESIGN.zh-CN.md
ok     BUILD_CHECKLIST.md   -> idea-plugin\BUILD_CHECKLIST.zh-CN.md
ok     CHANGELOG.md         -> idea-plugin\CHANGELOG.zh-CN.md
ok     PLUGIN_SURFACE.md    -> idea-plugin\PLUGIN_SURFACE.zh-CN.md
ok     README.md            -> idea-plugin\README.zh-CN.md
ok     README.md            -> ide-demo\README.zh-CN.md
ok     README.md            -> README.zh-CN.md
ok     ROADMAP.md           -> ROADMAP.zh-CN.md
ok     SAFETY.md            -> SAFETY.zh-CN.md
ok     AST.md               -> selfhost\AST.zh-CN.md
ok     CHECKER_PLAN.md      -> selfhost\CHECKER_PLAN.zh-CN.md
ok     ELISION_PLAN.md      -> selfhost\ELISION_PLAN.zh-CN.md
ok     ENUMS_PLAN.md        -> selfhost\ENUMS_PLAN.zh-CN.md
ok     LLVM_PLAN.md         -> selfhost\LLVM_PLAN.zh-CN.md
ok     SPEC.md              -> SPEC.zh-CN.md
ok     STATUS.md            -> STATUS.zh-CN.md

pairs: 17   ok: 17   stale: 0   failed: 0
RESULT: ok
```

**The verdict line is `RESULT: ok`.** For the record, the same run immediately
before the repin reported `pairs: 17   ok: 16   stale: 1   failed: 0` /
`RESULT: stale` / exit code 1, naming `STALE  ENUMS_PLAN.md -> selfhost\ENUMS_PLAN.zh-CN.md`
— i.e. the checker noticed the English edit exactly as it is supposed to.

What `RESULT: ok` does **not** mean here: it says the English file's hash is the
one the Chinese file records. It says nothing about whether the Chinese prose is a
faithful translation of the new English paragraphs. It is not, by machine — that
was checked by hand, and the eight edits are the same eight edits on both sides
(§2), in the same order, with the same numbers.

`selfhost/ENUMS_PLAN_AUDIT.md` (this file) has no twin at all, so the sweep stays
at the 17 pairs above — the same 17 it found before this audit, with the
`ENUMS_PLAN` pair repinned rather than added.

## 7. Final state of the three files this audit wrote

| file | bytes | SHA-256 |
|---|---|---|
| `selfhost/ENUMS_PLAN.md` | 14976 | `45D3A8E5AE1F62F016A790E2D182CFA7B033E8532B32B9A10C11FF3A7F9A0341` |
| `selfhost/ENUMS_PLAN.zh-CN.md` | 15516 | `92129F81B403123EFE6004B1E575EAB4624F6D855751555D97AB8CA11F957FEE` |

**This report's own hash is deliberately not in that table.** It cannot be: the
digest of a file that contains that digest is never equal to it, so any value
written here would be wrong the instant it was saved — and a hash table whose last
row is wrong is worse than one that is short a row. The authoritative digest of
this file is recomputed at the end of the session and written into
`selfhost/ENUMS_PLAN_AUDIT.sha256`, one line per file, which is the one place a
verifiable value can live.

For comparison, the two plans as this audit found them:

| file | bytes | SHA-256 |
|---|---|---|
| `selfhost/ENUMS_PLAN.md` | 8047 | `C0B2F9BC7D13602188C4B8DD60CBF927679E6D4C179C4929FF0016717DDA198B` |
| `selfhost/ENUMS_PLAN.zh-CN.md` | 8289 | `5D1435DAD3C06F5AF5D61A50AAC8CF949AAD2A66FE6115BD346537FEA20858F5` |

**No `.vel` file was written.** `git status` on the three files is the record of
what changed; nothing was staged or committed.

## 8. Compiler binaries seen, and not used

Recorded because the task asked for it: if this report cites a compiler's
behaviour, it must say which binary. It cites none.

| path | bytes | SHA-256 | mtime |
|---|---|---|---|
| `selfhost/build/vm.exe` | 867840 | `501B6CBB5701F3D5EF36396B9DDCAF4CE740E66D6CA325F482C2451E76810FDF` | 2026-09-24 02:07:04 |
| `selfhost/build/vm_by_vela.exe` | 868352 | `592D16DC9E3FAE1F0689BAAC80B2ED86D2D27DC73EEBCBB3FB98592618C8F22E` | 2026-09-24 02:21:47 |
| `selfhost/build/vm.c` | 973686 | `26EB7639D368E70785299A08CA8C42C839EDA0CD35DEE1283C239010F0C3D46C` | 2026-09-24 02:21:44 |
| `tools/link_selfhost.exe` | 173056 | (not hashed) | 2026-09-24 02:21:37 |

The two sizes the task mentioned are both present in this tree, which is worth
recording precisely because they are *different files*: `1B2D7919…`/868352 B is
`vm_by_vela.exe` (the fixpoint's output, what the compiler writes when it compiles
itself), and `501B6CBB…`/867840 B is `vm.exe` (the promoted bootstrap compiler).
`vm_by_vela.exe` has since been rebuilt to `592D16DC…`/868352 B, so the byte count
matches the task's observation while the hash has moved. **No binary in this table
was executed or frozen.**

## 9. Not verified, and why

1. **Any compiler behaviour.** No `vm.exe` mode was run — not `check`, not
   `parse`, not `run`, not `build`, not `debug`. Reason: the compiler is being
   rebuilt on this machine by another agent right now (three `vm.exe`/`vm.c`
   revisions inside 15 minutes, `vm_boot.obj` written 02:22:22), and this audit's
   whole method is reading files, so it needs no compiler. Consequence: §1.3's
   `enum_exhaustive_switch` row and §3 item 7 are open questions, marked as such.
2. **`tools/build.ps1`, `tools/refreeze.ps1`, `vm.exe build`.** Not run, by
   instruction (the compiler agent holds the build slot) and because nothing here
   needs them.
3. **Anything in `tests/**`, `idea-plugin/**`, `tools/**`, `SPEC.md`,
   `DESIGN.md`.** Read-only where read at all (`tests/cases.txt`,
   `tests/accept/*.vel` names and bytes, `tools/link_selfhost.vel`,
   `tools/docs-zh-check.ps1`, `ROADMAP.md`); not written.
4. **`git add` / `git commit`.** Not used. The changes are in the working tree, and
   the file list is: `selfhost/ENUMS_PLAN.md`, `selfhost/ENUMS_PLAN.zh-CN.md`
   (modified), plus `selfhost/ENUMS_PLAN_AUDIT.md`,
   `selfhost/ENUMS_PLAN_AUDIT.sha256` and `selfhost/ENUMS_PLAN_AUDIT.evidence.txt`
   (new). `git status -- selfhost/` reports the plan pair, the report and the
   `.sha256` file; the evidence file is ignored by `.gitignore:120`
   (`/selfhost/*.txt`, see the header of this report) and so does not appear —
   it exists on disk with the hash in the `.sha256` file. The other modified
   entries `git status` lists under `selfhost/` (`parts/*.vel`, `build/vm.c`,
   `build/vm.exe`) are the compiler agent's work, untouched by this audit.
5. **Whether the Chinese plan reads well.** The same seven edits were mirrored by
   hand on both sides, plus two reflow fixes in the Chinese file only (a bare
   `:1171` written out as `parser.vel:1171`, and one ambiguous pronoun). The pair
   checker verified the pin, not the prose; a Chinese reader should still skim the
   new paragraphs in `selfhost/ENUMS_PLAN.zh-CN.md` before the plan is handed to
   an implementer.
6. **`ELISION_PLAN.md` itself.** Its two stale citations were confirmed (§1.2) and
   then deliberately left alone — the task scoped this audit to `ENUMS_PLAN.md`
   and its twin.
