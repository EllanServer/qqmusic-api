package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.codec.KtvLyricObj;
import com.coloryr.allmusic.server.core.music.LyricSave;
import com.coloryr.allmusic.server.core.objs.music.LyricItemObj;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/** Decodes QQ Music cloud QRC and adapts it to AllMusic's lyric timeline. */
final class QQMusicLyricParser {
    private static final long DEFAULT_LINE_DURATION_MILLIS = 4500L;
    private static final long TRANSLATION_TOLERANCE_MILLIS = 1200L;

    private static final Pattern HEX = Pattern.compile("^[0-9A-Fa-f]+$");
    private static final Pattern QRC_CONTENT = Pattern.compile(
            "LyricContent=\"(.*?)\"", Pattern.DOTALL);
    private static final Pattern QRC_LINE = Pattern.compile(
            "^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern QRC_WORD = Pattern.compile(
            "(?:\\[\\d+,\\d+])?((?:(?!\\(\\d+,\\d+\\)).)*?)\\((\\d+),(\\d+)\\)");
    private static final Pattern LRC_TIMESTAMP = Pattern.compile(
            "\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,6}))?]");
    private static final Pattern LRC_OFFSET = Pattern.compile(
            "(?im)^\\[offset:([+-]?\\d+)]$");

    private QQMusicLyricParser() {
    }

    static LyricSave parse(JsonObject response) throws IOException {
        String originalText = decodeField(response, "lyric", false);
        String translationText = decodeField(response, "trans", true);
        if (QQMusicSupport.isBlank(translationText)) {
            translationText = decodeField(response, "roma", true);
        }

        List<LyricLine> original = parseText(originalText);
        List<LyricLine> translations = parseText(translationText);
        NavigableMap<Long, String> translationByTime = new TreeMap<>();
        for (LyricLine line : translations) {
            if (!QQMusicSupport.isBlank(line.text)) {
                translationByTime.put(normalizeTime(line.start), line.text);
            }
        }

        Map<Long, LyricItemObj> lyricTimeline = new HashMap<>();
        Map<Long, KtvLyricObj> ktvTimeline = new HashMap<>();
        for (LyricLine line : original) {
            if (QQMusicSupport.isBlank(line.text)) {
                continue;
            }
            long start = normalizeTime(line.start);
            lyricTimeline.put(start, new LyricItemObj(
                    line.text,
                    translationAt(translationByTime, start),
                    start
            ));

            KtvLyricObj ktv = toKtv(line, start);
            if (ktv != null) {
                ktvTimeline.put(start, ktv);
            }
        }

        LyricSave save = new LyricSave();
        save.setLyric(lyricTimeline);
        save.setKlyric(ktvTimeline);
        save.setHaveLyric(!lyricTimeline.isEmpty());
        return save;
    }

