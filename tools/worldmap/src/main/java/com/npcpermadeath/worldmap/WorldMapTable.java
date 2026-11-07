package com.npcpermadeath.worldmap;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.cache.IndexType;
import net.runelite.cache.WorldMapManager;
import net.runelite.cache.definitions.MapSquareDefinition;
import net.runelite.cache.definitions.WorldMapCompositeDefinition;
import net.runelite.cache.definitions.WorldMapDefinition;
import net.runelite.cache.definitions.ZoneDefinition;
import net.runelite.cache.definitions.loaders.WorldMapCompositeLoader;
import net.runelite.cache.definitions.loaders.WorldMapLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.FSFile;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Position;

/**
 * Writes the plugin's world map area table: for every map in the game's world
 * map list, which world tiles it shows where, and where on the surface each
 * underground map is entered.
 *
 * <p>Each map is put together from 64x64 map squares and 8x8 zones, and each
 * piece is drawn at a display position that can differ from its real
 * (source) position. The surface draws everything in place; most underground
 * maps move pieces next to each other. The table records every 8x8 zone of
 * every map with its offset, merged into rectangles of equal offset.
 *
 * <p>Entrances come from the world map's intermap links, the icons that switch
 * between the surface and an underground map. A map without a link of its own
 * to the surface is entered through the map it links to (Dorgesh-Kaan through
 * the Lumbridge caves), up to {@link #MAX_HOPS} maps away.
 *
 * <p>Usage: {@code WorldMapTable <cache dir> <output file> [source note]}
 */
public final class WorldMapTable
{
	private static final int ZONE = 8;
	private static final int MAX_HOPS = 3;

	/** One map from the world map list. */
	private static final class MapArea
	{
		final int id;
		final String name;
		final boolean surface;
		/** {x, y, w, h, dx, dy}: map tiles and the offset to their world tiles. */
		final List<int[]> rects;
		/** {underground x, y, surface x, y}, world tiles. */
		final Set<List<Integer>> exits = new LinkedHashSet<>();

		MapArea(int id, String name, boolean surface, List<int[]> rects)
		{
			this.id = id;
			this.name = name;
			this.surface = surface;
			this.rects = rects;
		}

		/** Whether the map shows this world tile. */
		boolean shows(int x, int y)
		{
			for (int[] r : rects)
			{
				if (x >= r[0] + r[4] && x < r[0] + r[4] + r[2] && y >= r[1] + r[5] && y < r[1] + r[5] + r[3])
				{
					return true;
				}
			}
			return false;
		}
	}

	private WorldMapTable()
	{
	}

	public static void main(String[] args) throws IOException
	{
		if (args.length < 2)
		{
			System.err.println("usage: WorldMapTable <cache dir> <output file> [source note]");
			System.exit(2);
		}
		String source = args.length > 2 ? args[2] : "an OSRS disk cache";
		try (Store store = new Store(new File(args[0])))
		{
			store.load();
			Index index = store.getIndex(IndexType.WORLDMAP);
			Map<Integer, WorldMapDefinition> details = new HashMap<>();
			for (FSFile f : files(store, archive(index, "details", 0)))
			{
				details.put(f.getFileId(), new WorldMapLoader().load(f.getContents(), f.getFileId()));
			}
			WorldMapCompositeLoader loader = new WorldMapCompositeLoader().configureForRevision(index.getRevision());
			Map<Integer, WorldMapCompositeDefinition> composites = new TreeMap<>();
			for (FSFile f : files(store, archive(index, "compositemap", 1)))
			{
				composites.put(f.getFileId(), loader.load(f.getContents()));
			}

			List<MapArea> areas = new ArrayList<>();
			for (Map.Entry<Integer, WorldMapCompositeDefinition> e : composites.entrySet())
			{
				WorldMapDefinition def = details.get(e.getKey());
				if (def == null || def.getName() == null)
				{
					continue;
				}
				List<int[]> rects = rectangles(def.getName(), e.getValue());
				if (!rects.isEmpty())
				{
					areas.add(new MapArea(e.getKey(), def.getName().trim(), def.isSurface(), rects));
				}
			}

			WorldMapManager links = new WorldMapManager(store);
			links.load();
			addExits(areas, links.getIntermapLinks());

			File out = new File(args[1]);
			int pieces = 0;
			int exits = 0;
			try (PrintWriter w = new PrintWriter(out, StandardCharsets.UTF_8.name()))
			{
				w.print("# World map areas for NPC Permadeath, written by tools/worldmap from " + source + ".\n");
				w.print("# Do not edit by hand. \"area <id> <surface 0|1> <name>\" starts a map from the game's\n");
				w.print("# world map list. Each line after it is a piece of that map, in game tiles:\n");
				w.print("# x y width height dx dy. Map position (x, y) shows world tile (x + dx, y + dy).\n");
				w.print("# \"exit x y sx sy\": world tile (x, y) of this map is entered from surface tile (sx, sy).\n");
				for (MapArea a : areas)
				{
					w.print("area " + a.id + " " + (a.surface ? 1 : 0) + " " + a.name + "\n");
					for (int[] r : a.rects)
					{
						w.print(r[0] + " " + r[1] + " " + r[2] + " " + r[3] + " " + r[4] + " " + r[5] + "\n");
					}
					for (List<Integer> x : a.exits)
					{
						w.print("exit " + x.get(0) + " " + x.get(1) + " " + x.get(2) + " " + x.get(3) + "\n");
					}
					pieces += a.rects.size();
					exits += a.exits.size();
				}
			}
			System.out.println("Wrote " + areas.size() + " map areas, " + pieces + " pieces and " + exits
				+ " surface entrances to " + out);
			for (MapArea a : areas)
			{
				if (!a.surface && a.exits.isEmpty())
				{
					System.out.println("  no surface entrance known: " + a.name);
				}
			}
		}
	}

