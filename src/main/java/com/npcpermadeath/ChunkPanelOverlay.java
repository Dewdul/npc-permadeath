package com.npcpermadeath;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.List;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The list for the chunk the mouse is over on the world map, pinned beside
 * the chunk. Drawn above every widget so map markers cannot cover it.
 */
class ChunkPanelOverlay extends Overlay
{
	private static final int MAX_PANEL_LINES = 30;
	private static final int PANEL_GAP = 8;
	private static final Color UNTOUCHED = new Color(170, 170, 170);

	private final ChunkMapOverlay mapOverlay;
	private final PanelComponent panel = new PanelComponent();

	ChunkPanelOverlay(NpcPermadeathPlugin plugin, ChunkMapOverlay mapOverlay)
	{
		super(plugin);
		this.mapOverlay = mapOverlay;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		panel.setBackgroundColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		int region = mapOverlay.getHoveredRegion();
		Rectangle chunk = mapOverlay.getHoveredRect();
		Rectangle bounds = mapOverlay.getMapBounds();
		if (region < 0 || chunk == null || bounds == null)
		{
			return null;
		}
		ChunkMapOverlay.ChunkSource source = mapOverlay.getSource();
		panel.getChildren().clear();
		panel.getChildren().add(TitleComponent.builder().text(source.heading(region)).color(Color.WHITE).build());
		List<ChunkMapOverlay.Entry> entries = source.entries(region);
		int shown = Math.min(entries.size(), MAX_PANEL_LINES);
		for (int i = 0; i < shown; i++)
		{
			ChunkMapOverlay.Entry e = entries.get(i);
			String count = e.getTotal() == null ? Integer.toString(e.getKills()) : e.getKills() + " / " + e.getTotal();
			Color color = e.getKills() == 0 ? UNTOUCHED
				: e.getTotal() != null && e.getKills() >= e.getTotal()
					? ChunkMapOverlay.State.ALL.color
					: ChunkMapOverlay.State.SOME.color;
			panel.getChildren().add(LineComponent.builder()
				.left(e.getName()).leftColor(e.getKills() == 0 ? UNTOUCHED : Color.WHITE)
				.right(count).rightColor(color)
				.build());
		}
		if (entries.size() > shown)
		{
			panel.getChildren().add(LineComponent.builder().left("+" + (entries.size() - shown) + " more").build());
		}

		// Measure first with an empty clip, then place beside the chunk and draw for real.
		Shape clip = g.getClip();
		g.setClip(new Rectangle(0, 0, 0, 0));
		panel.setPreferredLocation(new java.awt.Point(0, 0));
		Dimension size = panel.render(g);
		g.setClip(clip);
		if (size == null)
		{
			return null;
		}
		int x = chunk.x + chunk.width + PANEL_GAP;
		if (x + size.width > bounds.x + bounds.width)
		{
			x = chunk.x - PANEL_GAP - size.width;
		}
		int y = Math.max(bounds.y, Math.min(chunk.y, bounds.y + bounds.height - size.height));
		panel.setPreferredLocation(new java.awt.Point(x, y));
		panel.render(g);
		return null;
	}
}
