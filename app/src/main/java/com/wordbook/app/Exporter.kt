package com.wordbook.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据导出：生词本（CSV / JSON / Anki 制表符格式 / 排版 PDF）+ 学习日志（CSV / JSON）。
 * 文件写入系统「下载/生词错题本/」目录（API 29+ 走 MediaStore，24-28 走公共目录）。
 */
class Exporter(private val context: Context) {

    private val dirName = "生词错题本"

    private fun ts(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun dateLabel(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
    private fun fmtDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

    // ================= 生词本导出 =================

    /** 返回保存路径说明；失败抛异常 */
    fun exportWords(format: String, words: List<WordDb.WordRow>): String {
        if (words.isEmpty()) throw Exception("生词本为空，没有可导出的内容")
        val fileName = "生词本_${ts()}.${extOf(format)}"
        val content: ByteArray = when (format) {
            "csv" -> wordsCsv(words)
            "json" -> wordsJson(words).toString(2).toByteArray(Charsets.UTF_8)
            "anki" -> wordsAnki(words)
            "pdf" -> pdfBytes(words)
            else -> throw Exception("不支持的格式：$format")
        }
        return saveFile(fileName, content)
    }

    private fun extOf(format: String): String = when (format) {
        "csv" -> "csv"; "json" -> "json"; "anki" -> "txt"; "pdf" -> "pdf"; else -> "txt"
    }

    /** CSV：表头为惯例字段，Excel/WPS 可直接打开 */
    private fun wordsCsv(words: List<WordDb.WordRow>): ByteArray {
        val freq = WordFreq(context)
        val sb = StringBuilder("\uFEFF") // BOM，Excel 中文不乱码
        sb.append("单词,音标,词性,中文释义,原文例句,来源,掌握程度,录入次数,考研词频,复习次数,间隔(天),下次复习时间,创建时间\n")
        words.forEach { w ->
            val f = freq.getFreq(w.word)
            sb.append(csv(w.word)).append(',').append(csv(w.phonetic)).append(',').append(csv(w.pos))
                .append(',').append(csv(w.meaning)).append(',').append(csv(w.sentence))
                .append(',').append(csv(w.source)).append(',').append(csv(w.mastery))
                .append(',').append(w.hitCount).append(',').append(csv(f.optString("label")))
                .append(',').append(w.repetitions).append(',').append(w.intervalDays)
                .append(',').append(fmtDate(w.dueAt)).append(',').append(fmtDate(w.createdAt)).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun csv(s: String): String =
        "\"" + s.replace("\"", "\"\"") + "\""

    /** JSON：完整结构化备份，可再导入/迁移 */
    private fun wordsJson(words: List<WordDb.WordRow>): JSONObject {
        val freq = WordFreq(context)
        val arr = JSONArray()
        words.forEach { w ->
            arr.put(JSONObject().apply {
                put("word", w.word); put("phonetic", w.phonetic); put("pos", w.pos)
                put("meaning", w.meaning); put("sentence", w.sentence); put("source", w.source)
                put("mastery", w.mastery); put("repetitions", w.repetitions)
                put("ease", w.ease); put("interval_days", w.intervalDays)
                put("due_at", w.dueAt); put("created_at", w.createdAt)
                put("hit_count", w.hitCount)
                put("kaoyan_freq", freq.getFreq(w.word))
            })
        }
        return JSONObject().apply {
            put("app", "生词错题本")
            put("version", "1.1")
            put("exported_at", dateLabel())
            put("count", words.size)
            put("words", arr)
        }
    }

    /** Anki：制表符分隔，可直接导入 Anki（字段：正面=单词，背面=音标+词性+释义+例句） */
    private fun wordsAnki(words: List<WordDb.WordRow>): ByteArray {
        val freq = WordFreq(context)
        val sb = StringBuilder("# 生词错题本 Anki 导入（制表符分隔，字段：单词 / 音标|词性|释义 / 例句）\n")
        sb.append("# 导入时选择制表符分隔，笔记类型字段顺序：Front / Back / Extra\n")
        words.forEach { w ->
            val front = w.word
            val back = "${w.phonetic} ${w.pos} ${w.meaning}".trim()
            val extra = "${w.sentence}　【${freq.getFreq(w.word).optString("label")} · 录入${w.hitCount}次】".trim()
            sb.append(front).append('\t').append(back).append('\t').append(extra).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** PDF：系统 PdfDocument 排版，可打印、可分享 */
    private fun pdfBytes(words: List<WordDb.WordRow>): ByteArray {
        val freq = WordFreq(context)
        val pageW = 595; val pageH = 842 // A4
        val doc = PdfDocument()
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        var canvas = page.canvas
        var y = 0f

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 22f; typeface = Typeface.create("sans-serif", Typeface.BOLD); color = Color.rgb(31, 90, 140)
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f; color = Color.rgb(120, 130, 140)
        }
        val wordP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 16f; typeface = Typeface.create("sans-serif", Typeface.BOLD); color = Color.rgb(47, 111, 167)
        }
        val metaP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.5f; color = Color.rgb(90, 100, 110)
        }
        val bodyP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f; color = Color.rgb(33, 38, 44)
        }
        val sentP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.5f; typeface = Typeface.create("sans-serif", Typeface.ITALIC); color = Color.rgb(70, 80, 92)
        }
        val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 0.6f; color = Color.rgb(225, 222, 213)
        }
        val hiP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 240, 150)
        }

        fun newPageIfNeed(need: Float) {
            if (y + need > pageH - 60f) {
                canvas.drawLine(40f, y + 6f, pageW - 40f, y + 6f, lineP)
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, page.info.pageNumber + 1).create())
                canvas = page.canvas
                y = 40f
            }
        }

        // 页眉
        canvas.drawText("生词错题本", 40f, 50f, title)
        canvas.drawText("导出时间：${dateLabel()}    共 ${words.size} 个生词", 40f, 70f, sub)
        canvas.drawLine(40f, 82f, pageW - 40f, 82f, lineP)
        y = 100f

        words.forEach { w ->
            // 荧光笔高亮背景（签名元素）
            newPageIfNeed(92f)
            val wordBaseline = y + 18f
            canvas.drawRoundRect(38f, y, 40f + 150f, y + 26f, 4f, 4f, hiP)
            canvas.drawText(w.word, 48f, wordBaseline, wordP)
            val meta = "${w.phonetic}    ${w.pos}    ${w.mastery}    录入${w.hitCount}次"
            canvas.drawText(meta, 200f, wordBaseline, metaP)
            y += 34f
            if (w.meaning.isNotEmpty()) {
                canvas.drawText("释义：${w.meaning}", 48f, y, bodyP); y += 18f
            }
            if (w.sentence.isNotEmpty()) {
                // 例句按宽度折行
                val wrapped = wrapText(w.sentence, sentP, pageW - 96f)
                wrapped.forEach { l -> canvas.drawText(l, 48f, y, sentP); y += 16f }
            }
            val srcLine = listOfNotNull(
                if (w.source.isNotEmpty()) "来源：${w.source}" else null,
                if (freq.getFreq(w.word).optInt("freq") > 0) freq.getFreq(w.word).optString("label") else null
            ).joinToString("　")
            if (srcLine.isNotEmpty()) {
                canvas.drawText(srcLine, 48f, y, metaP); y += 16f
            }
            y += 12f
            canvas.drawLine(48f, y, pageW - 48f, y, lineP)
            y += 14f
        }
        doc.finishPage(page)

        val out = java.io.ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        text.forEach { ch ->
            if (paint.measureText(cur.toString() + ch) > maxWidth) {
                if (cur.isNotEmpty()) { lines.add(cur.toString()); cur = StringBuilder() }
            }
            cur.append(ch)
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }

    // ================= 学习日志导出 =================

    fun exportLogs(format: String, logs: List<WordDb.LogRow>): String {
        if (logs.isEmpty()) throw Exception("暂无学习日志")
        val fileName = "学习日志_${ts()}.${if (format == "json") "json" else "csv"}"
        val content: ByteArray = if (format == "json") logsJson(logs).toString(2).toByteArray(Charsets.UTF_8) else logsCsv(logs)
        return saveFile(fileName, content)
    }

    private fun logsCsv(logs: List<WordDb.LogRow>): ByteArray {
        val sb = StringBuilder("\uFEFF")
        sb.append("时间,单词,模式,评分(0-5),说明\n")
        val modeName = mapOf("flash" to "闪卡", "dictation" to "听写", "quiz" to "测验")
        logs.forEach { l ->
            sb.append(fmtDate(l.createdAt)).append(',').append(csv(l.word))
                .append(',').append(csv(modeName[l.mode] ?: l.mode)).append(',').append(l.grade)
                .append(',').append(csv(gradeNote(l.grade))).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun logsJson(logs: List<WordDb.LogRow>): JSONObject {
        val arr = JSONArray()
        logs.forEach { l ->
            arr.put(JSONObject().apply {
                put("time", fmtDate(l.createdAt)); put("word", l.word)
                put("mode", l.mode); put("grade", l.grade)
            })
        }
        return JSONObject().apply {
            put("app", "生词错题本"); put("type", "learning_logs")
            put("exported_at", dateLabel()); put("count", logs.size); put("logs", arr)
        }
    }

    private fun gradeNote(g: Int): String = when (g) {
        5 -> "非常容易"; 4 -> "容易"; 3 -> "想起但犹豫"; 2 -> "错误但记得"; 1 -> "错误且困难"; 0 -> "完全忘记"; else -> ""
    }

    // ================= 调试日志包（zip） =================

    /** 打包调试日志为 zip 并保存到下载目录，返回 MediaStore Uri（供一键分享到微信/QQ 等） */
    fun exportDebugBundleUri(logs: List<WordDb.LogRow>, logStore: LogStore, dbVersion: Int, wordCount: Int): Uri {
        val zipBytes = buildZip(logs, logStore, dbVersion, wordCount)
        return saveFileUri("调试日志_${ts()}.zip", zipBytes)
    }

    private fun buildZip(logs: List<WordDb.LogRow>, logStore: LogStore, dbVersion: Int, wordCount: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            fun add(name: String, content: ByteArray) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
            add("app.log", logStore.readFile("app.log").toByteArray(Charsets.UTF_8))
            add("recognize_errors.log", logStore.readFile("recognize_errors.log").toByteArray(Charsets.UTF_8))
            add("device_info.txt", logStore.snapshot(dbVersion, wordCount).toByteArray(Charsets.UTF_8))
            add("learning_logs.csv", logsCsv(logs))
        }
        return out.toByteArray()
    }

    // ================= 写文件 =================

    /** 写入 MediaStore 并返回 Uri（分享用），支持 API 29+ 与旧版 */
    private fun saveFileUri(fileName: String, bytes: ByteArray): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$dirName")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("无法创建导出文件")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw Exception("写入失败")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                dirName
            ).apply { mkdirs() }
            val f = File(dir, fileName)
            FileOutputStream(f).use { it.write(bytes) }
            return Uri.fromFile(f)
        }
    }

    private fun saveFile(fileName: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$dirName")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("无法创建导出文件")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw Exception("写入失败")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "下载/$dirName/$fileName"
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                dirName
            ).apply { mkdirs() }
            val f = File(dir, fileName)
            FileOutputStream(f).use { it.write(bytes) }
            return f.absolutePath
        }
    }

    private fun mimeOf(fileName: String): String = when {
        fileName.endsWith(".pdf") -> "application/pdf"
        fileName.endsWith(".csv") -> "text/csv"
        fileName.endsWith(".json") -> "application/json"
        fileName.endsWith(".zip") -> "application/zip"
        else -> "text/plain"
    }
}
