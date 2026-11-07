package com.npcpermadeath;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * The maps in the game's world map list (Gielinor Surface, Taverley
 * Underground, ...) and which world tiles each shows where.
 *
 * <p>A map is put together from pieces of the world, and most underground
 * maps draw pieces away from their real position: on the Waterbirth Dungeon
 * map the Dagannoth Kings' lair sits beside the island's caves although its
 * real tiles are thousands of tiles away. So a world map position is a world
 * tile only on the surface and on maps drawn in place. The table comes from
 * the bundled {@code worldmap_areas.txt}, which {@code tools/worldmap} writes
 * from the game cache.
 */
final class MapAreas
{
	static final String RESOURCE = "worldmap_areas.txt";
	private static final int CHUNK = 64;

	/** Map tiles [x, x + w) x [y, y + h) show the world tiles (dx, dy) away. */
	@Value
	static class Piece
	{
		int x;
		int y;
		int w;
		int h;
		int dx;
		int dy;

		boolean contains(int px, int py)
		{
			return px >= x && px < x + w && py >= y && py < y + h;
		}
	}

	/** Where one chunk appears on a map, in map tiles (y up). */
	static final class Footprint
	{
		/** The rectangles it is drawn as, one per map piece it falls in. */
		final List<Rectangle> parts = new ArrayList<>();
		/** The union of the parts, for drawing one outline. */
		final Area shape = new Area();
		Rectangle bounds;

		private void add(Rectangle r)
		{
			parts.add(r);
			shape.add(new Area(r));
			bounds = bounds == null ? new Rectangle(r) : bounds.union(r);
		}

		/** The part holding the map tile, or null. */
		Rectangle partAt(int x, int y)
		{
			for (Rectangle r : parts)
			{
				if (r.contains(x, y))
				{
					return r;
				}
			}
			return null;
		}

		/** The middle of the largest part, in map tiles. */
		Point centre()
		{
			Rectangle best = parts.get(0);
			for (Rectangle r : parts)
			{
				if ((long) r.width * r.height > (long) best.width * best.height)
				{
					best = r;
				}
			}
			return new Point(best.x + best.width / 2, best.y + best.height / 2);
		}
	}

	/** One map from the world map list. */
	static final class MapArea
	{
		private final int id;
		private final String name;
		private final boolean surface;
		private final List<Piece> pieces = new ArrayList<>();
		/** chunk (region id) -> where it is drawn; built on first use */
		private Map<Integer, Footprint> footprints;

		MapArea(int id, String name, boolean surface)
		{
			this.id = id;
			this.name = name;
			this.surface = surface;
		}

		int getId()
		{
			return id;
		}

		String getName()
		{
			return name;
		}

		/** Whether this is the main map, which draws every tile in place. */
		boolean isSurface()
		{
			return surface;
		}

		List<Piece> getPieces()
		{
			return Collections.unmodifiableList(pieces);
		}

		/** The chunk whose tile is drawn at this map tile, or -1 where the map shows nothing. */
		int regionAt(int x, int y)
		{
			for (Piece p : pieces)
			{
				if (p.contains(x, y))
				{
					return regionOf(x + p.dx, y + p.dy);
				}
			}
			return -1;
		}

		/** Where each chunk the map shows is drawn, in map tiles. */
		Map<Integer, Footprint> footprints()
		{
			if (footprints == null)
			{
				Map<Integer, Footprint> out = new TreeMap<>();
				for (Piece p : pieces)
				{
					int wx0 = p.x + p.dx;
					int wy0 = p.y + p.dy;
					for (int cx = Math.floorDiv(wx0, CHUNK); cx <= Math.floorDiv(wx0 + p.w - 1, CHUNK); cx++)
					{
						for (int cy = Math.floorDiv(wy0, CHUNK); cy <= Math.floorDiv(wy0 + p.h - 1, CHUNK); cy++)
						{
							if (cx < 0 || cy < 0 || cx > 255 || cy > 255)
							{
								continue;
							}
							// the part of the piece inside this chunk, back in map tiles
							int x0 = Math.max(wx0, cx * CHUNK);
							int y0 = Math.max(wy0, cy * CHUNK);
							int x1 = Math.min(wx0 + p.w, (cx + 1) * CHUNK);
							int y1 = Math.min(wy0 + p.h, (cy + 1) * CHUNK);
							out.computeIfAbsent((cx << 8) | cy, r -> new Footprint())
								.add(new Rectangle(x0 - p.dx, y0 - p.dy, x1 - x0, y1 - y0));
						}
					}
				}
				footprints = out;
			}
			return footprints;
		}

