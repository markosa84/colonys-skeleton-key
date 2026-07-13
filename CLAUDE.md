# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**The Colony's Skeleton Key** — a solver + automator for the **Gothic Remake lockpicking minigame**
(Windows-only, JDK 25). The lock has 4–7 plates, each with a row of **7 holes** and one pin; a
plate's offset is where its pin sits (−3..+3, the middle hole being 0). The lock opens when every pin
is centered (offset 0). Moving a plate slides it one step and drags its connected plates the **same**
(Normal) or **opposite** (Inverted) direction. Connections don't cascade. An invalid move (any plate
would leave its track) **strains** the pick. Enough strains break it — **2 untrained, 4 basic,
6 master** — and the puzzle resets **only when untrained**; above that the lock is left exactly as it
was, so a break is invisible except in the lockpick counter. A manual reset (`R`) is free, but the
tool never uses it. The tool must work at any skill level.

All code lives under the base package `io.github.markosa84.colonysskeletonkey`.

## Where a fact belongs

Five files carry prose, and they do not overlap. Put a new finding in exactly one, and fix the
number in **every** file that repeats it — a stale count in one contradicts a correct one in another,
and both are loaded into the same context.

| File | Holds | Audience |
| --- | --- | --- |
| `CLAUDE.md` (this) | build/run, architecture, the measured constants, the dead ends | agents |
| `AGENTS.md` (root) | *workflow* gotchas — what costs time, what costs the player picks | agents |
| `src/main/java/AGENTS.md` | code-level notes on the reader/session seams | agents |
| `docs/INTERNALS.md` | the public deep doc: rules, **"Measured timings"**, geometry, dead ends | **users** |
| `README.md` | download, run, what it does. No internals. | **users** |

The last two are **published** — write them for a player, not for a maintainer, and keep them free of
legal hedging about the author's own screenshots. Everything an agent needs to *not repeat a mistake*
goes in one of the first three.

## Commands

`javac`/`java`/`gradle` are **not on PATH**. The JDK is Corretto 25 at `$env:JAVA_HOME`
(`C:\Users\marko\.jdks\corretto-25.0.2`); the Gradle wrapper uses `JAVA_HOME`.

```powershell
# Build (assembles jar + start scripts, and runs the whole JUnit suite)
& .\gradlew.bat build --console=plain

# Just the tests (~30s; includes the 34-frame calibration gate, the live-failure regression
# frames, the 21-frame 7-plate census and the 161-frame resolution sweep; no game, no display)
& .\gradlew.bat test --console=plain

# One class, or one method (a @ParameterizedTest matches by METHOD name, not by its "seed 0"
# display name). Single-quote the filter in PowerShell, as with any -D/-P arg.
& .\gradlew.bat test --console=plain --tests '*LockReaderTest'
& .\gradlew.bat test --console=plain --tests '*LockSolverTest.plansUseTheFewestSlidesPossible'

# Run the automation app. lockpick.bat builds if needed and sets the two required JVM flags;
# `gradlew run` is equivalent. Optional arg = the game's process name.
.\lockpick.bat

# Photograph the game's view on F8 and do nothing else - no probing, no keys, no lockpicks spent.
# This is the shape a bug report should arrive in (frame + sidecar, which now carries the gamma the
# tool read), and it is how the gamma corpus was captured: walk the lock through a known key protocol
# by hand, one dump per step, with the tool never touching it.
.\lockpick.bat --dump

# Replay a user's failure dump through the reader, offline: what it found, and what it made of it.
# This is the FIRST thing to run on a "no lock detected" report - see the dead ends. The frame is
# read as the game's whole view, so a dump that reads correctly here proves the pixels were fine
# and the LIVE viewport was wrong. Works headless; the packaged exe takes the same flag.
& .\gradlew.bat run --console=plain '--args=--diagnose captures\no-lock-20260713-103856-180.png'

# The release artifact: a self-contained Windows app image (our jar + a trimmed JRE + a native
# launcher), zipped. A player unzips it and runs the exe - no Java, nothing to configure.
& .\gradlew.bat releaseZip --console=plain '-PappVersion=1.0.0'
```

- **`-PappVersion` is not yours to choose.** In a release it comes from **release-please**, which
  derives it from the conventional commits and owns `version.txt` and `.release-please-manifest.json`
  — never hand-edit those, and never tag by hand. Pass any numeric value locally; it only names the
  zip. AGENTS.md, "Commit messages are load-bearing", has the rules and the release flow.

- **`gradlew run` / `lockpick.bat` launch `AutoLockpick`, an infinite global-hotkey loop** — never
  run it in a non-interactive/automated shell; it does not return. Only run it when a human will
  drive the game and press Ctrl-C.
- The automated gate is **`gradlew test`** (JUnit 6, `junit-bom:6.1.x`, the project's only
  dependency and test-scoped — the app itself has none; JaCoCo is a build plugin, not a dependency).
  `LockReaderTest` must stay green on
  **every** frame — the 34-frame census, the `6p-gap-shadow` regression frame, the 21-frame `7p-*`
  census, the 161-frame resolution sweep and the 27-frame `gamma` corpus: every `LockReader` constant
  is fitted to the frames under `src/test/data/frames/`. Those PNGs are deliberately **not classpath resources** (the whole corpus
  would be copied into `build/` every clean build); the test task passes their absolute path as
  `-Dlockpick.frames.dir` and declares them a task input, with a relative fallback for runners
  started in the repo root.
- **The JUnit 5 → 6 move cost nothing.** The suite only ever used `@Test`, `@BeforeAll`,
  `@ParameterizedTest` + `@ValueSource`/`@MethodSource`/`@CsvSource`, and the plain `Assertions`,
  all of which keep their `org.junit.jupiter.*` packages in 6. It was a one-line BOM bump: no source
  change, 407/407 still green. (JUnit 6's breaking changes — Java 17 baseline, the removed
  `migrationsupport` module, FastCSV replacing univocity — touch nothing here.)
- **`gradlew build` also enforces coverage** (`jacocoTestCoverageVerification`, wired into `check`):
  **≥92% line, ≥90% branch**, currently at 94.2/91.4. `win32` is excluded from the report *and* the
  gate — it is the FFM boundary, and a test of it would test Windows. What is left uncovered is only
  what a headless JVM cannot reach: `AutoLockpick.main` (owns a `Robot`, a `Toolkit` and an endless
  loop) plus its display-owning helpers (`awtScale`, `screenSize`, `environment` — a headless JVM
  throws from `GraphicsEnvironment`), and the `Robot`-backed halves of the seams. **Raise the floor
  when coverage rises; never lower it to make a change fit.**
