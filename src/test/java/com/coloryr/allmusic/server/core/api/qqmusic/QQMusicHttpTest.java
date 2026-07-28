package com.coloryr.allmusic.server.core.api.qqmusic;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQMusicHttpTest {
    @Test
    void extractsCookiesFromCombinedSetCookieHeaders() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Set-Cookie", Arrays.asList(
                "pt2gguin=o123456; Expires=Wed, 09 Jun 2027 10:18:14 GMT; Path=/, "
                        + "p_skey=test-ticket; Path=/; Domain=.qq.com; HttpOnly",
                "pt4_token=token-value; Secure; SameSite=None"
        ));

        QQMusicHttp.CookieJar jar = new QQMusicHttp.CookieJar();
        jar.mergeSetCookies(headers);

        assertEquals("o123456", jar.get("PT2GGuin"));
        assertEquals("test-ticket", jar.get("p_skey"));
        assertEquals("token-value", jar.get("pt4_token"));
        assertFalse(jar.names().contains("Expires"));
        assertTrue(jar.header().contains("p_skey=test-ticket"));
    }

    @Test
    void cookieHeaderParserIgnoresAttributesAndEmptyValues() {
        QQMusicHttp.CookieJar jar = QQMusicHttp.CookieJar.parse(
                "uin=o123456; qqmusic_key=key-value; Path=/; empty=");

        assertEquals("o123456", jar.get("uin"));
        assertEquals("key-value", jar.get("qqmusic_key"));
        assertFalse(jar.names().contains("Path"));
        assertFalse(jar.header().contains("empty="));
    }

    @Test
    void preservesUsableTicketWhenAnotherDomainClearsTheSameCookieName() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Set-Cookie", Arrays.asList(
                "p_skey=usable-ticket; Path=/; Domain=graph.qq.com, "
                        + "p_skey=; Max-Age=0; Path=/; Domain=.qq.com"
        ));

        QQMusicHttp.CookieJar jar = new QQMusicHttp.CookieJar();
        jar.mergeSetCookies(headers);

        assertEquals("usable-ticket", jar.get("p_skey"));
    }
}
