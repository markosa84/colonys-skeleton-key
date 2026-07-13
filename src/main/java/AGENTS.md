# Automation / screen-reader notes

- **`LockReader` has a regression gate — run it after any change:** `gradlew test` (wired into
  `check`/`build`) replays every labelled frame in `src/test/data/frames/` through
  `LockReaderTest` and must stay green on **every one of the 244** (the 34-frame 4K calibration
  census, the `6p-gap-shadow` regression frame, the 21-frame 7-plate census, the 161-frame
  resolution sweep, and the 27-frame gamma corpus). Every constant in the reader is fitted to those
  frames. To debug offline, `new LockReader(Viewport.REFERENCE)` is safe anywhere: it is pure
  image analysis (all Robot captures live in `GameScreen`), so you can feed `ImageIO.read(...)`
  images straight into `detectPlateCount` / `readCentered` / `readState`. Pixel constants in the
  vision classes are 4K reference values mapped through `Viewport` at construction - edit the
  reference constants, never the scaled instance fields.

- **Every colour and luminance constant assumes the CALIBRATED gamma, and `Tone` is what makes that
  true.** `new LockReader(viewport)` means "this frame is already at gamma 2.7" — right for the
  fixtures, and right for nothing else. Live, `AutoLockpick` measures the gamma off the frame once per
  F8 (`Tone.estimate`) and passes it to *both* `LockReader` and `GameScreen`; the reader maps every
  pixel through it inside `isPin` and the hole scan, and `GameScreen` does the same before
  thresholding the lockpick counter. **If you add a pixel gate, it must sit behind `tone.map(...)`
  too** — an absolute number applied to a raw frame is a number that is only true for the author's
  screen. The curves are measured (`tools/ToneTable.java`), never fitted: see CLAUDE.md's dead end on
  extrapolating one, which is a mistake that has already been made here and it destroys the pins.

- **Plate index orientation:** index `0` = back-most plate (top of the fan), `n-1` = front (the dark
  plate holding the keyhole/pick). The user's "panel 1..N" numbering is the REVERSE (front→back), so
  their "5th panel" of a 5-plate lock is our index 0. The fixture directory names use CODE indices.

- **You can drive the live game yourself** without the F8 hotkey loop (which never returns). Write a
  one-shot `main` that calls `Win32.setProcessDpiAware()`, checks
  `Win32.foregroundProcessName().equalsIgnoreCase("G1R-Win64-Shipping.exe")`, wires the graph the
  way `AutoLockpick.main` does (`GameScreen`/`LockReader` → `LiveLockView`/`LivePoller`,
  `RobotKeyboard` → `KeySender`, `Slider` with `Timing.GAME`), and calls
  `new LockSession(...).run()` directly. Force the game to the front first from PowerShell —
  `AppActivate` is unreliable; use `AttachThreadInput` + `ShowWindow(SW_RESTORE)` +
  `SetForegroundWindow`, and select it **by process name**, never by title: a Chrome window about the
  game matches any title test. Always keep the foreground check — without it a stray `A`/`D` lands in
  whatever window is focused.

- **A harness that presses keys must never trust its own cursor across a reset.** A pick break re-homes
  the selection. A scratch tool that assumed otherwise drove the wrong plate into an illegal move over
  and over, strained a dozen times and cost the player several lockpicks. Re-home (`endCursor`), and
  verify every move against `applyMove` before pressing and against the observed state afterwards.

- **The session is fully drivable by fakes.** `LockSession` depends only on the seam interfaces it
  owns (`LockView`, `MoveExecutor`, `CursorKeys`) — see `FakeGame` in the test tree, which simulates
  a lock with hidden connections and the game's real strain/break/reset rules (knobs: `lieAtPlay`,
  `hiddenPlate`/`hideWhen`, `unreadableAtPlay`, `unreadableOnceOpen`). Prefer extending that over
  building a live harness.