- **The frames are shrunk, and a new one must be too** (`scripts/shrink-frames.ps1`). A full-screen
  4K shot is ~12 MB and the corpus was ~1.1 GB in the tree and as much again in `.git`; almost all
  of it was game scenery the reader never samples. Each frame therefore keeps its **original
  dimensions** — every absolute screen coordinate still lands on the right pixel, and `TestFrames`
  needs no special loader — but everything outside the lock box and the lockpick counter (each plus
  a 150px belt, viewport-mapped) is painted **black**, which PNG stores for almost nothing. Pixels
  inside are byte-identical, so the fixtures are the same calibration ground truth; a shrunken frame
  is just what `GameScreen.captureLock()` already feeds the reader live. Corpus: **1,086 MB → 274 MB**,
  the suite unchanged at 407/407. **Capture at full size, archive the original off-repo, then shrink**
  — the discarded pixels do not come back, and a reader that ever needs to sample somewhere *else*
  will need the archive (`C:\dev\frames-archive` on the dev machine) or a fresh capture.
- In PowerShell, **single-quote `-D…` JVM args** (e.g. `'-Dsun.java2d.uiScale=1'`) or they get
  mangled into a bad main-class argument.
- **One repository, public, frames included** (`colonys-skeleton-key`, MIT). There used to be a
  private repo plus a public export copy (`scripts/export-public.ps1`), on the belief that no game
  screenshot could be published; that premise was retired, the split was pure overhead, and both the
  script and the second working copy are gone. Consequences that still bind: **anything that needs a
  frame goes through `TestFrames`**, and the frame-driven classes **fail** rather than skip when the
  frames are missing — a skip would switch the reader's entire calibration off and still report a
  green suite. CI therefore runs the real 244-frame gate, which is this project's only verification
  on a machine that is not the author's.
- Toolchain is pinned to Java 25 with `auto-download=false`; the daemon runs on JDK 25 and resolves
  the current JVM, so no toolchain fetch. (Dependency resolution for JUnit did need the network
  once.)

## Architecture

One dependency direction: `solver` is the dependency-free domain core; `vision`, `control` and
`session` build on it; `AutoLockpick` (root) is the only place concrete classes meet. The session
owns its seam interfaces (`session.LockView`, `session.MoveExecutor`, `session.CursorKeys`), so it
can be driven entirely by fakes in tests — `FakeGame` in the test tree simulates a lock with hidden
connections and the game's real strain/break/reset rules.

- **`solver/`** — `LockModel` (record: `n`, `start[]`, `Connection[][] connections`, `maxOffset`;
  also home of the domain constants `MAX_OFFSET`=3, `MIN_PLATES`=4, `MAX_PLATES`=7), `Connection`
  (target + `Type.NORMAL|INVERTED`), `Move` (plate + dir), `Cost`, and **`LockSolver`** — a
  least-cost (Dijkstra) search over `(lock configuration, selected-piece cursor)` states returning
  `List<Move>`. It only ever emits valid, strain-free moves. `solve(m, startConfig, startCursor,
  cost)` searches from **any** configuration and cursor — which is what lets F8 solve from wherever
  probing stopped. Two **`Cost`** weightings, same search:
  - `Cost.KEYPRESS` `(slide 1, nav 1)` — fewest keys.
  - `Cost.WALLCLOCK` `(slide 300, nav 10)` — a slide is ~300ms (press + wait out the animation), a
    W/S press ~10ms. **The automation uses this.**

  **A solve is not free: ~430ms on a 6-plate lock** — more than the ~320ms it takes to watch a
  slide animate. So `LockSession` **caches the plan** and re-solves only when the lock diverges
  from it; re-solving before every move once cost **9.1s of a 22.1s run** (the same 22-move plan,
  computed 22 times). Don't put the search back in the inner loop.

  **The two have never yet produced a different plan** (checked live on the 5-plate lock and 200
  random 6-plate starts, and now pinned by `LockSolverTest.keypressAndWallclockWeightingsAgree`). The slide
  count is fixed by the connection algebra — you cannot add a redundant slide to save cursor
  travel — so the keypress-optimal plan already uses the fewest slides
  (`LockSolverTest.plansUseTheFewestSlidesPossible` checks against brute-force BFS). **Do not expect
  WALLCLOCK to speed anything up**; the real win is F8 not resetting.

