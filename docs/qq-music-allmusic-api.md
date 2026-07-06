# QQ Music API notes for AllMusic

This branch implements an AllMusic external API provider for QQ Music.

## Feasibility

QQ Music's web endpoints can return direct CDN URLs for playable tracks:

- Search: `https://c.y.qq.com/soso/fcgi-bin/client_search_cp`
- Song detail: `https://u.y.qq.com/cgi-bin/musicu.fcg` with `music.pf_song_detail_svr/get_song_detail_yqq`
- Playback URL: `https://u.y.qq.com/cgi-bin/musicu.fcg` with `vkey.GetVkeyServer/CgiGetVkey`

The playable URL is usually an m4a or mp3 URL on QQ Music CDN, which fits AllMusic's existing client decoders. Unlike YouTube, this does not require clients to access Google domains.

## Playback Limits

QQ Music still enforces copyright, region, VIP, and login restrictions. Anonymous requests can play some tracks, but many songs return an empty `purl` with result codes such as `104003`.

For better coverage, configure a QQ Music web cookie exported from a logged-in browser:

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

`cookie` is treated as a secret. If it contains `uin` and `qqmusic_key`, the provider derives those fields automatically.

## QR Login

The provider includes a QQ Music QR login flow modeled after the lightweight ptlogin QR pattern used by projects such as NapCat, but it does not depend on NapCat or an NTQQ client.

Set:

```json
{
  "qrLogin": true,
  "qrLoginTimeoutSeconds": 120,
  "qrLoginPollSeconds": 2
}
```

When no login cookie is configured, the provider requests a QR image from QQ's ptlogin service using QQ Music parameters:

- `appid=716027609`
- `daid=383`
- `pt_3rd_aid=100497308`
- redirect target `https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https%3A%2F%2Fy.qq.com%2F`

The QR image is written to `qqmusic-login.png`, and a browser-friendly `qqmusic-login.html` is written next to `qqmusic.json`. After the user scans and confirms login in QQ, the provider follows the login redirects, collects QQ Music cookies, derives `uin`/`qqmusicKey`, and saves the updated `qqmusic.json`.

This is a server-side shared login. All players use the same QQ account capability. VIP, copyright, and region checks still apply to that account.

## Quality

The provider tries qualities in order:

| Quality | File type |
| --- | --- |
| `m4a` | `C400*.m4a` |
| `128` | `M500*.mp3` |
| `320` | `M800*.mp3` |

The default `m4a,128,320` avoids unsupported formats and keeps AllMusic client compatibility.

## Open-source references

- `jsososo/QQMusicApi`: reference implementation for search, song detail, and vkey URL generation.
- `Coloryr/netapi`: AllMusic external API shape used as the Java integration model.
- `NapNeko/NapCatQQ`: reference for the QR-code login user experience.
- `vikiboss/mioki`: reference for QQ ptlogin QR parameters and `qrsig` hash calculation.
