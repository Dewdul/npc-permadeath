package com.npcpermadeath;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * How many spawns of an NPC type exist per map region, according to the
 * OSRS Wiki's location maps. Fetched once per NPC name, cached in the
 * client config for a week. Only used for display ("12 of 35 slain").
 */
@Slf4j
class SpawnTotals
{
	static final String KEY = "spawnTotals";
	static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
	private static final HttpUrl API = HttpUrl.parse("https://oldschool.runescape.wiki/api.php");
	private static final Gson PARSER = new Gson();
	private static final Type CACHE_TYPE = new TypeToken<Map<String, Cached>>()
	{
	}.getType();

	static class Cached
	{
		long fetched;
		Map<Integer, Integer> byRegion = new HashMap<>();
	}

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ConfigManager configManager;
	private final ClientThread clientThread;

	private final Map<String, Cached> cache = new HashMap<>();
	private final Set<String> inFlight = new HashSet<>();

	SpawnTotals(OkHttpClient httpClient, Gson gson, ConfigManager configManager, ClientThread clientThread)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.configManager = configManager;
		this.clientThread = clientThread;
	}

	void load()
	{
		cache.clear();
		String json = configManager.getConfiguration(NpcPermadeathConfig.GROUP, KEY);
		if (json == null)
		{
			return;
		}
		try
		{
			Map<String, Cached> stored = gson.fromJson(json, CACHE_TYPE);
			if (stored != null)
			{
				stored.forEach((name, cached) ->
				{
					if (cached != null && cached.byRegion != null)
					{
						cache.put(name, cached);
					}
				});
			}
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Discarding unreadable spawn totals cache", e);
		}
	}

	private void save()
	{
		configManager.setConfiguration(NpcPermadeathConfig.GROUP, KEY, gson.toJson(cache, CACHE_TYPE));
	}

	/**
	 * @return the number of spawns of this NPC in the region, or null if unknown
	 */
	Integer get(String name, int region)
	{
		Cached cached = cache.get(name);
		if (cached == null)
		{
			return null;
		}
		return cached.byRegion.get(region);
	}

	boolean isKnown(String name)
	{
		return cache.containsKey(name);
	}

	/** Fetches totals for the NPC if they are missing or stale; runs the callback on the client thread when done. */
	void ensure(String name, long now, Runnable onLoaded)
	{
		Cached cached = cache.get(name);
		if (cached != null && now - cached.fetched < TTL_MS)
		{
			return;
		}
		if (!inFlight.add(name))
		{
			return;
		}
		HttpUrl url = API.newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("prop", "mapdata")
			.addQueryParameter("titles", name)
			.addQueryParameter("mpdlimit", "max")
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2")
			.build();
		httpClient.newCall(new Request.Builder().url(url).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Spawn totals lookup failed for {}", name, e);
				clientThread.invoke(() -> inFlight.remove(name));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				Map<Integer, Integer> counts = null;
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						counts = parse(r.body().string());
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("Could not read spawn totals for {}", name, e);
				}
				Map<Integer, Integer> result = counts;
				clientThread.invoke(() ->
				{
					inFlight.remove(name);
					if (result == null)
					{
						return;
					}
					Cached fresh = new Cached();
					fresh.fetched = now;
					fresh.byRegion = result;
					cache.put(name, fresh);
					save();
					onLoaded.run();
				});
			}
		});
	}

	/**
	 * Counts map pins per region in a wiki mapdata response. A page with no
	 * pins (or no page at all) yields an empty map, which is still cached so
	 * the lookup is not repeated.
	 */
	static Map<Integer, Integer> parse(String body)
	{
		Map<Integer, Integer> counts = new HashMap<>();
		JsonObject root = PARSER.fromJson(body, JsonElement.class).getAsJsonObject();
		JsonObject query = root.getAsJsonObject("query");
		if (query == null || !query.has("pages"))
		{
			return counts;
		}
		for (JsonElement pageEl : query.getAsJsonArray("pages"))
		{
			JsonObject page = pageEl.getAsJsonObject();
			if (!page.has("mapdata"))
			{
				continue;
			}
			for (JsonElement chunk : page.getAsJsonArray("mapdata"))
			{
				JsonElement groups = chunk.isJsonPrimitive()
					? PARSER.fromJson(chunk.getAsString(), JsonElement.class)
					: chunk;
				countFeatures(groups, counts);
			}
		}
		return counts;
	}

	private static void countFeatures(JsonElement element, Map<Integer, Integer> counts)
	{
		if (element.isJsonArray())
		{
			for (JsonElement child : element.getAsJsonArray())
			{
				countFeatures(child, counts);
			}
			return;
		}
		if (!element.isJsonObject())
		{
			return;
		}
		JsonObject obj = element.getAsJsonObject();
		if ("Feature".equals(stringOf(obj, "type")))
		{
			JsonObject geometry = obj.getAsJsonObject("geometry");
			JsonObject props = obj.getAsJsonObject("properties");
			if (geometry != null && "Point".equals(stringOf(geometry, "type"))
				&& (props == null || !props.has("mapID") || props.get("mapID").getAsInt() == 0))
			{
				JsonArray coords = geometry.getAsJsonArray("coordinates");
				int x = (int) coords.get(0).getAsDouble();
				int y = (int) coords.get(1).getAsDouble();
				counts.merge(((x >> 6) << 8) | (y >> 6), 1, Integer::sum);
			}
			return;
		}
		for (Map.Entry<String, JsonElement> entry : obj.entrySet())
		{
			countFeatures(entry.getValue(), counts);
		}
	}

	private static String stringOf(JsonObject obj, String key)
	{
		JsonElement el = obj.get(key);
		return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
	}
}