- **`vision/`** — everything pixels:
  - **`Tone`** — the game's **gamma slider (1.2–3.2)**, measured off the frame and undone. Every
    colour/luminance gate in `LockReader` is an absolute number fitted at **gamma 2.7**, so both ends
    of the slider break it, from opposite directions: dark, the brass falls under `isPin`'s `r>=130`
    and **no fan fits at all** (a real user's bug: "no lock detected" over a screenshot that looks
    fine); bright, the highlights climb over `b<=140`, the pin blobs shrink (51–103px at 3.2 against
    a calibrated 150–180), the holes brighten past `HOLE_MAX_MIN_LUM`, and — worst — **no pin reaches
    `CENTERED_MIN_PIXELS`**, so the pop signal dies and the tool cannot recognise a lock it just
    solved. The frame is mapped back to the calibrated look and every constant keeps its meaning.
    - **The probe is the lockpick-counter panel** (`GameScreen.picksBox`): opaque UI at a fixed spot,
      so the room cannot touch it, but the gamma pass runs over it like everything else. Measured
      identical across 8 fixtures (4 rooms, 4/5/6/7 plates, different pick counts) **and all 23
      resolutions down to 800×600**. Two anchors, and they are **complementary** — exactly where one
      saturates, the other moves:

      | gamma | 1.2 | 1.5 | 1.8 | 2.1 | 2.4 | **2.7** | 3.0 | 3.2 |
      | --- | --- | --- | --- | --- | --- | --- | --- | --- |
      | panel ink | **0** | 9 | 24 | 41 | 58 | **74** | 91 | 100 |
      | panel white | **244** | 253 | 255 | 255 | 255 | 255 | 255 | 255 |

      So: index by ink, except at the bottom of the slider where the ink has crushed to black and the
      white — just lifted off its clip — indexes instead.
    - **The curves are measured, never fitted** (`tools/ToneTable.java` regenerates them from the
      fixtures; don't hand-edit `Tone.FAMILY`). The transform is a plain **per-pixel LUT on the final
      LDR frame** — verified per channel, inter-quartile spread 0–3 levels — so a LUT inverts it
      exactly. The calibrated member is the **exact identity**, which is why the whole corpus goes on
      vouching for every reader constant unchanged.
    - **Gamma is a session constant** (you cannot move the slider without leaving the minigame), so
      `AutoLockpick` estimates it **once per F8**, not per poll. `GameScreen` takes it too, because
      `pickCounterFingerprint` thresholds the same panel — see the dead ends.
    - It **refuses to guess**: no plausible panel ⇒ the calibrated tone and a loud note (which is also
      a hint that the *viewport* is wrong, since a wrong one leaves the box black). And a frame whose
      (ink, white) is **off the family curve** is called out: the slider moves both anchors together,
      so an off-curve pair means a *second* setting is dimming the picture (`m_ColorBrightnessOffset`,
      HDR, a display profile). The reporter's frames read ink 13 beside a white of 184, where the
      family says 254 — still unexplained; see "Remaining".
  - **`Viewport`** — the rectangle **the game draws into** (origin + size), plus the mapping from the
    calibrated 4K reference coordinates onto it: an **aspect-fit** (`scale = min(w/3840, h/2160)`,
    positions anchored to the view's centre; distances scale linearly, blob areas quadratically,
    luminance thresholds not at all). The game fits its 16:9 view — Hor+ on wider screens, Vert+ on
    narrower ones; a height-only Hor+ model was tried first and **failed live at 16:10/4:3**, so
    don't regress to it. `AutoLockpick.resolveViewport` measures the game's window per F8 press (see
    "Screen capture needs ALL THREE" — measuring the *screen* was a shipped bug). Every mapping here
    is **view-local**: the origin exists so `GameScreen` can translate a grab onto the desktop, and
    nothing else in the vision layer knows the window moved. At `Viewport.REFERENCE` (3840×2160 at
    the desktop origin) the mapping is exactly the identity, which is how the 34/34 calibration gate
    certifies the parameterization itself; the 161-frame `front-plate-sweep` fixtures (one 5-plate
    lock, all 23 dev-machine display modes, 800×600..4K) validate the scaling against real renders.
  - **`GameScreen`** — the only Robot owner, and **the only class that applies the viewport's
    origin**: it keeps every box twice, view-local (what the reader sees, what the canvas uses) and
    translated onto the virtual desktop (what the grabber is asked for). `capture()` (the game's
    whole view, ~79ms at 4K — evidence only), `captureLock()` (the lock's 1300×1120 box — a ≥70px
    safety belt around everything the reader samples, containment proven by `CaptureBoxTest` for
    every plate count at every viewport — composited into a reused view-sized canvas, ~23ms, the belt
    enlargement measured cost-free — what polling uses), and `pickCounterFingerprint()` (FNV-1a over
    the thresholded lockpick counter box).
  - **`LockReader`** — pure `BufferedImage` analysis, headless-safe (which is why the 34-frame gate
    runs anywhere), from **one frame**:
    - *Plate count.* The minigame frames the lock at a **fixed screen position**, so the reader
      finds the brass **pins** (warm blobs in a fixed box) at their calibrated **fan** positions
      (`FAN_CENTER` + `DEPTH_STEP`) and returns the largest 4–7 fan every position of which is
      covered. Stray warm blobs (candles, wood) are simply left unmatched.
    - *Which plates are centered* (`readCentered`). **A pin doesn't move with its plate's offset** —
      it only **pops up** (warm blob grows ~2.5×) when that plate is centered. The signal is binary:
      pin size says centered-or-not, never the magnitude.
    - *Every plate's offset* (`readState`). Rotating the frame `ROT_DEG` (−30°) about `FAN_CENTER`
      lays the 7-hole rows horizontal and separates the plates vertically. Six of a plate's holes
      are dark blobs (the pin fills the seventh), so **offset = (holes left of the pin) − 3**. No
      single angle lays *every* row flat (see "Rotation angle" below), so holes are found as 2D
      blobs and assigned to the nearest row, not sampled along a line; a blob only counts if its
      darkest pixel is near-black (`HOLE_MAX_MIN_LUM`), which separates most real holes from the
      plate's own shadows — but that separation is room-dependent (an arch-gap shadow once
      bottomed at 57, *darker* than a real plate-backed hole at 45), so geometry does the real
      rejecting: a blob must sit within 12px of its row's own **deskewed line** (`rowSlope`; see
      "Rotation angle"), and `walk()` hops pin→hole→hole in **single** spacings, falling back to
      the hole-bridging double step only when the exact walk doesn't add up to six — a skip that
      overshoots "bridges" a hole that was never missing (the `6p-gap-shadow` fixture pins both
      fixes). Rows that still don't add up read `UNKNOWN` (`LockModel.isComplete`).
  - **`LockReader.describe`** — the reader's own account of a frame: every warm blob it found in the
    pin box, and for each `n` in 7..4 which fan positions nothing covered. **This is the first thing
    to read on a "no lock detected" report.** *No warm blobs* = the scan box is not over the lock
    (wrong viewport) or the colours moved (HDR/gamma/shader). *Blobs but no fan fits* = wrong scale,
    so still the viewport. It is what `--diagnose` and the dump sidecar print.
  - **`Captures`** — failure-frame dumps to `captures/<tag>-<timestamp>.png` so a live misread can
    be replayed offline and folded into the test fixtures, **plus a `.txt` sidecar** carrying the
    viewport, the environment and `describe()`. The frame alone is not evidence: the failure it
    usually documents leaves a screenshot that looks perfectly normal, because the pixels are fine
    and the coordinates are not. Ask a reporter for both files.
  - **`LivePoller` / `LiveLockView`** — the live implementations of the `control.LockPoller` and
    `session.LockView` seams, composing `GameScreen` + `LockReader` (+ `Captures`). `LiveLockView`
    takes a `Supplier<String>` for the environment half of the sidecar — Win32 and the display are
    `AutoLockpick`'s to know about, not the vision layer's.

- **`control/`** — everything keys:
  - **`KeySender`** — W/S/A/D with a tracked cursor, over the **`Keyboard`** seam
    (`RobotKeyboard` = the real taps with 5/5ms margins). `endCursor` saturates S to reach plate
    `n−1` (W/S clamp — verified live). No reset key: F8 never resets, so `R` is not bound at all.
    Implements `session.CursorKeys`. **`RobotKeyboard` re-checks the focus gate before every
    tap** and throws `FocusLost` (caught in `AutoLockpick`) — the F8 gate only covers the start
    of a session, and an alt-tab mid-run must abort instead of typing W/A/S/D into whatever got
    focused (it typed into IDEA once).
  - **`Slider`** — the one place a plate is ever moved: press the key, watch through a
    `LockPoller` (readLock reports unreadable plates as `LockModel.UNKNOWN`), and report
    `MOVED` / `UNCHANGED` / `RESET` plus whether a pick broke (fingerprint diff). **The counter is
    only sampled when the move might have strained** — a pick breaks on nothing else, and a strain
    is exactly "the state did not change", so a clean single-step move proves the counter did not
    move either and needs no grab (a baseline is taken once per session and kept). A state that has
    stopped changing but keeps an unreadable plate is **settled**, not still-moving —
    nothing animates longer than ~300ms — so after `partialAfterMs` it is returned *with* its
    `UNKNOWN` entries instead of being waited on for the full 12s (the bug that once made a
    difficulty-4 chest look unsolvable and slow). `UNCHANGED` always returns the caller's
    fully-known pre-move state: moves are atomic, so a visibly unmoved mover means nothing moved.
    The animation contract lives in the injectable **`Slider.Timing`** record; `Timing.GAME`
    holds the measured values (settle 300ms, break animation 5s, give-up 12s, poll 5ms, 2 still
    frames, partial floor 1.5s), tests inject near-zeros. A strain needs no waiting; a **break**
    does, because the game discards input for ~4-5s while it swaps the pick (`awaitBreak`).
    Implements `session.MoveExecutor`.

