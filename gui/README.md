# gui — a native desktop toolkit in Vela

**English** | [简体中文](README.zh-CN.md)

## What it is

`gui/app.vel` is a **single-file** native desktop program: a window, a frame loop, a
theme and a set of immediate-mode widgets, all written in Vela.  There is no C file
underneath, no external runtime and no shim — only `extern c` calls into Windows' own
user32 / gdi32 / winmm.

The change that made it possible is `2ef31e3`: once `extern c` could carry a `str`
parameter, Win32 became reachable at all (every entry point that takes text takes a
`char *`).

## Build and run

```bat
selfhost\build\vm.exe build gui\app.vel      ::  -> gui\app.exe
gui\app.exe                                  ::  runs until the window closes or Esc
gui\app.exe 200                              ::  draws 200 frames, prints its own timings
```

`vm.exe run` **cannot** run it: the interpreter has no foreign call.  `vm.exe build-c`
can (same C back end).

## Where the line is

| layer | what it is |
|---|---|
| window | `CreateWindowExA("STATIC", …, SS_OWNERDRAW)` + `ShowWindow` |
| messages | `PeekMessageA` drains the queue (**never dispatches**); `WM_CLOSE` is read straight out of it |
| input | `GetMessagePos` (mouse), `GetAsyncKeyState` (keys) |
| drawing | a memory DC with a compatible bitmap, `PatBlt`/`RoundRect`/`TextOutA`, one `BitBlt` a frame |
| timing | `GetTickCount64`; `timeBeginPeriod(1)` makes `Sleep(1)` an actual millisecond |
| widgets | `label` / `button` / `card` / `meter` / sidebar item, immediate mode |

**Why there is no callback**: Vela 0.1 has no function types, and a Win32 window class
wants a WNDPROC.  So the window is the system's own `STATIC` with `SS_OWNERDRAW`: it
paints nothing itself, and the messages it does not handle — `WM_CLOSE` included — go
to `DefWindowProc`, where the destroy is already written.

## Measured

| | value | how |
|---|---|---|
| startup, process to first blit | **15–16 ms** | the program's own `GetTickCount64` deltas |
| frame time | **< 1 ms** | same, `spent / frames` |
| fps | **8333–13333 (uncapped)** | same, `frames * 1000 / spent` |
| resident memory | **26 MB** (1.9 MB / 0.5 MB private before the first frame) | sampled outside with `Get-Process WorkingSet64` |
| a frame of the toolkit's drawing | **0.31 ms** | `.work/gui/bench.vel`: no window, no message loop, 60 frames of 30 fills + 8 rounded rects + 20 labels |

If a number disagrees, check the two places it comes from: what `gui/app.exe 200`
prints about itself, and `runtime/vela_runtime.h`'s two regions (`VELA_ARENA_MB`,
`VELA_PERSIST_MB`), which since `87c3faf` are *reserved* rather than committed — that
is why the resident number is 26 MB and not 793 MB.

## The crash this environment has, not the toolkit

`gui/app.exe` sometimes exits with `0xC0000005` after a few seconds.  The controls that
settled what it is:

| control | result |
|---|---|
| the demo with `ShowWindow` removed, 3000 frames | clean, twice |
| a window shown, pumped and slept over for ~10 s, **never drawing** | crashed, twice |
| `notepad.exe` started with `start /min notepad.exe` | alive after 1 s, **gone after 9 s** |

So it is not the drawing, the pacing, the DC lifetime or dispatching — an ordinary
Windows program with an ordinary visible window does not survive here either, and every
crashing run's `stderr` carries the injected overlay's own failures
(`..._CC_CONN__GAMEPP-IPC-OVERLAY`, `[cc_acc] acquire failed`).  The numbers above come
from the runs that survived, and the hidden-window control is what separates "the
toolkit is wrong" from "a process with a window is not allowed to live here".

## What it is not

* **not multi-file** — the back ends still compile one file (`MODULES_PLAN.md` D8), so
  the toolkit and the demo live in one file; they split when steps 3-4 give the back
  ends a path per node;
* **no resize** — reading a client `RECT` needs a pointer, and there is no pointer type;
* **fixed 1× scaling** — no DPI awareness yet, so a 150% display stretches the frame;
* **a small widget set** — enough for the demo, not a widget library;
* **the number formatting is the demo's own** (`digits`/`fixed2`/`parse_int`) — there is
  no `int -> str` in the language yet, and that is the first standard-library module.
