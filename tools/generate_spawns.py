"""Regenerate src/main/resources/com/npcpermadeath/spawns.csv.gz.

The seed is built from two sources and keeps one line per attackable NPC
spawn tile: name|id|x|y|plane.

* The community NPC spawn dump (mejrs/data_osrs, derived from the game
  cache), filtered to NPCs with an "Attack" action. It carries real NPC ids
  but stops at roughly 2022 content.
* Every {{LocLine}} on the OSRS Wiki. Those location tables list exact spawn
  tiles for each monster and are kept current, so they cover newer content
  (Varlamore, Sailing, ...). Only lines that give a combat level are kept.
  They carry no NPC id, so their tiles get id 0.

A tile present in both sources (same lower-cased name, x, y, plane) keeps the
dump's line, which has the real id. This seeds every chunk's NPC list so the
plugin can show "0 / 53" before anyone has killed anything there.

    python tools/generate_spawns.py

If either source cannot be fetched, or the merged result looks implausible,
the script exits non-zero and leaves the existing file untouched, so the
weekly job never replaces good data with a partial set.

For development, --cache FILE keeps the raw wiki pages in FILE (read if it
exists, written otherwise) so the parser can be iterated on without
downloading everything again.

Standard library only: the weekly GitHub Action runs this on a bare Python.
"""
import gzip
import html
import io
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DUMP_SOURCE = "https://raw.githubusercontent.com/mejrs/data_osrs/master/NPCList_OSRS.json"
WIKI_API = "https://oldschool.runescape.wiki/api.php"
UA = "npc-permadeath build script (https://github.com/Dewdul)"
OUT = Path(__file__).resolve().parent.parent / "src/main/resources/com/npcpermadeath/spawns.csv.gz"

HEADER = "# name|id|x|y|plane, attackable NPC spawns: OSRS Wiki LocLine tables (id 0) merged with mejrs/data_osrs\n"

# Guards against writing a partial or runaway result.
MAX_TOTAL = 200_000
MIN_WIKI_PAGES = 600  # about 980 pages use LocLine today

# Politeness and robustness of the wiki client.
PAGE_SIZE = 50  # the most pages the API returns with content for a normal client
PAUSE_SECONDS = 1.0
MAX_ATTEMPTS = 6
MAX_REQUESTS = 600

PROBES = ["Goblin", "Rat", "Frost Nagua", "Sulphur Nagua", "Jaguar", "Blue dragon", "Cow", "Guard"]

requests_made = 0


class FetchError(Exception):
    pass


# ---- HTTP -----------------------------------------------------------------


def http_get(url):
    """GET with retries and exponential backoff; returns (body bytes, headers)."""
    global requests_made
    delay = 2.0
    last = None
    for attempt in range(MAX_ATTEMPTS):
        if requests_made >= MAX_REQUESTS:
            raise FetchError("request budget exhausted")
        requests_made += 1
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                body = resp.read()
                headers = resp.headers
                if headers.get("Content-Encoding", "").lower() == "gzip":
                    body = gzip.decompress(body)
                return body, headers
        except urllib.error.HTTPError as e:
            last = e
            if e.code not in (429, 500, 502, 503, 504):
                raise FetchError(f"HTTP {e.code} for {url}") from e
            retry_after = e.headers.get("Retry-After") if e.headers else None
            wait = float(retry_after) if retry_after and retry_after.isdigit() else delay
        except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
            last = e
            wait = delay
        print(f"  request failed ({last}); retrying in {wait:.0f}s", file=sys.stderr)
        time.sleep(wait)
        delay = min(delay * 2, 60)
    raise FetchError(f"giving up on {url}: {last}")


