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

/**
 * Thin Win32 bindings via the Foreign Function &amp; Memory API (stable since JDK 22). Used for
 * things {@code java.awt} cannot do: capturing global hotkeys the app doesn't own, making the
 * process DPI-aware so screen capture yields true device pixels, and identifying the process that
 * owns the focused window as a safety gate before sending keys.
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
    private static final MethodHandle GET_FOREGROUND_WINDOW =
            handle(USER32, "GetForegroundWindow", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID =
            handle(USER32, "GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle OPEN_PROCESS =
            handle(KERNEL32, "OpenProcess", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle QUERY_FULL_PROCESS_IMAGE_NAME_W =
            handle(KERNEL32, "QueryFullProcessImageNameW",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CLOSE_HANDLE =
            handle(KERNEL32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private Win32() {}

    private static MethodHandle handle(SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment sym = lib.find(name)
                .orElseThrow(() -> new IllegalStateException("symbol not found: " + name));
        return LINKER.downcallHandle(sym, fd);
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
     * Marks the process system-DPI-aware so {@code Robot.createScreenCapture} returns physical
     * 3840x2160 pixels rather than DPI-scaled logical pixels. MUST be called before any AWT use
     * (before {@code new Robot()} / Toolkit init) or it has no effect.
     */
    public static void setProcessDpiAware() {
        try {
            int ok = (int) SET_PROCESS_DPI_AWARE.invokeExact();
            if (ok == 0) {
                System.out.println("Warning: SetProcessDPIAware returned FALSE (already set or unavailable).");
            }
        } catch (Throwable t) {
            throw new RuntimeException("SetProcessDPIAware failed", t);
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
