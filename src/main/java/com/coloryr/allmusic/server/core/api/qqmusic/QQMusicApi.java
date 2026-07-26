package com.coloryr.allmusic.server.core.api.qqmusic;

import com.coloryr.allmusic.server.core.AllMusic;
import com.coloryr.allmusic.server.core.IMusicApi;
import com.coloryr.allmusic.server.core.music.LyricSave;
import com.coloryr.allmusic.server.core.objs.SearchMusicObj;
import com.coloryr.allmusic.server.core.objs.music.SearchPageObj;
import com.coloryr.allmusic.server.core.objs.music.SongInfoObj;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QQ Music provider for AllMusic.
 *
 * <p>Search, metadata and vkey playback URLs use QQ Music's web endpoints. Some
 * songs require account cookies or VIP rights; in those cases getPlayUrl returns
 * null so AllMusic marks the song as not playable.</p>
 */
public class QQMusicApi implements IMusicApi {
    public static final String API_ID = "qqmusic";

    private static final String CONFIG_FILE_NAME = "qqmusic.json";
    private static final String RELOGIN_TRIGGER_FILE_NAME = "qqmusic-relogin";
    private static final long RELOGIN_TRIGGER_POLL_MILLIS = 2000L;
    private static final String MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp";
    private static final String REFERER = "https://y.qq.com/";
    private static final String QQLOGIN_APP_ID = "716027609";
    private static final String QQLOGIN_DAID = "383";
    private static final String QQLOGIN_THIRD_AID = "100497308";
    private static final String QQLOGIN_U1 = "https://graph.qq.com/oauth2.0/login_jump";
    private static final String QQLOGIN_REDIRECT = "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https%3A%2F%2Fy.qq.com%2F";
    private static final int DEFAULT_SEARCH_LIMIT = 20;
    private static final int DEFAULT_TIMEOUT_SECONDS = 20;

