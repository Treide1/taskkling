package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.getenv
import platform.windows.DWORDVar
import platform.windows.ERROR_SUCCESS
import platform.windows.HKEYVar
import platform.windows.HKEY_CURRENT_USER
import platform.windows.HWND_BROADCAST
import platform.windows.KEY_QUERY_VALUE
import platform.windows.KEY_SET_VALUE
import platform.windows.MOVEFILE_DELAY_UNTIL_REBOOT
import platform.windows.MoveFileExW
import platform.windows.REG_EXPAND_SZ
import platform.windows.REG_SZ
import platform.windows.RegCloseKey
import platform.windows.RegOpenKeyExW
import platform.windows.RegQueryValueExW
import platform.windows.RegSetValueExW
import platform.windows.SMTO_ABORTIFHUNG
import platform.windows.SendMessageTimeoutW
import platform.windows.WM_SETTINGCHANGE

private const val ENVIRONMENT_SUBKEY = "Environment"
private const val PATH_VALUE_NAME = "Path"

/** This binary's first registry write (ADR-004) is `HKCU\Environment\Path` — everything below is scoped to that one value. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun globalInstallDir(): String {
    val localAppData = getenv("LOCALAPPDATA")?.toKString() ?: error("LOCALAPPDATA is not set")
    return "$localAppData\\Programs\\taskkling"
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun readWindowsUserPath(): WindowsPathValue? = memScoped {
    val hKeyVar = alloc<HKEYVar>()
    if (RegOpenKeyExW(HKEY_CURRENT_USER, ENVIRONMENT_SUBKEY, 0u, KEY_QUERY_VALUE.convert(), hKeyVar.ptr) != ERROR_SUCCESS) {
        return null
    }
    val hKey = hKeyVar.value
    try {
        val typeVar = alloc<DWORDVar>()
        val sizeVar = alloc<DWORDVar>()
        // First pass: no buffer, just the required size (in bytes) and the value's registry type.
        if (RegQueryValueExW(hKey, PATH_VALUE_NAME, null, typeVar.ptr, null, sizeVar.ptr) != ERROR_SUCCESS || sizeVar.value == 0u) {
            return null
        }
        val units = (sizeVar.value.toInt() / 2).coerceAtLeast(1)
        val buffer = allocArray<UShortVar>(units)
        if (RegQueryValueExW(hKey, PATH_VALUE_NAME, null, typeVar.ptr, buffer.reinterpret(), sizeVar.ptr) != ERROR_SUCCESS) {
            return null
        }
        val type = if (typeVar.value.toInt() == REG_EXPAND_SZ) WindowsPathValueType.REG_EXPAND_SZ else WindowsPathValueType.REG_SZ
        WindowsPathValue(buffer.toKStringFromUtf16(), type)
    } finally {
        if (hKey != null) RegCloseKey(hKey)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun writeWindowsUserPath(value: WindowsPathValue): Unit = memScoped {
    val hKeyVar = alloc<HKEYVar>()
    if (RegOpenKeyExW(HKEY_CURRENT_USER, ENVIRONMENT_SUBKEY, 0u, KEY_SET_VALUE.convert(), hKeyVar.ptr) != ERROR_SUCCESS) {
        throw TkError(ExitCode.VALIDATION, "could not open HKCU\\Environment for writing")
    }
    val hKey = hKeyVar.value
    try {
        val type = if (value.type == WindowsPathValueType.REG_EXPAND_SZ) REG_EXPAND_SZ else REG_SZ
        val wide = value.raw.wcstr
        val byteCount = (value.raw.length + 1) * 2 // UTF-16 code units incl. the NUL terminator, in bytes.
        val rc = RegSetValueExW(hKey, PATH_VALUE_NAME, 0u, type.convert(), wide.ptr.reinterpret(), byteCount.convert())
        if (rc != ERROR_SUCCESS) {
            throw TkError(ExitCode.VALIDATION, "could not write HKCU\\Environment\\Path (RegSetValueExW error $rc)")
        }
    } finally {
        if (hKey != null) RegCloseKey(hKey)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun broadcastEnvironmentChange(): Unit = memScoped {
    val action = "Environment".wcstr
    SendMessageTimeoutW(
        HWND_BROADCAST,
        WM_SETTINGCHANGE.convert(),
        0u,
        action.ptr.rawValue.toLong().convert(),
        SMTO_ABORTIFHUNG.convert(),
        5000u,
        null,
    )
    Unit
}

/**
 * Delete-on-reboot for the `.old` sibling [renameSelfToOld] leaves behind —
 * best-effort, mirroring [DeleteFileW]'s ignore-failure style in that same
 * primitive (`update`'s stale-`.old` sweep retries on the next run; this has
 * no next run, so the OS is asked instead).
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun scheduleDeleteOnReboot(path: String) {
    MoveFileExW(path, null, MOVEFILE_DELAY_UNTIL_REBOOT.convert())
}
