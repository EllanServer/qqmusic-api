# QQMusic API for AllMusic

An AllMusic external API provider for QQ Music search, metadata lookup, and direct m4a/mp3 playback URLs.

## Contents

- [`docs/qq-music-allmusic-api.md`](docs/qq-music-allmusic-api.md): feasibility notes, cookie configuration, quality options, and open-source references.
- [`src/main/java/com/coloryr/allmusic/server/core/api/qqmusic/QQMusicApi.java`](src/main/java/com/coloryr/allmusic/server/core/api/qqmusic/QQMusicApi.java): an `IMusicApi` implementation for QQ Music search, metadata lookup, and vkey playback URL resolving.

## Build

```bash
./gradlew build
```

Copy `build/libs/qqmusic-api-1.0-SNAPSHOT.jar` into AllMusic's `allmusic_server/api` directory.

## Configuration

On first load the provider creates `qqmusic.json` next to the API jar:

```json
{
  "uin": "0",
  "qqmusicKey": "",
  "cookie": "",
  "qrLogin": true,
  "qrLoginTimeoutSeconds": 120,
  "qrLoginPollSeconds": 2,
  "qualities": "m4a,128,320",
  "searchLimit": 20,
  "timeoutSeconds": 20
}
```

For better coverage, paste a QQ Music web cookie from a logged-in browser. The provider can derive `uin` and `qqmusicKey` from the cookie when those values are present.

When `qrLogin` is enabled and no login cookie is configured, the provider creates `qqmusic-login.html` and `qqmusic-login.png` next to `qqmusic.json`. Open the HTML file, scan the QR code with QQ, and the provider will save the QQ Music cookie back to `qqmusic.json` after login succeeds.

## Playback

`getPlayUrl` asks QQ Music's vkey endpoint for m4a/mp3 URLs and hands the CDN URL to AllMusic's existing player path. QQ Music may still return no playable URL for VIP, region-locked, or otherwise restricted tracks unless the configured account has access.
