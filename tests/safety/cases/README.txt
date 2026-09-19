# tests/safety/cases/*.vel — one program per promise, positive and negative.
#
# Every file here is ASCII-only and standalone: it is run by `tools/safety.ps1`
# against a frozen copy of `selfhost/build/vm.exe`, never by the Vela suite (its
# expectations are in `tests/safety/manifest.txt`, written by hand before anything
# was run).  The cases that are *supposed* to run print one line and assert
# nothing themselves; the harness compares their stdout to the manifest.
#
# Naming: <area>_<shape>.  `strict_` cases are SPEC.md §4 strictness, `par_` cases
# are SPEC.md §7 `parallel for`, `hole_` cases are the ones that should be refused
# and are not — those are documented in `SAFETY.md` and kept here so the gap is a
# case and not a rumour.
