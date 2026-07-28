package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.server.core.AllMusic;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QQMusicTrackTest {
    @Test
    void parsesCurrentSearchAndDetailSongShape() {
        String json = "{"
                + "\"mid\":\"001Bbywq2gicae\","
                + "\"title\":\"<em>搁浅</em>\","
                + "\"interval\":240,\"type\":1,"
                + "\"singer\":[{\"name\":\"周杰伦\"},{\"name\":\"合唱者\"}],"
                + "\"album\":{\"mid\":\"003DFRzD192KKD\",\"name\":\"七里香\"},"
                + "\"file\":{\"media_mid\":\"004UlK9x0jeuow\"}"
                + "}";

        QQMusicTrack track = QQMusicTrack.fromJson(AllMusic.gson.fromJson(json, JsonObject.class));

        assertNotNull(track);
        assertEquals("001Bbywq2gicae", track.songMid);
        assertEquals("搁浅", track.title);
        assertEquals("周杰伦/合唱者", track.singer);
        assertEquals("七里香", track.album);
        assertEquals(240000L, track.durationMillis);
        assertEquals("C400004UlK9x0jeuow.m4a", QQMusicTrack.Quality.M4A.filename(track));
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R300x300M000003DFRzD192KKD.jpg",
                track.coverUrl());
    }

    @Test
    void duplicatesSongMidWhenMediaMidIsMissing() {
        JsonObject value = AllMusic.gson.fromJson(
                "{\"mid\":\"001Bbywq2gicae\",\"name\":\"Song\"}", JsonObject.class);
        QQMusicTrack track = QQMusicTrack.fromJson(value);

        assertNotNull(track);
        assertEquals("M500001Bbywq2gicae001Bbywq2gicae.mp3",
                QQMusicTrack.Quality.MP3_128.filename(track));
    }
}
