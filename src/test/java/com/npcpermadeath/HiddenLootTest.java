package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import net.runelite.api.Point;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class HiddenLootTest
{
	private static final WorldPoint TILE = new WorldPoint(3245, 3245, 0);
	private static final Point SCENE = new Point(45, 45);

	private final HiddenLoot loot = new HiddenLoot();

	private static TileItem item(int id)
	{
		TileItem item = mock(TileItem.class);
		when(item.getId()).thenReturn(id);
		return item;
	}

	@Test
	public void lootSpawnedAfterTheDeathIsCursed()
	{
		TileItem bones = item(526);
		loot.armTile(TILE, 10);
		loot.onItemSpawned(bones, TILE, SCENE, 10);

		assertTrue(loot.isCursed(bones));
		assertTrue(loot.blocksMenu(526, 45, 45));
		assertFalse(loot.blocksMenu(526, 46, 45));
		assertFalse(loot.blocksMenu(527, 45, 45));
	}

	@Test
	public void lootSpawnedJustBeforeTheDespawnEventIsCursed()
	{
		TileItem bones = item(526);
		loot.onItemSpawned(bones, TILE, SCENE, 10);
		loot.armTile(TILE, 10);

		assertTrue(loot.isCursed(bones));
	}

	@Test
	public void unrelatedItemsOnTheTileStayUntouched()
	{
		TileItem old = item(526);
		loot.onItemSpawned(old, TILE, SCENE, 5);
		loot.tick(6);
		loot.tick(7);
		loot.armTile(TILE, 10);
		TileItem later = item(526);
		loot.onItemSpawned(later, TILE, SCENE, 13);

		assertFalse(loot.isCursed(old));
		assertFalse(loot.isCursed(later));
		assertEquals(0, loot.cursedCount());
	}

	@Test
	public void despawnedItemsAreForgotten()
	{
		TileItem bones = item(526);
		loot.armTile(TILE, 10);
		loot.onItemSpawned(bones, TILE, SCENE, 10);

		loot.onItemDespawned(bones);

		assertFalse(loot.isCursed(bones));
		assertFalse(loot.blocksMenu(526, 45, 45));
	}
}
