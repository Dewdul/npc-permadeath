package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.npcpermadeath.PermadeathTracker.SpawnOutcome;
import org.junit.Before;
import org.junit.Test;

public class PermadeathTrackerTest
{
	private static final int GOBLIN = 3029;
	private static final String NAME = "Goblin";
	private static final int LUMBRIDGE = 12850;
	private static final int WORLD = 301;
	private static final AreaKey AREA = new AreaKey(NAME, LUMBRIDGE);
	private static final long NOW = 1_700_000_000_000L;

	private final PermadeathTracker tracker = new PermadeathTracker();

	@Before
	public void setUp()
	{
		tracker.setCurrentWorld(WORLD);
	}

	private void kill(int index)
	{
		tracker.recordHit(index, 5, true);
		tracker.recordDeath(index, GOBLIN, NAME, LUMBRIDGE, 100);
		PermadeathTracker.PendingDeath death = tracker.takeDeath(index, GOBLIN);
		assertEquals(NAME, death.getName());
		assertEquals(AREA, tracker.countKill(index, GOBLIN, death.getName(), death.getRegion(), NOW));
	}

	@Test
	public void killIsMineOnlyWhenIDealtTheMostDamage()
	{
		assertFalse(tracker.isMyKill(7));

		tracker.recordHit(7, 3, true);
		tracker.recordHit(7, 2, false);
		assertTrue(tracker.isMyKill(7));

		tracker.recordHit(7, 4, false);
		assertFalse(tracker.isMyKill(7));
	}

	@Test
	public void tiedDamageGoesToWhoeverHitFirst()
	{
		tracker.recordHit(7, 4, true);
		tracker.recordHit(7, 4, false);
		assertTrue(tracker.isMyKill(7));

		tracker.recordHit(8, 4, false);
		tracker.recordHit(8, 4, true);
		assertFalse(tracker.isMyKill(8));
	}

	@Test
	public void zeroDamageFromMeIsNotAKill()
	{
		tracker.recordHit(7, 0, true);
		assertFalse(tracker.isMyKill(7));
	}

	@Test
	public void damageTallyResetsWhenTheKillIsCounted()
	{
		kill(7);

		assertFalse(tracker.isMyKill(7));
	}

	@Test
	public void killIsCountedAndTheNpcHiddenWhenItDespawns()
	{
		kill(7);

		assertEquals(1, tracker.kills(AREA));
		assertTrue(tracker.isHidden(7, GOBLIN));
		assertEquals(SpawnOutcome.EXACT, tracker.recordSpawn(7, GOBLIN, NAME, LUMBRIDGE, NOW));
		assertTrue(tracker.isDirty());
	}

	@Test
	public void deathWithoutDespawnCountsNothing()
	{
		tracker.recordDeath(7, GOBLIN, NAME, LUMBRIDGE, 100);

		assertTrue(tracker.isPending(7));
		assertEquals(0, tracker.kills(AREA));
		assertFalse(tracker.isHidden(7, GOBLIN));
	}

	@Test
	public void takeDeathOnlyMatchesTheDyingNpc()
	{
		tracker.recordDeath(7, GOBLIN, NAME, LUMBRIDGE, 100);

		assertNull(tracker.takeDeath(7, 9999));
		assertNull(tracker.takeDeath(8, GOBLIN));
		assertEquals(GOBLIN, tracker.takeDeath(7, GOBLIN).getNpcId());
		assertNull(tracker.takeDeath(7, GOBLIN));
	}

	@Test
	public void forgetDropsDamageAndPendingDeath()
	{
		tracker.recordHit(7, 9, true);
		tracker.recordDeath(7, GOBLIN, NAME, LUMBRIDGE, 100);

		tracker.forget(7);

		assertFalse(tracker.isMyKill(7));
		assertFalse(tracker.isPending(7));
	}

	@Test
	public void killingAnAlreadyHiddenNpcDoesNotDoubleCount()
	{
		kill(7);

		assertNull(tracker.countKill(7, GOBLIN, NAME, LUMBRIDGE, NOW + 1));
		assertEquals(1, tracker.kills(AREA));
	}

