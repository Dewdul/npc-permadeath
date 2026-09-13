package com.npcpermadeath;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.List;
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
import net.runelite.client.callback.Hooks;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;

@Slf4j
@PluginDescriptor(
	name = "NPC Permadeath",
	description = "NPCs you kill stay dead: once you have slain an NPC it is hidden when it respawns, so areas slowly empty out",
	tags = {"npc", "hide", "kill", "respawn", "permadeath", "entity", "hider", "immersion"}
)
public class NpcPermadeathPlugin extends Plugin
{
	static final String KEY_CULLED_SPAWNS = "culledSpawns";

	/** NPCs walking into view appear about 15 tiles out; a respawn is closer. */
	private static final int LEARN_MAX_DISTANCE = 13;
	/** Moving further than this in one tick means the player teleported. */
	private static final int TELEPORT_DISTANCE = 2;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Hooks hooks;

	@Inject
	private ConfigManager configManager;

	@Inject
	private NpcPermadeathConfig config;

	private final PermadeathTracker tracker = new PermadeathTracker();
	private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;

	private List<String> nameFilter = new ArrayList<>();
	private int currentWorld = -1;
	private WorldPoint lastPlayerLocation;

	@Provides
	NpcPermadeathConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(NpcPermadeathConfig.class);
	}

	@Override
	protected void startUp()
	{
		nameFilter = parseNames(config.npcNames());
		hooks.registerRenderableDrawListener(drawListener);
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				currentWorld = client.getWorld();
				loadCulledSpawns();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		hooks.unregisterRenderableDrawListener(drawListener);
		tracker.clearAll();
		currentWorld = -1;
		lastPlayerLocation = null;
	}

	boolean shouldDraw(Renderable renderable, boolean drawingUI)
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
		tracker.recordDespawn(npc.getIndex());
	}

	private void handleDeath(NPC npc)
	{
		int index = npc.getIndex();
		if (tracker.isPending(index) || !matchesFilter(npc.getName()))
		{
			return;
		}
		Player local = client.getLocalPlayer();
		boolean killedByMe = tracker.wasDamagedByMe(index) || (local != null && npc.getInteracting() == local);
		if (tracker.recordDeath(index, npc.getId(), killedByMe, config.onlyMyKills(), client.getTickCount()))
		{
			log.debug("Tracking death of {} (id {}, index {})", npc.getName(), npc.getId(), index);
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		WorldPoint location = npc.getWorldLocation();
		SpawnKey key = location == null ? null : SpawnKey.of(npc.getId(), location);
		boolean canLearn = config.rememberSpawns() && location != null && isTrustworthySpawnTile(location);

		PermadeathTracker.SpawnResult result = tracker.recordSpawn(
			npc.getIndex(), npc.getId(), key, canLearn, client.getTickCount());

		if (result.getOutcome() != PermadeathTracker.SpawnOutcome.VISIBLE)
		{
			log.debug("Hiding {} (id {}, index {}): {}", npc.getName(), npc.getId(), npc.getIndex(), result.getOutcome());
		}
		if (result.isLearned())
		{
			saveCulledSpawns();
		}
	}

	/**
	 * A respawn happens on the NPC's spawn tile, but an NPC can also appear
	 * because it walked into range or because the player teleported next to
	 * it. Only trust the tile when neither of those explains the spawn.
	 */
	private boolean isTrustworthySpawnTile(WorldPoint npcLocation)
	{
		Player local = client.getLocalPlayer();
		if (local == null || client.getTopLevelWorldView().isInstance())
		{
			return false;
		}
		WorldPoint playerLocation = local.getWorldLocation();
		if (playerLocation == null || npcLocation.distanceTo(playerLocation) > LEARN_MAX_DISTANCE)
		{
			return false;
		}
		return lastPlayerLocation != null && playerLocation.distanceTo(lastPlayerLocation) <= TELEPORT_DISTANCE;
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		lastPlayerLocation = local == null ? null : local.getWorldLocation();
		tracker.prunePending(client.getTickCount());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				if (client.getWorld() != currentWorld)
				{
					// NPC indices only mean something within one world.
					tracker.clearRuntime();
					currentWorld = client.getWorld();
				}
				loadCulledSpawns();
				break;
			case HOPPING:
			case LOGIN_SCREEN:
				tracker.clearRuntime();
				lastPlayerLocation = null;
				currentWorld = -1;
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		clientThread.invoke(this::loadCulledSpawns);
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
		message("NPC Permadeath: " + tracker.hiddenCount() + " NPC(s) hidden on this world, "
			+ tracker.getCulledSpawns().size() + " spawn point(s) remembered. ::permadeath reset forgets them all.");
	}

	private void forgetAll()
	{
		tracker.clearAll();
		saveCulledSpawns();
		message("NPC Permadeath: all slain NPCs forgotten.");
	}

	private void loadCulledSpawns()
	{
		String stored = configManager.getRSProfileConfiguration(NpcPermadeathConfig.GROUP, KEY_CULLED_SPAWNS);
		List<SpawnKey> spawns = new ArrayList<>();
		if (stored != null)
		{
			for (String entry : Text.fromCSV(stored))
			{
				SpawnKey key = SpawnKey.parse(entry);
				if (key != null)
				{
					spawns.add(key);
				}
			}
		}
		tracker.setCulledSpawns(spawns);
		log.debug("Loaded {} remembered spawn points", spawns.size());
	}

	private void saveCulledSpawns()
	{
		if (tracker.getCulledSpawns().isEmpty())
		{
			configManager.unsetRSProfileConfiguration(NpcPermadeathConfig.GROUP, KEY_CULLED_SPAWNS);
			return;
		}
		String csv = tracker.getCulledSpawns().stream()
			.map(SpawnKey::serialize)
			.sorted()
			.collect(Collectors.joining(","));
		configManager.setRSProfileConfiguration(NpcPermadeathConfig.GROUP, KEY_CULLED_SPAWNS, csv);
	}

	private boolean matchesFilter(String npcName)
	{
		if (nameFilter.isEmpty())
		{
			return true;
		}
		if (npcName == null)
		{
			return false;
		}
		String name = Text.removeTags(npcName);
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

	private void message(String text)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", text, null);
	}
}