def api_get(params):
    """One MediaWiki API call, retrying on errors and on maxlag replies."""
    query = dict(params, maxlag="5", format="json", formatversion="2")
    url = WIKI_API + "?" + urllib.parse.urlencode(query)
    delay = 5.0
    for attempt in range(MAX_ATTEMPTS):
        body, headers = http_get(url)
        try:
            data = json.loads(body.decode("utf-8"))
        except ValueError as e:
            raise FetchError(f"unparseable API reply: {e}") from e
        error = data.get("error")
        if not error:
            return data
        if error.get("code") == "maxlag":
            retry_after = headers.get("Retry-After")
            wait = float(retry_after) if retry_after and retry_after.isdigit() else delay
            print(f"  wiki is lagged; retrying in {wait:.0f}s", file=sys.stderr)
            time.sleep(wait)
            delay = min(delay * 2, 60)
            continue
        raise FetchError(f"API error: {error}")
    raise FetchError("wiki stayed lagged")


# ---- wiki fetching --------------------------------------------------------


def fetch_wiki_pages():
    """Returns {page title: wikitext} for every main-namespace page using LocLine."""
    pages = {}
    params = {
        "action": "query",
        "generator": "embeddedin",
        "geititle": "Template:LocLine",
        "geinamespace": "0",
        "geilimit": str(PAGE_SIZE),
        "prop": "revisions",
        "rvprop": "content",
        "rvslots": "main",
    }
    cont = {}
    while True:
        data = api_get(dict(params, **cont))
        for page in data.get("query", {}).get("pages", []):
            revs = page.get("revisions")
            if not revs:
                continue
            text = revs[0].get("slots", {}).get("main", {}).get("content")
            if text is not None:
                pages[page["title"]] = text
        cont = data.get("continue")
        if not cont:
            return pages
        time.sleep(PAUSE_SECONDS)


def load_wiki_pages(cache):
    if cache and cache.exists():
        print(f"using cached wiki pages from {cache}")
        return json.loads(cache.read_text(encoding="utf-8"))
    pages = fetch_wiki_pages()
    if cache:
        cache.write_text(json.dumps(pages, sort_keys=True), encoding="utf-8")
    return pages


# ---- wikitext parsing -----------------------------------------------------

COMMENT = re.compile(r"<!--.*?-->", re.S)
LOCLINE_START = re.compile(r"\{\{\s*loc[ _]?line\s*(?=[|}])", re.I)
NAMED_PARAM = re.compile(r"^\s*([A-Za-z_][\w ]*?)\s*=(.*)$", re.S)
LEVEL_NUMBER = re.compile(r"\d")


def read_template(text, start):
    """Splits the template opening at text[start] ('{{') on its top-level pipes.

    Pipes inside nested {{templates}} and [[links]] do not split. Returns
    (parts, end index) or (None, end) when the template is never closed.
    """
    n = len(text)
    i = start
    depth = 0
    links = 0
    part_start = start + 2
    parts = []
    while i < n:
        two = text[i:i + 2]
        if two == "{{":
            depth += 1
            i += 2
        elif two == "}}":
            depth -= 1
            i += 2
            if depth == 0:
                parts.append(text[part_start:i - 2])
                return parts, i
        elif two == "[[":
            links += 1
            i += 2
        elif two == "]]":
            links = max(0, links - 1)
            i += 2
        else:
            if text[i] == "|" and depth == 1 and links == 0:
                parts.append(text[part_start:i])
                part_start = i + 1
            i += 1
    return None, n


def locline_templates(wikitext):
    """Yields (named params {lower-case key: value}, positional values) per LocLine."""
    text = COMMENT.sub("", wikitext)
    pos = 0
    while True:
        m = LOCLINE_START.search(text, pos)
        if not m:
            return
        parts, end = read_template(text, m.start())
        pos = max(end, m.end())
        if parts is None:
            continue
        named = {}
        positional = []
        for part in parts[1:]:
            nm = NAMED_PARAM.match(part)
            if nm:
                named[nm.group(1).strip().lower()] = nm.group(2).strip()
            elif part.strip():
                positional.append(part.strip())
        yield named, positional


LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]")
TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
TAG = re.compile(r"<[^>]*>")
SPACES = re.compile(r"\s+")


