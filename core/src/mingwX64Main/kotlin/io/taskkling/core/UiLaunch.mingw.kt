@file:OptIn(ExperimentalForeignApi::class)

package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.wcstr
import okio.Path
import platform.posix.getenv
import platform.posix.system
import platform.windows.CREATE_ALWAYS
import platform.windows.CREATE_NEW_PROCESS_GROUP
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.CreateProcessW
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.GENERIC_WRITE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.PROCESS_INFORMATION
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.TRUE

internal actual fun readEnvVar(name: String): String? = getenv(name)?.toKString()

/**
 * Windows 10 1803+ ships bsdtar as `System32\tar.exe`, which auto-detects zip
 * — the exact archive format ADR-011 assigns to windows. Invoked by absolute
 * path ([windowsSystemTarCommand]): a bare `tar` resolves via PATH, where Git
 * for Windows' GNU tar shadows bsdtar and can extract neither `C:\...` paths
 * nor zip. `system` goes through cmd.exe; the command builder carries the
 * quoting rules.
 */
public actual fun extractArchiveWithSystemTar(archive: Path, destDir: Path): Boolean =
    system(windowsSystemTarCommand(readEnvVar("SystemRoot"), archive.toString(), destDir.toString())) == 0

/**
 * A real `CreateProcessW`, not `system`+`start` — it detaches cleanly (no
 * lingering cmd.exe, `CREATE_NO_WINDOW` so no console flashes up), redirects
 * both std streams to an inheritable handle on [logFile] (truncating it), and
 * — unlike the POSIX shell route — fails SYNCHRONOUSLY on a corrupt or
 * missing launcher image, which is exactly the signal the self-heal-once flow
 * (ADR-010 decision 6) keys on.
 */
public actual fun spawnDetachedProcess(argv: List<String>, logFile: Path): Boolean = memScoped {
    val sa = alloc<SECURITY_ATTRIBUTES>().apply {
        nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
        lpSecurityDescriptor = null
        bInheritHandle = TRUE
    }
    val log = CreateFileW(
        logFile.toString(),
        GENERIC_WRITE.convert(),
        FILE_SHARE_READ.convert(),
        sa.ptr,
        CREATE_ALWAYS.convert(),
        FILE_ATTRIBUTE_NORMAL.convert(),
        null,
    )
    if (log == INVALID_HANDLE_VALUE) return false
    try {
        val si = alloc<STARTUPINFOW>().apply {
            cb = sizeOf<STARTUPINFOW>().convert()
            dwFlags = STARTF_USESTDHANDLES.convert()
            hStdInput = null
            hStdOutput = log
            hStdError = log
        }
        val pi = alloc<PROCESS_INFORMATION>()
        val ok = CreateProcessW(
            null,
            windowsCommandLine(argv).wcstr.ptr, // CreateProcessW may scribble on the buffer; wcstr allocates a mutable copy in this scope
            null,
            null,
            TRUE,
            (CREATE_NO_WINDOW or CREATE_NEW_PROCESS_GROUP).convert(),
            null,
            null,
            si.ptr,
            pi.ptr,
        )
        if (ok == 0) return false
        CloseHandle(pi.hProcess)
        CloseHandle(pi.hThread)
        true
    } finally {
        CloseHandle(log)
    }
}
