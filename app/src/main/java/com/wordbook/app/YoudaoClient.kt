package com.wordbook.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 有道词典客户端：无鉴权公开接口（dict.youdao.com 网页端同款），
 * 提供联想搜索（候选词+释义）与词典详情（ec 释义/音标）。
 * 用于：手动录入先搜词确认、生词本查词、一键复核释义。
 */
class YoudaoClient {

    companion object {
        private const val SUGGEST_URL = "https://dict.youdao.com/suggest?num=8&ver=3.0&doctype=json&cache=false&le=en&q="
        private const val LOOKUP_URL = "https://dict.youdao.com/jsonapi?q="
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }

    /** 联想搜索：返回 [{word, explain}] 候选列表 */
    @Throws(Exception::class)
    fun suggest(q: String): JSONArray {
        val url = SUGGEST_URL + URLEncoder.encode(q.trim(), "UTF-8")
        val d = JSONObject(httpGet(url))
        val entries = d.optJSONObject("data")?.optJSONArray("entries") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            out.put(JSONObject().apply {
                put("word", e.optString("entry"))
                put("explain", e.optString("explain"))
            })
        }
        return out
    }

    /**
     * 词典详情：返回 {word, phonetic(英式音标), meaning(合并释义), pos(词性缩写)}。
     * ec 缺失时（部分词只返回 simple）仅返回音标，由前端回退用 suggest 的 explain。
     */
    @Throws(Exception::class)
    fun lookup(word: String): JSONObject {
        val url = LOOKUP_URL + URLEncoder.encode(word.trim(), "UTF-8")
        val d = JSONObject(httpGet(url))
        val out = JSONObject()
        out.put("word", word.trim())

        // 音标：simple 或 ec
        val sm = d.optJSONObject("simple")
        val smArr = sm?.optJSONArray("word")
        if (smArr != null && smArr.length() > 0) {
            out.put("phonetic", smArr.getJSONObject(0).optString("ukphone"))
        }

        // ec 释义（词性 + 中文释义）
        val ec = d.optJSONObject("ec")
        val ecArr = ec?.optJSONArray("word")
        if (ecArr != null && ecArr.length() > 0) {
            val w0 = ecArr.getJSONObject(0)
            val uk = w0.optString("ukphone")
            if (uk.isNotEmpty()) out.put("phonetic", uk)
            val trs = w0.optJSONArray("trs")
            val parts = ArrayList<String>()
            if (trs != null) {
                for (i in 0 until trs.length()) {
                    val tr = trs.getJSONObject(i).optJSONArray("tr")
                    if (tr != null) for (j in 0 until tr.length()) {
                        val l = tr.getJSONObject(j).optJSONObject("l")
                        val arr = l?.optJSONArray("i")
                        if (arr != null) for (k in 0 until arr.length()) parts.add(arr.getString(k))
                    }
                }
            }
            if (parts.isNotEmpty()) {
                out.put("meaning", parts.take(8).joinToString("；"))
                out.put("pos", extractPos(parts.first()))
            }
        }
        return out
    }

    /** 从"v. 抛弃…"提取词性缩写 */
    private fun extractPos(firstMeaning: String): String {
        val m = Regex("^([a-z]+)\\.").find(firstMeaning.trim())
        return m?.groupValues?.get(1)?.plus(".") ?: ""
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://dict.youdao.com/")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code == 429) throw Exception("有道查询频繁（429），请稍后重试")
            if (code >= 500) throw Exception("有道词典服务暂时不可用（$code）")
            if (code !in 200..299) throw Exception("有道查询失败（$code）：${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }
}
