# NPC Permadeath

A RuneLite plugin where the NPCs you kill stay dead. Once you have slain a
goblin, that goblin is hidden the next time it respawns, so the fields around
Lumbridge slowly empty out the more you fight.

## How it works

The game client never sees a permanent identity for an NPC, only the
server's NPC *index* (its slot number on that world). When you kill a static
NPC the server keeps that index and reuses it for the respawn, so "the goblin
I killed" is index + NPC type. The plugin records that pair the moment the
dead NPC despawns, whether or not you stay for the respawn, saves it per
account, and from then on skips drawing any NPC that matches. Hidden NPCs
cannot be clicked either.

By default the record applies on every world, on the assumption that all
worlds load the same spawn list in the same order and so number static NPCs
the same way. `::permadeath npcs` prints nearby NPCs with their indices so
you can check that for yourself by hopping. If it does not hold for you,
turn off *Apply on every world*.

As a second, renumbering-proof identity, whenever a slain NPC is seen
respawning in front of you its spawn tile is learned and saved too. An NPC
appearing on a learned tile is hidden even if its index has changed.

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| Only NPCs you killed | on | Only NPCs you damaged, or that were fighting you, count. Off means any NPC you see die. |
| Learn spawn tiles | on | Remember the spawn tile of slain NPCs seen respawning. |
| Apply on every world | on | Use remembered NPC numbers on any world, not just the one you killed on. |
| Only these NPCs | blank | Comma-separated names (wildcards allowed), e.g. `Goblin, Cow*`. Blank means everything. |
| Forget all slain NPCs | off | Tick to bring everything back. Unticks itself. |

Chat commands: `::permadeath` shows how many kills and spawn tiles are
remembered, `::permadeath npcs` lists the nearest NPCs with their indices,
`::permadeath reset` forgets everything.

## Limitations

- If a game update reshuffles NPC numbering, a remembered number may point
  at a different NPC. A different NPC *type* at that number is detected and
  the stale record dropped; the same type (another goblin) is hidden in its
  place, so the count of missing goblins stays right even if the individual
  is wrong.
- Spawn tiles are only learned when you are nearby for the respawn and not
  inside an instance.

## Building

Requires JDK 11 or newer to run Gradle.

```
./gradlew build
```

`./gradlew run` starts a development RuneLite client with the plugin loaded.
