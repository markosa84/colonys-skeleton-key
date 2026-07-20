# The Colony's Skeleton Key — internals

How the tool actually works, and every number behind it: the minigame's rules, the measured
animation timings, the screen reader's geometry, the probing algorithm, and the dead ends that are
not worth re-deriving. **If you only want to use the tool, [the README](../README.md) is the whole
story.**

One thing to know before reading on: the reader is calibrated and regression-tested against
labelled frames of the real game, and **those frames are in this repository**
([`src/test/data/frames`](../src/test/data/frames/README.md)). Everything stated about the reader
below was measured against them, and you can re-run every measurement yourself.

## The minigame

A lock has **4–7 plates**, each with a row of **7 holes** and a single pin. A plate slides between
offsets **−3 and +3** (the middle hole is 0), and the lock opens when **every pin is centered**
(offset 0) at once. Selecting a plate and sliding it one step may drag other plates it's
**connected** to:

| Connection | Effect |
| --- | --- |
| **Normal** | the connected plate slides the **same** direction |
| **Inverted** | the connected plate slides the **opposite** direction |

Connections don't cascade — only the plate you actually move drags its own connections. If any
affected plate would leave its track, the whole move is aborted and the pick is **strained**. Enough
strains break the pick — how many depends on the character's lockpicking skill (**2** untrained,
**4** basic, **6** master). A break re-homes the selection, and **resets the puzzle only when
untrained**; above that the plates are left exactly where they were. Resetting the lock yourself with
`R` is free, though the tool never does it.

The solver searches for the cheapest sequence that centers every plate, counting both the slides and
the presses needed to move the selection between plates. It only ever uses valid moves, so a found
solution also costs **zero strain**. "Cheapest" is measured in wall-clock time (`Cost.WALLCLOCK`):
a slide costs ~300 ms because it has to be watched to completion, where a selection press costs
~10 ms. (A fewest-keypresses weighting agrees in practice — the number of slides is fixed by the
connections, leaving nothing to trade — and that agreement is now a regression test.)

## Requirements

- **JDK 25** (developed against Amazon Corretto 25). The bundled Gradle wrapper handles the build.
- **The tool is Windows-only** (it calls `user32.dll` and `kernel32.dll` via Java's Foreign
  Function & Memory API and uses `java.awt.Robot`). Run the game **borderless windowed**. The
  reader was calibrated at **3840×2160 (4K)**; at any other resolution the 4K calibration is
  scaled to your screen at startup (an aspect-fit: the game's 16:9 view stays centred, wider
  screens add world at the sides, narrower ones above and below). The scaling is validated by
  live sweeps at 19 display modes from **1280×720** up, across 16:9, 16:10, 4:3 and 5:4. **1280×720
  is a hard floor**: below it the tool declines the lock rather than risk a misread, so play at
  720p or higher. If anything misreads anyway, the failure frames land in `captures/`.
- The build and the test suite run on any OS — no game and no display needed.

## Building and testing

```sh
./gradlew build          # Windows: .\gradlew.bat build
```

`build` runs the whole JUnit 6 suite (`./gradlew test`) and a coverage gate with it. Most of it runs
anywhere: the solver's optimality proofs, the session driven against a simulated game with the real
strain/break/reset rules, the executor's outcome classification, the capture-box geometry. Nothing
in it needs the game, a screen or a keyboard — even the screen capture and the key taps are tested,
through the seams that stand in for `java.awt.Robot` (which cannot exist in a headless JVM).

