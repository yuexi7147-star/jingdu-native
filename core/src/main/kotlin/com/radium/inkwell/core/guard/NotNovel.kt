package com.radium.inkwell.core.guard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 非小说类来源的判定。
 *
 * 两条依据：
 * 1. [bookSourceType]：Legado 用这个字段区分内容类型 —— 0 文本、1 音频、2 图片（漫画）、3 文件。
 *    这是结构化字段，几乎不会错。
 * 2. 名称与分组里的关键词：有些来源不填 type，但名字写着「漫画」「听书」。
 */
object NotNovel {

    /** Legado: 1 = 音频，2 = 图片/漫画，3 = 文件 */
    private val NON_TEXT_TYPES = mapOf(
        1 to "音频来源",
        2 to "漫画来源",
        3 to "文件来源",
    )

    private val KEYWORDS = listOf(
        "漫画" to "漫画", "漫畫" to "漫画", "韩漫" to "漫画", "日漫" to "漫画",
        "听书" to "听书", "有声" to "有声", "音频" to "音频", "电台" to "电台",
        "短剧" to "短剧", "视频" to "视频", "影视" to "影视",
        "教材" to "教材", "学术" to "学术", "论文" to "论文", "期刊" to "期刊",
        "文档" to "文档", "资料" to "资料",
    )

    fun check(json: String, name: String?, group: String?): String? {
        val o = parse(json)

        // 一、类型字段
        o?.get("bookSourceType")?.let { el ->
            val t = runCatching { el.jsonPrimitive.content.trim().toInt() }.getOrNull()
            if (t != null && t in NON_TEXT_TYPES) return NON_TEXT_TYPES.getValue(t)
        }

        // 二、名称与分组
        val text = listOfNotNull(name, group).joinToString(" ")
        if (text.isNotBlank()) {
            val hit = KEYWORDS.firstOrNull { text.contains(it.first) }
            if (hit != null) return "名称/分组含「${hit.second}」"
        }
        return null
    }

    private fun parse(json: String): JsonObject? =
        runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
}
