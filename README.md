# QQ Music API for AllMusic

A pure Java AllMusic external API provider for QQ Music. The provider implements
`IMusicApi`, registers as `qqmusic`, and supplies song search, metadata, covers,
direct QQ Music CDN playback URLs, and synchronized QQ Music lyrics.

Version 2.x is a clean protocol rewrite. It does not run Python, open a sidecar
port, or accept the 1.x top-level cookie configuration.

## Build

```bash
./gradlew clean build
```

The output is `build/libs/qqmusic-api-2.1.0.jar`. Copy it into the server's
AllMusic external API directory, which is `plugins/allmusic/api/` on the target
installation. AllMusic discovers API jars at startup, so loading a newly copied
jar requires an AllMusic/server restart.

## Configuration

On first load, `qqmusic.json` is created next to the API jar:

```json
{
  "credential": {
    "openid": "",
    "refresh_token": "",
    "access_token": "",
    "expired_at": 0,
    "musicid": "0",
    "musickey": "",
    "unionid": "",
    "str_musicid": "0",
    "refresh_key": "",
    "musickeyCreateTime": 0,
    "keyExpiresIn": 0,
    "loginType": 0
  },
  "qrLogin": true,
  "qrLoginTimeoutSeconds": 120,
  "qrLoginPollSeconds": 2,
  "qualities": "m4a,128,320",
  "searchLimit": 20,
  "timeoutSeconds": 20,
  "autoRefresh": true
}
```

When no complete credential exists, the provider writes `qqmusic-login.png`
and `qqmusic-login.html` beside the config. Scan the code with QQ and confirm
the login. The config is updated only after QQ Music returns both a non-zero
`musicid` and a `musickey`.

To request another QR code without restarting, create an empty file named
`qqmusic-relogin` beside `qqmusic.json`. The watcher consumes the trigger and
regenerates the login files.

The credential file contains account secrets. Do not publish it or include it
in support archives.

## Playback

The provider tries `m4a`, 128 kbps MP3, and 320 kbps MP3 in configured order.
QQ Music still enforces account, VIP, copyright, and region restrictions. A
restricted track can therefore search successfully while returning no playable
URL for the configured account.

## Lyrics

The provider requests encrypted QRC from `GetPlayLyricInfo` and decodes it in
Java. Word-timed QRC is converted to AllMusic KTV progress; ordinary timed LRC
is retained as line lyrics, and translation is matched to the closest original
line. Romanization is used only when no translation is available.

See [`docs/qq-music-allmusic-api.md`](docs/qq-music-allmusic-api.md) for protocol
and maintenance notes.
