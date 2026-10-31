#!/usr/bin/env python3
"""
Orbit PC: finds the games on this computer, lets you pick which ones to stream,
adds them to Sunshine and hands the list to Orbit on the phone (Cloud → "Import from PC").

    python3 orbit_pc.py            # window
    python3 orbit_pc.py --serve    # no window, only answers Orbit (used for autostart)
    python3 orbit_pc.py --list     # print what was found
    python3 orbit_pc.py --install  # add "Orbit PC" to the app menu

Finds games from Steam (and its non-Steam shortcuts), Heroic (Epic / GOG / Amazon / sideloaded),
Lutris and Hydra, plus your own files and game folders.
The window needs PySide6: `sudo pacman -S pyside6` or `pip install PySide6`.
Orbit talks to it on port 8766 (TCP + UDP) inside your home network.
"""
import argparse
import json
import locale
import os
import re
import shlex
import shutil
import socket
import sqlite3
import struct
import subprocess
import sys
import threading
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOME = Path.home()
CONF_DIR = HOME / ".config/orbit-pc"
CONF_FILE = CONF_DIR / "config.json"
COVERS = CONF_DIR / "covers"
PREFIX = HOME / ".local/share/orbit-pc/prefix"
PORT = 8766

# ---------- strings ----------

_lang = (os.environ.get("LANG") or locale.getlocale()[0] or "").lower()
UK = _lang.startswith(("uk", "ru"))
EN = {
    "Ігри на цьому ПК": "Games on this PC",
    "Познач, що стрімити на телефон. Вибране піде в Sunshine, а Orbit забере список: Хмара → «Імпорт з ПК».":
        "Tick what to stream to the phone. The selection goes to Sunshine, and Orbit picks up the list: Cloud → \"Import from PC\".",
    "Пошук": "Search", "Пересканувати": "Rescan", "Вибрати всі нові": "Select all new",
    "+ Гра…": "+ Game…", "+ Папка з іграми…": "+ Game folder…", "Зберегти в Sunshine": "Save to Sunshine",
    "Гра": "Game", "Звідки": "Source", "Стан": "Status", "у Sunshine": "in Sunshine", "нова": "new",
    "Мої ігри": "My games", "Папка": "Folder", "Sunshine (свої)": "Sunshine (own)", "Ярлики Steam": "Steam shortcuts",
    "Шукаю ігри…": "Looking for games…", "Знайдено: %d, вибрано: %d": "Found: %d, selected: %d",
    "Адреса для Orbit: %s": "Address for Orbit: %s",
    "Фаєрвол закриває домашню мережу, тому Orbit не знайде ПК сам. Відкрити: %s":
        "The firewall closes the home network, so Orbit won't find this PC by itself. To open it: %s",
    "Orbit уже обслуговує фонова копія Orbit PC": "A background Orbit PC is already answering Orbit",
    "Порт %d зайнятий — Orbit цей ПК не побачить": "Port %d is busy, Orbit won't see this PC",
    "Перейменувати…": "Rename…", "Змінити команду…": "Edit command…", "Прибрати зі списку": "Remove from list",
    "Прибрати папку": "Remove folder", "Відкрити папку": "Open folder",
    "Назва": "Name", "Назва в Sunshine і Orbit:": "Name in Sunshine and Orbit:",
    "Команда запуску:": "Launch command:", "Виберіть гру": "Choose a game",
    "Ігри та програми (*.exe *.bat *.msi *.sh *.AppImage *.desktop *.x86_64);;Усі файли (*)":
        "Games and programs (*.exe *.bat *.msi *.sh *.AppImage *.desktop *.x86_64);;All files (*)",
    "Папка, де кожна гра у своїй підпапці": "A folder with each game in its own subfolder",
    "Зберігаю…": "Saving…", "Завантажую обкладинки…": "Downloading covers…",
    "Нічого не змінилось": "Nothing changed",
    "Прибрати з Sunshine?": "Remove from Sunshine?",
    "Готово: додано %d, прибрано %d. Відкрий цей ПК у Moonlight один раз, щоб він побачив нові ігри, і натисни в Orbit: Хмара → «Імпорт з ПК».":
        "Done: added %d, removed %d. Open this PC in Moonlight once so it sees the new games, then in Orbit: Cloud → \"Import from PC\".",
    "Sunshine перезапущено": "Sunshine restarted",
    "Перезапусти Sunshine, щоб зміни з'явились у Moonlight": "Restart Sunshine so the changes show up in Moonlight",
    "Зараз хтось стрімить": "Someone is streaming right now",
    "Щоб нові ігри з'явились, Sunshine треба перезапустити, і стрім обірветься. Перезапустити зараз?":
        "Sunshine has to restart for the new games to show up, and the stream will drop. Restart now?",
    "Не знайдено Sunshine. Встанови його й запусти хоча б раз.": "Sunshine not found. Install it and start it once.",
    "Відповідати Orbit у фоні (автозапуск)": "Answer Orbit in the background (autostart)",
    "Не знайдено": "Not found",
}


def t(s: str) -> str:
    return s if UK else EN.get(s, s)


# ---------- the game model ----------

@dataclass
class Game:
    key: str               # stable id, e.g. "steam:730"
    name: str
    source: str            # group in the list
    cmd: str               # what Sunshine runs (detached)
    workdir: str = ""
    steam: int | None = None
    cover: str | None = None   # tall cover: URL or local file
    hero: str | None = None    # wide background: URL or local file
    detail: str = ""
    custom: bool = False       # added by hand: can be edited and removed
    folder: str | None = None  # came from this game folder


