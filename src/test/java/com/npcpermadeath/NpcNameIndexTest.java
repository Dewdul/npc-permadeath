package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.NPCComposition;
import org.junit.Test;

public class NpcNameIndexTest
{
	private static final String[] FIGHTABLE = {null, null, "Attack", null, null};
	private static final String[] PEACEFUL = {"Talk-to", null, null, null, null};

	private final NpcNameIndex index = new NpcNameIndex();

	private static List<String> sorted(String... names)
	{
		return Arrays.asList(names);
	}

	private List<String> suggest(String typed)
	{
		return index.suggest(typed, 8);
	}

	@Test
	public void blankInputSuggestsNothing()
	{
		index.setSeedNames(sorted("Goblin"));
		assertTrue(suggest("").isEmpty());
		assertTrue(suggest("   ").isEmpty());
		assertTrue(suggest(null).isEmpty());
	}

	@Test
	public void prefixMatchesComeBeforeWordStartsBeforeSubstrings()
	{
		index.setSeedNames(sorted("Cave goblin", "Goblin", "Hobgoblin", "Goblin guard", "Moss giant", "Gobbler"));
		// "Goblin" and "Goblin guard" start with it, "Cave goblin" has a word starting with it,
		// "Hobgoblin" only contains it.
		assertEquals(sorted("Goblin", "Goblin guard", "Cave goblin", "Hobgoblin"), suggest("goblin"));
	}

	@Test
	public void tiersAreAlphabeticalWithinThemselves()
	{
		index.setSeedNames(sorted("Zombie rat", "Rat", "Giant rat", "Brat", "Rats nest", "Dungeon rat"));
		assertEquals(sorted("Rat", "Rats nest", "Dungeon rat", "Giant rat", "Zombie rat", "Brat"), suggest("rat"));
	}

	@Test
	public void matchingIgnoresCase()
	{
		index.setSeedNames(sorted("Goblin"));
		assertEquals(sorted("Goblin"), suggest("GOB"));
		assertEquals(sorted("Goblin"), suggest("  gOBlin "));
	}

	@Test
	public void suggestionsAreCappedAtTheLimit()
	{
		index.setSeedNames(sorted("Imp 1", "Imp 2", "Imp 3", "Imp 4"));
		assertEquals(sorted("Imp 1", "Imp 2"), index.suggest("imp", 2));
		assertTrue(index.suggest("imp", 0).isEmpty());
	}

	@Test
	public void limitAppliesAcrossTiersKeepingTheBestFirst()
	{
		index.setSeedNames(sorted("Fire giant", "Giant", "Giant bat", "Moss giant"));
		assertEquals(sorted("Giant", "Giant bat", "Fire giant"), index.suggest("giant", 3));
	}

	@Test
	public void noMatchGivesNothing()
	{
		index.setSeedNames(sorted("Goblin"));
		assertTrue(suggest("dragon").isEmpty());
	}

	@Test
	public void seedNamesAreSortedAndDeduplicatedWithoutCase()
	{
		index.setSeedNames(Arrays.asList("Imp", "Goblin", "goblin", "Cow"));
		assertEquals(3, index.size());
		assertEquals(sorted("Goblin"), suggest("g"));
	}

	@Test
	public void cleanNameStripsTagsAndPlaceholders()
	{
		assertEquals("Goblin", NpcNameIndex.cleanName(" <col=ff0000>Goblin</col> "));
		assertNull(NpcNameIndex.cleanName(null));
		assertNull(NpcNameIndex.cleanName(""));
		assertNull(NpcNameIndex.cleanName("  "));
		assertNull(NpcNameIndex.cleanName("null"));
		assertNull(NpcNameIndex.cleanName("<col=ff0000></col>"));
	}

	@Test
	public void onlyNpcsWithAnAttackActionAreAttackable()
	{
		assertTrue(NpcNameIndex.hasAttack(FIGHTABLE));
		assertTrue(NpcNameIndex.hasAttack(new String[]{"attack"}));
		assertFalse(NpcNameIndex.hasAttack(PEACEFUL));
		assertFalse(NpcNameIndex.hasAttack(null));
	}

	/** Runs the scan to the end over the given definitions, counting the slices it took. */
	private int scan(Map<Integer, NPCComposition> table)
	{
		int slices = 1;
		while (!index.step(table::get))
		{
			slices++;
		}
		return slices;
	}

