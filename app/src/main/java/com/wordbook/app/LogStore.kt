package com.wordbook.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地调试日志：关键事件写入 files/logs/，供"同步日志"打包导出到电脑 debug。
 * - app.log：全链路事件（启动 / TTS / 识别 / 查词 / 导出）
 * - recognize_errors.log：识别失败明细（时间 / 模式 / 错误）
 */
class LogStore(private val context: Context) {

    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    private val logFile = File(dir, "app.log")
    private val errFile = File(dir, "recognize_errors.log")
    private val maxSize = 200 * 1024

    @Synchronized
    fun log(tag: String, msg: String) {
        append(logFile, "[${ts()}] [$tag] $msg")
    }

    @Synchronized
    fun error(tag: String, msg: String) {
        append(logFile, "[${ts()}] [$tag] ERROR: $msg")
        append(errFile, "[${ts()}] [$tag] $msg")
    }

    private fun append(file: File, line: String) {
        try {
            file.appendText(line + "\n")
            if (file.length() > maxSize) trimTail(file)
        } catch (e: Exception) {
        }
    }

    /** 保留最近 400 行 */
    private fun trimTail(file: File) {
        try {
            val lines = file.readLines()
            if (lines.size > 400) file.writeText(lines.takeLast(400).joinToString("\n") + "\n")
        } catch (e: Exception) {
        }
    }

    /** 读取日志文件内容（打包导出用）；不存在时返回空串 */
    fun readFile(name: String): String {
        return try { File(dir, name).readText() } catch (e: Exception) { "" }
    }

    /** 设备与环境快照（随日志包一起导出） */
    fun snapshot(dbVersion: Int, wordCount: Int): String {
        val sb = StringBuilder()
        sb.appendLine("===== 设备与环境信息 =====")
        sb.appendLine("时间: ${ts()}")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        sb.appendLine("应用包名: ${context.packageName}")
        sb.appendLine("数据库版本: v$dbVersion")
        sb.appendLine("词库大小: $wordCount 词")
        sb.appendLine("网络: ${networkState()}")
        sb.appendLine("TTS 引擎可用: ${ttsAvail}")
        return sb.toString()
    }

    var ttsAvail = "未知"
        private set

    fun setTtsAvail(v: String) { ttsAvail = v }

    private fun networkState(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return "无网络"
            val caps = cm.getNetworkCapabilities(net) ?: return "未知"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
                else -> "其他"
            }
        } catch (e: Exception) { "未知" }
    }

    private fun ts(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
}