def norm(name: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[®™©]", "", name)).strip().casefold()


def steam_cdn(appid: int) -> str:
    return f"https://cdn.cloudflare.steamstatic.com/steam/apps/{appid}/library_600x900_2x.jpg"


# ---------- Steam ----------

def vdf_text(s: str) -> dict:
    tokens = re.findall(r'"((?:[^"\\]|\\.)*)"|([{}])', s)
    pos = 0

    def parse() -> dict:
        nonlocal pos
        d = {}
        while pos < len(tokens):
            key, brace = tokens[pos]
            pos += 1
            if brace == "}":
                return d
            if pos >= len(tokens):
                break
            val, brace2 = tokens[pos]
            pos += 1
            d[key.lower()] = parse() if brace2 == "{" else val
        return d

    return parse()


def vdf_binary(b: bytes) -> dict:
    pos = 0

    def cstr() -> str:
        nonlocal pos
        end = b.index(b"\0", pos)
        s = b[pos:end].decode("utf-8", "replace")
        pos = end + 1
        return s

    def parse() -> dict:
        nonlocal pos
        d = {}
        while pos < len(b):
            kind = b[pos]
            pos += 1
            if kind == 8:
                return d
            key = cstr().lower()
            if kind == 0:
                d[key] = parse()
            elif kind == 1:
                d[key] = cstr()
            elif kind == 2:
                d[key] = struct.unpack_from("<I", b, pos)[0]
                pos += 4
            else:
                return d
        return d

    return parse()


STEAM_TOOLS = re.compile(r"^(proton|steam linux runtime|steamworks common|legacy steam runtime|steamvr|steam runtime)", re.I)


def steam_roots():
    seen = set()
    for root, flatpak in ((HOME / ".local/share/Steam", False), (HOME / ".steam/steam", False),
                          (HOME / ".var/app/com.valvesoftware.Steam/.local/share/Steam", True)):
        if (root / "steamapps").is_dir():
            real = root.resolve()
            if real not in seen:
                seen.add(real)
                yield real, flatpak


def steam_cmd(gameid: int, flatpak: bool) -> str:
    if flatpak:
        return f"flatpak run com.valvesoftware.Steam steam://rungameid/{gameid}"
    return f"setsid steam steam://rungameid/{gameid}"


def scan_steam() -> list[Game]:
    games: dict[str, Game] = {}
    for root, flatpak in steam_roots():
        libs = {root}
        try:
            data = vdf_text((root / "steamapps/libraryfolders.vdf").read_text(errors="ignore"))
            for v in data.get("libraryfolders", {}).values():
                if isinstance(v, dict) and v.get("path"):
                    libs.add(Path(v["path"]))
        except OSError:
            pass
        for lib in libs:
            for manifest in (lib / "steamapps").glob("appmanifest_*.acf"):
                try:
                    st = vdf_text(manifest.read_text(errors="ignore")).get("appstate", {})
                except OSError:
                    continue
                appid, name = st.get("appid", ""), st.get("name", "")
                installed = int(st.get("stateflags", "0") or 0) & 4
                if not appid.isdigit() or not name or not installed or appid == "480" or STEAM_TOOLS.match(name):
                    continue
                games[appid] = Game(f"steam:{appid}", name, "Steam", steam_cmd(int(appid), flatpak),
                                    steam=int(appid), cover=steam_cdn(int(appid)), detail=f"Steam ID {appid}")
    return list(games.values())


def scan_steam_shortcuts() -> list[Game]:
    games = {}
    for root, flatpak in steam_roots():
        for f in (root / "userdata").glob("*/config/shortcuts.vdf"):
            try:
                data = vdf_binary(f.read_bytes())
            except (OSError, ValueError, struct.error):
                continue
            for sc in data.get("shortcuts", {}).values():
                name, appid = sc.get("appname", ""), sc.get("appid")
                if not name or not isinstance(appid, int) or sc.get("ishidden"):
                    continue
                gameid = (appid << 32) | 0x02000000
                games[norm(name)] = Game(f"steamsc:{appid}", name, t("Ярлики Steam"), steam_cmd(gameid, flatpak),
                                         detail=sc.get("exe", "").strip('"'))
    return list(games.values())


# ---------- Heroic ----------

def heroic_dirs():
    for d in (HOME / ".config/heroic", HOME / ".var/app/com.heroicgameslauncher.hgl/config/heroic"):
        if d.is_dir():
            yield d


def heroic_cmd(app: str, runner: str) -> str:
    url = f"heroic://launch?appName={urllib.parse.quote(app)}&runner={runner}"
    if shutil.which("heroic"):
        return f'heroic --no-gui "{url}"'
    return f'flatpak run com.heroicgameslauncher.hgl --no-gui "{url}"'


def _json(path: Path):
    try:
        return json.loads(path.read_text(errors="ignore"))
    except (OSError, ValueError):
        return None


def _heroic_art(d: Path, file: str) -> dict:
    data = _json(d / "store_cache" / file) or {}
    lib = data.get("library") or data.get("games") or []
    if isinstance(lib, dict):
        lib = list(lib.values())
    return {g.get("app_name"): g for g in lib if isinstance(g, dict)}


def scan_heroic() -> list[Game]:
    games: dict[str, Game] = {}

    def add(app, title, runner, path, art):
        if not app or not title or app in games:
            return
        a = art.get(app, {})
        games[app] = Game(f"heroic:{runner}:{app}", title, "Heroic", heroic_cmd(app, runner),
                          cover=a.get("art_square") or None, hero=a.get("art_cover") or None, detail=path or "")

    for d in heroic_dirs():
        art = _heroic_art(d, "legendary_library.json")
        for app, g in (_json(d / "legendaryConfig/legendary/installed.json") or {}).items():
            if isinstance(g, dict) and not g.get("is_dlc"):
                add(app, g.get("title"), "legendary", g.get("install_path"), art)

        art = _heroic_art(d, "gog_library.json")
        for g in (_json(d / "gog_store/installed.json") or {}).get("installed", []):
            app = g.get("appName")
            add(app, art.get(app, {}).get("title") or g.get("title"), "gog", g.get("install_path"), art)

        art = _heroic_art(d, "nile_library.json")
        nile = _json(d / "nile_config/nile/installed.json") or []
        for g in nile if isinstance(nile, list) else []:
            app = g.get("id")
            add(app, art.get(app, {}).get("title"), "nile", g.get("path"), art)

        side = _json(d / "sideload_apps/library.json") or {}
        side_art = {g.get("app_name"): g for g in side.get("games", []) if isinstance(g, dict)}
        for app, g in side_art.items():
            add(app, g.get("title"), "sideload", (g.get("install") or {}).get("executable"), side_art)
    return list(games.values())


# ---------- Lutris ----------

def scan_lutris() -> list[Game]:
    games = []
    for db, flatpak in ((HOME / ".local/share/lutris/pga.db", False),
                        (HOME / ".var/app/net.lutris.Lutris/data/lutris/pga.db", True)):
        if not db.exists():
            continue
        try:
            con = sqlite3.connect(f"file:{db}?mode=ro", uri=True)
            rows = con.execute("select id, name, slug, directory from games where installed = 1").fetchall()
            con.close()
        except sqlite3.Error:
            continue
        for gid, name, slug, directory in rows:
            if not name:
                continue
            cmd = f"flatpak run net.lutris.Lutris lutris:rungameid/{gid}" if flatpak else f"lutris lutris:rungameid/{gid}"
            cover = db.parent / "coverart" / f"{slug}.jpg"
            games.append(Game(f"lutris:{gid}", name, "Lutris", cmd, cover=str(cover) if cover.exists() else None,
                              detail=directory or ""))
    return games


# ---------- Hydra (LevelDB, read without extra libraries) ----------

def _varint(b: bytes, pos: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = b[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, pos
        shift += 7


def snappy_decompress(b: bytes) -> bytes:
    size, pos = _varint(b, 0)
    out = bytearray()
    while pos < len(b):
        tag = b[pos]
        pos += 1
        kind = tag & 3
        if kind == 0:
            n = tag >> 2
            if n >= 60:
                extra = n - 59
                n = int.from_bytes(b[pos:pos + extra], "little")
                pos += extra
            n += 1
            out += b[pos:pos + n]
            pos += n
            continue
        if kind == 1:
            n = 4 + ((tag >> 2) & 7)
            off = ((tag >> 5) << 8) | b[pos]
            pos += 1
        elif kind == 2:
            n = (tag >> 2) + 1
            off = int.from_bytes(b[pos:pos + 2], "little")
            pos += 2
        else:
            n = (tag >> 2) + 1
            off = int.from_bytes(b[pos:pos + 4], "little")
            pos += 4
        for _ in range(n):  # copies may overlap themselves
            out.append(out[-off])
    return bytes(out[:size])


def _ldb_block(data: bytes, off: int, size: int) -> list[tuple[bytes, bytes]]:
    raw = data[off:off + size]
    if data[off + size] == 1:
        raw = snappy_decompress(raw)
    restarts = struct.unpack_from("<I", raw, len(raw) - 4)[0]
    end = len(raw) - 4 - 4 * restarts
    pos, key, out = 0, b"", []
    while pos < end:
        shared, pos = _varint(raw, pos)
        unshared, pos = _varint(raw, pos)
        vlen, pos = _varint(raw, pos)
        key = key[:shared] + raw[pos:pos + unshared]
        pos += unshared
        out.append((key, raw[pos:pos + vlen]))
        pos += vlen
    return out


def leveldb_read(folder: Path, prefix: bytes) -> dict[bytes, bytes]:
    """Latest value of every key that starts with prefix (by sequence number)."""
    found: dict[bytes, tuple[int, bytes | None]] = {}

    def put(key, seq, value):
        if key.startswith(prefix) and (key not in found or found[key][0] < seq):
            found[key] = (seq, value)

    for f in sorted(folder.glob("*.ldb")) + sorted(folder.glob("*.sst")):
        try:
            data = f.read_bytes()
            footer = data[-48:]
            _, p = _varint(footer, 0)
            _, p = _varint(footer, p)
            ioff, p = _varint(footer, p)
            isize, p = _varint(footer, p)
            for _, handle in _ldb_block(data, ioff, isize):
                boff, q = _varint(handle, 0)
                bsize, _ = _varint(handle, q)
                for ikey, value in _ldb_block(data, boff, bsize):
                    tag = int.from_bytes(ikey[-8:], "little")
                    put(ikey[:-8], tag >> 8, value if tag & 0xFF == 1 else None)
        except (OSError, IndexError, struct.error, ValueError):
            continue

    for f in sorted(folder.glob("*.log")):
        try:
            data = f.read_bytes()
        except OSError:
            continue
        records, record, pos = [], b"", 0
        while pos + 7 <= len(data):
            block_left = 32768 - pos % 32768
            if block_left < 7:
                pos += block_left
                continue
            length, kind = struct.unpack_from("<HB", data, pos + 4)
            chunk = data[pos + 7:pos + 7 + length]
            pos += 7 + length
            if kind == 0 and length == 0:
                pos += 32768 - pos % 32768 if pos % 32768 else 0
                continue
            if kind == 1:
                records.append(chunk)
            elif kind == 2:
                record = chunk
            elif kind == 3:
                record += chunk
            elif kind == 4:
                records.append(record + chunk)
        for rec in records:
            try:
                seq, count = struct.unpack_from("<QI", rec, 0)
                p = 12
                for i in range(count):
                    kind = rec[p]
                    p += 1
                    klen, p = _varint(rec, p)
                    key = rec[p:p + klen]
                    p += klen
                    value = None
                    if kind == 1:
                        vlen, p = _varint(rec, p)
                        value = rec[p:p + vlen]
                        p += vlen
                    put(key, seq + i, value)
            except (IndexError, struct.error):
                continue
    return {k: v for k, (_, v) in found.items() if v is not None}


def scan_hydra() -> list[Game]:
    db = HOME / ".config/hydralauncher/hydra-db"
    if not db.is_dir():
        return []
    exe = shutil.which("hydralauncher") or "/opt/Hydra/hydralauncher"
    games = []
    for value in leveldb_read(db, b"!games!").values():
        try:
            g = json.loads(value)
        except ValueError:
            continue
        path, shop, oid = g.get("executablePath"), g.get("shop"), g.get("objectId")
        if g.get("isDeleted") or not path or not shop or not oid or not g.get("title"):
            continue
        if not Path(path).exists():
            continue
        steam = int(oid) if shop == "steam" and str(oid).isdigit() else None
        link = "hydralauncher://run?" + urllib.parse.urlencode({"shop": shop, "objectId": oid})
        games.append(Game(f"hydra:{shop}:{oid}", g["title"], "Hydra", f'"{exe}" "{link}"', steam=steam,
                          cover=g.get("customCoverImageUrl") or (steam_cdn(steam) if steam else None),
                          hero=g.get("customHeroImageUrl") or g.get("libraryHeroImageUrl"), detail=path))
    return games


# ---------- your own files and folders ----------

SKIP_EXE = re.compile(
    r"unins|setup|install|redist|vc_?redist|dxsetup|directx|dotnet|crash|report|updater|prereq|easyanticheat|"
    r"eac_|battleye|be_service|helper|cefprocess|touchup|dxweb|config|settings|benchmark|uploader|notification", re.I)
SKIP_DIRS = {"_commonredist", "redist", "redistributables", "directx", "support", "__installer", "_redist",
             "windows", "system32", "syswow64", "engine", "dotnet"}


def command_for(path: str) -> tuple[str, str]:
    """Launch command and working folder for a file picked by hand."""
    p = Path(path)
    ext = p.suffix.lower()
    if ext in (".exe", ".bat", ".msi", ".lnk"):
        runner = "umu-run" if shutil.which("umu-run") else "wine"
        extra = " GAMEID=0" if runner == "umu-run" else ""
        return f'env WINEPREFIX="{PREFIX}"{extra} {runner} "{p}"', str(p.parent)
    if ext == ".desktop":
        return f'gio launch "{p}"', ""
    if ext == ".sh":
        return f'bash "{p}"', str(p.parent)
    return f'"{p}"', str(p.parent)


def clean_name(name: str) -> str:
    n = re.sub(r"[\[(].*?[\])]", " ", name)
    n = re.sub(r"(?<=\w)[._](?=\w)", " ", n)
    n = re.sub(r"\bv\d[\w.]*", " ", n, flags=re.I)
    n = re.sub(r"\s+", " ", n).strip(" -_.")
    return n or name


def find_main_file(folder: Path) -> Path | None:
    best, best_size = None, -1
    for dirpath, dirs, files in os.walk(folder):
        depth = len(Path(dirpath).relative_to(folder).parts)
        dirs[:] = [] if depth >= 5 else [d for d in dirs if d.lower() not in SKIP_DIRS]
        for f in files:
            low = f.lower()
            if not low.endswith(".exe") or SKIP_EXE.search(low):
                continue
            try:
                size = os.path.getsize(os.path.join(dirpath, f))
            except OSError:
                continue
            if size > best_size:
                best, best_size = Path(dirpath) / f, size
    if best:
        return best
    for pattern in ("*.x86_64", "*.AppImage", "start*.sh", "*.sh"):
        hits = sorted(folder.glob(pattern))
        if hits:
            return hits[0]
    return None


def scan_folder(folder: str) -> list[Game]:
    root = Path(folder)
    games = []
    try:
        subdirs = sorted(d for d in root.iterdir() if d.is_dir() and not d.name.startswith("."))
    except OSError:
        return []
    for d in subdirs:
        main = find_main_file(d)
        if not main:
            continue
        cmd, wd = command_for(str(main))
        games.append(Game(f"folder:{main}", clean_name(d.name), f"{t('Папка')}: {root.name}", cmd, wd,
                          detail=str(main), folder=folder))
    return games


# ---------- config and Sunshine ----------

def load_conf() -> dict:
    conf = _json(CONF_FILE) or {}
    conf.setdefault("custom", [])
    conf.setdefault("folders", [])
    conf.setdefault("names", {})    # game key -> name chosen by the user
    conf.setdefault("links", {})    # game key -> name of its app in Sunshine
    conf.setdefault("meta", {})     # Sunshine app name -> {steam, cover, hero} for Orbit
    return conf


def save_conf(conf: dict):
    CONF_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CONF_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(conf, indent=1, ensure_ascii=False))
    tmp.replace(CONF_FILE)


def scan_all(conf: dict) -> list[Game]:
    games: list[Game] = []
    for scan in (scan_steam, scan_heroic, scan_hydra, scan_lutris, scan_steam_shortcuts):
        try:
            games += scan()
        except Exception as e:  # one broken launcher should not hide the others
            print(f"{scan.__name__}: {e}", file=sys.stderr)
    for c in conf["custom"]:
        games.append(Game(c["key"], c["name"], t("Мої ігри"), c["cmd"], c.get("workdir", ""),
                          detail=c.get("path", ""), custom=True))
    for f in conf["folders"]:
        games += scan_folder(f)
    # the same game from two launchers: the first source wins
    seen, unique = set(), []
    for g in games:
        g.name = conf["names"].get(g.key, g.name)
        n = norm(g.name)
        if n not in seen:
            seen.add(n)
            unique.append(g)
    return unique


class Sunshine:
    def __init__(self):
        self.dir = next((d for d in (HOME / ".config/sunshine", HOME / ".var/app/dev.lizardbyte.app.Sunshine/config/sunshine")
                         if d.is_dir()), None)
        self.conf = {}
        if self.dir and (self.dir / "sunshine.conf").exists():
            for line in (self.dir / "sunshine.conf").read_text(errors="ignore").splitlines():
                if "=" in line and not line.lstrip().startswith("#"):
                    k, v = line.split("=", 1)
                    self.conf[k.strip()] = v.strip()

    @property
    def found(self) -> bool:
        return self.dir is not None

    @property
    def apps_file(self) -> Path:
        f = Path(self.conf.get("file_apps", "apps.json"))
        return f if f.is_absolute() else self.dir / f

    @property
    def pc_name(self) -> str:
        return self.conf.get("sunshine_name") or socket.gethostname()

    def read(self) -> dict:
        data = _json(self.apps_file) if self.found else None
        if not isinstance(data, dict):
            data = {"env": {"PATH": "$(PATH):$(HOME)/.local/bin"}, "apps": []}
        data.setdefault("apps", [])
        return data

    def write(self, data: dict):
        f = self.apps_file
        backup = f.with_name(f.name + ".orbit-backup")
        if f.exists() and not backup.exists():
            shutil.copy2(f, backup)  # the original, before Orbit PC touched it
        tmp = f.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=4, ensure_ascii=False))
        tmp.replace(f)

    def streaming(self) -> bool:
        try:
            log = (self.dir / "sunshine.log").read_text(errors="ignore")
        except OSError:
            return False
        return log.rfind("CLIENT CONNECTED") > log.rfind("CLIENT DISCONNECTED")

    def restart(self) -> bool:
        try:
            out = subprocess.run(["systemctl", "--user", "list-units", "--type=service", "--all", "--no-legend", "--plain"],
                                 capture_output=True, text=True, timeout=10).stdout
        except (OSError, subprocess.TimeoutExpired):
            return False
        units = [line.split() for line in out.splitlines() if "sunshine" in line.lower()]
        # columns: UNIT LOAD ACTIVE SUB DESCRIPTION; the running one goes first
        units.sort(key=lambda cols: cols[2:3] != ["active"])
        for unit in [cols[0] for cols in units]:
            if subprocess.run(["systemctl", "--user", "restart", unit], timeout=30).returncode == 0:
                return True
        return False


