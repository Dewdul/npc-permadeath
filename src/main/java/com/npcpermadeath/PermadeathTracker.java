package com.npcpermadeath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.Value;

/**
 * Pure bookkeeping for which NPCs should be hidden. Knows nothing about the
 * client so it can be unit tested.
 *
 * <p>The authoritative record is a <em>kill count</em> per NPC type per map
 * region. The plugin then keeps exactly that many NPCs of that type hidden in
 * the region. It prefers the individuals that were actually killed, which it
 * recognises by the server's NPC index (kept across death and respawn on one
 * world), and when those cannot be found, for example on another world, it
 * substitutes other NPCs of the same type as they come into view. Hidden
 * choices are sticky so the same NPCs stay hidden as you move around.
 */
class PermadeathTracker
{
	/** A death older than this is forgotten if the NPC never despawned. */
	static final int PENDING_TTL_TICKS = 6000;
	/** A hidden record not seen for this long is dropped so counts self-heal. */
	static final long RECORD_TTL_MS = 14L * 24 * 60 * 60 * 1000;

	enum SpawnOutcome
	{
		VISIBLE,
		/** Hidden because this is an NPC that was recorded hidden before. */
		EXACT,
		/** Hidden to bring the area up to its kill count. */
		SUBSTITUTE
	}

	/** One hidden NPC on one world. Plain fields so Gson can persist it. */
	static class HiddenNpc
	{
		int index;
		int npcId;
		int world;
		String name;
		int region;
		long lastSeen;

		HiddenNpc()
		{
		}

		HiddenNpc(int index, int npcId, int world, String name, int region, long lastSeen)
		{
			this.index = index;
			this.npcId = npcId;
			this.world = world;
			this.name = name;
			this.region = region;
			this.lastSeen = lastSeen;
		}

		AreaKey area()
		{
			return new AreaKey(name, region);
		}
	}

	/** Everything that is persisted. */
	static class SavedState
	{
		Map<String, Integer> kills = new TreeMap<>();
		List<HiddenNpc> hidden = new ArrayList<>();

		boolean isEmpty()
		{
			return kills.isEmpty() && hidden.isEmpty();
		}
	}

	@Value
	static class PendingDeath
	{
		int npcId;
		String name;
		int region;
		int tick;
	}

	/** Damage dealt to an NPC by the player and by everyone else. */
	static class DamageTally
	{
		int mine;
		int others;
		boolean firstHitMine;
	}

	private final Map<Integer, DamageTally> damage = new HashMap<>();
	private final Map<Integer, PendingDeath> pending = new HashMap<>();

	private final Map<AreaKey, Integer> kills = new HashMap<>();
	/** world -> index -> record */
	private final Map<Integer, Map<Integer, HiddenNpc>> hidden = new HashMap<>();
	/** Hidden records on the current world, counted per area. */
	private final Map<AreaKey, Integer> hiddenHere = new HashMap<>();

	private int currentWorld = -1;
	private boolean dirty;

	void setCurrentWorld(int world)
	{
		currentWorld = world;
		hiddenHere.clear();
		for (HiddenNpc rec : here().values())
		{
			hiddenHere.merge(rec.area(), 1, Integer::sum);
		}
	}

	private Map<Integer, HiddenNpc> here()
	{
		return hidden.computeIfAbsent(currentWorld, w -> new HashMap<>());
	}

	void recordHit(int index, int amount, boolean mine)
	{
		DamageTally tally = damage.get(index);
		if (tally == null)
		{
			tally = new DamageTally();
			tally.firstHitMine = mine;
			damage.put(index, tally);
		}
		if (mine)
		{
			tally.mine += amount;
		}
		else
		{
			tally.others += amount;
		}
	}

	/**
	 * Whether the kill would be the player's by the game's loot rule: most
	 * damage dealt, ties going to whoever hit first.
	 */
	boolean isMyKill(int index)
	{
		DamageTally tally = damage.get(index);
		if (tally == null || tally.mine == 0)
		{
			return false;
		}
		return tally.mine > tally.others || (tally.mine == tally.others && tally.firstHitMine);
	}

	/** Marks an NPC as dying; the death is taken for a decision when it despawns. */
	void recordDeath(int index, int npcId, String name, int region, int tick)
	{
		pending.put(index, new PendingDeath(npcId, name, region, tick));
	}

	boolean isPending(int index)
	{
		return pending.containsKey(index);
	}

	/**
	 * Takes the pending death for a despawning NPC so the caller can decide
	 * whether it counts.
	 *
	 * @return the death, or null if this NPC was not dying
	 */
	PendingDeath takeDeath(int index, int npcId)
	{
		PendingDeath death = pending.get(index);
		if (death == null || death.getNpcId() != npcId)
		{
			return null;
		}
		pending.remove(index);
		return death;
	}

	/** Drops everything in flight for an NPC that left without being counted. */
	void forget(int index)
	{
		damage.remove(index);
		pending.remove(index);
	}

