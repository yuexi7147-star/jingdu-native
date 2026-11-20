package com.radium.inkwell.core.guard

/**
 * 域名与路径特征。
 *
 * 不内置大列表（成人站域名换得太快，攒不过来也维护不起），只放"永远不该出现"的 TLD
 * 与路径片段；更全的名单由 assets 侧追加，改数据不改代码。
 */
object DomainRules {

    /** 成人专用顶级域 */
    private val ADULT_TLD = setOf("xxx", "porn", "sex", "adult", "sexy", "cam", "hentai")

    /** 路径或分类标签里出现这些片段即可定性 */
    private val ADULT_PATH = listOf(
        "/r18", "/adult", "/novel18", "/18plus", "/nsfw", "/erotic",
        "/hentai", "/sexy", "/porn",
    )

    /** 成人内容站常用的域名片段 */
    private val SUSPICIOUS_PART = listOf(
        "avmoo", "javbus", "javdb", "missav", "hanime",
        // 常见成人小说站
        "po18", "sesebook", "blpopo", "pdok", "porn", "hentai",
        "sebooks", "lumanovel", "xbookcn",
    )

    fun check(host: String): String? {
        if (host.isBlank()) return null
        val h = host.lowercase().removePrefix("www.")
        val tld = h.substringAfterLast('.', "")
        if (tld in ADULT_TLD) return "成人顶级域 ." + tld
        val part = SUSPICIOUS_PART.firstOrNull { h.contains(it) }
        if (part != null) return "域名含 " + part
        return null
    }

    fun pathHit(text: String): String? {
        if (text.isBlank()) return null
        val t = text.lowercase()
        return ADULT_PATH.firstOrNull { t.contains(it) }
    }
}
