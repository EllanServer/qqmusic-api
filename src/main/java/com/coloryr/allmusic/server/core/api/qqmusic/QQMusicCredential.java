package com.coloryr.allmusic.server.core.api.qqmusic;

import com.google.gson.JsonObject;

final class QQMusicCredential {
    static final QQMusicCredential EMPTY = new QQMusicCredential(
            "", "", "", 0L, "0", "", "", "0", "",
            0L, 0L, 0
    );

    final String openId;
    final String refreshToken;
    final String accessToken;
    final long expiredAt;
    final String musicId;
    final String musicKey;
    final String unionId;
    final String stringMusicId;
    final String refreshKey;
    final long musicKeyCreateTime;
    final long keyExpiresIn;
    final int loginType;

    QQMusicCredential(String openId, String refreshToken, String accessToken, long expiredAt,
                      String musicId, String musicKey, String unionId, String stringMusicId,
                      String refreshKey, long musicKeyCreateTime, long keyExpiresIn, int loginType) {
        this.openId = QQMusicSupport.trim(openId);
        this.refreshToken = QQMusicSupport.trim(refreshToken);
        this.accessToken = QQMusicSupport.trim(accessToken);
        this.expiredAt = Math.max(0L, expiredAt);
        this.musicId = QQMusicSupport.normalizeUin(musicId);
        this.musicKey = QQMusicSupport.trim(musicKey);
        this.unionId = QQMusicSupport.trim(unionId);
        this.stringMusicId = QQMusicSupport.normalizeUin(
                QQMusicSupport.firstNonBlank(stringMusicId, musicId)
        );
        this.refreshKey = QQMusicSupport.trim(refreshKey);
        this.musicKeyCreateTime = Math.max(0L, musicKeyCreateTime);
        this.keyExpiresIn = Math.max(0L, keyExpiresIn);
        this.loginType = loginType > 0 ? loginType : inferLoginType(this.musicKey);
    }

    static QQMusicCredential fromConfig(JsonObject root) {
        JsonObject source = QQMusicSupport.object(root, "credential");
        if (source == null) {
            return EMPTY;
        }
        String id = QQMusicSupport.firstNonBlank(
                QQMusicSupport.string(source, "musicid"),
                QQMusicSupport.string(source, "str_musicid")
        );
        String key = QQMusicSupport.firstNonBlank(
                QQMusicSupport.string(source, "musickey"),
                QQMusicSupport.string(source, "musicKey")
        );
        return new QQMusicCredential(
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "openid"),
                        QQMusicSupport.string(source, "openId")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "refresh_token"),
                        QQMusicSupport.string(source, "refreshToken")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "access_token"),
                        QQMusicSupport.string(source, "accessToken")
                ),
                firstLong(source, "expired_at", "expiredAt"),
                id,
                key,
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "unionid"),
                        QQMusicSupport.string(source, "unionId")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "str_musicid"),
                        QQMusicSupport.string(source, "stringMusicId"),
                        id
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(source, "refresh_key"),
                        QQMusicSupport.string(source, "refreshKey")
                ),
                firstLong(source, "musickeyCreateTime", "musicKeyCreateTime"),
                firstLong(source, "keyExpiresIn", "key_expires_in"),
                firstInt(source, "loginType", "login_type")
        );
    }

    static QQMusicCredential fromLoginData(JsonObject data) {
        if (data == null) {
            return EMPTY;
        }
        return new QQMusicCredential(
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "openid"),
                        QQMusicSupport.string(data, "openId")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "refresh_token"),
                        QQMusicSupport.string(data, "refreshToken")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "access_token"),
                        QQMusicSupport.string(data, "accessToken")
                ),
                firstLong(data, "expired_at", "expiredAt"),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "musicid"),
                        QQMusicSupport.string(data, "musicId"),
                        QQMusicSupport.string(data, "str_musicid")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "musickey"),
                        QQMusicSupport.string(data, "musicKey")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "unionid"),
                        QQMusicSupport.string(data, "unionId")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "str_musicid"),
                        QQMusicSupport.string(data, "stringMusicId"),
                        QQMusicSupport.string(data, "musicid")
                ),
                QQMusicSupport.firstNonBlank(
                        QQMusicSupport.string(data, "refresh_key"),
                        QQMusicSupport.string(data, "refreshKey")
                ),
                firstLong(data, "musickeyCreateTime", "musicKeyCreateTime"),
                firstLong(data, "keyExpiresIn", "key_expires_in"),
                firstInt(data, "loginType", "login_type")
        );
    }

    boolean isComplete() {
        return !"0".equals(musicId) && !QQMusicSupport.isBlank(musicKey);
    }

    boolean canRefresh() {
        return isComplete() && (!QQMusicSupport.isBlank(refreshKey)
                || !QQMusicSupport.isBlank(refreshToken)
                || !QQMusicSupport.isBlank(accessToken));
    }

    boolean expiresSoon() {
        if (musicKeyCreateTime <= 0L || keyExpiresIn <= 0L) {
            return false;
        }
        long refreshAt = musicKeyCreateTime + keyExpiresIn - 86400L;
        return System.currentTimeMillis() / 1000L >= refreshAt;
    }

    String cookieHeader() {
        if (!isComplete()) {
            return "";
        }
        QQMusicHttp.CookieJar cookies = new QQMusicHttp.CookieJar();
        cookies.put("uin", stringMusicId);
        cookies.put("qqmusic_uin", stringMusicId);
        cookies.put("qm_keyst", musicKey);
        cookies.put("qqmusic_key", musicKey);
        return cookies.header();
    }

    JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("openid", openId);
        object.addProperty("refresh_token", refreshToken);
        object.addProperty("access_token", accessToken);
        object.addProperty("expired_at", expiredAt);
        object.addProperty("musicid", musicId);
        object.addProperty("musickey", musicKey);
        object.addProperty("unionid", unionId);
        object.addProperty("str_musicid", stringMusicId);
        object.addProperty("refresh_key", refreshKey);
        object.addProperty("musickeyCreateTime", musicKeyCreateTime);
        object.addProperty("keyExpiresIn", keyExpiresIn);
        object.addProperty("loginType", loginType);
        return object;
    }

    private static int inferLoginType(String key) {
        if (QQMusicSupport.isBlank(key)) {
            return 0;
        }
        return key.startsWith("W_X") ? 1 : 2;
    }

    private static long firstLong(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object != null && object.has(key)) {
                return QQMusicSupport.longValue(object, key, 0L);
            }
        }
        return 0L;
    }

    private static int firstInt(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object != null && object.has(key)) {
                return QQMusicSupport.integer(object, key, 0);
            }
        }
        return 0;
    }
}
