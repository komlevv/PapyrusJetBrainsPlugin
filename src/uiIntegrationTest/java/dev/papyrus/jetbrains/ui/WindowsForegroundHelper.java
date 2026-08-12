package dev.papyrus.jetbrains.ui;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class WindowsForegroundHelper {
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_SHOWWINDOW = 0x0040;
    private static final WinDef.HWND HWND_TOPMOST = new WinDef.HWND(Pointer.createConstant(-1));
    private static final WinDef.HWND HWND_NOTOPMOST = new WinDef.HWND(Pointer.createConstant(-2));

    record ActivationResult(
            boolean success,
            long targetProcessId,
            long targetWindowHandle,
            long foregroundProcessIdBefore,
            long foregroundProcessIdAfter,
            int attempts,
            String detail
    ) {
        String describe() {
            return "success=" + success
                    + ", targetPid=" + targetProcessId
                    + ", targetHwnd=0x" + Long.toHexString(targetWindowHandle)
                    + ", foregroundPidBefore=" + foregroundProcessIdBefore
                    + ", foregroundPidAfter=" + foregroundProcessIdAfter
                    + ", attempts=" + attempts
                    + ", detail=" + detail;
        }
    }

    private WindowsForegroundHelper() {
    }

    static ActivationResult forceForeground(long targetProcessId, Duration timeout) {
        if (isNotWindows()) {
            return new ActivationResult(
                    true,
                    targetProcessId,
                    0,
                    -1,
                    -1,
                    0,
                    "native foreground forcing is only required on Windows"
            );
        }
        if (targetProcessId <= 0 || targetProcessId > 0xFFFF_FFFFL) {
            return failure(targetProcessId, 0, -1, -1, 0, "invalid Windows process id");
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        int attempts = 0;
        long beforePid = foregroundProcessId();
        long targetHandle = 0;
        String lastDetail = "target window not found";

        while (System.nanoTime() < deadline) {
            WinDef.HWND targetWindow = findLargestVisibleWindow(targetProcessId);
            if (targetWindow == null) {
                sleep(50);
                continue;
            }

            targetHandle = handleValue(targetWindow);
            long currentForegroundPid = foregroundProcessId();
            if (currentForegroundPid == targetProcessId) {
                return new ActivationResult(
                        true,
                        targetProcessId,
                        targetHandle,
                        beforePid,
                        currentForegroundPid,
                        attempts,
                        "target IDE window is already the Windows foreground window"
                );
            }

            attempts++;
            pulseAltToReleaseForegroundLock();
            lastDetail = activateWindow(targetWindow);
            sleep(75);

            long afterPid = foregroundProcessId();
            if (afterPid != targetProcessId) {
                lastDetail += "; captionClick=" + clickWindowCaption(targetWindow);
                sleep(75);
                afterPid = foregroundProcessId();
            }
            if (afterPid == targetProcessId) {
                return new ActivationResult(
                        true,
                        targetProcessId,
                        targetHandle,
                        beforePid,
                        afterPid,
                        attempts,
                        lastDetail
                );
            }
        }

        return failure(
                targetProcessId,
                targetHandle,
                beforePid,
                foregroundProcessId(),
                attempts,
                lastDetail
        );
    }

    static boolean isForegroundProcess(long targetProcessId) {
        return isNotWindows() || foregroundProcessId() == targetProcessId;
    }

    private static String activateWindow(WinDef.HWND targetWindow) {
        User32 user32 = User32.INSTANCE;
        WinDef.HWND foregroundWindow = user32.GetForegroundWindow();
        int targetThreadId = user32.GetWindowThreadProcessId(targetWindow, null);
        int foregroundThreadId = foregroundWindow == null
                ? 0
                : user32.GetWindowThreadProcessId(foregroundWindow, null);

        boolean attached = false;
        if (foregroundThreadId != 0 && targetThreadId != 0 && foregroundThreadId != targetThreadId) {
            attached = user32.AttachThreadInput(
                    dword(foregroundThreadId),
                    dword(targetThreadId),
                    true
            );
        }

        boolean restored;
        boolean broughtToTop;
        boolean madeTopmost;
        boolean removedTopmost;
        boolean foregroundSet;
        boolean focusSet;
        try {
            restored = user32.ShowWindow(targetWindow, WinUser.SW_RESTORE);
            broughtToTop = user32.BringWindowToTop(targetWindow);
            int flags = SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW;
            madeTopmost = user32.SetWindowPos(targetWindow, HWND_TOPMOST, 0, 0, 0, 0, flags);
            removedTopmost = user32.SetWindowPos(targetWindow, HWND_NOTOPMOST, 0, 0, 0, 0, flags);
            foregroundSet = user32.SetForegroundWindow(targetWindow);
            focusSet = user32.SetFocus(targetWindow) != null;
        } finally {
            if (attached) {
                user32.AttachThreadInput(
                        dword(foregroundThreadId),
                        dword(targetThreadId),
                        false
                );
            }
        }

        return "attachThreadInput=" + attached
                + ", showWindow=" + restored
                + ", bringWindowToTop=" + broughtToTop
                + ", topmost=" + madeTopmost
                + ", notTopmost=" + removedTopmost
                + ", setForegroundWindow=" + foregroundSet
                + ", setFocus=" + focusSet
                + ", helperThreadId=" + Integer.toUnsignedLong(Kernel32.INSTANCE.GetCurrentThreadId());
    }

    private static String clickWindowCaption(WinDef.HWND targetWindow) {
        User32 user32 = User32.INSTANCE;
        WinDef.RECT rect = new WinDef.RECT();
        if (!user32.GetWindowRect(targetWindow, rect)) {
            return "skipped-getWindowRect-failed";
        }

        long width = Math.max(0L, (long) rect.right - rect.left);
        long height = Math.max(0L, (long) rect.bottom - rect.top);
        if (width < 100 || height < 100) {
            return "skipped-window-too-small";
        }

        int flags = SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW;
        user32.SetWindowPos(targetWindow, HWND_TOPMOST, 0, 0, 0, 0, flags);
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        Point originalPointer = pointerInfo == null ? null : pointerInfo.getLocation();
        int clickX = rect.left + (int) (width / 2);
        int clickY = rect.top + Math.clamp((int) height - 1, 4, 8);

        try {
            Robot robot = new Robot();
            robot.setAutoDelay(20);
            robot.mouseMove(clickX, clickY);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(30);
            return "sent@" + clickX + "," + clickY;
        } catch (Exception failure) {
            return "failed-" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
        } finally {
            user32.SetWindowPos(targetWindow, HWND_NOTOPMOST, 0, 0, 0, 0, flags);
            if (originalPointer != null) {
                try {
                    Robot restoreRobot = new Robot();
                    restoreRobot.mouseMove(originalPointer.x, originalPointer.y);
                } catch (Exception ignored) {
                    // Best-effort pointer restoration only.
                }
            }
        }
    }

    private static WinDef.HWND findLargestVisibleWindow(long targetProcessId) {
        User32 user32 = User32.INSTANCE;
        AtomicReference<WinDef.HWND> selected = new AtomicReference<>();
        AtomicLong selectedArea = new AtomicLong(-1);

        user32.EnumWindows((window, ignored) -> {
            if (!user32.IsWindowVisible(window)) {
                return true;
            }
            IntByReference processId = new IntByReference();
            user32.GetWindowThreadProcessId(window, processId);
            if (Integer.toUnsignedLong(processId.getValue()) != targetProcessId) {
                return true;
            }

            WinDef.RECT rect = new WinDef.RECT();
            if (!user32.GetWindowRect(window, rect)) {
                return true;
            }
            long width = Math.max(0L, (long) rect.right - rect.left);
            long height = Math.max(0L, (long) rect.bottom - rect.top);
            long area = width * height;
            if (area > selectedArea.get()) {
                selectedArea.set(area);
                selected.set(window);
            }
            return true;
        }, null);

        return selected.get();
    }

    private static long foregroundProcessId() {
        WinDef.HWND foregroundWindow = User32.INSTANCE.GetForegroundWindow();
        if (foregroundWindow == null) {
            return 0;
        }
        IntByReference processId = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(foregroundWindow, processId);
        return Integer.toUnsignedLong(processId.getValue());
    }

    private static void pulseAltToReleaseForegroundLock() {
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(10);
            robot.keyPress(KeyEvent.VK_ALT);
            robot.keyRelease(KeyEvent.VK_ALT);
        } catch (Exception ignored) {
            // Best effort only. Foreground activation has fallback paths.
        }
    }

    private static WinDef.DWORD dword(int value) {
        return new WinDef.DWORD(Integer.toUnsignedLong(value));
    }

    private static long handleValue(WinDef.HWND window) {
        return window == null ? 0 : Pointer.nativeValue(window.getPointer());
    }

    private static ActivationResult failure(
            long targetProcessId,
            long targetWindowHandle,
            long beforePid,
            long afterPid,
            int attempts,
            String detail
    ) {
        return new ActivationResult(
                false,
                targetProcessId,
                targetWindowHandle,
                beforePid,
                afterPid,
                attempts,
                detail
        );
    }

    private static boolean isNotWindows() {
        return !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
