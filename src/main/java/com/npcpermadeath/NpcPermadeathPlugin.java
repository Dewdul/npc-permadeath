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
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.coords.WorldPoint;
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
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
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

	@Inject
	private ClientToolbar clientToolbar;

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
	private SpawnTotals totals;
	private PermadeathPanel panel;
	private NavigationButton navButton;

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
		panel = new PermadeathPanel(area -> clientThread.invoke(() -> forgetArea(area)));
		navButton = NavigationButton.builder()
			.tooltip("NPC Permadeath")
			.icon(ImageUtil.loadImageResource(getClass(), "panel_icon.png"))
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
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
		navButton = null;
		panel = null;
		saveIfDirty();
		tracker.clearAll();
		tracker.markSaved();
		loot.clear();
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
		if (name == null || location == null || !isEligible(npc, name))
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
				boolean totalKnown = totals.get(area.getName(), area.getRegion()) != null;
				announce(area);
				// If the total was missing, say the line again once the lookup fills it in.
				totals.ensure(area.getName(), now(), () ->
				{
					refreshPanel();
					if (!totalKnown && totals.get(area.getName(), area.getRegion()) != null)
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
		considerNpc(event.getNpc());
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
				loot.clear();
				pendingDecisions.clear();
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
		if (kills.isEmpty())
		{
			message("NPC Permadeath: nothing slain in this area yet. " + tracker.totalKills()
				+ " kill(s) remembered overall. ::permadeath reset forgets them all.");
			return;
		}
		message("NPC Permadeath, " + placeName(new AreaKey("", region)) + ":");
		kills.forEach((name, count) ->
		{
			AreaKey area = new AreaKey(name, region);
			message(name + ": " + count + totalSuffix(area) + " slain, " + tracker.hiddenHere(area) + " hidden here");
		});
	}

	private String placeName(AreaKey area)
	{
		String label = totals.label(area.getName(), area.getRegion());
		return label != null ? label : "Region " + area.getRegion();
	}

	private void forgetArea(AreaKey area)
	{
		tracker.forgetArea(area);
		saveState();
		refreshPanel();
		message("NPC Permadeath: " + area.getName() + " in " + placeName(area) + " forgotten.");
	}

	/** Snapshots the kill list on the client thread and hands it to the panel on the Swing thread. */
	private void refreshPanel()
	{
		PermadeathPanel target = panel;
		if (target == null)
		{
			return;
		}
		List<PermadeathPanel.Row> rows = new ArrayList<>();
		tracker.killsByArea().forEach((area, kills) -> rows.add(new PermadeathPanel.Row(
			area, placeName(area), kills, totals.get(area.getName(), area.getRegion()), tracker.hiddenHere(area))));
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
		Integer total = totals.get(area.getName(), area.getRegion());
		return total == null ? "" : " of " + total;
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
