# NPC Permadeath

A RuneLite plugin where the NPCs you kill stay dead. Every goblin you slay in
Lumbridge means one fewer goblin around from then on, so the fields slowly
empty out the more you fight.

## How it works

The plugin keeps a **kill count per NPC type per map area** (a 64x64 tile
chunk), saved per account. In each area it then keeps exactly that many NPCs
of that type hidden. Hidden NPCs are not drawn and cannot be clicked.

Which individuals get hidden:

- **The ones you actually killed**, where possible. The game server keeps a
  slain NPC's index (its slot number on that world) for the respawn, so on the
  world you killed it on the exact same goblin stays gone.
- **Substitutes** otherwise. On another world, or after the server renumbers
  NPCs, the plugin hides other NPCs of the same type in the area as they come
  into view, until the count is met. Choices are sticky, so the same NPCs stay
  hidden as you walk around and between sessions.

After each kill the chat shows the tally, e.g. `Goblin: 12 of 121 slain in
Lumbridge`. The place name and total come from the location table on the
NPC's OSRS Wiki page, looked up once per NPC name and cached for a week.
Chunks the wiki files under the same place are reported together, and a kill
that lands in a chunk the wiki has no spawns in (an NPC that wandered over a
border) is credited to the neighbouring chunk that has them. This is display
only; the hiding works without it.

The sidebar panel (tombstone icon) lists everything slain, grouped by place,
with an `x` on each row to forget those kills and bring the NPCs back.

## Settings

**Which NPCs count**

| Setting | Default | Meaning |
| --- | --- | --- |
| Only your kills | on | Only kills that were yours count: your loot dropped from it, or, for NPCs that drop nothing, you dealt the most damage (ties to whoever hit first). Off means any NPC you see die. |
| Include bosses | off | Bosses (anything the OSRS Wiki lists as a boss) can be killed for good. Off means they always respawn. |
| Include instanced areas | off | Count kills and hide NPCs inside instances such as boss rooms and raids. |
| Max combat level | 0 | NPCs above this level always respawn. 0 means no limit. |
| Only these NPCs | blank | Comma-separated names (wildcards allowed), e.g. `Goblin, Cow*`. Blank means everything. |
| Never these NPCs | blank | Names that always respawn, same format. |

**Experience**

| Setting | Default | Meaning |
| --- | --- | --- |
| Reveal NPCs attacking you | on | A hidden NPC that attacks you becomes visible so you can fight back, and another of its kind is hidden instead. |
| Hide loot from hidden NPCs | on | Drops from a hidden NPC (killed by a cannon or area attacks) are invisible and cannot be picked up. Other items on the tile are unaffected. |
| Announce kills in chat | on | Show the area tally after each kill. |

*Forget all slain NPCs* brings everything back and unticks itself. Chat
commands: `::permadeath` shows the tallies for the area you are standing in,
`::permadeath reset` forgets everything.

The boss list lives in `src/main/resources/com/npcpermadeath/bosses.txt` and
is regenerated from the wiki with `python tools/generate_bosses.py`.

## Things to know

- Hidden NPCs that are aggressive can still attack you. With *Reveal NPCs
  attacking you* on, the attacker pops back into view and a different NPC
  takes its place in the hidden count.
- Areas are map chunks under the hood. Kills are credited to a neighbouring
  chunk only once the wiki data for that NPC has been fetched, so the very
  first kill of a new NPC type near a border may sit in its own chunk.
- Hidden choices that have not been seen for two weeks are dropped and refilled
  from whatever is in view, so the count stays right after game updates.
- Loot hiding covers the item models and the right-click options. Other
  plugins that draw ground item text (like Ground Items) may still label the
  tile.

## Building

Requires JDK 11 or newer to run Gradle.

```
./gradlew build
```

`./gradlew run` starts a development RuneLite client with the plugin loaded.
