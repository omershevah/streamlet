# Streamlet

Paste (or tap, or share) a magnet link for an episode, a season or a whole series. Pick a season and an episode, and it plays in VLC while it downloads. Only the chosen episode is downloaded, in playback order, and downloads are cleared when you leave the torrent.

## Build

1. Open the folder in a current Android Studio (it needs Android Gradle Plugin 9.5 support) and let Gradle sync.
2. Run on a device. VLC for Android (`org.videolan.vlc`) must be installed; the app offers the Play Store page if it isn't.
3. Release APKs (`./gradlew assembleRelease`) are minified and split per CPU type, so each one carries only the native library it needs.

## Releases

GitHub Actions builds the APKs on every push. To publish a release, open **Actions → Build → Run workflow** and enter a version (like `1.1`), or push a tag:

```sh
git tag v1.1 && git push origin v1.1
```

The APKs (one per CPU type) appear on the Releases page, with the version taken from the tag.

**Signing.** Without a key, CI signs with a throwaway debug key that changes every run, so each new release has to be installed over an uninstall. To sign every release with the same key, create one once:

```sh
keytool -genkeypair -v -keystore release.jks -alias streamlet -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # on macOS: base64 -i release.jks
```

and add these repository secrets (Settings → Secrets and variables → Actions): `SIGNING_KEYSTORE_BASE64` (the base64 output), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (`streamlet`), `SIGNING_KEY_PASSWORD`. Keep `release.jks` safe and out of git.

## How it works

```
magnet link
  └─ TorrentEngine.resolve   add the torrent with every file switched off, wait for the file list (BEP 9)
       └─ Episodes.parse     group video files into seasons/episodes (S01E02, 1x02, "Season 1/02 …", anime "- 02")
            └─ TorrentEngine.play(file)
                 ├─ switch on only that file
                 ├─ Stream: piece deadlines on the ~24 MB just ahead of the player (time-critical, beats rarest-first)
                 └─ StreamServer: HTTP on 127.0.0.1 with Range support → VLC (seeking moves the window)
```

- The torrent stays connected while you're in it, so "Next episode" starts without finding peers again.
- `StreamService` is a foreground service with a notification (and a Stop button). It keeps the process alive while VLC is in front.
- The start of the file and its last piece (where MP4/MKV keep their index) are fetched first; VLC opens on its own once they're in.

| File | What it does |
|---|---|
| `engine/TorrentEngine.kt` | libtorrent session, resolve, play, stats |
| `engine/Stream.kt` | Read-head tracking and piece deadlines; blocks reads until pieces arrive |
| `engine/StreamServer.kt`, `ByteRange.kt` | Minimal HTTP/1.1 server with Range requests |
| `engine/Episodes.kt`, `Magnet.kt` | Filename parsing, magnet normalization (adds a few public trackers) |
| `MainViewModel.kt` | Screen state: Home → Resolving → Picker → Playing |
| `ui/StreamletApp.kt` | Compose / Material 3 screens |

## Notes

- Dependencies: Compose Material 3, AndroidX core/activity/lifecycle, and [libtorrent4j](https://github.com/aldenml/libtorrent4j) (libtorrent 2.x). No navigation, DI, networking or image libraries.
- Material You colors on Android 12+, edge-to-edge, predictive back, themed (monochrome) launcher icon.
- Uses a `dataSync` foreground service. On Android 15 that's limited to 6 hours a day; the stream stops after that.
- Only stream content you have the right to watch.
