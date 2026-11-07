package com.npcpermadeath;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.Map;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <p>On the surface map a map tile is the world tile, so chunks form a
 * plain grid. Most underground maps draw pieces of the world away from their
 * real place, so there each chunk is outlined where {@link MapAreas} says its
 * pieces are drawn.
 */
@Slf4j
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
	private final MapAreas areas;
	/** Chunk under the mouse this frame, or -1; read by {@link ChunkPanelOverlay}. */
	private int hoveredRegion = -1;
	private Rectangle hoveredRect;
	private Rectangle mapBounds;
	/** The open map's name as last seen, for the debug log. */
	private String lastMapName;

	ChunkMapOverlay(NpcPermadeathPlugin plugin, Client client, ChunkSource source, MapAreas areas)
	{
		super(plugin);
		this.client = client;
		this.source = source;
		this.areas = areas;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.Worldmap.MAP_CONTAINER >>> 16);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		hoveredRegion = -1;
		hoveredRect = null;
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
		// The map widget's centre shows the map tile the map is positioned on.
		double originX = bounds.getCenterX() - centre.getX() * pxPerTile;
		double originY = bounds.getCenterY() + centre.getY() * pxPerTile;
		// Map tile range currently on screen (y grows upward on the map).
		int minTileX = (int) Math.floor((bounds.x - originX) / pxPerTile);
		int maxTileX = (int) Math.ceil((bounds.x + bounds.width - originX) / pxPerTile);
		int minTileY = (int) Math.floor((originY - (bounds.y + bounds.height)) / pxPerTile);
		int maxTileY = (int) Math.ceil((originY - bounds.y) / pxPerTile);

		Shape oldClip = g.getClip();
		g.setClip(bounds);
		Point mouse = client.getMouseCanvasPosition();
		if (mouse != null && !bounds.contains(mouse.getX(), mouse.getY()))
		{
			mouse = null;
		}
		String mapName = MapAreas.openMapName(client);
		MapAreas.MapArea area = areas.named(mapName);
		if (!java.util.Objects.equals(mapName, lastMapName))
		{
			lastMapName = mapName;
			log.debug("World map shows \"{}\": {}", mapName,
				area == null ? "not in the table, drawn as a plain grid" : area.getPieces().size() + " pieces");
		}
		if (area == null || area.isSurface())
		{
			drawGrid(g, bounds, originX, originY, pxPerTile, minTileX, maxTileX, minTileY, maxTileY, mouse);
		}
		else
		{
			Rectangle view = new Rectangle(minTileX, minTileY, maxTileX - minTileX + 1, maxTileY - minTileY + 1);
			drawPieces(g, area, view, new AffineTransform(pxPerTile, 0, 0, -pxPerTile, originX, originY), mouse);
		}
		g.setClip(oldClip);
		return null;
	}

	/**
	 * The surface: every map tile is the world tile, so chunks are a plain
	 * 64x64 grid. Also used for a map the table does not know.
	 */
	private void drawGrid(Graphics2D g, Rectangle bounds, double originX, double originY, double pxPerTile,
		int minTileX, int maxTileX, int minTileY, int maxTileY, Point mouse)
	{
		int rx0 = Math.max(0, minTileX >> 6);
		int rx1 = Math.min(255, maxTileX >> 6);
		int ry0 = Math.max(0, minTileY >> 6);
		int ry1 = Math.min(255, maxTileY >> 6);
		boolean fullGrid = (long) (rx1 - rx0 + 1) * (ry1 - ry0 + 1) <= MAX_FULL_GRID;

		int hovered = -1;
		Rectangle hovRect = null;
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
				if (mouse != null && rect.contains(mouse.getX(), mouse.getY()))
				{
					hovered = region;
					hovRect = rect;
				}
			}
		}
		if (hovered >= 0)
		{
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(2f));
			g.draw(hovRect);
			hoveredRegion = hovered;
			hoveredRect = hovRect;
		}
	}

	/**
	 * A map drawn from moved pieces: each chunk is outlined where its pieces
	 * appear, which can be several places or only part of a square.
	 */
	private void drawPieces(Graphics2D g, MapAreas.MapArea area, Rectangle view, AffineTransform toScreen, Point mouse)
	{
		Map<Integer, MapAreas.Footprint> footprints = area.footprints();
		int hovered = -1;
		Rectangle hoveredPart = null;
		if (mouse != null)
		{
			int tx = (int) Math.floor((mouse.getX() - toScreen.getTranslateX()) / toScreen.getScaleX());
			int ty = (int) Math.floor((mouse.getY() - toScreen.getTranslateY()) / toScreen.getScaleY());
			int region = area.regionAt(tx, ty);
			MapAreas.Footprint fp = region < 0 ? null : footprints.get(region);
			if (fp != null && source.isKnown(region))
			{
				hovered = region;
				hoveredPart = fp.partAt(tx, ty);
				if (hoveredPart == null)
				{
					hoveredPart = fp.bounds;
				}
			}
		}
		for (Map.Entry<Integer, MapAreas.Footprint> e : footprints.entrySet())
		{
			int region = e.getKey();
			if (!e.getValue().bounds.intersects(view) || !source.isKnown(region))
			{
				continue;
			}
			State state = source.state(region);
			g.setColor(state.color);
			g.setStroke(new BasicStroke(state.stroke));
			g.draw(toScreen.createTransformedShape(e.getValue().shape));
		}
		if (hovered >= 0)
		{
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(2f));
			g.draw(toScreen.createTransformedShape(footprints.get(hovered).shape));
			hoveredRegion = hovered;
			hoveredRect = toScreen.createTransformedShape(hoveredPart).getBounds();
		}
	}

	/** Whether the world map is open now, so the hover from its last frame still applies. */
	boolean isMapOpen()
	{
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		return map != null && !map.isHidden();
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