- **So is everything else — the `Robot` is behind a seam.** `java.awt.Robot` cannot be constructed
  *or subclassed* in a headless JVM, which is why `GameScreen` takes a `ScreenGrabber` and
  `RobotKeyboard` a `Taps` (both package-private; the public constructors still take the `Robot`, so
  no call site changed). A fake `ScreenGrabber` cropping a fixture frame is byte-for-byte what the
  live grabber returns, so `GameScreen`/`LivePoller`/`LiveLockView` test against real frames.
  `Slider.Ticks` is the same idea for the clock: a virtual one lets the tests run the **real**
  `Timing.GAME` (5s break animation, 12s give-up) instantly. Don't add a near-zero `Timing` where a
  fake clock will do, and don't call something "only checkable live" before checking these.

- **Measure the game's animation, don't guess it.** Measured: plates move 112→207ms after the key,
  stable read at 269ms; the moving plate reads `UNKNOWN` while it travels. Sampling too early turns
  a successful move into a phantom "strain" and corrupts the session's idea of the state. A plate
  that STAYS `UNKNOWN` in a state that stopped changing (~1.5s) is settled with an unresolved
  row - `Slider` returns that state with its `UNKNOWN` entries and the session works around it.
  Full tables: `docs/INTERNALS.md` "Measured timings".

- **A strain does not block input; a pick *break* does.** A legal slide sent 0ms after a rejected move
  still lands, and the shake is invisible to the reader (no `UNKNOWN`, no offset change). Only the
  ~4-5s pick-replacement animation swallows keys. Don't add waiting around strains.

- **`capture()` is ~79ms, `readState()` is ~8ms** — the screenshot dominates every poll. Poll with
  `GameScreen.captureLock()`, which grabs the lock's 1300x1120 box (sized with a safety belt;
  `CaptureBoxTest` proves it contains everything the reader samples at every viewport) and
  composites it into a *reused* view-sized canvas so the reader's coordinates still land.
  Never dump that image through `Captures`: every pixel outside the box is stale. (`LiveLockView`
  saves the full `capture()` for exactly this reason, and a test pins it.)

- **Everything in the vision layer is VIEW-local; only `GameScreen` knows where the window is.** The
  `Viewport` carries the game's origin on the virtual desktop, and `GameScreen` is the one class that
  adds it — it keeps each box twice, once as the reader sees it and once translated for the grabber.
  So an image reaching `LockReader` always has the game's top-left at (0,0), whether the game fills
  the screen or sits in a corner of it. Don't leak the origin past `GameScreen`; the reader needed
  **no change at all** for windowed play, and that is the property to preserve.

- **A wrong viewport is silent by construction.** `detectPins` clamps its scan box to the frame, so
  a viewport describing the wrong rectangle yields "no pins" → `detectPlateCount` = -1 → "no lock
  detected", over a dumped frame in which the lock is plainly visible. That is a *feature* (better
  than an out-of-bounds crash) and a trap: it makes an acquisition bug look exactly like a reader
  bug. `LockReader.describe` exists to tell them apart, and `WindowedGameTest` pins the case that
  shipped. Before suspecting a threshold, run `--diagnose` on the frame.

- **A row that fails to read in a settled frame is a reader bug to fix, not a fact of the lock.**
  The one live case (a difficulty-4 chest) was an arch-gap shadow the old walk mistook for a
  seventh hole - fixed by the exact walk + deskewed row gate, pinned by the `6p-gap-shadow`
  fixture; drop any new failure dump into `src/test/data/frames/` the same way. Until fixed, such
  a row reads `UNKNOWN` and the session tolerates it: **never learn a connection row from a diff
  that contains `UNKNOWN`** - undo the move and probe again from a different configuration
  (`LockSession.partiallyObserved` is the pattern). And confirm "solved" from `readCentered`,
  never from hole counting - the pop is the game's own exact signal.

- **The slide-sequence fixtures are usable ground truth**, because each frame differs from the
  previous by exactly one plate step. Chain the arithmetic from a known frame and a reader that is
  wrong anywhere breaks the whole sequence. That is how `LockReaderTest`'s expectations were derived
  from a single user-labelled frame (`5p-plates-1-2-opposed/step-0`).

- **Pin detection is boxed to `RX0..RY1` on purpose:** on the full 4K frame the warm wooden
  background (candles/crates/torches) has far more warm pixels than the brass pins, so a global scan
  locks onto clutter. The lock is framed at a FIXED screen spot (`FAN_CENTER`+`DEPTH_STEP`, verified
  across 4 rooms), which is what makes the fixed box — and the whole reader — work.

