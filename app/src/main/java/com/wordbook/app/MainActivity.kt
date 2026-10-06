package com.wordbook.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.content.SharedPreferences
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.view.Gravity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生词错题本 · 单机版（开源版）
 * 本地前端页面（assets/index.html）+ 本地数据库（SQLite）+ AI 识别。
 * AI 渠道与 API Key 由用户在「我的 → API 设置」中自行填写（OpenCode Go / DeepSeek 官方二选一），
 * 仅保存在本机，不内置、不上传。数据全部存储在本机。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var db: WordDb
    private lateinit var wordFreq: WordFreq
    private lateinit var logStore: LogStore
    private lateinit var prefs: SharedPreferences
    private lateinit var ai: AiClient
    private val youdao = YoudaoClient()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaPlayer: MediaPlayer? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = WordDb(this)
        wordFreq = WordFreq(this)
        logStore = LogStore(this)
        prefs = getSharedPreferences("vocab_settings", MODE_PRIVATE)
        ai = AiClient(
            provider = { prefs.getString("provider", "opencode") ?: "opencode" },
            apiKey = {
                val p = prefs.getString("provider", "opencode") ?: "opencode"
                SecretStore.decrypt(prefs.getString("api_key_" + p, "") ?: "")
            }
        )
        logStore.log("启动", "App 启动，SDK=${Build.VERSION.SDK_INT}，DB v${WordDb.DB_VERSION}，词库 ${wordFreq.size} 词")
        initTts()

        webView = WebView(this)

        // 启动加载指示：避免 WebView 加载前端页时的白屏
        val root = FrameLayout(this)
        val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleLarge)
        spinner.isIndeterminate = true
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.CENTER
        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(spinner, lp)
        setContentView(root)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                spinner.visibility = android.view.View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                spinner.visibility = android.view.View.GONE
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("file://")) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: Exception) { true }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    openGallery(true)
                } else {
                    showPickDialog()
                }
                return true
            }
        }

        webView.addJavascriptInterface(Bridge(), "WordBook")
        webView.loadUrl("file:///android_asset/index.html")
    }

    // ================= JS 桥 =================

    inner class Bridge {

        @JavascriptInterface
        fun getSettings(): String {
            return try {
                val provider = prefs.getString("provider", "opencode") ?: "opencode"
                fun keyNonEmpty(p: String): Boolean =
                    !SecretStore.decrypt(prefs.getString("api_key_" + p, "") ?: "").trim().isEmpty()
                JSONObject()
                    .put("provider", provider)
                    .put("hasKey", keyNonEmpty(provider))
                    .put("opencodeHasKey", keyNonEmpty("opencode"))
                    .put("deepseekHasKey", keyNonEmpty("deepseek"))
                    .put("model", if (provider == "deepseek") "deepseek-flash" else "deepseek-v4.1-flash")
                    .toString()
            } catch (e: Exception) { "{}" }
        }

        @JavascriptInterface
        fun saveSettings(json: String): String {
            return try {
                val obj = JSONObject(json)
                val provider = obj.optString("provider", "opencode")
                val ed = prefs.edit()
                // clear 标志：清除当前渠道已保存的 Key
                if (obj.optBoolean("clear", false)) {
                    ed.remove("api_key_" + provider)
                    ed.putString("provider", provider)
                    ed.apply()
                    return JSONObject().put("ok", true).put("cleared", true).toString()
                }
                // 空 Key 只切换渠道，不修改已保存的 Key
                val key = obj.optString("apiKey", "").trim()
                if (key.isNotEmpty()) {
                    ed.putString("api_key_" + provider, SecretStore.encrypt(key))
                }
                ed.putString("provider", provider)
                ed.apply()
                JSONObject().put("ok", true).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "保存失败").toString()
            }
        }

        /** 测试连接：按指定渠道测试（渠道独立取 Key / 端点，互不串扰） */
        @JavascriptInterface
        fun testApi(provider: String, callbackId: String) {
            val safeProvider = if (provider == "deepseek") "deepseek" else "opencode"
            val key = SecretStore.decrypt(prefs.getString("api_key_" + safeProvider, "") ?: "")
            if (key.trim().isEmpty()) {
                runOnUiThread {
                    val js = "window.__apiTest && window.__apiTest(" + jsStr(callbackId) + "," +
                            jsStr(JSONObject().put("ok", false).put("error", "该渠道尚未配置 API Key").toString()) + ")"
                    webView.evaluateJavascript(js, null)
                }
                return
            }
            Thread {
                var payload = ""
                try {
                    val testAi = AiClient(
                        provider = { safeProvider },
                        apiKey = { key }
                    )
                    val ok = testAi.testConnection()
                    payload = if (ok) JSONObject().put("ok", true).toString()
                    else JSONObject().put("ok", false).put("error", "连接失败，请检查 Key 与网络").toString()
                } catch (e: Exception) {
                    payload = JSONObject().put("ok", false).put("error", e.message ?: "连接失败").toString()
                }
                runOnUiThread {
                    val js = "window.__apiTest && window.__apiTest(" + jsStr(callbackId) + "," + jsStr(payload) + ")"
                    webView.evaluateJavascript(js, null)
                }
            }.start()
        }

        @JavascriptInterface
        fun getWords(filter: String): String {
            return try { db.listWords(if (filter.isBlank()) null else JSONObject(filter)).toString() }
            catch (e: Exception) { "[]" }
        }

        @JavascriptInterface
        fun saveWord(json: String): String {
            return try {
                val id = db.saveWord(JSONObject(json))
                JSONObject().put("ok", true).put("id", id).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "保存失败").toString()
            }
        }

        @JavascriptInterface
        fun deleteWord(id: Long): String {
            db.deleteWord(id)
            return "ok"
        }

        @JavascriptInterface
        fun setMastery(id: Long, mastery: String): String {
            db.setMastery(id, mastery)
            return "ok"
        }

        @JavascriptInterface
        fun getStats(): String {
            return try {
                val o = db.getStats()
                o.put("tkOpenPrompt", prefs.getInt("ai_tk_opencode_p", 0))
                o.put("tkOpenCompl", prefs.getInt("ai_tk_opencode_c", 0))
                o.put("tkOpenReq", prefs.getInt("ai_tk_opencode_r", 0))
                o.put("tkDeepPrompt", prefs.getInt("ai_tk_deepseek_p", 0))
                o.put("tkDeepCompl", prefs.getInt("ai_tk_deepseek_c", 0))
                o.put("tkDeepReq", prefs.getInt("ai_tk_deepseek_r", 0))
                o.toString()
            } catch (e: Exception) { "{}" }
        }

        /** 记录当前渠道一次 AI 调用的 token 消耗（OpenCode / DeepSeek 分开累计） */
        private fun recordAiUsage() {
            val u = ai.lastUsage() ?: return
            val p = prefs.getString("provider", "opencode") ?: "opencode"
            val pre = "ai_tk_" + p + "_"
            prefs.edit()
                .putInt(pre + "p", prefs.getInt(pre + "p", 0) + u.optInt("prompt_tokens", 0))
                .putInt(pre + "c", prefs.getInt(pre + "c", 0) + u.optInt("completion_tokens", 0))
                .putInt(pre + "r", prefs.getInt(pre + "r", 0) + 1)
                .apply()
        }

        @JavascriptInterface
        fun getDueReviews(limit: Int): String {
            return try { db.getDueReviews(if (limit <= 0) 30 else limit).toString() }
            catch (e: Exception) { "[]" }
        }

        @JavascriptInterface
        fun submitReview(wordId: Long, grade: Int, mode: String): String {
            return try { db.submitReview(wordId, grade, mode).toString() }
            catch (e: Exception) { JSONObject().put("ok", false).put("error", e.message ?: "提交失败").toString() }
        }

        /** 异步识别：完成后通过 window.__aiResult(callbackId, json) 回调前端。mode: black/red/auto/smart */
        @JavascriptInterface
        fun recognize(dataUrl: String, mode: String, callbackId: String) {
            logStore.log("识别", "开始识别（模式=$mode）")
            Thread {
                var payload = ""
                try {
                    val words = ai.recognize(dataUrl, mode)
                    val ok = JSONObject().put("ok", true).put("words", words)
                    ai.lastUsage()?.let { ok.put("usage", it) }
                    recordAiUsage()
                    payload = ok.toString()
                    logStore.log("识别", "成功，识别到 ${words.length()} 个词")
                } catch (e: Exception) {
                    payload = JSONObject().put("ok", false).put("error", e.message ?: "识别失败").toString()
                    logStore.error("识别", "失败（模式=$mode）：${e.message}")
                }
                runOnUiThread {
                    val js = "window.__aiResult && window.__aiResult(" + jsStr(callbackId) + "," + jsStr(payload) + ")"
                    webView.evaluateJavascript(js, null)
                }
            }.start()
        }

        /** 查词补全：输入单词自动补音标/词性/释义/例句。结果经 window.__aiLookup(callbackId, json) 回调。 */
        @JavascriptInterface
        fun lookupWord(word: String, callbackId: String) {
            logStore.log("查词", "请求补全：$word")
            Thread {
                var payload = ""
                try {
                    val info = ai.lookupWord(word)
                    val ok = JSONObject().put("ok", true).put("info", info)
                    ai.lastUsage()?.let { ok.put("usage", it) }
                    recordAiUsage()
                    payload = ok.toString()
                    logStore.log("查词", "补全成功：$word")
                } catch (e: Exception) {
                    payload = JSONObject().put("ok", false).put("error", e.message ?: "查词失败").toString()
                    logStore.error("查词", "失败：$word → ${e.message}")
                }
                runOnUiThread {
                    val js = "window.__aiLookup && window.__aiLookup(" + jsStr(callbackId) + "," + jsStr(payload) + ")"
                    webView.evaluateJavascript(js, null)
                }
            }.start()
        }

        /** 保存图片到系统相册（Pictures/生词错题本/）。拍照/选图进入识别列表时调用。 */
        @JavascriptInterface
        fun saveToGallery(dataUrl: String, name: String) {
            Thread {
                try {
                    val bytes = base64Decode(dataUrl)
                    if (Build.VERSION.SDK_INT >= 29) {
                        saveBitmapMediaStore(bytes)
                        logStore.log("相册", "已保存到相册：$name")
                    } else {
                        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            pendingSaveToGallery.offer(dataUrl to name)
                            runOnUiThread { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE) }
                        } else {
                            saveBitmapLegacy(bytes)
                            logStore.log("相册", "已保存到相册（旧系统）：$name")
                        }
                    }
                } catch (e: Exception) {
                    logStore.error("相册", "保存失败：$name → ${e.message}")
                }
            }.start()
        }
        /** 分享日志：打包 zip 到下载目录并弹出系统分享（可发微信/QQ 等）。 */
        @JavascriptInterface
        fun shareLogs(callbackId: String) {
            Thread {
                try {
                    val uri = Exporter(this@MainActivity)
                        .exportDebugBundleUri(db.allLogs(0), logStore, WordDb.DB_VERSION, wordFreq.size)
                    logStore.log("分享", "日志包已生成：$uri")
                    runOnUiThread {
                        try {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(Intent.createChooser(intent, "分享日志包"))
                            evaluateSync(callbackId, JSONObject().put("ok", true).toString())
                        } catch (e: Exception) {
                            evaluateSync(callbackId, JSONObject().put("ok", false).put("error", "无法弹出分享：${e.message}").toString())
                        }
                    }
                } catch (e: Exception) {
                    logStore.error("分享", "日志包生成失败：${e.message}")
                    runOnUiThread {
                        evaluateSync(callbackId, JSONObject().put("ok", false).put("error", e.message ?: "导出失败").toString())
                    }
                }
            }.start()
        }

        /** 有道联想搜索：输入关键词返回候选词列表（含释义），经 window.__ydSuggest(callbackId, json) 回调。 */
        @JavascriptInterface
        fun youdaoSuggest(q: String, callbackId: String) {
            logStore.log("有道", "搜索：$q")
            Thread {
                var payload = ""
                try {
                    val cands = youdao.suggest(q)
                    payload = JSONObject().put("ok", true).put("cands", cands).toString()
                    logStore.log("有道", "搜索返回 ${cands.length()} 个候选")
                } catch (e: Exception) {
                    payload = JSONObject().put("ok", false).put("error", e.message ?: "搜索失败").toString()
                    logStore.error("有道", "搜索失败：$q → ${e.message}")
                }
                runOnUiThread {
                    val js = "window.__ydSuggest && window.__ydSuggest(" + jsStr(callbackId) + "," + jsStr(payload) + ")"
                    webView.evaluateJavascript(js, null)
                }
            }.start()
        }

        /** 有道词典详情：给定确切单词返回音标/词性/释义，经 window.__ydLookup(callbackId, json) 回调。 */
        @JavascriptInterface
        fun youdaoLookup(word: String, callbackId: String) {
            logStore.log("有道", "查询：$word")
            Thread {
                var payload = ""
                try {
                    val info = youdao.lookup(word)
                    payload = JSONObject().put("ok", true).put("info", info).toString()
                    logStore.log("有道", "查询成功：$word")
                } catch (e: Exception) {
                    payload = JSONObject().put("ok", false).put("error", e.message ?: "查询失败").toString()
                    logStore.error("有道", "查询失败：$word → ${e.message}")
                }
                runOnUiThread {
                    val js = "window.__ydLookup && window.__ydLookup(" + jsStr(callbackId) + "," + jsStr(payload) + ")"
                    webView.evaluateJavascript(js, null)
                }
            }.start()
        }

        /** 查询考研词频：返回 {freq, syllabus, level, label} */
        @JavascriptInterface
        fun getFreq(word: String): String {
            return try { wordFreq.getFreq(word).toString() }
            catch (e: Exception) { JSONObject().put("freq", 0).put("level", "未收录").put("label", "未收录词频数据").toString() }
        }

        // 批量查询词频：入参 JSON 数组，返回 word 到词频信息的映射
        @JavascriptInterface
        fun getFreqs(wordsJson: String): String {
            return try {
                val arr = org.json.JSONArray(wordsJson)
                val out = JSONObject()
                for (i in 0 until arr.length()) {
                    val w = arr.optString(i)
                    if (w.isNotEmpty()) out.put(w, wordFreq.getFreq(w))
                }
                out.toString()
            } catch (e: Exception) { "{}" }
        }

        /** 词库大小（供前端标注数据来源） */
        @JavascriptInterface
        fun getFreqSize(): Int = wordFreq.size

        /** 朗读单词：优先系统 TTS，不可用/失败时回退联网发音（有道词典语音）。
         *  accent: us / uk；结果经 window.__speakResult(callbackId, json) 回前端。 */
        @JavascriptInterface
        fun speak(word: String, accent: String, callbackId: String) {
            val loc = if (accent == "uk") Locale.UK else Locale.US
            if (ttsReady) {
                if (doSpeak(word, loc)) speakCallback(callbackId, true, "tts", "")
                else playNetwork(word, loc, callbackId)
            } else {
                playNetwork(word, loc, callbackId)
            }
        }

        /** 导出生词本：csv / json / anki / pdf */
        @JavascriptInterface
        fun exportWords(format: String): String {
            return try {
                val path = Exporter(this@MainActivity).exportWords(format, db.allWords())
                JSONObject().put("ok", true).put("path", path).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "导出失败").toString()
            }
        }

        /** 导出学习日志：csv / json */
        @JavascriptInterface
        fun exportLogs(format: String): String {
            return try {
                val path = Exporter(this@MainActivity).exportLogs(format, db.allLogs(0))
                JSONObject().put("ok", true).put("path", path).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "导出失败").toString()
            }
        }
    }

    private fun jsStr(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "") + "'"

    /** 在 UI 线程执行 JS 回调 */
    private fun evaluateSync(callbackId: String, payload: String) {
        val js = "window.__syncResult && window.__syncResult(" + jsStr(callbackId) + "," + jsStr(payload) + ")"
        webView.evaluateJavascript(js, null)
    }

    // ================= 保存图片到相册 =================
    private val pendingSaveToGallery = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    private fun base64Decode(dataUrl: String): ByteArray {
        val b64 = dataUrl.substringAfter(',')
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
    }

    /** Android 10+：MediaStore 写入（无需权限） */
    private fun saveBitmapMediaStore(bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "wordbook_" + System.currentTimeMillis() + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/生词错题本")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("相册不可写")
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw Exception("写入失败")
    }

    /** Android 9-：MediaStore.insertImage（需存储权限，已声明 maxSdkVersion=28） */
    private fun saveBitmapLegacy(bytes: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        try {
            MediaStore.Images.Media.insertImage(contentResolver, bmp, "wordbook_" + System.currentTimeMillis(), "生词错题本")
        } finally {
            bmp.recycle()
        }
    }

    /** 授权后补存排队中的相册图片（与相机权限回调合并，见 onRequestPermissionsResult） */
    private fun flushPendingGallerySaves() {
        while (pendingSaveToGallery.isNotEmpty()) {
            val item = pendingSaveToGallery.poll() ?: break
            try {
                saveBitmapLegacy(base64Decode(item.first))
                logStore.log("相册", "授权后已保存：${item.second}")
            } catch (e: Exception) {
                logStore.error("相册", "授权后保存失败：${item.second} → ${e.message}")
            }
        }
    }

    // ================= 语音发音（TTS 优先，联网发音兜底） =================

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                val avail = tts?.isLanguageAvailable(Locale.US)
                // 语言数据缺失（如精简 ROM 无语音包）时视为不可用，前端自动走联网发音
                ttsReady = avail != null && avail >= TextToSpeech.LANG_AVAILABLE
                logStore.setTtsAvail(if (ttsReady) "可用(语言数据完整)" else "语言数据缺失")
                logStore.log("TTS", "初始化成功，语言可用性=${if (ttsReady) "OK" else "缺失/不支持"}")
            } else {
                ttsReady = false
                logStore.setTtsAvail("初始化失败(status=$status)")
                logStore.error("TTS", "初始化失败 status=$status（设备无语音引擎时自动走联网发音）")
            }
        }
    }

    private fun doSpeak(word: String, loc: Locale): Boolean {
        return try {
            tts?.language = loc
            val r = tts?.speak(word, TextToSpeech.QUEUE_FLUSH, null, "wordbook")
            val ok = r == TextToSpeech.SUCCESS
            logStore.log("发音", if (ok) "TTS 播放：$word (${if (loc == Locale.UK) "英" else "美"}音)" else "TTS 调用返回失败，转联网")
            ok
        } catch (e: Exception) {
            logStore.error("发音", "TTS 异常：$word → ${e.message}")
            false
        }
    }

    /** 联网发音兜底：有道词典语音接口（type=1 美音 / type=2 英音） */
    private fun playNetwork(word: String, loc: Locale, callbackId: String) {
        try {
            stopMedia()
            val type = if (loc == Locale.UK) 2 else 1
            val url = "https://dict.youdao.com/dictvoice?audio=" +
                URLEncoder.encode(word, "UTF-8") + "&type=" + type
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener { it.start(); speakCallback(callbackId, true, "network", "联网发音") }
                setOnErrorListener { _, _, _ ->
                    speakCallback(callbackId, false, "network", "联网发音失败")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            logStore.error("发音", "联网发音异常：$word → ${e.message}")
            speakCallback(callbackId, false, "network", "发音失败：" + (e.message ?: ""))
        }
    }

    private fun speakCallback(callbackId: String, ok: Boolean, source: String, msg: String) {
        runOnUiThread {
            val payload = JSONObject()
                .put("ok", ok).put("source", source).put("msg", msg).toString()
            val js = "window.__speakResult && window.__speakResult(" + jsStr(callbackId) + "," + jsStr(payload) + ")"
            webView.evaluateJavascript(js, null)
        }
    }

    private fun stopMedia() {
        try { mediaPlayer?.stop() } catch (e: Exception) {}
        try { mediaPlayer?.release() } catch (e: Exception) {}
        mediaPlayer = null
    }

    // ================= 拍照 / 相册 =================

    private fun showPickDialog() {
        AlertDialog.Builder(this)
            .setTitle("选择图片来源")
            .setItems(arrayOf("拍照", "从相册选择")) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> openGallery(false)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openGallery(multiple: Boolean) {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            if (multiple) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(intent, REQ_GALLERY)
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION)
            return
        }
        launchCamera()
    }

    private fun launchCamera() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val photoFile = try { createImageFile() } catch (e: IOException) { null }
        if (photoFile == null) { Toast.makeText(this, "无法创建拍照临时文件", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        cameraImageUri = uri
        intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        try { startActivityForResult(intent, REQ_CAMERA) }
        catch (e: Exception) { Toast.makeText(this, "没有可用的相机应用", Toast.LENGTH_SHORT).show() }
    }

    private fun createImageFile(): File {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "IMG_$name.jpg")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val callback = filePathCallback ?: return
        when (requestCode) {
            REQ_GALLERY -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val uris = ArrayList<Uri>()
                    if (data.clipData != null) {
                        for (i in 0 until data.clipData!!.itemCount) uris.add(data.clipData!!.getItemAt(i).uri)
                    } else if (data.data != null) uris.add(data.data!!)
                    callback.onReceiveValue(uris.toTypedArray())
                } else callback.onReceiveValue(null)
            }
            REQ_CAMERA -> {
                val uri = cameraImageUri
                callback.onReceiveValue(if (resultCode == Activity.RESULT_OK && uri != null) arrayOf(uri) else null)
            }
            else -> callback.onReceiveValue(null)
        }
        filePathCallback = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) launchCamera()
            else Toast.makeText(this, "需要相机权限才能拍照上传", Toast.LENGTH_SHORT).show()
        } else if (requestCode == REQ_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) flushPendingGallerySaves()
            else logStore.log("相册", "用户拒绝了存储权限，图片不保存到相册")
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        stopMedia()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_GALLERY = 1001
        private const val REQ_CAMERA = 1002
        private const val REQ_CAMERA_PERMISSION = 1003
        private const val REQ_STORAGE = 1004
    }
}
