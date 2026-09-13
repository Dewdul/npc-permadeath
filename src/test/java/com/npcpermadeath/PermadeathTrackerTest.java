package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.npcpermadeath.PermadeathTracker.SpawnOutcome;
import com.npcpermadeath.PermadeathTracker.SpawnResult;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;

public class PermadeathTrackerTest
{
	private static final int GOBLIN = 3029;
	private static final int WORLD = 301;
	private static final SpawnKey TILE = new SpawnKey(GOBLIN, 3245, 3245, 0);

	private final PermadeathTracker tracker = new PermadeathTracker();

	@Before
	public void setUp()
	{
		tracker.setCurrentWorld(WORLD);
	}

	private void killAndDespawn(int index)
	{
		tracker.recordMyHit(index);
		assertTrue(tracker.recordDeath(index, GOBLIN, true, true, 100));
		assertTrue(tracker.recordDespawn(index, GOBLIN));
	}

	@Test
	public void npcIsHiddenAsSoonAsItDespawnsDead()
	{
		killAndDespawn(7);

		assertTrue(tracker.isHidden(7, GOBLIN));
		assertEquals(Collections.singletonList("7:3029:301"), tracker.serializeHidden());
	}

	@Test
	public void deathWithoutDespawnDoesNotHideYet()
	{
		tracker.recordDeath(7, GOBLIN, true, true, 100);

		assertFalse(tracker.isHidden(7, GOBLIN));
		assertTrue(tracker.isPending(7));
	}

	@Test
	public void respawnIsHiddenAndSpawnTileLearned()
	{
		killAndDespawn(7);

		SpawnResult result = tracker.recordSpawn(7, GOBLIN, TILE, true, 130);

		assertEquals(SpawnOutcome.RESPAWN, result.getOutcome());
		assertTrue(result.isChanged());
		assertEquals(Collections.singleton(TILE), tracker.getCulledSpawns());
	}

	@Test
	public void untrustedSpawnTileIsNotLearned()
	{
		killAndDespawn(7);

		SpawnResult result = tracker.recordSpawn(7, GOBLIN, TILE, false, 130);

		assertEquals(SpawnOutcome.RESPAWN, result.getOutcome());
		assertFalse(result.isChanged());
		assertTrue(tracker.getCulledSpawns().isEmpty());
	}

	@Test
	public void hiddenNpcWalkingBackIntoViewLaterDoesNotLearnItsTile()
	{
		killAndDespawn(7);
		tracker.recordSpawn(7, GOBLIN, TILE, false, 130);
		tracker.recordDespawn(7, GOBLIN);

		SpawnResult result = tracker.recordSpawn(7, GOBLIN, new SpawnKey(GOBLIN, 3260, 3250, 0), true, 500);

		assertEquals(SpawnOutcome.RESPAWN, result.getOutcome());
		assertFalse(result.isChanged());
		assertTrue(tracker.getCulledSpawns().isEmpty());
	}

	@Test
	public void someoneElsesKillIsIgnoredWhenOnlyMineIsOn()
	{
		assertFalse(tracker.recordDeath(7, GOBLIN, false, true, 100));
		assertFalse(tracker.recordDespawn(7, GOBLIN));
		assertFalse(tracker.isHidden(7, GOBLIN));
	}

	@Test
	public void someoneElsesKillCountsWhenOnlyMineIsOff()
	{
		assertTrue(tracker.recordDeath(7, GOBLIN, false, false, 100));
		assertTrue(tracker.recordDespawn(7, GOBLIN));
		assertTrue(tracker.isHidden(7, GOBLIN));
	}

	@Test
	public void killSurvivesLogoutAndHop()
	{
		killAndDespawn(7);
		PermadeathTracker fresh = new PermadeathTracker();
		fresh.loadHidden(tracker.serializeHidden());

		fresh.setCurrentWorld(WORLD + 1);
		assertTrue(fresh.isHidden(7, GOBLIN));
		fresh.setAcrossWorlds(false);
		assertFalse(fresh.isHidden(7, GOBLIN));
		fresh.setCurrentWorld(WORLD);
		assertTrue(fresh.isHidden(7, GOBLIN));
	}