	/**
	 * Counts a kill and hides the NPC.
	 *
	 * @return the area the kill was counted in, or null if it was already hidden
	 */
	AreaKey countKill(int index, int npcId, String name, int region, long now)
	{
		damage.remove(index);
		HiddenNpc existing = here().get(index);
		if (existing != null)
		{
			// It was already one of ours (hidden and killed again, e.g. by a cannon).
			existing.lastSeen = now;
			return null;
		}
		AreaKey key = new AreaKey(name, region);
		kills.merge(key, 1, Integer::sum);
		put(new HiddenNpc(index, npcId, currentWorld, name, region, now));
		dirty = true;
		return key;
	}

	SpawnOutcome recordSpawn(int index, int npcId, String name, int region, long now)
	{
		HiddenNpc rec = here().get(index);
		if (rec != null)
		{
			if (rec.npcId == npcId)
			{
				rec.lastSeen = now;
				return SpawnOutcome.EXACT;
			}
			// Same world, same index, different NPC: the server renumbered.
			remove(index);
			dirty = true;
		}
		AreaKey key = new AreaKey(name, region);
		int wanted = kills.getOrDefault(key, 0);
		if (wanted == 0)
		{
			return SpawnOutcome.VISIBLE;
		}
		if (hiddenHere.getOrDefault(key, 0) < wanted)
		{
			put(new HiddenNpc(index, npcId, currentWorld, name, region, now));
			dirty = true;
			return SpawnOutcome.SUBSTITUTE;
		}
		return SpawnOutcome.VISIBLE;
	}

	private void put(HiddenNpc rec)
	{
		HiddenNpc previous = here().put(rec.index, rec);
		if (previous != null)
		{
			decrement(previous.area());
		}
		hiddenHere.merge(rec.area(), 1, Integer::sum);
	}

	private void remove(int index)
	{
		HiddenNpc previous = here().remove(index);
		if (previous != null)
		{
			decrement(previous.area());
		}
	}

	private void decrement(AreaKey key)
	{
		hiddenHere.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1);
	}

	boolean isHidden(int index, int npcId)
	{
		HiddenNpc rec = here().get(index);
		return rec != null && rec.npcId == npcId;
	}

	/**
	 * Makes a hidden NPC visible again, for example because it is attacking
	 * the player. The kill count is untouched, so the area now has a deficit
	 * that {@link #recordSpawn} fills from the next eligible NPC.
	 *
	 * @return the area that now has a deficit, or null if it was not hidden
	 */
	AreaKey release(int index)
	{
		HiddenNpc rec = here().get(index);
		if (rec == null)
		{
			return null;
		}
		remove(index);
		dirty = true;
		return rec.area();
	}

	boolean hasDeficit(AreaKey key)
	{
		return hiddenHere(key) < kills(key);
	}

	int kills(AreaKey key)
	{
		return kills.getOrDefault(key, 0);
	}

	int hiddenHere(AreaKey key)
	{
		return hiddenHere.getOrDefault(key, 0);
	}

	int totalKills()
	{
		return kills.values().stream().mapToInt(Integer::intValue).sum();
	}

	/** Kill counts for every NPC type in one region, sorted by name. */
	Map<String, Integer> killsInRegion(int region)
	{
		Map<String, Integer> out = new TreeMap<>();
		kills.forEach((key, n) ->
		{
			if (key.getRegion() == region)
			{
				out.put(key.getName(), n);
			}
		});
		return out;
	}

	/** Drops deaths whose NPC never despawned and hidden records gone stale. */
	void prune(int tick, long now)
	{
		pending.values().removeIf(d -> tick - d.getTick() > PENDING_TTL_TICKS);
		for (Map.Entry<Integer, Map<Integer, HiddenNpc>> world : hidden.entrySet())
		{
			Iterator<HiddenNpc> it = world.getValue().values().iterator();
			while (it.hasNext())
			{
				HiddenNpc rec = it.next();
				if (now - rec.lastSeen > RECORD_TTL_MS)
				{
					it.remove();
					dirty = true;
					if (world.getKey() == currentWorld)
					{
						decrement(rec.area());
					}
				}
			}
		}
	}

	/** Forgets in-flight state that only means something on one world. */
	void clearSession()
	{
		damage.clear();
		pending.clear();
	}

	void clearAll()
	{
		clearSession();
		kills.clear();
		hidden.clear();
		hiddenHere.clear();
		dirty = true;
	}

	boolean isDirty()
	{
		return dirty;
	}

	void markSaved()
	{
		dirty = false;
	}

	SavedState toSaved()
	{
		SavedState state = new SavedState();
		kills.forEach((key, n) -> state.kills.put(key.serialize(), n));
		for (Map<Integer, HiddenNpc> world : hidden.values())
		{
			state.hidden.addAll(world.values());
		}
		return state;
	}

	void load(SavedState state)
	{
		kills.clear();
		hidden.clear();
		if (state.kills != null)
		{
			state.kills.forEach((text, n) ->
			{
				AreaKey key = AreaKey.parse(text);
				if (key != null && n != null && n > 0)
				{
					kills.put(key, n);
				}
			});
		}
		if (state.hidden != null)
		{
			for (HiddenNpc rec : state.hidden)
			{
				if (rec != null && rec.name != null)
				{
					hidden.computeIfAbsent(rec.world, w -> new HashMap<>()).put(rec.index, rec);
				}
			}
		}
		dirty = false;
		setCurrentWorld(currentWorld);
	}
}
