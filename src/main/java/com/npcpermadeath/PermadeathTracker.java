package com.npcpermadeath;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/**
 * Pure bookkeeping for which NPCs should be hidden. Knows nothing about the
 * client so it can be unit tested.
 *
 * <p>Two identities are used. Within one world session the server keeps an
 * NPC's <em>index</em> across death and respawn, so a slain NPC coming back is
 * recognised by index. Indices are not stable across worlds or server restarts,
 * so when a slain NPC is seen respawning its spawn tile is learned as a
 * {@link SpawnKey}, which is what gets persisted.
 */
class PermadeathTracker
{
	/** A death older than this is forgotten if the NPC never respawned in view. */
	static final int PENDING_TTL_TICKS = 6000;

	@Value
	static class PendingDeath
	{
		int npcId;
		int tick;
	}

	enum SpawnOutcome
	{
		VISIBLE,
		/** Hidden because a slain NPC with this index came back. */
		RESPAWN,
		/** Hidden because it appeared on a remembered spawn tile. */
		SPAWN_POINT,
		/** Hidden because an already hidden NPC re-entered view. */
		REENTER
	}

	@Value
	static class SpawnResult
	{
		SpawnOutcome outcome;
		/** True when a new spawn tile was learned and should be persisted. */
		boolean learned;
	}

	private final Set<Integer> damagedByMe = new HashSet<>();
	private final Map<Integer, PendingDeath> pending = new HashMap<>();
	private final Map<Integer, Integer> hiddenByIndex = new HashMap<>();
	private final Set<SpawnKey> culledSpawns = new HashSet<>();

	void recordMyHit(int index)
	{
		damagedByMe.add(index);
	}

	boolean wasDamagedByMe(int index)
	{
		return damagedByMe.contains(index);
	}

	/**
	 * Marks an NPC as slain so its respawn will be hidden.
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

	void recordDespawn(int index)
	{
		damagedByMe.remove(index);
	}

	/**
	 * @param location the tile the NPC appeared on, or null if unknown
	 * @param canLearn whether the tile is trustworthy as a spawn tile
	 */
	SpawnResult recordSpawn(int index, int npcId, SpawnKey location, boolean canLearn, int tick)
	{
		PendingDeath death = pending.remove(index);
		if (death != null && death.getNpcId() == npcId)
		{
			hiddenByIndex.put(index, npcId);
			boolean learned = false;
			if (canLearn && location != null && tick - death.getTick() <= PENDING_TTL_TICKS)
			{
				learned = culledSpawns.add(location);
			}
			return new SpawnResult(SpawnOutcome.RESPAWN, learned);
		}

		if (location != null && culledSpawns.contains(location))
		{
			hiddenByIndex.put(index, npcId);
			return new SpawnResult(SpawnOutcome.SPAWN_POINT, false);
		}

		Integer hiddenId = hiddenByIndex.get(index);
		if (hiddenId != null)
		{
			if (hiddenId == npcId)
			{
				return new SpawnResult(SpawnOutcome.REENTER, false);
			}
			// The server reused this index for a different NPC.
			hiddenByIndex.remove(index);
		}
		return new SpawnResult(SpawnOutcome.VISIBLE, false);
	}

	boolean isHidden(int index, int npcId)
	{
		Integer hiddenId = hiddenByIndex.get(index);
		return hiddenId != null && hiddenId == npcId;
	}

	int hiddenCount()
	{
		return hiddenByIndex.size();
	}

	/** Drops deaths that never resolved into a respawn in view. */
	void prunePending(int tick)
	{
		pending.values().removeIf(d -> tick - d.getTick() > PENDING_TTL_TICKS);
	}

	/** Forgets everything tied to NPC indices; used on world hop and logout. */
	void clearRuntime()
	{
		damagedByMe.clear();
		pending.clear();
		hiddenByIndex.clear();
	}

	void clearAll()
	{
		clearRuntime();
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
}