	@Test
	public void differentNpcAtSameIndexOnSameWorldClearsStaleEntry()
	{
		killAndDespawn(7);

		SpawnResult result = tracker.recordSpawn(7, 9999, null, false, 200);

		assertEquals(SpawnOutcome.VISIBLE, result.getOutcome());
		assertTrue(result.isChanged());
		assertFalse(tracker.isHidden(7, 9999));
		assertFalse(tracker.isHidden(7, GOBLIN));
		assertTrue(tracker.serializeHidden().isEmpty());
	}

	@Test
	public void differentNpcAtSameIndexOnOtherWorldKeepsEntry()
	{
		killAndDespawn(7);
		tracker.setCurrentWorld(WORLD + 1);

		SpawnResult result = tracker.recordSpawn(7, 9999, null, false, 200);

		assertEquals(SpawnOutcome.VISIBLE, result.getOutcome());
		assertFalse(result.isChanged());
		assertFalse(tracker.isHidden(7, 9999));
		assertEquals(1, tracker.hiddenCount());
	}

	@Test
	public void rememberedSpawnTileHidesNewIndex()
	{
		tracker.setCulledSpawns(Collections.singleton(TILE));

		SpawnResult result = tracker.recordSpawn(42, GOBLIN, TILE, true, 5);

		assertEquals(SpawnOutcome.SPAWN_POINT, result.getOutcome());
		assertTrue(result.isChanged());
		assertTrue(tracker.isHidden(42, GOBLIN));
	}

	@Test
	public void differentNpcOnRememberedTileStaysVisible()
	{
		tracker.setCulledSpawns(Collections.singleton(TILE));
		SpawnKey otherNpcSameTile = new SpawnKey(GOBLIN + 1, TILE.getX(), TILE.getY(), TILE.getPlane());

		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(42, GOBLIN + 1, otherNpcSameTile, true, 5).getOutcome());
	}

	@Test
	public void despawnOfDifferentNpcIdDoesNotHide()
	{
		tracker.recordDeath(7, GOBLIN, true, true, 100);

		assertFalse(tracker.recordDespawn(7, 9999));
		assertFalse(tracker.isHidden(7, GOBLIN));
	}

	@Test
	public void staleDeathsArePruned()
	{
		tracker.recordDeath(7, GOBLIN, true, true, 100);
		tracker.prunePending(100 + PermadeathTracker.PENDING_TTL_TICKS + 1);

		assertFalse(tracker.isPending(7));
		assertFalse(tracker.recordDespawn(7, GOBLIN));
	}

	@Test
	public void clearSessionKeepsHiddenAndSpawns()
	{
		killAndDespawn(7);
		tracker.recordSpawn(7, GOBLIN, TILE, true, 130);

		tracker.clearSession();

		assertTrue(tracker.isHidden(7, GOBLIN));
		assertEquals(Collections.singleton(TILE), tracker.getCulledSpawns());
	}

	@Test
	public void clearAllForgetsEverything()
	{
		killAndDespawn(7);
		tracker.recordSpawn(7, GOBLIN, TILE, true, 130);

		tracker.clearAll();

		assertFalse(tracker.isHidden(7, GOBLIN));
		assertTrue(tracker.getCulledSpawns().isEmpty());
	}

	@Test
	public void loadHiddenSkipsMalformedEntries()
	{
		tracker.loadHidden(Arrays.asList("7:3029:301", "garbage", "1:2", "a:b:c", "8:3030:302"));

		assertEquals(2, tracker.hiddenCount());
		assertTrue(tracker.isHidden(8, 3030));
	}

	@Test
	public void spawnKeyRoundTrips()
	{
		assertEquals(TILE, SpawnKey.parse(TILE.serialize()));
		assertNull(SpawnKey.parse("garbage"));
		assertNull(SpawnKey.parse("1:2:3"));
		assertNull(SpawnKey.parse("a:b:c:d"));
	}
}
