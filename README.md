# Thunderfun Arcade

A small Android launcher for the games Claude makes for me. Open the app, tap a game, and it opens in Firefox
(where I'm signed in to Claude, so cloud saves just work).

## Adding a new game

Add an entry to [`games.json`](games.json). The app picks it up the next time it opens or you tap refresh,
with no reinstall needed.

```json
{
  "id": "short-name",
  "name": "Game Name",
  "url": "https://claude.ai/artifact/...",
  "blurb": "One line about the game.",
  "icon": "target",
  "hue": 28
}
```

- `icon`: one of `target`, `sprout`, `flame`, `castle`, `bolt`, `sword`, `star`, `gamepad`, or leave it out to show the game's initials.
- `hue`: the tile colour, 0 to 360 (28 orange, 48 gold, 145 green, 215 blue, 285 purple, 350 red).
- Only `https://claude.ai/` game links are accepted. Anything else is ignored by the app.

## Installing or updating the app

Download the latest APK from the [Releases page](../../releases/latest) on the phone, open it and tap Install.
When a new version is out, the app shows a "New app version" banner that links straight here.

## How it's built

- `app/` is a tiny Android app: a bundled page (`app/src/main/assets/hub.html`) inside a locked-down WebView.
  Its only permission is internet access, used to read `games.json`.
- Pushing changes to `app/` runs the **Build APK** workflow, which builds a signed APK and publishes a Release.
- The signing key `app/release.p12` is encrypted; its password is stored only as the `KEYSTORE_PASSWORD` repo secret.
- To release an app update, bump `versionCode` and `versionName` in `app/build.gradle` and `appVersion` in `games.json`.
