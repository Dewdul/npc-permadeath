package com.npcpermadeath;

import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class GhostManagerTest
{
	@Test
	public void opacityMapsToTheGameAlphaByte()
	{
		// In the game 0 is opaque and 255 is fully transparent.
		Assert.assertEquals(0, GhostManager.opacityToAlpha(100) & 0xff);
		Assert.assertEquals(255, GhostManager.opacityToAlpha(0) & 0xff);
		Assert.assertEquals(166, GhostManager.opacityToAlpha(35) & 0xff);
		Assert.assertEquals(230, GhostManager.opacityToAlpha(10) & 0xff);
		Assert.assertEquals(51, GhostManager.opacityToAlpha(80) & 0xff);
	}

	@Test
	public void higherOpacityIsAlwaysMoreSolid()
	{
		int previous = 256;
		for (int percent = 0; percent <= 100; percent++)
		{
			int alpha = GhostManager.opacityToAlpha(percent) & 0xff;
			Assert.assertTrue(alpha <= previous);
			previous = alpha;
		}
	}

	@Test
	public void opacityOutsideTheRangeIsClamped()
	{
		Assert.assertEquals(255, GhostManager.opacityToAlpha(-20) & 0xff);
		Assert.assertEquals(0, GhostManager.opacityToAlpha(500) & 0xff);
	}

	@Test
	public void facesNeverBecomeMoreSolidThanTheyWere()
	{
		// An invisible helper face (255) stays invisible, and an opaque one takes the ghost's alpha.
		Assert.assertEquals(255, GhostManager.combineAlpha((byte) 255, 166) & 0xff);
		Assert.assertEquals(166, GhostManager.combineAlpha((byte) 0, 166) & 0xff);
		Assert.assertEquals(200, GhostManager.combineAlpha((byte) 200, 166) & 0xff);
	}

	@Test
	public void modelKeysAreEqualOnlyForTheSameBuild()
	{
		GhostManager.ModelKey key = new GhostManager.ModelKey(7, 166, true);
		Assert.assertEquals(key, new GhostManager.ModelKey(7, 166, true));
		Assert.assertEquals(key.hashCode(), new GhostManager.ModelKey(7, 166, true).hashCode());
		Assert.assertNotEquals(key, new GhostManager.ModelKey(8, 166, true));
		Assert.assertNotEquals(key, new GhostManager.ModelKey(7, 165, true));
		Assert.assertNotEquals(key, new GhostManager.ModelKey(7, 166, false));
	}

	@Test
	public void lruMapDropsTheLeastRecentlyUsedEntry()
	{
		Map<Integer, String> map = GhostManager.lruMap(2);
		map.put(1, "a");
		map.put(2, "b");
		map.get(1);
		map.put(3, "c");
		Assert.assertTrue(map.containsKey(1));
		Assert.assertFalse(map.containsKey(2));
		Assert.assertTrue(map.containsKey(3));
		Assert.assertEquals(2, map.size());
	}
}
