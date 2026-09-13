package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Map;
import org.junit.Test;

public class SpawnTotalsTest
{
	private static final int LUMBRIDGE = 12850;

	@Test
	public void countsPinsPerRegionAndNamesTheRegion()
	{
		String page = "{{Infobox Monster|name = Goblin}}\n"
			+ "==Locations==\n{{LocTableHead}}\n"
			+ "{{LocLine\n|name = Goblin\n|location = [[Lumbridge]]\n|levels = 2\n|members = n\n|mapID = 0\n|plane = 0\n"
			+ "|x:3245,y:3245|x:3250,y:3230|x:3100,y:3100\n|mtype = pin\n}}\n"
			+ "{{LocLine|name = Goblin|location = Between [[Lumbridge]] and [[Varrock|the city]]|mapID = 0|plane = 0|x:3259,y:3338|mtype = pin}}\n"
			+ "{{LocLine|name = Goblin|location = [[Goblin Cave]]|mapID = 2|x:3245,y:3245}}\n"
			+ "{{LocTableBottom}}";

		SpawnTotals.PageSpawns spawns = SpawnTotals.parse(page);

		assertEquals(Integer.valueOf(2), spawns.byRegion.get(LUMBRIDGE));
		assertEquals(Integer.valueOf(1), spawns.byRegion.get(((3100 >> 6) << 8) | (3100 >> 6)));
		assertEquals(Integer.valueOf(1), spawns.byRegion.get(((3259 >> 6) << 8) | (3338 >> 6)));
		assertEquals(3, spawns.byRegion.size());
		Map<Integer, String> labels = spawns.labels();
		assertEquals("Lumbridge", labels.get(LUMBRIDGE));
		assertEquals("Between Lumbridge and the city", labels.get(((3259 >> 6) << 8) | (3338 >> 6)));
	}

	@Test
	public void mostCommonLabelWinsARegion()
	{
		String page = "{{LocLine|location = [[Lumbridge]]|x:3245,y:3245|x:3246,y:3245}}"
			+ "{{LocLine|location = [[Lumbridge Castle]]|x:3220,y:3220}}";

		assertEquals("Lumbridge", SpawnTotals.parse(page).labels().get(LUMBRIDGE));
	}

	@Test
	public void pageWithoutLocationLinesYieldsNothing()
	{
		SpawnTotals.PageSpawns spawns = SpawnTotals.parse("{{Infobox Monster|name = Rat}} Some prose.");

		assertTrue(spawns.byRegion.isEmpty());
		assertTrue(spawns.labels().isEmpty());
		assertTrue(SpawnTotals.parse("").byRegion.isEmpty());
	}

	@Test
	public void unterminatedTemplateIsIgnored()
	{
		assertTrue(SpawnTotals.parse("{{LocLine|location = [[X]]|x:1,y:1").byRegion.isEmpty());
	}

	@Test
	public void placesSpanRegionsAndBorderKillsGoHome()
	{
		SpawnTotals totals = new SpawnTotals(null, null, null, null);
		String page = "{{LocLine|location = [[Lumbridge]]|x:3245,y:3245|x:3250,y:3230|x:3190,y:3280}}"
			+ "{{LocLine|location = [[Goblin Village]]|x:2957,y:3510}}";
		totals.put("Goblin", SpawnTotals.parse(page), 0);
		int west = ((3190 >> 6) << 8) | (3280 >> 6);
		int east = ((3264 >> 6) << 8) | (3264 >> 6);

		assertEquals("Lumbridge", totals.label("Goblin", LUMBRIDGE));
		assertEquals("Lumbridge", totals.label("Goblin", west));
		// A kill just over the border, where the wiki has no goblins, belongs to Lumbridge.
		assertEquals(LUMBRIDGE, totals.homeRegion("Goblin", east));
		assertEquals(LUMBRIDGE, totals.homeRegion("Goblin", LUMBRIDGE));
		// Unknown NPC: nothing to go on.
		assertEquals(east, totals.homeRegion("Rat", east));
		// Pooled labels name a region for NPCs whose own page has none.
		assertEquals("Lumbridge", totals.label("Rat", LUMBRIDGE));
		assertNull(totals.label("Rat", 1));
	}

	@Test
	public void pipesInsideLinksDoNotSplitParameters()
	{
		assertEquals(java.util.Arrays.asList("a", "[[X|Y]] and {{T|u}}", "x:1,y:2"),
			SpawnTotals.splitParams("a|[[X|Y]] and {{T|u}}|x:1,y:2"));
	}

	@Test
	public void labelsAreCleanedOfMarkup()
	{
		assertEquals("Lumbridge Swamp", SpawnTotals.cleanLabel("[[Lumbridge Swamp]]"));
		assertEquals("the swamp", SpawnTotals.cleanLabel("[[Lumbridge Swamp|the swamp]]"));
		assertEquals("Varrock sewers", SpawnTotals.cleanLabel("'''Varrock''' sewers{{sic}} <!-- note -->"));
		assertNull(SpawnTotals.parse("{{LocLine|x:3245,y:3245}}").labels().get(LUMBRIDGE));
	}
}
