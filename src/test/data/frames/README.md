# Labelled calibration frames

These 244 PNGs are the test fixtures `LockReader` is calibrated against — every pixel constant in
the vision layer is fitted to them, and `LockReaderTest` replays all of them on every build. They
are captures of the Gothic 1 Remake lockpicking minigame taken while developing the tool.

**They are shrunk.** Each frame keeps its original screen dimensions — so every absolute coordinate
in the reader still lands on the right pixel — but everything outside the lock box and the lockpick
counter is painted black, which is all the reader ever samples. Pixels inside those boxes are
byte-identical to the original capture, so a fixture is exactly what `GameScreen.captureLock()`
hands the reader in a live run. `scripts/shrink-frames.ps1` does this; a new frame must go through
it too, or the corpus starts growing by ~12 MB a shot.

## What's here

| Group | What it pins |
|---|---|
| `plate-count/` | the 4/5/6-plate fans |
| `5p-*`, `6p-*`, `7p-*` | slide sequences at 4K: each is a chain of single steps, so a reader wrong on one frame breaks the whole sequence's arithmetic |
| `6p-gap-shadow/` | a live misread: an arch-gap shadow the old hole walk mistook for a seventh hole |
| `<width>x<height>/front-plate-sweep/` | one 5-plate lock replayed at all 23 display modes, 800x600 to 4K — this is what validates the `Viewport` scaling against real renders |
| `gamma/` | one 7-plate lock replayed across the game's **gamma slider**, 1.2 to 3.2 — what `vision/Tone` is measured against and gated by. Raw, the two ends break the reader in opposite ways |
| `2560x1440/gamma-1.2-sweep/` | the dark end at the resolution a user actually reported broken: gamma and scale are independent faults |

`3840x2160/front-plate-sweep/labels.txt` and `gamma/labels.txt` record how those labels were
established — both rest on the same argument, that game state depends on the **keys sent**, not on
the pixels that come back, so a known protocol labels a frame without a reader you can yet trust.
`docs/INTERNALS.md` explains what the reader does with all of it.
