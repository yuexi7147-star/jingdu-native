package com.radium.inkwell.data.repo

import com.radium.inkwell.core.guard.SourceAudit
import com.radium.inkwell.data.db.InkwellDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 安全性检查：扫一遍已导入的来源，把「需要登录」与「内容违规」的移除。
 *
 * 判定本身是启发式的（见 [SourceAudit]），所以移除不是终局：原文先写进 [SourceQuarantine]，
 * 用户可以恢复。它只读本地数据，不联网，几百个来源是秒级。
 */
class SourceAuditService(
    private val db: InkwellDb,
    private val quarantine: SourceQuarantine,
) {

    suspend fun run(): SourceAuditReport = withContext(Dispatchers.IO) {
        val ids = db.bookSourceDao().getAllIds()
        // 书按来源分组，一次读完，别在循环里按 id 反复查
        val booksBySource = db.bookDao().getAll().groupBy { it.sourceId ?: "" }

        val hits = mutableListOf<SourceAuditReport.Removed>()
        val entries = mutableListOf<SourceQuarantine.Entry>()
        val now = System.currentTimeMillis()

        for (id in ids) {
            val src = db.bookSourceDao().getById(id) ?: continue
            val evidences = booksBySource[id].orEmpty().map {
                SourceAudit.BookEvidence(
                    title = it.title,
                    author = it.author,
                    intro = it.intro,
                    chapterTitles = db.chapterDao().getByBook(it.id).take(10).map { c -> c.title },
                )
            }
            val verdict = SourceAudit.audit(
                id = src.id,
                name = src.name,
                group = src.groupName,
                json = src.json,
                books = evidences,
            ) ?: continue

            val kind = when (verdict) {
                is SourceAudit.Verdict.LoginRequired -> SourceAuditReport.Kind.LOGIN
                is SourceAudit.Verdict.Harmful -> SourceAuditReport.Kind.HARMFUL
                is SourceAudit.Verdict.NotNovel -> SourceAuditReport.Kind.NOT_NOVEL
            }
            hits += SourceAuditReport.Removed(src.id, src.name, verdict.reason, kind)
            entries += SourceQuarantine.Entry(
                id = src.id,
                name = src.name,
                reason = verdict.reason,
                json = src.json,
                removedAt = now,
                login = kind == SourceAuditReport.Kind.LOGIN,
            )
        }

        if (hits.isNotEmpty()) {
            quarantine.addAll(entries)
            db.bookSourceDao().softDeleteByIds(hits.map { it.id }, now)
        }
        SourceAuditReport(ids.size, hits)
    }

    /** 从隔离区恢复一条来源 */
    suspend fun restore(id: String): Boolean = withContext(Dispatchers.IO) {
        val entry = quarantine.all().firstOrNull { it.id == id } ?: return@withContext false
        val src = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<com.radium.inkwell.data.db.entity.BookSourceEntity>(entry.json)
        }.getOrNull() ?: return@withContext false
        db.bookSourceDao().upsert(src.copy(updatedAt = System.currentTimeMillis()))
        quarantine.remove(id)
        true
    }
}
