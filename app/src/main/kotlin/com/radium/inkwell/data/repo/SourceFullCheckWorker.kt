package com.radium.inkwell.data.repo

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.radium.inkwell.data.db.InkwellDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * 半月的完整校验，分批推进。
 *
 * 完整链路（搜索 -> 详情 -> 目录 -> 正文）一个来源十几秒，515 个要一个多小时 ——
 * WorkManager 一次跑不完，会被系统掐掉。所以每次只跑 [MAX_MINUTES] 分钟，
 * 进度写文件，下次触发接着跑。一轮可能跨好几次触发才跑完，中间靠每天的轻量校验兜底。
 */
class SourceFullCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val db: InkwellDb by inject()
    private val counter: SuspiciousCounter by inject()
    private val checker: com.radium.inkwell.ui.sourcemanage.SourceChecker by inject()
    private val quarantine: SourceQuarantine by inject()

    override suspend fun doWork(): Result {
        val ids = db.bookSourceDao().getAllIds()
        if (ids.isEmpty()) return Result.success()

        val state = FullCheckStateStore(applicationContext)
        val done = state.done()
        val startIndex = state.cursor().coerceIn(0, ids.size)
        val startedAt = System.currentTimeMillis()

        var cursor = startIndex
        var examined = 0

        while (cursor < ids.size) {
            if (isStopped) break
            val elapsedMin = (System.currentTimeMillis() - startedAt) / 60000
            if (elapsedMin >= MAX_MINUTES) break

            val batch = ids.drop(cursor).take(BATCH)
            if (batch.isEmpty()) break

            // 复用现有 SourceChecker：结果直接落库（checkedAt / checkStatus）
            val started = checker.start(batch)
            if (!started) {
                // 上一轮还没收尾，等它
                kotlinx.coroutines.delay(3000)
                continue
            }
            // 等这一批跑完（progress 为 null 表示结束）
            var guard = 0
            while (checker.progress.value != null && guard < 1200) {
                kotlinx.coroutines.delay(1000)
                guard++
            }
            cursor += batch.size
            examined += batch.size
        }

        state.save(
            FullCheckStateStore.State(
                cursor = if (cursor >= ids.size) 0 else cursor,
                done = if (cursor >= ids.size) done + 1 else done,
                lastFinishedAt = if (cursor >= ids.size) System.currentTimeMillis() else state.lastFinishedAt(),
                total = ids.size,
                examined = examined,
            ),
        )
        return Result.success()
    }

    companion object {
        /** 单次最多跑这么久，超出就停下等下次 */
        const val MAX_MINUTES = 25
        /** 一批交多少给 SourceChecker */
        const val BATCH = 40
    }
}

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
