package com.wordbook.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** 生词本本地数据库：words（含复习调度字段）+ review_records（学习日志） */
class WordDb(context: Context) : SQLiteOpenHelper(context, "wordbook.db", null, DB_VERSION) {

    companion object {
        // v3：掌握度简化为两级（陌生/已掌握），迁移时将旧"模糊"归入"陌生"
        internal const val DB_VERSION = 3
    }

    data class WordRow(
        val id: Long, val word: String, val phonetic: String, val pos: String,
        val meaning: String, val sentence: String, val source: String,
        val mastery: String, val repetitions: Int, val ease: Double,
        val intervalDays: Double, val dueAt: Long, val lapses: Int, val createdAt: Long,
        val hitCount: Int
    )

    data class LogRow(
        val id: Long, val wordId: Long, val word: String, val mode: String,
        val grade: Int, val createdAt: Long
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL,
                phonetic TEXT DEFAULT '',
                pos TEXT DEFAULT '',
                meaning TEXT DEFAULT '',
                sentence TEXT DEFAULT '',
                source TEXT DEFAULT '',
                mastery TEXT DEFAULT '陌生',
                repetitions INTEGER DEFAULT 0,
                ease REAL DEFAULT 2.5,
                interval_days REAL DEFAULT 0,
                due_at INTEGER DEFAULT 0,
                lapses INTEGER DEFAULT 0,
                created_at INTEGER DEFAULT 0,
                hit_count INTEGER DEFAULT 1
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE review_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word_id INTEGER,
                mode TEXT,
                grade INTEGER,
                created_at INTEGER
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_words_due ON words(due_at)")
        db.execSQL("CREATE INDEX idx_logs_word ON review_records(word_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v1 → v2：增加录入次数统计列
            db.execSQL("ALTER TABLE words ADD COLUMN hit_count INTEGER DEFAULT 1")
        }
        if (oldVersion < 3) {
            // v2 → v3：掌握度简化为两级，旧"模糊"归入"陌生"
            db.execSQL("UPDATE words SET mastery='陌生' WHERE mastery='模糊'")
        }
    }

    // ================= 生词 CRUD =================

    fun listWords(filter: JSONObject?): JSONArray {
        val q = filter?.optString("q") ?: ""
        val mastery = filter?.optString("mastery") ?: ""
        val sb = StringBuilder("SELECT * FROM words WHERE 1=1")
        val args = ArrayList<String>()
        if (q.isNotEmpty()) {
            sb.append(" AND (word LIKE ? OR meaning LIKE ?)")
            val like = "%$q%"
            args.add(like); args.add(like)
        }
        if (mastery.isNotEmpty() && mastery != "全部") {
            sb.append(" AND mastery = ?")
            args.add(mastery)
        }
        sb.append(" ORDER BY created_at DESC")
        val arr = JSONArray()
        val c = readableDatabase.rawQuery(sb.toString(), args.toTypedArray())
        while (c.moveToNext()) arr.put(toJson(c))
        c.close()
        return arr
    }

    /**
     * 保存生词：同一单词（不区分大小写）已存在时，不新建记录，录入次数 +1；
     * 不存在时插入新记录。返回记录 id。
     */
    fun saveWord(json: JSONObject): Long {
        val db = writableDatabase
        val id = if (json.has("id")) json.optLong("id") else 0L
        if (id > 0) {
            // 编辑已有记录
            val cv = ContentValues().apply {
                put("word", json.optString("word"))
                put("phonetic", json.optString("phonetic"))
                put("pos", json.optString("pos"))
                put("meaning", json.optString("meaning"))
                put("sentence", json.optString("sentence"))
                put("source", json.optString("source"))
                if (json.has("mastery")) put("mastery", json.optString("mastery"))
            }
            db.update("words", cv, "id=?", arrayOf(id.toString()))
            return id
        }

        // 新增：按单词查重（不区分大小写）
        val word = json.optString("word").trim()
        val c = db.rawQuery("SELECT id FROM words WHERE word = ? COLLATE NOCASE", arrayOf(word))
        val exists = c.moveToFirst()
        val existId = if (exists) c.getLong(0) else 0L
        c.close()
        if (exists) {
            db.execSQL("UPDATE words SET hit_count = hit_count + 1 WHERE id = ?", arrayOf(existId))
            return existId
        }

        val cv = ContentValues().apply {
            put("word", word)
            put("phonetic", json.optString("phonetic"))
            put("pos", json.optString("pos"))
            put("meaning", json.optString("meaning"))
            put("sentence", json.optString("sentence"))
            put("source", json.optString("source"))
            put("mastery", json.optString("mastery", "陌生"))
            put("ease", 2.5)
            put("created_at", System.currentTimeMillis())
            put("due_at", System.currentTimeMillis())
            put("hit_count", 1)
        }
        return db.insert("words", null, cv)
    }