	@Test
	public void scanKeepsDistinctAttackableNamesAndReplacesTheSeed()
	{
		index.setSeedNames(sorted("Seed only"));
		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(0, new FakeNpcComposition("Hans", PEACEFUL));
		table.put(1, new FakeNpcComposition("Goblin", FIGHTABLE));
		table.put(2, new FakeNpcComposition("<col=00ff00>Goblin</col>", FIGHTABLE));
		table.put(3, new FakeNpcComposition("null", FIGHTABLE));
		table.put(5, new FakeNpcComposition("Cow", FIGHTABLE));
		table.put(9000, new FakeNpcComposition("Giant rat", FIGHTABLE));

		assertFalse(index.isComplete());
		assertEquals(sorted("Seed only"), suggest("seed"));
		scan(table);

		assertTrue(index.isComplete());
		assertEquals(3, index.size());
		assertEquals(sorted("Goblin"), suggest("gob"));
		assertTrue(suggest("hans").isEmpty());
		assertTrue(suggest("seed").isEmpty());
		assertEquals(sorted("Giant rat"), suggest("giant"));
	}

	@Test
	public void scanReachesTheKnownMaximumAcrossLongGaps()
	{
		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(NpcNameIndex.KNOWN_MAX_ID, new FakeNpcComposition("Last imp", FIGHTABLE));

		int slices = scan(table);

		assertEquals(sorted("Last imp"), suggest("last"));
		assertTrue("ids are handled in slices", slices > 1);
	}

	@Test
	public void scanKeepsGoingPastTheKnownMaximumWhileNamesTurnUp()
	{
		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(NpcNameIndex.KNOWN_MAX_ID, new FakeNpcComposition("Last imp", FIGHTABLE));
		table.put(NpcNameIndex.KNOWN_MAX_ID + NpcNameIndex.EMPTY_RUN_LIMIT - 1, new FakeNpcComposition("New boss", FIGHTABLE));

		scan(table);

		assertEquals(sorted("New boss"), suggest("new"));
	}

	@Test
	public void scanStopsAfterAnEmptyRunPastTheKnownMaximum()
	{
		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(NpcNameIndex.KNOWN_MAX_ID, new FakeNpcComposition("Near", FIGHTABLE));
		table.put(NpcNameIndex.KNOWN_MAX_ID + NpcNameIndex.EMPTY_RUN_LIMIT + 5, new FakeNpcComposition("Too far", FIGHTABLE));

		scan(table);

		assertEquals(sorted("Near"), suggest("n"));
		assertTrue(suggest("too").isEmpty());
	}

	@Test
	public void scanSurvivesDefinitionsThatThrow()
	{
		int[] calls = {0};
		boolean done;
		do
		{
			done = index.step(id ->
			{
				calls[0]++;
				if (id == 3)
				{
					throw new IllegalStateException("no such npc");
				}
				return id == 4 ? new FakeNpcComposition("Imp", FIGHTABLE) : null;
			});
		}
		while (!done);

		assertEquals(sorted("Imp"), suggest("imp"));
		assertTrue(calls[0] > NpcNameIndex.KNOWN_MAX_ID);
	}

	@Test
	public void aSliceNeverLooksAtMoreThanTheSliceLimit()
	{
		int[] calls = {0};
		boolean done = index.step(id ->
		{
			calls[0]++;
			return null;
		});

		assertFalse(done);
		assertTrue(calls[0] <= NpcNameIndex.SLICE_IDS);
	}

	@Test
	public void cancellingStopsTheScanAndKeepsTheSeed()
	{
		index.setSeedNames(sorted("Seed only"));
		index.cancel();

		assertTrue(index.step(id -> new FakeNpcComposition("Goblin", FIGHTABLE)));

		assertFalse(index.isComplete());
		assertEquals(Collections.singletonList("Seed only"), suggest("seed"));
	}

	@Test
	public void anEmptyScanKeepsTheSeed()
	{
		index.setSeedNames(sorted("Seed only"));
		scan(new HashMap<>());

		assertFalse(index.isComplete());
		assertEquals(sorted("Seed only"), suggest("seed"));
	}

	@Test
	public void extraNamesAreSuggestedBeforeTheScanAlongsideTheSeed()
	{
		index.setSeedNames(sorted("Giant rat", "Goblin"));
		index.setExtraNames(sorted("Zulrah", "Giant Mole"));

		assertFalse(index.isComplete());
		assertEquals(4, index.size());
		assertEquals(sorted("Zulrah"), suggest("zul"));
		assertEquals(sorted("Giant Mole", "Giant rat"), suggest("giant"));
	}

	@Test
	public void extrasWorkWithoutAnySeed()
	{
		index.setExtraNames(sorted("Vorkath"));

		assertEquals(sorted("Vorkath"), suggest("vork"));
	}

	@Test
	public void afterTheScanAnExtraWithoutAnAttackActionIsStillSuggested()
	{
		index.setExtraNames(sorted("Kalphite Queen", "Callisto"));
		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(1, new FakeNpcComposition("Goblin", FIGHTABLE));
		table.put(2, new FakeNpcComposition("Kalphite Queen", PEACEFUL));
		table.put(3, new FakeNpcComposition("<col=ff0000>Callisto</col>", new String[]{null, null, null, null, null}));
		scan(table);

		assertTrue(index.isComplete());
		assertEquals(sorted("Kalphite Queen"), suggest("kalphite"));
		assertEquals(sorted("Callisto"), suggest("calli"));
		assertEquals(3, index.size());
	}

