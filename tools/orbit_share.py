#!/usr/bin/env python3
"""
Orbit "My PC": shares your game folder over your home network as a source for the Orbit store.

    python3 orbit_share.py /path/to/ROMs [--port 8765]

Use the same folder layout as on the phone: ROMs/psp/…, ROMs/psx/…, ROMs/n3ds/…
Covers (optional): ROMs/media/<system>/covers/<game file name>.png|jpg
The script prints a link — add it in Orbit: Store → Y → "+ Add source by link".
Only reachable inside your home network.
"""
import argparse
import json
import os
import socket
import sys
import urllib.parse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

# folder name -> Orbit system id (same as in the app)
FOLDERS = {
    "nes": "nes", "snes": "snes", "n64": "n64", "gb": "gb", "gbc": "gbc", "gba": "gba",
    "genesis": "genesis", "megadrive": "genesis", "nds": "nds", "n3ds": "n3ds", "3ds": "n3ds",
    "psx": "psx", "ps1": "psx", "ps2": "ps2", "psp": "psp", "psvita": "psvita",
    "gc": "gc", "gamecube": "gc", "wii": "wii", "switch": "switch", "windows": "windows", "pc": "windows",
}
GAME_EXT = {
    "nes", "fds", "sfc", "smc", "n64", "z64", "v64", "gb", "gbc", "gba", "md", "gen", "smd", "sms", "gg",
    "nds", "3ds", "3dsx", "cci", "cxi", "chd", "cue", "pbp", "m3u", "iso", "cso", "zso", "rvz", "wbfs", "gcz",
    "nsp", "xci", "psvita", "desktop", "steam", "zip", "7z",
}
SKIP_EXT = {"bin", "img", "sub"}  # parts of a .cue — only the .cue itself is listed


def build_catalog(root: str) -> dict:
    games = []
    for folder in sorted(os.listdir(root)):
        system = FOLDERS.get(folder.lower())
        path = os.path.join(root, folder)
        if not system or not os.path.isdir(path):
            continue
        for dirpath, _, files in os.walk(path):
            for name in sorted(files):
                ext = name.rsplit(".", 1)[-1].lower() if "." in name else ""
                if ext not in GAME_EXT or ext in SKIP_EXT or name.startswith("."):
                    continue
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, root).replace(os.sep, "/")
                base = name.rsplit(".", 1)[0]
                game = {
                    "title": base,
                    "system": system,
                    "url": "files/" + urllib.parse.quote(rel),
                    "file": name,
                    "size": os.path.getsize(full),
                }
                for cext in ("png", "jpg", "jpeg", "webp"):
                    cover = os.path.join(root, "media", folder, "covers", f"{base}.{cext}")
                    if os.path.exists(cover):
                        game["cover"] = "files/" + urllib.parse.quote(os.path.relpath(cover, root).replace(os.sep, "/"))
                        break
                games.append(game)
    return {"name": f"My PC ({socket.gethostname()})", "games": games}


def lan_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", help="game folder (same layout as ROMs on the phone)")
    ap.add_argument("--port", type=int, default=8765)
    args = ap.parse_args()
    root = os.path.abspath(args.root)
    if not os.path.isdir(root):
        sys.exit(f"Folder not found: {root}")

    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=root, **kw)

        def do_GET(self):
            path = urllib.parse.urlparse(self.path).path
            if path in ("/", "/catalog.json"):
                body = json.dumps(build_catalog(root), ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            if path.startswith("/files/"):
                self.path = "/" + path[len("/files/"):]
                return super().do_GET()
            self.send_error(404)

        def list_directory(self, path):  # never list folder contents
            self.send_error(403)
            return None

    count = len(build_catalog(root)["games"])
    url = f"http://{lan_ip()}:{args.port}/catalog.json"
    print(f"Games found: {count}")
    print(f"Add this in Orbit (Store → Y → \"+ Add source by link\"):\n\n    {url}\n")
    print("Press Ctrl+C to stop")
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
