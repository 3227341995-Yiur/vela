# ide-demo — a small project for trying the Vela plugin in IntelliJ IDEA

**English** | [简体中文](README.zh-CN.md)

A small, real project for trying the Vela plugin in IntelliJ IDEA.  Every file in it
is small, but every one is a real program, and one that has been **run and measured**:
each block of output below was copied byte for byte out of a real `vm.exe build` and a
real run on this machine, not written down as what it "should" be.

This directory sits **deliberately** inside the Vela checkout
(`C:\Users\lu\Downloads\vela\ide-demo\`), for the reason under "Why inside the
checkout" below.  It has nothing to do with `vela\tests\` and depends on nothing there.

---

## What is in here

| file | purpose |
|---|---|
| `hello.vel` | the smallest legal program, printing three lines.  Used to confirm that "it runs from inside IDEA" |
| `tour.vel` | the file that demonstrates the editing features: a struct + methods, functions with parameters, a function calling a function, one `mut` local, and one legal `parallel for` |
| `broken.vel` | **deliberately wrong**: an intentional type error, which the plugin draws as a diagnostic in the editor |
| `expected\hello.txt` | the exact stdout `hello.vel` must print (the real machine's own output, byte for byte) |
| `expected\tour.txt` | the exact stdout `tour.vel` must print (likewise) |
| `README.md` | this file |

After a run, the directory grows `hello.c` / `hello.exe` / `hello.obj` / `tour.c` /
`tour.exe` / `tour.obj`: those are compile products and are expected.  The rule
`vm.exe build` follows is "write the `.c` and the `.exe` beside the source file".

---

## Why inside the checkout

The plugin works without a stored compiler path because it finds the compiler by
**walking up from the project directory** (`VelaCompiler.locate`: starting at the
project directory, up to 6 levels, looking for
`<directory>\selfhost\build\vm.exe`).

One level up from `vela\ide-demo` is `vela\`, which holds
`selfhost\build\vm.exe`, so opening `vela\ide-demo` as an IDEA project lets the
plugin find the compiler with no configuration at all.  I measured that walk on this
machine (replaying the loop from the plugin's own source):

```
项目目录: C:\Users\lu\Downloads\vela\ide-demo

locate()   第 0 跳: C:\Users\lu\Downloads\vela\ide-demo\selfhost\build\vm.exe  -> 不存在
locate()   第 1 跳: C:\Users\lu\Downloads\vela\selfhost\build\vm.exe           -> 存在
           -> 编译器 = C:\Users\lu\Downloads\vela\selfhost\build\vm.exe（往上 1 层）

runtimeDir()  从编译器自己的目录往上最多 4 层找 <目录>\runtime\vela_runtime.h
           第 0 跳: ...\vela\selfhost\build\runtime\vela_runtime.h  -> 不存在
           第 1 跳: ...\vela\selfhost\runtime\vela_runtime.h        -> 不存在
           第 2 跳: ...\vela\runtime\vela_runtime.h                 -> 存在
           -> runtimeDir = C:\Users\lu\Downloads\vela\runtime
           -> velaRoot   = C:\Users\lu\Downloads\vela
```

(Both rules in `locate()` use forward slashes, `selfhost/build/vm.exe`; on Windows
the two spellings are equivalent.)

---

## How to open it and run a file

1. **Install the plugin** (skip if it is already installed): `Settings → Plugins →
   ⚙ → Install Plugin from Disk…`, choose
   `C:\Users\lu\Downloads\vela\idea-plugin\dist\vela-idea-plugin-0.1.1.zip`, and
   restart IDEA when prompted.
2. **Open the project**: `File → Open`, choose the **directory itself**,
   `C:\Users\lu\Downloads\vela\ide-demo` (not a file inside it) → confirm
   `Trust Project`.  IDEA opens it as a project, rather than "adding a directory to
   the current project".
3. **Wait for indexing**: on the first open, indexing runs in the bottom right corner
   for a few seconds; wait for it to finish before doing anything else.  Syntax
   highlighting, Structure and completion all depend on it.
4. **Compiler path**: leaving `Settings → Languages & Frameworks → Vela` blank is
   fine — the plugin finds `vm.exe` itself by the rule above.  (You can also type
   `C:\Users\lu\Downloads\vela\selfhost\build\vm.exe` by hand; the effect is the
   same.)
5. **Run** (any one of three, all the same action):
   * right-click `hello.vel` in the editor → **Run 'hello.vel'**;
   * click the **green triangle** in the gutter;
   * with the cursor in the file, press **Ctrl+Shift+F10**.
   The plugin creates a "Vela" run configuration by itself: `vm.exe build <file>`
   first, then the `.exe` it produced, with both steps' output in IDEA's Run console.
   The run configuration can also be saved, renamed and re-run from
   `Run → Edit Configurations…`.

---

## What each file demonstrates

### `hello.vel`
The smallest runnable program: `def main() -> None` plus three `print`s.  No arrays,
no loops.  It is there to show whether the whole Run path works.

### `tour.vel`
Everything worth trying, gathered into one file so it can be lined up at a glance:

| spot | feature demonstrated |
|---|---|
| `struct Point` + the two methods `manhattan` / `moved` | the struct and its methods appear in the Structure view; a call like `.manhattan()` has semantic highlighting |
| parameterised functions such as `def scale(v: int, factor: int)` | put the cursor inside `scale(` and press `Ctrl+P` for parameter info; hover (`Ctrl+Q`) for documentation |
| `def moved(self: Point, dx: int, dy: int) -> Point` | two parameters + a returned struct (`Point(x, y)` constructs it; construction is a "call") |
| `scale(...)` called inside `describe` | **Go to declaration has a target**: `Ctrl+Click` `scale` in `describe` → jumps to `def scale` |
| `mut n: int = 8` | a mutable local that collides with nothing |
| `mut a` / `mut out` / `parallel for` | one **legal** `parallel for` (`SPEC.md` §7): `out[i] = a[i] * 2`, where the written `out` is this function's `mut` local array, indexed by a single loop variable, and the read `a` is a **different** array |

`Ctrl+Click` is worth trying a few times; these have targets: `scale` inside
`describe`, `Point` inside `Point(3, -4)`, `manhattan` inside `p.manhattan()`,
`moved` inside `p.moved(...)`, and `x` inside `p.x` / `q.x`.

One class **deliberately** has no target: a bare read of a local name, such as `n` in
`range(0, n)` or `out` in `out[i]`, gets no jump target from the plugin
(`VelaGotoDeclaration.kt` says so explicitly: a local is resolved only in receiver
position like `p.x`; a name on its own is not guessed).  That is the design, not a
break.

### `broken.vel`
Its top says, in both Chinese and English, that the file is deliberately wrong.  It
demonstrates **real-time diagnostics as you type**, not running.

### `expected\`
The two `.txt` files are the **exact stdout** of the runnable files, copied byte for
byte out of real runs (not typed by hand), so they can be diffed directly without
trusting this README.  Note that they are Windows bytes as-is: the line endings are
**CRLF** and there is a trailing newline.

---

## The exact expected output

Every block below is real machine output, byte for byte (inside a code block the line
endings display as newlines; they are actually CRLF).

### `hello.vel` — running `hello.exe`

```
hello from Vela
3 * 14 = 42
goodbye
```

39 bytes, SHA256 `FD3FA73FF967EDE73152D341738CA70B3598C33177D49CB75D334DD7C6955567`
(the hash of `expected\hello.txt` is identical to it).
The interpreter, `vm.exe run ide-demo\hello.vel`, prints the **same** 39 bytes.

### `tour.vel` — running `tour.exe`

```
p = 3 -4
p.manhattan() = 7
q = 13 -3
describe(q) = 173
sum(out) = 72
out[7] = 16
```

87 bytes, SHA256 `3D766FE028E4B8790C7A8FE2B851DC3BD8EC46B2E7D44AB2516908929EA093FF`
(the hash of `expected\tour.txt` is identical to it).

The last two lines are that `parallel for`'s result: `sum(out) = 2×(1+2+…+8) = 72`,
`out[7] = 8×2 = 16`.

`tour.exe` is built **with OpenMP** (`vm.exe build` says exactly
`built ide-demo\tour.exe  (with OpenMP)`).  Its output is byte-identical at three
thread counts, and identical to the interpreter's too — one SHA256 for all four:

| how it was run | output SHA256 |
|---|---|
| `tour.exe` with `OMP_NUM_THREADS=1` | `3D766FE0…93FF` |
| `tour.exe` with `OMP_NUM_THREADS=8` | `3D766FE0…93FF` |
| `tour.exe` with `OMP_NUM_THREADS` unset (the default) | `3D766FE0…93FF` |
| `vm.exe run ide-demo\tour.vel` (the interpreter) | `3D766FE0…93FF` |

### `broken.vel` — the expected outcome is "refused"

Opening `broken.vel` in the editor should show a **red squiggle on line 11** (the
`if 1 {` line), whose hover text is:

```
'if' condition must be bool, got int
```

(This is the plugin running `vm.exe check` in the background: the `kind` is
`type error` and line 11 is the compiler's, not the plugin's guess.  The whole line is
marked, because the compiler gives a line number and no column.)

Pressing Run on that file (or Ctrl+Shift+F10) does **not** start the program.  The Run
console shows (the stderr of the build step, exit code **2**):

```
vela: type error: 'if' condition must be bool, got int
  at ide-demo\broken.vel:11

vela: panic: the program was refused

vela: panic: build: this compiler could not write the C for that file
```

The first two lines are the diagnostic; the two `panic:` lines afterwards are the
driver saying it has already refused, and the plugin does **not** treat them as extra
problems (`VelaCompiler.parse` skips `panic` explicitly), so the editor shows
**one** squiggle rather than three.  Changing `if 1` to `if True` (or `if n > 0`)
makes it run, which is exactly what it is for: a "change one thing and it works"
exercise.

---

## Debug is deliberately not offered

**The plugin has no debugger today, and it is not "clickable but useless": IDEA
simply does not offer you the Debug option.**  There are two reasons, both measured:

1. **On the plugin side**: the run configuration `VelaRunConfiguration` implements the
   platform interface `RunConfigurationWithSuppressedDefaultDebugAction`.  The
   platform's `DapProgramRunner.canRun` checks exactly that interface, so it will not
   take over a Vela run configuration, and no other runner will take it over either —
   Debug does not appear.  The plugin's own source states the reason plainly: a Debug
   that looks like it works is only a Run with a debugger label stuck on it, and the
   user finds out at the moment they most need a breakpoint; better to say there is
   none from the start.
2. **On the language side**: the compiler has a `debug` mode — the same interpreter,
   stopping, reading commands from a directory — and it works, but the IDEA plugin has
   no UI for it, so nothing offers it.  Measured on the compiler that was in the tree
   when this README's Chinese original was written (exit code **2**):

   ```
   > selfhost\build\vm.exe debug ide-demo\hello.vel
   usage: vela <lex|count|parse|nodes|emit-c|run|check|build> <file.vel>
     ...
   vela: panic: unknown mode            [退出码 2]
   ```

   That is the original record, from a compiler older than the one in the tree today,
   and it is kept rather than rewritten.  **Corrected 2026-09-21**, measured against the
   compiler built into the working tree that day — a mid-round build, deliberately not
   pinned here (the LLVM track owns the build slot this round and rebuilt
   `selfhost\build\vm.exe` several times, and the binary is ignored rather than
   committed, so a hash from it names a file nobody can go back to; the pin belongs to
   the round's frozen compiler): the mode is a working protocol debugger with the
   syntax `vela debug <file.vel> <cmddir> [arg...]`, and the usage line above is an
   older one — it does **not** list `debug` or `build-llvm`, while today's usage line
   does list both, and ends with `emit-llvm` and `build-llvm`.  Invoked the way the
   original did above, the current compiler exits 2 with the usage block **and**
   `vela: panic: debug needs a command directory: vela debug FILE <cmddir>` on stderr;
   given a command directory it exits 0, prints `ready` on stderr, and waits for
   `cmd.NNN` files, running the program to completion when none are supplied.  So the
   accurate statement about this repository is that **the compiler has debug mode and
   the plugin does not offer it** — not that the mode is missing.

Conclusion: **breakpoints, stepping and frames do not exist yet.**  The means of
"debugging" available now are printing — `print` / `emit_int` and the like — plus the
editor's real-time diagnostics and `vm.exe check`'s verdict.

---

## The one known trap: the compiler's include path

`vm.exe build`'s include directory defaults to the **relative** path `runtime`, so it
requires the **current working directory to be the repository root**.  Measured
(running from another directory, even with the source file given as an absolute path):

```
> cd C:\Users\lu\Downloads\_demo
> C:\Users\lu\Downloads\vela\selfhost\build\vm.exe build C:\...\ide-demo\hello.vel
hello.c
...\ide-demo\hello.c(5): fatal error C1083: 无法打开包括文件: "vela_runtime.h": No such file or directory
vela: build: the C compiler refused ...
                                                   [退出码 2]
```

**Run inside IDEA is unaffected**: the plugin sets `vm.exe`'s working directory to the
vela root (`VelaCompiler.velaRoot`), and additionally passes the runtime directory to
`build` as a **4th argument**.  I measured that 4th-argument route separately too —
running from another directory, supplying the runtime directory is all it takes:

```
> cd C:\Users\lu\Downloads\_demo
> C:\...\vm.exe build C:\...\ide-demo\hello.vel C:\Users\lu\Downloads\vela\runtime
built C:\Users\lu\Downloads\vela\ide-demo\hello.exe      [退出码 0]
```

(One thing measured in passing: the `VELA_RUNTIME` environment variable does **not**
work in this `vm.exe` — it is in the source and not in the binary — so when running by
hand use the 4th argument, or just `cd` to the root first.)

If you want to replay this by hand at the command line, **`cd
C:\Users\lu\Downloads\vela` first**:

```powershell
cd C:\Users\lu\Downloads\vela
.\selfhost\build\vm.exe build ide-demo\hello.vel      # -> built ide-demo\hello.exe
.\ide-demo\hello.exe                                  # -> hello from Vela / 3 * 14 = 42 / goodbye
.\selfhost\build\vm.exe build ide-demo\tour.vel       # -> built ide-demo\tour.exe  (with OpenMP)
.\ide-demo\tour.exe
.\selfhost\build\vm.exe run ide-demo\tour.vel         # 解释器，输出应完全相同
.\selfhost\build\vm.exe check ide-demo\broken.vel     # -> 上面那条诊断，退出码 2
```

---

## How these numbers were measured

**This English file translates the Chinese `README.zh-CN.md`, which came first.**  The
measurements and the phrasing belong to that original; this file is a faithful
translation of it, kept in the repository's bilingual shape so the two can be read side
by side.  Because the Chinese came first, it is the original record and this file is the
source *by the repository's convention only*: `README.zh-CN.md`'s provenance header
points at this file, so that `tools\docs-zh-check.ps1` still reports the pair and says
when the two drift apart — and a change to either side should update that header.  Where
the compiler's banner or mode list is quoted, it is quoted as it was at the time of
measurement, and not re-checked against today's binary.

* Compiler: `C:\Users\lu\Downloads\vela\selfhost\build\vm.exe`, 508928 bytes, SHA256
  `D35B330E3A72D00AFA4E84FBEB4F91A31F77971674E017FEA417D184BB990D6`.
* Every `build` was run in the repository root, with the source written as
  `ide-demo\<name>.vel`; `hello.exe` and `tour.exe` both exited **0**, and
  `check broken.vel` exited **2**.
* `expected\*.txt` were captured as **raw stdout bytes** by shell redirection, then
  copied into `expected\` unchanged and confirmed identical by comparing SHA256 (not
  retyped from the screen).
* All four `tour` outputs (interpreter, default, 1 thread, 8 threads) share one SHA256,
  `3D766FE028E4B8790C7A8FE2B851DC3BD8EC46B2E7D44AB2516908929EA093FF`.
* The compiler's upward search (`locate()` / `runtimeDir()`) was measured by replaying
  the loop in `idea-plugin\src\main\kotlin\dev\vela\plugin\VelaCompiler.kt` exactly;
  the output is in "Why inside the checkout" above.

One honest boundary: what is proved here is that the programs really do compile, really
do run, and print exactly this — and how the plugin is supposed to find the compiler
and the runtime on this machine.  What happens **inside IDEA's UI** (whether a menu
item appears, which line a squiggle is drawn on, a completion popup) cannot be proved in
a headless environment, and the plugin's own README says the same.
