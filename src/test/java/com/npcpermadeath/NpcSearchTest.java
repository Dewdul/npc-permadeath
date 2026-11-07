package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class NpcSearchTest
{
	private static final List<String> KNOWN = Arrays.asList(
		"Giant skeleton", "Goblin", "Skeleton", "Skeleton Hellhound", "Skeleton mage");

	@Test
	public void anExactNameMeansOnlyThatNpc()
	{
		assertEquals(Collections.singletonList("Skeleton"), NpcSearch.namesFor("skeleton ", KNOWN));
	}

	@Test
	public void aWildcardMeansEveryMatch()
	{
		assertEquals(Arrays.asList("Skeleton", "Skeleton Hellhound", "Skeleton mage"), NpcSearch.namesFor("Skeleton*", KNOWN));
		assertEquals(Arrays.asList("Giant skeleton", "Skeleton", "Skeleton Hellhound", "Skeleton mage"),
			NpcSearch.namesFor("*skeleton*", KNOWN));
		assertTrue(NpcSearch.namesFor("Dragon*", KNOWN).isEmpty());
	}

	@Test
	public void partOfANameMeansEveryNameHoldingIt()
	{
		assertEquals(Arrays.asList("Giant skeleton", "Skeleton", "Skeleton Hellhound", "Skeleton mage"),
			NpcSearch.namesFor("skel", KNOWN));
	}

	@Test
	public void anUnknownNameIsSearchedAsTyped()
	{
		assertEquals(Collections.singletonList("Zombie pirate"), NpcSearch.namesFor("Zombie pirate", KNOWN));
		assertTrue(NpcSearch.namesFor("  ", KNOWN).isEmpty());
		assertTrue(NpcSearch.namesFor(null, KNOWN).isEmpty());
	}

	private static PermadeathPanel.Row row(String name, int region, String heading, int kills)
	{
		return new PermadeathPanel.Row(new AreaKey(name, region), heading, kills, 10, 0);
	}

	@Test
	public void totalsSumEveryChunkOfEachName()
	{
		List<PermadeathPanel.Row> rows = Arrays.asList(
			new PermadeathPanel.Row(new AreaKey("Skeleton", 1), "A (1, 1)", 3, 8, 0),
			new PermadeathPanel.Row(new AreaKey("Skeleton", 2), "B (2, 2)", 1, null, 0),
			new PermadeathPanel.Row(new AreaKey("Skeleton", 3), "C (3, 3)", 0, 4, 0),
			new PermadeathPanel.Row(new AreaKey("Skeleton mage", 1), "A (1, 1)", 0, null, 0));

		List<PermadeathPanel.Total> totals = NpcSearch.totals(
			Arrays.asList("Skeleton", "Skeleton mage", "Goblin"), rows);

		assertEquals(2, totals.size());
		assertEquals(new PermadeathPanel.Total("Skeleton", 4, 12, 3, 1), totals.get(0));
		// no chunk has a count: the total is unknown, not zero
		assertEquals(new PermadeathPanel.Total("Skeleton mage", 0, null, 1, 1), totals.get(1));
		assertEquals(3, NpcSearch.chunks(rows));
		assertEquals(PermadeathPanel.Progress.STARTED, totals.get(0).getProgress());
		assertEquals(PermadeathPanel.Progress.CLEARED, new PermadeathPanel.Total("Rat", 5, 5, 1, 0).getProgress());
		// a chunk without a count could hold more, so it is never cleared
		assertEquals(PermadeathPanel.Progress.STARTED, new PermadeathPanel.Total("Rat", 5, 5, 2, 1).getProgress());
	}

	@Test
	public void chunksWithKillsComeFirstThenByPlace()
	{
		List<PermadeathPanel.Row> rows = Arrays.asList(
			row("Skeleton", 1, "Varrock Sewers (1, 1)", 0),
			row("Skeleton mage", 2, "Edgeville Dungeon (2, 2)", 1),
			row("Skeleton", 3, "Asgarnia (3, 3)", 0),
			row("Skeleton", 2, "Edgeville Dungeon (2, 2)", 2),
			row("Skeleton", 4, "Crandor (4, 4)", 5));

		List<String> order = NpcSearch.order(rows).stream()
			.map(r -> r.getArea().getRegion() + " " + r.getArea().getName())
			.collect(Collectors.toList());

		// Crandor has 5 kills, Edgeville 3 (its two types stay together), then the rest by place
		assertEquals(Arrays.asList("4 Skeleton", "2 Skeleton", "2 Skeleton mage", "3 Skeleton", "1 Skeleton"), order);
	}
}
