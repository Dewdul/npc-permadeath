package com.npcpermadeath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/**
 * Pure bookkeeping for which NPCs should be hidden. Knows nothing about the
 * client so it can be unit tested.
 *
 * <p>The client never sees a permanent NPC identity, only the server's NPC
 * <em>index</em>. The server keeps a slain static NPC's index for its respawn,
 * so a kill is recorded as {@code index + npc id} the moment the dead NPC
 * despawns, whether or not the player sticks around for the respawn. That
 * record is persisted and applied on every later login. Whenever a hidden
 * NPC is actually seen respawning, its spawn tile is learned as well, which
 * is an identity that survives even if indices get reshuffled.
 */
class PermadeathTracker
{
	/** A death older than this is forgotten if the NPC never despawned. */
	static final int PENDING_TTL_TICKS = 6000;

	@Value
	static class PendingDeath
	{
		int npcId;
		int tick;
	}

	/** A slain NPC, remembered by index. */
	@Value
	static class HiddenNpc
	{
		int npcId;
		/** World the kill happened on, so stale entries can be recognised. */
		int world;

		String serialize()
		{
			return npcId + ":" + world;
		}
	}

	enum SpawnOutcome
	{
		VISIBLE,
		/** Hidden because a slain NPC with this index came back. */
		RESPAWN,
		/** Hidden because it appeared on a remembered spawn tile. */
		SPAWN_POINT
	}

	@Value
	static class SpawnResult
	{
		SpawnOutcome outcome;
		/** True when persisted state changed and should be saved. */
		boolean changed;
	}

	private final Set<Integer> damagedByMe = new HashSet<>();
	private final Map<Integer, PendingDeath> pending = new HashMap<>();
	/** Indices whose NPC despawned dead this session and has not been seen since. */
	private final Map<Integer, Integer> awaitingRespawn = new HashMap<>();
	private final Map<Integer, HiddenNpc> hidden = new HashMap<>();
	private final Set<SpawnKey> culledSpawns = new HashSet<>();

	private int currentWorld = -1;
	private boolean acrossWorlds = true;

	void setCurrentWorld(int world)
	{
		currentWorld = world;
	}

	/** Whether kills recorded on other worlds also hide NPCs here. */
	void setAcrossWorlds(boolean acrossWorlds)
	{
		this.acrossWorlds = acrossWorlds;
	}

	void recordMyHit(int index)
	{
		damagedByMe.add(index);
	}

	boolean wasDamagedByMe(int index)
	{
		return damagedByMe.contains(index);
	}

	/**
	 * Marks an NPC as slain. It becomes hidden once it despawns.
	 *
	 * @return true if the death is now being tracked
	 */
	boolean recordDeath(int index, int npcId, boolean killedByMe, boolean requireMine, int tick)
	{
		if (requireMine && !killedByMe)
		{
			return false;
		}
		pending.put(index, new PendingDeath(npcId, tick));
		return true;
	}

	boolean isPending(int index)
	{
		return pending.containsKey(index);
	}

	/**
	 * @return true if the NPC is now hidden and state should be saved
	 */
	boolean recordDespawn(int index, int npcId)
	{
		damagedByMe.remove(index);
		PendingDeath death = pending.remove(index);
		if (death == null || death.getNpcId() != npcId)
		{
			return false;
		}
		hidden.put(index, new HiddenNpc(npcId, currentWorld));
		awaitingRespawn.put(index, death.getTick());
		return true;
	}

	/**
	 * @param location the tile the NPC appeared on, or null if unknown
	 * @param canLearn whether the tile is trustworthy as a spawn tile
	 */
	SpawnResult recordSpawn(int index, int npcId, SpawnKey location, boolean canLearn, int tick)
	{
		Integer deathTick = awaitingRespawn.remove(index);
		HiddenNpc entry = hidden.get(index);
		if (entry != null)
		{
			if (entry.getNpcId() == npcId)
			{
				if (!appliesHere(entry))
				{
					return new SpawnResult(SpawnOutcome.VISIBLE, false);
				}
				boolean learned = false;
				if (deathTick != null && canLearn && location != null && tick - deathTick <= PENDING_TTL_TICKS)
				{
					learned = culledSpawns.add(location);
				}
				return new SpawnResult(SpawnOutcome.RESPAWN, learned);
			}
			if (entry.getWorld() == currentWorld)
			{
				// Same world, same index, different NPC: the server reshuffled.
				hidden.remove(index);
				return checkSpawnPoint(index, npcId, location, true);
			}
		}
		return checkSpawnPoint(index, npcId, location, false);
	}

	private SpawnResult checkSpawnPoint(int index, int npcId, SpawnKey location, boolean changed)
	{
		if (location != null && culledSpawns.contains(location))
		{
			hidden.put(index, new HiddenNpc(npcId, currentWorld));
			return new SpawnResult(SpawnOutcome.SPAWN_POINT, true);
		}
		return new SpawnResult(SpawnOutcome.VISIBLE, changed);
	}

	private boolean appliesHere(HiddenNpc entry)
	{
		return acrossWorlds || entry.getWorld() == currentWorld;
	}

	boolean isHidden(int index, int npcId)
	{
		HiddenNpc entry = hidden.get(index);
		return entry != null && entry.getNpcId() == npcId && appliesHere(entry);
	}

	int hiddenCount()
	{
		return hidden.size();
	}

	/** Drops deaths whose NPC never despawned. */
	void prunePending(int tick)
	{
		pending.values().removeIf(d -> tick - d.getTick() > PENDING_TTL_TICKS);
		awaitingRespawn.values().removeIf(t -> tick - t > PENDING_TTL_TICKS);
	}

	/** Forgets in-flight state that only means something on one world. */
	void clearSession()
	{
		damagedByMe.clear();
		pending.clear();
		awaitingRespawn.clear();
	}

	void clearAll()
	{
		clearSession();
		hidden.clear();
		culledSpawns.clear();
	}

	Set<SpawnKey> getCulledSpawns()
	{
		return Collections.unmodifiableSet(culledSpawns);
	}

	void setCulledSpawns(Collection<SpawnKey> spawns)
	{
		culledSpawns.clear();
		culledSpawns.addAll(spawns);
	}

	/** Serializes hidden NPCs as {@code index:npcId:world} entries, sorted. */
	List<String> serializeHidden()
	{
		List<String> out = new ArrayList<>();
		hidden.forEach((index, entry) -> out.add(index + ":" + entry.serialize()));
		Collections.sort(out);
		return out;
	}

	void loadHidden(Collection<String> entries)
	{
		hidden.clear();
		for (String entry : entries)
		{
			String[] parts = entry.trim().split(":");
			if (parts.length != 3)
			{
				continue;
			}
			try
			{
				hidden.put(Integer.parseInt(parts[0]),
					new HiddenNpc(Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
			}
			catch (NumberFormatException ignored)
			{
				// skip malformed entry
			}
		}
	}
}
