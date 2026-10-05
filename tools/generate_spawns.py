"""Regenerate src/main/resources/com/npcpermadeath/spawns.csv.gz.

Pulls the community NPC spawn dump (mejrs/data_osrs, derived from the game
cache) and keeps one line per attackable NPC spawn: name|id|x|y|plane.
This seeds every chunk's NPC list so the plugin can show "0 / 53" before
anyone has killed anything there.

    python tools/generate_spawns.py
"""
import gzip
import json
import urllib.request
from pathlib import Path

SOURCE = "https://raw.githubusercontent.com/mejrs/data_osrs/master/NPCList_OSRS.json"
UA = "npc-permadeath build script (https://github.com/Dewdul)"
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/com/npcpermadeath/spawns.csv.gz"


def main():
    req = urllib.request.Request(SOURCE, headers={"User-Agent": UA})
    records = json.load(urllib.request.urlopen(req))
    lines = set()
    for r in records:
        name = r.get("name")
        actions = r.get("actions") or []
        if not name or name == "null" or "|" in name:
            continue
        if not any(a and a.lower() == "attack" for a in actions):
            continue
        try:
            x, y, plane, npc_id = int(r["x"]), int(r["y"]), int(r.get("p", 0)), int(r["id"])
        except (KeyError, TypeError, ValueError):
            continue
        lines.add(f"{name}|{npc_id}|{x}|{y}|{plane}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(OUT, "wt", encoding="utf-8", newline="\n") as f:
        f.write("# name|id|x|y|plane, attackable NPC spawns from mejrs/data_osrs\n")
        for line in sorted(lines):
            f.write(line + "\n")
    # Rewrite with mtime=0 and no file name so the output is byte-identical
    # when the data has not changed; the weekly job then only commits real
    # changes and installed plugins do not re-download identical data.
    with gzip.open(OUT, "rb") as f:
        content = f.read()
    with open(OUT, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as f:
            f.write(content)
    print(f"wrote {len(lines)} spawns ({OUT.stat().st_size // 1024} KB) to {OUT}")


if __name__ == "__main__":
    main()
