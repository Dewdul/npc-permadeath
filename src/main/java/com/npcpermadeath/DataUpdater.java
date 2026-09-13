package com.npcpermadeath;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Keeps the bundled data files fresh between plugin releases. A scheduled
 * job in the plugin repository regenerates them weekly; this fetches the
 * latest copy from GitHub once a week and caches it under the RuneLite
 * directory. Only data is fetched, never code.
 */
@Slf4j
class DataUpdater
{
	static final long REFRESH_MS = 7L * 24 * 60 * 60 * 1000;
	static final String KEY_PREFIX = "fetched.";
	private static final HttpUrl BASE = HttpUrl.parse(
		"https://raw.githubusercontent.com/Dewdul/npc-permadeath/master/src/main/resources/com/npcpermadeath/");
	private static final File DIR = new File(RuneLite.RUNELITE_DIR, "npc-permadeath");

	private final OkHttpClient httpClient;
	private final ConfigManager configManager;
	private final ClientThread clientThread;
	private final Set<String> inFlight = new HashSet<>();

	DataUpdater(OkHttpClient httpClient, ConfigManager configManager, ClientThread clientThread)
	{
		this.httpClient = httpClient;
		this.configManager = configManager;
		this.clientThread = clientThread;
	}

	/** The cached copy of a data file, or null if none has been fetched yet. */
	byte[] cached(String fileName)
	{
		File file = new File(DIR, fileName);
		if (!file.isFile())
		{
			return null;
		}
		try
		{
			return Files.readAllBytes(file.toPath());
		}
		catch (IOException e)
		{
			log.warn("Could not read cached {}", fileName, e);
			return null;
		}
	}

	/**
	 * Fetches the file if the cached copy is a week old or missing. The
	 * callback runs on the client thread, only when the content changed.
	 */
	void refreshIfStale(String fileName, long now, Consumer<byte[]> onChanged)
	{
		String key = KEY_PREFIX + fileName;
		Long fetched = configManager.getConfiguration(NpcPermadeathConfig.GROUP, key, Long.class);
		if (fetched != null && now - fetched < REFRESH_MS && new File(DIR, fileName).isFile())
		{
			return;
		}
		if (!inFlight.add(fileName))
		{
			return;
		}
		HttpUrl url = BASE.newBuilder().addPathSegment(fileName).build();
		httpClient.newCall(new Request.Builder().url(url).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Could not refresh {}", fileName, e);
				clientThread.invoke(() -> inFlight.remove(fileName));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				byte[] body = null;
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						body = r.body().bytes();
					}
				}
				catch (IOException e)
				{
					log.debug("Could not read refreshed {}", fileName, e);
				}
				byte[] fresh = body;
				clientThread.invoke(() ->
				{
					inFlight.remove(fileName);
					if (fresh == null || fresh.length == 0)
					{
						return;
					}
					configManager.setConfiguration(NpcPermadeathConfig.GROUP, key, now);
					byte[] previous = cached(fileName);
					if (previous != null && Arrays.equals(previous, fresh))
					{
						return;
					}
					if (store(fileName, fresh))
					{
						log.debug("Refreshed {} ({} bytes)", fileName, fresh.length);
						onChanged.accept(fresh);
					}
				});
			}
		});
	}

	private static boolean store(String fileName, byte[] content)
	{
		try
		{
			if (!DIR.isDirectory() && !DIR.mkdirs())
			{
				return false;
			}
			File tmp = new File(DIR, fileName + ".tmp");
			Files.write(tmp.toPath(), content);
			Files.move(tmp.toPath(), new File(DIR, fileName).toPath(), StandardCopyOption.REPLACE_EXISTING);
			return true;
		}
		catch (IOException e)
		{
			log.warn("Could not store {}", fileName, e);
			return false;
		}
	}
}
