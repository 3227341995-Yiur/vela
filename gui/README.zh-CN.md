# gui —— 用 Vela 写的原生桌面工具包

[English](README.md) | **简体中文**

<!--
源文件 : README.md
源文件字节 : 5318
源文件 SHA256 : c5c936f99cde0a3a6e57b36cc968c3dc8b2bcb582a086f458b5a777a8d30488b
翻译日期 : 2026-10-30
规则 : 本文件是上面那个英文文件的完整翻译。英文文件一旦改动，本文件立即过时，
       powershell -ExecutionPolicy Bypass -File tools\docs-zh-check.ps1 会指名报告。
-->

## 这是什么

`gui/app.vel` 是一个**单文件**的原生桌面程序：一个窗口、一个帧循环、一套主题、一组
immediate-mode 控件，全部用 Vela 写。底下没有一行 C、没有外部运行时、没有 shim——只有
`extern c` 调 Windows 自己的 user32 / gdi32 / winmm。

让这一切成为可能的那次改动是 `2ef31e3`：`extern c` 能传 `str` 参数之后，Win32 才真的可达
（每一个接受文本的入口点接受的都是 `char *`）。

## 构建与运行

```bat
selfhost\build\vm.exe build gui\app.vel      ::  -> gui\app.exe
gui\app.exe                                  ::  一直跑到关窗或按 Esc
gui\app.exe 200                              ::  画 200 帧就退出，并打印自己的计时
```

`vm.exe run` **不能**跑它：解释器没有外部调用。`vm.exe build-c` 可以（同一个 C 后端）。

## 这条线画在哪

| 层 | 是什么 |
|---|---|
| 窗口 | `CreateWindowExA("STATIC", …, SS_OWNERDRAW)` + `ShowWindow` |
| 消息 | `PeekMessageA` 排水（**从不 dispatch**）；`WM_CLOSE` 直接从队列里读出来 |
| 输入 | `GetMessagePos`（鼠标）、`GetAsyncKeyState`（按键） |
| 绘制 | 内存 DC + 兼容位图，`PatBlt`/`RoundRect`/`TextOutA`，每帧一次 `BitBlt` |
| 计时 | `GetTickCount64`；`timeBeginPeriod(1)` 让 `Sleep(1)` 真的是 1 毫秒 |
| 控件 | `label` / `button` / `card` / `meter` / 侧栏项，immediate mode |

**为什么没有回调**：Vela 0.1 没有函数类型，而 Win32 的窗口类要一个 WNDPROC。所以窗口用的是
系统自带的 `STATIC` + `SS_OWNERDRAW`：它自己不画东西，它不处理的消息（含 `WM_CLOSE`）交给
`DefWindowProc`——那里本来就写好了销毁窗口的逻辑。

## 实测数字

| | 值 | 怎么测的 |
|---|---|---|
| 启动，进程到第一次 blit | **15–16 ms** | 程序自己的 `GetTickCount64` 差值 |
| 帧时间 | **< 1 ms** | 同上，`spent / frames` |
| fps | **8333–13333（不封顶）** | 同上，`frames * 1000 / spent` |
| 常驻内存 | **26 MB**（首帧前 1.9 MB / 私有 0.5 MB） | 外部用 `Get-Process WorkingSet64` 采样 |
| 工具包一帧的绘制成本 | **0.31 ms** | `.work/gui/bench.vel`：无窗口、无消息循环，60 帧 × 30 填充 + 8 圆角 + 20 文本 |

## DPI

进程是 per-monitor aware V2（`SetProcessDpiAwarenessContext(-4)`，必须在第一个窗口之前调用——
之后 Windows 已经决定要位图拉伸这个进程了），工具包自己缩放：`sc = GetDpiForSystem() * 1000 / 96`
按千分之一为单位，在绘制原语内部应用，所以布局仍然按 1120×720 的逻辑画布书写，而窗口、四个字体
和每一个进 GDI 的坐标都是设备像素。

100% 的显示器只能跑到 `sc = 1000`，所以缩放比例也是可设的：

```bat
set VELA_GUI_SCALE=1500 && gui\app.exe 200
```

在这台机器上实测（100% 显示器，`GetDpiForSystem()` = 96），200 帧，每种各三次：

| 强制缩放 | 客户区 | fps（不封顶） | 运行 |
|---|---|---|---|
| 1000（100%） | 1120 x 720 | 4255 | 3 干净 / 0 崩 |
| 1500（150%） | 1680 x 1080 | 3125 | 3 干净 / 0 崩 |

窗口尺寸、字体和命中测试都用同一个数，所以客户区在两个方向上都正好是 1.5 倍。

数字对不上时查这两处：`gui/app.exe 200` 自己打印的 `startup` / `frame time` / `fps`，以及
`runtime/vela_runtime.h` 的两个区（`VELA_ARENA_MB`、`VELA_PERSIST_MB`）——`87c3faf` 之后它们是
**预留**而不是提交，这正是常驻内存是 26 MB 而不是 793 MB 的原因。

## 一个还没关掉的缺陷

`gui/app.exe` 有时以 `0xC0000005` 退出：窗口**显示出来**时大约每两次里崩一次，通常发生在
已经跑满五秒的那次；把 `ShowWindow` 去掉，同一份程序 400 帧跑两次都是干净的。五秒是 Windows
的“无响应”时钟。目前排除掉的：`DispatchMessageA`（早已完全去掉）、`DeleteObject`/画笔反复
创建、绘制本身（什么都不画也会崩）、节流的 `Sleep`、`ValidateRect`、`ReleaseDC`；
`BeginPaint`/`EndPaint` 和 `UpdateWindow` 各自把崩溃率降下来一些，但**都没有消掉它**。

## 它现在不是

* **不是多文件**——后端仍然只编译一个文件（`MODULES_PLAN.md` D8），所以工具包和 demo 在同一个
  文件里；等第 3、4 步给后端教会逐节点路径，它们才会分开；
* **不能 resize**——读客户端 `RECT` 需要一个指针，而语言里没有指针类型；
* **固定 1× 缩放**——还没有 DPI 感知，150% 的屏上会被系统拉伸；
* **控件集很小**——够 demo 用，不是一套控件库；
* **数字转字符串是 demo 自己写的**（`digits`/`fixed2`/`parse_int`）——语言还没有 `int -> str`，
  那是第一个 stdlib 模块。