    fun deleteWord(id: Long) {
        writableDatabase.delete("words", "id=?", arrayOf(id.toString()))
        writableDatabase.delete("review_records", "word_id=?", arrayOf(id.toString()))
    }

    fun setMastery(id: Long, mastery: String) {
        val cv = ContentValues().apply {
            put("mastery", mastery)
            // 人工标记掌握时重置复习曲线
            if (mastery == "已掌握") { put("interval_days", 21.0); put("due_at", System.currentTimeMillis()) }
            if (mastery == "陌生") { put("interval_days", 0.0); put("due_at", System.currentTimeMillis()) }
        }
        writableDatabase.update("words", cv, "id=?", arrayOf(id.toString()))
    }

    // ================= 复习调度（SM-2 简化版） =================

    fun getDueReviews(limit: Int): JSONArray {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        val c = readableDatabase.rawQuery(
            "SELECT * FROM words WHERE due_at <= ? ORDER BY due_at ASC LIMIT ?",
            arrayOf(now.toString(), limit.toString())
        )
        while (c.moveToNext()) arr.put(toJson(c))
        c.close()
        return arr
    }

    /** 提交一次复习作答，按 SM-2 更新调度，写日志。grade: 0-5 */
    fun submitReview(wordId: Long, grade: Int, mode: String): JSONObject {
        val db = writableDatabase
        val q = grade.coerceIn(0, 5)
        val out = JSONObject()

        val c = db.rawQuery("SELECT * FROM words WHERE id=?", arrayOf(wordId.toString()))
        if (!c.moveToFirst()) { c.close(); out.put("ok", false); return out }
        val row = fromCursor(c)
        c.close()

        var reps = row.repetitions
        var ease = row.ease
        var interval = row.intervalDays
        var lapses = row.lapses

        if (q >= 3) {
            reps += 1
            interval = when (reps) {
                1 -> 1.0
                2 -> 6.0
                else -> Math.round(interval * ease).toDouble().coerceAtLeast(1.0)
            }
        } else {
            reps = 0
            interval = 1.0
            lapses += 1
        }
        ease = (ease + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02))).coerceAtLeast(1.3)
        val dueAt = System.currentTimeMillis() + (interval * 86400000L).toLong()
        // 掌握度两档：复习间隔 ≥21 天视为已掌握，其余为陌生
        val mastery = if (interval >= 21.0) "已掌握" else "陌生"

        db.execSQL(
            "UPDATE words SET repetitions=?, ease=?, interval_days=?, due_at=?, lapses=?, mastery=? WHERE id=?",
            arrayOf(reps, ease, interval, dueAt, lapses, mastery, wordId)
        )
        db.execSQL(
            "INSERT INTO review_records(word_id, mode, grade, created_at) VALUES(?,?,?,?)",
            arrayOf(wordId, mode, q, System.currentTimeMillis())
        )

        out.put("ok", true); out.put("due_at", dueAt)
        out.put("interval_days", interval); out.put("ease", ease); out.put("mastery", mastery)
        return out
    }

    // ================= 统计 =================

    fun getStats(): JSONObject {
        val out = JSONObject()
        val db = readableDatabase
        val now = System.currentTimeMillis()
        val dayStart = now - (now % 86400000L)
        out.put("total", queryInt(db, "SELECT COUNT(*) FROM words"))
        out.put("total_hits", queryInt(db, "SELECT COALESCE(SUM(hit_count),0) FROM words"))
        out.put("today_new", queryInt(db, "SELECT COUNT(*) FROM words WHERE created_at >= $dayStart"))
        out.put("today_reviewed", queryInt(db, "SELECT COUNT(*) FROM review_records WHERE created_at >= $dayStart"))
        out.put("mastered", queryInt(db, "SELECT COUNT(*) FROM words WHERE mastery='已掌握'"))
        out.put("fuzzy", queryInt(db, "SELECT COUNT(*) FROM words WHERE mastery='模糊'"))
        out.put("strange", queryInt(db, "SELECT COUNT(*) FROM words WHERE mastery='陌生'"))
        out.put("due_today", queryInt(db, "SELECT COUNT(*) FROM words WHERE due_at <= $now"))
        return out
    }

    private fun queryInt(db: SQLiteDatabase, sql: String): Int {
        db.rawQuery(sql, null).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    // ================= 导出数据源 =================

    fun allWords(): List<WordRow> {
        val list = ArrayList<WordRow>()
        val c = readableDatabase.rawQuery("SELECT * FROM words ORDER BY created_at DESC", null)
        while (c.moveToNext()) list.add(fromCursor(c))
        c.close()
        return list
    }

    fun allLogs(limit: Int): List<LogRow> {
        val list = ArrayList<LogRow>()
        val sql = if (limit > 0)
            "SELECT r.*, w.word FROM review_records r LEFT JOIN words w ON r.word_id=w.id ORDER BY r.created_at DESC LIMIT $limit"
        else
            "SELECT r.*, w.word FROM review_records r LEFT JOIN words w ON r.word_id=w.id ORDER BY r.created_at DESC"
        val c = readableDatabase.rawQuery(sql, null)
        while (c.moveToNext()) {
            list.add(LogRow(c.getLong(0), c.getLong(1), c.getString(5) ?: "", c.getString(2) ?: "", c.getInt(3), c.getLong(4)))
        }
        c.close()
        return list
    }

    // ================= 辅助 =================

    private fun toJson(c: android.database.Cursor): JSONObject {
        return JSONObject().apply {
            put("id", c.getLong(c.getColumnIndexOrThrow("id")))
            put("word", c.getString(c.getColumnIndexOrThrow("word")))
            put("phonetic", c.getString(c.getColumnIndexOrThrow("phonetic")) ?: "")
            put("pos", c.getString(c.getColumnIndexOrThrow("pos")) ?: "")
            put("meaning", c.getString(c.getColumnIndexOrThrow("meaning")) ?: "")
            put("sentence", c.getString(c.getColumnIndexOrThrow("sentence")) ?: "")
            put("source", c.getString(c.getColumnIndexOrThrow("source")) ?: "")
            put("mastery", c.getString(c.getColumnIndexOrThrow("mastery")) ?: "陌生")
            put("repetitions", c.getInt(c.getColumnIndexOrThrow("repetitions")))
            put("ease", c.getDouble(c.getColumnIndexOrThrow("ease")))
            put("interval_days", c.getDouble(c.getColumnIndexOrThrow("interval_days")))
            put("due_at", c.getLong(c.getColumnIndexOrThrow("due_at")))
            put("lapses", c.getInt(c.getColumnIndexOrThrow("lapses")))
            put("created_at", c.getLong(c.getColumnIndexOrThrow("created_at")))
            put("hit_count", c.getInt(c.getColumnIndexOrThrow("hit_count")))
        }
    }

    private fun fromCursor(c: android.database.Cursor): WordRow = WordRow(
        c.getLong(c.getColumnIndexOrThrow("id")),
        c.getString(c.getColumnIndexOrThrow("word")) ?: "",
        c.getString(c.getColumnIndexOrThrow("phonetic")) ?: "",
        c.getString(c.getColumnIndexOrThrow("pos")) ?: "",
        c.getString(c.getColumnIndexOrThrow("meaning")) ?: "",
        c.getString(c.getColumnIndexOrThrow("sentence")) ?: "",
        c.getString(c.getColumnIndexOrThrow("source")) ?: "",
        c.getString(c.getColumnIndexOrThrow("mastery")) ?: "陌生",
        c.getInt(c.getColumnIndexOrThrow("repetitions")),
        c.getDouble(c.getColumnIndexOrThrow("ease")),
        c.getDouble(c.getColumnIndexOrThrow("interval_days")),
        c.getLong(c.getColumnIndexOrThrow("due_at")),
        c.getInt(c.getColumnIndexOrThrow("lapses")),
        c.getLong(c.getColumnIndexOrThrow("created_at")),
        c.getInt(c.getColumnIndexOrThrow("hit_count"))
    )
}
