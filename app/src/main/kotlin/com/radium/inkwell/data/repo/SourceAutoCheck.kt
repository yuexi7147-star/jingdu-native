package com.radium.inkwell.data.repo

import android.content.Context
import com.radium.inkwell.data.db.InkwellDb
import com.radium.inkwell.ui.sourcemanage.SourceChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 自动校验：应用启动时判断距上次多久，到点就在后台跑一轮。
 *
 * 不用 WorkManager —— 它依赖旧版 Room（androidx.room），和本项目用的 Room 3 在启动初始化阶段冲突，
 * 装上直接闪退。启动时检查的代价是「用户不打开 App 就不校验」，但轻量校验只要十来分钟，
 * 每天总会开一次，够用。
 *
 * 两个周期：
 * - 每天：轻量（DNS + HEAD），失效的按分级规则移除
 * - 每半月：完整（搜索 -> 详情 -> 正文），单次最多 25 分钟，进度存文件、下次接着跑
 */
class SourceAutoCheck(
    private val context: Context,
    private val db: InkwellDb,
    private val lightCheck: SourceLightCheck,
    private val counter: SuspiciousCounter,
    private val quarantine: SourceQuarantine,
    private val checker: SourceChecker,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("source_auto_check", Context.MODE_PRIVATE)
    private val fullState = FullCheckStateStore(context)

    /** 启动后调一次；内部自己判断该不该跑 */
    fun onAppStart() {
        if (!prefs.getBoolean("enabled", false) && !prefs.getBoolean("full_enabled", false)) return
        scope.launch {
            // 让开启动时的正事：书架、字体、数据库迁移都抢在这几秒
            delay(8_000)
            if (prefs.getBoolean("enabled", false)) maybeRunLight()
            if (prefs.getBoolean("full_enabled", false)) maybeRunFull()
        }
    }

    private suspend fun maybeRunLight() {
        val last = prefs.getLong("last_light_at", 0)
        if (System.currentTimeMillis() - last < DAY_MS) return
        runLight()
        prefs.edit().putLong("last_light_at", System.currentTimeMillis()).apply()
    }

    private suspend fun runLight() {
        val ids = db.bookSourceDao().getAllIds()
        val hits = mutableListOf<Pair<String, String>>()
        val now = System.currentTimeMillis()

        for (id in ids) {
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
    }

    private suspend fun maybeRunFull() {
        val state = fullState.read()
        val gap: Long = System.currentTimeMillis() - fullState.lastFinishedAt()
        // 还没跑完一轮：按游标继续；跑完过且距上次满 15 天才重开
        if (state.cursor == 0 && gap in 1L until HALF_MONTH_MS) return
        runFullBatch()
    }

    private suspend fun runFullBatch() {
        val ids = db.bookSourceDao().getAllIds()
        if (ids.isEmpty()) return
        val start = fullState.cursor().coerceIn(0, ids.size)
        val startedAt = System.currentTimeMillis()
        var cursor = start
        var examined = 0

        while (cursor < ids.size) {
            val minutes = (System.currentTimeMillis() - startedAt) / 60000
            if (minutes >= MAX_MINUTES) break
            val batch = ids.drop(cursor).take(BATCH)
            if (batch.isEmpty()) break
            if (!checker.start(batch)) {
                delay(3000)
                continue
            }
            var guard = 0
            while (checker.progress.value != null && guard < 900) {
                delay(1000)
                guard++
            }
            cursor += batch.size
            examined += batch.size
        }

        val finished = cursor >= ids.size
        fullState.save(
            FullCheckStateStore.State(
                cursor = if (finished) 0 else cursor,
                done = fullState.done() + if (finished) 1 else 0,
                lastFinishedAt = if (finished) System.currentTimeMillis() else fullState.lastFinishedAt(),
                total = ids.size,
                examined = examined,
            ),
        )
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
        private const val DAY_MS = 24L * 3600 * 1000
        private const val HALF_MONTH_MS = 15L * DAY_MS
        private const val MAX_SUSPICIOUS = 3
        private const val MAX_MINUTES = 25
        private const val BATCH = 40
    }
}
