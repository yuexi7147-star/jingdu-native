package com.radium.inkwell.data.repo

/**
 * 安全检查的一份报告。移除的书源原文留在 [QuarantinedSource] 里，可以恢复 ——
 * 判定是启发式的，误删不该是终局。
 */
data class SourceAuditReport(
    val total: Int,
    val removed: List<Removed>,
) {
    data class Removed(
        val id: String,
        val name: String,
        /** 面向用户的原因，如「需要登录」「域名可疑（成人顶级域 .xxx）」 */
        val reason: String,
        val kind: Kind,
    )

    enum class Kind { LOGIN, HARMFUL, NOT_NOVEL }

    val isEmpty: Boolean get() = removed.isEmpty()
    val loginCount: Int get() = removed.count { it.kind == Kind.LOGIN }
    val harmfulCount: Int get() = removed.count { it.kind == Kind.HARMFUL }
    val notNovelCount: Int get() = removed.count { it.kind == Kind.NOT_NOVEL }
}
