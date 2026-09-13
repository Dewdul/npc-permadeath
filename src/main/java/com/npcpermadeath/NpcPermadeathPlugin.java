package com.npcpermadeath;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "NPC Permadeath",
	description = "NPCs you kill stay dead: every kill keeps one more NPC of that kind hidden in the area, so places slowly empty out",
	tags = {"npc", "hide", "kill", "respawn", "permadeath", "entity", "hider", "immersion"}
)
public class NpcPermadeathPlugin extends Plugin implements RenderCallback
{
	static final String KEY_STATE = "state";

	private static final int PRUNE_INTERVAL_TICKS = 100;
	private static final int SAVE_INTERVAL_TICKS = 200;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private NpcPermadeathConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	private final PermadeathTracker tracker = new PermadeathTracker();
	private SpawnTotals totals;

	private List<String> nameFilter = new ArrayList<>();
	private int currentWorld = -1;
	private boolean loaded;
	private int tickCounter;

	@Provides
	NpcPermadeathConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(NpcPermadeathConfig.class);
	}

	@Override
	protected void startUp()
	{
		nameFilter = parseNames(config.npcNames());
		totals = new SpawnTotals(okHttpClient, gson, configManager, clientThread);
		totals.load();
		renderCallbackManager.register(this);
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				enterWorld(client.getWorld());
			}
		});
	}

	@Override
	protected void shutDown()
	{
		renderCallbackManager.unregister(this);
		saveIfDirty();
		tracker.clearAll();
		tracker.markSaved();
		loaded = false;
		currentWorld = -1;
	}

	/** Called by the client for every entity about to be drawn; false skips it. */
	@Override
	public boolean addEntity(Renderable renderable, boolean drawingUI)
	{
		if (renderable instanceof NPC)
		{
			NPC npc = (NPC) renderable;
			return !tracker.isHidden(npc.getIndex(), npc.getId());
		}
		return true;
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (event.getActor() instanceof NPC && event.getHitsplat().isMine())
		{
			tracker.recordMyHit(((NPC) event.getActor()).getIndex());
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() instanceof NPC)
		{
			handleDeath((NPC) event.getActor());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();
		if (npc.isDead())
		{
			handleDeath(npc);
		}
		AreaKey area = tracker.recordDespawn(npc.getIndex(), npc.getId(), now());
		if (area != null)
		{
			log.debug("Counted kill of {} in region {} (index {})", area.getName(), area.getRegion(), npc.getIndex());
			saveState();
			announce(area);
			totals.ensure(area.getName(), now(), () -> announce(area));
		}
	}

	private void handleDeath(NPC npc)
	{
		int index = npc.getIndex();
		if (tracker.isPending(index) || inInstance())
		{
			return;
		}
		String name = cleanName(npc);
		WorldPoint location = npc.getWorldLocation();
		if (name == null || location == null || !matchesFilter(name))
		{
			return;
		}
		Player local = client.getLocalPlayer();
		boolean killedByMe = tracker.wasDamagedByMe(index) || (local != null && npc.getInteracting() == local);
		tracker.recordDeath(index, npc.getId(), name, location.getRegionID(), killedByMe, config.onlyMyKills(),
			client.getTickCount());
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		considerNpc(event.getNpc());
	}

	private void considerNpc(NPC npc)
	{
		if (inInstance())
		{
			return;
		}
		String name = cleanName(npc);
		WorldPoint location = npc.getWorldLocation();
		if (name == null || location == null)
		{
			return;
		}
		PermadeathTracker.SpawnOutcome outcome = tracker.recordSpawn(
			npc.getIndex(), npc.getId(), name, location.getRegionID(), now());
		if (outcome != PermadeathTracker.SpawnOutcome.VISIBLE)
		{
			log.debug("Hiding {} (id {}, index {}): {}", name, npc.getId(), npc.getIndex(), outcome);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCounter++;
		if (tickCounter % PRUNE_INTERVAL_TICKS == 0)
		{
			tracker.prune(client.getTickCount(), now());
		}
		if (tickCounter % SAVE_INTERVAL_TICKS == 0)
		{
			saveIfDirty();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				enterWorld(client.getWorld());
				break;
			case HOPPING:
			case LOGIN_SCREEN:
				saveIfDirty();
				tracker.clearSession();
				currentWorld = -1;
				break;
			default:
				break;
		}
	}

	private void enterWorld(int world)
	{
		if (world != currentWorld)
		{
			saveIfDirty();
			// In-flight deaths only mean something on the world they happened on.
			tracker.clearSession();
			currentWorld = world;
			tracker.setCurrentWorld(world);
		}
		if (!loaded)
		{
			loadState();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		clientThread.invoke(this::loadState);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!NpcPermadeathConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("npcNames".equals(event.getKey()))
		{
			nameFilter = parseNames(config.npcNames());
		}
		else if (NpcPermadeathConfig.KEY_FORGET_ALL.equals(event.getKey()) && config.forgetAll())
		{
			configManager.setConfiguration(NpcPermadeathConfig.GROUP, NpcPermadeathConfig.KEY_FORGET_ALL, false);
			clientThread.invoke(this::forgetAll);
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"permadeath".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}
		String[] args = event.getArguments();
		if (args.length > 0 && "reset".equalsIgnoreCase(args[0]))
		{
			forgetAll();
			return;
		}
		summarizeHere();
	}

	private void summarizeHere()
	{
		Player local = client.getLocalPlayer();
		WorldPoint here = local == null ? null : local.getWorldLocation();
		if (here == null)
		{
			return;
		}
		int region = here.getRegionID();
		Map<String, Integer> kills = tracker.killsInRegion(region);
		if (kills.isEmpty())
		{
			message("NPC Permadeath: nothing slain in this area yet. " + tracker.totalKills()
				+ " kill(s) remembered overall. ::permadeath reset forgets them all.");
			return;
		}
		message("NPC Permadeath, this area:");
		kills.forEach((name, count) ->
		{
			AreaKey area = new AreaKey(name, region);
			message(name + ": " + count + totalSuffix(area) + " slain, " + tracker.hiddenHere(area) + " hidden here");
		});
	}

	private void announce(AreaKey area)
	{
		if (!config.announceKills())
		{
			return;
		}
		message(area.getName() + ": " + tracker.kills(area) + totalSuffix(area) + " slain in this area");
	}

	private String totalSuffix(AreaKey area)
	{
		Integer total = totals.get(area.getName(), area.getRegion());
		return total == null ? "" : " of " + total;
	}

	private void forgetAll()
	{
		tracker.clearAll();
		saveState();
		message("NPC Permadeath: all slain NPCs forgotten.");
	}

	private void loadState()
	{
		PermadeathTracker.SavedState state = null;
		String json = configManager.getRSProfileConfiguration(NpcPermadeathConfig.GROUP, KEY_STATE);
		if (json != null)
		{
			try
			{
				state = gson.fromJson(json, PermadeathTracker.SavedState.class);
			}
			catch (JsonSyntaxException e)
			{
				log.warn("Discarding unreadable NPC Permadeath state", e);
			}
		}
		tracker.load(state == null ? new PermadeathTracker.SavedState() : state);
		loaded = true;
		log.debug("Loaded {} kills", tracker.totalKills());
		// NPCs already in view spawned before the state was known.
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			considerNpc(npc);
		}
	}

	private void saveState()
	{
		PermadeathTracker.SavedState state = tracker.toSaved();
		if (state.isEmpty())
		{
			configManager.unsetRSProfileConfiguration(NpcPermadeathConfig.GROUP, KEY_STATE);
		}
		else
		{
			configManager.setRSProfileConfiguration(NpcPermadeathConfig.GROUP, KEY_STATE, gson.toJson(state));
		}
		tracker.markSaved();
	}

	private void saveIfDirty()
	{
		if (loaded && tracker.isDirty())
		{
			saveState();
		}
	}

	private boolean inInstance()
	{
		return client.getTopLevelWorldView().isInstance();
	}

	private static String cleanName(NPC npc)
	{
		String name = npc.getName();
		if (name == null)
		{
			return null;
		}
		String clean = Text.removeTags(name).trim();
		return clean.isEmpty() ? null : clean;
	}

	private boolean matchesFilter(String name)
	{
		if (nameFilter.isEmpty())
		{
			return true;
		}
		for (String pattern : nameFilter)
		{
			if (WildcardMatcher.matches(pattern, name))
			{
				return true;
			}
		}
		return false;
	}

	private static List<String> parseNames(String csv)
	{
		return Text.fromCSV(csv).stream()
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toList());
	}

	private static long now()
	{
		return System.currentTimeMillis();
	}

	private void message(String text)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", text, null);
	}
}
