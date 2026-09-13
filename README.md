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

After each kill the chat shows the tally, e.g. `Goblin: 12 of 53 slain in
this area`. The total comes from the spawn pins on the NPC's OSRS Wiki page,
looked up once per NPC name and cached for a week. It is display only; the
hiding works without it.

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| Only NPCs you killed | on | Only NPCs you damaged, or that were fighting you, count. Off means any NPC you see die. |
| Announce kills in chat | on | Show the area tally after each kill. |
| Only these NPCs | blank | Comma-separated names (wildcards allowed), e.g. `Goblin, Cow*`. Blank means everything. |
| Forget all slain NPCs | off | Tick to bring everything back. Unticks itself. |

Chat commands: `::permadeath` shows the tallies for the area you are standing
in, `::permadeath reset` forgets everything.

## Things to know

- This applies to every NPC unless you set a name filter. Kill a boss or a
  quest NPC once and its respawn (or a substitute) will be hidden until you
  reset. Use *Only these NPCs* if you only want it for goblins and the like.
- Instanced areas are ignored entirely.
- Areas are map chunks, so a wandering NPC that strays over a chunk border
  counts toward the neighbouring area.
- Hidden choices that have not been seen for two weeks are dropped and refilled
  from whatever is in view, so the count stays right after game updates.

## Building

Requires JDK 11 or newer to run Gradle.

```
./gradlew build
```

`./gradlew run` starts a development RuneLite client with the plugin loaded.
