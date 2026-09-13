package com.npcpermadeath;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * What the OSRS Wiki knows about an NPC's spawns: how many there are per map
 * region and what the wiki calls that place ("Lumbridge"). Parsed from the
 * location lines on the NPC's page, fetched once per NPC name and cached in
 * the client config for a week. Display only; hiding works without it.
 */
@Slf4j
class SpawnTotals
{
	static final String KEY = "spawnTotals";
	static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
	private static final HttpUrl WIKI = HttpUrl.parse("https://oldschool.runescape.wiki/w/");
	private static final Type CACHE_TYPE = new TypeToken<Map<String, Cached>>()
	{
	}.getType();
	private static final Pattern PIN = Pattern.compile("x:\\s*(-?\\d+)\\s*,\\s*y:\\s*(-?\\d+)");
	private static final Pattern REDIRECT = Pattern.compile("^\\s*#REDIRECT\\s*\\[\\[([^\\]|]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]|]*)(?:\\|([^\\]]*))?\\]\\]");
	private static final int MAX_REDIRECTS = 2;

	static class Cached
	{
		long fetched;
		Map<Integer, Integer> byRegion = new HashMap<>();
		Map<Integer, String> labelByRegion = new HashMap<>();
	}

	/** Spawn counts and place names for one NPC page. */
	static class PageSpawns
	{
		final Map<Integer, Integer> byRegion = new HashMap<>();
		/** region -> place label -> pins with that label */
		final Map<Integer, Map<String, Integer>> labelVotes = new HashMap<>();

		Map<Integer, String> labels()
		{
			Map<Integer, String> out = new HashMap<>();
			labelVotes.forEach((region, votes) -> votes.entrySet().stream()
				.max(Map.Entry.comparingByValue())
				.ifPresent(e -> out.put(region, e.getKey())));
			return out;
		}
	}

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ConfigManager configManager;
	private final ClientThread clientThread;

