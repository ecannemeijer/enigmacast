# EnigmaCast

EnigmaCast turns an Android TV into a remote screen for an Enigma2 receiver. The receiver stays the GUI. The TV plays the live MPEG-TS stream with Media3 and draws the real Enigma2 OSD, grabbed from OpenWebif, on top of it. The Android TV remote sends Linux key codes to OpenWebif.

The first target is an Octagon SF8008 running OpenATV 7.6. Nothing in the app is hardcoded to that model. The same OpenWebif client is meant for other Enigma2 receivers (Vu+, Dreambox, Zgemma, Mutant, Gigablue, Edision, and others) as long as they expose OpenWebif and the standard streaming port.

```text
Enigma2 receiver
    live MPEG-TS :8001 -----------------> Media3 / ExoPlayer ----+
    /grab?format=png&mode=osd ----------> OSD overlay -----------+--> Android TV
    /api/remotecontrol <----------------- DPAD / remote keys <---+
    /api/getcurrent --------------------> service monitor -------+--> new stream URL
```

Android does not rebuild the infobar, channel list, EPG, menus, timers, or plugins. Those stay on the receiver and appear through the OSD grab.

## Requirements

- Android Studio Quail 4 (2026.1.4) or newer, with AGP 9.4 support
- JDK 17 or newer. Android Studio's bundled JDK 21 is the one used for development
- Android SDK platform 37 and build-tools. Gradle downloads platform 37 when the SDK licenses are accepted
- An Android TV device or an Android TV emulator. `minSdk` is 28 (Android 9). Android 10, 11, and newer are included.
- A receiver on the same LAN with OpenWebif enabled and the stream server on port 8001

`compileSdk` and `targetSdk` are 37 because Compose 1.12 and AndroidX Hilt 1.4 require compile SDK 37. Android 17 ignores a forced landscape lock on large screens. The activity still requests landscape, and the UI is a fullscreen 16:9 surface, which is how Android TV presents the app.

## Dependency versions

Pinned in `gradle/libs.versions.toml` (stable releases checked on 5 October 2026):

| Component | Version |
| --- | --- |
| Kotlin | 2.4.20 |
| KSP | 2.3.11 |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.7.0 |
| Compose BOM | 2026.09.00 |
| Compose for TV | 1.1.0 |
| Media3 | 1.11.1 |
| Dagger Hilt | 2.60.1 |
| AndroidX Hilt | 1.4.0 |
| OkHttp | 5.5.0 |
| Coil | 3.6.0 |
| DataStore | 1.2.1 |
| kotlinx-coroutines / serialization | 1.11.0 |
| compileSdk / targetSdk | 37 |
| minSdk | 28 |
| applicationId | com.enigmacast.app |

Gradle 9.8.0 exists, but Kotlin 2.4.20 documents full compatibility through 9.7.0, so the wrapper stays on 9.7.0.

## Build

From the project root, with `JAVA_HOME` pointing at JDK 17+:

```bat
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Receiver setup

1. Enable OpenWebif on the receiver (OpenATV: menu, plugins, OpenWebif).
2. Note the receiver IP, the HTTP port (usually 80), and whether a username and password are set.
3. Leave the streaming server on port 8001 unless the image uses another port.
4. Start EnigmaCast. The first launch opens the connection screen.
5. Enter the receiver name, host, ports, and optional credentials.
6. Test connection, then Connect.

The test calls `/api/about`, probes `/grab` for PNG and JPG in `osd` and `all` modes, and checks that the streaming port accepts a TCP connection. A successful test shows the model, image, and Enigma version returned by OpenWebif.

HTTP is allowed because local OpenWebif is often plain HTTP. The connection screen warns that HTTP is not encrypted. HTTPS uses the system trust store. Trusting a self-signed certificate is off until you enable it in settings. There is no default trust-all configuration.

Passwords are stored in app-private Preferences DataStore. They are not written to logs. Encrypted DataStore (`datastore-tink`) is still alpha, so it is not used.

The settings model is a list of receivers plus an active id, so more than one receiver can be stored later. Version 1 edits the active receiver.

Network discovery is a Search button. It browses mDNS `_http._tcp` and fills in a host if something answers. Many images do not advertise OpenWebif reliably, so discovery never runs by itself and a failure leaves manual setup working.

## OpenWebif

All paths are built in `OpenWebifEndpoints`.

- JSON API: `/api/about`, `/api/statusinfo`, `/api/getcurrent`, `/api/powerstate`, `/api/vol`, `/api/bouquets`, `/api/getservices`, `/api/remotecontrol`
- OSD: `/grab?format=png|jpg&mode=osd|all|video`
- Stream: `http://HOST:8001/<percent-encoded service reference>`

`/api/` is JSON. The older `/web/` routes return XML and are not used. `/grab` is on the web root, not under `/api`. The stream port stays HTTP even when OpenWebif itself is HTTPS.

