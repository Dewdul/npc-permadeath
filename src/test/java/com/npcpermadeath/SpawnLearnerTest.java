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
import java.util.List;
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
		assertEquals(1, learner.pendingCount());
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
	public void seedCommunityAndLearnedTilesAreCountedOnce() throws IOException
	{
		learner.loadSeed(new ByteArrayInputStream(gzip(
			"# header\nGoblin|3029|3245|3245|0\nGoblin|3029|3250|3230|0\nRat|2854|3210|3210|0\nbad line\n")));
		assertEquals(3, learner.seedCount());
		learner.addCommunity(Collections.singletonList(new SpawnTile("Goblin", GOBLIN, 3245, 3245, 0)));
		learner.noteDeath(7, GOBLIN, "Goblin", 100);
		learner.noteSpawn(7, GOBLIN, "Goblin", 3250, 3230, 0, 130, true);
		learner.noteDeath(8, GOBLIN, "Goblin", 100);
		learner.noteSpawn(8, GOBLIN, "Goblin", 3260, 3240, 0, 130, true);

		assertEquals(3, learner.countInRegion("Goblin", LUMBRIDGE));
		assertEquals(1, learner.countInRegion("Rat", LUMBRIDGE));
		assertEquals(0, learner.countInRegion("Cow", LUMBRIDGE));
		assertEquals(Arrays.asList("Goblin", "Rat"), new java.util.ArrayList<>(learner.namesInRegion(LUMBRIDGE)));
		// Only the tile nobody knew about is worth sharing.
		List<SpawnTile> upload = learner.takePendingUpload();
		assertEquals(1, upload.size());
		assertEquals(3260, upload.get(0).getX());
		assertEquals(0, learner.pendingCount());
		learner.uploadFailed(upload);
		assertEquals(1, learner.pendingCount());
	}

	@Test
	public void stateRoundTripsThroughText()
	{
		learner.noteDeath(7, GOBLIN, "Goblin", 100);
		learner.noteSpawn(7, GOBLIN, "Goblin", 3245, 3245, 0, 130, true);

		SpawnLearner fresh = new SpawnLearner();
		fresh.load(learner.serializeLearned(), learner.serializePending());

		assertEquals(1, fresh.learnedCount());
		assertEquals(1, fresh.pendingCount());
		assertEquals(1, fresh.countInRegion("Goblin", LUMBRIDGE));
		assertEquals("Goblin|3029|3245|3245|0", learner.serializeLearned().get(0));
		assertNull(SpawnTile.parse("garbage"));
		assertNull(SpawnTile.parse("|1|2|3|4"));
		assertNull(SpawnTile.parse("Goblin|a|b|c|d"));
		fresh.load(Arrays.asList("bad", "Rat|2854|3200|3200|0"), Collections.emptyList());
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
