package me.weishu.kernelsu.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import android.system.Os
import android.util.Log
import android.provider.OpenableColumns
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.weishu.kernelsu.BuildConfig
import me.weishu.kernelsu.core.tasks.ProbeResult
import me.weishu.kernelsu.ksuApp
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * ShellKit CLI 层 — KSU Manager fork 核心改造文件。
 *
 * 原版 KsuCli.kt 通过 libksud.so + root shell 执行所有操作。
 * 本版通过 ShellKit daemon socket (127.0.0.1:27042) 执行模块管理操作。
 *
 * daemon 以 uid 2000 (shell) 运行，不需要 root。
 *
 * 保留的函数签名与原版完全一致，确保 ViewModel/UI 代码无需修改。
 * 不支持的功能（boot patching、sepolicy、app profile）返回安全默认值。
 */
private const val TAG = "ShellKitCli"

data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
    constructor(code: Int, err: String) : this(code, err, false)
}

object KsuCli {
    // ShellKit 不需要持久 shell 连接，daemon socket 是按需连接的
    val SHELL: Shell = Shell.Builder.create().build("sh")
    val GLOBAL_MNT_SHELL: Shell = SHELL
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return KsuCli.SHELL
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    return Shell.Builder.create().build("sh")
}

/**
 * daemon 是否可用（替代原 rootAvailable()）。
 * ShellKit 用 daemon socket 连接状态代替 root 检查。
 */
fun rootAvailable(): Boolean {
    return ShellKitConnection.isConnected() || ShellKitConnection.connect()
}

// ===================== 模块管理（通过 daemon socket）=====================

/**
 * 列出已安装模块，返回 KSU 兼容的 JSON 数组。
 */
fun listModules(): String {
    val result = ShellKitConnection.sendCommand("list_modules_json")
    if (result.isNullOrBlank()) return "[]"
    return result
}

fun getModuleCount(): Int {
    val result = listModules()
    return runCatching {
        JSONArray(result).length()
    }.getOrElse { 0 }
}

fun getSuperuserCount(): Int {
    // ShellKit 不支持 su 授权，永远返回 0
    return 0
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) "enable_module|$id" else "disable_module|$id"
    val result = ShellKitConnection.sendCommandBool(cmd)
    Log.i(TAG, "toggleModule $id enable=$enable result=$result")
    return result
}

fun undoUninstallModule(id: String): Boolean {
    val result = ShellKitConnection.sendCommandBool("undo_uninstall|$id")
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val result = ShellKitConnection.sendCommandBool("uninstall_module|$id")
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {
    // ShellKit 通过 daemon socket 执行，不支持流式输出回调
    // 这里把 daemon 返回的多行结果按行回调
    val result = ShellKitConnection.sendCommand(cmd)
    if (result != null) {
        result.split("\n").forEach { onStdout(it) }
    } else {
        onStderr("daemon not connected")
    }
    // 构造一个 Shell.Result 兼容对象（libsu 6.0 没有 SUCCESS 常量）
    return KsuCli.SHELL.newJob().add("true").to(ArrayList()).exec()
}

fun flashModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    val file = File.createTempFile("module-", ".zip", ksuApp.cacheDir)
    try {
        try {
            val input = ksuApp.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open $uri")
            input.use { it.copyTo(file.outputStream()) }
        } catch (e: IOException) {
            val message = "! ${e.message ?: "Failed to read module"}"
            onStderr(message)
            return FlashResult(1, message, false)
        }

        onStdout("- Installing module via ShellKit daemon...")
        val result = ShellKitConnection.sendCommand("install_module|${file.absolutePath}")
        Log.i("ShellKit", "install module $uri result: $result")

        if (result == null) {
            onStderr("daemon not connected")
            return FlashResult(1, "daemon not connected", false)
        }

        if (result.startsWith("ok")) {
            result.split("\n").forEach { onStdout(it) }
            return FlashResult(0, "", false)
        } else {
            onStderr(result)
            return FlashResult(1, result, false)
        }
    } finally {
        file.delete()
    }
}

private val MODULE_ID_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9._-]+$")

// ShellKit 直接解压安装，不需要 reboot
private fun moduleNeedsReboot(zip: File): Boolean = false

fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    // ShellKit daemon 的 run_action 只接受 moduleId|actionName
    // KSU 的 action 是无参数的默认 action
    val result = ShellKitConnection.sendCommand("run_action|$moduleId|default")
    Log.i("ShellKit", "Module runAction result: $result")

    if (result == null) {
        onStderr("daemon not connected")
        return false
    }

    result.split("\n").forEach { onStdout(it) }
    return !result.startsWith("error") && !result.startsWith("module not found")
}

