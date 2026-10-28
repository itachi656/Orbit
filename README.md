# Orbit — a console-style game launcher for Android

Orbit turns an Android phone with a controller into something that feels like a console. It puts your emulators, Android games and PC streaming (Moonlight) into one fullscreen launcher, with 12 console-inspired themes, full controller navigation and automatic box art.

> **Version 0.5.1 (rebuilt).** This branch is Orbit 0.5.1 — the last version before the Store. It was reconstructed from the 0.6.0 source by removing the Store, because the original 0.5.1 code was not kept. For the current version see the [main branch](../../tree/main).

Android 10+ · English / Українська / Русский

---

## Highlights

- **12 themes with their own layouts:**
  - Orbit (PS5-style row);
  - Waves (PS3 XMB);
  - Neon light/dark (Switch);
  - Carousel (3D coverflow);
  - Library (Steam Deck grid);
  - Retro CRT;
  - Sunset;
  - Blades (Xbox 360);
  - Channels (Wii);
  - Spiral (Dreamcast);
  - Pocket (PSP, color changes every month).
- **One-tap launching** straight into the game, with no emulator menus in between. Launch profiles for 50+ standalone emulators come from ES-DE; RetroArch is only a fallback.
- **Automatic artwork:**
  - your own files;
  - the Steam store (for Steam games);
  - SteamGridDB (with your free API key);
  - libretro-thumbnails, using fuzzy name matching.
- **Android games**, **Moonlight** streams that start directly in the game, and **Winlator / GameNative** PC games.
- **Your stats:**
  - play time with weekly statistics;
  - collections and favorites;
  - sorting;
  - multi-disc games (automatic `.m3u`).
- **Quick menu** on the Guide button: resume the game, brightness, volume, Wi-Fi, Bluetooth, battery of phone and controller.
- **On-screen keyboard** built for controllers (EN/UK/RU layouts) and search by title, file name or acronym (`mhfu`).
- **Polish:**
  - boot intro;
  - launch animations;
  - soft synthesized UI sounds;
  - optional ambient background music;
  - accent color taken from the cover art.
- **Autostart** when a USB controller is plugged in (tested with GameSir G8 Galileo).

## Install

1. Download `Orbit.apk` from **[Releases](../../releases/latest)**.
2. Open it on your phone and allow installing from this source.
3. On first start, allow **All files access** (needed to find your games and hand them to emulators).

## Setting up your games

Put games into `/storage/emulated/0/ROMs/<system>/`. Folder names match ES-DE:

| Folder | System | Default emulator (first installed wins) |
|---|---|---|
| `nes` `snes` `n64` `genesis` | NES / SNES / N64 / Mega Drive | NES.emu, Snes9x EX+, M64Plus FZ, MD.emu… → RetroArch |
| `gb` `gbc` `gba` | Game Boy family | My OldBoy! / My Boy!, Pizza Boy, … → RetroArch |
| `nds` | Nintendo DS | melonDS, DraStic, … |
| `n3ds` | Nintendo 3DS | Azahar / Lime3DS |
| `psx` | PlayStation | DuckStation, … |
| `ps2` | PlayStation 2 | NetherSX2 / AetherSX2, ARMSX2 |
| `psp` | PSP | PPSSPP (incl. Gold) |
| `psvita` | PS Vita | Vita3K (`Name.psvita` containing the title ID) |
| `gc` `wii` | GameCube / Wii | Dolphin |
| `switch` | Switch | Eden, Kenji-NX |
| `windows` | PC | Winlator Cmod (`.desktop`), GameNative (`.steam` / `.epic` / `.gog` / `.amazon`) |

- **Emulator access to your games.** The first time you launch a game, Orbit asks for access to your `ROMs` folder (tap *Use this folder* → *Allow*). After that, Orbit gives each emulator temporary access to the game on every launch, so you don't need to set up folders inside the emulators.
- **Emulators that aren't on the list.** Press **Y → Emulator → + Other app…** and pick any installed app; Orbit will hand it the game file.
- **Another games folder.** Choose it under Menu → Library & games → Games folder.

### PC games from Winlator and GameNative

For each game, export it once and it shows up in the **PC** tab. Nothing is added to your Android home screen.

- **Winlator Cmod / Ludashi** (regular Winlator can't export):
  1. Settings → *Frontend Export Path* → `ROMs/windows`.
  2. Then, on each game shortcut, use *Export for Frontend*.
- **GameNative:** game → ⋮ → *Export for frontend* → save into `ROMs/windows`.
- **WinNative:** shortcuts in `/sdcard/WinNative/Shortcuts` are picked up automatically.

### Moonlight (stream from your PC)

**Cloud** tab → **Add PC game**. Enter the PC name exactly as Moonlight shows it, and the app name exactly as it appears in Sunshine/Apollo (e.g. `Desktop`, `Steam Big Picture`). The stream then starts straight into that game.

## Artwork

Orbit looks for artwork in this order:

1. **Your own files:** `ROMs/media/<system>/covers|heroes|logos/<game file name>.png|jpg|webp`.
2. **Steam store art** for `.steam` games. No key needed.
3. **SteamGridDB:** square covers, large backgrounds and logos. Get a free API key at steamgriddb.com → Preferences → API, then paste it in Menu → Artwork.
4. **libretro-thumbnails** for retro systems. Fuzzy matching turns `pokemon_emerald.gba` into *Pokemon - Emerald Version*.

Any image can be replaced from your gallery (**Y → Cover / Background / Logo from gallery**).

## Controls

| Button | Action |
|---|---|
| D-pad / stick | move (L2/R2 jump by 6) |
| A | play / open |
| B | back / to the start of the row |
| X | favorite |
| Y | game options: emulator, artwork, collections, rename, hide |
| LB / RB | switch tabs |
| R3 | change sorting |
| Select | search |
| Start | menu |
| Guide / Home | quick menu |

Touch works everywhere too:
- tap selects, tap again to play;
- long-press opens game options;
- the button hints at the bottom are tappable.

## Controller autostart

Orbit opens automatically when a USB controller is connected. When Android asks, choose *Always*.

- You can bind one specific controller (by VID:PID) under Menu → Controller & startup.
- On HyperOS/MIUI, allow *Autostart* and *Display pop-up windows while running in the background* for Orbit, otherwise the system may block it.

Orbit can also be set as your home screen.

## Building from source

Requires JDK 17 and Android SDK 35.

```bash
./gradlew assembleRelease
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the code is organized and [CHANGELOG.md](CHANGELOG.md) for the version history.

## License

© 2026 itachi656. **All rights reserved.** The source is publicly visible, and you may download and use the official release builds for personal use. You may not copy, modify or redistribute the code without permission. See [LICENSE](LICENSE). Third-party components and credits are listed in [NOTICE.md](NOTICE.md).

Orbit is not affiliated with Sony, Nintendo, Microsoft, Sega, Valve or any emulator project. All trademarks belong to their owners. Orbit does not include any games or BIOS files.
