package com.radium.inkwell.data.repo

import android.content.Context
import com.radium.inkwell.data.db.entity.BookSourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** 从来源 JSON 里取 bookSourceUrl —— 实体本身不拆这一列 */
fun BookSourceEntity.urlField(): String {
    val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
    return runCatching { o?.get("bookSourceUrl")?.jsonPrimitive?.content }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: ""
}

/**
 * 疑似失效的连续次数。存文件而不是加数据库列：加列要带迁移、要动库版本，
 * 而这份数据只是一张「id -> 次数」的小表，丢了也只是多测一次。
 */
class SuspiciousCounter(private val context: Context) {

    private val file: File get() = File(context.filesDir, "source_suspect.json")

    private fun read(): MutableMap<String, Int> = runCatching {
        Json.decodeFromString<Map<String, Int>>(file.readText()).toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun write(map: Map<String, Int>) {
        runCatching {
            val tmp = File.createTempFile("suspect", ".tmp", context.filesDir)
            tmp.writeText(Json.encodeToString(map))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    suspend fun bump(id: String): Int = withContext(Dispatchers.IO) {
        val map = read()
        val n = (map[id] ?: 0) + 1
        map[id] = n
        write(map)
        n
    }

    suspend fun clear(id: String) = withContext(Dispatchers.IO) {
        val map = read()
        if (map.remove(id) != null) write(map)
    }

    suspend fun forget(id: String) = clear(id)
}
