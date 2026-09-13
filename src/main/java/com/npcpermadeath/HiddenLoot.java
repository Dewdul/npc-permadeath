package com.npcpermadeath;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import lombok.Value;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;

/**
 * Tracks ground items dropped by hidden NPCs so they can be hidden and made
 * untakeable without touching other loot on the same tile.
 *
 * <p>Loot appears on the NPC's tile on the same tick the dead NPC despawns,
 * sometimes before and sometimes after the despawn event, so recent item
 * spawns are buffered and a tile stays armed for one more tick.
 */
class HiddenLoot
{
	private static final int WINDOW_TICKS = 1;

	@Value
	private static class Spawn
	{
		TileItem item;
		WorldPoint tile;
		Point scene;
		int tick;
	}

	private final List<Spawn> recent = new ArrayList<>();
	private final Map<WorldPoint, Integer> armedTiles = new java.util.HashMap<>();
	/** Cursed items and the scene coordinates of their tile. */
	private final Map<TileItem, Point> cursed = new IdentityHashMap<>();

	/** A hidden NPC died on this tile; anything that drops there now is its loot. */
	void armTile(WorldPoint tile, int tick)
	{
		if (tile == null)
		{
			return;
		}
		armedTiles.put(tile, tick);
		for (Spawn spawn : recent)
		{
			if (spawn.getTile().equals(tile) && Math.abs(spawn.getTick() - tick) <= WINDOW_TICKS)
			{
				cursed.put(spawn.getItem(), spawn.getScene());
			}
		}
	}

	void onItemSpawned(TileItem item, WorldPoint tile, Point scene, int tick)
	{
		if (tile == null || scene == null)
		{
			return;
		}
		recent.add(new Spawn(item, tile, scene, tick));
		Integer armed = armedTiles.get(tile);
		if (armed != null && tick - armed <= WINDOW_TICKS)
		{
			cursed.put(item, scene);
		}
	}

	void onItemDespawned(TileItem item)
	{
		cursed.remove(item);
	}

	boolean isCursed(Renderable renderable)
	{
		return renderable instanceof TileItem && cursed.containsKey(renderable);
	}

	/** Whether a ground-item menu entry for this item on this scene tile should be removed. */
	boolean blocksMenu(int itemId, int sceneX, int sceneY)
	{
		for (Map.Entry<TileItem, Point> entry : cursed.entrySet())
		{
			Point p = entry.getValue();
			if (entry.getKey().getId() == itemId && p.getX() == sceneX && p.getY() == sceneY)
			{
				return true;
			}
		}
		return false;
	}

	int cursedCount()
	{
		return cursed.size();
	}

	void tick(int tick)
	{
		Iterator<Spawn> it = recent.iterator();
		while (it.hasNext())
		{
			if (tick - it.next().getTick() > WINDOW_TICKS)
			{
				it.remove();
			}
		}
		armedTiles.values().removeIf(t -> tick - t > WINDOW_TICKS);
	}

	void clear()
	{
		recent.clear();
		armedTiles.clear();
		cursed.clear();
	}
}
