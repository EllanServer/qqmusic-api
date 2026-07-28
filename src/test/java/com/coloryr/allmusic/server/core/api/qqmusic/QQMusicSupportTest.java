package com.coloryr.allmusic.server.core.api.qqmusic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QQMusicSupportTest {
    @Test
    void normalizesQQUserIds() {
        assertEquals("123456789", QQMusicSupport.normalizeUin("o0123456789"));
        assertEquals("123456789", QQMusicSupport.normalizeUin("00123456789"));
        assertEquals("0", QQMusicSupport.normalizeUin("not-a-uin"));
    }

    @Test
    void computesQQHash33() {
        assertEquals(193485963, QQMusicSupport.hash33("abc", 5381));
    }

    @Test
    void decodesOAuthQueryParameters() throws Exception {
        String location = "https://y.qq.com/callback?state=state&code=a%2Bb%3D%3D&other=1";
        assertEquals("a+b==", QQMusicSupport.queryParameter(location, "code"));
    }
}