- **`session/`** —
  - **`Skill`** — the character's lockpicking level: the strains-per-pick and whether a break resets
    the puzzle. **It is an observation, never a setting** — there is no `-Dlockpick.skill` and there
    must not be one: a character can train lockpicking at any moment, so an answer given once goes
    stale. It is also unnecessary — a break is *seen* (the lockpick counter changes at every level),
    so `LockSession` counts picks instead of estimating them, and nothing in the control flow needs a
    level at all. The level merely falls out of the same observation: the strains a pick survived
    **is** that character's strains-per-pick (`Skill.fromStrainsPerPick`), and a break that also
    resets the puzzle can only be untrained. Reported, then forgotten. It is hedged on purpose: a
    pick carries damage between locks, so the first one a run breaks may arrive already worn and
    break at a count matching no level — say so rather than round it into a guess.
  - **`LockSession`** (one per F8 press) — holds everything: `conn[p]` (null = unprobed), the
    refusal memory, the cached plan, the strain and pick counts. A single loop picks the next move —
    a discovery move while anything is unprobed, otherwise the head of the **cached** solution plan,
    re-solved from **the state the lock is actually in** whenever the lock diverges from it (or the
    model changes under it) — plays it via `MoveExecutor`, and folds the observation back in. The
    plan is **verified, never trusted**: it survives a move only if the lock lands in exactly the
    configuration it predicted, so a surprise still costs one move, not the run. A successful
    move always reveals that plate's whole row, so an observation *corrects* a wrong row rather
    than derailing the run. Nothing is reset, ever.
    - **Broken picks are expected, not fatal.** When the counter says one broke, the slider waits
      the ~4-5s animation out, the session re-homes the cursor, keeps every connection and every
      refusal, and carries on. It gives up after 5 broken picks (`MAX_PICKS`) — no lock in the
      game needs more.

- **`win32/Win32`** — Foreign Function & Memory (FFM) bindings into `user32.dll`/`kernel32.dll`:
  `GetAsyncKeyState` (F8); the DPI calls (below); `GetClientRect` + `ClientToScreen`
  (`foregroundClientRect` — the rectangle the game actually draws into, which is what the viewport is
  built from); and `GetForegroundWindow` + `GetWindowThreadProcessId` + `QueryFullProcessImageNameW`
  (the focus gate: keys go out only when `G1R-Win64-Shipping.exe` owns the focused window). This is
  the only way to capture hotkeys the app doesn't own; plain `java.awt` can't. The package stays
  clear of AWT (`Win32.Rect`, not `java.awt.Rectangle`) so nothing in it needs a display to load.

  **DPI awareness is observed, not set — and both setters fail, which is fine.** Measured with a
  scratch probe: the JVM is **already `per-monitor-v2`** before a line of our code runs (`java.exe`'s
  own manifest declares it), so `SetProcessDpiAwarenessContext(-4)` is *refused* (awareness cannot be
  set twice) and the legacy `SetProcessDPIAware()` returns TRUE while changing nothing — verified not
  to downgrade a V2 process. So `setProcessDpiAware()` calls both (a future launcher might declare
  nothing), then **reads the awareness back** via `GetThreadDpiAwarenessContext` +
  `AreDpiAwarenessContextsEqual` and returns that. Inferring it from a setter's return value reported
  "system" for a process that was really per-monitor-v2 — a lie in every bug report. (`GetProcess`​`Dpi`​`AwarenessContext`
  is *absent* from user32 on the dev machine; use the thread one.) This is the same
  observe-don't-configure rule as `Skill`.

- **`AutoLockpick`** (root) — background console app polling the F8 hotkey; each press **re-measures
  the game's window** and builds a fresh `GameScreen`/`LockReader`/`Slider`/`LockSession` from it
  (the game is normally launched *after* this tool, and a player can change resolution between two
  locks); nothing is kept between presses but the keyboard. `main` is only the composition root: the
  hotkey edge (`Hotkey`), the focus gate (`run`), the process resolution (`resolveGameProcess`), the
  viewport (`resolveViewport`), the DPI self-check (`dpiWarning`), the offline replay (`diagnose`,
  `--diagnose <png>`) and the banner are package-private and tested.

### Testing seams (four, all package-private — use them, don't add a fifth)

`java.awt.Robot` **cannot be constructed, or even subclassed, in a headless JVM**, and the tests are
headless by design. So the two classes that own a `Robot` hide it behind a one-method interface, and
their public constructors still take the `Robot` — no call site changed:

- **`vision.ScreenGrabber`** (`grab(Rectangle)`) — behind `GameScreen`. A fake serving a labelled
  frame is exactly what the live grabber hands over, so the box arithmetic, the canvas compositing,
  the lockpick-counter hash, `LivePoller` and `LiveLockView` all test headless off real frames.
- **`control.RobotKeyboard.Taps`** (`press`/`release`/`delay`) — which is how the **focus gate** is
  pinned: it must throw `FocusLost` *before* a key leaves the process, and it is re-checked on
  every tap.
- **`control.Slider.Ticks`** (`nanoTime`/`sleep`) — a virtual clock. This is what lets `SliderTimingTest`
  exercise the **measured `Timing.GAME`** contract itself (the 300ms settle floor, the 5s break
  animation, the 12s give-up) in milliseconds of real time instead of twenty seconds. Prefer it to
  inventing new near-zero timings.
- **`vision.Captures`** is now an **instance** (`Captures(Path dir, Clock clock)`), so a dump can be
  written into a `@TempDir` with a predictable name. `LiveLockView` takes one; the no-arg constructor
  is still `captures/` on the wall clock.

`Telemetry.summary` formats with **`Locale.ROOT`**: it is a diagnostic line, and "0.3s" must not
become "0,3s" on a machine whose locale says so.

### Rotation angle (measured — don't re-tune it, and don't call the rows curved)

`ROT_DEG = −30` was eyeballed, then verified by fitting all **1170 hole centroids** across the 34
labelled frames (a complete census: every one of the 195 `(frame, plate)` rows yields exactly 6
holes). Un-rotate a detected centroid back to screen space and the angle becomes a free parameter —
a hole at screen offset `(dx, dy)` from its pin lands `dx·sin θ + dy·cos θ` from the row.

- **−30° is right.** The min-max optimum is **−30.15°**, least-squares **−30.11°**. Going there moves
  the worst hole from 11.02px to 10.24px off its row. `ROW_MAX_DY` is 20px, so there is ~9px of
  headroom and the whole safe band is about **−32.5° … −28.3°** (at −28° two holes fall outside the
  gate; at −33°, twenty do). −30 sits near the middle of that band. Don't "improve" it.
- **No single angle can lay every row flat.** Fit each plate separately and the optimum slides
  linearly with how deep the plate sits in the fan — `opt(depth) = −29.80 + 0.70 · depth` (depth in
  `DEPTH_STEP` units, positive = further back), spanning −28.26° (back plate) to −31.66° (front) on a
  6-plate lock. It is perspective, not curvature. −30° is the compromise and lands on the middle
  plate. The worst holes are exactly the outermost hole of the front and back rows.
