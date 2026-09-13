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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import lombok.Value;

/**
 * Everything known about where NPCs spawn, indexed by map chunk: a bundled
 * seed (the community spawn dump), tiles this client observed itself, and
 * tiles other players shared.
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

	private final Map<Integer, Dead> awaitingRespawn = new HashMap<>();
	/** Tiles this client observed itself. */
	private final Set<SpawnTile> learned = new HashSet<>();
	/** Tiles reported by other players. */
	private final Set<SpawnTile> community = new HashSet<>();
	private final Set<SpawnTile> pendingUpload = new HashSet<>();
	/** region -> NPC name -> every known spawn tile, from all sources. */
	private final Map<Integer, Map<String, Set<SpawnTile>>> index = new HashMap<>();
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
				if (tile != null && addToIndex(tile))
				{
					seedCount++;
				}
			}
		}
	}

	private boolean addToIndex(SpawnTile tile)
	{
		return index.computeIfAbsent(tile.region(), r -> new HashMap<>())
			.computeIfAbsent(tile.getName(), n -> new HashSet<>())
			.add(tile);
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
		boolean isNew = addToIndex(tile);
		if (isNew && !community.contains(tile))
		{
			pendingUpload.add(tile);
		}
		dirty = true;
		return tile;
	}

	/** Distinct known spawn tiles of the NPC in the chunk, from every source. */
	int countInRegion(String name, int region)
	{
		Map<String, Set<SpawnTile>> byName = index.get(region);
		if (byName == null)
		{
			return 0;
		}
		Set<SpawnTile> tiles = byName.get(name);
		return tiles == null ? 0 : tiles.size();
	}

	/** Every NPC type known to spawn in the chunk, sorted. */
	Set<String> namesInRegion(int region)
	{
		Map<String, Set<SpawnTile>> byName = index.get(region);
		return byName == null ? Collections.emptySet() : new TreeSet<>(byName.keySet());
	}

	boolean hasSpawns(int region)
	{
		return index.containsKey(region);
	}

	void addCommunity(Collection<SpawnTile> tiles)
	{
		for (SpawnTile tile : tiles)
		{
			community.add(tile);
			addToIndex(tile);
		}
		pendingUpload.removeAll(tiles);
	}

	/** Hands over everything not yet shared; call {@link #uploadFailed} to put them back. */
	List<SpawnTile> takePendingUpload()
	{
		List<SpawnTile> out = new ArrayList<>(pendingUpload);
		pendingUpload.clear();
		dirty = true;
		return out;
	}

	void uploadFailed(Collection<SpawnTile> tiles)
	{
		pendingUpload.addAll(tiles);
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

	int communityCount()
	{
		return community.size();
	}

	int pendingCount()
	{
		return pendingUpload.size();
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

	List<String> serializePending()
	{
		List<String> out = new ArrayList<>();
		pendingUpload.forEach(t -> out.add(t.serialize()));
		Collections.sort(out);
		return out;
	}

	void load(Collection<String> learnedEntries, Collection<String> pendingEntries)
	{
		learned.clear();
		pendingUpload.clear();
		for (String e : learnedEntries)
		{
			SpawnTile t = SpawnTile.parse(e);
			if (t != null && learned.add(t))
			{
				addToIndex(t);
			}
		}
		for (String e : pendingEntries)
		{
			SpawnTile t = SpawnTile.parse(e);
			if (t != null)
			{
				pendingUpload.add(t);
			}
		}
		dirty = false;
	}
}
