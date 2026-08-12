package dev.papyrus.jetbrains.runtime;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Windows Job Object whose members are terminated by the OS when the last job handle is closed.
 *
 * <p>This is deliberately small and uses only documented Kernel32 Job Object APIs. It is used both
 * by the Papyrus language-host launcher and by the Windows UI-test harness so child processes cannot
 * survive their owning JVM.</p>
 */
public final class WindowsKillOnCloseJob implements AutoCloseable {
    private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;

    private final WinNT.HANDLE handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    private WindowsKillOnCloseJob(@NotNull WinNT.HANDLE handle) {
        this.handle = handle;
    }

    public static @NotNull WindowsKillOnCloseJob create() throws IOException {
        Kernel32JobApi api = Kernel32JobApi.INSTANCE;
        WinNT.HANDLE job = api.CreateJobObjectW(null, null);
        if (isNull(job)) {
            throw nativeFailure("CreateJobObjectW");
        }

        boolean configured = false;
        try {
            ExtendedLimitInformation limits = new ExtendedLimitInformation();
            limits.basicLimitInformation.limitFlags = new WinDef.DWORD(JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
            limits.write();
            if (!api.SetInformationJobObject(
                    job,
                    JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                    limits.getPointer(),
                    limits.size()
            )) {
                throw nativeFailure("SetInformationJobObject(JobObjectExtendedLimitInformation)");
            }
            configured = true;
            return new WindowsKillOnCloseJob(job);
        } finally {
            if (!configured) {
                api.CloseHandle(job);
            }
        }
    }

    public void assign(long processId) throws IOException {
        if (closed.get()) {
            throw new IOException("Cannot assign a process to a closed Windows Job Object");
        }
        if (processId <= 0 || processId > Integer.MAX_VALUE) {
            throw new IOException("Invalid Windows process id: " + processId);
        }

        Kernel32JobApi api = Kernel32JobApi.INSTANCE;
        WinNT.HANDLE process = api.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false, (int)processId);
        if (isNull(process)) {
            throw nativeFailure("OpenProcess(" + processId + ")");
        }
        try {
            if (!api.AssignProcessToJobObject(handle, process)) {
                throw nativeFailure("AssignProcessToJobObject(" + processId + ")");
            }
        } finally {
            api.CloseHandle(process);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Kernel32JobApi api = Kernel32JobApi.INSTANCE;
            // Explicit cleanup gets deterministic tree termination. If the JVM dies before this method runs,
            // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE still terminates members when Windows closes the handle.
            api.TerminateJobObject(handle, 1);
            api.CloseHandle(handle);
        }
    }

    private static boolean isNull(WinNT.HANDLE handle) {
        return handle == null || handle.getPointer() == null || Pointer.nativeValue(handle.getPointer()) == 0L;
    }

    private static @NotNull IOException nativeFailure(@NotNull String operation) {
        return new IOException(operation + " failed with Win32 error " + Kernel32JobApi.INSTANCE.GetLastError());
    }

    @SuppressWarnings("UnusedReturnValue") // JNA signatures must preserve Win32 BOOL return types even when cleanup callers ignore them.
    private interface Kernel32JobApi extends StdCallLibrary {
        Kernel32JobApi INSTANCE = Native.load("kernel32", Kernel32JobApi.class, W32APIOptions.DEFAULT_OPTIONS);

        WinNT.HANDLE CreateJobObjectW(Pointer jobAttributes, WString name);

        boolean SetInformationJobObject(WinNT.HANDLE job, int informationClass, Pointer information, int informationLength);

        boolean AssignProcessToJobObject(WinNT.HANDLE job, WinNT.HANDLE process);

        WinNT.HANDLE OpenProcess(int desiredAccess, boolean inheritHandle, int processId);

        boolean TerminateJobObject(WinNT.HANDLE job, int exitCode);

        boolean CloseHandle(WinNT.HANDLE handle);

        int GetLastError();
    }

    @Structure.FieldOrder({
            "perProcessUserTimeLimit",
            "perJobUserTimeLimit",
            "limitFlags",
            "minimumWorkingSetSize",
            "maximumWorkingSetSize",
            "activeProcessLimit",
            "affinity",
            "priorityClass",
            "schedulingClass"
    })
    public static final class BasicLimitInformation extends Structure {
        public long perProcessUserTimeLimit;
        public long perJobUserTimeLimit;
        public WinDef.DWORD limitFlags = new WinDef.DWORD(0);
        public BaseTSD.SIZE_T minimumWorkingSetSize = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T maximumWorkingSetSize = new BaseTSD.SIZE_T();
        public WinDef.DWORD activeProcessLimit = new WinDef.DWORD(0);
        public BaseTSD.ULONG_PTR affinity = new BaseTSD.ULONG_PTR();
        public WinDef.DWORD priorityClass = new WinDef.DWORD(0);
        public WinDef.DWORD schedulingClass = new WinDef.DWORD(0);
    }

    @Structure.FieldOrder({
            "readOperationCount",
            "writeOperationCount",
            "otherOperationCount",
            "readTransferCount",
            "writeTransferCount",
            "otherTransferCount"
    })
    public static final class IoCounters extends Structure {
        public long readOperationCount;
        public long writeOperationCount;
        public long otherOperationCount;
        public long readTransferCount;
        public long writeTransferCount;
        public long otherTransferCount;
    }

    @Structure.FieldOrder({
            "basicLimitInformation",
            "ioInfo",
            "processMemoryLimit",
            "jobMemoryLimit",
            "peakProcessMemoryUsed",
            "peakJobMemoryUsed"
    })
    public static final class ExtendedLimitInformation extends Structure {
        public BasicLimitInformation basicLimitInformation = new BasicLimitInformation();
        public IoCounters ioInfo = new IoCounters();
        public BaseTSD.SIZE_T processMemoryLimit = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T jobMemoryLimit = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T peakProcessMemoryUsed = new BaseTSD.SIZE_T();
        public BaseTSD.SIZE_T peakJobMemoryUsed = new BaseTSD.SIZE_T();
    }
}