    static String decodeCloudQrc(String value) throws IOException {
        if (QQMusicSupport.isBlank(value)) {
            return "";
        }
        String encoded = value.trim();
        if ((encoded.length() & 1) != 0 || !HEX.matcher(encoded).matches()) {
            return encoded;
        }

        byte[] encrypted = new byte[encoded.length() / 2];
        for (int index = 0; index < encrypted.length; index++) {
            int high = Character.digit(encoded.charAt(index * 2), 16);
            int low = Character.digit(encoded.charAt(index * 2 + 1), 16);
            encrypted[index] = (byte) ((high << 4) | low);
        }
        if ((encrypted.length & 7) != 0) {
            throw new IOException("QQ Music returned a truncated QRC payload");
        }

        try {
            byte[] compressed = QQMusicQrcCipher.decrypt(encrypted);
            try (InflaterInputStream inflater = new InflaterInputStream(
                    new ByteArrayInputStream(compressed));
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = inflater.read(buffer)) >= 0) {
                    output.write(buffer, 0, length);
                }
                String decoded = new String(output.toByteArray(), StandardCharsets.UTF_8);
                return decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
            }
        } catch (RuntimeException e) {
            throw new IOException("Could not decrypt QQ Music QRC", e);
        }
    }

    private static String decodeField(JsonObject response, String name, boolean optional)
            throws IOException {
        String value = QQMusicSupport.string(response, name);
        if (QQMusicSupport.isBlank(value)) {
            return "";
        }
        try {
            return decodeCloudQrc(value);
        } catch (IOException e) {
            if (!optional) {
                throw e;
            }
            QQMusicSupport.logInfo("QQ Music ignored invalid " + name + " lyric data: "
                    + e.getMessage());
            return "";
        }
    }

    private static List<LyricLine> parseText(String text) {
        if (QQMusicSupport.isBlank(text)) {
            return Collections.emptyList();
        }
        Matcher qrc = QRC_CONTENT.matcher(text);
        List<LyricLine> lines = qrc.find()
                ? parseQrcContent(unescapeXml(qrc.group(1)))
                : parseLrc(text);
        if (lines.isEmpty() && !text.contains("[")) {
            lines = parsePlainText(text);
        }
        Collections.sort(lines, new Comparator<LyricLine>() {
            @Override
            public int compare(LyricLine left, LyricLine right) {
                return Long.compare(left.start, right.start);
            }
        });
        inferMissingEnds(lines);
        return lines;
    }

    private static List<LyricLine> parseQrcContent(String content) {
        List<LyricLine> lines = new ArrayList<>();
        for (String rawLine : content.split("\\r?\\n")) {
            Matcher lineMatcher = QRC_LINE.matcher(rawLine.trim());
            if (!lineMatcher.matches()) {
                continue;
            }
            long start = parseLong(lineMatcher.group(1));
            long duration = parseLong(lineMatcher.group(2));
            String lineContent = lineMatcher.group(3);
            List<LyricWord> words = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            Matcher wordMatcher = QRC_WORD.matcher(lineContent);
            while (wordMatcher.find()) {
                String wordText = wordMatcher.group(1);
                if (wordText == null || wordText.isEmpty() || "\r".equals(wordText)) {
                    continue;
                }
                long wordStart = parseLong(wordMatcher.group(2));
                long wordDuration = parseLong(wordMatcher.group(3));
                text.append(wordText);
                words.add(new LyricWord(wordStart, wordDuration, wordText));
            }
            if (words.isEmpty()) {
                text.append(lineContent);
            }
            lines.add(new LyricLine(start, start + duration, text.toString(), words));
        }
        return lines;
    }

    private static List<LyricLine> parseLrc(String text) {
        long offset = 0;
        Matcher offsetMatcher = LRC_OFFSET.matcher(text);
        if (offsetMatcher.find()) {
            offset = parseLong(offsetMatcher.group(1));
        }

        List<LyricLine> lines = new ArrayList<>();
        for (String rawLine : text.split("\\r?\\n")) {
            Matcher matcher = LRC_TIMESTAMP.matcher(rawLine);
            List<Long> starts = new ArrayList<>();
            int contentStart = -1;
            while (matcher.find()) {
                starts.add(Math.max(0L, lrcTime(matcher) + offset));
                contentStart = matcher.end();
            }
            if (starts.isEmpty()) {
                continue;
            }
            String content = contentStart >= 0 ? rawLine.substring(contentStart).trim() : "";
            for (Long start : starts) {
                lines.add(new LyricLine(start, -1L, content,
                        Collections.<LyricWord>emptyList()));
            }
        }
        return lines;
    }

    private static List<LyricLine> parsePlainText(String text) {
        List<LyricLine> lines = new ArrayList<>();
        long start = 0;
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (!line.isEmpty()) {
                lines.add(new LyricLine(start, start + DEFAULT_LINE_DURATION_MILLIS,
                        line, Collections.<LyricWord>emptyList()));
                start += DEFAULT_LINE_DURATION_MILLIS;
            }
        }
        return lines;
    }

    private static void inferMissingEnds(List<LyricLine> lines) {
        for (int index = 0; index < lines.size(); index++) {
            LyricLine line = lines.get(index);
            if (line.end > line.start) {
                continue;
            }
            long nextStart = index + 1 < lines.size()
                    ? lines.get(index + 1).start
                    : line.start + DEFAULT_LINE_DURATION_MILLIS;
            line.end = Math.max(line.start + 1, nextStart);
        }
    }

    private static KtvLyricObj toKtv(LyricLine line, long normalizedStart) {
        if (line.words.isEmpty()) {
            return null;
        }
        KtvLyricObj result = new KtvLyricObj();
        result.start = normalizedStart;
        long lastWordEnd = normalizedStart;
        for (LyricWord word : line.words) {
            // Spaces are timed QRC tokens too and occupy visible lyric width.
            if (word.text == null || word.text.isEmpty() || word.duration <= 0) {
                continue;
            }
            KtvLyricObj.KtvItem item = new KtvLyricObj.KtvItem();
            item.start = word.start;
            item.time = word.duration;
            item.key = word.text;
            result.items.add(item);
            result.charCount += word.text.length();
            lastWordEnd = Math.max(lastWordEnd, word.start + word.duration);
        }
        if (result.items.isEmpty() || result.charCount <= 0) {
            return null;
        }
        result.time = Math.max(1L,
                Math.max(line.end, lastWordEnd) - normalizedStart);
        return result;
    }

    private static String translationAt(NavigableMap<Long, String> translations, long time) {
        String exact = translations.get(time);
        if (exact != null) {
            return exact;
        }
        Map.Entry<Long, String> floor = translations.floorEntry(time);
        Map.Entry<Long, String> ceiling = translations.ceilingEntry(time);
        Map.Entry<Long, String> closest;
        if (floor == null) {
            closest = ceiling;
        } else if (ceiling == null) {
            closest = floor;
        } else {
            closest = time - floor.getKey() <= ceiling.getKey() - time ? floor : ceiling;
        }
        return closest != null && Math.abs(closest.getKey() - time) <= TRANSLATION_TOLERANCE_MILLIS
                ? closest.getValue()
                : "";
    }

    private static long normalizeTime(long time) {
        long positive = Math.max(0L, time);
        return positive - positive % 10L;
    }

    private static long lrcTime(Matcher matcher) {
        long minutes = parseLong(matcher.group(1));
        long seconds = parseLong(matcher.group(2));
        String fraction = matcher.group(3);
        long millis = 0;
        if (fraction != null && !fraction.isEmpty()) {
            if (fraction.length() == 1) {
                millis = parseLong(fraction) * 100L;
            } else if (fraction.length() == 2) {
                millis = parseLong(fraction) * 10L;
            } else {
                millis = parseLong(fraction.substring(0, 3));
            }
        }
        return minutes * 60000L + seconds * 1000L + millis;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String unescapeXml(String value) {
        return value.replace("&#10;", "\n")
                .replace("&#13;", "\r")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static final class LyricLine {
        final long start;
        long end;
        final String text;
        final List<LyricWord> words;

        LyricLine(long start, long end, String text, List<LyricWord> words) {
            this.start = Math.max(0L, start);
            this.end = end;
            this.text = text == null ? "" : text;
            this.words = words;
        }
    }

    private static final class LyricWord {
        final long start;
        final long duration;
        final String text;

        LyricWord(long start, long duration, String text) {
            this.start = Math.max(0L, start);
            this.duration = Math.max(0L, duration);
            this.text = text;
        }
    }
}
