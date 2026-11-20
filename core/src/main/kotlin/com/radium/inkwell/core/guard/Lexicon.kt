package com.radium.inkwell.core.guard

/**
 * 违规词表。分两档：
 * - [HARD]：强信号，命中一次就足以定性（单条 +60 分）
 * - [SOFT]：擦边词，累计计分（每个 +8）
 *
 * 符号标记（🔞 🚫 之类）也算 SOFT —— 来源名里带这种图标就是明示。
 */
object Lexicon {

    /** 强信号：露骨性描写、违法内容 */
    private val HARD = listOf(
        "做爱", "性交", "口交", "肛交", "肉棒", "肉穴", "阴蒂", "阴道", "龟头",
        "潮吹", "中出", "内射", "颜射", "轮奸", "乱伦", "兽交", "奸淫",
        "自慰", "淫水", "骚穴", "巨乳", "爆乳",
        "制毒", "毒品配方", "枪支改装", "自杀教程", "赌博平台", "洗钱渠道",
    )

    /** 擦边：情色暗示、成人向标签、符号标记 */
    private val SOFT = listOf(
        "床戏", "呻吟", "娇喘", "情欲", "欲火", "赤裸", "缱绻", "春宵",
        "尤物", "野合", "偷情", "玩物", "调教", "荡妇", "发情",
        "无删减", "未删节", "里番", "无码", "成人向", "18禁", "18+", "R18",
        "NSFW", "ADULT", "H漫", "色情", "黄色", "黄文", "成人小说", "激情",
        // 符号标记：来源名里带这些就是明示
        "\uD83D\uDD1E",
        "\uD83D\uDC1E",
        "\u274C",
        "\uD83D\uDEAB",
        "\uD83D\uDC8B",
    )

    private const val SOFT_WEIGHT = 8

    /** 归一化：去空白标点、全角转半角，避免「做 爱」「做·爱」绕过 */
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.isWhitespace() -> {}
                ch == '·' || ch == '•' || ch == '*' || ch == '-' || ch == '_' -> {}
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    fun firstHardHit(text: String): String? {
        if (text.isBlank()) return null
        val t = normalize(text)
        return HARD.firstOrNull { t.contains(it) }
    }

    /** 单段文本得分：强信号直接给高分，擦边词累加 */
    fun score(text: String): Int {
        if (text.isBlank()) return 0
        val t = normalize(text)
        if (HARD.any { t.contains(it) }) return 60
        var s = 0
        for (w in SOFT) if (t.contains(w)) s += SOFT_WEIGHT
        return minOf(s, 60)
    }
}
