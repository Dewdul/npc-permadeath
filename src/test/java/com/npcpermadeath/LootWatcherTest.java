package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import net.runelite.api.Point;
import net.runelite.api.TileItem;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class LootWatcherTest
{
	private static final WorldPoint TILE = new WorldPoint(3245, 3245, 0);
	private static final WorldPoint OTHER_TILE = new WorldPoint(3246, 3245, 0);
	private static final Point SCENE = new Point(45, 45);

	private final LootWatcher loot = new LootWatcher();

	private static TileItem item(int id, int ownership)
	{
		return new FakeTileItem(id, ownership);
	}

	@Test
	public void myLootOnTheDeathTileIsRecognisedBeforeOrAfterTheDespawn()
	{
		loot.onItemSpawned(item(526, TileItem.OWNERSHIP_SELF), TILE, SCENE, 10);

		assertTrue(loot.sawLoot(TILE, 10));
		assertTrue(loot.sawMyLoot(TILE, 10));
		assertTrue(loot.sawMyLoot(TILE, 11));
		assertFalse(loot.sawMyLoot(TILE, 12));
		assertFalse(loot.sawMyLoot(OTHER_TILE, 10));
	}

	@Test
	public void someoneElsesLootIsLootButNotMine()
	{
		loot.onItemSpawned(item(526, TileItem.OWNERSHIP_OTHER), TILE, SCENE, 10);

		assertTrue(loot.sawLoot(TILE, 10));
		assertFalse(loot.sawMyLoot(TILE, 10));
	}

	@Test
	public void oldSpawnsAreForgotten()
	{
		loot.onItemSpawned(item(526, TileItem.OWNERSHIP_SELF), TILE, SCENE, 10);
		loot.tick(14);

		assertFalse(loot.sawLoot(TILE, 11));
	}

	@Test
	public void lootSpawnedAfterAHiddenDeathIsCursed()
	{
		TileItem bones = item(526, TileItem.OWNERSHIP_SELF);
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
		TileItem bones = item(526, TileItem.OWNERSHIP_SELF);
		loot.onItemSpawned(bones, TILE, SCENE, 10);
		loot.armTile(TILE, 10);

		assertTrue(loot.isCursed(bones));
	}

	@Test
	public void unrelatedItemsOnTheTileStayUntouched()
	{
		TileItem old = item(526, TileItem.OWNERSHIP_NONE);
		loot.onItemSpawned(old, TILE, SCENE, 5);
		loot.tick(9);
		loot.armTile(TILE, 10);
		TileItem later = item(526, TileItem.OWNERSHIP_SELF);
		loot.onItemSpawned(later, TILE, SCENE, 13);

		assertFalse(loot.isCursed(old));
		assertFalse(loot.isCursed(later));
		assertEquals(0, loot.cursedCount());
	}

	@Test
	public void despawnedItemsAreForgotten()
	{
		TileItem bones = item(526, TileItem.OWNERSHIP_SELF);
		loot.armTile(TILE, 10);
		loot.onItemSpawned(bones, TILE, SCENE, 10);

		loot.onItemDespawned(bones);

		assertFalse(loot.isCursed(bones));
		assertFalse(loot.blocksMenu(526, 45, 45));
	}
}