def clean_name(raw):
    """Plain text out of wiki markup, or None if it cannot be reduced to a name."""
    text = raw
    while TEMPLATE.search(text):
        text = TEMPLATE.sub("", text)  # e.g. Hobgoblin{{^|id=3049}}
    text = LINK.sub(r"\1", text)
    text = TAG.sub(" ", text)
    text = text.replace("'''", "").replace("''", "")
    text = html.unescape(text)
    text = SPACES.sub(" ", text).strip()
    if not text or "{" in text or "}" in text or "[" in text or "]" in text or "|" in text or text.startswith("#"):
        return None
    return text


def strip_qualifier(name):
    """Drops a trailing wiki disambiguation such as " (Tarn's Lair)"; the game has none."""
    return re.sub(r"\s*\([^()]*\)\s*$", "", name)


def monster_name(raw):
    cleaned = clean_name(raw)
    return clean_name(strip_qualifier(cleaned)) if cleaned else None


def parse_int(token):
    try:
        return int(token)
    except ValueError:
        try:
            return int(float(token))
        except ValueError:
            return None


def coordinates(positional, default_plane):
    """Yields (x, y, plane) for every point in the unnamed parameters.

    Handles "x:3202,y:3253", "3202,3253" and keyed forms such as
    "desc:text,x:1,y:2,plane:1". Several points can share one parameter.
    """
    for part in positional:
        # A parameter can hold several points separated by whitespace or
        # newlines only when each repeats its keys; split before every "x:".
        chunks = re.split(r"(?=\bx:)", part) if "x:" in part else [part]
        for chunk in chunks:
            x = y = None
            plane = default_plane
            plain = []
            for token in chunk.replace("\n", ",").split(","):
                token = token.strip()
                if not token:
                    continue
                if ":" in token:
                    key, _, value = token.partition(":")
                    key = key.strip().lower()
                    value = value.strip()
                    if key == "x":
                        x = parse_int(value)
                    elif key == "y":
                        y = parse_int(value)
                    elif key in ("plane", "p"):
                        p = parse_int(value)
                        if p is not None:
                            plane = p
                else:
                    value = parse_int(token)
                    if value is not None:
                        plain.append(value)
            if x is None and y is None and len(plain) >= 2:
                x, y = plain[0], plain[1]
            elif x is not None and y is None and plain:
                y = plain[0]  # "x:1364,3186"
            if x is not None and y is not None:
                yield x, y, plane


def wiki_spawns(pages, stats):
    """Returns {(lower name, x, y, plane): display name} from every page's LocLines."""
    tiles = {}
    for title in sorted(pages):
        # Subpages such as "Splashing/Guide" are strategy guides, not monster
        # pages; their tables are not maintained as spawn data, so skip them.
        if "/" in title:
            stats["subpages"] = stats.get("subpages", 0) + 1
            continue
        default_name = monster_name(title)
        for named, positional in locline_templates(pages[title]):
            stats["locline"] += 1
            if not LEVEL_NUMBER.search(named.get("levels", "")):
                stats["no_level"] += 1
                continue
            mtype = named.get("mtype", "pin").strip().lower() or "pin"
            if mtype not in ("pin", "dot"):
                stats["area_mtype"] += 1
                continue
            name = monster_name(named["name"]) if named.get("name") else default_name
            if name and name.lower() == (clean_name(named.get("location", "")) or "").lower():
                name = default_name  # the name is a place label, not the monster
            if not name:
                stats["no_name"] += 1
                continue
            plane = parse_int(named.get("plane", "0") or "0") or 0
            mapid = named.get("mapid", "0").strip() or "0"
            stats["mapid_" + ("0" if mapid == "0" else "other")] += 1
            for x, y, p in coordinates(positional, plane):
                if x < 0 or y < 0 or x > 16383 or y > 16383 or p < 0 or p > 3:
                    stats["bad_coord"] += 1
                    continue
                key = (name.lower(), x, y, p)
                if key not in tiles or name < tiles[key]:
                    tiles[key] = name
    return tiles


# ---- the dump -------------------------------------------------------------