def plan(rows: list[dict], apps: list[dict]) -> tuple[list[dict], int, int]:
    """
    rows: {"game": Game | None, "sun": existing app name | None, "name": wanted name, "on": bool}
    Returns the new Sunshine app list, how many were added and removed.
    """
    by_name = {a.get("name"): a for a in apps}
    off = {r["sun"] for r in rows if r["sun"] and not r["on"]}
    rename = {r["sun"]: r["name"] for r in rows if r["sun"] and r["on"] and r["name"] != r["sun"]}
    result = []
    for a in apps:
        name = a.get("name")
        if name in off:
            continue
        if name in rename:
            a = dict(a, name=rename[name])
        result.append(a)
    added = 0
    taken = {norm(a.get("name", "")) for a in result}
    for r in rows:
        if r["on"] and not r["sun"] and r["game"] and norm(r["name"]) not in taken:
            g = r["game"]
            app = {"name": r["name"], "detached": [g.cmd], "auto-detach": True, "wait-all": True, "exit-timeout": 5,
                   "image-path": ""}
            if g.workdir:
                app["working-dir"] = g.workdir
            result.append(app)
            taken.add(norm(r["name"]))
            added += 1
    return result, added, len([a for a in apps if a.get("name") in off])


def make_rows(games: list[Game], apps: list[dict], conf: dict) -> list[dict]:
    names = {a.get("name") for a in apps}
    by_norm = {norm(a.get("name", "")): a.get("name") for a in apps}
    rows, used = [], set()
    for g in games:
        sun = conf["links"].get(g.key)
        if sun not in names:
            sun = by_norm.get(norm(g.name))
        if sun in used:
            sun = None
        if sun:
            used.add(sun)
            g.name = sun if g.key not in conf["names"] else g.name
        rows.append({"game": g, "sun": sun, "name": g.name, "on": bool(sun)})
    for a in apps:
        if a.get("name") not in used:
            rows.append({"game": None, "sun": a.get("name"), "name": a.get("name"), "on": True})
    return rows


