package com.npcpermadeath;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Comparator;
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

@Slf4j
@PluginDescriptor(
	name = "NPC Permadeath",
	description = "NPCs you kill stay dead: once you have slain an NPC it is hidden when it respawns, so areas slowly empty out",
	tags = {"npc", "hide", "kill", "respawn", "permadeath", "entity", "hider", "immersion"}
)
public class NpcPermadeathPlugin extends Plugin implements RenderCallback
{
	static final String KEY_HIDDEN_NPCS = "hiddenNpcs";
	static final String KEY_CULLED_SPAWNS = "culledSpawns";

	/** NPCs walking into view appear about 15 tiles out; a respawn is closer. */
	private static final int LEARN_MAX_DISTANCE = 13;
	/** Moving further than this in one tick means the player teleported. */
	private static final int TELEPORT_DISTANCE = 2;
	private static final int NPC_LIST_LIMIT = 12;

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

	private final PermadeathTracker tracker = new PermadeathTracker();

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
		tracker.setAcrossWorlds(config.acrossWorlds());
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
		tracker.clearAll();
		currentWorld = -1;
		lastPlayerLocation = null;
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
		if (tracker.recordDespawn(npc.getIndex(), npc.getId()))
		{
			log.debug("{} (id {}, index {}) is now permanently dead", npc.getName(), npc.getId(), npc.getIndex());
			saveState();
		}
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
		tracker.recordDeath(index, npc.getId(), killedByMe, config.onlyMyKills(), client.getTickCount());
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
		if (result.isChanged())
		{
			saveState();
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
				enterWorld(client.getWorld());
				break;
			case HOPPING:
			case LOGIN_SCREEN:
				tracker.clearSession();
				lastPlayerLocation = null;
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
			// In-flight deaths only mean something on the world they happened on.
			tracker.clearSession();
			currentWorld = world;
		}
		tracker.setCurrentWorld(world);
		loadState();
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
		switch (event.getKey())
		{
			case "npcNames":
				nameFilter = parseNames(config.npcNames());
				break;
			case "acrossWorlds":
				tracker.setAcrossWorlds(config.acrossWorlds());
				break;
			case NpcPermadeathConfig.KEY_FORGET_ALL:
				if (config.forgetAll())
				{
					configManager.setConfiguration(NpcPermadeathConfig.GROUP, NpcPermadeathConfig.KEY_FORGET_ALL, false);
					clientThread.invoke(this::forgetAll);
				}
				break;
			default:
				break;
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
		String sub = args.length > 0 ? args[0].toLowerCase() : "";
		switch (sub)
		{
			case "reset":
				forgetAll();
				break;
			case "npcs":
				listNearbyNpcs();
				break;
			default:
				message("NPC Permadeath: " + tracker.hiddenCount() + " slain NPC(s) remembered, "
					+ tracker.getCulledSpawns().size() + " spawn tile(s) learned. "
					+ "::permadeath npcs lists nearby NPC indices, ::permadeath reset forgets everything.");
				break;
		}
	}

	/** Prints nearby NPCs with their indices, for checking index stability between worlds. */
	private void listNearbyNpcs()
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}
		WorldPoint here = local.getWorldLocation();
		List<NPC> npcs = new ArrayList<>();
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc.getName() != null && npc.getWorldLocation() != null)
			{
				npcs.add(npc);
			}
		}
		npcs.sort(Comparator.comparingInt(n -> n.getWorldLocation().distanceTo(here)));
		if (npcs.size() > NPC_LIST_LIMIT)
		{
			npcs = npcs.subList(0, NPC_LIST_LIMIT);
		}
		message("World " + client.getWorld() + ", nearest NPCs (name, id, index, tile):");
		for (NPC npc : npcs)
		{
			WorldPoint p = npc.getWorldLocation();
			String hidden = tracker.isHidden(npc.getIndex(), npc.getId()) ? " [hidden]" : "";
			message(Text.removeTags(npc.getName()) + " id " + npc.getId() + " #" + npc.getIndex()
				+ " @ " + p.getX() + "," + p.getY() + "," + p.getPlane() + hidden);
		}
	}

	private void forgetAll()
	{
		tracker.clearAll();
		saveState();
		message("NPC Permadeath: all slain NPCs forgotten.");
	}

	private void loadState()
	{
		tracker.loadHidden(loadList(KEY_HIDDEN_NPCS));
		List<SpawnKey> spawns = new ArrayList<>();
		for (String entry : loadList(KEY_CULLED_SPAWNS))
		{
			SpawnKey key = SpawnKey.parse(entry);
			if (key != null)
			{
				spawns.add(key);
			}
		}
		tracker.setCulledSpawns(spawns);
		log.debug("Loaded {} slain NPCs and {} spawn tiles", tracker.hiddenCount(), spawns.size());
	}

	private List<String> loadList(String key)
	{
		String stored = configManager.getRSProfileConfiguration(NpcPermadeathConfig.GROUP, key);
		return stored == null ? new ArrayList<>() : Text.fromCSV(stored);
	}

	private void saveState()
	{
		saveList(KEY_HIDDEN_NPCS, tracker.serializeHidden());
		saveList(KEY_CULLED_SPAWNS, tracker.getCulledSpawns().stream()
			.map(SpawnKey::serialize)
			.sorted()
			.collect(Collectors.toList()));
	}

	private void saveList(String key, List<String> entries)
	{
		if (entries.isEmpty())
		{
			configManager.unsetRSProfileConfiguration(NpcPermadeathConfig.GROUP, key);
		}
		else
		{
			configManager.setRSProfileConfiguration(NpcPermadeathConfig.GROUP, key, String.join(",", entries));
		}
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