- **The rows are straight.** A least-squares line through a row leaves a residual of **1.2–2.0px**.
  The "~10px bow" this file used to claim is the leftover *tilt* of a per-plate optimum against the
  one global angle, not a bend. (2D blob detection is still required — a per-plate tilt breaks a
  single horizontal sampling line just as thoroughly — but the reason has changed.)

**The deskew is implemented** (`rowSlope` in `readState`; constants `OPT_BASE_DEG = −29.80`,
`OPT_PER_DEPTH_DEG = 0.70`): every blob is tested against a line sloped by
`tan(ROT_DEG − opt(depth(n, row)))` through its row's pin — no second image rotation, only the
row-assignment predicate. It went in when a difficulty-4 chest's arch-gap shadow — 17.4px off the
flat row line, inside the old 20px gate — combined with the walk's skip window to break a read.
Deskewed, real holes sit within **5.21px** worst-case (rms 2.52; measured across all 196 fixture
frames, 800×600..4K), while that shadow sits 23.7px off. `ROW_MAX_DY` is therefore now **12**
(viewport-scaled, floored at 4px — the same effective gate the old flat 20 already had at
800×600): headroom ≥2.3x at 4K, 1.3–2.1x at the sweep resolutions where the pixel-granularity
floor dominates. On a 7-plate lock (fit: plates want −27.69° … −31.90°; the flat gate's worst holes
would be 16.5px/14.9px) the deskew is what keeps the margin. **The predicted ~5px worst case is now
measured, and it held**: shrinking `ROW_MAX_DY` against the 21 live 7-plate frames, every row still
reads exactly at a **6px** gate and rows start failing at **5px**, so the worst real hole sits
between 5 and 6px of its deskewed line — ~2x headroom at the shipped 12. The rows that fail first
are exactly the predicted ones, the **front and back** (`7p-plate-6-drags-1`, `7p-plate-0-drags-4`);
the middle-plate sweep survives even a 5px gate, which is the perspective model showing through.
**Retuning the global angle cannot help** — moving toward one end's optimum pushes the other end
out; −30 is already near the best single angle for 7 plates too.

### Probing a lock that blocks itself

A move is **atomic**: if the plate *or anything it drags* would leave its track, the whole move is
cancelled and the pick strains. Two consequences drive `LockSession`'s whole design:

- **A plate that refuses to move is not a plate without connections.** If it isn't itself at the end
  of its track, refusing *proves* it drags a plate that is. Recording "no connections" there (an
  earlier bug) silently produced a wrong model.
- **Contrapositive:** if every plate *except* `p` is off the ends, `p` is guaranteed to move — a
  one-step drag of an interior plate always stays on the track. This is a checkable precondition, and
  it makes strain-free probing possible.

So the session never resets (a reset just recreates the blockage), slides toward centre so plates drift
off the ends, and escalates cheapest-risk-first:

1. **Free** — every other plate already interior ⇒ slide it, no strain possible.
2. **Planned** — BFS for a sequence of moves of *already-probed* plates that clears the ends for it.
   Their connections are known, so `LockSolver.applyMove` proves each move legal before a key is
   pressed. **Costs time, never a pick** — which is the trade the whole routine exists to make.
3. **Gamble** — only when neither of the above exists. A strained move is remembered and not retried
   *while every plate that was at an end back then is still at that end* — the culprit is among them,
   so the retry would fail (`isRefused`). **That memory survives a broken pick**, which is what stops
   the reset from recreating the very gamble that just failed.

**Unreadable rows are tolerated, never learned from.** A settled observation can contain
`UNKNOWN` entries — the reader refuses to guess rather than misread. (The one cause ever
diagnosed live, on a difficulty-4 chest, turned out to be an **added fake hole**, not a hidden
row: an arch-gap shadow past the end of plate 1's row passed every pixel gate and the old walk's
skip window bridged onto it. The user disproved the "plates hide rows" story by marking every
visible hole on the dump. Fixed in the reader — exact walk + deskewed row gate, pinned by
`6p-gap-shadow` — so this machinery is a safety net that should idle.) A diff with an unread
plate could record a silently wrong connection row — the exact bug class the refusal logic
exists to prevent — so the session never learns from one. While probing, the move is **undone**
(the inverse of a legal move is always legal) and remembered as occluding *from that exact
configuration* (`isOccludedHere`), to be retried after the geometry changes. While solving, the
unread entries are **filled from the model** when it explains every visible plate — each further
move re-verifies. A state that arrives unreadable with no move to undo (session start,
post-break) is **nudged** readable (`recoverFull`). And `isGoal` is never trusted from hole rows:
the loop confirms "open" from the pin pops, which no hole-row artifact can touch.

`LockSessionTest` pins this machine against `FakeGame`: the deadlocked-lock budget (one strain per
direction, then stuck, never a retry), the refusal memory across an UNTRAINED break-reset, model
self-correction after a lying observation, and the five-pick give-up.

### Cross-file conventions (keep these consistent everywhere)

These are shared contracts spanning `solver`, `control` and `session` — changing one without the
others silently breaks the automation. `KeySenderTest` and `LockSolverTest` pin most of them:

- **Key/direction semantics:** `dir +1` = LEFT = `A`; `dir −1` = RIGHT = `D`. `W` selects a
  **lower** plate index, `S` a **higher** one. The selection **starts on the last plate** (`n−1`).
- **Never assume where the selection is.** The game parks it on plate `n−1` when a lock opens and again
  whenever a **pick breaks**. Nothing on screen reveals it (the front plate is drawn dark whichever
  plate is selected). A W/S press costs ~10ms, so `keys.endCursor(n)` buys certainty for almost
  nothing: call it at the start of a session and after **every** strain or reset.
- **Offset convention:** `0` centered, **positive = LEFT** of center, **negative = RIGHT**; track is
  `[−MAX_OFFSET, +MAX_OFFSET]` (7 positions, ±3; `LockModel.MAX_OFFSET`). Equivalently, offset =
  *how many holes sit left of the pin*, minus 3 — which is what `LockReader.readState` counts.
  Sliding a plate left raises its offset, matching `LockSolver.applyMove`'s `delta[piece] += dir`
  for `dir +1` = `A` = LEFT.
- **Connections are directed per-mover:** `connections[p]` lists what moving `p` drags, each entry a
  `Connection(target, NORMAL|INVERTED)`. No cascade — a dragged plate's own row does not fire.
- **The lockpick counter is thresholded, so the gamma reaches the break detector too.**
  `GameScreen.PICKS_DARK_MAX = 110` splits the digits from the panel behind them — and the digits move
  with the slider: 74 at the calibration, **101 at gamma 3.2**, nine levels under the threshold. A
  little more brightness and every pixel in the box reads "light", the hash stops depending on the
  digits, and **a broken pick becomes invisible** — silently, in the one signal `LockSession` trusts
  to notice it. `pickCounterFingerprint` maps through the `Tone` first. Don't unpick that.
