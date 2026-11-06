# ShellKit Manager — KernelSU Manager Fork Guide

本文档记录将 KernelSU Manager fork 为 ShellKit Manager 的所有改动。

## 架构概述

```
原版 KSU Manager                    ShellKit Manager
─────────────                      ────────────────
App (Kotlin/Compose)               App (Kotlin/Compose) ← 保留不变
    ↓                                  ↓
KsuCli.kt                          KsuCli.kt ← 重写
    ↓                                  ↓
libksud.so + su (root shell)       ShellKitCli.kt (daemon socket)
    ↓                                  ↓
kernel IOCTL (supercall)           127.0.0.1:27042 TCP
                                       ↓
                                   ShellKitDaemon (uid 2000)
```

核心思路：**保留全部 Compose UI 代码不变**，只替换数据/执行层。

## 改动清单

### 1. 新增文件

#### `ui/util/ShellKitCli.kt` — Daemon socket 客户端
- `ShellKitConnection` object：TCP 连接到 `127.0.0.1:27042`
- `connect()` / `disconnect()` / `isConnected()`
- `sendCommand(command: String): String?` — 发送命令，读取 `===RESULT===`/`===END===` 帧响应
- `sendCommandBool(command: String): Boolean`
- `shellKitAvailable(): Boolean`
- `rootAvailable(): Boolean` — 兼容 KSU 原有调用

#### `ui/util/KsuCli.kt` — 完全重写
保留所有函数签名不变，替换实现：

| 函数 | 原实现 | 新实现 |
|------|--------|--------|
| `listModules()` | `libksud module list` (root shell) | `ShellKitConnection.sendCommand("list_modules_json")` |
| `toggleModule(id, enable)` | `libksud module enable/disable` | `sendCommand("enable_module\|id")` / `"disable_module\|id"` |
| `uninstallModule(id)` | `libksud module uninstall` | `sendCommand("uninstall_module\|id")` |
| `undoUninstallModule(id)` | `libksud module undo-uninstall` | `sendCommand("undo_uninstall\|id")` |
| `flashModule(uri, ...)` | `libksud module install` (root shell) | `sendCommand("install_module\|path")` |
| `runModuleAction(id, ...)` | `libksud module action` | `sendCommand("run_action\|id\|default")` |
| `runOneshotScript(cmd, ...)` | root shell exec | `sendCommand("exec\|cmd")` |
| `rootAvailable()` | `shell.isRoot` | `ShellKitConnection.isConnected()` |
| `forceStopApp(pkg)` | root shell `am force-stop` | `sendCommand("exec\|am force-stop pkg")` |
| `install()` | `libksud install` | no-op (ShellKit 不需要装 ksud) |
| `installBoot()` / `downloadBoot()` | `libksud boot-patch` | 返回 not supported |
| `restoreBoot()` / `uninstallPermanently()` | `libksud boot-restore` / `uninstall` | 返回 not supported |
| `isSepolicyValid()` / `getSepolicy()` 等 | `libksud sepolicy/profile` | 返回默认值 |
| `getCurrentKmi()` 等 boot-info | `libksud boot-info` | 返回空值 |
| `hasMagisk()` | root shell `which magisk` | `false` |
| `reboot(reason)` | root shell `svc power reboot` | `exec\|svc power reboot` |

### 2. `Natives.kt` — 完全 stub 化
- 移除 `System.loadLibrary("kernelsu")`
- 移除所有 `external` 声明，替换为静态返回值
- `isManager` → `true`（始终）
- `version` → `SHELLKIT_VERSION = 10000`
- `kernelUAPIVersion` / `managerUAPIVersion` → 始终相等（版本检查通过）
- `isFullFeatured()` → `ShellKitConnection.isConnected()`
- `getAppProfile()` → 返回默认非 root Profile
- `getSuperuserCount()` → `0`
- 所有 sepolicy/umount/su toggle 函数 → 返回 false/0
- `Profile` 数据类保留不变（UI 依赖）

### 3. `HomeViewModel.kt` — 适配
- `lkmMode` → `null`（ShellKit 无 LKM）
- `requiresNewKernel` / `requiresNewManager` → `false`（版本始终匹配）
- `isLkmBundled` → `false`
- 其余字段保持不变

### 4. 模块路径常量
全局替换 `/data/adb/` → `/data/local/tmp/shellkit/`：

