package com.npcpermadeath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * Draws translucent ghost copies of hidden NPCs. A ghost is a client-side
 * scene object that follows its NPC, so it has no menu entries and can be
 * neither clicked, attacked nor looted. The real NPC stays hidden.
 *
 * <p>Everything here touches the client and must run on the client thread.
 * Ghosts are created and removed when an NPC's hidden state changes; the only
 * per-frame work is moving the ghosts that already exist.
 */
@Slf4j
class GhostManager
{
	private static final int MAX_CACHED_MODELS = 64;
	private static final int MAX_CACHED_ANIMATIONS = 128;
	/** Model scale value that means "unscaled". */
	private static final int NORMAL_SCALE = 128;
	/** Pale spectral blue-white, packed as the game's 16 bit HSL. */
	private static final short TINT_COLOUR = JagexColor.packHSL(40, 2, 112);

	/** What makes one ghost model different from another. */
	@Value
	static class ModelKey
	{
		int compositionId;
		int alpha;
		boolean tint;
	}

	private final Client client;
	private final NpcPermadeathConfig config;
	private final Map<NPC, Ghost> ghosts = new IdentityHashMap<>();
	private final Map<ModelKey, Model> models = lruMap(MAX_CACHED_MODELS);
	private final Map<Integer, Animation> animations = lruMap(MAX_CACHED_ANIMATIONS);

	GhostManager(Client client, NpcPermadeathConfig config)
	{
		this.client = client;
		this.config = config;
	}

	/**
	 * Brings one NPC's ghost in line with whether it is hidden: creates it,
	 * rebuilds it if the NPC changed form, or removes it.
	 */
	void sync(NPC npc, boolean hidden)
	{
		if (!hidden || !config.ghosts())
		{
			remove(npc);
			return;
		}
		NPCComposition composition = compositionOf(npc);
		if (composition == null)
		{
			remove(npc);
			return;
		}
		Ghost ghost = ghosts.get(npc);
		if (ghost != null && ghost.compositionId == composition.getId())
		{
			return;
		}
		Model model = modelFor(composition);
		if (model == null)
		{
			remove(npc);
			return;
		}
		if (ghost == null)
		{
			ghost = new Ghost(client, npc);
			ghosts.put(npc, ghost);
		}
		ghost.setBase(model, composition.getId());
		ghost.follow();
		ghost.mirrorAnimation(this::animation);
	}

	void remove(NPC npc)
	{
		Ghost ghost = ghosts.remove(npc);
		if (ghost != null)
		{
			ghost.unregister();
		}
	}

	/** Re-checks every NPC in the scene and every existing ghost against the hidden test. */
	void resync(Iterable<? extends NPC> scene, Predicate<NPC> isHidden)
	{
		if (!config.ghosts())
		{
			clear();
			return;
		}
		for (NPC npc : new ArrayList<>(ghosts.keySet()))
		{
			if (!isHidden.test(npc))
			{
				remove(npc);
			}
		}
		for (NPC npc : scene)
		{
			sync(npc, isHidden.test(npc));
		}
	}

	/** Throws away every ghost and cached model and builds them again, for example after a setting changed. */
	void rebuildAll(Iterable<? extends NPC> scene, Predicate<NPC> isHidden)
	{
		ArrayList<NPC> known = new ArrayList<>(ghosts.keySet());
		clear();
		if (!config.ghosts())
		{
			return;
		}
		// Ghosts of NPCs outside the top level scene (boats) are not in the scan, so rebuild them too.
		for (NPC npc : known)
		{
			sync(npc, isHidden.test(npc));
		}
		for (NPC npc : scene)
		{
			sync(npc, isHidden.test(npc));
		}
	}

	/** Runs every client tick: moves each ghost to where its NPC is and copies its animation. */
	void update()
	{
		if (ghosts.isEmpty() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Iterator<Ghost> it = ghosts.values().iterator();
		while (it.hasNext())
		{
			Ghost ghost = it.next();
			if (!ghost.npcStillInScene())
			{
				ghost.unregister();
				it.remove();
				continue;
			}
			ghost.follow();
			ghost.mirrorAnimation(this::animation);
		}
	}

	/** Removes every ghost and forgets the cached models. */
	void clear()
	{
		for (Ghost ghost : ghosts.values())
		{
			ghost.unregister();
		}
		ghosts.clear();
		models.clear();
		animations.clear();
	}

	private static NPCComposition compositionOf(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		return composition != null ? composition : npc.getComposition();
	}

	private Animation animation(int id)
	{
		Animation cached = animations.get(id);
		if (cached == null)
		{
			cached = client.loadAnimation(id);
			if (cached != null)
			{
				animations.put(id, cached);
			}
		}
		return cached;
	}

	private Model modelFor(NPCComposition composition)
	{
		ModelKey key = new ModelKey(composition.getId(), opacityToAlpha(config.ghostOpacity()), config.ghostTint());
		Model model = models.get(key);
		if (model == null)
		{
			model = build(composition, key);
			if (model != null)
			{
				models.put(key, model);
			}
		}
		return model;
	}

	/** Builds the translucent model for an NPC type the same way the game builds the NPC itself. */
	private Model build(NPCComposition composition, ModelKey key)
	{
		int[] ids = composition.getModels();
		if (ids == null || ids.length == 0)
		{
			return null;
		}
		ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				log.debug("No model {} for NPC {}", ids[i], composition.getId());
				return null;
			}
		}
		ModelData data = parts.length == 1 ? parts[0] : client.mergeModels(parts);
		if (data == null)
		{
			return null;
		}