	@Test
	public void afterTheScanAnExtraThatIsNoNpcIsNoLongerSuggested()
	{
		index.setSeedNames(sorted("Seed only"));
		index.setExtraNames(sorted("Barrows", "Boss kill count", "Zulrah"));
		assertEquals(sorted("Barrows"), suggest("barrows"));

		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(1, new FakeNpcComposition("Goblin", FIGHTABLE));
		table.put(2, new FakeNpcComposition("Zulrah", FIGHTABLE));
		scan(table);

		assertTrue(suggest("barrows").isEmpty());
		assertTrue(suggest("kill count").isEmpty());
		assertEquals(sorted("Zulrah"), suggest("zul"));
		assertEquals(2, index.size());
	}

	@Test
	public void extrasSetAfterTheScanFinishedAreFilteredByTheScan()
	{
		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(1, new FakeNpcComposition("Goblin", FIGHTABLE));
		table.put(2, new FakeNpcComposition("Vorkath", PEACEFUL));
		scan(table);
		assertEquals(1, index.size());
		assertTrue(suggest("vork").isEmpty());

		index.setExtraNames(sorted("Vorkath", "Moons of Peril"));

		assertEquals(sorted("Vorkath"), suggest("vork"));
		assertTrue(suggest("moons").isEmpty());
		assertEquals(2, index.size());
	}

	@Test
	public void changingTheExtrasAfterTheScanReplacesTheEarlierOnes()
	{
		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(1, new FakeNpcComposition("Goblin", FIGHTABLE));
		table.put(2, new FakeNpcComposition("Vorkath", PEACEFUL));
		table.put(3, new FakeNpcComposition("Zulrah", PEACEFUL));
		index.setExtraNames(sorted("Vorkath"));
		scan(table);
		assertEquals(sorted("Vorkath"), suggest("vork"));

		index.setExtraNames(sorted("Zulrah"));

		assertTrue(suggest("vork").isEmpty());
		assertEquals(sorted("Zulrah"), suggest("zul"));
	}

	@Test
	public void changingTheSeedAfterTheScanKeepsTheScannedNames()
	{
		index.setExtraNames(sorted("Vorkath"));
		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(1, new FakeNpcComposition("Goblin", FIGHTABLE));
		table.put(2, new FakeNpcComposition("Vorkath", PEACEFUL));
		scan(table);

		index.setSeedNames(sorted("Seed only"));

		assertEquals(2, index.size());
		assertTrue(suggest("seed").isEmpty());
		assertEquals(sorted("Vorkath"), suggest("vork"));
	}

	@Test
	public void extraNamesKeepTheirDisplayCase()
	{
		index.setExtraNames(sorted("Giant Mole", "TzTok-Jad"));
		assertEquals(sorted("Giant Mole"), suggest("giant m"));
		assertEquals(sorted("TzTok-Jad"), suggest("tztok"));

		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(1, new FakeNpcComposition("giant mole", PEACEFUL));
		table.put(2, new FakeNpcComposition("Goblin", FIGHTABLE));
		scan(table);

		// The extra's spelling is used because no attackable definition supplied one.
		assertEquals(sorted("Giant Mole"), suggest("giant m"));
		assertTrue(suggest("tztok").isEmpty());
	}

	@Test
	public void extrasAndScannedNamesAreMergedWithoutCase()
	{
		index.setSeedNames(sorted("goblin"));
		index.setExtraNames(sorted("zulrah", "ZULRAH", "Goblin"));
		assertEquals(2, index.size());

		Map<Integer, NPCComposition> table = new HashMap<>();
		table.put(1, new FakeNpcComposition("Zulrah", FIGHTABLE));
		table.put(2, new FakeNpcComposition("Cow", FIGHTABLE));
		scan(table);

		assertEquals(2, index.size());
		// Scanned spelling wins over the extra's.
		assertEquals(sorted("Zulrah"), suggest("zul"));
		assertEquals(sorted("Cow"), suggest("c"));
		assertTrue(suggest("goblin").isEmpty());
	}

	@Test
	public void aCancelledScanKeepsTheSeedAndExtras()
	{
		index.setSeedNames(sorted("Seed only"));
		index.setExtraNames(sorted("Barrows"));
		index.cancel();
		assertTrue(index.step(id -> new FakeNpcComposition("Goblin", FIGHTABLE)));

		assertFalse(index.isComplete());
		assertEquals(sorted("Seed only"), suggest("seed"));
		assertEquals(sorted("Barrows"), suggest("barrows"));
	}
}