def download_cover(src: str, dest: Path) -> bool:
    """Sunshine wants PNG covers; converting needs Qt, which the window has anyway."""
    try:
        if src.startswith("http"):
            req = urllib.request.Request(src, headers={"User-Agent": "Orbit-PC"})
            with urllib.request.urlopen(req, timeout=12) as r:
                raw = r.read()
        else:
            raw = Path(src).read_bytes()
        from PySide6.QtGui import QImage
        img = QImage()
        if not img.loadFromData(raw):
            return False
        dest.parent.mkdir(parents=True, exist_ok=True)
        return img.save(str(dest), "PNG")
    except Exception:
        return False


def remember(conf: dict, rows: list[dict]):
    """Links and data for Orbit (Steam ID, pictures) of everything that is in Sunshine after saving."""
    meta = {}
    for r in rows:
        if not r["on"]:
            continue
        g = r["game"]
        old = conf["meta"].get(r["sun"] or "", {})
        if g:
            conf["links"][g.key] = r["name"]
            m = {k: v for k, v in (("steam", g.steam), ("cover", g.cover), ("hero", g.hero)) if v}
            meta[r["name"]] = {k: v for k, v in m.items() if not str(v).startswith("/")}
        elif old:
            meta[r["name"]] = old
    conf["meta"] = meta


