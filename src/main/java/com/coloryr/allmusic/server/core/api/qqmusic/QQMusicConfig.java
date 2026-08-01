package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.server.core.AllMusic;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class QQMusicConfig {
    private static final String FILE_NAME = "qqmusic.json";
    private static final long RELOAD_CHECK_NANOS = TimeUnit.SECONDS.toNanos(30L);

    final File file;

    private volatile JsonObject document;
    private volatile Snapshot snapshot;
    private volatile String documentFingerprint;
    private volatile String rejectedFingerprint = "";
    private volatile long generation;
    private volatile long nextReloadCheckNanos;
    private volatile FileState observedFileState;

    private QQMusicConfig(File file, JsonObject document) {
        this.file = file;
        install(document, false);
        observedFileState = readFileState(file);
    }

    static QQMusicConfig load() {
        File file = findConfigFile();
        JsonObject document = null;
        if (file.isFile()) {
            try {
                document = readDocument(file);
            } catch (Exception e) {
                QQMusicSupport.logError("QQ Music config read failed; using safe defaults until the file is fixed: "
                        + e.getMessage());
            }
        }
        if (document == null) {
            document = template();
            if (!file.isFile()) {
                try {
                    QQMusicSupport.writeTextAtomic(file, AllMusic.gson.toJson(document));
                } catch (IOException e) {
                    QQMusicSupport.logError("QQ Music config template write failed: " + e.getMessage());
                }
            }
        }
        applyOverrides(document);
        return new QQMusicConfig(file, document);
    }

    /**
     * Reloads a complete replacement document and keeps the previous snapshot
     * when an editor or upload leaves a transient invalid file behind.
     */
    boolean reloadIfChanged() {
        long now = System.nanoTime();
        if (now < nextReloadCheckNanos) {
            return false;
        }
        synchronized (this) {
            now = System.nanoTime();
            if (now < nextReloadCheckNanos) {
                return false;
            }
            nextReloadCheckNanos = now + RELOAD_CHECK_NANOS;
            return reloadFromDisk(false);
        }
    }

    synchronized boolean reloadNow() {
        nextReloadCheckNanos = System.nanoTime() + RELOAD_CHECK_NANOS;
        return reloadFromDisk(true);
    }

    private boolean reloadFromDisk(boolean force) {
        FileState currentState = readFileState(file);
        if (!currentState.exists) {
            observedFileState = currentState;
            rejectOnce("missing", "QQ Music config reload skipped because the file is missing");
            return false;
        }
        if (!force && currentState.equals(observedFileState)) {
            return false;
        }
        observedFileState = currentState;

        String raw;
        try {
            raw = QQMusicSupport.readFile(file);
        } catch (Exception e) {
            rejectOnce("read:" + e.getClass().getName() + ':' + e.getMessage(),
                    "QQ Music config reload failed; keeping the previous configuration: " + e.getMessage());
            return false;
        }

        JsonObject updated;
        try {
            updated = AllMusic.gson.fromJson(raw, JsonObject.class);
            if (updated == null) {
                throw new IOException("configuration document is empty");
            }
            applyOverrides(updated);
        } catch (Exception e) {
            rejectOnce("json:" + raw.hashCode(),
                    "QQ Music config reload rejected invalid JSON; keeping the previous configuration: "
                            + e.getMessage());
            return false;
        }

        String fingerprint = AllMusic.gson.toJson(updated);
        if (fingerprint.equals(documentFingerprint)) {
            rejectedFingerprint = "";
            return false;
        }

        install(updated, true);
        QQMusicSupport.logInfo("QQ Music config hot-reloaded, credential="
                + snapshot.credential.isComplete()
                + ", qualities=" + snapshot.qualities
                + ", searchLimit=" + snapshot.searchLimit
                + ", qrLogin=" + snapshot.qrLogin);
        return true;
    }

    QQMusicCredential credential() {
        return snapshot.credential;
    }

    boolean qrLogin() {
        return snapshot.qrLogin;
    }

    int qrLoginTimeoutSeconds() {
        return snapshot.qrLoginTimeoutSeconds;
    }

    int qrLoginPollSeconds() {
        return snapshot.qrLoginPollSeconds;
    }

    String qualities() {
        return snapshot.qualities;
    }

    int searchLimit() {
        return snapshot.searchLimit;
    }

    int timeoutSeconds() {
        return snapshot.timeoutSeconds;
    }

    boolean autoRefresh() {
        return snapshot.autoRefresh;
    }

    long generation() {
        return generation;
    }

    synchronized void saveCredential(QQMusicCredential replacement) throws IOException {
        if (replacement == null || !replacement.isComplete()) {
            throw new IOException("Refusing to save an incomplete QQ Music credential");
        }
        reloadFromDisk(true);

        Snapshot current = snapshot;
        JsonObject updated = document.deepCopy();
        updated.add("credential", replacement.toJson());
        updated.addProperty("qrLogin", current.qrLogin);
        updated.addProperty("qrLoginTimeoutSeconds", current.qrLoginTimeoutSeconds);
        updated.addProperty("qrLoginPollSeconds", current.qrLoginPollSeconds);
        updated.addProperty("qualities", current.qualities);
        updated.addProperty("searchLimit", current.searchLimit);
        updated.addProperty("timeoutSeconds", current.timeoutSeconds);
        updated.addProperty("autoRefresh", current.autoRefresh);

        QQMusicSupport.writeTextAtomic(file, AllMusic.gson.toJson(updated));
        install(updated, true);
        observedFileState = readFileState(file);
    }

    File directory() {
        File parent = file.getAbsoluteFile().getParentFile();
        return parent == null ? new File(".") : parent;
    }

    List<String> qualityList() {
        String configured = snapshot.qualities;
        List<String> result = new ArrayList<>();
        for (String value : configured.split(",")) {
            if (!QQMusicSupport.isBlank(value)) {
                result.add(value.trim());
            }
        }
        if (result.isEmpty()) {
            result.add("m4a");
        }
        return result;
    }

    private void install(JsonObject updated, boolean incrementGeneration) {
        JsonObject installed = updated.deepCopy();
        Snapshot next = Snapshot.from(installed);
        document = installed;
        snapshot = next;
        documentFingerprint = AllMusic.gson.toJson(installed);
        rejectedFingerprint = "";
        if (incrementGeneration) {
            generation++;
        }
    }

    private void rejectOnce(String fingerprint, String message) {
        if (!fingerprint.equals(rejectedFingerprint)) {
            rejectedFingerprint = fingerprint;
            QQMusicSupport.logError(message);
        }
    }

    private static JsonObject readDocument(File file) throws IOException {
        JsonObject object;
        try {
            object = AllMusic.gson.fromJson(QQMusicSupport.readFile(file), JsonObject.class);
        } catch (RuntimeException e) {
            throw new IOException("invalid JSON", e);
        }
        if (object == null) {
            throw new IOException("configuration document is empty");
        }
        return object;
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

    private static FileState readFileState(File file) {
        try {
            if (file == null || !file.isFile()) {
                return FileState.MISSING;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    file.toPath(), BasicFileAttributes.class);
            return new FileState(
                    true,
                    attributes.lastModifiedTime().toMillis(),
                    attributes.size(),
                    String.valueOf(attributes.fileKey())
            );
        } catch (IOException ignored) {
            return FileState.MISSING;
        }
    }

    private static final class FileState {
        static final FileState MISSING = new FileState(false, 0L, 0L, "");

        final boolean exists;
        final long lastModified;
        final long size;
        final String fileKey;

        private FileState(boolean exists, long lastModified, long size, String fileKey) {
            this.exists = exists;
            this.lastModified = lastModified;
            this.size = size;
            this.fileKey = fileKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FileState)) {
                return false;
            }
            FileState state = (FileState) other;
            return exists == state.exists
                    && lastModified == state.lastModified
                    && size == state.size
                    && fileKey.equals(state.fileKey);
        }

        @Override
        public int hashCode() {
            int result = exists ? 1 : 0;
            result = 31 * result + (int) (lastModified ^ (lastModified >>> 32));
            result = 31 * result + (int) (size ^ (size >>> 32));
            result = 31 * result + fileKey.hashCode();
            return result;
        }
    }

    private static final class Snapshot {
        final QQMusicCredential credential;
        final boolean qrLogin;
        final int qrLoginTimeoutSeconds;
        final int qrLoginPollSeconds;
        final String qualities;
        final int searchLimit;
        final int timeoutSeconds;
        final boolean autoRefresh;

        private Snapshot(QQMusicCredential credential, boolean qrLogin,
                         int qrLoginTimeoutSeconds, int qrLoginPollSeconds,
                         String qualities, int searchLimit, int timeoutSeconds,
                         boolean autoRefresh) {
            this.credential = credential;
            this.qrLogin = qrLogin;
            this.qrLoginTimeoutSeconds = qrLoginTimeoutSeconds;
            this.qrLoginPollSeconds = qrLoginPollSeconds;
            this.qualities = qualities;
            this.searchLimit = searchLimit;
            this.timeoutSeconds = timeoutSeconds;
            this.autoRefresh = autoRefresh;
        }

        static Snapshot from(JsonObject document) {
            return new Snapshot(
                    QQMusicCredential.fromConfig(document),
                    QQMusicSupport.bool(document, "qrLogin", true),
                    clamp(QQMusicSupport.integer(document, "qrLoginTimeoutSeconds", 120), 30, 300),
                    clamp(QQMusicSupport.integer(document, "qrLoginPollSeconds", 2), 1, 10),
                    QQMusicSupport.firstNonBlank(QQMusicSupport.string(document, "qualities"), "m4a,128,320"),
                    clamp(QQMusicSupport.integer(document, "searchLimit", 20), 1, 50),
                    clamp(QQMusicSupport.integer(document, "timeoutSeconds", 20), 5, 120),
                    QQMusicSupport.bool(document, "autoRefresh", true)
            );
        }
    }
}
