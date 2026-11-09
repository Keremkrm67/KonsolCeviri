package com.playceviri.tv.translate

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.LruCache
import java.util.Locale

/** Tekrarlanan cümleler anında gelsin diye bellek + SQLite önbelleği (en fazla 4000 kayıt). */
class TranslationCache(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "playceviri_ceviri.db", null, 1) {
    private val memory = LruCache<String, String>(400)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ceviri (k TEXT PRIMARY KEY, v TEXT NOT NULL, t INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun key(target: String, text: String) = target + "|" + text.lowercase(Locale.ROOT)

    @Synchronized
    fun get(target: String, text: String): String? {
        val k = key(target, text)
        memory.get(k)?.let { return it }
        return try {
            readableDatabase.rawQuery("SELECT v FROM ceviri WHERE k=?", arrayOf(k)).use { c ->
                if (c.moveToFirst()) c.getString(0).also { memory.put(k, it) } else null
            }
        } catch (e: Exception) { null }
    }

    @Synchronized
    fun put(target: String, text: String, translated: String) {
        val k = key(target, text)
        memory.put(k, translated)
        runCatching {
            writableDatabase.insertWithOnConflict("ceviri", null, ContentValues().apply {
                put("k", k); put("v", translated); put("t", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            writableDatabase.execSQL("DELETE FROM ceviri WHERE k NOT IN (SELECT k FROM ceviri ORDER BY t DESC LIMIT 4000)")
        }
    }
}
