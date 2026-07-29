package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.server.core.AllMusic;
import com.coloryr.allmusic.server.core.music.LyricSave;
import com.coloryr.allmusic.server.core.objs.SearchMusicObj;
import com.coloryr.allmusic.server.core.objs.music.SearchPageObj;
import com.coloryr.allmusic.server.core.objs.music.SongInfoObj;
import com.coloryr.allmusic.server.core.side.IAllMusicLogger;
import net.kyori.adventure.text.Component;

import java.io.File;

public final class QQMusicSmokeRunner {
    private QQMusicSmokeRunner() {
    }

    public static void main(String[] args) {
        File config = new File(args[0]).getAbsoluteFile();
        String query = args.length > 1 ? args[1] : "周杰伦";
        System.setProperty("qqmusic.config", config.getAbsolutePath());
        AllMusic.log = new IAllMusicLogger() {
            @Override
            public void data(String message) {
                System.out.println(message);
            }

            @Override
            public void data(Component component) {
                System.out.println(component);
            }
        };

        QQMusicApi api = new QQMusicApi();
        SearchPageObj search = api.search(new String[]{query}, true);
        if (search == null) {
            throw new IllegalStateException("QQ Music search returned no results");
        }
        SearchMusicObj first = search.getRes(0);
        SongInfoObj song = api.getMusic(first.id, "smoke-test", false);
        if (song == null || song.isNull()) {
            throw new IllegalStateException("QQ Music song detail lookup failed");
        }
        String playUrl = api.getPlayUrl(first.id);
        LyricSave lyrics = api.getLyric(first.id);
        boolean lyricTimelineReady = false;
        boolean ktvTimelineReady = false;
        long scanLength = Math.max(song.getLength(), 10 * 60 * 1000L);
        if (lyrics != null) {
            for (long time = 0; time <= scanLength; time += 10) {
                lyricTimelineReady |= lyrics.lyricGetNext(time);
                ktvTimelineReady |= lyrics.ktvGetNext(time);
                if (lyricTimelineReady && ktvTimelineReady) {
                    break;
                }
            }
        }
        System.out.println("QQMUSIC_SEARCH_READY=true");
        System.out.println("QQMUSIC_DETAIL_READY=true");
        System.out.println("QQMUSIC_PLAY_URL_READY=" + !QQMusicSupport.isBlank(playUrl));
        System.out.println("QQMUSIC_LYRIC_READY=" + (lyrics != null && lyrics.isHaveLyric()));
        System.out.println("QQMUSIC_LYRIC_TIMELINE_READY=" + lyricTimelineReady);
        System.out.println("QQMUSIC_KTV_TIMELINE_READY=" + ktvTimelineReady);
    }
}