/**
 * Runs a prepared one-shot script command in a new shell.
 * Returns the exit code.
 */
fun runOneshotScript(
    command: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Int {
    // 通过 daemon 的 exec 命令执行
    val result = ShellKitConnection.sendCommand("exec|$command")
    if (result == null) {
        onStderr("daemon not connected")
        return 1
    }
    result.split("\n").forEach { onStdout(it) }
    return 0
}

/** Removes a script's extraction directory. */
fun removeAsRoot(path: String): Boolean {
    val escaped = "'" + path.replace("'", "'\\''") + "'"
    return ShellKitConnection.sendCommandBool("exec|rm -rf $escaped")
}

// ===================== 不支持的功能（root 专属）=====================
// 以下功能返回安全默认值，UI 层会据此隐藏相关入口。

fun restoreBoot(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    onStderr("ShellKit does not support boot patching (requires root)")
    return FlashResult(1, "Not supported", false)
}

fun uninstallPermanently(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    onStderr("ShellKit does not support permanent uninstall (requires root)")
    return FlashResult(1, "Not supported", false)
}

@Parcelize
sealed class LkmSelection : Parcelable {
    @Parcelize
    data class LkmUri(val uri: Uri) : LkmSelection()

    @Parcelize
    data class KmiString(val value: String) : LkmSelection()

    @Parcelize
    data object KmiNone : LkmSelection()
}

fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    ota: Boolean,
    partition: String?,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    onStderr("ShellKit does not support boot patching (requires root)")
    return FlashResult(1, "Not supported", false)
}

fun downloadBoot(
    url: String,
    partition: String,
    lkm: LkmSelection,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    onStderr("ShellKit does not support boot patching (requires root)")
    return FlashResult(1, "Not supported", false)
}

fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        ShellKitConnection.sendCommandBool("exec|setprop sys.powerctl=shutdown")
        return
    }
    val shell = getRootShell()
    if (reason == "recovery") {
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    ShellUtils.fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

// ===================== Feature flags（不支持，返回默认值）=====================

suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    "" // ShellKit 不支持 kernel feature
}

suspend fun getFeaturePersistValue(feature: String): Long? = null
suspend fun getFeatureValue(feature: String): Long? = null

fun execKsud(args: String, newShell: Boolean = false, globalMnt: Boolean = false): Boolean {
    // ShellKit 不使用 libksud.so，直接通过 daemon exec
    return ShellKitConnection.sendCommandBool("exec|$args")
}

fun install() {
    // ShellKit 不需要安装 ksud 到系统分区
    Log.i(TAG, "ShellKit: install() is a no-op (no ksud to install)")
}

// ===================== KMI / Boot info（不支持）=====================

suspend fun getCurrentKmi(): String = ""
suspend fun getSupportedKmis(): List<String> = emptyList()
suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    Os.uname().release.contains("_a") || File("/proc/cmdline").readText().contains("androidboot.slot_suffix=_a")
}
suspend fun getDefaultPartition(): String = "init_boot"
suspend fun getSlotSuffix(ota: Boolean): String = ""
suspend fun getAvailablePartitions(): List<String> = emptyList()

fun hasMagisk(): Boolean = false

// ===================== SELinux / App Profile（不支持）=====================

fun isSepolicyValid(rules: String?): Boolean = true
fun getSepolicy(pkg: String): String = ""
fun setSepolicy(pkg: String, rules: String): Boolean = false
fun listAppProfileTemplates(): List<String> = emptyList()
fun getAppProfileTemplate(id: String): String = ""
fun setAppProfileTemplate(id: String, template: String): Boolean = false
fun deleteAppProfileTemplate(id: String): Boolean = false

// ===================== App 管理（通过 daemon exec）=====================

fun forceStopApp(packageName: String, userId: Int? = null) {
    val userArg = userId?.let { " --user $it" } ?: ""
    ShellKitConnection.sendCommandBool("exec|am force-stop$userArg $packageName")
    Log.i(TAG, "force stop $packageName")
}

fun launchApp(packageName: String, userId: Int? = null) {
    val userArg = userId?.let { " --user $it" } ?: ""
    ShellKitConnection.sendCommandBool(
        "exec|cmd package resolve-activity --brief$userArg $packageName | tail -n 1 | xargs cmd activity start-activity$userArg -n"
    )
    Log.i(TAG, "launch $packageName")
}

fun restartApp(packageName: String, userId: Int? = null) {
    forceStopApp(packageName, userId)
    launchApp(packageName, userId)
}

// ===================== Probe（不支持）=====================

suspend fun probeRemoteBootPartitions(url: String): ProbeResult {
    // ShellKit 不支持 boot probing — 返回空结果
    return ProbeResult(emptyList(), null)
}