# ---------- answering Orbit ----------

def orbit_list() -> dict:
    sun = Sunshine()
    meta = load_conf()["meta"]
    apps = [a.get("name") for a in sun.read()["apps"] if a.get("name")]
    return {"version": 1, "pc": sun.pc_name, "apps": [{"name": n, **meta.get(n, {})} for n in apps]}


class OrbitHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.split("?")[0] != "/orbit-pc.json":
            self.send_error(404)
            return
        body = json.dumps(orbit_list(), ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def start_server() -> bool:
    """HTTP for the list + UDP so Orbit finds this PC without typing an address."""
    try:
        http = ThreadingHTTPServer(("", PORT), OrbitHandler)
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp.bind(("", PORT))
    except OSError:
        return False

    def answer():
        while True:
            try:
                data, addr = udp.recvfrom(256)
                if data.startswith(b"ORBIT_PC?"):
                    udp.sendto(json.dumps({"orbit_pc": 1, "port": PORT, "pc": Sunshine().pc_name}).encode(), addr)
            except OSError:
                pass

    threading.Thread(target=http.serve_forever, daemon=True).start()
    threading.Thread(target=answer, daemon=True).start()
    return True


def local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.0.1", 9))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def tailscale_ip() -> str | None:
    try:
        out = subprocess.run(["ip", "-4", "-o", "addr", "show", "tailscale0"], capture_output=True, text=True, timeout=5).stdout
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"inet (\d+\.\d+\.\d+\.\d+)", out)
    return m.group(1) if m else None