| 文件 | 原路径 | 新路径 |
|------|--------|--------|
| `KsuCli.kt:262` | `/data/adb/modules/$id` | 已移除（daemon 处理） |
| `WebViewHelper.kt:86` | `/data/adb/modules/$moduleId` | `/data/local/tmp/shellkit/modules/$moduleId` |
| `ScriptCommand.kt:16` | `/data/adb/ksu/bin` | `/data/local/tmp/shellkit/bin` |
| `SulogHelper.kt:14` | `/data/adb/ksu/log` | `/data/local/tmp/shellkit/log` |
| `LogEvent.kt:54-63` | 多处 `/data/adb/ksu/*` | `/data/local/tmp/shellkit/*` |

### 5. `build.gradle.kts` — 移除 native build
- 移除 `externalNativeBuild { cmake { ... } }` 全部配置
- 移除 `baseCFlags` / `baseCppFlags` 变量
- 移除 `ndkVersion` / `ndk { abiFilters }`
- 移除 debug/release 的 cmake arguments
- **不再需要** `libksud.so` 在 `jniLibs/` 目录

### 6. daemon 侧升级（`shellkit/` 目录）

#### `ModuleInfo.java`
新增字段：`versionCode`, `updateJson`, `remove`, `update`, `hasWebUi`, `hasActionScript`, `metamodule`

#### `ModuleLoader.java`
- 解析新 module.prop 字段（`versionCode`, `updateJson`, `metamodule`）
- 检查 `remove`/`update` 状态文件
- 检查 `webroot/` 目录（WebUI）、`actions/` 目录
- 新增 `uninstallModule(id)` — 删除模块目录
- 新增 `undoUninstall(id)` — 清除 remove 标记
- 新增 `installModule(zipPath)` — 解压 ZIP 安装
- 新增 `listActions(moduleId)` — 列出 action 脚本
- 新增 `modulesToJson()` — 输出 KSU 兼容 JSON 数组

#### `ShellKitDaemon.java`
新增命令：
- `list_modules_json` — 返回 JSON 数组（KSU Manager 兼容）
- `uninstall_module|<id>` — 卸载模块
- `undo_uninstall|<id>` — 撤销卸载
- `install_module|<zipPath>` — 安装模块 ZIP
- `list_actions|<moduleId>` — 列出 action 脚本

## daemon 命令协议

```
请求:  COMMAND|arg1|arg2\n
响应:  ===RESULT===\n
       <content>\n
       ===END===\n
```

| 命令 | 参数 | 返回 |
|------|------|------|
| `ping` | — | `pong` |
| `info` | — | daemon 信息文本 |
| `list_modules` | — | pipe 分隔文本（旧格式） |
| `list_modules_json` | — | JSON 数组（KSU 兼容） |
| `enable_module` | `\|id` | `ok` / `fail` |
| `disable_module` | `\|id` | `ok` / `fail` |
| `uninstall_module` | `\|id` | `ok` / `fail` |
| `undo_uninstall` | `\|id` | `ok` / `fail` |
| `install_module` | `\|zipPath` | `ok: installed id` / `error: ...` |
| `run_action` | `\|moduleId\|action` | 脚本输出 |
| `list_actions` | `\|moduleId` | 逗号分隔 action 名称 |
| `exec` | `\|shell command` | 命令输出 |
| `install_pkg` | `\|apkPath` | `ok` / `fail` |
| `force_stop` | `\|pkg` | `ok` / `fail` |
| `set_appop` | `\|pkg\|op\|mode` | `ok` / `fail` |
| `grant_perm` | `\|pkg\|perm` | `ok` / `fail` |
| `get_setting` | `\|ns\|key` | 值 |
| `put_setting` | `\|ns\|key\|value` | `ok` / `fail` |

## 模块 JSON 格式

daemon `list_modules_json` 输出，与 KSU `ksud module list` 兼容：

```json
[
  {
    "id": "my-module",
    "name": "My Module",
    "author": "Author",
    "version": "1.0",
    "versionCode": 100,
    "description": "Description",
    "enabled": true,
    "update": false,
    "remove": false,
    "updateJson": "",
    "web": false,
    "action": true,
    "metamodule": 0
  }
]
```

## 不支持的功能（ShellKit 架构限制）

uid 2000 (shell) 无法实现的功能：

- ❌ `/system` 修改 / mount overlay
- ❌ Zygisk / Xposed 注入
- ❌ Kernel module 加载
- ❌ SELinux 策略修改
- ❌ App profile / superuser 授权
- ❌ Boot image patching / kernel flashing
- ❌ KMI 检测 / 分区探测

所有这些功能的函数保留签名但返回安全默认值（false/空），UI 层据此隐藏相关入口。

## 构建说明

```bash
cd KernelSU/manager

# ShellKit 不需要 libksud.so，直接构建
./gradlew clean assembleRelease
```

注意：需要 Android SDK + NDK 环境。本环境（proot）无 javac，仅源码改造。
