package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Map;
import org.junit.Test;

public class SpawnTotalsTest
{
	@Test
	public void countsPinsPerRegionFromStringMapdata()
	{
		String pins = "{\"pins\":[{\"type\":\"FeatureCollection\",\"features\":["
			+ "{\"type\":\"Feature\",\"geometry\":{\"coordinates\":[3245.5,3245.5],\"type\":\"Point\"},\"properties\":{\"mapID\":0,\"plane\":0}},"
			+ "{\"type\":\"Feature\",\"geometry\":{\"coordinates\":[3250.5,3230.5],\"type\":\"Point\"},\"properties\":{\"mapID\":0,\"plane\":0}},"
			+ "{\"type\":\"Feature\",\"geometry\":{\"coordinates\":[3100.5,3100.5],\"type\":\"Point\"},\"properties\":{\"mapID\":0,\"plane\":0}},"
			+ "{\"type\":\"Feature\",\"geometry\":{\"coordinates\":[3245.5,3245.5],\"type\":\"Point\"},\"properties\":{\"mapID\":2,\"plane\":0}}"
			+ "]}]}";
		String body = "{\"query\":{\"pages\":[{\"title\":\"Goblin\",\"mapdata\":[" + quote(pins) + "]}]}}";

		Map<Integer, Integer> counts = SpawnTotals.parse(body);

		assertEquals(Integer.valueOf(2), counts.get(12850));
		assertEquals(Integer.valueOf(1), counts.get(((3100 >> 6) << 8) | (3100 >> 6)));
		assertEquals(2, counts.size());
	}

	@Test
	public void missingPageYieldsNoCounts()
	{
		String body = "{\"query\":{\"pages\":[{\"title\":\"Nope\",\"missing\":true}]}}";

		assertTrue(SpawnTotals.parse(body).isEmpty());
	}

	private static String quote(String json)
	{
		return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}
}
