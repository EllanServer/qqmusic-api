package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.codec.KtvLyricObj;
import com.coloryr.allmusic.server.core.music.LyricSave;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QQMusicLyricParserTest {
    private static final String ORIGINAL_QRC =
            "c90db2e3f6940a43538b45865eb6753863c981f936a71a093b450246d48b65f0b"
                    + "ca0d019ca6bbd156e3ebffa6fd6edbca8844d4a71cc99957d1e4828a5d387d33"
                    + "34ba1a902b1b105ea9e737bd1ca2f5862f78ca9c540375d0f3e038dfdeecd6e7"
                    + "da089e3812bfcdc66a4cc0c1b9cdfa4a20f956ffd8bccc00848137285a5169f";
    private static final String TRANSLATION_QRC =
            "c90db2e3f6940a43538b45865eb6753863c981f936a71a093b450246d48b65f07"
                    + "51f5f66b996f97925247995300031f3993b60a5e9be7d9416bab1a6642ad41368"
                    + "ed976708148d62369449be2c5fec4b4e01db4ec81006c2";
    private static final String LINE_LRC =
            "32dabb4c5e9846fa6f3a98a26231e62faa2f92527b4b66399aceac954ccbb7bc"
                    + "e2d996df595836a57589df8de3f46727";
    private static final String TRANSLATED_LINE_LRC =
            "32dabb4c5e9846fa0535e3b1fd7a225a20f0d934d7cd1a72f52c1007d8389d36"
                    + "ab474efeebc6bf604f03cb30821f9d2e";

    @Test
    void decodesQrcLyricsTranslationAndWordProgress() throws Exception {
        JsonObject response = new JsonObject();
        response.addProperty("lyric", ORIGINAL_QRC);
        response.addProperty("trans", TRANSLATION_QRC);

        LyricSave save = QQMusicLyricParser.parse(response);

        assertTrue(save.isHaveLyric());
        assertTrue(save.lyricGetNext(1000));
        assertEquals("你好 世界", save.getNow().lyric);
        assertEquals("Hello", save.getNow().tlyric);
        assertTrue(save.ktvGetNext(1000));
        KtvLyricObj ktv = save.getKtvNow();
        assertEquals(1000, ktv.start);
        assertEquals(1600, ktv.time);
        assertEquals(5, ktv.items.size());
        assertEquals("你", ktv.items.get(0).key);
        assertEquals(1000, ktv.items.get(0).start);
        assertEquals(400, ktv.items.get(0).time);
        assertEquals("界", ktv.items.get(4).key);
        assertEquals("你好 世界".length(), ktv.charCount);
    }

    @Test
    void fallsBackToLineTimedLrcWhenQrcIsUnavailable() throws Exception {
        JsonObject response = new JsonObject();
        response.addProperty("lyric", LINE_LRC);
        response.addProperty("trans", TRANSLATED_LINE_LRC);

        LyricSave save = QQMusicLyricParser.parse(response);

        assertTrue(save.isHaveLyric());
        assertTrue(save.lyricGetNext(1230));
        assertEquals("第一行", save.getNow().lyric);
        assertEquals("First line", save.getNow().tlyric);
        assertFalse(save.ktvGetNext(1230));
        assertTrue(save.lyricGetNext(5500));
        assertEquals("第二行", save.getNow().lyric);
    }

    @Test
    void returnsAnEmptyTimelineForAnEmptyResponse() throws Exception {
        LyricSave save = QQMusicLyricParser.parse(new JsonObject());

        assertFalse(save.isHaveLyric());
    }

}
