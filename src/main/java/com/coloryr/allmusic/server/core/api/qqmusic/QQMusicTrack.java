package com.coloryr.allmusic.server.core.api.qqmusic;

import com.google.gson.JsonObject;

final class QQMusicTrack {
    final String songMid;
    final String mediaMid;
    final String title;
    final String singer;
    final String album;
    final String albumMid;
    final long durationMillis;
    final int songType;

    private QQMusicTrack(String songMid, String mediaMid, String title, String singer,
                         String album, String albumMid, long durationMillis, int songType) {
        this.songMid = QQMusicSupport.trim(songMid);
        this.mediaMid = QQMusicSupport.trim(mediaMid);
        this.title = cleanText(title);
        this.singer = cleanText(singer);
        this.album = cleanText(album);
        this.albumMid = QQMusicSupport.trim(albumMid);
        this.durationMillis = Math.max(0L, durationMillis);
        this.songType = Math.max(0, songType);
    }

    static QQMusicTrack fromJson(JsonObject value) {
        if (value == null) {
            return null;
        }
        JsonObject wrapped = QQMusicSupport.object(value, "track_info");
        if (wrapped == null) {
            wrapped = QQMusicSupport.object(value, "track");
        }
        JsonObject track = wrapped == null ? value : wrapped;
        JsonObject file = QQMusicSupport.object(track, "file");
        JsonObject album = QQMusicSupport.object(track, "album");

        String mid = QQMusicSupport.firstNonBlank(
                QQMusicSupport.string(track, "mid"),
                QQMusicSupport.string(track, "songmid"),
                QQMusicSupport.string(track, "songMid")
        );
        if (QQMusicSupport.isBlank(mid)) {
            return null;
        }

        String albumName = QQMusicSupport.firstNonBlank(
                QQMusicSupport.string(album, "name"),
                QQMusicSupport.string(album, "title"),
                QQMusicSupport.string(track, "albumname"),
                QQMusicSupport.string(track, "albumName")
        );
        String albumMid = QQMusicSupport.firstNonBlank(
                QQMusicSupport.string(album, "mid"),
                QQMusicSupport.string(album, "pmid"),
                QQMusicSupport.string(track, "albummid"),
                QQMusicSupport.string(track, "albumMid")
        );
        long seconds = Math.max(
                QQMusicSupport.longValue(track, "interval", 0L),
                QQMusicSupport.longValue(track, "duration", 0L)
        );
        return new QQMusicTrack(
                mid,
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(file, "media_mid"),
                        QQMusicSupport.string(track, "strMediaMid"),
                        QQMusicSupport.string(track, "media_mid")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(track, "name"),
                        QQMusicSupport.string(track, "title"),
                        QQMusicSupport.string(track, "songname"),
                        mid
                ),
                QQMusicSupport.singerNames(QQMusicSupport.array(track, "singer")),
                albumName,
                albumMid,
                seconds * 1000L,
                QQMusicSupport.integer(track, "type", 0)
        );
    }

    String coverUrl() {
        return QQMusicSupport.isBlank(albumMid)
                ? ""
                : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
    }

    private static String cleanText(String value) {
        return QQMusicSupport.trim(value).replaceAll("<[^>]+>", "");
    }

    enum Quality {
        M4A("m4a", "C400", ".m4a"),
        MP3_128("128", "M500", ".mp3"),
        MP3_320("320", "M800", ".mp3");

        final String configName;
        final String prefix;
        final String extension;

        Quality(String configName, String prefix, String extension) {
            this.configName = configName;
            this.prefix = prefix;
            this.extension = extension;
        }

        static Quality fromConfig(String value) {
            String normalized = QQMusicSupport.lower(value);
            if ("m4a".equals(normalized) || "aac".equals(normalized) || "96".equals(normalized)) {
                return M4A;
            }
            if ("128".equals(normalized) || "mp3".equals(normalized)) {
                return MP3_128;
            }
            if ("320".equals(normalized)) {
                return MP3_320;
            }
            return null;
        }

        String filename(QQMusicTrack track) {
            if (!QQMusicSupport.isBlank(track.mediaMid)) {
                return prefix + track.mediaMid + extension;
            }
            return prefix + track.songMid + track.songMid + extension;
        }
    }
}
