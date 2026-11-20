package com.radium.inkwell.core.guard

/**
 * 违规词表，三档：
 * - [HARD]：露骨描写、违法内容，命中即满 60
 * - [SYMBOLS]：🔞 这类符号标记。**来源名里带它就是明示**，同样命中即满 60
 * - [SOFT]：擦边词，累计计分（每个 8 分）
 *
 * 符号单独一档的原因：它是"作者自己贴的标签"，比"床戏"这种正文用词确定得多 ——
 * 同一个词在正经文学里也常见，🔞 不是。把它按擦边词只给 8 分，等于漏判。
 */
object Lexicon {

    private val HARD = listOf(
        "做爱", "性交", "口交", "肛交", "肉棒", "肉穴", "阴蒂", "阴道", "龟头",
        "潮吹", "中出", "内射", "颜射", "轮奸", "乱伦", "兽交", "奸淫",
        "自慰", "淫水", "骚穴", "巨乳", "爆乳",
        "制毒", "毒品配方", "枪支改装", "自杀教程", "赌博平台", "洗钱渠道",
    )

    /** 符号标记：命中即满 60 */
    private val SYMBOLS = listOf(
        "\uD83D\uDD1E",   // 🔞
        "\uD83D\uDC1E",   // 🐞
        "\u274C",          // ❌
        "\uD83D\uDEAB",   // 🚫
        "\uD83D\uDC8B",   // 💋
    )

    /** 明确成人向的站点名/标签 */
    private val STRONG_LABEL = listOf(
        "po18", "涩涩", "情幻", "porn", "hentai", "成人小说", "成人文学",
        "黄文", "色情", "肉文", "r18", "18禁",
    )

    /** 站点风格标签：BL / JK 这类，多是成人向，但在正常来源里也偶尔出现，按强标签处理 */
    private val STYLE_LABEL = listOf("bl小说", "jk小说", "bl文学", "耽美", "gl小说")

    private val SOFT = listOf(
        "床戏", "呻吟", "娇喘", "情欲", "欲火", "赤裸", "缱绻", "春宵",
        "尤物", "野合", "偷情", "玩物", "调教", "荡妇", "发情",
        "无删减", "未删节", "里番", "无码", "成人向", "18+",
        "nsfw", "adult", "h漫", "黄色", "激情",
    )

    private const val SOFT_WEIGHT = 8

    /** 去掉空白与常见分隔符，全角转半角；emoji 原样保留 */
    private fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch.isWhitespace() -> i++
                ch == '·' || ch == '•' || ch == '*' || ch == '-' || ch == '_' -> i++
                ch.code in 0xFF01..0xFF5E -> {
                    sb.append((ch.code - 0xFEE0).toChar())
                    i++
                }
                // 代理对整体保留，别把 emoji 拆成两个孤立的 char
                Character.isHighSurrogate(ch) && i + 1 < text.length -> {
                    sb.append(ch).append(text[i + 1])
                    i += 2
                }
                else -> {
                    sb.append(ch.lowercaseChar())
                    i++
                }
            }
        }
        return sb.toString()
    }

    fun firstHardHit(text: String): String? {
        if (text.isBlank()) return null
        val t = normalize(text)
        return (HARD + SYMBOLS + STRONG_LABEL + STYLE_LABEL).firstOrNull { t.contains(it) }
    }

    /** 单段文本得分：硬信号 / 符号 / 强标签直接满 60，擦边词累加 */
    fun score(text: String): Int {
        if (text.isBlank()) return 0
        val t = normalize(text)
        if (HARD.any { t.contains(it) }) return 60
        if (SYMBOLS.any { t.contains(it) }) return 60
        if (STRONG_LABEL.any { t.contains(it) }) return 60
        if (STYLE_LABEL.any { t.contains(it) }) return 60
        var s = 0
        for (w in SOFT) if (t.contains(w)) s += SOFT_WEIGHT
        return minOf(s, 60)
    }
}
