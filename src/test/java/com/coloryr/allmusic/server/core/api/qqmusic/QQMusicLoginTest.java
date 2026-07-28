package com.coloryr.allmusic.server.core.api.qqmusic;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QQMusicLoginTest {
    @Test
    void parsesSuccessfulPtloginCallback() throws Exception {
        String body = "ptuiCB('0','0','https://example.test/check?uin=123456\\x26ptsigx=sig-value',"
                + "'0','login ok','nickname');";

        QQMusicLogin.QrStatus status = QQMusicLogin.parseCallback(body);

        assertEquals("0", status.code);
        assertEquals("123456", QQMusicSupport.queryParameter(status.callbackUrl, "uin"));
        assertEquals("sig-value", QQMusicSupport.queryParameter(status.callbackUrl, "ptsigx"));
    }

    @Test
    void rejectsUnexpectedPtloginResponse() {
        assertThrows(IOException.class, () -> QQMusicLogin.parseCallback("not a callback"));
    }
}
