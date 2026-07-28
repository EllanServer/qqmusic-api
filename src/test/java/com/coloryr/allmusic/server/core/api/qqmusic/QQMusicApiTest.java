package com.coloryr.allmusic.server.core.api.qqmusic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QQMusicApiTest {
    @Test
    void parsesSongMidInputsUsedByAllMusic() {
        assertEquals("001Bbywq2gicae", QQMusicApi.normalizeSongMid("001Bbywq2gicae"));
        assertEquals("001Bbywq2gicae", QQMusicApi.normalizeSongMid("song:001Bbywq2gicae"));
        assertEquals("001Bbywq2gicae", QQMusicApi.normalizeSongMid(
                "https://y.qq.com/n/ryqq/songDetail/001Bbywq2gicae"));
        assertEquals("001Bbywq2gicae", QQMusicApi.normalizeSongMid(
                "https://example.test/play?songmid=001Bbywq2gicae&from=allmusic"));
    }

    @Test
    void rejectsNonSongIds() {
        assertNull(QQMusicApi.normalizeSongMid(""));
        assertNull(QQMusicApi.normalizeSongMid("123"));
        assertNull(QQMusicApi.normalizeSongMid("https://y.qq.com/n/ryqq/playlist/123456"));
    }

    @Test
    void resolvesAllMusicSearchArguments() {
        assertEquals("周杰伦 晴天", QQMusicApi.resolveSearchQuery(
                new String[]{"周杰伦", "晴天"}, true));
        assertEquals("周杰伦 晴天", QQMusicApi.resolveSearchQuery(
                new String[]{"qqmusic", "周杰伦", "晴天"}, false));
        assertNull(QQMusicApi.resolveSearchQuery(new String[]{"qqmusic"}, false));
    }
}
