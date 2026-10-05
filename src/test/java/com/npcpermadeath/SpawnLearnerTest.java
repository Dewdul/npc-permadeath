package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.npcpermadeath.SpawnLearner.SpawnTile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

public class SpawnLearnerTest
{
	private static final int GOBLIN = 3029;
	private static final int LUMBRIDGE = 12850;

	private final SpawnLearner learner = new SpawnLearner();

	private static byte[] gzip(String text) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (GZIPOutputStream out = new GZIPOutputStream(bytes))
		{
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
		return bytes.toByteArray();
	}

	@Test
	public void respawnOfADeadNpcTeachesItsTile()
	{
		learner.noteDeath(7, GOBLIN, "Goblin", 100);

		SpawnTile tile = learner.noteSpawn(7, GOBLIN, "Goblin", 3245, 3245, 0, 130, true);

		assertNotNull(tile);
		assertEquals(LUMBRIDGE, tile.region());
		assertEquals(1, learner.countInRegion("Goblin", LUMBRIDGE));
		assertEquals(Collections.singleton("Goblin"), learner.namesInRegion(LUMBRIDGE));
		assertTrue(learner.hasSpawns(LUMBRIDGE));
		assertFalse(learner.hasSpawns(LUMBRIDGE + 1));
		assertTrue(learner.isDirty());
	}

	@Test
	public void spawnsThatAreNotARespawnTeachNothing()
	{
		assertNull(learner.noteSpawn(7, GOBLIN, "Goblin", 3245, 3245, 0, 130, true));

		learner.noteDeath(7, GOBLIN, "Goblin", 100);
		assertNull(learner.noteSpawn(7, GOBLIN, "Goblin", 3245, 3245, 0, 130, false));

		learner.noteDeath(8, GOBLIN, "Goblin", 100);
		assertNull(learner.noteSpawn(8, 9999, "Cow", 3245, 3245, 0, 130, true));

		learner.noteDeath(9, GOBLIN, "Goblin", 100);
		assertNull(learner.noteSpawn(9, GOBLIN, "Goblin", 3245, 3245, 0, 100 + SpawnLearner.RESPAWN_TTL_TICKS + 1, true));
		assertEquals(0, learner.learnedCount());
	}

	@Test
	public void oneDeathTeachesAtMostOnce()
	{
		learner.noteDeath(7, GOBLIN, "Goblin", 100);
		learner.noteSpawn(7, GOBLIN, "Goblin", 3245, 3245, 0, 130, true);

		assertNull(learner.noteSpawn(7, GOBLIN, "Goblin", 3246, 3245, 0, 131, true));
	}

	@Test
	public void sameTileIsNotLearnedTwice()
	{
		learner.noteDeath(7, GOBLIN, "Goblin", 100);
		learner.noteSpawn(7, GOBLIN, "Goblin", 3245, 3245, 0, 130, true);
		learner.noteDeath(7, GOBLIN, "Goblin", 200);

		assertNull(learner.noteSpawn(7, GOBLIN, "Goblin", 3245, 3245, 0, 230, true));
		assertEquals(1, learner.learnedCount());
	}

	@Test
	public void seedAndLearnedTilesAreCountedOnce() throws IOException
	{
		learner.loadSeed(new ByteArrayInputStream(gzip(
			"# header\nGoblin|3029|3245|3245|0\nGoblin|3029|3250|3230|0\nRat|2854|3210|3210|0\nbad line\n")));
		assertEquals(3, learner.seedCount());
		learner.noteDeath(7, GOBLIN, "Goblin", 100);
		learner.noteSpawn(7, GOBLIN, "Goblin", 3250, 3230, 0, 130, true);
		learner.noteDeath(8, GOBLIN, "Goblin", 100);
		learner.noteSpawn(8, GOBLIN, "Goblin", 3260, 3240, 0, 130, true);

		assertEquals(3, learner.countInRegion("Goblin", LUMBRIDGE));
		assertEquals(1, learner.countInRegion("Rat", LUMBRIDGE));
		assertEquals(0, learner.countInRegion("Cow", LUMBRIDGE));
		assertEquals(Arrays.asList("Goblin", "Rat"), new java.util.ArrayList<>(learner.namesInRegion(LUMBRIDGE)));
		assertEquals(2, learner.learnedCount());
	}

	@Test
	public void sameTileWithDifferentIdsCountsOnce() throws IOException
	{
		// A wiki-only tile (id 0) that the player later sees in game with its real id.
		learner.loadSeed(new ByteArrayInputStream(gzip("Goblin|0|3245|3245|0\nGoblin|0|3250|3230|0\n")));
		learner.noteDeath(7, GOBLIN, "Goblin", 100);
		SpawnTile tile = learner.noteSpawn(7, GOBLIN, "Goblin", 3245, 3245, 0, 130, true);

		assertNotNull(tile);
		assertEquals(2, learner.countInRegion("Goblin", LUMBRIDGE));
		// The persisted tile keeps the real id.
		assertEquals(Collections.singletonList("Goblin|3029|3245|3245|0"), learner.serializeLearned());
	}

	@Test
	public void sameTileFromTheDumpAndTheWikiCountsOnce() throws IOException
	{
		learner.loadSeed(new ByteArrayInputStream(gzip("Goblin|3029|3245|3245|0\nGoblin|0|3245|3245|0\n")));

		assertEquals(1, learner.countInRegion("Goblin", LUMBRIDGE));
		assertEquals(1, learner.seedCount());
	}

