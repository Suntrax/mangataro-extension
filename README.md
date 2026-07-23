# Oni: MangaTaro Extension

Scraper extension for the **Oni** manga client that sources manga from **mangataro.org**.

## How it works

REST API (custom mangapeak theme with /auth/ endpoints).

POST /auth/search, GET /auth/manga-chapters (MD5 token), GET /auth/chapter-content.

The extension exposes two ContentProvider paths:
- `chapters` — returns the full chapter list (metadata only, no images)
- `scrape` — returns image URLs for a single requested chapter

## Building

1. Place your release keystore at `app/release.jks` (or anywhere) and add credentials to `local.properties`:
   ```properties
   storeFile=release.jks
   storePassword=********
   keyAlias=********
   keyPassword=********
   ```
2. Build the Release APK:
   ```bash
   ./gradlew assembleRelease
   ```
3. Install the APK on your device. Oni will discover it automatically via the `com.blissless.mangaclient.EXTENSION_BEACON` broadcast.

## Data contract

See the [Oni extensions template README](https://github.com/Suntrax/oni-extensions) for the full data contract.

## Dependencies

None — uses only Android built-in APIs (`HttpURLConnection`, `WebView`, `org.json`) to keep the APK under ~50 KB after R8.