- **A missed pin does not read as "no lock". It reads as a SMALLER lock.** The fans of `n` and `n+2`
  share a lattice, so a 6-plate lock's pins cover a 4-plate fan exactly (and a 7-plate covers a
  5-plate). `detectPlateCount` takes the largest fan that fits, which is only safe while every pin is
  seen — lose the faintest and it silently answers the smaller lock, which is far worse than finding
  nothing: the session then drives a model with the wrong number of plates. `fanFits` checks one step
  past each end, but **only on an uncluttered frame** (`CLUTTER_ALLOWANCE`): at low resolutions one pin
  fragments into several blobs and a fragment lands on a neighbouring fan position. If you touch pin
  detection, the frame to test against is `2048x1536/front-plate-sweep` (15 blobs for 5 pins).

- **Approaches already tried and scrapped** (CLAUDE.md "Dead ends" has the detail): a "pin x →
  offset" reader (the pin never moves); drive-to-centre probing (a connected plate can block a plate
  from ever centring); model-fitting hole positions from a per-plate spacing along a straight sampled
  line (no single rotation flattens every row, and spacing varies 41–54px with depth and position);
  resetting the lock between probes (the reset restores the very blockage you need to escape); and
  playing a solution open-loop.

- **The open-loop failure was misdiagnosed for a whole session — don't repeat the diagnosis.** Slides
  are **queued, not dropped** (six back-to-back at a 0ms gap all landed; a 1ms hold suffices). What the
  game discards is input sent **during a reset animation**, and the old executor pressed `R` then slid
  ~830ms later while the reset still ran. Symptom looked identical to a dropped key.

- **`ROT_DEG` is measured over a 1170-hole census — don't re-derive or re-tune it.** The rows are
  *straight* (≤2px residual); what no single angle can fix is that each plate's row projects at its
  own angle (`opt(depth) = −29.80 + 0.70·depth`) — which is why `readState` tests every blob
  against its row's own deskewed line (`rowSlope`, `ROW_MAX_DY` 12px scaled) and walks the lattice
  in single steps first, bridging a gap only when a hole is genuinely missing. CLAUDE.md
  "Rotation angle" has the numbers and the history.

- **Every plate move goes through `Slider`.** It presses the key, watches through its `LockPoller`,
  and reports `MOVED`/`UNCHANGED`/`RESET` plus `pickBroke`. Don't add a second path — the animation
  contract (`Slider.Timing`, and the `awaitBreak` wait that lets the pick-replacement animation
  finish) belongs in exactly one file.

- **The cursor cannot be read from a frame** — the dark plate is the front one, not the selected one.
  And the game re-homes the selection whenever a pick breaks. A W/S press costs ~10ms, so
  `endCursor(n)` is nearly free: call it after every strain and every reset rather than reasoning about
  where the selection "should" be.

- **A broken pick is invisible in the lock** unless the player is untrained (only level 0 resets the
  puzzle). Detect it from the **remaining-lockpicks counter** — a fixed white box under the lock whose
  pixels change when the number drops. `GameScreen.pickCounterFingerprint()`. Trying to see a break in
  the plates is a dead end that costs the player real picks. And after a break the game swallows input
  for ~4-5s while it swaps the pick.

- **Two cost models, one search.** `Cost.KEYPRESS` (1,1) minimizes keys; `Cost.WALLCLOCK` (300,10) is
  milliseconds, for the automation. `solve(m)` defaults to KEYPRESS and cursor `n-1`; the automation
  must call `solve(m, state, cursor, WALLCLOCK)`. The two have **never produced a different plan**
  (0 divergences over 200 random 6-plate starts, now pinned by `LockSolverTest`) because the slide
  count is fixed by the connection algebra — don't expect the weighting to speed anything up.

- **A plate that won't slide has connections, it doesn't lack them.** Moves are atomic, so a plate
  that is not itself at the end of its track can only be blocked by something it drags. The useful
  contrapositive: if every plate except `p` is off the ends, `p` is guaranteed to move. `LockSession`
  builds on exactly that.
