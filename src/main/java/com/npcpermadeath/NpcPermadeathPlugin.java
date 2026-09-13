package com.npcpermadeath;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import java.io.BufferedReader;
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
import java.util.stream.Collectors;
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
import java.awt.image.BufferedImage;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuEntryAdded;
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
import net.runelite.client.ui.overlay.worldmap.WorldMapOverlay;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
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
	static final String KEY_PENDING = "pendingSpawns";

	private static final int PRUNE_INTERVAL_TICKS = 100;
	private static final int SAVE_INTERVAL_TICKS = 200;
	private static final int UPLOAD_INTERVAL_TICKS = 500;
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
	private WorldMapOverlay worldMapOverlay;

	@Inject
	private WorldMapPointManager worldMapPointManager;

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
	private SpawnTotals totals;
	private SpawnSync sync;
	private WorldPoint lastPlayerLocation;
	private PermadeathPanel panel;
	private NavigationButton navButton;
	private ChunkMapOverlay mapOverlay;
	private BufferedImage mapIcon;
	private final List<WorldMapPoint> mapPoints = new ArrayList<>();

	private Set<String> bosses = new HashSet<>();
	private List<String> nameFilter = new ArrayList<>();
	private List<String> ignoredNames = new ArrayList<>();
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
		bosses = loadBosses();
		nameFilter = parseNames(config.npcNames());
		ignoredNames = parseNames(config.ignoredNames());
		totals = new SpawnTotals(okHttpClient, gson, configManager, clientThread);
		totals.load();
		sync = new SpawnSync(okHttpClient, gson, configManager, clientThread);
		learner.load(loadGlobalList(KEY_LEARNED), loadGlobalList(KEY_PENDING));
		learner.addCommunity(sync.load());
		mapIcon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		panel = new PermadeathPanel(
			area -> clientThread.invoke(() -> forgetArea(area)),
			this::openSettings,
			region -> clientThread.invoke(() -> showOnMap(region)));
		navButton = NavigationButton.builder()
			.tooltip("NPC Permadeath")
			.icon(mapIcon)
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		mapOverlay = new ChunkMapOverlay(this, client, worldMapOverlay);
		overlayManager.add(mapOverlay);
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
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(mapOverlay);
		worldMapPointManager.removeIf(mapPoints::contains);
		mapPoints.clear();
		mapOverlay = null;
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
			int region = totals.homeRegion(d.getName(), d.getRegion());
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
					rehomeBorderKills();
					if (!totalKnown && spawnTotal(area) != null)
					{
						announce(area);
					}
				});
				fetchCommunity(area.getName());
			}
		}
	}

	// ---- hiding ------------------------------------------------------------

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		learnSpawn(event.getNpc());
		considerNpc(event.getNpc());
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
		}
	}

	private boolean isAttackingMe(NPC npc)
	{
		Player local = client.getLocalPlayer();
		return config.revealAttackers() && local != null && npc.getInteracting() == local;
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
		if (tickCounter % PRUNE_INTERVAL_TICKS == 0)
		{
			tracker.prune(client.getTickCount(), now());
			learner.prune(client.getTickCount());
		}
		if (tickCounter % SAVE_INTERVAL_TICKS == 0)
		{
			saveIfDirty();
			saveLearner();
		}
		if (tickCounter % UPLOAD_INTERVAL_TICKS == 0)
		{
			shareSpawns();
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
				shareSpawns();
				tracker.clearSession();
				learner.clearSession();
				loot.clear();
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
		if (world != currentWorld)
		{
			saveIfDirty();
			// In-flight deaths only mean something on the world they happened on.
			tracker.clearSession();
			currentWorld = world;
			tracker.setCurrentWorld(world);
			refreshPanel();
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
			case "npcNames":
				nameFilter = parseNames(config.npcNames());
				break;
			case "ignoredNames":
				ignoredNames = parseNames(config.ignoredNames());
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
		message("NPC Permadeath: " + learner.learnedCount() + " spawn tile(s) observed, " + learner.communityCount()
			+ " from other players, " + learner.pendingCount() + " waiting to share.");
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
		saveState();
		refreshPanel();
		message("NPC Permadeath: " + area.getName() + " in " + chunkHeading(area) + " forgotten.");
	}

	/**
	 * Kills recorded in a chunk the wiki says the NPC does not spawn in (it
	 * wandered over a border) are moved to the neighbouring chunk that has
	 * the spawns, once the wiki data is available.
	 */
	private void rehomeBorderKills()
	{
		boolean moved = false;
		for (AreaKey area : tracker.killsByArea().keySet())
		{
			int home = totals.homeRegion(area.getName(), area.getRegion());
			if (home != area.getRegion())
			{
				log.debug("Moving {} kills of {} from region {} to {}", tracker.kills(area), area.getName(),
					area.getRegion(), home);
				tracker.rehome(area, home);
				moved = true;
			}
		}
		if (moved)
		{
			saveState();
		}
		refreshPanel();
	}

	/**
	 * Snapshots the kill list on the client thread and hands it to the
	 * panel on the Swing thread, and refreshes the world map drawing.
	 */
	private void refreshPanel()
	{
		PermadeathPanel target = panel;
		if (target == null)
		{
			return;
		}
		List<PermadeathPanel.Row> rows = new ArrayList<>();
		tracker.killsByArea().forEach((area, kills) -> rows.add(new PermadeathPanel.Row(
			area, chunkHeading(area), kills, spawnTotal(area), tracker.hiddenHere(area))));
		SwingUtilities.invokeLater(() -> target.update(rows));
		refreshMap(rows);
	}

	private void refreshMap(List<PermadeathPanel.Row> rows)
	{
		Map<Integer, List<PermadeathPanel.Row>> byRegion = new java.util.TreeMap<>();
		for (PermadeathPanel.Row row : rows)
		{
			byRegion.computeIfAbsent(row.getArea().getRegion(), r -> new ArrayList<>()).add(row);
		}
		List<ChunkMapOverlay.Chunk> chunks = new ArrayList<>();
		worldMapPointManager.removeIf(mapPoints::contains);
		mapPoints.clear();
		byRegion.forEach((region, regionRows) ->
		{
			regionRows.sort((a, b) -> a.getArea().getName().compareToIgnoreCase(b.getArea().getName()));
			List<String> lines = new ArrayList<>();
			boolean complete = true;
			for (PermadeathPanel.Row row : regionRows)
			{
				lines.add(row.getArea().getName() + " " + row.getKills()
					+ (row.getTotal() == null ? "" : "/" + row.getTotal()));
				complete &= row.getTotal() != null && row.getKills() >= row.getTotal();
			}
			String heading = regionRows.get(0).getHeading();
			chunks.add(new ChunkMapOverlay.Chunk(region, heading, lines, complete));
			WorldMapPoint point = WorldMapPoint.builder()
				.worldPoint(chunkCentre(region))
				.image(mapIcon)
				.name("NPC Permadeath")
				.tooltip(heading + "</br>" + String.join("</br>", lines))
				.jumpOnClick(false)
				.build();
			mapPoints.add(point);
			worldMapPointManager.add(point);
		});
		if (mapOverlay != null)
		{
			mapOverlay.setChunks(chunks);
		}
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
	 * Spawns of the NPC in the chunk: the wiki's count or the number of
	 * spawn tiles observed by players, whichever is larger. Null if neither
	 * knows anything.
	 */
	private Integer spawnTotal(AreaKey area)
	{
		Integer wiki = totals.get(area.getName(), area.getRegion());
		int observed = learner.countInRegion(area.getName(), area.getRegion());
		if (wiki == null)
		{
			return observed == 0 ? null : observed;
		}
		return Math.max(wiki, observed);
	}

	// ---- community spawn data ------------------------------------------

	private void fetchCommunity(String name)
	{
		if (!config.shareSpawns())
		{
			return;
		}
		sync.fetch(config.syncUrl(), name, now(), tiles ->
		{
			learner.addCommunity(tiles);
			refreshPanel();
		});
	}

	private void shareSpawns()
	{
		if (!config.shareSpawns() || learner.pendingCount() == 0)
		{
			return;
		}
		sync.upload(config.syncUrl(), learner.takePendingUpload(), learner::uploadFailed);
	}

	private void saveLearner()
	{
		if (!learner.isDirty())
		{
			return;
		}
		saveGlobalList(KEY_LEARNED, learner.serializeLearned());
		saveGlobalList(KEY_PENDING, learner.serializePending());
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
		rehomeBorderKills();
		for (AreaKey area : tracker.killsByArea().keySet())
		{
			totals.ensure(area.getName(), now(), this::rehomeBorderKills);
			fetchCommunity(area.getName());
		}
		refreshPanel();
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

	private static List<String> parseNames(String csv)
	{
		return Text.fromCSV(csv).stream()
			.map(String::trim)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toList());
	}

	private static Set<String> loadBosses()
	{
		Set<String> names = new HashSet<>();
		try (InputStream in = NpcPermadeathPlugin.class.getResourceAsStream("bosses.txt"))
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