	/**
	 * Gives each underground map its surface entrances: its own links to the
	 * surface (either direction), else, through a link to another underground
	 * map, that map's entrance nearest the link.
	 */
	static void addExits(List<MapArea> areas, Map<Position, Position> links)
	{
		MapArea surface = areas.stream().filter(a -> a.surface).findFirst().orElse(null);
		if (surface == null)
		{
			return;
		}
		// {from x, y, to x, y, to area index} between two underground maps, both directions
		Map<MapArea, List<int[]>> between = new HashMap<>();
		for (Map.Entry<Position, Position> link : links.entrySet())
		{
			Position a = link.getKey();
			Position b = link.getValue();
			boolean aUp = surface.shows(a.getX(), a.getY());
			boolean bUp = surface.shows(b.getX(), b.getY());
			for (MapArea m : areas)
			{
				if (m.surface)
				{
					continue;
				}
				boolean hasA = m.shows(a.getX(), a.getY());
				boolean hasB = m.shows(b.getX(), b.getY());
				if (hasA && !aUp && bUp)
				{
					m.exits.add(List.of(a.getX(), a.getY(), b.getX(), b.getY()));
				}
				if (hasB && !bUp && aUp)
				{
					m.exits.add(List.of(b.getX(), b.getY(), a.getX(), a.getY()));
				}
				for (MapArea other : areas)
				{
					if (other == m || other.surface)
					{
						continue;
					}
					if (hasA && other.shows(b.getX(), b.getY()) && !aUp && !bUp)
					{
						between.computeIfAbsent(m, k -> new ArrayList<>())
							.add(new int[]{a.getX(), a.getY(), b.getX(), b.getY(), areas.indexOf(other)});
					}
					if (hasB && other.shows(a.getX(), a.getY()) && !aUp && !bUp)
					{
						between.computeIfAbsent(m, k -> new ArrayList<>())
							.add(new int[]{b.getX(), b.getY(), a.getX(), a.getY(), areas.indexOf(other)});
					}
				}
			}
		}
		for (int hop = 0; hop < MAX_HOPS; hop++)
		{
			Map<MapArea, List<List<Integer>>> found = new HashMap<>();
			for (MapArea m : areas)
			{
				if (m.surface || !m.exits.isEmpty())
				{
					continue;
				}
				for (int[] l : between.getOrDefault(m, List.of()))
				{
					MapArea next = areas.get(l[4]);
					List<Integer> best = null;
					long bestD = Long.MAX_VALUE;
					for (List<Integer> x : next.exits)
					{
						long dx = x.get(0) - l[2];
						long dy = x.get(1) - l[3];
						if (dx * dx + dy * dy < bestD)
						{
							bestD = dx * dx + dy * dy;
							best = x;
						}
					}
					if (best != null)
					{
						found.computeIfAbsent(m, k -> new ArrayList<>()).add(List.of(l[0], l[1], best.get(2), best.get(3)));
					}
				}
			}
			found.forEach((m, xs) -> m.exits.addAll(xs));
		}
	}

	private static Archive archive(Index index, String name, int id)
	{
		Archive a = index.isNamed() ? index.findArchiveByName(name) : null;
		return a != null ? a : index.getArchive(id);
	}