		short[] from = composition.getColorToReplace();
		short[] to = composition.getColorToReplaceWith();
		if (from != null && to != null)
		{
			for (int i = 0; i < Math.min(from.length, to.length); i++)
			{
				data.recolor(from[i], to[i]);
			}
		}

		int width = composition.getWidthScale();
		int height = composition.getHeightScale();
		if (width != NORMAL_SCALE || height != NORMAL_SCALE)
		{
			data.cloneVertices().scale(width, height, width);
		}

		// Passing true makes the call create the array when the model has none.
		data.cloneTransparencies(true);
		byte[] faces = data.getFaceTransparencies();
		if (faces != null)
		{
			for (int i = 0; i < faces.length; i++)
			{
				faces[i] = combineAlpha(faces[i], key.getAlpha());
			}
		}

		if (key.isTint())
		{
			data.cloneColors();
			short[] colours = data.getFaceColors();
			if (colours != null)
			{
				Arrays.fill(colours, TINT_COLOUR);
			}
		}
		return data.light();
	}

	/**
	 * Face transparency as the game stores it: 0 is opaque and 255 is fully
	 * transparent (the GPU plugin draws the face with alpha 255 minus this).
	 * The ghost's setting never makes a face more solid than it already was,
	 * so invisible helper faces some models carry stay invisible.
	 */
	static byte opacityToAlpha(int opacityPercent)
	{
		int percent = Math.max(0, Math.min(100, opacityPercent));
		return (byte) Math.round((100 - percent) * 255 / 100f);
	}

	/** The more transparent of a face's own transparency and the ghost's, as an unsigned byte. */
	static byte combineAlpha(byte existing, int ghostAlpha)
	{
		return (byte) Math.max(existing & 0xff, ghostAlpha & 0xff);
	}

	/** A map that drops its least recently used entry once it holds more than max. */
	static <K, V> Map<K, V> lruMap(int max)
	{
		return new LinkedHashMap<K, V>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest)
			{
				return size() > max;
			}
		};
	}

	/** One ghost: a scene object that draws the cached model, posed like the NPC it follows. */
	private static final class Ghost extends RuneLiteObjectController
	{
		private final Client client;
		private final NPC npc;
		private Model base;
		private int compositionId = -1;
		private boolean registered;
		private AnimationController pose;
		private AnimationController action;
		private int poseId = -1;
		private int actionId = -1;
		private int lastActionFrame;

		Ghost(Client client, NPC npc)
		{
			this.client = client;
			this.npc = npc;
		}

		void setBase(Model model, int compositionId)
		{
			this.base = model;
			this.compositionId = compositionId;
		}

		@Override
		public Model getModel()
		{
			if (action != null && action.getAnimation() != null)
			{
				return action.animate(base, pose != null && pose.getAnimation() != null ? pose : null);
			}
			if (pose != null && pose.getAnimation() != null)
			{
				return pose.animate(base);
			}
			return base;
		}

		/** The client calls this as time passes, so animations run without any work from us. */
		@Override
		public void tick(int cycles)
		{
			if (pose != null)
			{
				pose.tick(cycles);
			}
			if (action != null)
			{
				action.tick(cycles);
			}
		}

		boolean npcStillInScene()
		{
			WorldView view = npc.getWorldView();
			return view != null && view.npcs().byIndex(npc.getIndex()) == npc;
		}

		/** Copies the NPC's position, plane and facing, and makes sure the object is registered. */
		void follow()
		{
			LocalPoint point = npc.getLocalLocation();
			WorldView view = npc.getWorldView();
			if (point == null || view == null)
			{
				return;
			}
			int plane = view.getPlane();
			// An object must be re-registered when it moves between world views.
			if (registered && point.getWorldView() != getWorldView())
			{
				unregister();
			}
			setLocation(point, plane);
			setZ(Perspective.getTileHeight(client, point, plane));
			setOrientation(npc.getCurrentOrientation());
			if (!registered)
			{
				client.registerRuneLiteObject(this);
				registered = true;
			}
		}

		void unregister()
		{
			if (registered)
			{
				client.removeRuneLiteObject(this);
				registered = false;
			}
		}

		/** Follows the NPC's idle, walk and action animations, restarting the action when the NPC does. */
		void mirrorAnimation(IntFunction<Animation> loader)
		{
			int newPose = npc.getPoseAnimation();
			if (newPose != poseId)
			{
				poseId = newPose;
				pose = controller(newPose, loader);
			}
			int newAction = npc.getAnimation();
			int frame = npc.getAnimationFrame();
			if (newAction != actionId)
			{
				actionId = newAction;
				action = controller(newAction, loader);
			}
			else if (action != null && frame < lastActionFrame)
			{
				action.reset();
			}
			lastActionFrame = frame;
		}

		private AnimationController controller(int id, IntFunction<Animation> loader)
		{
			if (id < 0)
			{
				return null;
			}
			Animation animation = loader.apply(id);
			return animation == null ? null : new AnimationController(client, animation);
		}
	}
}
