package com.npcpermadeath;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.util.ImageUtil;
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
	static final String KEY_LEARNED = "learnedSpawns";

	private static final int PRUNE_INTERVAL_TICKS = 100;
	private static final int SAVE_INTERVAL_TICKS = 200;
	private static final int REGION_CHECK_INTERVAL_TICKS = 10;
	/** NPCs walking into view appear about 15 tiles out; a respawn is closer. */
	private static final int LEARN_MAX_DISTANCE = 13;
	/** Moving further than this in one tick means the player teleported. */
	private static final int TELEPORT_DISTANCE = 2;

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

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private EventBus eventBus;

	/** A death whose loot we are waiting on before deciding if it counts. */
	@Value
	private static class Decision
	{
		int index;
		int npcId;
		String name;
		int region;
		WorldPoint tile;
		int deathTick;
	}

	private final PermadeathTracker tracker = new PermadeathTracker();
	private final LootWatcher loot = new LootWatcher();
	private final List<Decision> pendingDecisions = new ArrayList<>();
	private final SpawnLearner learner = new SpawnLearner();
	private GhostManager ghosts;
	private SpawnTotals totals;
	private DataUpdater updater;
	private WorldPoint lastPlayerLocation;
	private PermadeathPanel panel;
	private NavigationButton navButton;
	private ChunkMapOverlay mapOverlay;
	private ChunkPanelOverlay panelOverlay;
	/** Map chunk the player is standing in, for the panel. */
	private int currentRegion = -1;

	private Set<String> bosses = new HashSet<>();
	private volatile List<String> nameFilter = new ArrayList<>();
	private volatile List<String> ignoredNames = new ArrayList<>();
	private NpcNameIndex nameIndex;
	private volatile boolean nameScanStarted;
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
		bosses = loadBosses(getClass().getResourceAsStream("bosses.txt"));
		updater = new DataUpdater(okHttpClient, configManager, clientThread);
		nameFilter = NameList.parse(config.npcNames());
		ignoredNames = NameList.parse(config.ignoredNames());
		nameIndex = new NpcNameIndex();
		totals = new SpawnTotals(okHttpClient, gson, configManager, clientThread);
		totals.load();
		loadSeedSpawns();
		learner.load(loadGlobalList(KEY_LEARNED));
		nameIndex.setSeedNames(learner.allNames());
		clientThread.invoke(this::refreshData);
		panel = new PermadeathPanel(
			area -> clientThread.invoke(() -> forgetArea(area)),
			this::openSettings,
			region -> clientThread.invoke(() -> showOnMap(region)),
			nameIndex::suggest,
			(key, csv) -> configManager.setConfiguration(NpcPermadeathConfig.GROUP, key, csv));
		refreshPanelFilters();
		navButton = NavigationButton.builder()
			.tooltip("NPC Permadeath")
			.icon(ImageUtil.loadImageResource(getClass(), "panel_icon.png"))
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		mapOverlay = new ChunkMapOverlay(this, client, chunkSource);
		panelOverlay = new ChunkPanelOverlay(this, mapOverlay);
		overlayManager.add(mapOverlay);
		overlayManager.add(panelOverlay);
		ghosts = new GhostManager(client, config);
		renderCallbackManager.register(this);
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				enterWorld(client.getWorld());
			}
		});
	}

	/** Reads the NPC definitions a slice at a time on the client thread, once per session. */
	private void startNameScan()
	{
		NpcNameIndex index = nameIndex;
		if (index == null || nameScanStarted)
		{
			return;
		}
		nameScanStarted = true;
		clientThread.invokeLater(() -> index.step(client::getNpcDefinition));
	}

	/** Shows the configured name filters in the panel, from whichever thread the config changed on. */
	private void refreshPanelFilters()
	{
		PermadeathPanel target = panel;
		if (target == null)
		{
			return;
		}
		String never = config.ignoredNames();
		String only = config.npcNames();
		SwingUtilities.invokeLater(() -> target.setFilters(never, only));
	}

	@Override
	protected void shutDown()
	{
		if (nameIndex != null)
		{
			nameIndex.cancel();
		}
		nameIndex = null;
		nameScanStarted = false;
		renderCallbackManager.unregister(this);
		clientThread.invoke(ghosts::clear);
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(mapOverlay);
		overlayManager.remove(panelOverlay);
		mapOverlay = null;
		panelOverlay = null;
		currentRegion = -1;
		navButton = null;
		panel = null;
		saveIfDirty();
		saveLearner();
		tracker.clearAll();
		tracker.markSaved();
		loot.clear();
		learner.clearSession();
		loaded = false;
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
		return !loot.isCursed(renderable);
	}

	// ---- kills -------------------------------------------------------------

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!(event.getActor() instanceof NPC))
		{
			return;
		}
		Hitsplat hitsplat = event.getHitsplat();
		if (hitsplat.isMine() || hitsplat.isOthers())
		{
			tracker.recordHit(((NPC) event.getActor()).getIndex(), hitsplat.getAmount(), hitsplat.isMine());
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
		ghosts.remove(npc);
		if (!npc.isDead())
		{
			tracker.forget(npc.getIndex());
			return;
		}
		handleDeath(npc);
		int tick = client.getTickCount();
		if (config.hideLoot() && tracker.isHidden(npc.getIndex(), npc.getId()))
		{
			loot.armTile(npc.getWorldLocation(), tick);
		}
		PermadeathTracker.PendingDeath death = tracker.takeDeath(npc.getIndex(), npc.getId());
		if (death != null)
		{
			// Loot can land a tick after the despawn, so decide next tick.
			pendingDecisions.add(new Decision(npc.getIndex(), npc.getId(), death.getName(), death.getRegion(),
				npc.getWorldLocation(), tick));
		}
	}

	private void handleDeath(NPC npc)
	{
		int index = npc.getIndex();
		if (tracker.isPending(index))
		{
			return;
		}
		String name = cleanName(npc);
		WorldPoint location = areaLocation(npc);
		if (name == null || location == null)
		{
			return;
		}
		if (!inInstance())
		{
			learner.noteDeath(index, npc.getId(), name, client.getTickCount());
		}
		if (!isEligible(npc, name))
		{
			return;
		}
		tracker.recordDeath(index, npc.getId(), name, location.getRegionID(), client.getTickCount());
	}

	/**
	 * Decides whether a finished death counts. Loot settles it when there is
	 * any: the kill is yours if your loot appeared on the tile. NPCs that drop
	 * nothing fall back to the damage rule.
	 */
	private void resolveDecisions(int tick)
	{
		Iterator<Decision> it = pendingDecisions.iterator();
		while (it.hasNext())
		{
			Decision d = it.next();
			if (tick < d.getDeathTick() + LootWatcher.WINDOW_TICKS)
			{
				continue;
			}
			it.remove();
			boolean mine;
			if (d.getTile() != null && loot.sawLoot(d.getTile(), d.getDeathTick()))
			{
				mine = loot.sawMyLoot(d.getTile(), d.getDeathTick());
			}
			else
			{
				mine = tracker.isMyKill(d.getIndex());
			}
			if (!mine && config.onlyMyKills())
			{
				log.debug("Not counting {} (index {}): not your kill", d.getName(), d.getIndex());
				tracker.forget(d.getIndex());
				continue;
			}
			int region = creditRegion(d.getName(), d.getRegion());
			AreaKey area = tracker.countKill(d.getIndex(), d.getNpcId(), d.getName(), region, now());
			if (area != null)
			{
				log.debug("Counted kill of {} in region {} (index {})", area.getName(), area.getRegion(), d.getIndex());
				saveState();
				refreshPanel();
				boolean totalKnown = spawnTotal(area) != null;
				announce(area);
				// If the total was missing, say the line again once the lookup fills it in.
				totals.ensure(area.getName(), now(), () ->
				{
					rebalanceKills();
					if (!totalKnown && spawnTotal(area) != null)
					{
						announce(area);
					}
				});
			}
		}
	}

	// ---- hiding ------------------------------------------------------------

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		learnSpawn(event.getNpc());
		considerNpc(event.getNpc());
		syncGhost(event.getNpc());
	}

	/** An NPC that changes form can stop (or start) being a hidden one, and its ghost needs the new model. */
	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		syncGhost(event.getNpc());
	}

	/** If this is an NPC we saw die coming back, the tile it appears on is its spawn point. */
	private void learnSpawn(NPC npc)
	{
		String name = cleanName(npc);
		WorldPoint location = npc.getWorldLocation();
		if (name == null || location == null)
		{
			return;
		}
		SpawnLearner.SpawnTile tile = learner.noteSpawn(npc.getIndex(), npc.getId(), name, location.getX(),
			location.getY(), location.getPlane(), client.getTickCount(), isTrustworthySpawnTile(location));
		if (tile != null)
		{
			log.debug("Learned spawn tile of {} at {},{},{}", name, tile.getX(), tile.getY(), tile.getPlane());
			refreshPanel();
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
		if (local == null || inInstance() || lastPlayerLocation == null)
		{
			return false;
		}
		WorldPoint playerLocation = local.getWorldLocation();
		return playerLocation != null
			&& npcLocation.distanceTo(playerLocation) <= LEARN_MAX_DISTANCE
			&& playerLocation.distanceTo(lastPlayerLocation) <= TELEPORT_DISTANCE;
	}

	/** Hides the NPC if its area still owes kills. */
	private void considerNpc(NPC npc)
	{
		String name = cleanName(npc);
		WorldPoint location = areaLocation(npc);
		if (name == null || location == null || !isEligible(npc, name) || isAttackingMe(npc))
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
	public void onInteractingChanged(InteractingChanged event)
	{
		if (!config.revealAttackers() || !(event.getSource() instanceof NPC))
		{
			return;
		}
		NPC npc = (NPC) event.getSource();
		Player local = client.getLocalPlayer();
		if (local == null || event.getTarget() != local || !tracker.isHidden(npc.getIndex(), npc.getId()))
		{
			return;
		}
		AreaKey area = tracker.release(npc.getIndex());
		if (area == null)
		{
			return;
		}
		log.debug("Revealing {} (index {}) because it is attacking the player", area.getName(), npc.getIndex());
		syncGhost(npc);
		backfill(area);
	}

	/** After a reveal, hide another NPC of the same kind in the area if one is in view. */
	private void backfill(AreaKey area)
	{
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (!tracker.hasDeficit(area))
			{
				return;
			}
			if (!area.getName().equals(cleanName(npc)) || tracker.isHidden(npc.getIndex(), npc.getId()))
			{
				continue;
			}
			considerNpc(npc);
			syncGhost(npc);
		}
	}

	private boolean isAttackingMe(NPC npc)
	{
		Player local = client.getLocalPlayer();
		return config.revealAttackers() && local != null && npc.getInteracting() == local;
	}

	// ---- ghosts ------------------------------------------------------------

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		ghosts.update();
	}

	private void syncGhost(NPC npc)
	{
		ghosts.sync(npc, tracker.isHidden(npc.getIndex(), npc.getId()));
	}

	/** Cheap re-check of every ghost against the tracker, for changes nothing announces. */
	private void resyncGhosts()
	{
		ghosts.resync(client.getTopLevelWorldView().npcs(), npc -> tracker.isHidden(npc.getIndex(), npc.getId()));
	}

	/** Builds every ghost from scratch, after a setting or the set of slain NPCs changed. */
	private void rebuildGhosts()
	{
		ghosts.rebuildAll(client.getTopLevelWorldView().npcs(), npc -> tracker.isHidden(npc.getIndex(), npc.getId()));
	}

	// ---- loot --------------------------------------------------------------

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		loot.onItemSpawned(event.getItem(), event.getTile().getWorldLocation(), event.getTile().getSceneLocation(),
			client.getTickCount());
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		loot.onItemDespawned(event.getItem());
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (loot.cursedCount() == 0 || !isGroundItemAction(event.getType()))
		{
			return;
		}
		if (loot.blocksMenu(event.getIdentifier(), event.getActionParam0(), event.getActionParam1()))
		{
			client.getMenu().removeMenuEntry(event.getMenuEntry());
		}
	}

	private static boolean isGroundItemAction(int type)
	{
		switch (MenuAction.of(type))
		{
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case EXAMINE_ITEM_GROUND:
			case ITEM_USE_ON_GROUND_ITEM:
			case WIDGET_TARGET_ON_GROUND_ITEM:
				return true;
			default:
				return false;
		}
	}

	// ---- lifecycle ---------------------------------------------------------

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tickCounter++;
		resolveDecisions(client.getTickCount());
		loot.tick(client.getTickCount());
		Player local = client.getLocalPlayer();
		lastPlayerLocation = local == null ? null : local.getWorldLocation();
		if (tickCounter % REGION_CHECK_INTERVAL_TICKS == 0 && lastPlayerLocation != null
			&& lastPlayerLocation.getRegionID() != currentRegion)
		{
			currentRegion = lastPlayerLocation.getRegionID();
			refreshPanel();
		}
		if (tickCounter % PRUNE_INTERVAL_TICKS == 0)
		{
			tracker.prune(client.getTickCount(), now());
			learner.prune(client.getTickCount());
			resyncGhosts();
		}
		if (tickCounter % SAVE_INTERVAL_TICKS == 0)
		{
			saveIfDirty();
			saveLearner();
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
				saveLearner();
				tracker.clearSession();
				learner.clearSession();
				loot.clear();
				ghosts.clear();
				pendingDecisions.clear();
				currentWorld = -1;
				lastPlayerLocation = null;
				break;
			default:
				break;
		}
	}

	private void enterWorld(int world)
	{
		startNameScan();
		if (world != currentWorld)
		{
			saveIfDirty();
			// In-flight deaths only mean something on the world they happened on.
			tracker.clearSession();
			currentWorld = world;
			tracker.setCurrentWorld(world);
			refreshPanel();
			resyncGhosts();
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
		switch (event.getKey())
		{
			case NpcPermadeathConfig.KEY_NPC_NAMES:
				nameFilter = NameList.parse(config.npcNames());
				refreshPanelFilters();
				break;
			case NpcPermadeathConfig.KEY_IGNORED_NAMES:
				ignoredNames = NameList.parse(config.ignoredNames());
				refreshPanelFilters();
				break;
			case NpcPermadeathConfig.KEY_GHOSTS:
			case NpcPermadeathConfig.KEY_GHOST_OPACITY:
			case NpcPermadeathConfig.KEY_GHOST_TINT:
				clientThread.invoke(this::rebuildGhosts);
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
		message("NPC Permadeath: " + learner.seedCount() + " bundled spawn tiles, " + learner.learnedCount()
			+ " observed by you.");
		if (kills.isEmpty())
		{
			message("Nothing slain in this area yet. " + tracker.totalKills()
				+ " kill(s) remembered overall. ::permadeath reset forgets them all.");
			return;
		}
		message("NPC Permadeath, " + chunkHeading(new AreaKey("", region)) + ":");
		kills.forEach((name, count) ->
		{
			AreaKey area = new AreaKey(name, region);
			message(name + ": " + count + totalSuffix(area) + " slain, " + tracker.hiddenHere(area) + " hidden here");
		});
	}

	/** Wiki place name for the chunk, or null. */
	private String placeName(AreaKey area)
	{
		return totals.label(area.getName(), area.getRegion());
	}

	/** "Lumbridge (3200, 3200)", or just the coordinates when the place is unknown. */
	private String chunkHeading(AreaKey area)
	{
		String place = placeName(area);
		String corner = "(" + ((area.getRegion() >> 8) << 6) + ", " + ((area.getRegion() & 0xff) << 6) + ")";
		return place == null ? "Chunk " + corner : place + " " + corner;
	}

	private static WorldPoint chunkCentre(int region)
	{
		return new WorldPoint(((region >> 8) << 6) + ChunkMapOverlay.CHUNK_TILES / 2,
			((region & 0xff) << 6) + ChunkMapOverlay.CHUNK_TILES / 2, 0);
	}

	/** Opens this plugin's settings by asking the config panel the same way an overlay's Configure entry does. */
	private void openSettings()
	{
		if (mapOverlay != null)
		{
			eventBus.post(new OverlayMenuClicked(
				new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", "NPC Permadeath"), mapOverlay));
		}
	}

	private void showOnMap(int region)
	{
		client.getWorldMap().setWorldMapPositionTarget(chunkCentre(region));
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null || map.isHidden())
		{
			message("NPC Permadeath: open the world map to see the chunk.");
		}
	}

	private void forgetArea(AreaKey area)
	{
		tracker.forgetArea(area);
		rebuildGhosts();
		saveState();
		refreshPanel();
		message("NPC Permadeath: " + area.getName() + " in " + chunkHeading(area) + " forgotten.");
	}

	private static final int[][] NEIGHBOURS = {
		{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}
	};

	private static int neighbour(int region, int[] d)
	{
		return (((region >> 8) + d[0]) << 8) | ((region & 0xff) + d[1]);
	}

	/** Known spawns of the NPC in the chunk, or 0. */
	private int spawnCount(String name, int region)
	{
		Integer total = spawnTotal(new AreaKey(name, region));
		return total == null ? 0 : total;
	}

	/** Spawns not yet killed; unlimited when the total is unknown. */
	private int headroom(String name, int region)
	{
		Integer total = spawnTotal(new AreaKey(name, region));
		return total == null ? Integer.MAX_VALUE : total - tracker.kills(new AreaKey(name, region));
	}

	/**
	 * Which chunk a kill belongs to. An NPC killed in a chunk it does not
	 * spawn in, or one whose spawns are all already dead, wandered in from a
	 * neighbouring chunk, so the kill is credited there instead.
	 */
	private int creditRegion(String name, int region)
	{
		int chosen = region;
		if (spawnCount(name, region) == 0)
		{
			int best = 0;
			for (int[] d : NEIGHBOURS)
			{
				int candidate = neighbour(region, d);
				if (spawnCount(name, candidate) > best)
				{
					best = spawnCount(name, candidate);
					chosen = candidate;
				}
			}
		}
		if (headroom(name, chosen) <= 0)
		{
			int best = 0;
			int alt = chosen;
			for (int[] d : NEIGHBOURS)
			{
				int candidate = neighbour(chosen, d);
				if (spawnCount(name, candidate) > 0 && headroom(name, candidate) > best)
				{
					best = headroom(name, candidate);
					alt = candidate;
				}
			}
			chosen = alt;
		}
		return chosen;
	}

	/**
	 * Fixes up recorded kills once spawn data is known: kills in chunks the
	 * NPC does not spawn in move next door, and chunks with more kills than
	 * spawns hand the surplus to neighbours with room.
	 */
	private void rebalanceKills()
	{
		boolean moved = false;
		for (AreaKey area : new ArrayList<>(tracker.killsByArea().keySet()))
		{
			String name = area.getName();
			int region = area.getRegion();
			if (spawnCount(name, region) == 0)
			{
				int target = creditRegion(name, region);
				if (target != region)
				{
					log.debug("Moving {} kills of {} from chunk {} to {}", tracker.kills(area), name, region, target);
					tracker.rehome(area, target);
					moved = true;
				}
				continue;
			}
			int surplus = -headroom(name, region);
			for (int[] d : NEIGHBOURS)
			{
				if (surplus <= 0)
				{
					break;
				}
				int candidate = neighbour(region, d);
				int room = spawnCount(name, candidate) > 0 ? headroom(name, candidate) : 0;
				if (room > 0)
				{
					int n = tracker.moveKills(area, candidate, Math.min(room, surplus));
					log.debug("Moving {} surplus kills of {} from chunk {} to {}", n, name, region, candidate);
					surplus -= n;
					moved = true;
				}
			}
		}
		if (moved)
		{
			saveState();
		}
		refreshPanel();
	}

	/**
	 * Every NPC type known in the chunk plus anything killed there, as panel
	 * rows: the ones you have started on first, then the ones fully cleared,
	 * then the untouched, alphabetical within each group.
	 */
	private List<PermadeathPanel.Row> rowsFor(int region)
	{
		Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		names.addAll(learner.namesInRegion(region));
		names.addAll(tracker.killsInRegion(region).keySet());
		List<PermadeathPanel.Row> rows = new ArrayList<>();
		for (String name : names)
		{
			AreaKey area = new AreaKey(name, region);
			rows.add(new PermadeathPanel.Row(area, chunkHeading(area), tracker.kills(area), spawnTotal(area),
				tracker.hiddenHere(area)));
		}
		rows.sort(java.util.Comparator.comparingInt((PermadeathPanel.Row r) -> r.getProgress().ordinal())
			.thenComparing(r -> r.getArea().getName(), String.CASE_INSENSITIVE_ORDER));
		return rows;
	}

	private ChunkMapOverlay.State chunkState(int region)
	{
		Map<String, Integer> kills = tracker.killsInRegion(region);
		if (kills.isEmpty())
		{
			return ChunkMapOverlay.State.NONE;
		}
		for (String name : learner.namesInRegion(region))
		{
			Integer total = spawnTotal(new AreaKey(name, region));
			if (total == null || kills.getOrDefault(name, 0) < total)
			{
				return ChunkMapOverlay.State.SOME;
			}
		}
		return ChunkMapOverlay.State.ALL;
	}

	private final ChunkMapOverlay.ChunkSource chunkSource = new ChunkMapOverlay.ChunkSource()
	{
		@Override
		public boolean isKnown(int region)
		{
			return learner.hasSpawns(region) || !tracker.killsInRegion(region).isEmpty();
		}

		@Override
		public ChunkMapOverlay.State state(int region)
		{
			return chunkState(region);
		}

		@Override
		public String heading(int region)
		{
			return chunkHeading(new AreaKey("", region));
		}

		@Override
		public List<ChunkMapOverlay.Entry> entries(int region)
		{
			List<ChunkMapOverlay.Entry> entries = new ArrayList<>();
			for (PermadeathPanel.Row row : rowsFor(region))
			{
				entries.add(new ChunkMapOverlay.Entry(row.getArea().getName(), row.getKills(), row.getTotal()));
			}
			return entries;
		}
	};

	/** Snapshots the kill list on the client thread and hands it to the panel on the Swing thread. */
	private void refreshPanel()
	{
		PermadeathPanel target = panel;
		if (target == null)
		{
			return;
		}
		Set<Integer> regions = new java.util.TreeSet<>();
		tracker.killsByArea().keySet().forEach(area -> regions.add(area.getRegion()));
		if (currentRegion >= 0)
		{
			regions.add(currentRegion);
		}
		List<PermadeathPanel.Row> rows = new ArrayList<>();
		regions.forEach(region -> rows.addAll(rowsFor(region)));
		SwingUtilities.invokeLater(() -> target.update(rows));
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
		Integer total = spawnTotal(area);
		return total == null ? "" : " of " + total;
	}

	/**
	 * Spawns of the NPC in the chunk: the wiki count or the number of known
	 * spawn tiles (bundled seed, observed, shared), whichever is larger.
	 * Null if no source knows anything.
	 */
	private Integer spawnTotal(AreaKey area)
	{
		Integer wiki = totals.get(area.getName(), area.getRegion());
		int known = learner.countInRegion(area.getName(), area.getRegion());
		if (wiki == null)
		{
			return known == 0 ? null : known;
		}
		return Math.max(wiki, known);
	}

	// ---- observed spawn data -------------------------------------------

	private void loadSeedSpawns()
	{
		try (InputStream in = getClass().getResourceAsStream("spawns.csv.gz"))
		{
			if (in == null)
			{
				log.warn("spawns.csv.gz is missing; chunk NPC lists will only show what you have seen");
				return;
			}
			learner.loadSeed(in);
			log.debug("Loaded {} bundled spawn tiles", learner.seedCount());
		}
		catch (IOException e)
		{
			log.warn("Could not read spawns.csv.gz", e);
		}
	}

	/**
	 * Layers the latest data files from the plugin repository over the
	 * bundled ones: whatever was cached from an earlier fetch first, then a
	 * fresh copy if the cache is a week old.
	 */
	private void refreshData()
	{
		byte[] spawns = updater.cached("spawns.csv.gz");
		if (spawns != null)
		{
			loadExtraSpawns(spawns);
		}
		byte[] bossList = updater.cached("bosses.txt");
		if (bossList != null)
		{
			bosses.addAll(loadBosses(new ByteArrayInputStream(bossList)));
		}
		updater.refreshIfStale("spawns.csv.gz", now(), bytes ->
		{
			loadExtraSpawns(bytes);
			refreshPanel();
		});
		updater.refreshIfStale("bosses.txt", now(), bytes -> bosses.addAll(loadBosses(new ByteArrayInputStream(bytes))));
	}

	private void loadExtraSpawns(byte[] gzipped)
	{
		try
		{
			learner.loadSeed(new ByteArrayInputStream(gzipped));
			NpcNameIndex index = nameIndex;
			if (index != null)
			{
				index.setSeedNames(learner.allNames());
			}
			log.debug("Spawn tiles known after update: {}", learner.seedCount());
		}
		catch (IOException e)
		{
			log.debug("Ignoring unreadable spawn data update", e);
		}
	}

	private void saveLearner()
	{
		if (!learner.isDirty())
		{
			return;
		}
		saveGlobalList(KEY_LEARNED, learner.serializeLearned());
		learner.markSaved();
	}

	private List<String> loadGlobalList(String key)
	{
		String stored = configManager.getConfiguration(NpcPermadeathConfig.GROUP, key);
		List<String> out = new ArrayList<>();
		if (stored != null)
		{
			for (String entry : stored.split(";"))
			{
				if (!entry.isEmpty())
				{
					out.add(entry);
				}
			}
		}
		return out;
	}

	private void saveGlobalList(String key, List<String> entries)
	{
		if (entries.isEmpty())
		{
			configManager.unsetConfiguration(NpcPermadeathConfig.GROUP, key);
		}
		else
		{
			configManager.setConfiguration(NpcPermadeathConfig.GROUP, key, String.join(";", entries));
		}
	}

	private void forgetAll()
	{
		tracker.clearAll();
		loot.clear();
		rebuildGhosts();
		saveState();
		refreshPanel();
		message("NPC Permadeath: all slain NPCs forgotten.");
	}

	// ---- persistence -------------------------------------------------------

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
		rebalanceKills();
		for (AreaKey area : tracker.killsByArea().keySet())
		{
			totals.ensure(area.getName(), now(), this::rebalanceKills);
		}
		refreshPanel();
		// NPCs already in view spawned before the state was known.
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			considerNpc(npc);
		}
		rebuildGhosts();
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

	// ---- eligibility -------------------------------------------------------

	/** Whether this NPC can be killed for good under the current settings. */
	private boolean isEligible(NPC npc, String name)
	{
		if (inInstance() && !config.includeInstances())
		{
			return false;
		}
		if (!config.includeBosses() && bosses.contains(name.toLowerCase()))
		{
			return false;
		}
		int maxLevel = config.maxCombatLevel();
		if (maxLevel > 0 && npc.getCombatLevel() > maxLevel)
		{
			return false;
		}
		if (matchesAny(ignoredNames, name))
		{
			return false;
		}
		return nameFilter.isEmpty() || matchesAny(nameFilter, name);
	}

	/**
	 * The tile used to place the NPC in an area. Inside an instance the
	 * template coordinates are used so the same boss room always maps to the
	 * same area no matter where the instance was built.
	 */
	private WorldPoint areaLocation(NPC npc)
	{
		if (inInstance())
		{
			return npc.getLocalLocation() == null ? null : WorldPoint.fromLocalInstance(client, npc.getLocalLocation());
		}
		return npc.getWorldLocation();
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

	private static boolean matchesAny(List<String> patterns, String name)
	{
		for (String pattern : patterns)
		{
			if (WildcardMatcher.matches(pattern, name))
			{
				return true;
			}
		}
		return false;
	}

	private static Set<String> loadBosses(InputStream source)
	{
		Set<String> names = new HashSet<>();
		try (InputStream in = source)
		{
			if (in == null)
			{
				log.warn("bosses.txt is missing; the boss filter will do nothing");
				return names;
			}
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = reader.readLine()) != null)
			{
				line = line.trim();
				if (!line.isEmpty() && !line.startsWith("#"))
				{
					names.add(line.toLowerCase());
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Could not read bosses.txt", e);
		}
		return names;
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