	private final Map<String, Cached> cache = new HashMap<>();
	private final Set<String> inFlight = new HashSet<>();
	/** Best known place name per region, pooled from every fetched NPC. */
	private final Map<Integer, String> regionLabels = new HashMap<>();

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
		regionLabels.clear();
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
						if (cached.labelByRegion == null)
						{
							cached.labelByRegion = new HashMap<>();
						}
						cache.put(name, cached);
						regionLabels.putAll(cached.labelByRegion);
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
		return cached == null ? null : cached.byRegion.get(region);
	}

	/** A place name for the region, preferring this NPC's own page, or null if unknown. */
	String label(String name, int region)
	{
		Cached cached = cache.get(name);
		if (cached != null)
		{
			String own = cached.labelByRegion.get(region);
			if (own != null)
			{
				return own;
			}
		}
		return regionLabels.get(region);
	}

	boolean isKnown(String name)
	{
		return cache.containsKey(name);
	}

	/** Stores parsed page data directly; used by tests and by the fetch callback. */
	void put(String name, PageSpawns spawns, long now)
	{
		Cached fresh = new Cached();
		fresh.fetched = now;
		fresh.byRegion = spawns.byRegion;
		fresh.labelByRegion = spawns.labels();
		cache.put(name, fresh);
		regionLabels.putAll(fresh.labelByRegion);
	}

	/**
	 * Regions of this NPC's spawns that the wiki files under the given place,
	 * or an empty set if the place is not one of the NPC's own labels.
	 */
	Set<Integer> regionsOfPlace(String name, String place)
	{
		Set<Integer> out = new HashSet<>();
		Cached cached = cache.get(name);
		if (cached != null)
		{
			cached.labelByRegion.forEach((region, label) ->
			{
				if (label.equals(place))
				{
					out.add(region);
				}
			});
		}
		return out;
	}

	/** Total spawns of the NPC across every region the wiki files under the place, or null if unknown. */
	Integer totalForPlace(String name, String place)
	{
		Cached cached = cache.get(name);
		if (cached == null)
		{
			return null;
		}
		int sum = 0;
		boolean any = false;
		for (int region : regionsOfPlace(name, place))
		{
			Integer n = cached.byRegion.get(region);
			if (n != null)
			{
				sum += n;
				any = true;
			}
		}
		return any ? sum : null;
	}

	/**
	 * When a kill lands in a region the wiki has no spawns of this NPC in, the
	 * NPC most likely wandered over from next door. Returns the neighbouring
	 * region with the most spawns, or the region itself when nothing better
	 * is known.
	 */
	int homeRegion(String name, int region)
	{
		Cached cached = cache.get(name);
		if (cached == null || cached.byRegion.isEmpty() || cached.byRegion.containsKey(region))
		{
			return region;
		}
		int rx = region >> 8;
		int ry = region & 0xff;
		int best = region;
		int bestPins = 0;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				int candidate = ((rx + dx) << 8) | (ry + dy);
				Integer pins = cached.byRegion.get(candidate);
				if (pins != null && pins > bestPins)
				{
					best = candidate;
					bestPins = pins;
				}
			}
		}
		return best;
	}

	/** Fetches the NPC's page if missing or stale; runs the callback on the client thread when done. */
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
		fetch(name, name, 0, now, onLoaded);
	}

	private void fetch(String name, String title, int redirects, long now, Runnable onLoaded)
	{
		HttpUrl url = WIKI.newBuilder().addPathSegment(title).addQueryParameter("action", "raw").build();
		httpClient.newCall(new Request.Builder().url(url).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Spawn lookup failed for {}", name, e);
				clientThread.invoke(() -> inFlight.remove(name));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				String body = null;
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						body = r.body().string();
					}
					else if (r.code() == 404)
					{
						body = "";
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("Could not read spawn page for {}", name, e);
				}
				if (body != null)
				{
					Matcher redirect = REDIRECT.matcher(body);
					if (redirect.find() && redirects < MAX_REDIRECTS)
					{
						fetch(name, redirect.group(1).trim(), redirects + 1, now, onLoaded);
						return;
					}
				}
				PageSpawns result = body == null ? null : parse(body);
				clientThread.invoke(() ->
				{
					inFlight.remove(name);
					if (result == null)
					{
						return;
					}
					put(name, result, now);
					save();
					onLoaded.run();
				});
			}
		});
	}

	/**
	 * Reads every {@code {{LocLine ...}}} on a wiki page. A page without any
	 * (or an empty body for a missing page) yields empty maps, which are still
	 * cached so the lookup is not repeated.
	 */
	static PageSpawns parse(String wikitext)
	{
		PageSpawns spawns = new PageSpawns();
		int from = 0;
		while (true)
		{
			int start = wikitext.indexOf("{{LocLine", from);
			if (start < 0)
			{
				break;
			}
			int end = templateEnd(wikitext, start);
			if (end < 0)
			{
				break;
			}
			parseLocLine(wikitext.substring(start + 2, end), spawns);
			from = end + 2;
		}
		return spawns;
	}

	/** Index of the {@code }}} closing the template opened at {@code start}, or -1. */
	private static int templateEnd(String text, int start)
	{
		int depth = 0;
		for (int i = start; i + 1 < text.length(); i++)
		{
			if (text.charAt(i) == '{' && text.charAt(i + 1) == '{')
			{
				depth++;
				i++;
			}
			else if (text.charAt(i) == '}' && text.charAt(i + 1) == '}')
			{
				depth--;
				if (depth == 0)
				{
					return i;
				}
				i++;
			}
		}
		return -1;
	}

	private static void parseLocLine(String body, PageSpawns spawns)
	{
		String location = null;
		int mapId = 0;
		Map<Integer, Integer> pins = new TreeMap<>();
		for (String part : splitParams(body))
		{
			String p = part.trim();
			int eq = p.indexOf('=');
			if (eq > 0)
			{
				String key = p.substring(0, eq).trim();
				String value = p.substring(eq + 1).trim();
				if (key.equals("location"))
				{
					location = cleanLabel(value);
				}
				else if (key.equals("mapID"))
				{
					try
					{
						mapId = Integer.parseInt(value);
					}
					catch (NumberFormatException ignored)
					{
						mapId = -1;
					}
				}
				continue;
			}
			Matcher pin = PIN.matcher(p);
			while (pin.find())
			{
				int x = Integer.parseInt(pin.group(1));
				int y = Integer.parseInt(pin.group(2));
				pins.merge(((x >> 6) << 8) | (y >> 6), 1, Integer::sum);
			}
		}
		if (mapId != 0)
		{
			return;
		}
		for (Map.Entry<Integer, Integer> e : pins.entrySet())
		{
			spawns.byRegion.merge(e.getKey(), e.getValue(), Integer::sum);
			if (location != null && !location.isEmpty())
			{
				spawns.labelVotes.computeIfAbsent(e.getKey(), r -> new HashMap<>())
					.merge(location, e.getValue(), Integer::sum);
			}
		}
	}

	/** Splits template parameters on {@code |}, ignoring pipes inside links and nested templates. */
	static java.util.List<String> splitParams(String body)
	{
		java.util.List<String> parts = new java.util.ArrayList<>();
		StringBuilder current = new StringBuilder();
		int depth = 0;
		for (int i = 0; i < body.length(); i++)
		{
			char ch = body.charAt(i);
			char next = i + 1 < body.length() ? body.charAt(i + 1) : '\0';
			if ((ch == '[' && next == '[') || (ch == '{' && next == '{'))
			{
				depth++;
				current.append(ch).append(next);
				i++;
			}
			else if ((ch == ']' && next == ']') || (ch == '}' && next == '}'))
			{
				depth = Math.max(0, depth - 1);
				current.append(ch).append(next);
				i++;
			}
			else if (ch == '|' && depth == 0)
			{
				parts.add(current.toString());
				current.setLength(0);
			}
			else
			{
				current.append(ch);
			}
		}
		parts.add(current.toString());
		return parts;
	}

	/** Turns wiki markup like {@code [[Lumbridge Swamp|the swamp]]} into plain text. */
	static String cleanLabel(String value)
	{
		Matcher m = LINK.matcher(value);
		StringBuffer sb = new StringBuffer();
		while (m.find())
		{
			String shown = m.group(2) != null ? m.group(2) : m.group(1);
			m.appendReplacement(sb, Matcher.quoteReplacement(shown));
		}
		m.appendTail(sb);
		return sb.toString()
			.replaceAll("\\{\\{[^}]*\\}\\}", "")
			.replace("'''", "")
			.replace("''", "")
			.replaceAll("<[^>]*>", "")
			.replaceAll("\\s+", " ")
			.trim();
	}
}
