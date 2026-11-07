# worldmap: the world map area table

Writes `src/main/resources/com/npcpermadeath/worldmap_areas.txt`. The plugin
uses it to outline chunks on the underground world maps.

## Why

The world map's maps are built from pieces of the world: 64x64 map squares
and 8x8 zones. Each piece has a display position that can differ from its
real position. The surface draws everything in place. Most underground maps
move pieces next to each other. For example, the Waterbirth Dungeon map shows
the Dagannoth Kings' lair beside the island's caves, but its real tiles are
about 5,000 tiles away.

RuneLite's API gives only the map's display position. It has no way to turn
a display position into world tiles, so the plugin ships this table from the
game cache.

## Format

`area <id> <surface 0|1> <name>` starts a map. The name is as the game's map
list shows it. Each line after that is a rectangle of zones that share one
offset: `x y width height dx dy` in game tiles. Map position `(x, y)` shows
world tile `(x + dx, y + dy)`.

`exit x y sx sy` lines say where an underground map is entered from the
surface: world tile `(x, y)` on that map is reached from surface tile
`(sx, sy)`. They come from the world map's intermap links, the dungeon and
ladder icons that switch between maps, in either direction.

Some maps have no surface link of their own. Dorgesh-Kaan, for example, is
reached through the Lumbridge caves. Such a map gets the entrance of the map
it links to, up to three maps away. Maps the game links to nothing get no
entrance. The generator lists them, among them The Abyss, Lassar Undercity
and Ghorrock Prison.

## Regenerate

This is a standalone Gradle project. The plugin build doesn't include it, and
nothing here ships except the text file. Run it from the repo root with
JDK 21 for Gradle.

You can use any OSRS disk cache: either a copy of the RuneLite client's own
(`~/.runelite/jagexcache/oldschool/LIVE`, copied first so the running client
isn't touched), or an OpenRS2 `disk.zip` unpacked from
https://archive.openrs2.org/caches.

```bash
./gradlew --no-daemon -p tools/worldmap generate -Pcache=<cache dir> -Psource="<where the cache came from>"
```

Regenerate after a game update that adds or rebuilds an underground map.
Until then, a map missing from the table falls back to a plain grid. That is
correct for maps drawn in place, but may be wrong elsewhere.
