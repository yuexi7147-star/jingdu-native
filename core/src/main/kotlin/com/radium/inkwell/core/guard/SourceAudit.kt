package com.radium.inkwell.core.guard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 书源安全检查：判定一个书源是否该被移除。
 *
 * 两类理由分开报：
 * - [Verdict.LoginRequired]：书源声明了登录字段。这是结构化判定，几乎不会错。
 * - [Verdict.Harmful]：多信号累计分超阈值（域名 / 名称 / JSON 全文 / 名下书籍）。
 *
 * 判定只读本地数据，不联网；书名与章节标题来自已入库的书 —— 那是书源自己产出的内容，
 * 比它的元数据可信。
 */
object SourceAudit {

    /** 违规判定阈值 */
    const val THRESHOLD = 60

    sealed interface Verdict {
        val reason: String

        data class LoginRequired(override val reason: String) : Verdict
        data class Harmful(override val reason: String, val score: Int) : Verdict
        /** 非小说类：漫画、听书、音频、教材等 */
        data class NotNovel(override val reason: String) : Verdict
    }

    data class BookEvidence(
        val title: String,
        val author: String? = null,
        val intro: String? = null,
        val chapterTitles: List<String> = emptyList(),
    )

    /**
     * @param id 书源 id
     * @param name 书源名
     * @param group 分组
     * @param json 完整书源 JSON
     * @param books 该源下已入库的书（最多取十几本即可）
     */
    fun audit(
        id: String,
        name: String?,
        group: String?,
        json: String,
        books: List<BookEvidence> = emptyList(),
    ): Verdict? {
        // 一、需要登录：字段判定，命中即删
        requiresLogin(json)?.let { return Verdict.LoginRequired(it) }

        // 二、非小说类（漫画 / 听书 / 音频 / 教材）
        NotNovel.check(json, name, group)?.let { return Verdict.NotNovel(it) }

        // 三、内容违规：累计分
        val hits = mutableListOf<String>()
        var score = 0

        val host = hostOf(json) ?: ""
        val domainHit = DomainRules.check(host)
        if (domainHit != null) {
            score += 100
            hits += "域名可疑（$domainHit）"
        }

        val meta = listOfNotNull(name, group).joinToString(" ")
        val metaWord = Lexicon.firstHardHit(meta)
        if (metaWord != null) {
            score += 60
            hits += "名称含「$metaWord」"
        }

        val surface = jsonSurface(json)
        val jsonWord = Lexicon.firstHardHit(surface)
        if (jsonWord != null) {
            score += 40
            hits += "规则含「$jsonWord」"
        }
        if (DomainRules.pathHit(surface) != null) {
            score += 40
            hits += "规则路径含成人标识"
        }

        // 书源自己抓回来的书，最有说服力
        var bookScore = 0
        var bookHit = 0
        for (b in books.take(20)) {
            val text = listOfNotNull(b.title, b.author, b.intro).joinToString(" ") +
                " " + b.chapterTitles.take(10).joinToString(" ")
            val s = Lexicon.score(text)
            if (s > 0) {
                bookScore += s
                bookHit++
            }
        }
        if (bookHit >= 2) {
            val add = minOf(bookScore, 60)
            score += add
            hits += "名下 $bookHit 本书内容可疑"
        }

        return if (score >= THRESHOLD) {
            Verdict.Harmful(hits.joinToString("、"), score)
        } else null
    }

    /** 需要登录的判定：任一字段有值即成立 */
    fun requiresLogin(json: String): String? {
        val o = parse(json) ?: return null

        fun str(k: String): String? =
            o[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }

        str("loginUrl")?.let { return "书源声明了登录页" }
        str("loginUi")?.let { return "要求填写登录表单" }
        str("loginCheckJs")?.let { return "需要登录态校验脚本" }
        str("loginJs")?.let { return "需要运行登录脚本" }

        val header = str("header") ?: ""
        if (Regex("\"(Cookie|Authorization)\"\\s*:\\s*\"[^\"]+\"", RegexOption.IGNORE_CASE).containsMatchIn(header)) {
            return "请求头内嵌登录凭据"
        }

        val search = str("searchUrl") ?: ""
        if (Regex("\\{\\{\\s*(cookie|token|userId|uid)\\s*\\}\\}", RegexOption.IGNORE_CASE).containsMatchIn(search)) {
            return "搜索地址依赖登录变量"
        }
        return null
    }

    /** 把 JSON 里所有字符串值拼起来 —— 分类名、路径、描述全在这里面 */
    private fun jsonSurface(json: String): String =
        Regex("\"([^\"]{2,})\"").findAll(json).joinToString(" ") { it.groupValues[1] }

    private fun hostOf(json: String): String? {
        val o = parse(json) ?: return null
        val url = runCatching { o["bookSourceUrl"]?.jsonPrimitive?.content }.getOrNull() ?: return null
        return runCatching {
            java.net.URI(url.trim()).host ?: url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        }.getOrNull()
    }

    private fun parse(json: String): JsonObject? =
        runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
}
