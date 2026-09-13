package com.npcpermadeath;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.worldmap.WorldMapOverlay;

/**
 * Paints every chunk with kills onto the world map: a tinted square with the
 * place name and one line per NPC ("Goblin 47/53"). Text appears once the
 * map is zoomed in enough for it to fit.
 */
class ChunkMapOverlay extends Overlay
{
	static final int CHUNK_TILES = 64;
	private static final Color FILL = new Color(255, 120, 40, 45);
	private static final Color FILL_DONE = new Color(200, 40, 40, 60);
	private static final Color BORDER = new Color(255, 150, 60, 200);
	private static final Color BORDER_DONE = new Color(230, 60, 60, 220);
	private static final int MIN_TEXT_WIDTH = 70;

	/** One chunk's summary, prepared by the plugin. */
	@Value
	static class Chunk
	{
		int region;
		String place;
		/** e.g. "Goblin 47/53" */
		List<String> lines;
		/** True when every NPC listed has hit its total. */
		boolean complete;
	}

	private final Client client;
	private final WorldMapOverlay worldMapOverlay;
	private volatile List<Chunk> chunks = Collections.emptyList();

	ChunkMapOverlay(NpcPermadeathPlugin plugin, Client client, WorldMapOverlay worldMapOverlay)
	{
		super(plugin);
		this.client = client;
		this.worldMapOverlay = worldMapOverlay;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.Worldmap.MAP_CONTAINER >>> 16);
	}

	void setChunks(List<Chunk> chunks)
	{
		this.chunks = new ArrayList<>(chunks);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		if (map == null || map.isHidden() || chunks.isEmpty())
		{
			return null;
		}
		Rectangle bounds = map.getBounds();
		Shape oldClip = g.getClip();
		g.setClip(bounds);
		g.setFont(FontManager.getRunescapeSmallFont());
		FontMetrics fm = g.getFontMetrics();
		for (Chunk chunk : chunks)
		{
			int x0 = (chunk.getRegion() >> 8) << 6;
			int y0 = (chunk.getRegion() & 0xff) << 6;
			Point sw = worldMapOverlay.mapWorldPointToGraphicsPoint(new WorldPoint(x0, y0, 0));
			Point ne = worldMapOverlay.mapWorldPointToGraphicsPoint(new WorldPoint(x0 + CHUNK_TILES, y0 + CHUNK_TILES, 0));
			if (sw == null || ne == null)
			{
				continue;
			}
			Rectangle rect = new Rectangle(sw.getX(), ne.getY(), ne.getX() - sw.getX(), sw.getY() - ne.getY());
			if (!rect.intersects(bounds))
			{
				continue;
			}
			g.setColor(chunk.isComplete() ? FILL_DONE : FILL);
			g.fill(rect);
			g.setColor(chunk.isComplete() ? BORDER_DONE : BORDER);
			g.setStroke(new BasicStroke(1));
			g.draw(rect);
			if (rect.width >= MIN_TEXT_WIDTH)
			{
				drawText(g, fm, rect, chunk);
			}
		}
		g.setClip(oldClip);
		return null;
	}

	private static void drawText(Graphics2D g, FontMetrics fm, Rectangle rect, Chunk chunk)
	{
		int x = rect.x + 3;
		int y = rect.y + fm.getAscent() + 2;
		int lineHeight = fm.getHeight();
		List<String> lines = new ArrayList<>();
		lines.add(chunk.getPlace());
		lines.addAll(chunk.getLines());
		for (String line : lines)
		{
			if (y > rect.y + rect.height - 2)
			{
				break;
			}
			g.setColor(Color.BLACK);
			g.drawString(line, x + 1, y + 1);
			g.setColor(Color.WHITE);
			g.drawString(line, x, y);
			y += lineHeight;
		}
	}
}