	@Test
	public void sameNameOnAnotherPlaneOrTileCountsSeparately() throws IOException
	{
		learner.loadSeed(new ByteArrayInputStream(gzip("Goblin|0|3245|3245|0\nGoblin|0|3245|3245|1\nGoblin|0|3246|3245|0\n")));

		assertEquals(3, learner.countInRegion("Goblin", LUMBRIDGE));
	}

	@Test
	public void loadOrderOfSeedAndLearnedTilesDoesNotChangeTheCount() throws IOException
	{
		String seed = "Goblin|0|3245|3245|0\nGoblin|3029|3250|3230|0\nGoblin|0|3255|3255|0\n";
		// The first two learned tiles are also in the seed; the third is new.
		java.util.List<String> learnedTiles = Arrays.asList("Goblin|3029|3245|3245|0", "Goblin|0|3250|3230|0",
			"Goblin|3029|3240|3240|0");

		SpawnLearner seedFirst = new SpawnLearner();
		seedFirst.loadSeed(new ByteArrayInputStream(gzip(seed)));
		seedFirst.load(learnedTiles);

		SpawnLearner learnedFirst = new SpawnLearner();
		learnedFirst.load(learnedTiles);
		learnedFirst.loadSeed(new ByteArrayInputStream(gzip(seed)));

		assertEquals(4, seedFirst.countInRegion("Goblin", LUMBRIDGE));
		assertEquals(4, learnedFirst.countInRegion("Goblin", LUMBRIDGE));
		assertEquals(seedFirst.namesInRegion(LUMBRIDGE), learnedFirst.namesInRegion(LUMBRIDGE));
		assertEquals(3, seedFirst.learnedCount());
		assertEquals(3, learnedFirst.learnedCount());
	}

	@Test
	public void loadingTheSeedAgainAddsNothing() throws IOException
	{
		byte[] seed = gzip("Goblin|3029|3245|3245|0\nRat|0|3210|3210|0\n");
		learner.loadSeed(new ByteArrayInputStream(seed));
		learner.loadSeed(new ByteArrayInputStream(seed));

		assertEquals(2, learner.seedCount());
		assertEquals(1, learner.countInRegion("Goblin", LUMBRIDGE));
	}

	@Test
	public void namesMatchWithoutRegardToCase() throws IOException
	{
		learner.loadSeed(new ByteArrayInputStream(gzip(
			"Frost dragon|0|3245|3245|0\nFrost Dragon|0|3246|3245|0\nFrost dragon|0|3247|3245|0\n")));

		assertEquals(3, learner.countInRegion("Frost dragon", LUMBRIDGE));
		assertEquals(3, learner.countInRegion("FROST DRAGON", LUMBRIDGE));
		assertEquals(1, learner.namesInRegion(LUMBRIDGE).size());
		assertEquals(Collections.singleton("Frost Dragon"), learner.allNames());
	}

	@Test
	public void displayNamePrefersTheDumpThenTheGame() throws IOException
	{
		// The wiki spells it one way, the dump (with a real id) another.
		learner.loadSeed(new ByteArrayInputStream(gzip(
			"Frost Dragon|0|3245|3245|0\nFrost dragon|10|3246|3245|0\nFrost Dragon|0|3247|3245|0\n")));
		assertEquals(Collections.singleton("Frost dragon"), learner.namesInRegion(LUMBRIDGE));
		assertEquals(Collections.singleton("Frost dragon"), learner.allNames());

		// Load order does not matter.
		SpawnLearner reversed = new SpawnLearner();
		reversed.loadSeed(new ByteArrayInputStream(gzip("Frost dragon|10|3246|3245|0\nFrost Dragon|0|3245|3245|0\n")));
		assertEquals(Collections.singleton("Frost dragon"), reversed.namesInRegion(LUMBRIDGE));

		// What the game itself reports wins over both.
		learner.noteDeath(7, 10, "FROST DRAGON", 100);
		learner.noteSpawn(7, 10, "FROST DRAGON", 3250, 3250, 0, 130, true);
		assertEquals(Collections.singleton("FROST DRAGON"), learner.namesInRegion(LUMBRIDGE));
		assertEquals(4, learner.countInRegion("Frost dragon", LUMBRIDGE));
	}

	@Test
	public void stateRoundTripsThroughText()
	{
		learner.noteDeath(7, GOBLIN, "Goblin", 100);
		learner.noteSpawn(7, GOBLIN, "Goblin", 3245, 3245, 0, 130, true);

		SpawnLearner fresh = new SpawnLearner();
		fresh.load(learner.serializeLearned());

		assertEquals(1, fresh.learnedCount());
		assertEquals(1, fresh.countInRegion("Goblin", LUMBRIDGE));
		assertEquals("Goblin|3029|3245|3245|0", learner.serializeLearned().get(0));
		assertNull(SpawnTile.parse("garbage"));
		assertNull(SpawnTile.parse("|1|2|3|4"));
		assertNull(SpawnTile.parse("Goblin|a|b|c|d"));
		fresh.load(Arrays.asList("bad", "Rat|2854|3200|3200|0"));
		assertEquals(1, fresh.learnedCount());
	}

	@Test
	public void staleDeathsArePruned()
	{
		learner.noteDeath(7, GOBLIN, "Goblin", 100);
		learner.prune(100 + SpawnLearner.RESPAWN_TTL_TICKS + 1);

		assertNull(learner.noteSpawn(7, GOBLIN, "Goblin", 3245, 3245, 0, 130, true));
	}
}