def dump_spawns():
    """Returns {(lower name, x, y, plane): (name, id)} for attackable NPCs in the dump."""
    body, _ = http_get(DUMP_SOURCE)
    records = json.loads(body.decode("utf-8"))
    tiles = {}
    for r in records:
        name = r.get("name")
        actions = r.get("actions") or []
        if not name or name == "null" or "|" in name or name.startswith("#"):
            continue
        if not any(a and a.lower() == "attack" for a in actions):
            continue
        try:
            x, y, plane, npc_id = int(r["x"]), int(r["y"]), int(r.get("p", 0)), int(r["id"])
        except (KeyError, TypeError, ValueError):
            continue
        key = (name.lower(), x, y, plane)
        # Keep the lowest id and the lexically first spelling so the choice
        # does not depend on the order of the dump.
        if key not in tiles or (npc_id, name) < (tiles[key][1], tiles[key][0]):
            tiles[key] = (name, npc_id)
    return tiles


# ---- output ---------------------------------------------------------------


def write_gzip(path, lines):
    """Writes the lines gzipped with mtime 0 and no file name.

    The output is byte-identical when the data has not changed, so the weekly
    job only commits real changes and installed plugins do not re-download
    identical data. The write is atomic: the old file survives any failure.
    """
    raw = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as f:
        f.write((HEADER + "".join(line + "\n" for line in lines)).encode("utf-8"))
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_bytes(raw.getvalue())
    os.replace(tmp, path)


def fail(message):
    print(f"error: {message}; leaving {OUT} untouched", file=sys.stderr)
    sys.exit(1)


def main():
    cache = None
    if "--cache" in sys.argv:
        cache = Path(sys.argv[sys.argv.index("--cache") + 1])
    started = time.time()
    try:
        dump = dump_spawns()
        pages = load_wiki_pages(cache)
    except (FetchError, ValueError, KeyError) as e:
        fail(f"could not fetch the data ({e})")
    if len(pages) < MIN_WIKI_PAGES:
        fail(f"only {len(pages)} wiki pages came back, expected at least {MIN_WIKI_PAGES}")

    stats = {k: 0 for k in ("locline", "no_level", "area_mtype", "no_name", "bad_coord", "mapid_0", "mapid_other")}
    wiki = wiki_spawns(pages, stats)

    merged = {key: (name, npc_id) for key, (name, npc_id) in dump.items()}
    wiki_only = 0
    for key, name in wiki.items():
        if key not in merged:
            merged[key] = (name, 0)
            wiki_only += 1
    lines = sorted(f"{name}|{npc_id}|{key[1]}|{key[2]}|{key[3]}" for key, (name, npc_id) in merged.items())

    if len(merged) < len(dump):
        fail(f"merged total {len(merged)} is smaller than the dump alone ({len(dump)})")
    if len(merged) > MAX_TOTAL:
        fail(f"merged total {len(merged)} is implausibly large (limit {MAX_TOTAL})")

    write_gzip(OUT, lines)

    print(f"dump:   {len(dump)} tiles (attackable, one per name+tile)")
    print(f"wiki:   {len(wiki)} tiles from {len(pages)} pages, {stats['locline']} LocLine templates "
          f"({stats['no_level']} without a level, {stats['area_mtype']} area-style, "
          f"{stats['no_name']} unnamed, {stats['bad_coord']} bad coordinates; "
          f"mapID 0: {stats['mapid_0']}, other: {stats['mapid_other']})")
    print(f"merged: {len(merged)} tiles ({wiki_only} wiki-only, {len(wiki) - wiki_only} also in the dump)")
    for probe in PROBES:
        low = probe.lower()
        print(f"  {probe}: dump {sum(1 for k in dump if k[0] == low)}, "
              f"wiki {sum(1 for k in wiki if k[0] == low)}, "
              f"merged {sum(1 for k in merged if k[0] == low)}")
    print(f"wrote {len(lines)} spawns ({OUT.stat().st_size / 1024:.1f} KB) to {OUT}")
    print(f"{requests_made} HTTP requests in {time.time() - started:.0f}s")


if __name__ == "__main__":
    main()
