# Changelog

Development started before the project was put under git, so the git history begins at 0.6.0. Earlier versions are documented below.

---

## 0.6.1 — 2026-10-29 · Showcase theme, deleting games

- **Showcase theme** (13 in total): pick a system over slanted strips of its game art that slowly drift across the screen, with a large console icon and name in the middle. **A** opens the system's game list, **B** goes back.
- **Delete game** in the game options (**Y**): removes the game's files from the phone, including `.cue` tracks, discs from `.m3u`, and the whole folder for `EBOOT.PBP` games. Asks first, with the cursor on *Cancel*.
- UI sounds now follow the phone's media volume and stay quiet in silent / vibrate mode.

## 0.6.0 — 2026-10-26 · Store

- **Game store** with covers, descriptions, screenshots, system filters (LB/RB) and search via the Orbit keyboard.
- **Download** button with progress. Games go into `ROMs/<system>` and show up in the library right away, with the store's cover and title. `.zip` archives are unpacked when the system's emulators can't open them.
- **Built-in sources** of free, legal games: Homebrew Hub (about 1,000 games for GB/GBC/GBA/NES) and Universal-DB (3DS/DS homebrew).
- **Custom sources by link** in the Orbit catalog format (JSON), for any system. The person who adds a source is responsible for its content.
- **My PC** (`tools/orbit_share.py`): shares a game folder from a computer over home Wi-Fi as a store source.
- Allowed `http://` for sources on the local network.

## 0.5.1 — 2026-10-25 · Winlator and GameNative games

- The PC tab recognizes `.desktop` (Winlator Cmod / WinNative) and `.steam`, `.epic`, `.gog`, `.amazon` (GameNative).
- The emulator is chosen **by file type**: `.steam` goes to GameNative, `.desktop` to Winlator.
- Cover, background and logo for `.steam` games come straight from the Steam store, no key needed.
- WinNative shortcuts in `/sdcard/WinNative/Shortcuts` are picked up automatically.
- When Orbit is the home screen, *Add to home screen* from any app adds the game to Orbit.

## 0.5.0 — 2026-10-24 · Quick menu, new themes, play time

- **Quick menu** on the Home/Guide button: resume game, brightness, volume, Wi-Fi, Bluetooth, search, settings (the Store item was added in 0.6.0).
- **Battery reminders** for the phone (15% / 5%) and the controller; controller battery is shown in the top bar.
- **4 new themes:** Blades (Xbox 360), Channels (Wii), Spiral (Dreamcast), Pocket (PSP, color changes every month) — 12 in total.
- **Background music:** a built-in synthesized ambient track or your own file, with its own volume.
- **Accent color from cover art** (Palette), optional.
- **Play time** and **statistics** for the week and all time.
- **Sorting** in tabs: A–Z, recent, most played (R3).
- **Collections** as their own tabs.
- **Multi-disc games:** `Game (Disc N)` files are merged into one game, and the `.m3u` is created automatically.
- DuckStation: option to resume from auto-save.
- Renaming and collection names use the Orbit keyboard.

## 0.4.0 — 2026-10-24 · Search and languages

- **Search with a built-in on-screen keyboard** designed for controllers, with EN / UK / RU layouts; physical keyboards work too.
- Results update while typing; matches word starts and acronyms (`mhfu`).
- **Interface language:** Ukrainian, English, Russian.

## 0.3.1 — 2026-10-24

- Softer UI sounds.
- Fixed grey squares instead of Android app icons: broken images are discarded and the tile shows a fallback icon.
- SteamGridDB is only queried for Android apps marked as games.

## 0.3.0 — 2026-10-24 · Intro and console icons

- Fixed launching in PPSSPP and other emulators ("File doesn't exist"): Orbit gets access to the ROMs folder and passes it to the emulator temporarily.
- **Launcher boot intro** (orbit animation), plus a CRT power-on for the CRT theme.
- **Console icons** (vector silhouettes).
- Games stored as a folder with `EBOOT.PBP` get the folder's name.
- More accurate artwork matching; title screens as a fallback cover.
- **Find a game** from the menu.

## 0.2.0 — 2026-10-24 · Themes

- **8 themes** with different layouts: Orbit (PS5), Waves (XMB), Neon light/dark (Switch), Carousel, Library (Steam Deck), Retro CRT, Sunset.
- Appearance settings: accent, background, own photo, tile shape and size, sounds, clock.
- **Game launch animations:** hyperjump, portal, camera zoom.
- Home shows only recent games and section shortcuts.
- Full touch support.
- Emulator profiles generated from ES-DE; standalone emulators come first, RetroArch is the fallback.
- "Other app…" as an emulator, artwork from the gallery, renaming.
- Fuzzy artwork matching (libretro), about 10× faster.

## 0.1.0 — 2026-10-24 · First version

- PS5-style launcher: a row of tiles, large art in the background, controller navigation.
- ROM scanner (`ROMs/<system>`), launching emulators straight into the game (ES-DE profiles).
- Artwork: own files → SteamGridDB → libretro-thumbnails.
- Android games and Moonlight (starts the PC game directly).
- Autostart when a USB controller is connected (GameSir G8 Galileo).
