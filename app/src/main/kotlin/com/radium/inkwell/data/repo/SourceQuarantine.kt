package com.radium.inkwell.data.repo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 隔离区：被安全检查移除的书源原文留在这里，可以恢复。
 *
 * 存文件而不是新建 Room 表：加表要带迁移、要动 [com.radium.inkwell.data.db.InkwellDb] 的版本号，
 * 而这份数据只在一个页面里读写、量级是几十到几百条 —— 不值得为它改数据库结构。
 */
class SourceQuarantine(private val context: Context) {

    @Serializable
    data class Entry(
        val id: String,
        val name: String,
        val reason: String,
        val json: String,
        val removedAt: Long,
        /** true = 需要登录，false = 内容违规 */
        val login: Boolean,
    )

    private val file: File get() = File(context.filesDir, "source_quarantine.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun all(): List<Entry> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString<List<Entry>>(file.readText()) }.getOrDefault(emptyList())
    }

    /** 原子写：先写临时文件再改名，进程被杀不会留下半截 JSON */
    suspend fun addAll(entries: List<Entry>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        val merged = all().filter { e -> entries.none { it.id == e.id } } + entries
        val tmp = File.createTempFile("quarantine", ".tmp", context.filesDir)
        tmp.writeText(json.encodeToString(merged))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        val rest = all().filter { it.id != id }
        val tmp = File.createTempFile("quarantine", ".tmp", context.filesDir)
        tmp.writeText(json.encodeToString(rest))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        file.delete()
    }
}
