package com.coloryr.allmusic.server.core.api.qqmusic;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQMusicCredentialTest {
    @Test
    void loadsOnlyTheVersionTwoNestedCredential() {
        JsonObject source = new JsonObject();
        source.addProperty("musicid", "o00123456");
        source.addProperty("musickey", "Q_H_L_test");
        source.addProperty("str_musicid", "00123456");
        source.addProperty("refresh_key", "refresh");
        source.addProperty("loginType", 2);
        JsonObject root = new JsonObject();
        root.add("credential", source);

        QQMusicCredential credential = QQMusicCredential.fromConfig(root);

        assertTrue(credential.isComplete());
        assertEquals("123456", credential.musicId);
        assertEquals(2, credential.loginType);
        assertTrue(credential.canRefresh());
        assertTrue(credential.cookieHeader().contains("uin=123456"));
        assertTrue(credential.cookieHeader().contains("qm_keyst=Q_H_L_test"));
    }

    @Test
    void doesNotTreatLegacyTopLevelFieldsAsCredentials() {
        JsonObject root = new JsonObject();
        root.addProperty("uin", "123456");
        root.addProperty("qqmusicKey", "legacy-key");

        assertFalse(QQMusicCredential.fromConfig(root).isComplete());
    }

    @Test
    void rejectsPartialCredentials() {
        JsonObject data = new JsonObject();
        data.addProperty("musicid", "123456");
        assertFalse(QQMusicCredential.fromLoginData(data).isComplete());
    }
}
