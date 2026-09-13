# NPC Permadeath

A RuneLite plugin where the NPCs you kill stay dead. Once you have slain a
goblin, that goblin is hidden the next time it respawns, so the fields around
Lumbridge slowly empty out the more you fight.

## How it works

Old School RuneScape gives each NPC on a world a server-side *index*. When you
kill a static NPC the server keeps that index and reuses it for the respawn,
so the plugin can recognise "the goblin I killed" when it comes back and
skip drawing it. Hidden NPCs cannot be clicked either.

Indices are only stable for one world until the next server restart, so when
a slain NPC respawns in front of you the plugin also learns its spawn tile.
That list is saved per account and used on every world and login, which is
how the effect persists between sessions.

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| Only NPCs you killed | on | Only NPCs you damaged, or that were fighting you, count. Off means any NPC you see die. |
| Remember between sessions | on | Learn spawn tiles and keep them hidden on later logins. |
| Only these NPCs | blank | Comma-separated names (wildcards allowed), e.g. `Goblin, Cow*`. Blank means everything. |
| Forget all slain NPCs | off | Tick to bring everything back. Unticks itself. |

Chat commands: `::permadeath` shows how many NPCs are hidden and remembered,
`::permadeath reset` forgets them all.

## Limitations

- The spawn tile is only learned if you are still nearby when the NPC
  respawns. If you kill something and leave before it comes back, it is
  hidden for the rest of your session on that world but not remembered.
- NPCs that live in instances are hidden for the session but never remembered.
- After a game update (server restart) the per-world hiding resets, and
  only the remembered spawn tiles carry over.

## Building

Requires JDK 11 or newer to run Gradle.

```
./gradlew build
```

`./gradlew run` starts a development RuneLite client with the plugin loaded.
