# BilibiliXlava

Lavalink v4 plugin that adds a Bilibili source. Author: **Pawan**

## Install (JitPack)

```yaml
lavalink:
  plugins:
    - dependency: "com.github.<your-github-user>:BilibiliXlava:<TAG>"
      repository: "https://jitpack.io"
```

## Enable

```yaml
plugins:
  bilibilixlava:
    engine: enable
```

## What it loads

| Input | Example |
| --- | --- |
| Search | `bilisearch:lofi` or `bilibili:lofi` |
| Video (BV / av) | `https://www.bilibili.com/video/BV1xx411c7mD` |
| One page of a multi-part video | `https://www.bilibili.com/video/BV...?p=2` (without `?p=` the whole video loads as a playlist) |
| Bangumi episode / season | `https://www.bilibili.com/bangumi/play/ep12345`, `.../ss678` |
| Audio track / album | `https://www.bilibili.com/audio/au123`, `.../am456` |
| Live room | `https://live.bilibili.com/21452505` (HLS, experimental) |
| Uploader page | `https://space.bilibili.com/2` |
| Short links | `https://b23.tv/XXXX`, `https://bili2233.cn/XXXX` |
| Mobile links | `https://m.bilibili.com/video/BV...` |

Not supported: `bilibili.tv` (international site) links.

## Options (`plugins.bilibilixlava.*`)

| Key | Default | Notes |
| --- | --- | --- |
| `engine` | `enable` | `disable` turns the source off |
| `sourceName` | `bilibili` | name reported to clients |
| `sessdata` | empty | optional login cookie value |
| `searchLimit` | 10 | max 50 |
| `playlistLimit` | 100 | tracks for albums / seasons / uploader pages |
| `maxRetries` | 3 | playback retries with a fresh stream link |

## Notes

- Video/bangumi audio uses the DASH audio-only stream when available. Region-locked, paid or premium content may fail.
- Search and uploader pages are sometimes rate-limited by Bilibili; setting `sessdata` helps.
- Live rooms resolve an HLS stream and hand it to Lavalink's HTTP source; treat it as experimental.

## Build

```
./gradlew build        # jar in build/libs/bilibilixlava-1.0.0.jar
```

JitPack: push to GitHub, create a release tag, then look the repo up on jitpack.io.
