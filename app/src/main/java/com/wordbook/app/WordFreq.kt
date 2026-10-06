package com.wordbook.app

import android.content.Context
import org.json.JSONObject

/**
 * 考研词频查词：内置懒笔记 46 套真题句频榜（1199 词）+ 考纲内标记。
 * 用于识别结果/生词本标注"真题高频·N次 / 未收录"并按优先级排序。
 */
class WordFreq(context: Context) {

    private data class FreqInfo(val freq: Int, val syllabus: Boolean)

    private val map: HashMap<String, FreqInfo> = HashMap()

    init {
        try {
            val json = context.assets.open("kaoyan.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val words = obj.getJSONObject("words")
            val keys = words.keys()
            while (keys.hasNext()) {
                val w = keys.next()
                val v = words.getJSONObject(w)
                map[w] = FreqInfo(v.optInt("f", 0), v.optInt("s", 0) == 1)
            }
        } catch (e: Exception) {
            // 词库缺失时静默降级：全部词按"未收录"处理
        }
    }

    val size: Int get() = map.size

    /** 查询单词（小写归一），返回 {freq, syllabus, level, label}；未收录 freq=0、level="未收录" */
    fun getFreq(word: String): JSONObject {
        val out = JSONObject()
        val info = map[word.trim().lowercase()]
        if (info == null) {
            out.put("freq", 0)
            out.put("syllabus", false)
            out.put("level", "未收录")
            out.put("label", "未收录词频数据")
            return out
        }
        out.put("freq", info.freq)
        out.put("syllabus", info.syllabus)
        val level = when {
            info.freq >= 100 -> "真题超高"
            info.freq >= 50 -> "真题高频"
            info.freq >= 30 -> "真题中高"
            info.freq >= 15 -> "真题中频"
            else -> "真题低频"
        }
        out.put("level", level)
        out.put("label", "真题$level · ${info.freq}次")
        return out
    }

    /** 排序权重：高频在前；未收录最后 */
    fun sortWeight(word: String): Int {
        val info = map[word.trim().lowercase()]
        return info?.freq ?: 0
    }
}
