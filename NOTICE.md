# Third-party components

| Component | License | Source |
|---|---|---|
| UxPlay receiver core (lib/, incl. playfair, llhttp, mdnsd) | GPL-3.0 (parts LGPL-2.1, MIT) | https://github.com/FDH2/UxPlay |
| libplist | LGPL-2.1 | https://github.com/libimobiledevice/libplist |
| Apple Lossless (ALAC) decoder | Apache-2.0 | https://github.com/macosforge/alac |
| OpenSSL (libcrypto, static) | Apache-2.0 | https://github.com/openssl/openssl |

`app/src/main/cpp/mdnsd_android.c` is a modified copy of UxPlay's `lib/mdnsd/mdnsd.c` (LGPL-2.1): it uses a real host name on Android and re-announces services every 30 seconds.