- **Screen capture needs ALL THREE.** Two are the DPI half: `Win32.setProcessDpiAware()` (before any
  AWT init) **and** `-Dsun.java2d.uiScale=1`. The dev display is 4K at 200% scaling; either alone
  yields a wrong (scaled/black) capture. See `.claude` project memory `screen-capture-dpi` for
  detail. `AutoLockpick.dpiWarning` now checks the flag at startup (the AWT default transform must
  be 1.0) and says so loudly, because nothing downstream can: with `uiScale != 1` the `Robot` still
  returns an image of exactly the requested *size*, merely resampled from the wrong region — every
  size assertion passes and only the reader notices, by finding nothing.
- **The third is the viewport, and it is the game's WINDOW — never the screen.** `Viewport` carries an
  origin; `AutoLockpick.resolveViewport` measures the focused window's client rect
  (`Win32.foregroundClientRect`), per F8 press, falling back to the display only when there is no
  plausible window. Measuring the display and assuming the game filled it was a real shipped bug: a
  player at 2560×1440 inside a larger desktop got "no lock detected" over a screenshot that looked
  perfectly fine, because a wrong viewport **cannot fail loudly** — `LockReader.detectPins` clamps
  its scan box to the frame, finds nothing, and returns -1. `WindowedGameTest` pins both halves.
  **Only `GameScreen` ever applies the origin**; every other coordinate in the vision layer is
  view-local, which is why the reader needed no change.

## Automated-mode status / gates

