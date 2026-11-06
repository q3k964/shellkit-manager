package me.weishu.kernelsu.ui.util

import android.util.Log
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import me.weishu.kernelsu.ksuApp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

/**
 * ShellKit daemon 客户端。
 *
 * 这是 KSU Manager → ShellKit 改造的核心：
 * 把所有原来走 libksud.so + root shell 的调用，
 * 替换为走本地 daemon socket (127.0.0.1:27042)。
 *
 * daemon 以 uid 2000 (shell) 运行，不需要 root。
 * daemon 由 AdbClient 通过 setsid app_process 拉起。
 *
 * 通信协议：
 *   发送：每行一条命令 "COMMAND|arg1|arg2|..."
 *   返回：===RESULT===\n<内容>\n===END===
 */
private const val TAG = "ShellKitCli"
private const val DAEMON_PORT = 27042
private const val DAEMON_HOST = "127.0.0.1"

object ShellKitConnection {
    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var connected = false

    private val lock = Any()

    /**
     * 连接 daemon。
     */
    fun connect(): Boolean {
        synchronized(lock) {
            if (connected && socket?.isClosed == false) return true
            try {
                val s = Socket(DAEMON_HOST, DAEMON_PORT)
                s.soTimeout = 10000
                s.keepAlive = true
                s.tcpNoDelay = true
                socket = s

                // ping 验证
                val resp = sendCommand("ping")
                connected = "pong".equals(resp?.trim(), ignoreCase = true)
                Log.i(TAG, "Daemon connected: $connected")
                return connected
            } catch (e: Exception) {
                Log.w(TAG, "Daemon connect failed: ${e.message}")
                connected = false
                return false
            }
        }
    }

    /**
     * 断开。
     */
    fun disconnect() {
        synchronized(lock) {
            connected = false
            try { socket?.close() } catch (_: Exception) {}
            socket = null
        }
    }

    fun isConnected(): Boolean = connected && socket?.isClosed == false

    /**
     * 发送命令并获取结果。
     */
    fun sendCommand(command: String): String? {
        synchronized(lock) {
            if (!connected) {
                if (!connect()) return null
            }
            val s = socket ?: return null
            try {
                val writer = s.getOutputStream()
                writer.write((command + "\n").toByteArray(Charsets.UTF_8))
                writer.flush()

                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val sb = StringBuilder()
                var inResult = false
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!.trim()
                    if (l == "===RESULT===") { inResult = true; continue }
                    if (l == "===END===") break
                    if (inResult) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(line)
                    }
                }
                return sb.toString()
            } catch (e: Exception) {
                Log.w(TAG, "sendCommand failed: ${e.message}")
                connected = false
                // 重试一次
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                return null
            }
        }
    }

    /**
     * 发送命令，返回是否成功。
     */
    fun sendCommandBool(command: String): Boolean {
        val r = sendCommand(command)
        return r != null && !r.startsWith("error") && !r.startsWith("fail")
    }
}

/**
 * daemon 是否可用（替代 rootAvailable()）。
 */
fun shellKitAvailable(): Boolean {
    return ShellKitConnection.isConnected() || ShellKitConnection.connect()
}
