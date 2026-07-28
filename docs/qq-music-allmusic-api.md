# QQ Music protocol notes for AllMusic

## Integration contract

`QQMusicApi` is the public AllMusic adapter and implements `IMusicApi`. Its API
ID is `qqmusic`. All protocol, login, persistence, and parsing code is internal
to the jar; no external process or local HTTP service is required.

The provider intentionally supports songs only. AllMusic playlist import is not
part of this implementation, and 1.x configuration fields are not migrated.

## Current endpoints

All JSON requests are sent to:

`https://u.y.qq.com/cgi-bin/musicu.fcg`

The modules used by version 2.0 are:

| Operation | Module | Method |
| --- | --- | --- |
| Search | `music.adaptor.SearchAdaptor` | `do_search_v2` |
| Song detail | `music.pf_song_detail_svr` | `get_song_detail_yqq` |
| Playback vkey | `music.vkey.GetVkey` | `UrlGetVkey` |
| QQ login exchange | `QQConnectLogin.LoginServer` | `QQLogin` |
| Credential refresh | `music.login.LoginServer` | `Login` |

The web request profile uses `ct=24`, `cv=4747474`, and
`platform=yqq.json`. Authenticated calls add the canonical QQ Music cookies and
the login fields required by the musicu protocol.

## QQ QR login

The login sequence is:

1. Request a QR image from QQ ptlogin and retain `qrsig`.
2. Poll `ptqrlogin` using the hash33 token until the user confirms.
3. Parse `uin` and `ptsigx` from the successful callback.
4. Call `check_sig` without following redirects and collect `p_skey` from all
   `Set-Cookie` headers.
5. Submit the QQ OAuth authorization form and read `code` from its redirect.
6. Exchange that code through `QQConnectLogin.LoginServer/QQLogin`.
7. Atomically persist the credential only when `musicid` and `musickey` are
   both complete.

The cookie parser handles multiple header fields and proxy-combined
`Set-Cookie` values, including commas inside `Expires`. Diagnostics list cookie
names only and never log cookie values, OAuth codes, or music keys.

If refresh metadata is present and the key approaches expiry, the provider uses
the QQ Music login refresh module and atomically replaces the saved credential.
An incomplete or failed refresh leaves the previous config file intact and
starts a new QR flow when enabled.

## Playback files

| Config value | QQ Music filename |
| --- | --- |
| `m4a` | `C400<media_mid>.m4a` |
| `128` | `M500<media_mid>.mp3` |
| `320` | `M800<media_mid>.mp3` |

If QQ Music omits `media_mid`, its documented song-MID fallback is used. The
first non-empty purl is joined with QQ Music's returned CDN domain. An empty
purl is a normal permission result for unavailable, VIP, or region-restricted
content and is returned to AllMusic as no playable URL.

## Reference boundary

The behavior was checked against the public protocol implementation in
`L-1124/QQMusicApi` and against AllMusic's public `IMusicApi` contract. This jar
is an independent Java implementation: it does not bundle, import, execute, or
copy the reference project's Python source.

## Verification

The automated suite covers AllMusic argument parsing, current search/detail
payloads, vkey filename generation, request construction, credential boundaries,
OAuth callbacks, and combined `Set-Cookie` parsing. The local smoke runner also
checks real search, metadata, and playback URL resolution:

```bash
./gradlew runSmokeTest -PqqmusicTestConfig=/path/to/qqmusic.json
```
