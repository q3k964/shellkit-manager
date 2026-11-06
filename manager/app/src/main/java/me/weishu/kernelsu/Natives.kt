package me.weishu.kernelsu

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import me.weishu.kernelsu.ui.util.ShellKitConnection
import me.weishu.kernelsu.Natives.Profile.RootProfileFlag

/**
 * ShellKit Natives — KSU Manager fork stub 层。
 *
 * 原版通过 JNI 调用 kernel IOCTL（supercall）。
 * ShellKit 没有 kernel 组件，所有 external 声明已替换为静态返回值。
 *
 * isFullFeatured() 改为检查 daemon socket 连接状态。
 * Profile 数据类保留不变，UI 层依赖它。
 */
object Natives {
    // ShellKit 版本号（模拟 KSU version code，UI 显示用）
    const val SHELLKIT_VERSION = 10000
    const val SHELLKIT_VERSION_NAME = "ShellKit 1.0"

    const val MINIMAL_SUPPORTED_KERNEL = 0 // ShellKit 不需要 kernel 版本检查

    const val KERNEL_SU_DOMAIN = "u:r:shell:s0"

    const val ROOT_UID = 0
    const val ROOT_GID = 0

    // --- 原 external 属性 → 静态值 ---

    val version: Int
        get() = SHELLKIT_VERSION

    val isSafeMode: Boolean
        get() = false

    val isLkmMode: Boolean
        get() = false

    val isLkmBundled: Boolean
        get() = false

    val isLateLoadMode: Boolean
        get() = false

    /**
     * ShellKit 始终作为 manager 运行。
     */
    val isManager: Boolean
        get() = true

    val isPrBuild: Boolean
        get() = false

    // --- 原 external 函数 → stub ---

    fun uidShouldUmount(uid: Int): Boolean = false

    fun getAppProfile(key: String?, uid: Int): Profile {
        // ShellKit 不支持 app profile，返回默认非 root profile
        return Profile(key ?: "", uid, allowSu = false)
    }

    fun setAppProfile(profile: Profile?): Boolean {
        // ShellKit 不支持 app profile 设置
        return false
    }

    fun isSuEnabled(): Boolean = false

    fun setSuEnabled(enabled: Boolean): Boolean = false

    fun isKernelUmountEnabled(): Boolean = false

    fun setKernelUmountEnabled(enabled: Boolean): Boolean = false

    fun isSelinuxHideEnabled(): Boolean = false

    fun setSelinuxHideEnabled(enabled: Boolean): Int = 0

    fun getUserName(uid: Int): String? = null

    fun getSuperuserCount(): Int = 0

    // --- UAPI 版本（始终匹配，使 isFullFeatured 不被版本检查阻断）---

    val kernelUAPIVersion: Int
        get() = SHELLKIT_VERSION

    val managerUAPIVersion: Int
        get() = SHELLKIT_VERSION

    /**
     * ShellKit 核心功能可用性 = daemon socket 已连接。
     * 替代原版的 isManager && kernelUAPIVersion == managerUAPIVersion && rootAvailable()
     *
     * 注意：这里只检查当前连接状态，不主动发起连接（避免阻塞 UI 线程）。
     * 连接由 ViewModel 在后台线程通过 rootAvailable() 发起。
     */
    fun isFullFeatured(): Boolean {
        return ShellKitConnection.isConnected()
    }

    private const val NON_ROOT_DEFAULT_PROFILE_KEY = "$"
    private const val NOBODY_UID = 9999

    fun setDefaultUmountModules(umountModules: Boolean): Boolean {
        // ShellKit 不支持 umount modules（无 root mount overlay）
        return false
    }

    fun isDefaultUmountModules(): Boolean {
        return false
    }

    // --- Profile 数据类（UI 依赖，保持不变）---

    @Keep
    @Immutable
    @Parcelize
    @Serializable
    data class Profile(
        val name: String,
        val currentUid: Int = 0,
        val allowSu: Boolean = false,
        val rootUseDefault: Boolean = true,
        val rootTemplate: String? = null,
        val uid: Int = ROOT_UID,
        val gid: Int = ROOT_GID,
        val groups: List<Int> = mutableListOf(),
        val capabilities: List<Int> = mutableListOf(),
        val context: String = KERNEL_SU_DOMAIN,
        val namespace: Int = Namespace.INHERITED.ordinal,
        val nonRootUseDefault: Boolean = true,
        val umountModules: Boolean = true,
        var rules: String = "",
        val flags: Long = FLAG_KSU_NO_NEW_PRIVS,
    ) : Parcelable {
        @Keep
        enum class RootProfileFlag(val display: String, val desc: Int) {
            NO_NEW_PRIVS(
                "NO_NEW_PRIVS",
                R.string.profile_flags_desc_no_new_privs
            )
        }

        enum class Namespace {
            INHERITED,
            GLOBAL,
            INDIVIDUAL,
        }

        constructor() : this("")
    }

    const val FLAG_KSU_NO_NEW_PRIVS = 1L
}

fun List<RootProfileFlag>.toRawFlags(): Long =
    fold(0L) { acc, flag -> acc.or(1L.shl(flag.ordinal)) }

fun List<RootProfileFlag>.toOrdinalList(): List<Int> =
    map { it.ordinal }

fun Long.toRootProfileFlags(): List<RootProfileFlag> =
    RootProfileFlag.entries.filter { 1L.shl(it.ordinal).and(this) != 0L }.toList()
