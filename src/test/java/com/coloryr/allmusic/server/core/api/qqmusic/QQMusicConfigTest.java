package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.server.core.AllMusic;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQMusicConfigTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearConfigOverride() {
        System.clearProperty("qqmusic.config");
    }

    @Test
    void hotReloadsSettingsAndCredentials() throws Exception {
        Path file = writeConfig(configDocument(20, QQMusicCredential.EMPTY));
        System.setProperty("qqmusic.config", file.toString());
        QQMusicConfig config = QQMusicConfig.load();

        QQMusicCredential replacement = credential("123456", "Q_H_L_reloaded");
        QQMusicSupport.writeTextAtomic(file.toFile(),
                AllMusic.gson.toJson(configDocument(7, replacement)));

        assertTrue(config.reloadNow());
        assertEquals(7, config.searchLimit());
        assertEquals("123456", config.credential().musicId);
        assertTrue(config.credential().isComplete());
        assertEquals(1L, config.generation());
    }

    @Test
    void keepsThePreviousSnapshotWhileAReplacementIsInvalid() throws Exception {
        Path file = writeConfig(configDocument(20, credential("123456", "Q_H_L_original")));
        System.setProperty("qqmusic.config", file.toString());
        QQMusicConfig config = QQMusicConfig.load();

        Files.write(file, "{not-json".getBytes(StandardCharsets.UTF_8));

        assertFalse(config.reloadNow());
        assertEquals(20, config.searchLimit());
        assertEquals("Q_H_L_original", config.credential().musicKey);

        QQMusicSupport.writeTextAtomic(file.toFile(),
                AllMusic.gson.toJson(configDocument(11, credential("654321", "Q_H_L_fixed"))));

        assertTrue(config.reloadNow());
        assertEquals(11, config.searchLimit());
        assertEquals("654321", config.credential().musicId);
    }

    @Test
    void savingCredentialPreservesUnknownConfigurationFields() throws Exception {
        JsonObject document = configDocument(20, QQMusicCredential.EMPTY);
        JsonObject future = new JsonObject();
        future.addProperty("enabled", true);
        document.add("futureSetting", future);
        Path file = writeConfig(document);
        System.setProperty("qqmusic.config", file.toString());
        QQMusicConfig config = QQMusicConfig.load();

        config.saveCredential(credential("123456", "Q_H_L_saved"));

        JsonObject saved = AllMusic.gson.fromJson(
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8), JsonObject.class);
        assertTrue(saved.getAsJsonObject("futureSetting").get("enabled").getAsBoolean());
        assertEquals("123456", saved.getAsJsonObject("credential").get("musicid").getAsString());
        assertTrue(config.credential().isComplete());
    }

    @Test
    void fileSystemWatcherPublishesAnAtomicReplacement() throws Exception {
        Path file = writeConfig(configDocument(20, credential("123456", "Q_H_L_original")));
        System.setProperty("qqmusic.config", file.toString());
        QQMusicConfig config = QQMusicConfig.load();
        QQMusicLogin login = new QQMusicLogin(config, new QQMusicClient(config));
        login.start();

        QQMusicSupport.writeTextAtomic(file.toFile(),
                AllMusic.gson.toJson(configDocument(6, credential("654321", "Q_H_L_reloaded"))));

        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline && config.searchLimit() != 6) {
            Thread.sleep(25L);
        }

        assertEquals(6, config.searchLimit());
        assertEquals("654321", config.credential().musicId);
    }

    private Path writeConfig(JsonObject document) throws Exception {
        Path file = temporaryDirectory.resolve("qqmusic.json");
        Files.write(file, AllMusic.gson.toJson(document).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static JsonObject configDocument(int searchLimit, QQMusicCredential credential) {
        JsonObject document = new JsonObject();
        document.add("credential", credential.toJson());
        document.addProperty("qrLogin", false);
        document.addProperty("qrLoginTimeoutSeconds", 120);
        document.addProperty("qrLoginPollSeconds", 2);
        document.addProperty("qualities", "m4a,128,320");
        document.addProperty("searchLimit", searchLimit);
        document.addProperty("timeoutSeconds", 20);
        document.addProperty("autoRefresh", false);
        return document;
    }

    private static QQMusicCredential credential(String musicId, String musicKey) {
        return new QQMusicCredential(
                "", "refresh-token", "", 0L,
                musicId, musicKey, "", musicId,
                "refresh-key", 0L, 0L, 2
        );
    }
}
