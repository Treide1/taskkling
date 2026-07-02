package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.DeleteFileW
import platform.windows.GetLastError
import platform.windows.MoveFileW

/**
 * Windows locks a running `.exe`'s image file while it executes, so it can't
 * be overwritten (or renamed onto) in place — but the executing file's OWN
 * directory entry CAN be renamed away (the PE loader opens it with
 * `FILE_SHARE_READ`/`FILE_SHARE_DELETE`), which is exactly the rename-then-swap
 * dance ADR-002 calls for: free the live path by renaming it to a `.old`
 * sibling; [installNewExecutable] then moves the new binary into the freed
 * name.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun renameSelfToOld(exePath: String): String {
    val oldPath = "$exePath.old"
    // Best-effort: clear a stale .old left by a prior update whose sweep
    // hook hasn't run yet (or lost the race); ignore failure either way.
    DeleteFileW(oldPath)
    if (MoveFileW(exePath, oldPath) == 0) {
        throw TkError(ExitCode.VALIDATION, "could not rename running executable to $oldPath (MoveFileW error ${GetLastError()})")
    }
    return oldPath
}

/** This target is always Windows x64 — the single published Windows binary. */
public actual fun currentReleaseAssetName(): String = resolveAssetName(HostOs.WINDOWS, HostArch.X64)
