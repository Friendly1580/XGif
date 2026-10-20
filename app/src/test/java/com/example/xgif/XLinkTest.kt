package com.example.xgif
import org.junit.Assert.assertEquals
import org.junit.Test
class XLinkTest {
    @Test fun photoSuffix() = assertEquals("https://x.com/i/status/2112074026441199770", XLink.normalize("https://x.com/Insuelin/status/2112074026441199770/photo/1"))
    @Test fun sharedText() = assertEquals("https://x.com/i/status/123", XLink.normalize("看看 https://twitter.com/user/status/123/video/1?s=20"))
    @Test fun normalized() = assertEquals("https://x.com/i/status/123", XLink.normalize("https://x.com/i/status/123"))
    @Test(expected = IllegalArgumentException::class) fun fakeHost() { XLink.normalize("https://x.com.evil.test/user/status/123") }
    @Test(expected = IllegalArgumentException::class) fun userInfo() { XLink.normalize("https://evil@x.com/user/status/123") }
    @Test(expected = IllegalArgumentException::class) fun profile() { XLink.normalize("https://x.com/Insuelin") }
}