    private static final Pattern MID = Pattern.compile("^[A-Za-z0-9]{8,32}$");
    private static final Pattern SONGMID_QUERY = Pattern.compile("[?&]songmid=([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SONGMID_PATH = Pattern.compile("/songDetail/([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYLIST_PATH = Pattern.compile("/playlist/([0-9]+)", Pattern.CASE_INSENSITIVE);

    private final QQMusicConfig config;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicBoolean loginBusy = new AtomicBoolean(false);

    public QQMusicApi() {
        this(QQMusicConfig.load());
    }

    public QQMusicApi(QQMusicConfig config) {
        this.config = config;
        logInfo("QQ Music API loaded, qualities=" + config.qualities
                + ", searchLimit=" + config.searchLimit
                + ", cookie=" + (!isBlank(config.cookie))
                + ", qrLogin=" + config.qrLogin);
        startQrLoginIfNeeded("startup");
        startReloginWatcher();
    }

    @Override
    public String getId() {
        return API_ID;
    }

    @Override
    public SongInfoObj getMusic(String id, String player, boolean isList) {
        final String songMid = normalizeSongMid(id);
        if (songMid == null) {
            return null;
        }

        return withBusy(new QQOperation<SongInfoObj>() {
            @Override
            public SongInfoObj run() throws Exception {
                QQTrack track = fetchTrack(songMid);
                if (track == null) {
                    return null;
                }

                return new SongInfoObj(
                        defaultText(track.singer, "QQ Music"),
                        defaultText(track.title, track.songMid),
                        track.songMid,
                        "",
                        player,
                        defaultText(track.album, "QQ Music"),
                        isList,
                        track.durationMillis,
                        track.imageUrl(),
                        false,
                        null,
                        API_ID
                );
            }
        });
    }

    @Override
    public SearchPageObj search(String[] args, boolean isDefault) {
        final String query = resolveSearchQuery(args, isDefault);
        if (isBlank(query)) {
            return null;
        }

        return withBusy(new QQOperation<SearchPageObj>() {
            @Override
            public SearchPageObj run() throws Exception {
                List<QQTrack> tracks = searchTracks(query);
                if (tracks.isEmpty()) {
                    return null;
                }

                List<SearchMusicObj> results = new ArrayList<>();
                for (QQTrack track : tracks) {
                    results.add(new SearchMusicObj(track.songMid, track.title, track.singer, track.album));
                }
                return new SearchPageObj(results, Math.max(1, (results.size() + 9) / 10), API_ID);
            }
        });
    }

    @Override
    public void setList(String id, Object sender) {
        // QQ playlist import differs across web endpoints and is intentionally
        // left out of the first provider pass.
    }

    @Override
    public LyricSave getLyric(String id) {
        return new LyricSave();
    }

    @Override
    public String getPlayUrl(String id) {
        final String songMid = normalizeSongMid(id);
        if (songMid == null) {
            return null;
        }

        return withBusy(new QQOperation<String>() {
            @Override
            public String run() throws Exception {
                QQTrack track = fetchTrack(songMid);
                if (track == null) {
                    return null;
                }

                for (String quality : config.qualityList()) {
                    String url = fetchPlayUrl(track, quality);
                    if (!isBlank(url)) {
                        return url;
                    }
                }
                return null;
            }
        });
    }

    @Override
    public boolean isBusy() {
        return busy.get();
    }

    @Override
    public String getMusicId(String arg) {
        QQId id = parseId(arg);
        return id == null ? null : id.value;
    }

    @Override
    public boolean checkId(String id) {
        QQId parsed = parseId(id);
        return parsed != null && "song".equals(parsed.type);
    }

    private List<QQTrack> searchTracks(String query) throws IOException {
        String url = SEARCH_URL
                + "?format=json&n=" + config.searchLimit
                + "&p=1&cr=1&g_tk=5381&t=0&w=" + encode(query);
        JsonObject root = getJson(url);
        JsonObject data = getObject(root, "data");
        JsonObject song = getObject(data, "song");
        JsonArray list = getArray(song, "list");
        List<QQTrack> tracks = new ArrayList<>();
        if (list == null) {
            return tracks;
        }

        for (JsonElement element : list) {
            if (element != null && element.isJsonObject()) {
                QQTrack track = QQTrack.fromSearchJson(element.getAsJsonObject());
                if (track != null && !isBlank(track.songMid)) {
                    tracks.add(track);
                }
            }
        }
        return tracks;
    }

    private QQTrack fetchTrack(String songMid) throws IOException {
        JsonObject request = new JsonObject();
        JsonObject songInfo = new JsonObject();
        songInfo.addProperty("method", "get_song_detail_yqq");
        songInfo.addProperty("module", "music.pf_song_detail_svr");
        JsonObject param = new JsonObject();
        param.addProperty("song_mid", songMid);
        songInfo.add("param", param);
        request.add("songinfo", songInfo);

        JsonObject root = getJson(MUSICU_URL + "?data=" + encode(AllMusic.gson.toJson(request)));
        JsonObject data = getObject(getObject(root, "songinfo"), "data");
        JsonObject trackInfo = getObject(data, "track_info");
        return QQTrack.fromTrackInfo(trackInfo);
    }

    private String fetchPlayUrl(QQTrack track, String quality) throws IOException {
        QQQuality file = QQQuality.from(quality, track);
        if (file == null) {
            return null;
        }

        String guid = String.valueOf(1000000000L + Math.abs(new Random().nextLong() % 8999999999L));
        JsonObject rootReq = new JsonObject();

        JsonObject req0 = new JsonObject();
        req0.addProperty("module", "vkey.GetVkeyServer");
        req0.addProperty("method", "CgiGetVkey");
        JsonObject param = new JsonObject();
        JsonArray filenames = new JsonArray();
        filenames.add(file.filename);
        param.add("filename", filenames);
        param.addProperty("guid", guid);
        JsonArray songMids = new JsonArray();
        songMids.add(track.songMid);
        param.add("songmid", songMids);
        JsonArray songTypes = new JsonArray();
        songTypes.add(0);
        param.add("songtype", songTypes);
        param.addProperty("uin", config.uin);
        param.addProperty("loginflag", 1);
        param.addProperty("platform", "20");
        req0.add("param", param);
        rootReq.add("req_0", req0);

        JsonObject comm = new JsonObject();
        comm.addProperty("uin", config.uin);
        comm.addProperty("format", "json");
        comm.addProperty("ct", 19);
        comm.addProperty("cv", 0);
        if (!isBlank(config.qqmusicKey)) {
            comm.addProperty("authst", config.qqmusicKey);
        }
        rootReq.add("comm", comm);

        String url = MUSICU_URL + "?-=getplaysongvkey&g_tk=5381&loginUin=" + encode(config.uin)
                + "&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0"
                + "&platform=yqq.json&needNewCode=0&data=" + encode(AllMusic.gson.toJson(rootReq));

        JsonObject root = getJson(url);
        JsonObject data = getObject(getObject(root, "req_0"), "data");
        JsonArray midurlinfo = getArray(data, "midurlinfo");
        if (midurlinfo == null || midurlinfo.size() == 0 || !midurlinfo.get(0).isJsonObject()) {
            return null;
        }

        JsonObject info = midurlinfo.get(0).getAsJsonObject();
        String purl = getString(info, "purl");
        if (isBlank(purl)) {
            logInfo("QQ Music purl empty for " + track.songMid + " quality=" + quality
                    + " result=" + getString(info, "result"));
            return null;
        }

        JsonArray sip = getArray(data, "sip");
        String domain = firstUsableDomain(sip);
        return isBlank(domain) ? null : domain + purl;
    }

    private QQId parseId(String arg) {
        if (isBlank(arg)) {
            return null;
        }

        String value = arg.trim();
        if (value.startsWith("song:")) {
            value = value.substring("song:".length());
        }

        Matcher songQuery = SONGMID_QUERY.matcher(value);
        if (songQuery.find()) {
            return new QQId("song", songQuery.group(1));
        }
        Matcher songPath = SONGMID_PATH.matcher(value);
        if (songPath.find()) {
            return new QQId("song", songPath.group(1));
        }
        Matcher playlistPath = PLAYLIST_PATH.matcher(value);
        if (playlistPath.find()) {
            return new QQId("playlist", playlistPath.group(1));
        }
        if (MID.matcher(value).matches()) {
            return new QQId("song", value);
        }
        return null;
    }

    private String normalizeSongMid(String id) {
        QQId parsed = parseId(id);
        return parsed == null || !"song".equals(parsed.type) ? null : parsed.value;
    }

    private String resolveSearchQuery(String[] args, boolean isDefault) {
        if (args == null || args.length == 0) {
            return null;
        }

        int start = isDefault ? 0 : 1;
        if (start >= args.length) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (!isBlank(args[i])) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(args[i].trim());
            }
        }
        return builder.toString();
    }

    private <T> T withBusy(QQOperation<T> operation) {
        if (!busy.compareAndSet(false, true)) {
            return null;
        }

        try {
            return operation.run();
        } catch (Exception e) {
            logError("QQ Music request failed: " + e.getMessage());
            return null;
        } finally {
            busy.set(false);
        }
    }

    private JsonObject getJson(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(config.timeoutSeconds * 1000);
            connection.setReadTimeout(config.timeoutSeconds * 1000);
            connection.setRequestProperty("Referer", REFERER);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setRequestProperty("Accept", "application/json,text/plain,*/*");
            if (!isBlank(config.cookie)) {
                connection.setRequestProperty("Cookie", config.cookie);
            }

            int status = connection.getResponseCode();
            String body = readBody(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " " + limit(body, 200));
            }
            JsonObject object = AllMusic.gson.fromJson(body, JsonObject.class);
            return object == null ? new JsonObject() : object;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void startQrLoginIfNeeded(String reason) {
        startQrLogin(reason, false);
    }

    /**
     * Starts the QR login flow on a background thread.
     *
     * @param force when true, starts even if a login cookie is already
     *              configured (used to renew an expired login).
     */
    private void startQrLogin(String reason, boolean force) {
        if (!config.qrLogin) {
            return;
        }
        if (!force && hasLoginCookie()) {
            return;
        }
        if (!loginBusy.compareAndSet(false, true)) {
            logInfo("QQ Music QR login already in progress, ignored reason=" + reason);
            return;
        }

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    performQrLogin();
                } catch (Exception e) {
                    logInfo("QQ Music QR login stopped: " + e.getMessage());
                } finally {
                    loginBusy.set(false);
                }
            }
        }, "AllMusic-QQMusic-QRLogin");
        thread.setDaemon(true);
        thread.start();
        logInfo("QQ Music QR login started, reason=" + reason);
    }

    /**
     * Watches for a trigger file next to qqmusic.json. Creating the file
     * (for example with {@code touch qqmusic-relogin}) re-initiates the QR
     * login flow without restarting the server, even when a (possibly
     * expired) login cookie is already configured. The trigger file is
     * deleted before the new flow starts.
     */
    private void startReloginWatcher() {
        if (!config.qrLogin) {
            return;
        }

        final File trigger = new File(config.configDirectory(), RELOGIN_TRIGGER_FILE_NAME);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        if (trigger.isFile() && trigger.delete()) {
                            startQrLogin("trigger-file", true);
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(RELOGIN_TRIGGER_POLL_MILLIS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "AllMusic-QQMusic-QRTrigger");
        thread.setDaemon(true);
        thread.start();
        logInfo("QQ Music QR relogin trigger enabled, create this file to re-initiate: "
                + trigger.getAbsolutePath());
    }

    private boolean hasLoginCookie() {
        return !isBlank(config.cookie) && (!isBlank(config.qqmusicKey) || !"0".equals(config.uin));
    }

    private void performQrLogin() throws IOException, InterruptedException {
        QQQrCode qrCode = requestQrCode();
        if (isBlank(qrCode.qrsig)) {
            throw new IOException("qrsig is empty");
        }

        writeQrLoginFiles(qrCode.image);
        Map<String, String> cookieJar = parseCookieString(config.cookie);
        cookieJar.put("qrsig", qrCode.qrsig);

        long endAt = System.currentTimeMillis() + config.qrLoginTimeoutSeconds * 1000L;
        String lastStatus = "";
        while (System.currentTimeMillis() < endAt) {
            QQLoginStatus status = checkQrLogin(qrCode.qrsig);
            mergeSetCookies(cookieJar, status.cookies);

            if ("0".equals(status.ret)) {
                if (isBlank(status.jumpUrl)) {
                    throw new IOException("success without jump url");
                }
                followLoginRedirects(status.jumpUrl, cookieJar);
                synchronized (config) {
                    cookieJar.remove("qrsig");
                    config.cookie = cookieMapToString(cookieJar);
                    QQMusicConfig.deriveCookieFields(config);
                    config.save();
                }
                logInfo("QQ Music QR login success, uin=" + config.uin
                        + ", key=" + (!isBlank(config.qqmusicKey)));
                return;
            }

            if ("65".equals(status.ret)) {
                throw new IOException("QR code expired");
            }
            if ("68".equals(status.ret)) {
                throw new IOException("QR login refused");
            }

            if (!status.ret.equals(lastStatus)) {
                if ("67".equals(status.ret)) {
                    logInfo("QQ Music QR scanned, confirm login on your phone");
                } else if ("66".equals(status.ret)) {
                    logInfo("QQ Music QR waiting for scan");
                } else {
                    logInfo("QQ Music QR status ret=" + status.ret + " msg=" + status.message);
                }
                lastStatus = status.ret;
            }

            Thread.sleep(Math.max(1, config.qrLoginPollSeconds) * 1000L);
        }

        throw new IOException("QR login timeout");
    }

    private QQQrCode requestQrCode() throws IOException {
        String url = "https://ssl.ptlogin2.qq.com/ptqrshow"
                + "?appid=" + QQLOGIN_APP_ID
                + "&e=2&l=M&s=3&d=72&v=4"
                + "&t=" + Math.random()
                + "&daid=" + QQLOGIN_DAID
                + "&pt_3rd_aid=" + QQLOGIN_THIRD_AID
                + "&u1=" + encode(QQLOGIN_U1);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(config.timeoutSeconds * 1000);
            connection.setReadTimeout(config.timeoutSeconds * 1000);
            connection.setRequestProperty("Referer", qqLoginReferer());
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            int status = connection.getResponseCode();
            byte[] body = readBytes(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status);
            }
            Map<String, String> cookies = new LinkedHashMap<>();
            mergeSetCookies(cookies, connection.getHeaderFields());
            return new QQQrCode(QQMusicConfig.cookieValue(cookieMapToString(cookies), "qrsig"), body);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private QQLoginStatus checkQrLogin(String qrsig) throws IOException {
        String url = "https://ssl.ptlogin2.qq.com/ptqrlogin"
                + "?ptqrtoken=" + qqLoginHash(qrsig)
                + "&from_ui=1"
                + "&aid=" + QQLOGIN_APP_ID
                + "&daid=" + QQLOGIN_DAID
                + "&pt_3rd_aid=" + QQLOGIN_THIRD_AID
                + "&u1=" + encode(QQLOGIN_U1);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(config.timeoutSeconds * 1000);
            connection.setReadTimeout(config.timeoutSeconds * 1000);
            connection.setRequestProperty("Cookie", "qrsig=" + qrsig);
            connection.setRequestProperty("Referer", qqLoginReferer());
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            int status = connection.getResponseCode();
            String body = readBody(status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream());
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + " " + limit(body, 200));
            }
            QQLoginStatus loginStatus = parsePtuiCallback(body);
            loginStatus.cookies = connection.getHeaderFields();
            return loginStatus;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void followLoginRedirects(String startUrl, Map<String, String> cookieJar) throws IOException {
        String current = startUrl;
        for (int i = 0; i < 10 && !isBlank(current); i++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(config.timeoutSeconds * 1000);
                connection.setReadTimeout(config.timeoutSeconds * 1000);
                connection.setRequestProperty("Cookie", cookieMapToString(cookieJar));
                connection.setRequestProperty("Referer", REFERER);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                int status = connection.getResponseCode();
                mergeSetCookies(cookieJar, connection.getHeaderFields());
                String location = connection.getHeaderField("Location");
                if (status >= 300 && status < 400 && !isBlank(location)) {
                    current = resolveLocation(current, location);
                    continue;
                }
                readBody(status >= 200 && status < 400
                        ? connection.getInputStream()
                        : connection.getErrorStream());
                return;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    private void writeQrLoginFiles(byte[] image) throws IOException {
        File base = config.configDirectory();
        if (base != null) {
            base.mkdirs();
        }
        File png = new File(base == null ? new File(".") : base, "qqmusic-login.png");
        File html = new File(base == null ? new File(".") : base, "qqmusic-login.html");
        writeBytes(png, image);

        String img = Base64.getEncoder().encodeToString(image);
        String body = "<!doctype html><meta charset=\"utf-8\"><title>QQ Music Login</title>"
                + "<body style=\"font-family:sans-serif;text-align:center;padding:32px\">"
                + "<h1>QQ Music Login</h1>"
                + "<p>Use QQ to scan this QR code, then confirm login on your phone.</p>"
                + "<img alt=\"QQ Music login QR\" width=\"288\" height=\"288\" "
                + "src=\"data:image/png;base64," + img + "\">"
                + "<p>This page can be closed after the server logs a successful login.</p>"
                + "</body>";
        writeFile(html, body);
        logInfo("QQ Music QR login file: " + html.getAbsolutePath());
    }

    private static String qqLoginReferer() throws IOException {
        return "https://xui.ptlogin2.qq.com/cgi-bin/xlogin"
                + "?appid=" + QQLOGIN_APP_ID
                + "&style=20"
                + "&s_url=" + encode(QQLOGIN_REDIRECT)
                + "&maskOpacity=60"
                + "&daid=" + QQLOGIN_DAID
                + "&target=self";
    }

    private static int qqLoginHash(String value) {
        int hash = 0;
        for (int i = 0; i < value.length(); i++) {
            hash += (hash << 5) + value.charAt(i);
        }
        return hash & 0x7fffffff;
    }

    private static QQLoginStatus parsePtuiCallback(String body) throws IOException {
        Matcher matcher = Pattern.compile("ptuiCB\\((.*)\\)").matcher(body);
        if (!matcher.find()) {
            throw new IOException("unexpected login response: " + limit(body, 120));
        }
        List<String> values = splitCallbackArgs(matcher.group(1));
        if (values.isEmpty()) {
            throw new IOException("empty login response");
        }
        return new QQLoginStatus(
                values.get(0),
                values.size() > 2 ? values.get(2) : "",
                values.size() > 4 ? values.get(4) : ""
        );
    }

    private static List<String> splitCallbackArgs(String input) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
                continue;
            }
            if (c == ',' && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        values.add(current.toString().trim());
        return values;
    }

    private static String firstUsableDomain(JsonArray sip) {
        if (sip == null || sip.size() == 0) {
            return "";
        }

        String first = "";
        for (JsonElement element : sip) {
            String value = element == null || element.isJsonNull() ? "" : element.getAsString();
            if (isBlank(value)) {
                continue;
            }
            if (isBlank(first)) {
                first = value;
            }
            if (!value.startsWith("http://ws")) {
                return value;
            }
        }
        return first;
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static JsonObject getObject(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }

    private static JsonArray getArray(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonArray()) {
            return null;
        }
        return object.getAsJsonArray(key);
    }

    private static String getString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return object.get(key).getAsBoolean();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long secondsToMillis(JsonObject object, String key) {
        return getInt(object, key, 0) * 1000L;
    }

    private static String singerNames(JsonArray singers) {
        if (singers == null || singers.size() == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (JsonElement element : singers) {
            if (element != null && element.isJsonObject()) {
                String name = getString(element.getAsJsonObject(), "name");
                if (!isBlank(name)) {
                    if (builder.length() > 0) {
                        builder.append('/');
                    }
                    builder.append(name);
                }
            }
        }
        return builder.toString();
    }

    private static String defaultText(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private static Map<String, String> parseCookieString(String cookie) {
        Map<String, String> values = new LinkedHashMap<>();
        if (isBlank(cookie)) {
            return values;
        }

        String[] parts = cookie.split(";");
        for (String part : parts) {
            String item = trimToEmpty(part);
            int index = item.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = item.substring(0, index).trim();
            String value = item.substring(index + 1).trim();
            if (isBlank(key) || isCookieAttribute(key)) {
                continue;
            }
            values.put(key, value);
        }
        return values;
    }

    private static String cookieMapToString(Map<String, String> cookies) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (isBlank(entry.getKey()) || isBlank(entry.getValue()) || isCookieAttribute(entry.getKey())) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    private static void mergeSetCookies(Map<String, String> cookieJar, Map<String, List<String>> headers) {
        if (headers == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            for (String value : entry.getValue()) {
                Map<String, String> parsed = parseCookieString(firstCookiePart(value));
                cookieJar.putAll(parsed);
            }
        }
    }

    private static String firstCookiePart(String value) {
        if (value == null) {
            return "";
        }
        int index = value.indexOf(';');
        return index < 0 ? value : value.substring(0, index);
    }

    private static boolean isCookieAttribute(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return "path".equals(normalized)
                || "domain".equals(normalized)
                || "expires".equals(normalized)
                || "max-age".equals(normalized)
                || "samesite".equals(normalized)
                || "secure".equals(normalized)
                || "httponly".equals(normalized);
    }

    private static String resolveLocation(String currentUrl, String location) throws IOException {
        URL current = new URL(currentUrl);
        return new URL(current, location).toString();
    }

    private static String readBody(InputStream stream) throws IOException {
        byte[] bytes = readBytes(stream);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream stream) throws IOException {
        if (stream == null) {
            return new byte[0];
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return output.toByteArray();
        } finally {
            stream.close();
        }
    }

    private static String readFile(File file) throws IOException {
        return readBody(new FileInputStream(file));
    }

    private static void writeFile(File file, String data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(data);
        }
    }

    private static void writeBytes(File file, byte[] data) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(data);
        }
    }

    private static void logInfo(String message) {
        log("<light_purple>[AllMusic]<yellow>" + message);
    }

    private static void logError(String message) {
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

    private interface QQOperation<T> {
        T run() throws Exception;
    }

    private static final class QQQrCode {
        private final String qrsig;
        private final byte[] image;

        private QQQrCode(String qrsig, byte[] image) {
            this.qrsig = qrsig;
            this.image = image;
        }
    }

    private static final class QQLoginStatus {
        private final String ret;
        private final String jumpUrl;
        private final String message;
        private Map<String, List<String>> cookies;

        private QQLoginStatus(String ret, String jumpUrl, String message) {
            this.ret = defaultText(ret, "");
            this.jumpUrl = defaultText(jumpUrl, "");
            this.message = defaultText(message, "");
        }
    }

    private static final class QQTrack {
        private final String songMid;
        private final String mediaMid;
        private final String title;
        private final String singer;
        private final String album;
        private final String albumMid;
        private final long durationMillis;

        private QQTrack(String songMid, String mediaMid, String title, String singer, String album,
                        String albumMid, long durationMillis) {
            this.songMid = songMid;
            this.mediaMid = mediaMid;
            this.title = title;
            this.singer = singer;
            this.album = album;
            this.albumMid = albumMid;
            this.durationMillis = durationMillis;
        }

        private static QQTrack fromSearchJson(JsonObject object) {
            JsonObject file = getObject(object, "file");
            JsonObject album = getObject(object, "album");
            return new QQTrack(
                    firstNonBlank(getString(object, "songmid"), getString(object, "mid")),
                    firstNonBlank(getString(object, "strMediaMid"), getString(file, "media_mid")),
                    firstNonBlank(getString(object, "songname"), getString(object, "title")),
                    singerNames(getArray(object, "singer")),
                    firstNonBlank(getString(object, "albumname"), getString(album, "title")),
                    firstNonBlank(getString(object, "albummid"), getString(album, "mid")),
                    secondsToMillis(object, "interval")
            );
        }

        private static QQTrack fromTrackInfo(JsonObject object) {
            if (object == null) {
                return null;
            }

            JsonObject file = getObject(object, "file");
            JsonObject album = getObject(object, "album");
            return new QQTrack(
                    firstNonBlank(getString(object, "mid"), getString(object, "songmid")),
                    getString(file, "media_mid"),
                    firstNonBlank(getString(object, "title"), getString(object, "name")),
                    singerNames(getArray(object, "singer")),
                    firstNonBlank(getString(album, "title"), getString(album, "name")),
                    firstNonBlank(getString(album, "mid"), getString(album, "pmid")),
                    secondsToMillis(object, "interval")
            );
        }

        private String imageUrl() {
            if (isBlank(albumMid)) {
                return "";
            }
            return "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg";
        }
    }

    private static final class QQQuality {
        private final String filename;

        private QQQuality(String filename) {
            this.filename = filename;
        }

        private static QQQuality from(String quality, QQTrack track) {
            String mediaMid = firstNonBlank(track.mediaMid, track.songMid);
            String normalized = trimToEmpty(quality).toLowerCase(Locale.ROOT);
            if ("m4a".equals(normalized)) {
                return new QQQuality("C400" + mediaMid + ".m4a");
            }
            if ("128".equals(normalized) || "mp3".equals(normalized)) {
                return new QQQuality("M500" + mediaMid + ".mp3");
            }
            if ("320".equals(normalized)) {
                return new QQQuality("M800" + mediaMid + ".mp3");
            }
            return null;
        }
    }

    private static final class QQId {
        private final String type;
        private final String value;

        private QQId(String type, String value) {
            this.type = type;
            this.value = value;
        }
    }

    public static final class QQMusicConfig {
        private String uin = "0";
        private String qqmusicKey = "";
        private String cookie = "";
        private boolean qrLogin = true;
        private int qrLoginTimeoutSeconds = 120;
        private int qrLoginPollSeconds = 2;
        private String qualities = "m4a,128,320";
        private int searchLimit = DEFAULT_SEARCH_LIMIT;
        private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        private File configFile;

        private static QQMusicConfig load() {
            QQMusicConfig config = new QQMusicConfig();
            File file = findConfigFile();
            if (file != null && file.isFile()) {
                config.configFile = file;
                applyFile(config, file);
            } else {
                File template = defaultConfigFile();
                if (template != null) {
                    config.configFile = template;
                    createTemplate(template);
                }
            }
            applyOverrides(config);
            config.uin = defaultText(config.uin, "0");
            config.searchLimit = Math.max(1, Math.min(50, config.searchLimit));
            config.timeoutSeconds = Math.max(5, Math.min(120, config.timeoutSeconds));
            config.qrLoginTimeoutSeconds = Math.max(30, Math.min(300, config.qrLoginTimeoutSeconds));
            config.qrLoginPollSeconds = Math.max(1, Math.min(10, config.qrLoginPollSeconds));
            deriveCookieFields(config);
            return config;
        }

        private List<String> qualityList() {
            List<String> list = new ArrayList<>();
            String[] values = qualities.split(",");
            for (String value : values) {
                if (!isBlank(value)) {
                    list.add(value.trim());
                }
            }
            if (list.isEmpty()) {
                list.add("m4a");
            }
            return list;
        }

        private static void applyFile(QQMusicConfig config, File file) {
            try {
                JsonObject object = AllMusic.gson.fromJson(readFile(file), JsonObject.class);
                if (object == null) {
                    return;
                }
                config.uin = firstNonBlank(getString(object, "uin"), config.uin);
                config.qqmusicKey = firstNonBlank(getString(object, "qqmusicKey"), config.qqmusicKey);
                config.cookie = firstNonBlank(getString(object, "cookie"), config.cookie);
                config.qrLogin = getBoolean(object, "qrLogin", config.qrLogin);
                config.qrLoginTimeoutSeconds = getInt(object, "qrLoginTimeoutSeconds", config.qrLoginTimeoutSeconds);
                config.qrLoginPollSeconds = getInt(object, "qrLoginPollSeconds", config.qrLoginPollSeconds);
                config.qualities = firstNonBlank(getString(object, "qualities"), config.qualities);
                config.searchLimit = getInt(object, "searchLimit", config.searchLimit);
                config.timeoutSeconds = getInt(object, "timeoutSeconds", config.timeoutSeconds);
            } catch (Exception e) {
                logError("QQ Music config read failed: " + e.getMessage());
            }
        }

        private static void applyOverrides(QQMusicConfig config) {
            config.uin = firstNonBlank(safeProperty("qqmusic.uin"), safeEnv("QQMUSIC_UIN"), config.uin);
            config.qqmusicKey = firstNonBlank(safeProperty("qqmusic.qqmusicKey"), safeEnv("QQMUSIC_KEY"), config.qqmusicKey);
            config.cookie = firstNonBlank(safeProperty("qqmusic.cookie"), safeEnv("QQMUSIC_COOKIE"), config.cookie);
            config.qrLogin = firstBoolean(config.qrLogin, safeProperty("qqmusic.qrLogin"), safeEnv("QQMUSIC_QR_LOGIN"));
            config.qrLoginTimeoutSeconds = firstInt(config.qrLoginTimeoutSeconds,
                    safeProperty("qqmusic.qrLoginTimeoutSeconds"), safeEnv("QQMUSIC_QR_LOGIN_TIMEOUT_SECONDS"));
            config.qrLoginPollSeconds = firstInt(config.qrLoginPollSeconds,
                    safeProperty("qqmusic.qrLoginPollSeconds"), safeEnv("QQMUSIC_QR_LOGIN_POLL_SECONDS"));
            config.qualities = firstNonBlank(safeProperty("qqmusic.qualities"), safeEnv("QQMUSIC_QUALITIES"), config.qualities);
        }

        private static void deriveCookieFields(QQMusicConfig config) {
            if (isBlank(config.cookie)) {
                return;
            }
            if ("0".equals(config.uin)) {
                String parsed = firstNonBlank(cookieValue(config.cookie, "uin"), cookieValue(config.cookie, "p_uin"));
                if (!isBlank(parsed)) {
                    config.uin = parsed.startsWith("o") ? parsed.substring(1) : parsed;
                }
            }
            if (isBlank(config.qqmusicKey)) {
                config.qqmusicKey = firstNonBlank(
                        cookieValue(config.cookie, "qqmusic_key"),
                        cookieValue(config.cookie, "qm_keyst"),
                        cookieValue(config.cookie, "music_key")
                );
            }
        }

        private static String cookieValue(String cookie, String key) {
            String[] parts = cookie.split(";");
            for (String part : parts) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && key.equalsIgnoreCase(pair[0].trim())) {
                    return pair[1].trim();
                }
            }
            return "";
        }

        private static File findConfigFile() {
            List<File> files = candidateConfigFiles();
            for (File file : files) {
                if (file != null && file.isFile()) {
                    return file;
                }
            }
            return null;
        }

        private static List<File> candidateConfigFiles() {
            List<File> files = new ArrayList<>();
            addFile(files, safeProperty("qqmusic.config"));
            addFile(files, safeEnv("QQMUSIC_CONFIG"));
            File jarDir = jarDirectory();
            if (jarDir != null) {
                files.add(new File(jarDir, CONFIG_FILE_NAME));
            }
            files.add(new File(CONFIG_FILE_NAME));
            files.add(new File("allmusic_server", CONFIG_FILE_NAME));
            files.add(new File(new File("allmusic_server", "api"), CONFIG_FILE_NAME));
            return files;
        }

        private static File defaultConfigFile() {
            File jarDir = jarDirectory();
            return jarDir == null ? new File(CONFIG_FILE_NAME) : new File(jarDir, CONFIG_FILE_NAME);
        }

        private static File jarDirectory() {
            try {
                CodeSource source = QQMusicApi.class.getProtectionDomain().getCodeSource();
                if (source == null || source.getLocation() == null) {
                    return null;
                }
                File file = new File(source.getLocation().toURI());
                return file.isFile() ? file.getParentFile() : file;
            } catch (Exception e) {
                return null;
            }
        }

        private static void addFile(List<File> files, String path) {
            if (!isBlank(path)) {
                files.add(new File(path));
            }
        }

        private static void createTemplate(File file) {
            if (file.exists()) {
                return;
            }
            try {
                JsonObject object = new JsonObject();
                object.addProperty("uin", "0");
                object.addProperty("qqmusicKey", "");
                object.addProperty("cookie", "");
                object.addProperty("qrLogin", true);
                object.addProperty("qrLoginTimeoutSeconds", 120);
                object.addProperty("qrLoginPollSeconds", 2);
                object.addProperty("qualities", "m4a,128,320");
                object.addProperty("searchLimit", DEFAULT_SEARCH_LIMIT);
                object.addProperty("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS);
                writeFile(file, AllMusic.gson.toJson(object));
            } catch (Exception e) {
                logError("QQ Music config template write failed: " + e.getMessage());
            }
        }

        private static String safeProperty(String name) {
            try {
                return System.getProperty(name);
            } catch (SecurityException e) {
                return "";
            }
        }

        private static String safeEnv(String name) {
            try {
                return System.getenv(name);
            } catch (SecurityException e) {
                return "";
            }
        }

        private File configDirectory() {
            if (configFile == null) {
                return new File(".");
            }
            File parent = configFile.getParentFile();
            return parent == null ? new File(".") : parent;
        }

        private void save() {
            if (configFile == null) {
                configFile = defaultConfigFile();
            }
            try {
                JsonObject object = new JsonObject();
                object.addProperty("uin", uin);
                object.addProperty("qqmusicKey", qqmusicKey);
                object.addProperty("cookie", cookie);
                object.addProperty("qrLogin", qrLogin);
                object.addProperty("qrLoginTimeoutSeconds", qrLoginTimeoutSeconds);
                object.addProperty("qrLoginPollSeconds", qrLoginPollSeconds);
                object.addProperty("qualities", qualities);
                object.addProperty("searchLimit", searchLimit);
                object.addProperty("timeoutSeconds", timeoutSeconds);
                writeFile(configFile, AllMusic.gson.toJson(object));
            } catch (Exception e) {
                logError("QQ Music config save failed: " + e.getMessage());
            }
        }

        private static int firstInt(int fallback, String... values) {
            if (values == null) {
                return fallback;
            }
            for (String value : values) {
                if (isBlank(value)) {
                    continue;
                }
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            return fallback;
        }

        private static boolean firstBoolean(boolean fallback, String... values) {
            if (values == null) {
                return fallback;
            }
            for (String value : values) {
                if (isBlank(value)) {
                    continue;
                }
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)) {
                    return true;
                }
                if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)) {
                    return false;
                }
            }
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