Remote commands use Linux input codes (`KEY_OK` = 352, `KEY_UP` = 103, `KEY_EXIT` = 174, and the rest in `EnigmaKey`). OpenWebif `remoteControl()` sends key-down and key-up for a normal command. `type=long` adds the long-press flag. There is no key-up or native repeat parameter, so holding a direction or channel key resends the short command (default every 120 ms). Info, menu, EPG, audio, and the color keys send `type=long` after the long-press threshold.

Android Back sends Enigma2 EXIT. It does not close the app. Hold Back to open Settings or exit EnigmaCast. The Android Home key stays Home. Volume keys are left to Android, because the MPEG-TS audio is decoded on the TV and the receiver volume usually does not change that stream.

## Streaming and OSD

`LivePlaybackController` keeps a single ExoPlayer. A zap calls `setMediaItem` and `prepare`. Playback uses a small live buffer (about 0.5 s to start, 5 s maximum) when low-latency mode is on. Decoders are the TV's MediaCodec decoders, with fallback enabled: MPEG-2, H.264, H.265, AAC, AC3, and E-AC3 work when the hardware exposes them. There is no bundled FFmpeg decoder.

The OSD is the Enigma2 GUI framebuffer (commonly 720p or 1080p), not the active video rectangle. A 4:3 broadcast is letterboxed inside that framebuffer while menus still cover the full GUI. The overlay is therefore aligned to the video surface. When the OSD aspect and the surface aspect match (both 16:9), it uses fill-bounds. When they differ, it is scaled uniformly and centered so the GUI is not stretched.

Grabs are hashed with CRC32. An unchanged image does not publish a new frame. Bitmaps are decoded into a two-slot pool so the bitmap on screen is not recycled. Polling is adaptive: a fast burst after a remote key (0, 50, 100, 175, 275, 400, 600, 900 ms), then slower, then idle. A nearly transparent PNG is treated as a hidden OSD. JPEG has no alpha, so a JPEG OSD is never treated as empty.

After a remote command the service monitor polls at 50, 100, 200, 350, 500, and 1000 ms, then returns to a 1.5 s idle poll. A new service reference switches the player URL.

OSD failure does not stop video. Video failure does not stop remote commands. If the receiver disappears, a connection overlay is shown and the app probes again. When the network returns, playback and the receiver probe are nudged.

## Debugging

Settings can enable:

- Debug overlay: host, service, reference, video, audio, Media3 state, buffer or live offset, OSD state, grab latency, OSD frame rate, last key
- Verbose logging, tags `EnigmaNetwork`, `EnigmaRemote`, `EnigmaOsd`, `EnigmaPlayer`, `EnigmaService`, `EnigmaConnection`
- OSD diagnostics: Grab PNG OSD, Grab JPG OSD, Grab PNG ALL, Grab JPG ALL, with status, content type, resolution, size, duration, and whether a PNG alpha channel was detected

## Known limitations

- OpenWebif cannot send a bare key-up or a kernel auto-repeat. Repeat is a series of short commands.
- PNG OSD alpha depends on the image and the `grab` binary. On some boxes `mode=osd` is opaque black outside the widgets. Use OSD diagnostics on the SF8008 to see which mode keeps transparency.
- AC3, E-AC3, and MPEG-2 depend on the Android TV decoder. If the SoC has no decoder, that track stays silent or the video stays black.
- mDNS discovery is best-effort.
- Passwords are not encrypted at rest.
- A future receiver-side plugin can replace the OpenWebif sources (`OsdSource`, `RemoteControlSource`, `ServiceStateSource`) with a push transport without rewriting the player screen.

## Local XAMPP

`http://localhost/enigmacast/install.php` is served from `C:\xampp\htdocs\enigmacast` by the localhost virtual host in `C:\xampp\apache\conf\extra\httpd-vhosts.conf`. That host has to be the first one and its document root has to stay `C:/xampp/htdocs`. From the project root, as administrator:

```bat
xampp\install.bat
```

The script writes that virtual host, turns on `Include conf/extra/httpd-vhosts.conf` in `httpd.conf`, and leaves an existing `install.php` in place. Restart Apache from the XAMPP Control Panel when it is not running as a service.

## Troubleshooting

- Test connection fails immediately: check the IP, that OpenWebif is enabled, and that the TV is on the same LAN. A VPN on the TV will hide the receiver.
- HTTP 401: fill in the OpenWebif username and password.
- HTTPS fails with a certificate error: enable the self-signed option only for that receiver, or install a certificate the TV already trusts.
- Picture without OSD: open OSD diagnostics and try PNG OSD, then JPG OSD. Confirm `grab` works in a browser on the same URL.
- OSD without picture: confirm port 8001 is open and the receiver is not in standby. The player reconnects on its own when auto-reconnect is on.
- Zapping on the receiver updates the TV a moment later: that delay is the service poll plus tuner and decoder startup. Sending the zap from the Android remote triggers the fast poll.
- Menus feel sluggish: turn on the debug overlay and read OSD latency and frame rate. The receiver's `grab` cost is usually the limit, not Compose.
