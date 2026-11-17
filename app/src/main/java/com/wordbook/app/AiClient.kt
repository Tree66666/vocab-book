package com.wordbook.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 识别客户端：支持 OpenCode Go 与 DeepSeek 官方 API 两种渠道，模型固定 deepseek-v4.1-flash。
 * 识别链路收敛为单一智能选词：系统基础规则 → 个性化策略卡 → 选中预设侧重 → 本次手写补充，分层组装提示词。
 * API Key 由用户在「我的 → API 设置」中填写，仅保存在本机原生层（加密存储），不进入前端页面、不上传云端。
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

        // 智能选词基础规则（唯一识别链路）。分层组装：基础规则 → 策略卡 → 预设侧重 → 本次手写补充。
        private val PROMPT_SMART = """
你是英语考研辅导老师。这张照片是一篇英语阅读理解文章（可能包含正文与题目选项）。请通读全文，站在"考研英语阅读"的角度，挑选出【对考生最有学习价值、最值得收录进生词本】的英文生词（建议 8~20 个）。
选择标准（按优先级）：
- 影响理解文意的关键实词（动词、名词、形容词、副词）；过于基础的简单词（如 the、and、have、there）不要选；
- 考研真题中出现频率高或高频派生词（如词根词缀变化）；
- 一词多义、熟词僻义、近义辨析价值高的词；
- 生僻但属于考研大纲范围的词优先于超纲冷僻词；
- 纸面上的圈画、下划线等标记是用户留下的参考信号：被圈出或划线的词通常是用户不认识的词，请多留意、在同等条件下适当优先考虑它们；但圈画标记只作参考、不作为最高优先级，最终仍以单词本身的学习价值（难度、考研词频、上下文重要性）为全局判断依据，没有被圈画的重要难词同样应该选出。

关于做题痕迹（重要）：纸面上有些勾画、批注、划线、打勾只是做题过程中的痕迹（比如标注重点句、断句、核对选项），不一定都是用户不认识的生词。请结合这个词是否真正影响文意理解、是否有复习价值来判断，不要把单纯的做题过程标记误当成生词。

价值分级（每个候选都要标注，供用户审阅时排序与取舍）：
- value="正常"：值得收录的难词、高频词、熟词僻义或关键实词；
- value="基础"：过于简单、用户大概率已经掌握的基础词；
- value="冷僻"：生僻超纲、考研复现价值低、背了意义不大的词；
- valueReason：一句中文说明分级理由（如"基础高频常用词""超纲生僻，真题复现低""熟词僻义，值得记"）。

要求：
1. 只输出你挑选的单词；为每个单词提供：word（原文拼写）、phonetic（英式音标）、pos（词性缩写）、meaning（中文释义）、sentence（该单词所在的原文完整句子，照抄原文）、source（出现位置：正文/题目选项）。
2. 额外为每个单词提供 priority（"高"/"中"/"低"）表示收录优先级，reason（一句中文说明为何值得收录），以及 value、valueReason 两个分级字段。
3. 句子必须来自原文，不要自己编造。
4. 只输出 JSON，不要输出任何其他文字或 markdown 代码块标记。

输出格式：
{"words":[{"word":"...","phonetic":"...","pos":"...","meaning":"...","sentence":"...","source":"...","priority":"高","reason":"...","value":"正常","valueReason":"..."}]}
"""

        // 难度档位（漂移限幅按此顺序计算索引差）
        private val DIFFICULTIES = arrayOf("偏易", "中等", "中等偏难", "偏难")

        private val PROMPT_STRATEGY = """
你是这个用户的英语选词策略维护者。下面是你过去在 smart（智能选词）模式下输出的候选词，
以及用户实际采纳（保存进生词本）/丢弃（删除）的反馈。请总结出下一版「选词策略卡」。

要求：
1. 必须从下面具体词的采纳/删除事实归纳，不要套用抽象标准或想当然；
2. difficulty 只能取这四个值之一：偏易 / 中等 / 中等偏难 / 偏难；
3. prefer 与 avoid 中每一项都要含 rule（一句中文规则）与 examples（从下面真实词中取 2~5 个具体词例）；
4. count 的 min/max 必须在 5~20 之间，且 min < max；
5. notes 用一句话写出关键依据，必须带具体数字（如"删除的 16 个词中 11 个是基础词"）；
6. 只输出 JSON，不要输出任何其他文字或 markdown 代码块标记。

输出格式：
{"difficulty":"...","prefer":[{"rule":"...","examples":["..."]}],"avoid":[{"rule":"...","examples":["..."]}],"count":{"min":8,"max":16},"notes":"..."}
"""

        private fun jsSafe(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }

    /** 当前 provider 对应的模型名 */
    private fun modelName(): String =
        if (provider() == "deepseek") MODEL_DEEPSEEK else MODEL_OPENCODE

    /** 分层组装识别提示词：基础规则 → 个性化策略卡 → 选中预设侧重 → 本次手写补充 */
    private fun buildRecognizePrompt(strategy: JSONObject?, presetText: String?, customText: String?): String {
        val sb = StringBuilder(PROMPT_SMART)
        sb.append(strategyText(strategy))
        if (!presetText.isNullOrBlank()) sb.append("\n\n【本次选词侧重（预设）】\n").append(presetText.trim())
        if (!customText.isNullOrBlank()) sb.append("\n\n【本次补充说明（用户手写）】\n").append(customText.trim())
        return sb.toString()
    }

    /** 把策略卡渲染成具体的个性化注入文本（含具体词例），无策略时返回空串 */
    private fun strategyText(s: JSONObject?): String {
        if (s == null) return ""
        val items: (JSONArray?) -> String = { arr ->
            val parts = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ex = o.optJSONArray("examples")
                val exs = if (ex != null && ex.length() > 0)
                    "（如 " + (0 until ex.length()).joinToString("、") { ex.optString(it) } + "）" else ""
                parts.add(o.optString("rule") + exs)
            }
            parts.joinToString("；")
        }
        val sb = StringBuilder("\n\n【本用户个性化选词策略（v")
        sb.append(s.optInt("version")).append("，依据你过去选词后用户实际采纳/丢弃的反馈）】\n")
        sb.append("- 用户当前难度线：").append(s.optString("difficulty")).append("\n")
        sb.append("- 优先选：").append(items(s.optJSONArray("prefer"))).append("\n")
        sb.append("- 不要选：").append(items(s.optJSONArray("avoid"))).append("\n")
        val cnt = s.optJSONObject("count")
        if (cnt != null) sb.append("- 本次选 ").append(cnt.optInt("min")).append("~")
            .append(cnt.optInt("max")).append(" 个\n")
        if (s.optString("notes").isNotEmpty()) sb.append("- 依据：").append(s.optString("notes")).append("\n")
        return sb.toString()
    }

    /** 触发 AI 总结反馈 → 生成并保存新版策略卡，返回策略卡 JSON */
    @Throws(Exception::class)
    fun updateStrategy(db: WordDb): JSONObject {
        val digest = db.buildFeedbackDigest()
        if (digest.optInt("total") < 5)
            throw Exception("反馈样本不足（当前 ${digest.optInt("total")} 条），再多识别几次后再更新")
        val content = doStrategyCall(PROMPT_STRATEGY + "\n【反馈事实】\n" + digestText(digest))
        val card = parseStrategyCard(content)
        validateCard(card)
        val cur = db.getActiveStrategy()
        if (cur != null) {
            val iOld = DIFFICULTIES.indexOf(cur.optString("difficulty")).coerceAtLeast(0)
            val iNew = DIFFICULTIES.indexOf(card.optString("difficulty"))
            if (iNew >= 0 && kotlin.math.abs(iNew - iOld) > 1)
                throw Exception("AI 建议难度档位跳变过大（${cur.optString("difficulty")}→${card.optString("difficulty")}），已拦截，请手动重试")
        }
        card.put("version", (cur?.optInt("version") ?: 0) + 1)
        db.saveStrategy(card)
        return card
    }

    /** 反馈事实清单渲染（具体词 + 具体数字） */
    private fun digestText(d: JSONObject): String {
        val wordList: (JSONArray) -> String = { arr ->
            val parts = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                parts.add(o.optString("word") + "（" + o.optString("priority").ifEmpty { "未标注" } +
                    "，" + o.optString("reason") + "）")
            }
            parts.joinToString("、")
        }
        val sb = StringBuilder()
        sb.append("【总体】").append(d.optInt("sessions")).append(" 次会话，共 ").append(d.optInt("total"))
            .append(" 个候选：采纳 ").append(d.optInt("adopted")).append("、丢弃 ").append(d.optInt("rejected"))
            .append("、编辑过 ").append(d.optInt("edited")).append("。\n【按当时 priority】\n")
        val bp = d.optJSONArray("byPriority")
        for (i in 0 until bp.length()) {
            val o = bp.optJSONObject(i)
            sb.append("- ").append(o.optString("priority")).append("：共 ").append(o.optInt("total"))
                .append("，采纳 ").append(o.optInt("adopted")).append("\n")
        }
        sb.append("【被丢弃的词】").append(wordList(d.optJSONArray("rejectedWords"))).append("\n")
        sb.append("【被采纳的词】").append(wordList(d.optJSONArray("keptWords"))).append("\n")
        val rp = d.optJSONArray("repeated")
        val rparts = ArrayList<String>()
        for (i in 0 until rp.length()) {
            val o = rp.optJSONObject(i)
            rparts.add(o.optString("word") + "×" + o.optInt("times") + "（采纳" + o.optInt("adopted") + "）")
        }
        sb.append("【反复出现的词】").append(rparts.joinToString("、")).append("\n")
        return sb.toString()
    }

    private fun parseStrategyCard(content: String): JSONObject {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```$"), "")
        }
        val start = s.indexOf('{'); val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) s = s.substring(start, end + 1)
        return JSONObject(s)
    }

    private fun validateCard(card: JSONObject) {
        if (card.optString("difficulty") !in DIFFICULTIES)
            throw Exception("策略卡 difficulty 非法：${card.optString("difficulty")}")
        if (card.optJSONArray("prefer") == null || card.optJSONArray("avoid") == null)
            throw Exception("策略卡缺少 prefer/avoid 数组")
        val cnt = card.optJSONObject("count")
            ?: throw Exception("策略卡缺少 count")
        val mn = cnt.optInt("min"); val mx = cnt.optInt("max")
        if (mn !in 5..20 || mx !in 5..20 || mn >= mx)
            throw Exception("策略卡 count 范围非法：$mn~$mx")
    }

    private fun doStrategyCall(prompt: String): String {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val body = JSONObject().apply {
                    put("model", modelName())
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user"); put("content", prompt)
                    }))
                    put("temperature", 0.2)
                    put("max_tokens", 4096)
                }
                val conn = URL(if (provider() == "deepseek") ENDPOINT_DEEPSEEK else ENDPOINT_OPENCODE)
                    .openConnection() as HttpURLConnection
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
                    if (code !in 200..299) throw Exception("策略总结请求失败（$code）")
                    if (text.isBlank()) throw Exception("返回空响应，已自动重试")
                    val json = JSONObject(text)
                    lastUsage = json.optJSONObject("usage")
                    val c = json.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content", "")
                    if (c.isBlank()) throw Exception("模型未返回策略卡，已自动重试")
                    return c
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                if (e is java.net.UnknownHostException) throw Exception("网络异常：无法解析服务器地址，请检查网络后重试")
                if (e is java.net.SocketTimeoutException) throw Exception("网络超时，请检查网络后重试")
                if (e is java.net.ConnectException) throw Exception("网络连接被中断，请检查网络后重试")
                val retriable = (e.message ?: "").let {
                    it.contains("429") || it.contains("空响应") || it.contains("未返回") ||
                        it.contains("暂时不可用") ||
                        it.contains(" 500 ") || it.contains(" 502 ") || it.contains(" 503 ")
                }
                if (retriable && attempt < 3) { Thread.sleep(2000L * attempt); continue }
                throw e
            }
        }
    }

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
                if (e is java.net.UnknownHostException) throw Exception("网络异常：无法解析服务器地址，请检查网络后重试")
                if (e is java.net.SocketTimeoutException) throw Exception("网络超时，请检查网络后重试")
                if (e is java.net.ConnectException) throw Exception("网络连接被中断，请检查网络后重试")
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

    /** 为某词生成关联词候选（派生/易混/搭配），返回 links JSONArray。宁缺毋滥，最多 6 个。 */
    @Throws(Exception::class)
    fun suggestLinks(word: String): JSONArray {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return doSuggestLinks(word)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (e is java.net.UnknownHostException) throw Exception("网络异常：无法解析服务器地址，请检查网络后重试")
                if (e is java.net.SocketTimeoutException) throw Exception("网络超时，请检查网络后重试")
                if (e is java.net.ConnectException) throw Exception("网络连接被中断，请检查网络后重试")
                val retriable = msg.contains("429") || msg.contains("暂时不可用") ||
                        msg.contains("空响应") || msg.contains("未返回") ||
                        msg.contains(" 500 ") || msg.contains(" 502 ") || msg.contains(" 503 ") ||
                        msg.contains("timeout") || msg.contains("超时")
                if (retriable && attempt < 3) { Thread.sleep(2000L * attempt); continue }
                throw e
            }
        }
    }

    private fun doSuggestLinks(word: String): JSONArray {
        val prompt = """
你是英语词汇拓展助手。围绕单词 "${word}"，从以下三类补充值得一起记的关联词，宁缺毋滥：
- 派生：同根词族（名词/动词/形容词/副词等词形变化，如 derive → derivation/derivative）；
- 易混：形近或义近、容易混淆、需要辨析的词；
- 搭配：与该词高频共现的固定短语、词组或搭配。
要求：
1. 最多 6 个；只给确实有辨析或记忆价值的，不要生造、不要堆砌；
2. relation 只能取 "派生" / "易混" / "搭配"；
3. meaning 给简洁中文释义（搭配给短语含义）；
4. 只输出 JSON，不要输出任何其他文字或 markdown 代码块标记。
输出格式：
{"links":[{"linked":"...","relation":"派生","meaning":"..."}]}
""".trimIndent()
        val body = JSONObject().apply {
            put("model", modelName())
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user"); put("content", prompt)
            }))
            put("temperature", 0.3)
            put("max_tokens", 4096)
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
            if (code !in 200..299) throw Exception("关联词请求失败（$code）")
            if (text.isBlank()) throw Exception("返回空响应，已自动重试")
            val json = JSONObject(text)
            lastUsage = json.optJSONObject("usage")
            val content = json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "")
            if (content.isBlank()) throw Exception("模型未返回关联词，已自动重试")
            return extractLinks(content)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractLinks(content: String): JSONArray {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("\\s*```$"), "")
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) s = s.substring(start, end + 1)
        val obj = JSONObject(s)
        return obj.optJSONArray("links") ?: JSONArray()
    }

    /** 识别照片生词（唯一智能选词链路）。dataUrl 形如 data:image/jpeg;base64,xxxx。
     *  presetText/customText 为分层注入的预设侧重与本次手写补充；strategy 为本地策略卡。
     *  服务端空响应/限流/5xx 时指数退避自动重试（最多 3 次），消化偶发链路波动。 */
    @Throws(Exception::class)
    fun recognize(dataUrl: String, strategy: JSONObject? = null, presetText: String = "", customText: String = ""): JSONArray {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return doRecognize(dataUrl, strategy, presetText, customText)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                // 网络类异常给出友好中文提示（失败项可单张重试，不自动重试以免拖慢队列）
                if (e is java.net.UnknownHostException) throw Exception("网络异常：无法解析服务器地址，请检查网络后重试")
                if (e is java.net.SocketTimeoutException) throw Exception("网络超时，请检查网络后重试")
                if (e is java.net.ConnectException) throw Exception("网络连接被中断，请检查网络后重试")
                val retriable = msg.contains("429") || msg.contains("暂时不可用") ||
                        msg.contains("空响应") || msg.contains("截断") || msg.contains("未返回") ||
                        msg.contains(" 500 ") || msg.contains(" 502 ") || msg.contains(" 503 ") ||
                        msg.contains("timeout") || msg.contains("超时")
                if (retriable && attempt < 3) { Thread.sleep(2000L * attempt); continue }
                throw e
            }
        }
    }

    private fun doRecognize(dataUrl: String, strategy: JSONObject?, presetText: String, customText: String): JSONArray {
        val prefix = dataUrl.substringBefore(',').takeIf { it.startsWith("data:") } ?: "data:image/jpeg"
        val base64 = dataUrl.substringAfter(',')
        val body = JSONObject().apply {
            put("model", modelName())
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray()
                        .put(JSONObject().apply { put("type", "text"); put("text", buildRecognizePrompt(strategy, presetText, customText)) })
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
            put("temperature", 0.3)
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
            // 网络类异常提示友好化（在 finally 之后 catch 由调用方处理；此处仅兜底）
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
