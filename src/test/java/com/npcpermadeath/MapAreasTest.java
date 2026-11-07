package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.Point;
import java.awt.Rectangle;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class MapAreasTest
{
	private static final String TABLE = "# comment\n"
		+ "area 0 1 Gielinor Surface\n"
		+ "3200 3200 64 64 0 0\n"
		+ "area 25 0 Waterbirth Dungeon\n"
		// a 16x8 piece drawn 292 tiles west and 5000 tiles north of its real tiles,
		// whose real tiles straddle two chunks (x 2940..2955 -> chunks 45 and 46)
		+ "2648 9600 16 8 292 -5000\n"
		+ "this line is damaged\n"
		+ "1 2 3\n"
		+ "area 20 0 Taverley Underground\n"
		// the same real chunk drawn in place and again 192 tiles south
		+ "2880 9792 64 64 0 0\n"
		+ "2880 9600 64 64 0 192\n"
		// a ladder near the north edge of the chunk and a trapdoor near its south edge
		+ "exit 2900 9850 2900 3450\n"
		+ "exit 2900 9795 2884 3397\n"
		+ "exit broken line\n";

	private static MapAreas parse(String text) throws IOException
	{
		return MapAreas.parse(new BufferedReader(new StringReader(text)));
	}

	@Test
	public void mapTilesLeadToTheChunkDrawnThere() throws IOException
	{
		MapAreas.MapArea waterbirth = parse(TABLE).named("Waterbirth Dungeon");

		assertEquals(MapAreas.regionOf(2940, 4600), waterbirth.regionAt(2648, 9600));
		assertEquals(MapAreas.regionOf(2955, 4607), waterbirth.regionAt(2663, 9607));
		assertEquals(-1, waterbirth.regionAt(2647, 9600));
		assertEquals(-1, waterbirth.regionAt(3245, 3245));
	}

	@Test
	public void aChunkSplitByAPieceGetsOnlyItsShare() throws IOException
	{
		MapAreas.MapArea waterbirth = parse(TABLE).named("waterbirth dungeon");
		int west = MapAreas.regionOf(2944 - 1, 4600);
		int east = MapAreas.regionOf(2944, 4600);

		// x 2940..2943 is chunk 45, x 2944..2955 is chunk 46: a 4- and a 12-tile strip
		assertEquals(2, waterbirth.footprints().size());
		assertEquals(new Rectangle(2648, 9600, 4, 8), waterbirth.footprints().get(west).bounds);
		assertEquals(new Rectangle(2652, 9600, 12, 8), waterbirth.footprints().get(east).bounds);
		assertTrue(waterbirth.shows(east));
		assertFalse(waterbirth.shows(MapAreas.regionOf(3245, 3245)));
	}

	@Test
	public void aChunkDrawnTwiceHasTwoParts() throws IOException
	{
		MapAreas.MapArea taverley = parse(TABLE).named("Taverley Underground");
		MapAreas.Footprint fp = taverley.footprints().get(MapAreas.regionOf(2900, 9800));

		assertEquals(2, fp.parts.size());
		assertEquals(new Rectangle(2880, 9600, 64, 64), fp.partAt(2890, 9610));
		assertEquals(new Rectangle(2880, 9792, 64, 64), fp.partAt(2890, 9800));
		assertNull(fp.partAt(2890, 9700));
		assertNotNull(fp.centre());
	}

	@Test
	public void theNearestWayInOnTheMapIsChosen() throws IOException
	{
		MapAreas areas = parse(TABLE);
		MapAreas.MapArea taverley = areas.named("Taverley Underground");

		// the chunk is drawn twice; its first part (in place) has its middle at
		// (2912, 9824), 26 tiles from the ladder and 29 from the trapdoor
		assertEquals(new Point(2900, 3450), taverley.entranceFor(MapAreas.regionOf(2900, 9800)));
		assertEquals(new Point(2900, 9850), taverley.mapPositionOf(2900, 9850));
		assertNull(taverley.mapPositionOf(3245, 3245));
		assertNull(areas.named("Waterbirth Dungeon").entranceFor(MapAreas.regionOf(2950, 4600)));
	}

	@Test
	public void namesAndHomes() throws IOException
	{
		MapAreas areas = parse(TABLE);

		assertTrue(areas.named(" GIELINOR SURFACE ").isSurface());
		assertFalse(areas.named("Waterbirth Dungeon").isSurface());
		assertNull(areas.named("Nowhere"));
		assertNull(areas.named(null));
		assertEquals(3, areas.getAreas().size());
		assertEquals(1, areas.named("Waterbirth Dungeon").getPieces().size());
		assertSame(areas.named("Gielinor Surface"), areas.home(MapAreas.regionOf(3245, 3245)));
		assertSame(areas.named("Waterbirth Dungeon"), areas.home(MapAreas.regionOf(2950, 4600)));
		assertNull(areas.home(MapAreas.regionOf(100, 100)));
		assertNull(MapAreas.empty().home(MapAreas.regionOf(3245, 3245)));
	}

	@Test
	public void footprintCentreIsTheLargestPart() throws IOException
	{
		MapAreas.MapArea waterbirth = parse(TABLE).named("Waterbirth Dungeon");

		assertEquals(new Point(2658, 9604), waterbirth.footprints().get(MapAreas.regionOf(2950, 4600)).centre());
	}

	@Test
	public void bundledTableKnowsTheMovedDungeons() throws IOException
	{
		MapAreas areas;
		try (InputStream in = NpcPermadeathPlugin.class.getResourceAsStream(MapAreas.RESOURCE))
		{
			assertNotNull(in);
			areas = MapAreas.parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)));
		}
		int lumbridge = MapAreas.regionOf(3245, 3245);
		int dagannothKings = MapAreas.regionOf(2910, 4450);
		int taverleyDragons = MapAreas.regionOf(2900, 9800);
		MapAreas.MapArea surface = areas.named("Gielinor Surface");
		MapAreas.MapArea waterbirth = areas.named("Waterbirth Dungeon");

		assertTrue(surface.isSurface());
		assertSame(surface, areas.home(lumbridge));
		assertEquals(lumbridge, surface.regionAt(3245, 3245));
		// the kings' lair is drawn beside the island's caves, far from its real tiles
		assertSame(waterbirth, areas.home(dagannothKings));
		Rectangle lair = waterbirth.footprints().get(dagannothKings).bounds;
		assertTrue(lair.y > 9000);
		assertEquals(dagannothKings, waterbirth.regionAt(lair.x + lair.width / 2, lair.y + lair.height / 2));
		assertSame(areas.named("Taverley Underground"), areas.home(taverleyDragons));
		// ways in from the surface: the kings from Waterbirth Island, Dorgesh-Kaan
		// through the Lumbridge caves, which have no surface link of their own
		Point island = waterbirth.entranceFor(dagannothKings);
		assertTrue(String.valueOf(island), island.x > 2500 && island.x < 2560 && island.y > 3720 && island.y < 3760);
		MapAreas.MapArea dorgesh = areas.named("Dorgesh-Kaan");
		Point cellar = dorgesh.entranceFor(dorgesh.footprints().keySet().iterator().next());
		assertTrue(String.valueOf(cellar), cellar.x > 3150 && cellar.x < 3260 && cellar.y > 3150 && cellar.y < 3260);
		for (MapAreas.MapArea a : areas.getAreas())
		{
			assertFalse(a.getName(), a.getPieces().isEmpty());
		}
	}
}
