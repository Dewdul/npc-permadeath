package com.npcpermadeath;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Outlines map chunks on the world map: green where nothing has been
 * killed, orange where something has, red where every NPC type is gone.
 * Hovering a chunk shows every NPC type in it with kills and totals.
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

	/** What the plugin knows about chunks; called on the client thread while rendering. */
	interface ChunkSource
	{
		boolean isKnown(int region);

		State state(int region);

		String heading(int region);

		List<String> lines(int region);
	}

	/** Beyond this many chunks in view, only chunks with kills are drawn. */
	private static final int MAX_FULL_GRID = 2500;
	private static final int MAX_TOOLTIP_LINES = 30;

	private final Client client;
	private final TooltipManager tooltipManager;
	private final ChunkSource source;

	ChunkMapOverlay(NpcPermadeathPlugin plugin, Client client, TooltipManager tooltipManager, ChunkSource source)
	{
		super(plugin);
		this.client = client;
		this.tooltipManager = tooltipManager;
		this.source = source;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.Worldmap.MAP_CONTAINER >>> 16);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
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
			tooltipManager.add(new Tooltip(tooltipFor(hovered)));
		}
		g.setClip(oldClip);
		return null;
	}

	private String tooltipFor(int region)
	{
		StringBuilder sb = new StringBuilder(source.heading(region));
		List<String> lines = source.lines(region);
		int shown = Math.min(lines.size(), MAX_TOOLTIP_LINES);
		for (int i = 0; i < shown; i++)
		{
			sb.append("</br>").append(lines.get(i));
		}
		if (lines.size() > shown)
		{
			sb.append("</br>+").append(lines.size() - shown).append(" more");
		}
		return sb.toString();
	}
}
