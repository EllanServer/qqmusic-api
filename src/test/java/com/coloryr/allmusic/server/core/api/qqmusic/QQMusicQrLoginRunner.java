package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.server.core.AllMusic;
import com.coloryr.allmusic.server.core.side.IAllMusicLogger;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

public final class QQMusicQrLoginRunner {
    private QQMusicQrLoginRunner() {
    }

    public static void main(String[] args) throws Exception {
        File config = new File(args[0]).getAbsoluteFile();
        final File statusLog = new File(config.getParentFile(), "qqmusic-login-status.log");
        Files.deleteIfExists(statusLog.toPath());
        System.setProperty("qqmusic.config", config.getAbsolutePath());
        AllMusic.log = new IAllMusicLogger() {
            @Override
            public void data(String message) {
                System.out.println(message);
                appendStatus(statusLog, message);
            }

            @Override
            public void data(Component component) {
                System.out.println(component);
                appendStatus(statusLog, String.valueOf(component));
            }
        };

        new QQMusicApi();
        long endAt = System.currentTimeMillis() + 330_000L;
        while (System.currentTimeMillis() < endAt) {
            if (hasCompleteCredential(config)) {
                System.out.println("QQMUSIC_LOGIN_READY");
                return;
            }
            Thread.sleep(1000L);
        }
        throw new IllegalStateException("QQ Music login did not complete within the test window");
    }

    private static synchronized void appendStatus(File file, String message) {
        try {
            Files.write(file.toPath(), (message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static boolean hasCompleteCredential(File config) {
        try {
            if (!config.isFile()) {
                return false;
            }
            String json = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
            JsonObject object = AllMusic.gson.fromJson(json, JsonObject.class);
            JsonObject credential = object == null ? null : QQMusicSupport.object(object, "credential");
            String musicId = QQMusicSupport.string(credential, "musicid");
            String musicKey = QQMusicSupport.string(credential, "musickey");
            return !"0".equals(QQMusicSupport.normalizeUin(musicId))
                    && !QQMusicSupport.isBlank(musicKey);
        } catch (Exception ignored) {
            return false;
        }
    }
}
