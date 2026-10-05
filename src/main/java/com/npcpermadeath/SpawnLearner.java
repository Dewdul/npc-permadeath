package com.npcpermadeath;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import lombok.Value;

/**
 * Everything known about where NPCs spawn, indexed by map chunk: a bundled
 * seed (the OSRS Wiki's location tables merged with the community spawn
 * dump) plus tiles this client observed itself. A spawn is identified by
 * name (any case) and position alone, so the sources can overlap freely.
 *
 * <p>Observing works by watching NPCs respawn: when an NPC we saw die
 * reappears with the same index close to the player, without the player
 * having teleported, the tile it appears on is its spawn point.
 */
class SpawnLearner
{
	/** How long after a death a respawn is still recognised. */
	static final int RESPAWN_TTL_TICKS = 3000;

	@Value
	static class SpawnTile
	{
		String name;
		int npcId;
		int x;
		int y;
		int plane;

		int region()
		{
			return ((x >> 6) << 8) | (y >> 6);
		}

		String serialize()
		{
			return name + "|" + npcId + "|" + x + "|" + y + "|" + plane;
		}

		/** @return the parsed tile, or null when the text is not a serialized tile */
		static SpawnTile parse(String text)
		{
			String[] p = text.split("\\|");
			if (p.length != 5 || p[0].isEmpty())
			{
				return null;
			}
			try
			{
				return new SpawnTile(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
					Integer.parseInt(p[4]));
			}
			catch (NumberFormatException e)
			{
				return null;
			}
		}
	}

	@Value
	private static class Dead
	{
		int npcId;
		String name;
		int tick;
	}

	/**
	 * What makes a spawn the same spawn for counting: the lower-cased name and
	 * the position, never the NPC id. The seed's wiki-sourced tiles carry id 0
	 * while the same tile observed in game carries the real id; both are one
	 * spawn.
	 */
	@Value
	private static class TileKey
	{
		String lowerName;
		int x;
		int y;
		int plane;
	}

	/** Spelling rank: names the game reported beat the dump's, which beat the wiki's. */
	private static final int RANK_WIKI = 0;
	private static final int RANK_DUMP = 1;
	private static final int RANK_GAME = 2;

	@Value
	private static class Spelling
	{
		String name;
		int rank;
	}

	private final Map<Integer, Dead> awaitingRespawn = new HashMap<>();
	/** Tiles this client observed itself. */
	private final Set<SpawnTile> learned = new HashSet<>();
	/** region -> lower-cased NPC name -> every known spawn tile, from all sources. */
	private final Map<Integer, Map<String, Set<TileKey>>> index = new HashMap<>();
	/** lower-cased NPC name -> the spelling shown to the user. */
	private final Map<String, Spelling> spellings = new HashMap<>();
	private int seedCount;
	private boolean dirty;

	/** Loads the bundled seed: gzipped lines of name|id|x|y|plane. */
	void loadSeed(InputStream gzipped) throws IOException
	{
		try (BufferedReader reader = new BufferedReader(
			new InputStreamReader(new GZIPInputStream(gzipped), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				SpawnTile tile = SpawnTile.parse(line);
				if (tile != null && addToIndex(tile, tile.getNpcId() == 0 ? RANK_WIKI : RANK_DUMP))
				{
					seedCount++;
				}
			}
		}
	}

	private static String lower(String name)
	{
		return name.toLowerCase(Locale.ROOT);
	}

	/** @return whether the tile was not known yet, ignoring its NPC id and the case of its name */
	private boolean addToIndex(SpawnTile tile, int rank)
	{
		String key = lower(tile.getName());
		Spelling known = spellings.get(key);
		// Ties go to the smaller spelling so the result does not depend on load order.
		if (known == null || rank > known.getRank()
			|| (rank == known.getRank() && tile.getName().compareTo(known.getName()) < 0))
		{
			spellings.put(key, new Spelling(tile.getName(), rank));
		}
		return index.computeIfAbsent(tile.region(), r -> new HashMap<>())
			.computeIfAbsent(key, n -> new HashSet<>())
			.add(new TileKey(key, tile.getX(), tile.getY(), tile.getPlane()));
	}

	void noteDeath(int index, int npcId, String name, int tick)
	{
		awaitingRespawn.put(index, new Dead(npcId, name, tick));
	}

	/**
	 * @param trustworthy whether the tile can be taken at face value (close to the player, no teleport, not in an instance)
	 * @return the newly learned tile, or null if nothing was learned
	 */
	SpawnTile noteSpawn(int index, int npcId, String name, int x, int y, int plane, int tick, boolean trustworthy)
	{
		Dead dead = awaitingRespawn.remove(index);
		if (dead == null || !trustworthy || dead.getNpcId() != npcId || tick - dead.getTick() > RESPAWN_TTL_TICKS)
		{
			return null;
		}
		SpawnTile tile = new SpawnTile(name, npcId, x, y, plane);
		if (!learned.add(tile))
		{
			return null;
		}
		addToIndex(tile, RANK_GAME);
		dirty = true;
		return tile;
	}

	/**
	 * Distinct known spawn tiles of the NPC in the chunk, seed and observed.
	 * A tile counts once however many sources know it, whatever NPC id they
	 * give it, and the name is matched without regard to case.
	 */
	int countInRegion(String name, int region)
	{
		Map<String, Set<TileKey>> byName = index.get(region);
		if (byName == null)
		{
			return 0;
		}
		Set<TileKey> tiles = byName.get(lower(name));
		return tiles == null ? 0 : tiles.size();
	}

	/** Every NPC type known to spawn in the chunk, sorted, spelled as the user should see it. */
	Set<String> namesInRegion(int region)
	{
		Map<String, Set<TileKey>> byName = index.get(region);
		return byName == null ? Collections.emptySet() : displayNames(byName.keySet());
	}

	/** Every distinct NPC name known to spawn anywhere, sorted, spelled as the user should see it. */
	Set<String> allNames()
	{
		Set<String> names = new TreeSet<>();
		for (Map<String, Set<TileKey>> byName : index.values())
		{
			names.addAll(displayNames(byName.keySet()));
		}
		return names;
	}

	private Set<String> displayNames(Collection<String> lowerNames)
	{
		Set<String> names = new TreeSet<>();
		for (String key : lowerNames)
		{
			names.add(spellings.get(key).getName());
		}
		return names;
	}

	boolean hasSpawns(int region)
	{
		return index.containsKey(region);
	}

	void prune(int tick)
	{
		awaitingRespawn.values().removeIf(d -> tick - d.getTick() > RESPAWN_TTL_TICKS);
	}

	void clearSession()
	{
		awaitingRespawn.clear();
	}

	int seedCount()
	{
		return seedCount;
	}

	int learnedCount()
	{
		return learned.size();
	}

	boolean isDirty()
	{
		return dirty;
	}

	void markSaved()
	{
		dirty = false;
	}

	List<String> serializeLearned()
	{
		List<String> out = new ArrayList<>();
		learned.forEach(t -> out.add(t.serialize()));
		Collections.sort(out);
		return out;
	}

	void load(Collection<String> learnedEntries)
	{
		learned.clear();
		for (String e : learnedEntries)
		{
			SpawnTile t = SpawnTile.parse(e);
			if (t != null && learned.add(t))
			{
				addToIndex(t, RANK_GAME);
			}
		}
		dirty = false;
	}
}
