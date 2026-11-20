package com.radium.inkwell.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URI

/**
 * 轻量校验：只测「这个站点还活着吗」。
 *
 * 完整校验（SourceChecker）要跑 搜索 -> 详情 -> 目录 -> 正文，单个来源十几秒；
 * 515 个来源要一个多小时，后台任务跑不完会被系统掐掉。
 *
 * 这里只做 DNS 解析 + HEAD 请求，1-2 秒一个，几百个十来分钟扫完 —— 够用来清死站。
 * 测不出「站点活着但规则坏了」，那种留给手动完整校验与每 7 天的全量。
 */
class SourceLightCheck(private val http: OkHttpClient) {

    sealed interface Verdict {
        /** 确定失效：域名没了、页面不存在 */
        data class Dead(val reason: String) : Verdict
        /** 疑似：被拒、服务端错误、超时 —— 可能只是临时抽风 */
        data class Suspicious(val reason: String) : Verdict
        data object Alive : Verdict
        /** 取不到地址、没网 —— 什么都不该做 */
        data object Unknown : Verdict
    }

    suspend fun check(url: String): Verdict = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val host = runCatching { URI(trimmed).host }.getOrNull()
            ?: runCatching { URI("https://" + trimmed).host }.getOrNull()
            ?: return@withContext Verdict.Unknown
        if (host.isBlank()) return@withContext Verdict.Unknown

        val resolved = runCatching { InetAddress.getByName(host) }.isSuccess
        if (!resolved) return@withContext Verdict.Dead("域名解析失败")

        val full = if (trimmed.startsWith("http")) trimmed else "https://" + trimmed
        val req = Request.Builder().url(full).head().header("User-Agent", "Mozilla/5.0").build()
        runCatching { http.newCall(req).execute() }.fold(
            onSuccess = { resp ->
                resp.use {
                    when (it.code) {
                        200, 201, 204, 301, 302, 304, 307, 308 -> Verdict.Alive
                        404, 410 -> Verdict.Dead("页面不存在（HTTP " + it.code + "）")
                        401, 403 -> Verdict.Suspicious("访问被拒（HTTP " + it.code + "）")
                        in 500..599 -> Verdict.Suspicious("站点出错（HTTP " + it.code + "）")
                        else -> Verdict.Suspicious("异常状态（HTTP " + it.code + "）")
                    }
                }
            },
            onFailure = { e ->
                val msg = e.message ?: ""
                if (msg.contains("UnknownHost", true)) Verdict.Dead("域名解析失败")
                else Verdict.Suspicious("连接失败（" + msg.take(24) + "）")
            },
        )
    }
}