	private static List<FSFile> files(Store store, Archive archive) throws IOException
	{
		return new ArrayList<>(archive.getFiles(store.getStorage().loadArchive(archive)).getFiles());
	}

	/** Every display zone of the map with its source offset, merged into rectangles {x, y, w, h, dx, dy}. */
	static List<int[]> rectangles(String name, WorldMapCompositeDefinition map)
	{
		// display zone (x, y) -> offset to its source, both in tiles
		Map<Long, int[]> cells = new TreeMap<>();
		int clashes = 0;
		for (MapSquareDefinition s : map.getMapSquareDefinitions())
		{
			int dx = (s.getSourceSquareX() - s.getDisplaySquareX()) * 64;
			int dy = (s.getSourceSquareZ() - s.getDisplaySquareZ()) * 64;
			for (int zx = 0; zx < 64 / ZONE; zx++)
			{
				for (int zy = 0; zy < 64 / ZONE; zy++)
				{
					clashes += put(cells, s.getDisplaySquareX() * 8 + zx, s.getDisplaySquareZ() * 8 + zy, dx, dy);
				}
			}
		}
		for (ZoneDefinition z : map.getZoneDefinitions())
		{
			int displayX = z.getDisplaySquareX() * 8 + z.getDisplayZoneX();
			int displayY = z.getDisplaySquareZ() * 8 + z.getDisplayZoneZ();
			int sourceX = z.getSourceSquareX() * 8 + z.getSourceZoneX();
			int sourceY = z.getSourceSquareZ() * 8 + z.getSourceZoneZ();
			clashes += put(cells, displayX, displayY, (sourceX - displayX) * ZONE, (sourceY - displayY) * ZONE);
		}
		if (clashes > 0)
		{
			System.err.println(name + ": " + clashes + " zones shown twice with different sources; kept the first");
		}

		// group by offset, then merge rows of zones into runs and equal runs into rectangles
		Map<Long, List<long[]>> byOffset = new TreeMap<>();
		for (Map.Entry<Long, int[]> c : cells.entrySet())
		{
			long key = ((long) c.getValue()[0] << 32) | (c.getValue()[1] & 0xffffffffL);
			byOffset.computeIfAbsent(key, k -> new ArrayList<>()).add(new long[]{c.getKey() >> 32, c.getKey() & 0xffffffffL});
		}
		List<int[]> out = new ArrayList<>();
		for (Map.Entry<Long, List<long[]>> group : byOffset.entrySet())
		{
			int dx = (int) (group.getKey() >> 32);
			int dy = (int) (long) group.getKey();
			List<long[]> zones = group.getValue();
			zones.sort(Comparator.<long[]>comparingLong(z -> z[1]).thenComparingLong(z -> z[0]));
			Map<Long, int[]> open = new HashMap<>(); // (x0, x1) -> {x0, x1, y0, y1} in zones
			List<int[]> done = new ArrayList<>();
			int i = 0;
			while (i < zones.size())
			{
				int y = (int) zones.get(i)[1];
				int x0 = (int) zones.get(i)[0];
				int x1 = x0;
				i++;
				while (i < zones.size() && zones.get(i)[1] == y && zones.get(i)[0] == x1 + 1)
				{
					x1++;
					i++;
				}
				long key = ((long) x0 << 32) | x1;
				int[] rect = open.get(key);
				if (rect != null && rect[3] == y - 1)
				{
					rect[3] = y;
				}
				else
				{
					if (rect != null)
					{
						done.add(rect);
					}
					open.put(key, new int[]{x0, x1, y, y});
				}
			}
			done.addAll(open.values());
			for (int[] r : done)
			{
				out.add(new int[]{r[0] * ZONE, r[2] * ZONE, (r[1] - r[0] + 1) * ZONE, (r[3] - r[2] + 1) * ZONE, dx, dy});
			}
		}
		out.sort(Comparator.<int[]>comparingInt(r -> r[1]).thenComparingInt(r -> r[0]));
		return out;
	}

	/** Records a display zone; returns 1 when it was already there with another offset. */
	private static int put(Map<Long, int[]> cells, int zoneX, int zoneY, int dx, int dy)
	{
		long key = ((long) zoneX << 32) | (zoneY & 0xffffffffL);
		int[] had = cells.putIfAbsent(key, new int[]{dx, dy});
		return had != null && (had[0] != dx || had[1] != dy) ? 1 : 0;
	}
}
