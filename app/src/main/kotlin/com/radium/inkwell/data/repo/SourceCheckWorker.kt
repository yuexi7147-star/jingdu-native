package com.radium.inkwell.data.repo

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.radium.inkwell.data.db.InkwellDb
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 后台校验来源可用性。
 *
 * 两种模式，由 inputData 的 light 区分：
 * - 轻量（每天）：只测站点存活
 * - 完整（每 7 天）：留给手动校验，这里不跑
 *
 * 删除分级：确定失效直接删；疑似要连续 MAX_SUSPICIOUS 次才删 ——
 * 站点临时 502 一次就删，515 个来源一轮下来能被清空一片。
 */
class SourceCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val db: InkwellDb by inject()
    private val lightCheck: SourceLightCheck by inject()
    private val quarantine: SourceQuarantine by inject()
    private val counter: SuspiciousCounter by inject()

    override suspend fun doWork(): Result {
        val light = inputData.getBoolean(KEY_LIGHT, true)
        if (!light) return Result.success()
        return runLight()
    }

    private suspend fun runLight(): Result {
        val ids = db.bookSourceDao().getAllIds()
        val hits = mutableListOf<Pair<String, String>>()
        val now = System.currentTimeMillis()

        for (id in ids) {
            if (isStopped) return Result.retry()
            val src = db.bookSourceDao().getById(id) ?: continue
            when (val v = lightCheck.check(src.urlField())) {
                is SourceLightCheck.Verdict.Dead -> hits += src.id to v.reason
                is SourceLightCheck.Verdict.Suspicious -> {
                    val fails = counter.bump(src.id)
                    if (fails >= MAX_SUSPICIOUS) hits += src.id to (v.reason + "（连续 " + fails + " 次）")
                }
                SourceLightCheck.Verdict.Alive -> counter.clear(src.id)
                SourceLightCheck.Verdict.Unknown -> Unit
            }
        }

        if (hits.isNotEmpty()) removeAll(hits, now)
        return Result.success()
    }

    private suspend fun removeAll(hits: List<Pair<String, String>>, now: Long) {
        val entries = mutableListOf<SourceQuarantine.Entry>()
        for ((id, reason) in hits) {
            val src = db.bookSourceDao().getById(id) ?: continue
            entries += SourceQuarantine.Entry(
                id = src.id,
                name = src.name,
                reason = reason,
                json = src.json,
                removedAt = now,
                login = false,
            )
        }
        quarantine.addAll(entries)
        db.bookSourceDao().softDeleteByIds(hits.map { it.first }, now)
    }

    companion object {
        const val KEY_LIGHT = "light"
        const val MAX_SUSPICIOUS = 3
    }
}
