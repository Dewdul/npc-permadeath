package com.npcpermadeath;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.npcpermadeath.SpawnLearner.SpawnTile;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Shares learned spawn tiles with other players through the community
 * server (see the {@code server} directory) and pulls theirs. Opt-in.
 */
@Slf4j
class SpawnSync
{
	static final String KEY = "communitySpawns";
	static final long TTL_MS = 24L * 60 * 60 * 1000;
	static final int MAX_UPLOAD = 200;
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final Type CACHE_TYPE = new TypeToken<Map<String, Cached>>()
	{
	}.getType();

	static class Cached
	{
		long fetched;
		List<TileDto> tiles = new ArrayList<>();
	}

	/** Wire format shared with the server. */
	static class TileDto
	{
		String name;
		int id;
		int x;
		int y;
		int plane;

		TileDto()
		{
		}

		TileDto(SpawnTile t)
		{
			name = t.getName();
			id = t.getNpcId();
			x = t.getX();
			y = t.getY();
			plane = t.getPlane();
		}

		SpawnTile toTile()
		{
			return new SpawnTile(name, id, x, y, plane);
		}
	}

	static class Report
	{
		List<TileDto> tiles;
	}

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ConfigManager configManager;
	private final ClientThread clientThread;

	private final Map<String, Cached> cache = new HashMap<>();
	private final Set<String> inFlight = new HashSet<>();
	private boolean uploading;

	SpawnSync(OkHttpClient httpClient, Gson gson, ConfigManager configManager, ClientThread clientThread)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.configManager = configManager;
		this.clientThread = clientThread;
	}

	/** Loads cached community tiles and returns them so the learner can count them. */
	List<SpawnTile> load()
	{
		cache.clear();
		List<SpawnTile> all = new ArrayList<>();
		String json = configManager.getConfiguration(NpcPermadeathConfig.GROUP, KEY);
		if (json == null)
		{
			return all;
		}
		try
		{
			Map<String, Cached> stored = gson.fromJson(json, CACHE_TYPE);
			if (stored != null)
			{
				stored.forEach((name, cached) ->
				{
					if (cached != null && cached.tiles != null)
					{
						cache.put(name, cached);
						cached.tiles.forEach(t -> all.add(t.toTile()));
					}
				});
			}
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Discarding unreadable community spawn cache", e);
		}
		return all;
	}

	private void save()
	{
		configManager.setConfiguration(NpcPermadeathConfig.GROUP, KEY, gson.toJson(cache, CACHE_TYPE));
	}

	private static HttpUrl base(String url)
	{
		HttpUrl parsed = HttpUrl.parse(url == null ? "" : url.trim());
		return parsed != null && "https".equals(parsed.scheme()) ? parsed : null;
	}

	/** Downloads other players' tiles for the NPC if missing or stale. */
	void fetch(String baseUrl, String name, long now, Consumer<List<SpawnTile>> onLoaded)
	{
		HttpUrl base = base(baseUrl);
		Cached cached = cache.get(name);
		if (base == null || (cached != null && now - cached.fetched < TTL_MS) || !inFlight.add(name))
		{
			return;
		}
		HttpUrl url = base.newBuilder().addPathSegment("npc").addPathSegment(name).build();
		httpClient.newCall(new Request.Builder().url(url).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Community spawn lookup failed for {}", name, e);
				clientThread.invoke(() -> inFlight.remove(name));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				Cached fresh = null;
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						Report report = gson.fromJson(r.body().string(), Report.class);
						fresh = new Cached();
						fresh.fetched = now;
						if (report != null && report.tiles != null)
						{
							fresh.tiles = report.tiles;
						}
					}
				}
				catch (IOException | RuntimeException e)
				{
					log.debug("Could not read community spawns for {}", name, e);
				}
				Cached result = fresh;
				clientThread.invoke(() ->
				{
					inFlight.remove(name);
					if (result == null)
					{
						return;
					}
					cache.put(name, result);
					save();
					List<SpawnTile> tiles = new ArrayList<>();
					result.tiles.forEach(t -> tiles.add(t.toTile()));
					onLoaded.accept(tiles);
				});
			}
		});
	}

	/**
	 * Uploads learned tiles. The failure callback receives the tiles so they
	 * can be queued again.
	 */
	void upload(String baseUrl, List<SpawnTile> tiles, Consumer<List<SpawnTile>> onFailure)
	{
		HttpUrl base = base(baseUrl);
		if (tiles.isEmpty())
		{
			return;
		}
		if (base == null || uploading)
		{
			onFailure.accept(tiles);
			return;
		}
		List<SpawnTile> batch = tiles.size() > MAX_UPLOAD ? tiles.subList(0, MAX_UPLOAD) : tiles;
		List<SpawnTile> rest = tiles.size() > MAX_UPLOAD ? tiles.subList(MAX_UPLOAD, tiles.size()) : new ArrayList<>();
		if (!rest.isEmpty())
		{
			onFailure.accept(new ArrayList<>(rest));
		}
		Report report = new Report();
		report.tiles = new ArrayList<>();
		batch.forEach(t -> report.tiles.add(new TileDto(t)));
		List<SpawnTile> sent = new ArrayList<>(batch);
		uploading = true;
		HttpUrl url = base.newBuilder().addPathSegment("report").build();
		Request request = new Request.Builder().url(url).post(RequestBody.create(JSON, gson.toJson(report))).build();
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Community spawn upload failed", e);
				clientThread.invoke(() ->
				{
					uploading = false;
					onFailure.accept(sent);
				});
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				boolean ok;
				try (Response r = response)
				{
					ok = r.isSuccessful();
				}
				clientThread.invoke(() ->
				{
					uploading = false;
					if (ok)
					{
						log.debug("Shared {} spawn tiles", sent.size());
					}
					else
					{
						onFailure.accept(sent);
					}
				});
			}
		});
	}
}