		boolean shows(int region)
		{
			return footprints().containsKey(region);
		}
	}

	private final List<MapArea> areas;
	private final Map<String, MapArea> byName = new HashMap<>();

	private MapAreas(List<MapArea> areas)
	{
		this.areas = areas;
		for (MapArea a : areas)
		{
			byName.putIfAbsent(key(a.name), a);
		}
	}

	static MapAreas empty()
	{
		return new MapAreas(Collections.emptyList());
	}

	/** Reads the table; lines it cannot read are skipped. */
	static MapAreas parse(BufferedReader in) throws IOException
	{
		List<MapArea> areas = new ArrayList<>();
		MapArea current = null;
		String line;
		while ((line = in.readLine()) != null)
		{
			line = line.trim();
			if (line.isEmpty() || line.startsWith("#"))
			{
				continue;
			}
			String[] p = line.split("\\s+");
			try
			{
				if (p[0].equals("area") && p.length >= 4)
				{
					String name = line.split("\\s+", 4)[3];
					current = new MapArea(Integer.parseInt(p[1]), name, p[2].equals("1"));
					areas.add(current);
				}
				else if (current != null && p.length == 6)
				{
					int w = Integer.parseInt(p[2]);
					int h = Integer.parseInt(p[3]);
					if (w > 0 && h > 0)
					{
						current.pieces.add(new Piece(Integer.parseInt(p[0]), Integer.parseInt(p[1]), w, h,
							Integer.parseInt(p[4]), Integer.parseInt(p[5])));
					}
				}
			}
			catch (NumberFormatException ignored)
			{
				// a damaged line; the rest of the table still works
			}
		}
		return new MapAreas(areas);
	}

	List<MapArea> getAreas()
	{
		return Collections.unmodifiableList(areas);
	}

	/** The map with this name from the world map list, or null. */
	MapArea named(String name)
	{
		return name == null ? null : byName.get(key(name));
	}

	/**
	 * The map that shows the chunk: the surface when it does, otherwise the
	 * first other map that does, or null when no map in the table shows it.
	 */
	MapArea home(int region)
	{
		MapArea found = null;
		for (MapArea a : areas)
		{
			if (a.shows(region))
			{
				if (a.isSurface())
				{
					return a;
				}
				if (found == null)
				{
					found = a;
				}
			}
		}
		return found;
	}

	/**
	 * The name of the map the world map is showing, as written in its map
	 * list box, or null when the world map is closed or the name cannot be
	 * read. The game writes the name into a text child it creates in that
	 * box.
	 */
	static String openMapName(Client client)
	{
		Widget box = client.getWidget(InterfaceID.Worldmap.MAPLIST_DISPLAY);
		if (box == null || box.isHidden())
		{
			return null;
		}
		String name = firstText(box.getDynamicChildren());
		if (name == null)
		{
			name = firstText(box.getStaticChildren());
		}
		return name;
	}

	private static String firstText(Widget[] widgets)
	{
		if (widgets == null)
		{
			return null;
		}
		for (Widget w : widgets)
		{
			String text = w == null ? null : w.getText();
			if (text != null)
			{
				text = Text.removeTags(text).trim();
				if (!text.isEmpty())
				{
					return text;
				}
			}
		}
		return null;
	}

	private static String key(String name)
	{
		return name.trim().toLowerCase(Locale.ROOT);
	}

	static int regionOf(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}
}