	@Test
	public void otherNpcsAreSubstitutedUpToTheKillCountOnAnotherWorld()
	{
		kill(7);
		kill(8);
		tracker.setCurrentWorld(WORLD + 1);

		assertEquals(SpawnOutcome.SUBSTITUTE, tracker.recordSpawn(50, GOBLIN, NAME, LUMBRIDGE, NOW));
		assertEquals(SpawnOutcome.SUBSTITUTE, tracker.recordSpawn(51, GOBLIN, NAME, LUMBRIDGE, NOW));
		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(52, GOBLIN, NAME, LUMBRIDGE, NOW));
		assertTrue(tracker.isHidden(50, GOBLIN));
		assertFalse(tracker.isHidden(52, GOBLIN));
		assertEquals(2, tracker.hiddenHere(AREA));
	}

	@Test
	public void substitutesAreStickyWhenTheyComeBackIntoView()
	{
		kill(7);
		tracker.setCurrentWorld(WORLD + 1);
		tracker.recordSpawn(50, GOBLIN, NAME, LUMBRIDGE, NOW);

		assertEquals(SpawnOutcome.EXACT, tracker.recordSpawn(50, GOBLIN, NAME, LUMBRIDGE, NOW + 1));
		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(51, GOBLIN, NAME, LUMBRIDGE, NOW + 1));
	}

	@Test
	public void exactKillsOnTheHomeWorldLeaveNoDeficit()
	{
		kill(7);

		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(8, GOBLIN, NAME, LUMBRIDGE, NOW));
	}

	@Test
	public void killsInOneRegionDoNotHideNpcsInAnother()
	{
		kill(7);

		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(8, GOBLIN, NAME, LUMBRIDGE + 1, NOW));
	}

	@Test
	public void killsOfOneTypeDoNotHideAnotherType()
	{
		kill(7);

		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(8, 2, "Cow", LUMBRIDGE, NOW));
	}

	@Test
	public void renumberedIndexIsReleasedAndBackfilled()
	{
		kill(7);

		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(7, 2, "Cow", LUMBRIDGE, NOW));
		assertFalse(tracker.isHidden(7, GOBLIN));
		assertEquals(0, tracker.hiddenHere(AREA));
		assertEquals(SpawnOutcome.SUBSTITUTE, tracker.recordSpawn(9, GOBLIN, NAME, LUMBRIDGE, NOW));
	}

	@Test
	public void staleRecordsArePrunedSoTheCountCanBeRefilled()
	{
		kill(7);

		tracker.prune(0, NOW + PermadeathTracker.RECORD_TTL_MS + 1);

		assertFalse(tracker.isHidden(7, GOBLIN));
		assertEquals(1, tracker.kills(AREA));
		assertEquals(SpawnOutcome.SUBSTITUTE, tracker.recordSpawn(9, GOBLIN, NAME, LUMBRIDGE, NOW));
	}

	@Test
	public void seeingARecordRefreshesIt()
	{
		kill(7);
		long later = NOW + PermadeathTracker.RECORD_TTL_MS - 1;
		tracker.recordSpawn(7, GOBLIN, NAME, LUMBRIDGE, later);

		tracker.prune(0, later + 10);

		assertTrue(tracker.isHidden(7, GOBLIN));
	}

	@Test
	public void stalePendingDeathsArePruned()
	{
		tracker.recordDeath(7, GOBLIN, NAME, LUMBRIDGE, 100);
		tracker.prune(100 + PermadeathTracker.PENDING_TTL_TICKS + 1, NOW);

		assertFalse(tracker.isPending(7));
		assertNull(tracker.takeDeath(7, GOBLIN));
	}

	@Test
	public void stateRoundTripsThroughSavedState()
	{
		kill(7);
		tracker.setCurrentWorld(WORLD + 1);
		tracker.recordSpawn(50, GOBLIN, NAME, LUMBRIDGE, NOW);
		PermadeathTracker.SavedState saved = tracker.toSaved();
		assertEquals(Integer.valueOf(1), saved.kills.get("12850/Goblin"));
		assertEquals(2, saved.hidden.size());

		PermadeathTracker fresh = new PermadeathTracker();
		fresh.setCurrentWorld(WORLD);
		fresh.load(saved);

		assertTrue(fresh.isHidden(7, GOBLIN));
		assertFalse(fresh.isHidden(50, GOBLIN));
		assertEquals(1, fresh.hiddenHere(AREA));
		fresh.setCurrentWorld(WORLD + 1);
		assertTrue(fresh.isHidden(50, GOBLIN));
		assertFalse(fresh.isDirty());
	}

	@Test
	public void loadSkipsMalformedEntries()
	{
		PermadeathTracker.SavedState saved = new PermadeathTracker.SavedState();
		saved.kills.put("garbage", 3);
		saved.kills.put("12850/Goblin", 0);
		saved.kills.put("12851/Cow", 2);
		saved.hidden.add(new PermadeathTracker.HiddenNpc(1, 2, WORLD, null, 12851, NOW));

		tracker.load(saved);

		assertEquals(2, tracker.totalKills());
		assertEquals(0, tracker.hiddenHere(new AreaKey("Cow", 12851)));
	}

	@Test
	public void releasingAHiddenNpcLeavesADeficitToBackfill()
	{
		kill(7);

		assertEquals(AREA, tracker.release(7));

		assertFalse(tracker.isHidden(7, GOBLIN));
		assertTrue(tracker.hasDeficit(AREA));
		assertEquals(1, tracker.kills(AREA));
		assertEquals(SpawnOutcome.SUBSTITUTE, tracker.recordSpawn(9, GOBLIN, NAME, LUMBRIDGE, NOW));
		assertFalse(tracker.hasDeficit(AREA));
		assertNull(tracker.release(7));
	}

	@Test
	public void rehomingMovesKillsAndHiddenRecordsToTheOtherChunk()
	{
		AreaKey border = new AreaKey(NAME, LUMBRIDGE + 1);
		tracker.countKill(7, GOBLIN, NAME, LUMBRIDGE + 1, NOW);
		tracker.countKill(8, GOBLIN, NAME, LUMBRIDGE, NOW);
		tracker.markSaved();

		tracker.rehome(border, LUMBRIDGE);

		assertEquals(0, tracker.kills(border));
		assertEquals(2, tracker.kills(AREA));
		assertEquals(2, tracker.hiddenHere(AREA));
		assertTrue(tracker.isHidden(7, GOBLIN));
		assertTrue(tracker.isDirty());
		assertEquals(SpawnOutcome.VISIBLE, tracker.recordSpawn(9, GOBLIN, NAME, LUMBRIDGE, NOW));
	}

	@Test
	public void forgettingAnAreaBringsItsNpcsBackEverywhere()
	{
		kill(7);
		tracker.countKill(8, 2, "Cow", LUMBRIDGE, NOW);
		tracker.setCurrentWorld(WORLD + 1);
		tracker.recordSpawn(50, GOBLIN, NAME, LUMBRIDGE, NOW);
		tracker.markSaved();

		tracker.forgetArea(AREA);

		assertFalse(tracker.isHidden(50, GOBLIN));
		assertEquals(0, tracker.kills(AREA));
		assertEquals(0, tracker.hiddenHere(AREA));
		tracker.setCurrentWorld(WORLD);
		assertFalse(tracker.isHidden(7, GOBLIN));
		assertTrue(tracker.isHidden(8, 2));
		assertEquals(1, tracker.killsByArea().size());
		assertTrue(tracker.isDirty());
	}

	@Test
	public void killsInRegionListsOnlyThatRegion()
	{
		kill(7);
		tracker.countKill(8, 2, "Cow", LUMBRIDGE + 1, NOW);

		assertEquals(1, tracker.killsInRegion(LUMBRIDGE).size());
		assertEquals(Integer.valueOf(1), tracker.killsInRegion(LUMBRIDGE).get(NAME));
	}

	@Test
	public void clearAllForgetsEverything()
	{
		kill(7);
		tracker.markSaved();

		tracker.clearAll();

		assertFalse(tracker.isHidden(7, GOBLIN));
		assertEquals(0, tracker.totalKills());
		assertTrue(tracker.isDirty());
		assertTrue(tracker.toSaved().isEmpty());
	}

	@Test
	public void areaKeyRoundTrips()
	{
		assertEquals(AREA, AreaKey.parse(AREA.serialize()));
		assertEquals(new AreaKey("Monk of Zamorak", 1), AreaKey.parse("1/Monk of Zamorak"));
		assertNull(AreaKey.parse("garbage"));
		assertNull(AreaKey.parse("12850/"));
		assertNull(AreaKey.parse("x/Goblin"));
	}
}
