package com.npcpermadeath;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.List;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Outlines map chunks on the world map: green where nothing has been
 * killed, orange where something has, red where every NPC type is gone.
 * Hovering a chunk shows every NPC type in it with kills and totals in a
 * panel pinned beside the chunk, so it stays clear of the map's own
 * tooltips.
 */
class ChunkMapOverlay extends Overlay
{
	static final int CHUNK_TILES = 64;

	enum State
	{
		NONE(new Color(70, 200, 90, 110), 1f),
		SOME(new Color(255, 160, 40, 230), 2f),
		ALL(new Color(235, 50, 50, 240), 2f);

		final Color color;
		final float stroke;

		State(Color color, float stroke)
		{
			this.color = color;
			this.stroke = stroke;
		}
	}

	/** One NPC type in a chunk. */
	@Value
	static class Entry
	{
		String name;
		int kills;
		/** Known spawns, or null. */
		Integer total;
	}

	/** What the plugin knows about chunks; called on the client thread while rendering. */
	interface ChunkSource
	{
		boolean isKnown(int region);

		State state(int region);

		String heading(int region);

		List<Entry> entries(int region);
	}

	/** Beyond this many chunks in view, only chunks with kills are drawn. */
	private static final int MAX_FULL_GRID = 2500;

	private final Client client;
	private final ChunkSource source;
	/** Chunk under the mouse this frame, or -1; read by {@link ChunkPanelOverlay}. */
	private int hoveredRegion = -1;
	private Rectangle hoveredRect;
	private Rectangle mapBounds;

	ChunkMapOverlay(NpcPermadeathPlugin plugin, Client client, ChunkSource source)
	{
		super(plugin);
		this.client = client;
		this.source = source;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.Worldmap.MAP_CONTAINER >>> 16);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		hoveredRegion = -1;
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null || map.isHidden())
		{
			return null;
		}
		Rectangle bounds = map.getBounds();
		WorldMap worldMap = client.getWorldMap();
		Point centre = worldMap.getWorldMapPosition();
		double pxPerTile = worldMap.getWorldMapZoom();
		if (centre == null || pxPerTile <= 0)
		{
			return null;
		}
		mapBounds = bounds;
		// The map widget's centre shows the world tile the map is positioned on.
		double originX = bounds.getCenterX() - centre.getX() * pxPerTile;
		double originY = bounds.getCenterY() + centre.getY() * pxPerTile;
		// World tile range currently on screen (y grows upward on the map).
		int minTileX = (int) Math.floor((bounds.x - originX) / pxPerTile);
		int maxTileX = (int) Math.ceil((bounds.x + bounds.width - originX) / pxPerTile);
		int minTileY = (int) Math.floor((originY - (bounds.y + bounds.height)) / pxPerTile);
		int maxTileY = (int) Math.ceil((originY - bounds.y) / pxPerTile);
		int rx0 = Math.max(0, minTileX >> 6);
		int rx1 = Math.min(255, maxTileX >> 6);
		int ry0 = Math.max(0, minTileY >> 6);
		int ry1 = Math.min(255, maxTileY >> 6);
		boolean fullGrid = (long) (rx1 - rx0 + 1) * (ry1 - ry0 + 1) <= MAX_FULL_GRID;

		Shape oldClip = g.getClip();
		g.setClip(bounds);
		Point mouse = client.getMouseCanvasPosition();
		int hovered = -1;
		Rectangle hoveredRect = null;
		for (int rx = rx0; rx <= rx1; rx++)
		{
			for (int ry = ry0; ry <= ry1; ry++)
			{
				int region = (rx << 8) | ry;
				if (!source.isKnown(region))
				{
					continue;
				}
				State state = source.state(region);
				if (!fullGrid && state == State.NONE)
				{
					continue;
				}
				int px = (int) Math.round(originX + (rx << 6) * pxPerTile);
				int py = (int) Math.round(originY - ((ry << 6) + CHUNK_TILES) * pxPerTile);
				int size = (int) Math.round(CHUNK_TILES * pxPerTile);
				Rectangle rect = new Rectangle(px, py, size, size);
				if (!rect.intersects(bounds))
				{
					continue;
				}
				g.setColor(state.color);
				g.setStroke(new BasicStroke(state.stroke));
				g.draw(rect);
				if (mouse != null && bounds.contains(mouse.getX(), mouse.getY()) && rect.contains(mouse.getX(), mouse.getY()))
				{
					hovered = region;
					hoveredRect = rect;
				}
			}
		}
		if (hovered >= 0)
		{
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(2f));
			g.draw(hoveredRect);
			this.hoveredRegion = hovered;
			this.hoveredRect = hoveredRect;
		}
		g.setClip(oldClip);
		return null;
	}

	int getHoveredRegion()
	{
		return hoveredRegion;
	}

	Rectangle getHoveredRect()
	{
		return hoveredRect;
	}

	Rectangle getMapBounds()
	{
		return mapBounds;
	}

	ChunkSource getSource()
	{
		return source;
	}
}