**The reader is done**: `LockReaderTest` replays every labelled frame in `src/test/data/frames/` —
the 34-frame 4K calibration census (3 plate-count frames plus 31 offset frames across four slide
sequences, with a `readCentered` cross-check), the `6p-gap-shadow` regression frame (a live
failure dump whose labels the user established by marking every hole), the **21-frame 4K 7-plate
census** (three sequences, `7p-*`), the 161-frame resolution sweep (23 display modes × 7
states), and the **27-frame `gamma` corpus** (the game's slider end to end — see below).
`CaptureBoxTest` proves the capture box contains everything the reader samples — any plate
count, any offsets, any viewport — with a safety belt. The full suite is **778 tests** across
solver/vision/control/session and the root, and **every class outside `win32` is covered** (94.2%
line / 91.4% branch, gated at 92/90 — see "Testing seams" below).

**The gamma slider is covered end to end** (`gamma/`, 27 frames, `src/test/data/frames/gamma/labels.txt`).
The same 7-plate chest, the same key protocol, replayed at every setting from 1.2 to 3.2 — plus the
dark extreme at 2560×1440, the reporter's own configuration. Raw, the ends fail in opposite ways; read
through the `Tone` the frame carries, **all 21 sweep frames give the same labels as the calibrated
fixtures**, with the pin-pop and the hole count agreeing on every plate. Only each sweep's `step-0`
went into fitting a curve, so steps 1–6 are a straight holdout. Two gates guard the calibration
itself: every frame at the calibrated gamma must measure as the **identity** (so the corpus keeps
vouching for the reader's constants unchanged), and no real gamma setting may trip the off-family
warning.

**Verified live** against Gothic 1 Remake (`G1R-Win64-Shipping`):

- The FFM bindings work; the game window is borderless at `(0,0)-(3840,2160)`; DPI-aware `Robot`
  capture returns true 3840×2160.
- **The focus gate keys on the process, not the window title** (`Win32.foregroundProcessName()` →
  `G1R-Win64-Shipping.exe`). Titles are unsafe: a Chrome window titled
  `"gothic 1 remake lockpicking levels - Google Search"` passes any sensible title substring — verified
  live that it passes the old gate and is rejected by the new one. Override with `-Dgame.process=`.
- **The game accepts `Robot` keys.** No `SendInput`/scancode fallback needed.
- **`A` raises the offset**, matching `applyMove`'s `delta[piece] += dir`, and `R` resets.
- **W/S clamp** at the ends, so `KeySender.endCursor` is sound.
- On a freshly opened lock the selection is on plate `n−1`: one `A` with no navigation moved
  `[3,-1,2,0,-3] → [2,-1,1,0,-2]`, which only `applyMove(plate 4, left)` explains.
- A 5-plate **chest** lock (`START = {3,-1,2,0,-3}`, plate 2 dragging four plates): **F8 learns it and
  opens it in ~2.9s with 0 strains.** Four separate probes produced byte-identical models.
- A **difficulty-4 chest** (6 plates) renders at the same calibrated fan — and exposed the one
  reader bug ever found live: in some configurations its inter-plate arch gap casts a hole-shaped,
  lattice-aligned shadow past the end of plate 1's row, and the old walk's skip window bridged
  onto it (misdiagnosed at first as the arch *hiding* the row; the user disproved that by marking
  every visible hole on the dump). Fixed by the exact walk + deskewed row gate; the frame is the
  `6p-gap-shadow` fixture. It also motivated the session's UNKNOWN tolerance, which stays as a
  safety net. Its lock model includes a plate dragging three others.
- A **7-plate chest** (also difficulty 4) reads correctly with **no reader change** — the fan
  geometry and rotation angle were extrapolated and turned out right on first contact. Its 21
  labelled frames are the `7p-*` fixtures; see "7-plate locks" below and `LockReaderTest`'s javadoc
  for how the labels were established without a reader that could yet be trusted at 7 plates. The
  short version, and the method to reuse for any future unlabelled lock: **probe the connections
  first, then predict every state from the model plus the keys sent, and capture only to confirm**.
  A refused move leaves the lock untouched, so probing from one base configuration costs a strain
  and nothing else — the whole 7-plate model cost 3 strains and not one pick.

### Animation timing (measured — do not re-guess these)

Full numbers, method and spread are in `docs/INTERNALS.md` "Measured timings". The load-bearing facts:

- **A slide**: plates start moving ~112ms after the key, stop ~207ms, and the reader sees a stable
  correct state by ~269ms. Mid-flight the moving plate reads **`UNKNOWN`** — its holes are between
  positions, so the hole walk finds five, not six. A *transiently* unreadable plate means *"still
  moving"*; one that stays `UNKNOWN` in a state that has stopped changing for ~1.5s is **settled
  with an unresolved row** — never *"give up"* either way. Nothing has moved before ~90ms, so
  an unchanged lock means nothing until then.
- **A rejected move never moves a plate, and does not block input.** A legal slide sent **0ms** after a
  strain still lands (measured at 0, 0, 350, 600, 900ms — all landed). The shake is invisible to the
  reader: no `UNKNOWN` frames, no offset change. So a strain is exactly "the state did not change", and
  needs no waiting. (The one probe ever swallowed coincided with a *pick break*, whose animation does
  discard input.)
- **Slides are queued, not dropped.** Six sent back-to-back at a 0ms gap all landed. A slide registers
  with a 0ms hold. **W/S is free**: 0ms hold, 0ms gap, no animation. `RobotKeyboard`'s 5/5ms is margin.
- **What the game *does* discard is input sent during a reset animation.** A slide 0ms after `R`
  vanishes. This — not a dropped key — is what broke the old open-loop `Executor`: it pressed `R`,
  then slid ~830ms later while the reset was still running.
- **A reset slides every plate home in parallel**, so it lasts as long as the *furthest* plate's
  travel: ~313ms per step (1 step ≈ 212ms, 6 steps ≈ 1774ms), motion starting ~64-122ms after the key.
- **A broken pick is invisible in the lock above skill level 0** (see `Skill`): only untrained resets
  the puzzle. Basic and master leave the plates exactly where they were. **Do not try to detect a break
  by watching the plates** — that dead end cost several of the player's picks.
- **Detect it from the remaining-lockpicks counter**, the white box under the lock at a fixed screen
  position. It only ever decrements, so a pixel-level change is enough — no OCR.
  `GameScreen.pickCounterFingerprint()` hashes it; `Slider` samples it around every strain. Verified
  live: at master the counter changes on exactly the 6th strain.
- **After a break the game discards input for ~4-5s** while it swaps the pick. Two strains sent into
  that window vanished, which made a second break look like it needed eight strains instead of six.
  `Slider.awaitBreak` waits it out, then re-reads.
- **Pressing a plate into its own wall strains the pick** exactly like any other rejected move. The
  tool never does it: `applyMove` returns null for such a move, so neither `solve` nor `pickDirection`
  can emit one.
- **Where a run's wall clock goes** — measured by `control.Telemetry`, which `AutoLockpick` prints
  after every F8. On the live difficulty-4 chest (6 plates, 33 slides, 22.1s) it was: **11.3s**
  watching slides settle (323ms each — the ~300ms animation floor, so the poll loop is already
  tight), **9.1s re-solving the same lock 22 times** (now cached; a solve is ~430ms), 0.8s of
  lockpick-counter grabs (now only taken when a move might have strained), 0.9s of keys. The
  slide *count* is near-irreducible — 22 of those 33 are the solution itself, 11 are discovery — so
  a big lock is simply a long run. **Below the animation floor there is nothing left to win without
  giving up the observe-every-move contract.**
- **Capture, not image processing, is the cost.** A full 4K `capture()` is ~79ms; `readState` on it is
  ~8ms. `GameScreen.captureLock()` grabs only the lock's 1300x1120 box (~23ms; enlarging it from
  1200x1000 for the safety belt measured cost-free — the grab's fixed overhead dominates) and
  composites it into a reused full-frame canvas, so every absolute coordinate still works. Poll with
  that, never `capture()`.

Remaining:

- **A frame darker than the gamma slider alone can make it is still unexplained.** The user who
  reported the bug sends frames reading **ink 13 beside a white of 184**, where the measured family
  says 254 at that ink. The slider moves both anchors together, so a second setting is dimming their
  picture — `m_ColorBrightnessOffset` (which sits in `GameUserSettings.ini` right beside `m_Gamma`),
  HDR, or a display profile. `Tone` says so in the sidecar and applies the nearest measured curve; it
  is not always enough (on one of their three frames a pin stays too faint to see, and the reader then
  correctly reports *nothing* rather than the wrong lock — see the fan-lattice dead end). **Two dumps
  taken with `m_ColorBrightnessOffset` at its extremes came back byte-identical to the baseline**, so
  either the setting does not reach the frame, or it did not take effect — that is the thread to pull.
  What is needed is a real `--dump` (PNG + sidecar) from that user, not a JPEG re-upload.
- **Non-4K resolutions are sweep-validated, with caveats.** A live front-plate sweep of one
  5-plate lock reads exactly right at all 23 dev-machine display modes (800×600..4K, spanning
  16:9/16:10/4:3/5:4) — pinned by `LockReaderTest`'s 161 sweep fixtures. Caveats: one lock, one
  room; 4/6/7-plate fans and other rooms are validated only at 4K; and threshold margins shrink
  with scale — one transient false pin-pop was seen live at 1280×1024 (single poll; the saved
  frame reads correctly and the two-identical-frames settle rule absorbs it), so treat sub-720p
  with respect. The sweep method, if it needs repeating: rehearse at 4K with the validated reader
  to learn a strain-free key protocol and the true state labels, then replay the identical keys
  from a fresh `R` at every other mode — game state depends on keys, not pixels, so the labels
  transfer. See `3840x2160/front-plate-sweep/labels.txt`.
- **7-plate locks are now verified at 4K** (a difficulty-4 chest; `START = {0,-2,-3,3,3,2,3}`). The
  extrapolations all held on first contact — no reader change was needed: the fan geometry
  (`pinPosition`) finds all seven pins, `ROW_MAX_DX = 345` covers the front row even though it sits
  one fan step nearer the camera, and **no row ever read `UNKNOWN`** across 21 frames. The rotation
  angle's predicted ~5px worst-case is measured and holds (see "Rotation angle"). Still open: 7-plate
  at **non-4K** resolutions (only the 5-plate lock has a resolution sweep) — the risk there is
  scaling, not fan geometry, and it is the same risk 4- and 6-plate locks already carry.
  Its connections, for reference: `0→4(N)`, `3→1(N),4(N)`, `6→1(N)`; plates 1, 2 and 5 drag nothing.
- **A row can still fail to read in a settled frame — treat that as a reader bug to fix, not a
  fact of the lock.** The one case diagnosed live (the difficulty-4 chest) was an arch-gap shadow
  the old walk mistook for a seventh hole — fixed, pinned by `6p-gap-shadow`. `readState` reads a
  row it cannot resolve as `UNKNOWN` rather than guessing; the session undoes/fills/nudges around
  it (see "Unreadable rows" above) rather than learning from it — a safety net that should idle,
  and any frame that trips it belongs in the fixtures. Confirm "solved" from `readCentered` (the
  pop is exact and needs no hole rows), never from hole counting — the main loop enforces this at
  the goal.
- **A popped pin's centroid sits ~10px above its fan position** (measured: 743→734, 780→770,
  816→806). Well inside `MATCH_MAX_DIST`, but don't tighten that constant without accounting for it.
- **The pop is an animation.** A pin caught mid-rise measures between the flat (~150-180px) and
  popped (~380-450px) sizes, so a single frame taken right after the last keypress can miss it. Poll.

### Hotkey choice (don't change this casually)

`GetAsyncKeyState` **observes** the hotkey; it does not swallow it, so F8 also reaches Gothic. That
ruled out **F5** (quicksave) and **F9** (quickload) outright, and **F11**/**F12** (fullscreen toggle,
Steam screenshot) by prudence. F8 is the only hotkey.

### Identifying which plate is selected

You cannot read it off a frame, and you cannot infer it from "the first plate whose offset changed" —
a Normal connection drags a plate the same direction as the mover, so a dragged plate with a lower
index looks exactly like the mover. (An old `Diagnostics` check got this wrong.)

The sound method: press one slide with **no navigation**, then match the observed transition against
`LockSolver.applyMove` for every `(plate, dir)`. Exactly one usually fits. That is how the "selection
starts on `n−1`" fact was established.

Confirmed from the screenshots: keys are `A/D` left/right, `W/S` up/down, `R` reset (unused by the tool);
4–7 plates; 7 holes per plate, middle hole the target; the pin stays put and only **pops up when
centered** (it does not slide with the offset).

### Dead ends — don't re-derive these

- **A tone curve cannot be extrapolated past its anchors. This was tried and it destroys the reader.**
  The obvious shortcut is to fit a two-parameter `gain × power` curve through the pick-counter
  panel's two *dark* anchors (28 and 75) and call it the gamma. Measured against a real 3.2 frame, it
  maps observed **255 → 179**, crushes the pin highlights, and leaves **1** detected pin where the raw
  frame had **7** — *worse than doing nothing*. Two closely-spaced dark anchors say nothing about the
  far end of the range. `Tone`'s curves are therefore **measured at every level** from matched frame
  pairs, and it **clamps** rather than extrapolates past the ends of the slider.
- **A smaller fan is not a smaller lock.** Plate `i` of `n` sits at `(mid − i)` depth steps with
  `mid = (n−1)/2`, so the fans of `n` and `n+2` **share a lattice**: a 6-plate lock's pins
  (`±2.5, ±1.5, ±0.5`) *always* cover a 4-plate fan (`±1.5, ±0.5`), and a 7-plate always covers a
  5-plate one. `detectPlateCount` disambiguates by taking the largest fan that fits — which works
  only while **every** pin is seen. Lose the faintest one (which is exactly what a dark gamma does)
  and it does not fail: it silently answers the **smaller lock**, handing the session a model with
  the wrong number of plates to drive into walls until the picks run out. `fanFits` therefore also
  checks one step past each end. **But only on a clean frame** — at 2048×1536 a single pin fragments
  into several blobs (a real 5-plate lock yields **15**), one lands on a neighbouring fan position,
  and the first version of this check rejected six perfectly good frames. Hence `CLUTTER_ALLOWANCE`.
  Blob *size* cannot separate the two cases: the genuine 5-plate lock's pins measured `[54,53,56,98,42]px`
  with a 54px blob on the extension, the misread 6-plate one `[58,29,39,41]px` with a 40px extension.
- **"No lock detected" over a frame that looks fine means the COORDINATES are wrong, not the pixels.**
  Do not go looking at thresholds. A viewport that describes the wrong rectangle cannot fail loudly —
  `detectPins` clamps its scan box to the frame, finds nothing, and returns -1, while the dump (the
  full frame) shows the lock exactly where the player sees it. This shipped: `AutoLockpick` measured
  the *display* and assumed the game filled it, so anyone playing below their desktop resolution, in
  a window, or on a second monitor was reported "no 4-7 plate lock detected" at a resolution the test
  suite proves the reader handles. Fixed by measuring the window (`Win32.foregroundClientRect`);
  reproduced headlessly in `WindowedGameTest`. **The reader was never at fault, and the reporter's
  resolution was a red herring** — before touching a constant, run `--diagnose` on the frame: if it
  reads correctly at `Viewport(png.width, png.height)`, the live viewport was wrong and that is that.
- **Pin position does not encode offset.** A "pin x → offset" reader was built and scrapped; the pin
  never moves. Only its *size* changes, and only at offset 0.
- **Drive-to-centre probing is impossible.** A connected plate can stop another from ever reaching
  centre, so you cannot identify an offset by counting steps to the pop.
- **One rotation cannot lay every hole row flat**, so a straight sampling centerline fails no matter
  how it is fitted — and so does a per-plate spacing (`HOLE_SPACING`/`SPACING_SCALE`), because
  spacing runs 41–54px with plate depth and position along the row. Find holes as 2D blobs instead.
  Note the rows themselves *are* straight (residual 1.2–2.0px); what defeats a sampling line is that
  each plate's row has its own tilt. See "Rotation angle" — and don't re-measure it, it is measured.
- **Dark alone doesn't mean hole.** The plate's bent end-tab holds hole-sized shadows. Real holes
  bottom out at luminance 0–50, shadows at 76–96 (`HOLE_MAX_MIN_LUM` / `HOLE_MAX_MEAN_LUM`) — but
  that separation is **room-dependent**: in the difficulty-4 chest's room an arch-gap shadow
  bottomed at 57 while a real plate-backed hole read 45, so no luminance threshold can split them.
  Geometry — the deskewed row line and the exact walk — is what rejects such shadows.
- **Don't reset the lock between probes.** It looks tidy and it is actively harmful: the reset
  restores exactly the blockage you are trying to escape, so a plate blocked at `START` is blocked
  forever. Keep each move; slide toward centre; the lock unblocks itself.
- **Don't conclude anything from an unchanged frame taken too soon** — see "Animation timing".
- **Never play a solution open-loop**, and don't blame the wrong thing when it fails. The old
  `Executor` computed the move list and typed every key at full speed after pressing `R`. It lost
  exactly one slide, leaving `[1,0,1,0,-1]` — the full solution minus one plate-4 slide. The obvious
  explanation (a second slide dropped inside the first slide's animation) is **wrong**: slides are
  queued, and six back-to-back at 0ms all land. What actually happened is that the *first* slide was
  pressed ~830ms after `R` while the reset was still animating, and **input during a reset is
  discarded**. From there the lock was not in the state the model believed, so the following moves were
  illegal, each strained, and the pick broke. Press → observe → compare against `applyMove` → continue.
- **Never assume where the selection is after the game moves the plates for you.** A broken pick
  re-homes it to the lowest plate. A throwaway harness that kept its own cursor across a reset drove
  the wrong plate into a wall repeatedly, strained a dozen times and cost the player several picks.
  `LockSession` calls `keys.endCursor(n)` after every strain and every reset; at ~10ms a press,
  certainty is nearly free.
- **The selected plate is not visually marked** — or at least, not by being dark. The **front** plate
  is always the dark one (it holds the keyhole and the pick). See
  `src/test/data/frames/6p-plate-0-drags-1/step-0.png`: plate 0 is the one being slid, and the front
  plate is still dark. So there is no cursor to read off a frame; it has to be tracked
  (`KeySender.cursor`) from a known starting point.
- **Don't reset just to re-home the cursor or "start clean".** Probing leaves the lock in a perfectly
  good state, and it is a *closer* state — solving from it is what F8 does. A reset costs its own
  animation plus every plate's travel back to `START`.
- **"No plate reads a definite non-zero offset" is not "the lock opened."** It is also true of a frame
  where nothing is readable at all — which is what a pick break looks like, every plate moving at once.
  An old verifier cheerfully reported "the lock has most likely opened" while the minigame was gone.
  Check `detectPlateCount` and `readCentered` before drawing the happy conclusion.