def firewall_hint() -> str | None:
    """ufw blocks the LAN by default; only rules readable without root are checked."""
    try:
        if subprocess.run(["systemctl", "is-active", "--quiet", "ufw"]).returncode != 0:
            return None
        rules = Path("/etc/ufw/user.rules").read_text(errors="ignore")
    except OSError:
        return None
    if re.search(rf"allow (any|udp) ({PORT}|\d+:\d+) ", rules) and str(PORT) in rules:
        return None
    return f"sudo ufw allow {PORT}"


# ---------- install / autostart ----------

APP_ID = "orbit-pc"


def desktop_entry(args: str = "") -> str:
    exe = HOME / ".local/bin" / APP_ID
    return ("[Desktop Entry]\nType=Application\nName=Orbit PC\n"
            "Comment=Pick PC games to stream to Orbit through Sunshine\n"
            f"Exec={exe}{args}\nIcon=applications-games\nCategories=Game;\nTerminal=false\n")


def install():
    target = HOME / ".local/bin" / APP_ID
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(__file__, target)
    target.chmod(0o755)
    apps = HOME / ".local/share/applications"
    apps.mkdir(parents=True, exist_ok=True)
    (apps / f"{APP_ID}.desktop").write_text(desktop_entry())
    print(f"Installed {target} and added Orbit PC to the app menu")


AUTOSTART = HOME / ".config/autostart" / f"{APP_ID}-serve.desktop"


def set_autostart(on: bool):
    if on:
        if not (HOME / ".local/bin" / APP_ID).exists():
            install()
        AUTOSTART.parent.mkdir(parents=True, exist_ok=True)
        AUTOSTART.write_text(desktop_entry(" --serve").replace("Name=Orbit PC", "Name=Orbit PC (background)"))
    elif AUTOSTART.exists():
        AUTOSTART.unlink()


# ---------- the window ----------

