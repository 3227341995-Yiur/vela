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

## One defect that is still open

`gui/app.exe` sometimes exits with `0xC0000005`: with the window **shown** roughly one
run in two, usually the one that has been up for five seconds; with `ShowWindow`
removed, the same program ran 400 frames twice and was clean.  Five seconds is
Windows' "not responding" clock.  Ruled out so far: `DispatchMessageA` (long gone),
`DeleteObject`/brush churn, the drawing itself (a variant that draws nothing still
faulted), pacing `Sleep`, `ValidateRect`, `ReleaseDC`; `BeginPaint`/`EndPaint` and
`UpdateWindow` each reduced the rate without removing it.

## What it is not

* **not multi-file** — the back ends still compile one file (`MODULES_PLAN.md` D8), so
  the toolkit and the demo live in one file; they split when steps 3-4 give the back
  ends a path per node;
* **no resize** — reading a client `RECT` needs a pointer, and there is no pointer type;
* **fixed 1× scaling** — no DPI awareness yet, so a 150% display stretches the frame;
* **a small widget set** — enough for the demo, not a widget library;
* **the number formatting is the demo's own** (`digits`/`fixed2`/`parse_int`) — there is
  no `int -> str` in the language yet, and that is the first standard-library module.
