package com.example.xgif

import java.net.URI

object XLink {
    fun normalize(text: String): String {
        val candidate = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text)?.value
            ?: throw IllegalArgumentException("請貼上完整的 X 貼文網址。")
        val uri = try { URI(candidate) } catch (_: Exception) {
            throw IllegalArgumentException("網址格式錯誤。")
        }
        require(uri.host?.lowercase() in setOf("x.com", "www.x.com", "mobile.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com")) {
            "只接受 x.com 或 twitter.com 的貼文連結。"
        }
        require(uri.userInfo == null && uri.port == -1) { "網址格式不受支援。" }
        val id = Regex("^/(?:[A-Za-z0-9_]+/status|i/status|i/web/status)/(\\d+)(?:/.*)?$")
            .find(uri.path.orEmpty())?.groupValues?.get(1)
            ?: throw IllegalArgumentException("請使用包含 /status/數字 的貼文網址。")
        return "https://x.com/i/status/$id"
    }
}
