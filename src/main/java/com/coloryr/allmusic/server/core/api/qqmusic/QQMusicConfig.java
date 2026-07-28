package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.server.core.AllMusic;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

final class QQMusicConfig {
    private static final String FILE_NAME = "qqmusic.json";

    final File file;
    final boolean qrLogin;
    final int qrLoginTimeoutSeconds;
    final int qrLoginPollSeconds;
    final String qualities;
    final int searchLimit;
    final int timeoutSeconds;
    final boolean autoRefresh;

    private final JsonObject document;
    private volatile QQMusicCredential credential;

    private QQMusicConfig(File file, JsonObject document, QQMusicCredential credential,
                          boolean qrLogin, int qrLoginTimeoutSeconds, int qrLoginPollSeconds,
                          String qualities, int searchLimit, int timeoutSeconds, boolean autoRefresh) {
        this.file = file;
        this.document = document;
        this.credential = credential;
        this.qrLogin = qrLogin;
        this.qrLoginTimeoutSeconds = qrLoginTimeoutSeconds;
        this.qrLoginPollSeconds = qrLoginPollSeconds;
        this.qualities = qualities;
        this.searchLimit = searchLimit;
        this.timeoutSeconds = timeoutSeconds;
        this.autoRefresh = autoRefresh;
    }

    static QQMusicConfig load() {
        File file = findConfigFile();
        JsonObject document = readDocument(file);
        if (document == null) {
            document = template();
            try {
                QQMusicSupport.writeTextAtomic(file, AllMusic.gson.toJson(document));
            } catch (IOException e) {
                QQMusicSupport.logError("QQ Music config template write failed: " + e.getMessage());
            }
        }

        applyOverrides(document);
        QQMusicCredential credential = QQMusicCredential.fromConfig(document);
        return new QQMusicConfig(
                file,
                document,
                credential,
                QQMusicSupport.bool(document, "qrLogin", true),
                clamp(QQMusicSupport.integer(document, "qrLoginTimeoutSeconds", 120), 30, 300),
                clamp(QQMusicSupport.integer(document, "qrLoginPollSeconds", 2), 1, 10),
                QQMusicSupport.firstNonBlank(QQMusicSupport.string(document, "qualities"), "m4a,128,320"),
                clamp(QQMusicSupport.integer(document, "searchLimit", 20), 1, 50),
                clamp(QQMusicSupport.integer(document, "timeoutSeconds", 20), 5, 120),
                QQMusicSupport.bool(document, "autoRefresh", true)
        );
    }

    QQMusicCredential credential() {
        return credential;
    }

    synchronized void saveCredential(QQMusicCredential replacement) throws IOException {
        if (replacement == null || !replacement.isComplete()) {
            throw new IOException("Refusing to save an incomplete QQ Music credential");
        }
        JsonObject updated = document.deepCopy();
        updated.add("credential", replacement.toJson());
        updated.addProperty("qrLogin", qrLogin);
        updated.addProperty("qrLoginTimeoutSeconds", qrLoginTimeoutSeconds);
        updated.addProperty("qrLoginPollSeconds", qrLoginPollSeconds);
        updated.addProperty("qualities", qualities);
        updated.addProperty("searchLimit", searchLimit);
        updated.addProperty("timeoutSeconds", timeoutSeconds);
        updated.addProperty("autoRefresh", autoRefresh);

        QQMusicSupport.writeTextAtomic(file, AllMusic.gson.toJson(updated));
        document.entrySet().clear();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : updated.entrySet()) {
            document.add(entry.getKey(), entry.getValue());
        }
        credential = replacement;
    }

    File directory() {
        File parent = file.getAbsoluteFile().getParentFile();
        return parent == null ? new File(".") : parent;
    }

    List<String> qualityList() {
        List<String> result = new ArrayList<>();
        for (String value : qualities.split(",")) {
            if (!QQMusicSupport.isBlank(value)) {
                result.add(value.trim());
            }
        }
        if (result.isEmpty()) {
            result.add("m4a");
        }
        return result;
    }

    private static JsonObject readDocument(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            JsonObject object = AllMusic.gson.fromJson(QQMusicSupport.readFile(file), JsonObject.class);
            return object == null ? template() : object;
        } catch (Exception e) {
            QQMusicSupport.logError("QQ Music config read failed: " + e.getMessage());
            return template();
        }
    }

    private static JsonObject template() {
        JsonObject object = new JsonObject();
        object.add("credential", QQMusicCredential.EMPTY.toJson());
        object.addProperty("qrLogin", true);
        object.addProperty("qrLoginTimeoutSeconds", 120);
        object.addProperty("qrLoginPollSeconds", 2);
        object.addProperty("qualities", "m4a,128,320");
        object.addProperty("searchLimit", 20);
        object.addProperty("timeoutSeconds", 20);
        object.addProperty("autoRefresh", true);
        return object;
    }

    private static File findConfigFile() {
        String explicit = QQMusicSupport.firstNonBlank(
                safeProperty("qqmusic.config"),
                safeEnvironment("QQMUSIC_CONFIG")
        );
        if (!QQMusicSupport.isBlank(explicit)) {
            return new File(explicit).getAbsoluteFile();
        }

        List<File> candidates = new ArrayList<>();
        File jarDirectory = jarDirectory();
        if (jarDirectory != null) {
            candidates.add(new File(jarDirectory, FILE_NAME));
        }
        candidates.add(new File(FILE_NAME));
        candidates.add(new File("allmusic_server", FILE_NAME));
        candidates.add(new File(new File("allmusic_server", "api"), FILE_NAME));
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate.getAbsoluteFile();
            }
        }
        return candidates.get(0).getAbsoluteFile();
    }

    private static File jarDirectory() {
        try {
            CodeSource source = QQMusicApi.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            File location = new File(source.getLocation().toURI());
            return location.isFile() ? location.getParentFile() : location;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void applyOverrides(JsonObject document) {
        applyString(document, "qualities", safeProperty("qqmusic.qualities"), safeEnvironment("QQMUSIC_QUALITIES"));
    }

    private static void applyString(JsonObject document, String key, String... values) {
        String value = QQMusicSupport.firstNonBlank(values);
        if (!QQMusicSupport.isBlank(value)) {
            document.addProperty(key, value);
        }
    }

    private static String safeProperty(String key) {
        try {
            return System.getProperty(key, "");
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private static String safeEnvironment(String key) {
        try {
            String value = System.getenv(key);
            return value == null ? "" : value;
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
