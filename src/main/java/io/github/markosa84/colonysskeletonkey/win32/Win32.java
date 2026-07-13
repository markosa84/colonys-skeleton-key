package io.github.markosa84.colonysskeletonkey.win32;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Thin Win32 bindings via the Foreign Function &amp; Memory API (stable since JDK 22). Used for
 * things {@code java.awt} cannot do: capturing global hotkeys the app doesn't own, making the
 * process DPI-aware so screen capture yields true device pixels, measuring the focused window's
 * client area so the tool reads the rectangle the game actually draws into, and identifying the
 * process that owns that window as a safety gate before sending keys.
 *
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED} to suppress native-access warnings
 * (already set in the Gradle {@code run} task).
 */
public final class Win32 {

    /**
     * The one hotkey we poll.
     *
     * <p>Gothic binds <b>F5</b> to quicksave and <b>F9</b> to quickload, and we only <i>observe</i>
     * hotkeys rather than swallowing them, so neither may be used. F11/F12 are avoided too: F12 is
     * Steam's screenshot key and F11 a common fullscreen toggle.
     */
    public static final int VK_F8 = 0x77;

    /** {@code PROCESS_QUERY_LIMITED_INFORMATION} - enough to read another process's image path. */
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());

    private static final MethodHandle GET_ASYNC_KEY_STATE =
            handle(USER32, "GetAsyncKeyState", FunctionDescriptor.of(JAVA_SHORT, JAVA_INT));
    private static final MethodHandle SET_PROCESS_DPI_AWARE =
            handle(USER32, "SetProcessDPIAware", FunctionDescriptor.of(JAVA_INT));
    /** Windows 10 1703+. Absent on older builds, hence the {@link Optional} and the fallback. */
    private static final Optional<MethodHandle> SET_PROCESS_DPI_AWARENESS_CONTEXT =
            optionalHandle(USER32, "SetProcessDpiAwarenessContext",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
    /** Windows 10 1607+. How the awareness is <b>read back</b> rather than assumed. */
    private static final Optional<MethodHandle> GET_THREAD_DPI_AWARENESS_CONTEXT =
            optionalHandle(USER32, "GetThreadDpiAwarenessContext", FunctionDescriptor.of(ADDRESS));
    private static final Optional<MethodHandle> ARE_DPI_AWARENESS_CONTEXTS_EQUAL =
            optionalHandle(USER32, "AreDpiAwarenessContextsEqual",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /** The DPI_AWARENESS_CONTEXT pseudo-handles, by name: index i is the handle -(i+1). */
    private static final String[] AWARENESS =
            {"unaware", "system", "per-monitor", "per-monitor-v2", "unaware-gdi-scaled"};
    private static final MethodHandle GET_FOREGROUND_WINDOW =
            handle(USER32, "GetForegroundWindow", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle GET_CLIENT_RECT =
            handle(USER32, "GetClientRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CLIENT_TO_SCREEN =
            handle(USER32, "ClientToScreen", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID =
            handle(USER32, "GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle OPEN_PROCESS =
            handle(KERNEL32, "OpenProcess", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle QUERY_FULL_PROCESS_IMAGE_NAME_W =
            handle(KERNEL32, "QueryFullProcessImageNameW",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CLOSE_HANDLE =
            handle(KERNEL32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * A window's client area in virtual-desktop pixels: what the game actually draws into, which is
     * the whole primary display only when the player runs it that way.
     *
     * <p>Deliberately not a {@code java.awt.Rectangle} - this package is the FFM boundary and stays
     * clear of AWT, so that nothing here needs a display to load.
     */
    public record Rect(int x, int y, int width, int height) {}

    private Win32() {}

    private static MethodHandle handle(SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment sym = lib.find(name)
                .orElseThrow(() -> new IllegalStateException("symbol not found: " + name));
        return LINKER.downcallHandle(sym, fd);
    }

    /** Like {@link #handle}, for an entry point that may not exist on the running Windows build. */
    private static Optional<MethodHandle> optionalHandle(SymbolLookup lib, String name,
            FunctionDescriptor fd) {
        return lib.find(name).map(sym -> LINKER.downcallHandle(sym, fd));
    }

    /** True while the given virtual key is physically down (high-order bit of GetAsyncKeyState). */
    public static boolean isKeyDown(int vKey) {
        try {
            short state = (short) GET_ASYNC_KEY_STATE.invokeExact(vKey);
            return (state & 0x8000) != 0;
        } catch (Throwable t) {
            throw new RuntimeException("GetAsyncKeyState failed", t);
        }
    }

    /**
     * Marks the process DPI-aware, so {@code Robot.createScreenCapture} returns physical pixels
     * rather than DPI-scaled logical ones, and so window rectangles come back unvirtualized. MUST
     * be called before any AWT use (before {@code new Robot()} / Toolkit init) or it has no effect.
     *
     * <p><b>Per-monitor</b> awareness (V2) is asked for first. The legacy {@code SetProcessDPIAware}
     * only makes the process aware of the <i>primary</i> monitor's DPI: on a mixed-DPI multi-monitor
     * desktop Windows then virtualizes coordinates on the other monitors, so a window rect - and the
     * capture taken from it - silently describes the wrong pixels. V2 needs Windows 10 1703; on
     * anything older the symbol is missing and the legacy call is all there is.
     *
     * <p><b>Both calls usually fail, and that is fine.</b> Measured on the dev machine: the JVM is
     * <i>already</i> per-monitor-v2 aware before a line of our code runs - {@code java.exe}'s own
     * manifest declares it - so {@code SetProcessDpiAwarenessContext} is refused (awareness cannot be
     * set twice) and the legacy call returns TRUE while changing nothing. Awareness is therefore
     * <b>read back</b>, never inferred from a setter's return value, which would have reported
     * "system" for a process that is really per-monitor-v2. (Verified: the legacy call does not
     * downgrade a V2 process.) The calls stay because nothing guarantees a future launcher declares
     * anything.
     *
     * @return the awareness the process actually has, for the banner and for bug reports.
     */
    public static String setProcessDpiAware() {
        if (SET_PROCESS_DPI_AWARENESS_CONTEXT.isPresent()) {
            try {
                // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 is the pseudo-handle (HANDLE) -4.
                int ignoredAlreadySet = (int) SET_PROCESS_DPI_AWARENESS_CONTEXT.get()
                        .invokeExact(MemorySegment.ofAddress(-4L));
            } catch (Throwable t) {
                throw new RuntimeException("SetProcessDpiAwarenessContext failed", t);
            }
        }
        try {
            int ignoredAlreadySet = (int) SET_PROCESS_DPI_AWARE.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("SetProcessDPIAware failed", t);
        }
        return dpiAwareness();
    }

    /**
     * The DPI awareness this process actually ended up with - observed, not assumed. Unknown only on
     * a Windows too old to have the query (pre-1607), where it is system-aware at best anyway.
     */
    private static String dpiAwareness() {
        if (GET_THREAD_DPI_AWARENESS_CONTEXT.isEmpty() || ARE_DPI_AWARENESS_CONTEXTS_EQUAL.isEmpty()) {
            return "unknown (this Windows cannot be asked)";
        }
        try {
            MemorySegment current =
                    (MemorySegment) GET_THREAD_DPI_AWARENESS_CONTEXT.get().invokeExact();
            for (int i = 0; i < AWARENESS.length; i++) {
                int equal = (int) ARE_DPI_AWARENESS_CONTEXTS_EQUAL.get()
                        .invokeExact(current, MemorySegment.ofAddress(-(i + 1L)));
                if (equal != 0) return AWARENESS[i];
            }
            return "unrecognised";
        } catch (Throwable t) {
            throw new RuntimeException("GetThreadDpiAwarenessContext failed", t);
        }
    }

    /**
     * The client area of the focused window, in virtual-desktop pixels, or empty if there is no
     * foreground window. This - not the size of the primary display - is the rectangle the game
     * renders into, and the two differ for anyone playing below their desktop resolution, in a
     * window, or on a second monitor.
     *
     * <p>The <i>client</i> rect, not the window rect: it excludes any border and title bar, so it is
     * exactly the rendered view even when the game is not borderless.
     */
    public static Optional<Rect> foregroundClientRect() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment hwnd = (MemorySegment) GET_FOREGROUND_WINDOW.invokeExact();
            if (hwnd.address() == 0) return Optional.empty();

            // RECT { LONG left, top, right, bottom } - client coords, so left/top are always 0.
            MemorySegment rect = arena.allocate(JAVA_INT, 4);
            if ((int) GET_CLIENT_RECT.invokeExact(hwnd, rect) == 0) return Optional.empty();
            int width = rect.getAtIndex(JAVA_INT, 2) - rect.getAtIndex(JAVA_INT, 0);
            int height = rect.getAtIndex(JAVA_INT, 3) - rect.getAtIndex(JAVA_INT, 1);

            // POINT { LONG x, y }, seeded with the client origin and mapped onto the desktop.
            MemorySegment origin = arena.allocate(JAVA_INT, 2);
            origin.setAtIndex(JAVA_INT, 0, rect.getAtIndex(JAVA_INT, 0));
            origin.setAtIndex(JAVA_INT, 1, rect.getAtIndex(JAVA_INT, 1));
            if ((int) CLIENT_TO_SCREEN.invokeExact(hwnd, origin) == 0) return Optional.empty();

            return Optional.of(new Rect(origin.getAtIndex(JAVA_INT, 0),
                    origin.getAtIndex(JAVA_INT, 1), width, height));
        } catch (Throwable t) {
            throw new RuntimeException("GetClientRect/ClientToScreen failed", t);
        }
    }

    /**
     * File name of the executable owning the focused window - e.g. {@code G1R-Win64-Shipping.exe} - or
     * {@code ""} if it cannot be determined.
     *
     * <p>This, not the window title, is what the focus gate must key on. Titles are attacker- and
     * accident-prone: a Chrome tab reading "BP Mod Loader ... at Gothic 1 Remake Nexus" matches any
     * sensible title substring, and focusing it would have typed {@code W/A/S/D} into the browser.
     */
    public static String foregroundProcessName() {
        MemorySegment process = MemorySegment.NULL;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment hwnd = (MemorySegment) GET_FOREGROUND_WINDOW.invokeExact();
            if (hwnd.address() == 0) return "";

            MemorySegment pidOut = arena.allocate(JAVA_INT);
            int ignoredThreadId = (int) GET_WINDOW_THREAD_PROCESS_ID.invokeExact(hwnd, pidOut);
            int pid = pidOut.get(JAVA_INT, 0);
            if (pid == 0) return "";

            process = (MemorySegment) OPEN_PROCESS.invokeExact(
                    PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if (process.address() == 0) return ""; // e.g. a more privileged process; deny by default

            int cap = 1024; // UTF-16 code units
            MemorySegment path = arena.allocate((long) cap * Character.BYTES, Character.BYTES);
            MemorySegment sizeInOut = arena.allocate(JAVA_INT);
            sizeInOut.set(JAVA_INT, 0, cap);
            int ok = (int) QUERY_FULL_PROCESS_IMAGE_NAME_W.invokeExact(process, 0, path, sizeInOut);
            if (ok == 0) return "";

            int len = sizeInOut.get(JAVA_INT, 0);
            byte[] bytes = new byte[len * Character.BYTES];
            MemorySegment.copy(path, JAVA_BYTE, 0, bytes, 0, bytes.length);
            return Path.of(new String(bytes, StandardCharsets.UTF_16LE)).getFileName().toString();
        } catch (Throwable t) {
            throw new RuntimeException("resolving the foreground process failed", t);
        } finally {
            if (process.address() != 0) {
                try {
                    int ignored = (int) CLOSE_HANDLE.invokeExact(process);
                } catch (Throwable ignored) {
                    // nothing useful to do; the handle leaks at worst until exit
                }
            }
        }
    }
}