def run_window():
    try:
        from PySide6.QtCore import QObject, Qt, Signal
        from PySide6.QtWidgets import (QApplication, QCheckBox, QFileDialog, QHBoxLayout, QHeaderView, QInputDialog,
                                       QLabel, QLineEdit, QMainWindow, QMenu, QMessageBox, QPushButton, QTreeWidget,
                                       QTreeWidgetItem, QVBoxLayout, QWidget)
    except ImportError:
        print("Orbit PC needs PySide6 for its window: sudo pacman -S pyside6  (or: pip install PySide6)\n"
              "Without a window: orbit_pc.py --serve / --list", file=sys.stderr)
        sys.exit(1)

    # Qt hands back copies of Python dicts, so items keep an index into Window.rows
    ROW = Qt.ItemDataRole.UserRole
    FOLDER = Qt.ItemDataRole.UserRole + 1

    class Bus(QObject):
        scanned = Signal(object)
        status = Signal(str)
        saved = Signal(str)

    class Window(QMainWindow):
        def __init__(self, serving: bool):
            super().__init__()
            self.setWindowTitle("Orbit PC")
            self.resize(1000, 700)
            self.conf = load_conf()
            self.sun = Sunshine()
            self.rows: list[dict] = []
            self.tick = None  # a game just added by hand starts ticked
            self.bus = Bus()
            self.bus.scanned.connect(self.show_rows)
            self.bus.status.connect(lambda s: self.status.setText(s))
            self.bus.saved.connect(self.after_save)

            root = QWidget()
            v = QVBoxLayout(root)
            title = QLabel(t("Ігри на цьому ПК"))
            f = title.font()
            f.setPointSize(f.pointSize() + 6)
            f.setBold(True)
            title.setFont(f)
            v.addWidget(title)
            hint = QLabel(t("Познач, що стрімити на телефон. Вибране піде в Sunshine, а Orbit забере список: Хмара → «Імпорт з ПК»."))
            hint.setWordWrap(True)
            v.addWidget(hint)

            bar = QHBoxLayout()
            self.search = QLineEdit()
            self.search.setPlaceholderText(t("Пошук"))
            self.search.setClearButtonEnabled(True)
            self.search.textChanged.connect(self.filter)
            bar.addWidget(self.search, 1)
            for text, slot in ((t("Пересканувати"), self.rescan), (t("Вибрати всі нові"), self.select_new),
                               (t("+ Гра…"), self.add_file), (t("+ Папка з іграми…"), self.add_folder)):
                b = QPushButton(text)
                b.clicked.connect(slot)
                bar.addWidget(b)
            v.addLayout(bar)

            self.tree = QTreeWidget()
            self.tree.setHeaderLabels([t("Гра"), t("Звідки"), t("Стан")])
            self.tree.header().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
            self.tree.header().setSectionResizeMode(1, QHeaderView.ResizeMode.Interactive)
            self.tree.header().resizeSection(1, 380)
            self.tree.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
            self.tree.customContextMenuRequested.connect(self.context_menu)
            self.tree.itemDoubleClicked.connect(lambda it, _: self.rename(it))
            self.tree.itemChanged.connect(self.item_changed)
            v.addWidget(self.tree, 1)

            bottom = QHBoxLayout()
            self.status = QLabel()
            self.status.setWordWrap(True)
            bottom.addWidget(self.status, 1)
            self.autostart = QCheckBox(t("Відповідати Orbit у фоні (автозапуск)"))
            self.autostart.setChecked(AUTOSTART.exists())
            self.autostart.toggled.connect(set_autostart)
            bottom.addWidget(self.autostart)
            self.save_btn = QPushButton(t("Зберегти в Sunshine"))
            self.save_btn.setDefault(True)
            self.save_btn.clicked.connect(self.save)
            bottom.addWidget(self.save_btn)
            v.addLayout(bottom)
            self.setCentralWidget(root)

            self.net = self.network_text(serving)
            if not self.sun.found:
                QMessageBox.warning(self, "Orbit PC", t("Не знайдено Sunshine. Встанови його й запусти хоча б раз."))
            self.rescan()

        @staticmethod
        def network_text(serving: bool) -> str:
            if not serving and not AUTOSTART.exists():
                return t("Порт %d зайнятий — Orbit цей ПК не побачить") % PORT
            text = t("Orbit уже обслуговує фонова копія Orbit PC") + ". " if not serving else ""
            addrs = [local_ip()] + [ip for ip in [tailscale_ip()] if ip]
            text += t("Адреса для Orbit: %s") % "  ·  ".join(addrs[:1] + [f"{ip} (Tailscale)" for ip in addrs[1:]])
            hint = firewall_hint()
            if hint:
                text += "\n" + t("Фаєрвол закриває домашню мережу, тому Orbit не знайде ПК сам. Відкрити: %s") % hint
            return text

        # --- list ---

        def rescan(self):
            self.status.setText(t("Шукаю ігри…"))
            self.save_btn.setEnabled(False)
            conf = self.conf

            def work():
                games = scan_all(conf)
                apps = self.sun.read()["apps"]
                self.bus.scanned.emit(make_rows(games, apps, conf))

            threading.Thread(target=work, daemon=True).start()

        def show_rows(self, rows):
            # what was ticked before a rescan stays ticked
            before = {self.key(r): r["on"] for r in self.rows}
            for r in rows:
                r["on"] = before.get(self.key(r), r["on"]) or self.key(r) == self.tick
            self.tick = None
            self.rows = rows
            self.tree.blockSignals(True)
            self.tree.clear()
            groups: dict[str, QTreeWidgetItem] = {}
            for index, r in enumerate(rows):
                g = r["game"]
                group = g.source if g else t("Sunshine (свої)")
                parent = groups.get(group)
                if parent is None:
                    parent = QTreeWidgetItem(self.tree, [group])
                    parent.setFlags(parent.flags() | Qt.ItemFlag.ItemIsAutoTristate | Qt.ItemFlag.ItemIsUserCheckable)
                    f = parent.font(0)
                    f.setBold(True)
                    parent.setFont(0, f)
                    groups[group] = parent
                    if g and g.folder:
                        parent.setData(0, FOLDER, g.folder)
                item = QTreeWidgetItem(parent, [r["name"], g.detail if g else "", t("у Sunshine") if r["sun"] else t("нова")])
                item.setFlags(item.flags() | Qt.ItemFlag.ItemIsUserCheckable)
                item.setCheckState(0, Qt.CheckState.Checked if r["on"] else Qt.CheckState.Unchecked)
                item.setToolTip(1, g.cmd if g else "")
                item.setData(0, ROW, index)
            self.tree.expandAll()
            self.tree.blockSignals(False)
            self.filter(self.search.text())
            self.save_btn.setEnabled(self.sun.found)
            self.count()

        @staticmethod
        def key(r):
            return r["game"].key if r["game"] else "sun:" + (r["sun"] or "")

        def count(self):
            on = sum(1 for r in self.rows if r["on"])
            self.status.setText(t("Знайдено: %d, вибрано: %d") % (len(self.rows), on) + "\n" + self.net)

        def row(self, item) -> dict | None:
            index = item.data(0, ROW)
            return self.rows[index] if isinstance(index, int) and 0 <= index < len(self.rows) else None

        def item_changed(self, item, column):
            r = self.row(item)
            if r:
                r["on"] = item.checkState(0) == Qt.CheckState.Checked
                self.count()

        def filter(self, text):
            q = text.casefold().strip()
            for i in range(self.tree.topLevelItemCount()):
                group = self.tree.topLevelItem(i)
                visible = 0
                for j in range(group.childCount()):
                    child = group.child(j)
                    hide = bool(q) and q not in child.text(0).casefold()
                    child.setHidden(hide)
                    visible += not hide
                group.setHidden(visible == 0)

        def select_new(self):
            for i in range(self.tree.topLevelItemCount()):
                group = self.tree.topLevelItem(i)
                for j in range(group.childCount()):
                    child = group.child(j)
                    r = self.row(child)
                    if r and not child.isHidden() and not r["sun"]:
                        child.setCheckState(0, Qt.CheckState.Checked)

        # --- editing ---

        def context_menu(self, pos):
            item = self.tree.itemAt(pos)
            if not item:
                return
            r = self.row(item)
            folder_group = item.data(0, FOLDER)
            menu = QMenu(self)
            if r:
                menu.addAction(t("Перейменувати…"), lambda: self.rename(item))
                g = r["game"]
                if g and g.custom:
                    menu.addAction(t("Змінити команду…"), lambda: self.edit_command(g))
                    menu.addAction(t("Прибрати зі списку"), lambda: self.remove_custom(g))
                if g and g.detail and Path(g.detail).exists():
                    folder = Path(g.detail) if Path(g.detail).is_dir() else Path(g.detail).parent
                    menu.addAction(t("Відкрити папку"), lambda: subprocess.Popen(["xdg-open", str(folder)]))
            elif folder_group:
                menu.addAction(t("Прибрати папку"), lambda: self.remove_folder(folder_group))
            if not menu.isEmpty():
                menu.exec(self.tree.viewport().mapToGlobal(pos))

        def rename(self, item):
            r = self.row(item)
            if not r:
                return
            name, ok = QInputDialog.getText(self, t("Назва"), t("Назва в Sunshine і Orbit:"), text=r["name"])
            name = name.strip()
            if not ok or not name or name == r["name"]:
                return
            r["name"] = name
            if r["game"]:
                self.conf["names"][r["game"].key] = name
                for c in self.conf["custom"]:
                    if c["key"] == r["game"].key:
                        c["name"] = name
                save_conf(self.conf)
            self.tree.blockSignals(True)
            item.setText(0, name)
            self.tree.blockSignals(False)

        def add_file(self):
            path, _ = QFileDialog.getOpenFileName(self, t("Виберіть гру"), str(HOME), t(
                "Ігри та програми (*.exe *.bat *.msi *.sh *.AppImage *.desktop *.x86_64);;Усі файли (*)"))
            if not path:
                return
            p = Path(path)
            guess = clean_name(p.parent.name if p.suffix.lower() == ".exe" else p.stem)
            name, ok = QInputDialog.getText(self, t("Назва"), t("Назва в Sunshine і Orbit:"), text=guess)
            if not ok or not name.strip():
                return
            cmd, wd = command_for(path)
            key = f"custom:{path}"
            self.conf["custom"] = [c for c in self.conf["custom"] if c["key"] != key]
            self.conf["custom"].append({"key": key, "name": name.strip(), "cmd": cmd, "workdir": wd, "path": path})
            save_conf(self.conf)
            self.tick = key
            self.rescan()

        def edit_command(self, g):
            cmd, ok = QInputDialog.getText(self, g.name, t("Команда запуску:"), text=g.cmd)
            if ok and cmd.strip():
                for c in self.conf["custom"]:
                    if c["key"] == g.key:
                        c["cmd"] = cmd.strip()
                save_conf(self.conf)
                self.rescan()

        def remove_custom(self, g):
            self.conf["custom"] = [c for c in self.conf["custom"] if c["key"] != g.key]
            save_conf(self.conf)
            self.rescan()

        def add_folder(self):
            folder = QFileDialog.getExistingDirectory(self, t("Папка, де кожна гра у своїй підпапці"), str(HOME))
            if folder and folder not in self.conf["folders"]:
                self.conf["folders"].append(folder)
                save_conf(self.conf)
                self.rescan()

        def remove_folder(self, folder):
            self.conf["folders"] = [f for f in self.conf["folders"] if f != folder]
            save_conf(self.conf)
            self.rescan()

        # --- saving ---

        def save(self):
            gone = [r["sun"] for r in self.rows if r["sun"] and not r["on"]]
            if gone:
                answer = QMessageBox.question(self, "Orbit PC", t("Прибрати з Sunshine?") + "\n\n" + "\n".join(gone))
                if answer != QMessageBox.StandardButton.Yes:
                    return
            restart = True
            if self.sun.streaming():
                answer = QMessageBox.question(self, t("Зараз хтось стрімить"), t(
                    "Щоб нові ігри з'явились, Sunshine треба перезапустити, і стрім обірветься. Перезапустити зараз?"))
                restart = answer == QMessageBox.StandardButton.Yes
            self.save_btn.setEnabled(False)
            self.status.setText(t("Зберігаю…"))
            rows = [dict(r) for r in self.rows]

            def work():
                data = self.sun.read()
                apps, added, removed = plan(rows, data["apps"])
                if not added and not removed and apps == data["apps"]:
                    self.bus.saved.emit(t("Нічого не змінилось"))
                    return
                fresh = {r["name"]: r["game"] for r in rows if r["on"] and not r["sun"] and r["game"]}
                todo = [(a, fresh[a["name"]]) for a in apps if a.get("name") in fresh and fresh[a["name"]].cover]
                if todo:
                    self.bus.status.emit(t("Завантажую обкладинки…"))

                    def cover(pair):
                        app, g = pair
                        dest = COVERS / (re.sub(r"[^\w-]+", "_", g.key) + ".png")
                        if dest.exists() or download_cover(g.cover, dest):
                            app["image-path"] = str(dest)

                    with ThreadPoolExecutor(8) as pool:
                        list(pool.map(cover, todo))
                data["apps"] = apps
                self.sun.write(data)
                for r in rows:
                    if r["on"] and not r["sun"]:
                        r["sun"] = r["name"]
                remember(self.conf, rows)
                save_conf(self.conf)
                msg = t("Готово: додано %d, прибрано %d. Відкрий цей ПК у Moonlight один раз, щоб він побачив нові ігри, і натисни в Orbit: Хмара → «Імпорт з ПК».") % (added, removed)
                if restart and self.sun.restart():
                    msg += "\n" + t("Sunshine перезапущено")
                else:
                    msg += "\n" + t("Перезапусти Sunshine, щоб зміни з'явились у Moonlight")
                self.bus.saved.emit(msg)

            threading.Thread(target=work, daemon=True).start()

        def after_save(self, msg):
            self.rescan()
            QMessageBox.information(self, "Orbit PC", msg)

    app = QApplication(sys.argv)
    app.setApplicationName("Orbit PC")
    app.setDesktopFileName(APP_ID)
    serving = start_server()
    w = Window(serving)
    w.show()
    sys.exit(app.exec())


def main():
    ap = argparse.ArgumentParser(description="Orbit PC: pick PC games to stream to Orbit through Sunshine")
    ap.add_argument("--serve", action="store_true", help="no window, only answer Orbit")
    ap.add_argument("--list", action="store_true", help="print the games that were found")
    ap.add_argument("--install", action="store_true", help="add Orbit PC to the app menu")
    args = ap.parse_args()
    if args.install:
        install()
    elif args.list:
        sun = Sunshine()
        for r in make_rows(scan_all(load_conf()), sun.read()["apps"], load_conf()):
            g = r["game"]
            print(f"[{'x' if r['sun'] else ' '}] {r['name']:<45} {g.source if g else 'Sunshine'}")
    elif args.serve:
        if not start_server():
            sys.exit(f"Port {PORT} is busy")
        print(f"Orbit PC is answering Orbit on {local_ip()}:{PORT}" + (f" and {tailscale_ip()} (Tailscale)" if tailscale_ip() else ""))
        threading.Event().wait()
    else:
        run_window()


if __name__ == "__main__":
    main()
