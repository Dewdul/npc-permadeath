package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.npcpermadeath.PermadeathTracker.SpawnOutcome;
import com.npcpermadeath.PermadeathTracker.SpawnResult;
import java.util.Collections;
import org.junit.Test;

public class PermadeathTrackerTest
{
	private static final int GOBLIN = 3029;
	private static final SpawnKey TILE = new SpawnKey(GOBLIN, 3245, 3245, 0);

	private final PermadeathTracker tracker = new PermadeathTracker();

	@Test
	public void respawnOfSlainNpcIsHiddenAndSpawnTileLearned()
	{
		tracker.recordMyHit(7);
		assertTrue(tracker.recordDeath(7, GOBLIN, true, true, 100));
		tracker.recordDespawn(7);

		SpawnResult result = tracker.recordSpawn(7, GOBLIN, TILE, true, 130);

		assertEquals(SpawnOutcome.RESPAWN, result.getOutcome());
		assertTrue(result.isLearned());
		assertTrue(tracker.isHidden(7, GOBLIN));
		assertEquals(Collections.singleton(TILE), tracker.getCulledSpawns());
	}

	@Test
	public void untrustedSpawnTileIsNotLearned()
	{
		tracker.recordDeath(7, GOBLIN, true, true, 100);

		SpawnResult result = tracker.recordSpawn(7, GOBLIN, TILE, false, 130);

		assertEquals(SpawnOutcome.RESPAWN, result.getOutcome());
		assertFalse(result.isLearned());
		assertTrue(tracker.getCulledSpawns().isEmpty());
	}

	@Test
	public void someoneElsesKillIsIgnoredWhenOnlyMineIsOn()
	{
		assertFalse(tracker.recordDeath(7, GOBLIN, false, true, 100));
		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(7, GOBLIN, TILE, true, 130).getOutcome());
		assertFalse(tracker.isHidden(7, GOBLIN));
	}

	@Test
	public void someoneElsesKillCountsWhenOnlyMineIsOff()
	{
		assertTrue(tracker.recordDeath(7, GOBLIN, false, false, 100));
		assertEquals(SpawnOutcome.RESPAWN, tracker.recordSpawn(7, GOBLIN, TILE, true, 130).getOutcome());
	}

	@Test
	public void rememberedSpawnTileHidesNpcOnAnotherWorld()
	{
		tracker.setCulledSpawns(Collections.singleton(TILE));

		SpawnResult result = tracker.recordSpawn(42, GOBLIN, TILE, true, 5);

		assertEquals(SpawnOutcome.SPAWN_POINT, result.getOutcome());
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
	public void hiddenNpcStaysHiddenWhenItWalksBackIntoView()
	{
		tracker.recordDeath(7, GOBLIN, true, true, 100);
		tracker.recordSpawn(7, GOBLIN, TILE, true, 130);
		tracker.recordDespawn(7);

		SpawnKey farAway = new SpawnKey(GOBLIN, 3260, 3250, 0);
		SpawnResult result = tracker.recordSpawn(7, GOBLIN, farAway, false, 500);

		assertEquals(SpawnOutcome.REENTER, result.getOutcome());
		assertTrue(tracker.isHidden(7, GOBLIN));
		assertEquals(Collections.singleton(TILE), tracker.getCulledSpawns());
	}

	@Test
	public void reusedIndexForDifferentNpcIsVisible()
	{
		tracker.recordDeath(7, GOBLIN, true, true, 100);
		tracker.recordSpawn(7, GOBLIN, TILE, true, 130);

		SpawnResult result = tracker.recordSpawn(7, 9999, null, false, 200);

		assertEquals(SpawnOutcome.VISIBLE, result.getOutcome());
		assertFalse(tracker.isHidden(7, 9999));
		assertFalse(tracker.isHidden(7, GOBLIN));
	}

	@Test
	public void deathFollowedByDifferentNpcIdIsNotARespawn()
	{
		tracker.recordDeath(7, GOBLIN, true, true, 100);

		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(7, 9999, null, true, 130).getOutcome());
		assertFalse(tracker.isPending(7));
	}

	@Test
	public void staleDeathsArePruned()
	{
		tracker.recordDeath(7, GOBLIN, true, true, 100);
		tracker.prunePending(100 + PermadeathTracker.PENDING_TTL_TICKS + 1);

		assertFalse(tracker.isPending(7));
		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(7, GOBLIN, TILE, true, 7000).getOutcome());
	}

	@Test
	public void clearRuntimeKeepsRememberedSpawns()
	{
		tracker.recordDeath(7, GOBLIN, true, true, 100);
		tracker.recordSpawn(7, GOBLIN, TILE, true, 130);

		tracker.clearRuntime();

		assertFalse(tracker.isHidden(7, GOBLIN));
		assertEquals(Collections.singleton(TILE), tracker.getCulledSpawns());
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
