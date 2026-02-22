package com.example.lxb_ignition.shizuku

import com.example.lxb_ignition.IShizukuService
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 运行于 Shizuku shell 进程中的服务实现。
 * 此类无法使用 Android Context，只能用纯 JVM 标准库和 shell 命令。
 */
class ShizukuServiceImpl : IShizukuService.Stub() {

    companion object {
        private const val LOG_FILE = "/data/local/tmp/lxb-core.log"
    }

    override fun deployJar(jarBytes: ByteArray, destPath: String): Boolean {
        return try {
            FileOutputStream(destPath).use { it.write(jarBytes) }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun startServer(jarPath: String, serverClass: String, port: Int): String {
        return try {
            // 先终止已有实例，避免端口占用（EADDRINUSE）
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -f $serverClass")).waitFor()
            Thread.sleep(800) // 等待端口释放

            // nohup 后台启动，输出写入日志文件，与 shell 进程完全解耦
            val cmd = "nohup app_process " +
                    "-Djava.class.path=$jarPath " +
                    "/system/bin $serverClass $port " +
                    "> $LOG_FILE 2>&1 &"
            val sh = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            sh.waitFor()

            Thread.sleep(2500)

            if (checkRunning(serverClass)) {
                "OK\n服务已启动，日志见 $LOG_FILE"
            } else {
                val log = readTail(LOG_FILE, 1024)
                "ERROR\n进程未找到，日志:\n$log"
            }
        } catch (e: Exception) {
            "ERROR\n${e.message}"
        }
    }

    override fun stopServer(serverClass: String) {
        runCatching {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "pkill -f $serverClass")).waitFor()
        }
    }

    override fun isRunning(serverClass: String): Boolean = checkRunning(serverClass)

    override fun readLogPart(fromByte: Long, maxBytes: Int): String {
        return try {
            val file = File(LOG_FILE)
            if (!file.exists() || fromByte >= file.length()) return ""
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(fromByte)
                val toRead = minOf(maxBytes.toLong(), file.length() - fromByte).toInt()
                val buf = ByteArray(toRead)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (_: Exception) { "" }
    }

    override fun destroy() {}

    private fun checkRunning(serverClass: String): Boolean {
        return try {
            // pgrep -f 匹配完整命令行，ps -A 只显示截断的进程名（15字符），无法可靠匹配
            val proc = Runtime.getRuntime().exec(arrayOf("pgrep", "-f", serverClass))
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output.isNotEmpty()
        } catch (_: Exception) { false }
    }

    private fun readTail(path: String, maxBytes: Int): String {
        return try {
            val file = File(path)
            if (!file.exists()) return "(日志文件不存在)"
            val len = file.length()
            val from = maxOf(0L, len - maxBytes)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(from)
                val buf = ByteArray((len - from).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (_: Exception) { "(读取日志失败)" }
    }
}
