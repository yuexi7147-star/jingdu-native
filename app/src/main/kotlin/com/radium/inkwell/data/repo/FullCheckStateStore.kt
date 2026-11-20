package com.radium.inkwell.data.repo

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 完整校验的进度：读到第几个、跑完过几轮、上次完成时间 */
class FullCheckStateStore(private val context: Context) {

    @Serializable
    data class State(
        val cursor: Int = 0,
        val done: Int = 0,
        val lastFinishedAt: Long = 0,
        val total: Int = 0,
        val examined: Int = 0,
    )

    private val file: File get() = File(context.filesDir, "source_full_check.json")

    fun read(): State = runCatching {
        Json.decodeFromString<State>(file.readText())
    }.getOrDefault(State())

    fun save(state: State) = runCatching {
        val tmp = File.createTempFile("fullcheck", ".tmp", context.filesDir)
        tmp.writeText(Json.encodeToString(state))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun cursor(): Int = read().cursor
    fun done(): Int = read().done
    fun lastFinishedAt(): Long = read().lastFinishedAt
}