**The screen reader's regression gates run too**, against the labelled frames in
[`src/test/data/frames`](../src/test/data/frames/README.md) — the reader's whole calibration: a
34-frame 4K census (every frame must decode to its exact known plate count and offsets — the
constants are *fitted* to these), a 21-frame census of a 7-plate lock, a regression frame from the
one live misread ever diagnosed, and a 133-frame sweep of one lock at 19 display modes that pins the
resolution scaling. Change `LockReader` and you answer to all 189. They are shrunk to the region the
reader samples (see that directory's README), and if the directory is missing the frame-driven tests
**fail** rather than skip — a silent skip would switch the calibration off and still look green.
`captures/` collects failure frames automatically if you hit a misread of your own.

## Usage (Windows)

```bat
lockpick.bat
```

That builds if needed and starts the tool in the current console (`Ctrl-C` to quit). It expects
`JAVA_HOME` to point at a JDK 25. If the game's executable is ever named something other than
`G1R-Win64-Shipping.exe`, pass it as a quoted argument — `lockpick.bat "SomeOther.exe"`.
(`.\gradlew.bat run` does the same thing, if you prefer.)

Open a lock in-game, keep it focused, and press **F8**. That is the whole interface. It forgets
everything it knew, learns the plate connections by experiment, and opens the lock — **without ever
resetting it**. Probing is itself a sequence of legal moves, so wherever it finishes is simply a
closer state to solve from.

Play at any resolution from **1280×720** up (the tool declines smaller locks rather than misread
them), windowed or borderless, on any monitor. Each F8 measures the rectangle the
game is actually drawing into (the focused window's client area) and scales the 4K calibration onto
it, so the tool does not care whether the game fills your screen.

### Your gamma setting

The tool reads the lock by colour and brightness — it finds the brass pins by how warm they are, and
the holes by how dark. Those are absolute numbers, and they were measured at **gamma 2.7**. The game's
gamma slider runs **1.2 to 3.2**, and it is a matter of taste, so left alone those numbers would
describe one player's screen and nobody else's. They did, and it broke: at a darker setting the brass
never looked brass enough, the tool found no pins at all, and it reported *"no lock detected"* over a
screenshot that looked perfectly fine.

**You do not have to change anything.** Each F8 measures your gamma off the screen and undoes it
before reading, so every setting on the slider works. There is deliberately no option to tell it your
gamma: the game has a second brightness knob beside the first, and only the picture that actually
comes out is the truth.

How it measures it: the little **lockpick counter** under the lock is part of the interface, not the
world, so it looks the same in every room, on every lock, at every resolution — but the gamma pass
lands on it like everything else. Its white panel and its dark digits are a brightness reference of
known shape, and how far they have moved says exactly how far everything else has. Reading is enough:
nothing is guessed. And when the panel is not where it should be, the tool says so and corrects
nothing, rather than inventing a correction.

The one thing it will tell you about is a picture *darker than the gamma slider alone can make it* —
most often **HDR**. Gamma correction cannot describe that, so the tool does not lean on it there: it
reads the lock from its own light and shadow — how much darker a hole is than the plate around it —
which needs no brightness reference at all, and so an HDR screenshot reads too. It still says so in
the `.txt` beside the dump, and it will never trust a correction it can see does not fit.

### When it says "No 4-7 plate lock detected"

It saves two files in `captures/`: the frame it was looking at, and a `.txt` beside it saying which
rectangle it thought it was reading, what brightness it read, and what it found there. **Open the
`.txt` first.** If the frame shows the lock perfectly well, then the pixels were never the problem
and the tool was reading the wrong part of them — please report it, with both files.

You can produce those two files whenever you like, without waiting for something to go wrong:

```bat
ColonysSkeletonKey.exe --dump
```

F8 then simply photographs the game's view and stops. Nothing is probed, no key is sent, and no
lockpick is spent — so it is safe on any lock, and it is the best thing to attach to a bug report.

You can also replay any saved frame through the reader, without the game running:

```bat
ColonysSkeletonKey.exe --diagnose captures\no-lock-20260713-103856-180.png
```

It prints the gamma it read, every brass pin it found, which plate counts fit them, and the offsets.

Every lock it opens is appended, one compact block per solve, to `captures\lock-history.txt`: the
date and time, the state the lock was in when you pressed F8, the plate connections it worked out, and
the exact key sequence it played (`W`/`S` to pick a plate, `A`/`D` to slide it). It is a running record
of your solves — never reset, and written only on success, so a failed attempt leaves nothing in it.

If it strains and a lockpick breaks, that is expected, not fatal. F8 notices (from the lockpick
counter), waits out the ~4–5 s animation during which the game ignores input, re-homes the selection,
**keeps every connection it has learned**, and carries on discovering the rest. If you are untrained
the break also resets the puzzle; it re-reads the lock and continues from there. It never retries a
move it has already proven strains in that configuration, which is what stops a hard lock from eating
your whole supply of picks. It gives up after 5 broken picks, on the grounds that no lock in the game
needs that many.

Keys are only sent while the focused window is owned by **`G1R-Win64-Shipping.exe`**, so stray input
cannot leak elsewhere — and the check is repeated before *every* keypress, so alt-tabbing away
mid-run aborts the session cleanly instead of typing W/A/S/D into whatever you switched to. The gate deliberately checks the *process*, not the window title: a Chrome
window titled "gothic 1 remake lockpicking levels – Google Search" matches any sensible title test,
and the old title-based gate would have typed `W/A/S/D` into it. Override with
`-Dgame.process=other.exe` (or pass it as the first argument). Quit with **Ctrl-C**.

> The hotkey is *observed*, not swallowed — the game receives F8 too. Check that **F8** is unbound in
> the game's controls menu before you start. **F5** and **F9** were avoided as candidates
> (quicksave/quickload), as were F11 and F12.

> **Status:** working end to end. The screen reader is verified against every labelled frame in
> [`src/test/data/frames/`](src/test/data/frames/) — plate counts for 4-, 5-, 6- and 7-plate locks,
> and every plate's exact offset across eight slide sequences; run it yourself with `./gradlew test`
> (no game needed). F8 opens a live 5-plate chest lock in ~2.9 s with no strains. Non-4K resolutions
> are validated by live sweeps at all 19 of the dev machine's display modes at or above the
> **1280×720 floor** (one 5-plate lock, 1280×720 through 4K). **7-plate locks now read correctly** — their fan geometry was extrapolated
> and needed no correction when a real one was finally found — but, like 4- and 6-plate locks, they
> are labelled at 4K only. See [`CLAUDE.md`](CLAUDE.md).

## Measured timings

Every number below was measured against the live game, not guessed. They are what the tool's
constants are set from, so **re-measure before changing any of them** — several beliefs that seemed
obvious turned out to be wrong.

### Sampling the screen

| | min | median | max |
| --- | --- | --- | --- |
| `capture()` — full 3840×2160 | 67 ms | 79 ms | 89 ms |
| `captureLock()` — the 1300×1120 box the lock lives in | 16 ms | **23 ms** | 27 ms |
| `readState()` on either | 6 ms | **8 ms** | 24 ms |

The screenshot dominates, not the image processing. Grabbing only the lock's box gives identical
`readState` results and makes a poll ~30 ms instead of ~93 ms. (The box was
later widened from 1200×1000 to 1300×1120 to guarantee a safety belt around everything the reader
samples; the enlargement measured cost-free — the grab's fixed overhead dominates, and
`CaptureBoxTest` proves the containment for every plate count at every supported resolution.)

### Sliding a plate (14 slides sampled)

| Event, measured from the keypress | min | median | max |
| --- | --- | --- | --- |
| Plates start moving | 92 ms | **112 ms** | 133 ms |
| Plates stop moving | 195 ms | **207 ms** | 227 ms |
| Reader sees a stable, correct state | 261 ms | **269 ms** | 287 ms |

So a slide is ~100 ms of travel, ~110 ms after the key. Nothing has moved before ~90 ms, which is why
an unchanged lock means nothing until then.

### Switching plates (W/S)

**Free, and instant.** A selection change registers with a **0 ms key hold and a 0 ms gap**, and has
no animation. The game reads discrete key events rather than polling the keyboard per frame. Walking
the cursor across a 7-plate lock costs about as much as one screenshot.

That asymmetry — a slide costs ~300 ms, a selection press ~10 ms — is what `Cost.WALLCLOCK` encodes.

### What the game does with input

- **Slides are queued, never dropped.** Six sent back-to-back with a 0 ms gap all landed.
- **Input sent during a reset animation is discarded.** A slide 0 ms after `R` simply vanishes.
- **A strain does NOT block input.** A legal slide sent **0 ms** after a rejected move still lands
  (tested at 0, 0, 350, 600 and 900 ms — all landed). The shake is also invisible to the reader: no
  unreadable frames, no offset change. The one probe ever swallowed was on the trial where a *pick
  broke*, and that animation is a different thing entirely.

### Resetting the lock

All plates slide home in parallel, so the animation lasts as long as the *furthest-travelled* plate
needs — about **313 ms per step**, not per plate.

| Largest plate travel | plates stop | reader stable |
| --- | --- | --- |
| 1 step | 212 ms | 273 ms |
| 2 | 537 ms | 598 ms |
| 3 | 825 ms | 887 ms |
| 4 | 1140 ms | 1202 ms |
| 5 | 1458 ms | 1522 ms |
| 6 | 1774 ms | 1833 ms |

Motion begins 64–122 ms after the key.

### Breaking a lockpick

The skill level changes the rules of the minigame itself:

| Level | Strains per pick | Puzzle resets when a pick breaks? |
| --- | --- | --- |
| 0 — Untrained | 2 | **yes** |
| 1 — Basic | 4 | no |
| 2 — Master | 6 | no |

Levels 1 and 2 also delete a connection between two plates, which only makes locks easier.

**A break is invisible in the lock itself above level 0** — the plates stay exactly where they were.
So watching the plates cannot detect it, and counting strains only works if you know the skill. The
signal that works at *every* level is the **remaining-lockpicks counter**, the little white box under
the lock. It only ever decreases by one, so it is enough to notice that those pixels changed; no OCR
needed. `GameScreen.pickCounterFingerprint()` hashes them, and `Slider` compares the hash from before
the keypress against the one after the lock settles.

Verified live at master: the counter changes on **exactly the 6th strain**.

Two more measured facts:

- **After a break the game plays a ~4–5 s animation and discards every key sent into it.** The second
  break in a test appeared to need eight strains rather than six, because two of them landed inside
  that animation and never counted. `Slider` waits it out.
- **Pressing a plate into its own wall strains the pick** just like any other rejected move. The tool
  never does it — `applyMove` returns null for such a move, so neither the solver nor the discovery
  escalation will ever emit one.

**The skill level is never configured — it is observed.** Because the counter makes a break *visible*
at every level, `LockSession` counts picks by watching them break rather than by dividing strains by a
number the player told it. The level then falls out of the same observation: the strains a pick
survived before it broke *is* that character's strains-per-pick, and a break that also slid every
plate home can only have happened untrained. The session reports what it saw and forgets it — a
character can train lockpicking between one lock and the next, so a remembered level would be a lie
soon after. (`Skill.fromStrainsPerPick` hedges honestly: a pick carries its damage between locks, so
the first one a run breaks may have arrived already worn and break at a count matching no level. That
is reported as such, not rounded into a guess.)

## How it works

A screenshot is only the first third of it. One frame tells you *where the plates currently sit*,
but not how they are wired to each other — and you cannot solve the puzzle without knowing that. So
the tool reads the lock off the screen, physically experiments on it to learn the wiring, and then
searches for the cheapest solution and types it in.

### 1. Reading one frame into numbers

`GameScreen` grabs the screen with `java.awt.Robot`, and `LockReader` turns that image into two
facts: how many plates the lock has, and what offset each plate is at. (The game's whole view is
grabbed only to detect the lock or to save evidence; every poll uses `captureLock()`, which reads
just the 1300×1120 box the lock occupies, sized with a safety belt so it can never crop the lock —
see [Measured timings](#measured-timings).)

What makes this tractable is that the minigame always frames the lock at the same spot — the camera
never moves. So the reader knows ahead of time exactly where each plate's brass pin will be drawn:
on a fan, at `FAN_CENTER` plus a fixed `DEPTH_STEP` per plate of depth. It scans a small box for
warm-colored blobs and asks which fan of 4, 5, 6 or 7 positions is fully covered. The largest such
fan gives the plate count. Stray warm blobs — candles, wooden crates — simply don't land on a fan
position, and are ignored.

"The same spot" means the same spot **in the game's view**, which is not the same thing as the same
spot on your desktop. Every constant above is a 4K measurement, and `Viewport` maps it onto the
rectangle the game is really drawing into — measured, per F8, from the focused window rather than
assumed to be the whole display. That assumption was the tool's one real bug: a player running the
game smaller than his desktop had the reader scanning a region the lock was not in. It found no
pins, said so, and saved a screenshot in which the lock was plainly visible. A wrong rectangle
cannot announce itself; it can only come up empty.

Getting the **offsets** is the interesting part, because the game plays a nasty trick: *the pin does
not slide with its plate.* It stays put no matter where the plate is; the plate slides underneath it.
The pin does one visible thing — it **pops up** at the moment its plate becomes centered — but the
tool ignores that and reads every plate's position straight from its holes, which say the same thing
and more. (An earlier version read the pop as a separate "centered" signal; above the 1280×720 floor
the holes already carry it, so it was removed — see "Reading the lock's state" below.)

The offset comes from the holes. Each plate is a row of seven holes, and the pin
physically fills one of them, so an offset is just **how many holes sit left of the pin, minus
three**. To count them the reader rotates the frame by −30° about the fan center, which lays the
hole rows horizontal and separates the plates vertically. It then finds holes as **2D dark blobs**,
not by sampling along a line: the plates sit at different depths, so **no single rotation flattens
every row at once** (their individual best angles span −28.3° to −31.7°; −30° is the measured
compromise), and hole spacing runs 41–54px with depth. A blob only counts as a hole if its darkest
pixel is genuinely near-black — the plate's bent end-tab casts hole-sized *shadows* — but darkness
alone cannot be trusted: shadow depth varies by room, and one chest's arch-gap shadow measured
*darker* than a real hole backed by the plate behind it. So geometry gets the final say, twice.
Each blob must sit on its own row's **deskewed line** — every plate's row tilts slightly
differently with its depth in the fan, and the measured per-plate tilt is corrected per row. And
the reader walks pin → hole → hole in strict single-spacing hops, falling back to a hole-bridging
double hop only when the strict walk cannot account for all six holes: a double hop that
*overshoots* is bridging a hole that was never missing. (Both rules earned their keep on a
difficulty-4 chest, where a hole-shaped gap shadow sat exactly one double-hop past the end of a
row and the old walk bridged onto it, reading a perfectly visible row as unreadable.) A row that
still does not add up to six holes reads `UNKNOWN` rather than guessing. The session treats an
`UNKNOWN` row as a first-class situation: it never learns from a diff it could not fully see — it
undoes the move and probes again once the geometry changes, fills the gap from the learned model
while solving, and confirms "open" only from a fresh **direct** read in which every plate's own holes
place it at 0, with none `UNKNOWN` — never from a model-filled row, which is a guess. With the exact
walk and the deskewed gate in place that machinery is a safety net, expected to stay idle.

This stage is checked offline: `./gradlew test` replays every labelled frame in
[`src/test/data/frames/`](src/test/data/frames/) through `LockReaderTest` — the 34-frame 4K
calibration census, the `6p-gap-shadow` live-failure regression, the 21-frame 7-plate census, and
the 133-frame resolution sweep — no game, no display.

### 2. Learning the wiring, without spending picks

Moving a plate drags whichever plates it is connected to. Nothing in the picture shows those
connections, so `LockSession` discovers them by experiment: nudge plate `p` one step, screenshot,
diff. Every plate that also moved is connected to `p`; same sign means Normal, opposite means
Inverted. One successful move reveals `p`'s **entire** connection row, because connections don't
cascade.

The hard part is *getting* that move. A move is **atomic**: if the plate **or anything it drags**
would run off the end of its track, the whole move is cancelled and the pick strains. And here is the
trap: **a plate that refuses to move is not a plate without connections.** If it is not itself at the
end of its track, refusing *proves* it drags a plate that is.

Turn that around and you get the guarantee the whole routine is built on: **if every plate other than
`p` is off the ends, `p` is certain to move**, because a one-step drag of an interior plate cannot
leave the track. That is a condition you can check from a screenshot. So each move is chosen
cheapest-risk-first:

1. **Free** — every other plate is already interior, so just slide it; no strain is possible.
2. **Planned** — breadth-first search over moves of *already-probed* plates for a sequence that clears
   the ends for some unprobed plate. Their connections are known, so every move is proven legal before
   a key is pressed. This costs time, never a pick — which is exactly the trade we want.
3. **Gamble** — only when neither of the above exists. Fewest plates at ends first, sliding toward
   centre so plates walk off the ends and the next move is safer.

A gamble that strains is remembered and never retried while the plate that blocked it could still be
blocking it. **That memory survives a broken pick.** Without it, an untrained player's reset would
restore the exact configuration the gamble just failed in and the tool would try it again, and again —
the old failure mode where a hard lock ate every pick you owned.

### 3. Solving, from wherever it ended up

Once every row is known, `LockSolver` runs a Dijkstra search over states of
`(lock configuration, selected plate)`, starting from **the lock as it now stands** rather than from
its original positions. Probing is a sequence of legal moves, so its end state is simply a closer
state to solve from — and there is no reset to pay for. It only ever expands legal moves, so the
solution costs **zero strain** by construction.

Moves are played back **one at a time**, each compared against what the model predicts. If the lock
does something else, the observation *corrects* the model — a successful move always reveals that
plate's true row — and the next move is re-planned from what actually happened. If a plate the model
called free refuses to move at all, the model is wrong about it, so its row is discarded and that
plate is learned again.

"Open" is confirmed from a **fresh direct read**, not a guess. When the plan says the next move
finishes the lock, the tool plays it and re-reads: only if every plate's own holes place it at `0`,
with **none unreadable**, does it call the lock open. A model-filled row — one deduced rather than
seen — can never declare it, so a hidden or mis-modeled plate costs a re-look, not a false "solved".
(A row that stays hidden in the all-zero configuration itself therefore can't be confirmed at all;
above the 1280×720 floor that does not happen, since a centred plate hides nothing.)

### The plumbing

`Win32` uses Java's Foreign Function & Memory API to call into `user32.dll` and `kernel32.dll` for
three things: `GetAsyncKeyState` to poll the F8 hotkey (plain AWT cannot see keys the app doesn't
own), `SetProcessDPIAware` so the capture returns real pixels, and
`GetForegroundWindow` + `GetWindowThreadProcessId` + `QueryFullProcessImageNameW` as a safety gate —
keys go out only while the **game's process** owns the focused window, so a stray `A` never lands in
your editor or your browser.

One detail that looks cosmetic and isn't: screen capture needs **both** `SetProcessDPIAware()`
(before AWT initializes) **and** `-Dsun.java2d.uiScale=1`. On a 4K display at 200% scaling, either one
alone yields a scaled or black capture.

## Project layout

All code lives under the base package `io.github.markosa84.colonysskeletonkey`
(`src/main/java/io/github/markosa84/colonysskeletonkey/`):

- `AutoLockpick` — the entry point: DPI-aware startup, the F8 hotkey loop, the focus gate, and the
  one place the concrete object graph is wired together.
- `solver/` — the dependency-free domain core: `LockModel`, `Connection`, `Move`, `Cost`, and
  `LockSolver` (the connection algebra `applyMove` plus the least-cost search).
- `vision/` — `Viewport` (the screen size, and the mapping from the calibrated 4K reference
  coordinates onto it), `GameScreen` (every Robot pixel grab: full capture, the fast lock-box
  composite, the pick-counter fingerprint), `LockReader` (pure frame analysis, headless-safe),
  `Captures` (failure-frame dumps to `captures/`), and the live adapters `LivePoller` /
  `LiveLockView`.
- `control/` — `KeySender` (cursor tracking over the `Keyboard` seam; `RobotKeyboard` is the real
  one) and `Slider`, the one place a plate is ever moved, with its measured `Timing` contract and
  the `LockPoller` seam it watches the lock through.
- `session/` — `LockSession` (learn-then-solve, the Free → Planned → Gamble escalation, the refusal
  memory), `Skill`, and the seam interfaces the session owns: `LockView`, `MoveExecutor`,
  `CursorKeys`.
- `win32/` — the FFM bindings.

Tests mirror the packages under `src/test/java/`, with `FakeGame` (a simulated lock with hidden
connections and real strain/break/reset rules) driving the session tests, and the labelled frames
under [`src/test/data/frames/`](src/test/data/frames/) — the 34-frame 4K census, the
`6p-gap-shadow` live-failure regression, the 21-frame 7-plate census and the 133-frame resolution
sweep, deliberately not classpath resources so builds never copy the corpus around — driving
`LockReaderTest`. The frames keep their full screen dimensions but are black outside the lock box
and the lockpick counter (`scripts/shrink-frames.ps1`): the reader samples nothing out there, and
the scenery was 4/5 of a 1.1 GB corpus.
`CaptureBoxTest` proves the capture box contains everything the reader samples, for any plate
count in any state at any supported resolution.
