# QR Generator

Simple Android app that turns any text or link into a QR code.

## Features

- Live QR generation while typing (UTF-8, Cyrillic supported)
- QR scanner (camera, gallery image, or image shared from another app)
- Shows what a code contains: links, Wi-Fi, contacts, phone, SMS, email, location, events
- Offline phishing check for every link, letter by letter:
  - look-alike letters from other alphabets (Cyrillic, Greek, Armenian), mixed alphabets, punycode
  - brand imitation (`pаypal.com`, `paypa1.com`, `rnicrosoft.com`) and typosquatting (`sberbnak.ru`)
  - real domain detection (`paypal.com.evil.ru`, `google.com@evil.ru`, backslash tricks)
  - invisible and text-direction characters, disguised IPs (`http://3232235777`)
  - shorteners, free hosting, suspicious zones, non-standard ports, http without TLS
  - dangerous schemes (`javascript:`, `intent:`, `data:`) are never opened
- Share the QR image together with the original text
- Save the QR code as PNG to `Pictures/QRGenerator`
- Accepts text shared from other apps (for example a link from a browser)
- Light and dark theme
- Android 10+ (minSdk 29)

## Download

Get the APK from the [Releases](../../releases) page and allow installation from unknown sources.

## Tests

```
./gradlew testReleaseUnitTest
```

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35.0.0).

```
./gradlew assembleRelease
```

Release signing reads `keystore.properties` in the project root:

```
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file the release build is signed with the debug key.

## Stack

Kotlin, Android Views, CameraX, [ZXing core](https://github.com/zxing/zxing) for encoding and decoding.
