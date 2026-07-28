package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.server.core.AllMusic;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QQMusicSupport {
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Safari/537.36";

    private QQMusicSupport() {
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static String firstNonBlank(String... values) {
        if (values != null) {
            for (String value : values) {
                if (!isBlank(value)) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    static String normalizeUin(String value) {
        String normalized = trim(value);
        if (normalized.startsWith("o") || normalized.startsWith("O")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.matches("[0-9]+")) {
            return "0";
        }
        normalized = normalized.replaceFirst("^0+(?!$)", "");
        return "0".equals(normalized) ? "0" : normalized;
    }

    static int hash33(String value, int initial) {
        int hash = initial;
        String input = value == null ? "" : value;
        for (int i = 0; i < input.length(); i++) {
            hash += (hash << 5) + input.charAt(i);
        }
        return hash & 0x7fffffff;
    }

    static String encode(String value) throws IOException {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
    }

    static String queryParameter(String url, String key) throws IOException {
        if (isBlank(url) || isBlank(key)) {
            return "";
        }
        Matcher matcher = Pattern.compile("(?:[?&])" + Pattern.quote(key) + "=([^&#]*)").matcher(url);
        if (!matcher.find()) {
            return "";
        }
        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8.name());
    }

    static JsonObject object(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement value = parent.get(key);
        return value == null || value.isJsonNull() || !value.isJsonObject() ? null : value.getAsJsonObject();
    }

    static JsonArray array(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return null;
        }
        JsonElement value = parent.get(key);
        return value == null || value.isJsonNull() || !value.isJsonArray() ? null : value.getAsJsonArray();
    }

    static String string(JsonObject parent, String key) {
        if (parent == null || !parent.has(key)) {
            return "";
        }
        try {
            JsonElement value = parent.get(key);
            return value == null || value.isJsonNull() ? "" : value.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    static int integer(JsonObject parent, String key, int fallback) {
        if (parent == null || !parent.has(key)) {
            return fallback;
        }
        try {
            JsonElement value = parent.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static long longValue(JsonObject parent, String key, long fallback) {
        if (parent == null || !parent.has(key)) {
            return fallback;
        }
        try {
            JsonElement value = parent.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }

    }

    static boolean bool(JsonObject parent, String key, boolean fallback) {
        if (parent == null || !parent.has(key)) {
            return fallback;
        }
        try {
            JsonElement value = parent.get(key);
            return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    static String singerNames(JsonArray singers) {
        if (singers == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonElement singer : singers) {
            if (singer != null && singer.isJsonObject()) {
                String name = firstNonBlank(
                        string(singer.getAsJsonObject(), "name"),
                        string(singer.getAsJsonObject(), "singerName")
                );
                if (!isBlank(name)) {
                    names.add(name);
                }
            }
        }
        return String.join("/", names);
    }

    static byte[] readBytes(InputStream stream) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        }
    }

    static String readFile(File file) throws IOException {
        return new String(readBytes(new FileInputStream(file)), StandardCharsets.UTF_8);
    }

    static void writeBytes(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
        }
    }

    static void writeText(File file, String data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(data);
        }
    }

    static void writeTextAtomic(File file, String data) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Path target = file.toPath();
        Path temporary = Files.createTempFile(parent == null ? target.toAbsolutePath().getParent() : parent.toPath(),
                file.getName(), ".tmp");
        try {
            Files.write(temporary, data.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            setOwnerOnly(file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void setOwnerOnly(File file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
            );
            Files.setPosixFilePermissions(file.toPath(), permissions);
        } catch (Exception ignored) {
        }
    }

    static String limit(String value, int maxLength) {
        String text = value == null ? "" : value;
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    static void logInfo(String message) {
        log("<light_purple>[AllMusic]<yellow>" + message);
    }

    static void logError(String message) {
        log("<light_purple>[AllMusic]<red>" + message);
    }

    private static void log(String message) {
        try {
            if (AllMusic.log != null) {
                AllMusic.log.data(message);
            }
        } catch (Throwable ignored) {
        }
    }

    static String lower(String value) {
        return trim(value).toLowerCase(Locale.ROOT);
    }
}
