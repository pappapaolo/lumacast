# Lumacast

**Free, open-source AirPlay screen-mirroring receiver for Android TV and Google TV.**

Lumacast turns your Android TV, Google TV or projector into a screen-mirroring target for iPhone, iPad and Mac. Your TV shows up in **Control Center → Screen Mirroring**, with nothing to install on the Apple device. No ads, no subscriptions, no time limits, no account.

- Screen mirroring from iPhone, iPad and Mac
- Sound included; music from audio apps plays through the TV (lossless ALAC)
- Hardware video decoding (H.264) for low latency
- Starts with the TV and stays discoverable
- Everything stays on your local network: no analytics, no cloud

**Not supported:** DRM-protected apps (Netflix, Apple TV+ and similar show a black screen) and AirPlay video casting from inside apps; use Screen Mirroring instead.

## Build

```bash
git clone --recursive https://github.com/pappapaolo/lumacast.git
cd lumacast
scripts/build-openssl.sh      # builds static libcrypto for armeabi-v7a and arm64-v8a
./gradlew assembleDebug
```

Needs the Android SDK, NDK 28.2.13676358, CMake 3.22.1 and JDK 17+.

## How it works

The AirPlay protocol implementation is the receiver core of [UxPlay](https://github.com/FDH2/UxPlay) (GPLv3), compiled with the NDK. Lumacast adds the Android side: JNI glue, MediaCodec hardware decoding onto a Surface, AAC-ELD/ALAC audio through AudioTrack, a patched mDNS responder that re-announces periodically (some routers drop mDNS replies), a foreground service that starts at boot, and a TV-first UI.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for third-party components.

AirPlay, iPhone, iPad and Mac are trademarks of Apple Inc. Lumacast is an independent open-source project and is not affiliated with, endorsed or sponsored by Apple.
