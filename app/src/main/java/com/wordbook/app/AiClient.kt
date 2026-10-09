package com.wordbook.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenCode Go 识别客户端：直连 deepseek-v4.1-flash 多模态接口。
 * Key 仅存于本机原生层，不进入前端页面。
 * 支持四种识别模式：black（黑笔圈画）/ red（红笔圈写）/ auto（自动判断）/ smart（智能选词）。
 */
/**
 * AI 识别客户端：支持 OpenCode Go 与 DeepSeek 官方 API 两种渠道。
 * 模型固定 deepseek-v4.1-flash；API Key 由用户在「我的 → API 设置」中填写，
 * 仅保存在本机 SharedPreferences，不写死在代码、不上传云端。
 */
class AiClient(
    private val provider: () -> String,
    private val apiKey: () -> String
) {

    /** 最近一次请求的 token 消耗（usage），供 JS 桥透传给前端统计 */
    @Volatile
    private var lastUsage: JSONObject? = null

    fun lastUsage(): JSONObject? = lastUsage

    companion object {
        // ===== 各 provider 接入参数 =====
        private const val ENDPOINT_OPENCODE = "https://opencode.ai/zen/go/v1/chat/completions"
        private const val ENDPOINT_DEEPSEEK = "https://api.deepseek.com/chat/completions"
        /** OpenCode Go 网关模型名 */
        private const val MODEL_OPENCODE = "deepseek-v4.1-flash"
        /** DeepSeek 官方 API 支持的模型名（官方不识别 deepseek-v4.1-flash） */
        private const val MODEL_DEEPSEEK = "deepseek-flash"

        private const val MODE_BLACK = "black"
        private const val MODE_RED = "red"
        private const val MODE_AUTO = "auto"
        private const val MODE_SMART = "smart"

        // 公共输出格式说明
        private const val OUTPUT_SPEC = """
5. 只输出 JSON，不要输出任何其他文字或 markdown 代码块标记。

输出格式：
{"words":[{"word":"...","phonetic":"...","pos":"...","meaning":"...","sentence":"...","source":"..."}]}
"""

        private val PROMPT_BLACK = """
你是英语学习助教。请识别这张英语阅读理解照片中被用户【用黑色笔（黑笔圈、黑下划线、黑勾等）手动圈画】的英文生词。
要求：
1. 只输出被黑色笔迹圈画/标记的单词；红色笔迹、其他颜色笔迹或未标记的单词一律不要输出。
2. 若单词同时出现在正文和题目选项中，只输出一次，并在 source 字段注明"正文/题目选项"。
3. 为每个单词提供：word（原文拼写）、phonetic（英式音标）、pos（词性缩写，如 n./v./adj./adv.）、meaning（中文释义）、sentence（该单词所在的原文完整句子，照抄原文）、source（出现位置：正文/题目选项）。
4. 句子必须来自原文，不要自己编造。
""" + OUTPUT_SPEC

        private val PROMPT_RED = """
你是英语学习助教。请识别这张英语阅读理解照片中被用户【用红色笔（红笔圈、红下划线、红勾等）手动圈写】的英文生词。
要求：
1. 只输出被红色/粉色笔迹圈写/标记的单词；黑色笔迹、其他颜色笔迹或未标记的单词一律不要输出。
2. 若单词同时出现在正文和题目选项中，只输出一次，并在 source 字段注明"正文/题目选项"。
3. 为每个单词提供：word（原文拼写）、phonetic（英式音标）、pos（词性缩写，如 n./v./adj./adv.）、meaning（中文释义）、sentence（该单词所在的原文完整句子，照抄原文）、source（出现位置：正文/题目选项）。
4. 句子必须来自原文，不要自己编造。
""" + OUTPUT_SPEC

        private val PROMPT_AUTO = """
你是英语学习助教。请识别这张英语阅读理解照片中被用户【手动圈画或标记】的英文生词（圈画可能是下划线、圆圈、荧光笔、打勾等，颜色不限）。
要求：
1. 只输出被圈画/标记的单词；正文中出现但未被标记的单词不要输出。
2. 若单词同时出现在正文和题目选项中，只输出一次，并在 source 字段注明"正文/题目选项"。
3. 为每个单词提供：word（原文拼写）、phonetic（英式音标）、pos（词性缩写，如 n./v./adj./adv.）、meaning（中文释义）、sentence（该单词所在的原文完整句子，照抄原文）、source（出现位置：正文/题目选项）。
4. 句子必须来自原文，不要自己编造。
""" + OUTPUT_SPEC

        private val PROMPT_SMART = """
你是英语考研辅导老师。这张照片是一篇英语阅读理解文章（可能包含正文与题目选项）。请忽略纸面上的一切圈画标记，通读全文，站在"考研英语阅读"的角度，挑选出【对考生最有学习价值、最值得收录进生词本】的英文生词（建议 8~20 个）。
选择标准（按优先级）：
- 影响理解文意的关键实词（动词、名词、形容词、副词）；过于基础的简单词（如 the、and、have、there）不要选；
- 考研真题中出现频率高或高频派生词（如词根词缀变化）；
- 一词多义、熟词僻义、近义辨析价值高的词；
- 生僻但属于考研大纲范围的词优先于超纲冷僻词。

要求：
1. 只输出你挑选的单词；为每个单词提供：word（原文拼写）、phonetic（英式音标）、pos（词性缩写）、meaning（中文释义）、sentence（该单词所在的原文完整句子，照抄原文）、source（出现位置：正文/题目选项）。
2. 额外为每个单词提供 priority（"高"/"中"/"低"）表示收录优先级，以及 reason（一句中文说明为何值得收录，如"考研高频词""熟词僻义""影响理解关键句"）。
3. 句子必须来自原文，不要自己编造。
4. 只输出 JSON，不要输出任何其他文字或 markdown 代码块标记。

输出格式：
{"words":[{"word":"...","phonetic":"...","pos":"...","meaning":"...","sentence":"...","source":"...","priority":"高","reason":"..."}]}
"""

        private fun jsSafe(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }

    /** 当前 provider 对应的模型名 */
    private fun modelName(): String =
        if (provider() == "deepseek") MODEL_DEEPSEEK else MODEL_OPENCODE

    private fun promptFor(mode: String): String = when (mode) {
        MODE_BLACK -> PROMPT_BLACK
        MODE_RED -> PROMPT_RED
        MODE_SMART -> PROMPT_SMART
        else -> PROMPT_AUTO
    }

    private fun temperatureFor(mode: String): Double =
        if (mode == MODE_SMART) 0.3 else 0.1

    /** 查词补全：输入英文单词，自动补音标/词性/中文释义/例句。返回 JSONObject（word/phonetic/pos/meaning/sentence）。 */
    @Throws(Exception::class)
    fun lookupWord(word: String): JSONObject {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return doLookup(word)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val retriable = msg.contains("429") || msg.contains("暂时不可用") ||
                        msg.contains("空响应") || msg.contains("截断") || msg.contains("未返回") ||
                        msg.contains(" 500 ") || msg.contains(" 502 ") || msg.contains(" 503 ") ||
                        msg.contains("timeout") || msg.contains("超时")
                if (retriable && attempt < 3) { Thread.sleep(2000L * attempt); continue }
                throw e
            }
        }
    }

    private fun doLookup(word: String): JSONObject {
        val prompt = """
你是英语词典助手。请为英文单词 "${word}" 输出以下字段：
1. word：该单词本身（保持原拼写，与输入一致）；
2. phonetic：英式音标，格式如 /ˈrɪəl/；
3. pos：词性缩写（n./v./adj./adv./prep. 等）；
4. meaning：准确、简洁、适合背单词的中文释义（若是常见多义词给出 2~3 个核心义项，用分号分隔）；
5. sentence：一个包含该单词的地道英文例句（中等难度，适合英语学习者），并确保能体现该词的核心用法。

只输出 JSON，不要输出任何其他文字或 markdown 代码块标记。
输出格式：
{"word":"...","phonetic":"...","pos":"...","meaning":"...","sentence":"..."}
"""
        val body = JSONObject().apply {
            put("model", modelName())
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                }
            ))
            put("temperature", 0.2)
            put("max_tokens", 8192)
        }

        val conn = URL(if (provider() == "deepseek") ENDPOINT_DEEPSEEK else ENDPOINT_OPENCODE).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val key = apiKey().trim()
            if (key.isEmpty()) throw Exception("尚未填写 API Key，请到「我的 → API 设置」填写")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("x-opencode-session", "wordbook-app-session")
            conn.setRequestProperty("User-Agent", "wordbook-app/1.0 (android)")

            // 固定长度流式上传：禁用 chunked，降低中间代理截断风险
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""

            if (code == 429) throw Exception("OpenCode Go 限流（429），请稍后重试")
            if (code >= 500) throw Exception("查词服务暂时不可用（$code）")
            if (code !in 200..299) throw Exception("查词请求失败（$code）：${text.take(200)}")
            if (text.isBlank()) throw Exception("OpenCode Go 返回空响应（HTTP $code），服务波动或网络代理干扰，已自动重试")

            val json = JSONObject(text)
            lastUsage = json.optJSONObject("usage")
            val choice = json.getJSONArray("choices").getJSONObject(0)
            val content = choice.getJSONObject("message").optString("content", "")
            val finish = choice.optString("finish_reason", "")
            if (content.isBlank()) {
                if (finish == "length") throw Exception("模型推理过长被截断，未返回查词结果，已自动重试")
                throw Exception("模型未返回查词结果，已自动重试")
            }
            return extractLookup(content)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractLookup(content: String): JSONObject {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```$"), "")
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) s = s.substring(start, end + 1)
        val obj = JSONObject(s)
        val out = JSONObject()
        out.put("word", obj.optString("word"))
        out.put("phonetic", obj.optString("phonetic"))
        out.put("pos", obj.optString("pos"))
        out.put("meaning", obj.optString("meaning"))
        out.put("sentence", obj.optString("sentence"))
        return out
    }

    /** 识别照片生词。dataUrl 形如 data:image/jpeg;base64,xxxx。mode 见 MODE_*。返回 words JSONArray。
     *  服务端空响应/限流/5xx 时指数退避自动重试（最多 2 次，间隔 2s/3s），消化偶发链路波动。 */
    @Throws(Exception::class)
    fun recognize(dataUrl: String, mode: String = MODE_AUTO): JSONArray {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return doRecognize(dataUrl, mode)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val retriable = msg.contains("429") || msg.contains("暂时不可用") ||
                        msg.contains("空响应") || msg.contains("截断") || msg.contains("未返回") ||
                        msg.contains(" 500 ") || msg.contains(" 502 ") || msg.contains(" 503 ") ||
                        msg.contains("timeout") || msg.contains("超时")
                if (retriable && attempt < 3) { Thread.sleep(2000L * attempt); continue }
                throw e
            }
        }
    }

    private fun doRecognize(dataUrl: String, mode: String): JSONArray {
        val prefix = dataUrl.substringBefore(',').takeIf { it.startsWith("data:") } ?: "data:image/jpeg"
        val base64 = dataUrl.substringAfter(',')
        val body = JSONObject().apply {
            put("model", modelName())
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray()
                        .put(JSONObject().apply { put("type", "text"); put("text", promptFor(mode)) })
                        .put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "$prefix,$base64")
                                put("detail", "high")
                            })
                        })
                    )
                }
            ))
            put("temperature", temperatureFor(mode))
            // deepseek 为推理模型，思考 token 计入 max_tokens；8192 易在长阅读上截断，给到 16384
            put("max_tokens", 16384)
        }

        val conn = URL(if (provider() == "deepseek") ENDPOINT_DEEPSEEK else ENDPOINT_OPENCODE).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val key = apiKey().trim()
            if (key.isEmpty()) throw Exception("尚未填写 API Key，请到「我的 → API 设置」填写")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("x-opencode-session", "wordbook-app-session")
            conn.setRequestProperty("User-Agent", "wordbook-app/1.0 (android)")

            // 固定长度流式上传：禁用 chunked，降低中间代理截断风险
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""

            if (code == 429) throw Exception("OpenCode Go 限流（429），请稍后重试")
            if (code >= 500) throw Exception("识别服务暂时不可用（$code）")
            if (code !in 200..299) throw Exception("识别请求失败（$code）：${text.take(200)}")
            if (text.isBlank()) throw Exception("OpenCode Go 返回空响应（HTTP $code），服务波动或网络代理干扰，已自动重试")

            val json = JSONObject(text)
            lastUsage = json.optJSONObject("usage")
            val choice = json.getJSONArray("choices").getJSONObject(0)
            val content = choice.getJSONObject("message").optString("content", "")
            val finish = choice.optString("finish_reason", "")
            if (content.isBlank()) {
                if (finish == "length") throw Exception("模型推理过长被截断，未返回识别内容，已自动重试")
                throw Exception("模型未返回识别内容，已自动重试")
            }
            return extractWords(content)
        } finally {
            conn.disconnect()
        }
    }

    /** 最小请求测试连接：验证 Key / 网络 / 模型可用。返回 true 表示可用。 */
    @Throws(Exception::class)
    fun testConnection(): Boolean {
        val body = JSONObject().apply {
            put("model", modelName())
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", "Reply with exactly: OK")
                }
            ))
            put("temperature", 0.1)
            put("max_tokens", 512)
        }
        val conn = URL(if (provider() == "deepseek") ENDPOINT_DEEPSEEK else ENDPOINT_OPENCODE).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val key = apiKey().trim()
            if (key.isEmpty()) throw Exception("尚未填写 API Key，请先到「我的 → API 设置」填写")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("x-opencode-session", "wordbook-app-session")
            conn.setRequestProperty("User-Agent", "wordbook-app/1.0 (android)")
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code == 429) throw Exception("限流（429），请稍后重试")
            if (code >= 500) throw Exception("服务暂时不可用（$code）")
            if (code !in 200..299) throw Exception("连接失败（$code）：${text.take(120)}")
            if (text.isBlank()) throw Exception("服务返回空响应，请重试")
            // 200 + 有效响应即视为连接成功（Key / 网络 / 模型名都正确）；
            // 不要求 content 非空——deepseek 为推理模型，小 token 下 content 可能为空，但与连接无关
            val json = JSONObject(text)
            if (json.getJSONArray("choices").length() == 0) throw Exception("响应缺少 choices，请检查模型名是否可用")
            return true
        } finally {
            conn.disconnect()
        }
    }

    /** 兼容模型返回的纯 JSON 或带 markdown 围栏的文本 */
    private fun extractWords(content: String): JSONArray {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```$"), "")
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) s = s.substring(start, end + 1)
        val obj = JSONObject(s)
        return obj.optJSONArray("words") ?: JSONArray()
    }
}
