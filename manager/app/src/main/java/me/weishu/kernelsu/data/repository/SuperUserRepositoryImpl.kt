package me.weishu.kernelsu.data.repository

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.Natives
import me.weishu.kernelsu.data.model.AppInfo
import me.weishu.kernelsu.data.model.WEBVIEW_ZYGOTE_PROFILE_KEY
import me.weishu.kernelsu.data.model.WEBVIEW_ZYGOTE_UID
import me.weishu.kernelsu.ksuApp

/**
 * ShellKit fork: 原 SuperUserRepositoryImpl 通过 RootService (Binder root IPC)
 * 获取跨用户安装包列表。ShellKit 无 root，改为直接使用 PackageManager
 * 获取当前用户包列表。App profile 全部返回默认非 root 值。
 *
 * SuperUser 页面仍可显示应用列表，但所有 app 显示为「无 root 权限」
 * — 这对 ShellKit 是正确行为（uid 2000 无法授予 root）。
 */
class SuperUserRepositoryImpl : SuperUserRepository {

    companion object {
        private const val TAG = "SuperUserRepository"
    }

    override suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = ksuApp.packageManager
            val start = SystemClock.elapsedRealtime()

            // ShellKit: 直接使用 PackageManager 获取当前用户安装包
            // 原 KSU 通过 RootService 跨用户获取，ShellKit 无 root 只能获取当前用户
            val packages = pm.getInstalledPackages(0)

            val newApps = packages.filter {
                val ai = it.applicationInfo ?: return@filter false
                ai.uid != WEBVIEW_ZYGOTE_UID &&
                        (ai.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
            }.map {
                val appInfo = it.applicationInfo!!
                val profile = Natives.getAppProfile(it.packageName, appInfo.uid)
                AppInfo(
                    label = appInfo.loadLabel(pm).toString(),
                    packageInfo = it,
                    profile = profile,
                )
            }.toMutableList()

            // WebView Zygote placeholder (保持与原版一致)
            val systemInfo = ApplicationInfo(pm.getApplicationInfo("android", 0)).apply {
                uid = WEBVIEW_ZYGOTE_UID
            }
            val placeholder = android.content.pm.PackageInfo().apply {
                packageName = ""
                applicationInfo = systemInfo
            }
            newApps += AppInfo(
                label = "WebView Zygote",
                packageInfo = placeholder,
                profile = Natives.getAppProfile(WEBVIEW_ZYGOTE_PROFILE_KEY, WEBVIEW_ZYGOTE_UID),
                profileKey = WEBVIEW_ZYGOTE_PROFILE_KEY,
                special = true,
            )

            android.util.Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}")
            // ShellKit: 只返回当前用户 (user 0)
            Pair(newApps, listOf(0))
        }
    }

    override suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            if (currentApps.isEmpty()) return@runCatching emptyList()

            currentApps.map {
                val profile = Natives.getAppProfile(it.profileKey, it.uid)
                it.copy(profile = profile)
            }
        }
    }
}
