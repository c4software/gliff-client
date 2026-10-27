# Submission folder — Google Play

What the Play Console asks for a first publication, ready to paste or upload.
Texts come in **English** (the app's default language) and **French**.

Application id: `io.github.c4software.gliff`
Version shown in the screenshots: commit `e2d3a26`, before any tag

---

## Inventory

| Item | File | Play constraint | State |
|---|---|---|---|
| Title | [`listing/*/title.txt`](./listing) | 30 characters | 17 (en) / 18 (fr) |
| Short description | [`listing/*/short-description.txt`](./listing) | 80 characters | 74 (en) / 77 (fr) |
| Full description | [`listing/*/full-description.txt`](./listing) | 4,000 characters | ≈ 3,450 (en) / ≈ 3,850 (fr) |
| Release notes | [`release-notes/`](./release-notes) | 500 characters | ≈ 270 |
| Icon | [`graphics/icon-512.png`](./graphics) | 512 × 512, 32-bit PNG | conforming |
| Feature graphic | [`graphics/feature-graphic-*.png`](./graphics) | 1,024 × 500 | conforming |
| Phone screenshots | [`screenshots/phone/en-US/`](./screenshots/phone/en-US), [`screenshots/phone/fr-FR/`](./screenshots/phone/fr-FR) | 2 to 8, 9:16 to 9:20, 1,080 × 2,400 | 4 per language, **to be retaken on a device** (see below) |
| 10-inch tablet screenshots | [`screenshots/tablet/`](./screenshots/tablet) | up to 8, 16:9 to 9:16, 1,080 to 7,680 px a side | 4 per language, 2,560 × 1,600, drawn like the phone's |
| Chromebook screenshots | [`screenshots/desktop/`](./screenshots/desktop) | up to 8, 16:9 or 9:16, 1,080 to 7,680 px a side | 4 per language, 1,920 × 1,080, drawn like the phone's; what a Googlebook shows |
| Data safety | [`data-safety.md`](./data-safety.md) | form | written |
| App content | [`app-content.md`](./app-content.md) | questionnaire | written |
| App access | [`demo-server/`](./demo-server/README.md) | a way in for the reviewer | server written and tested, **not deployed** |
| Privacy policy | [`privacy-policy-en.md`](./privacy-policy-en.md), [`privacy-policy-fr.md`](./privacy-policy-fr.md) | public URL | written, **not published yet** |

### The screenshots, one by one

| Order | en-US | fr-FR | What it shows |
|---|---|---|---|
| 1 | `01-session.png` | `01-session.png` | A session on a private screen, with the tab bar of the open sessions and the three floating buttons: touch mode, landscape, keyboard |
| 2 | `02-keys.png` | `02-touches.png` | The same session with the row of extra keys (Esc, Tab, Ctrl, Alt, Super…) |
| 3 | `03-machines.png` | `03-machines.png` | The machines as tiles: a private screen and a mirror of a named output, each with its desktop's picture and an open session's dot, and a machine never connected to |
| 4 | `04-options.png` | `04-options.png` | Options: scale, keyboard, touch, clipboard, keep awake, web mirror and its address |

The tablet and desktop sets are the same four, landscape; the desktop's options also show full screen on a desktop.

---

## What is not ready, most blocking first

1. **App access.** A reviewer has no Hyprland machine and cannot be given
   credentials. [`demo-server/`](./demo-server/README.md) is the machine to
   give them, but it has to be put on a public host that stays online, and
   its address written into `demo-server/app-access-en.txt`. Until then the
   listing will be rejected.
2. **The session screenshots are not real captures.** The app's screens are
   the real ones, composed by the app's own code at a phone's size, but the
   desktop inside screenshots 1 and 2 is **drawn by the generator**: there is
   no video without a machine, and the only machine at hand was a personal
   desktop. They also lack the status bar, and screenshot 2 lacks the
   on-screen keyboard under the row of keys. Play requires screenshots to
   show the app as it is: retake 1 and 2 on a device, against a desktop that
   can be shown, before sending.
3. **No Play service account.** The release workflow builds and signs the
   bundle on an `android-v*` tag, but sends it to the Play Console only with a
   `PLAY_SERVICE_ACCOUNT_JSON` secret (Cloud Console → IAM → Service
   accounts, invited in the Play Console with *Release to testing tracks*).
   The very first bundle has to be uploaded by hand: the API refuses it.
4. **The privacy policy has no URL.** [`pages.yml`](../.github/workflows/pages.yml)
   builds the site from the two files of this folder, but GitHub Pages has to
   be enabled once on the repository:

   ```sh
   gh api -X POST repos/c4software/gliff-client/pages -f build_type=workflow
   ```

   Then check both URLs answer on the day of sending:

   ```
   https://c4software.github.io/gliff-client/privacy-en.html
   https://c4software.github.io/gliff-client/privacy-fr.html
   ```
5. **The name.** "gliff" is the name of someone else's project. The title
   "Gliff Client" and the closing paragraph of the description say the
   app is an independent client, which is the usual way to stay within
   Play's impersonation policy; asking the author of gliff first would be
   courteous, and would settle it.
6. **Sessions end in the background.** It is listed under "Known limits" in
   the description. Fixing it with a foreground service changes the
   permissions and the answers of `app-content.md`.

## Order of entry in the Play Console

1. **Create the app**: name `Gliff Client`, default language *English
   (United States)*, an app (not a game), free, category *Tools*.
2. **Main store listing** (en-US): title, descriptions, icon, feature
   graphic, screenshots. Then add the **fr-FR** translation.
3. **App content**, in order: privacy policy (URL), app access, ads, content
   rating, target audience, data safety. Answers in
   [`app-content.md`](./app-content.md) and [`data-safety.md`](./data-safety.md).
4. **Internal testing** first: upload the App Bundle and the release notes,
   install from the Play Store on a real device, against a real machine.
5. **Production** only once internal testing holds. A personal developer
   account created after November 2023 must first run a closed test with 12
   testers for 14 days.

---

## How the pictures were made

Screenshots: by `StoreShots`, a desktop test that composes the shared screens
off-screen at 1,080 × 2,400 and 420 dpi, in both languages:

```sh
GLIFF_STORE=1 ./gradlew :shared:desktopTest --tests '*StoreShots*'
```

The machines, fingerprints and addresses in them are made up. To be redone
after any change to the interface.

Icon and feature graphics: the launcher icon's vector paths
(`app/src/main/res/drawable/ic_launcher_foreground.xml`, on `#101319`) rendered with `rsvg-convert`,
with the title set in Noto Sans.

## Still missing, and why

- **Tablet screenshots**: optional, and the app has not been checked on a
  tablet beyond resizing the window.
- **A screenshot of the web mirror in a browser**: it is a secondary feature,
  off by default.
