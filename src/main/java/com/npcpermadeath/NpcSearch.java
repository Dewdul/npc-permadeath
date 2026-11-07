package com.npcpermadeath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.runelite.client.util.WildcardMatcher;

/** What an NPC search in the panel means, and the order its results are listed in. */
final class NpcSearch
{
	/** The most NPC types one search lists; a broad wildcard is cut short here. */
	static final int MAX_NAMES = 25;
	/** The most chunks one search lists. */
	static final int MAX_ROWS = 300;

	private NpcSearch()
	{
	}

	/**
	 * The NPC names a search means, in the order of {@code known}:
	 * <ul>
	 * <li>with a {@code *}, every known name the wildcard matches (Skeleton* finds Skeleton mage too);</li>
	 * <li>otherwise the known name equal to it, ignoring case;</li>
	 * <li>otherwise every known name containing it;</li>
	 * <li>otherwise the text itself, so kills or wiki spawns under a name the
	 * spawn data lacks can still be found.</li>
	 * </ul>
	 */
	static List<String> namesFor(String query, Collection<String> known)
	{
		String q = query == null ? "" : query.trim();
		if (q.isEmpty())
		{
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		if (q.contains("*"))
		{
			for (String name : known)
			{
				if (WildcardMatcher.matches(q, name))
				{
					out.add(name);
				}
			}
			return out;
		}
		for (String name : known)
		{
			if (name.equalsIgnoreCase(q))
			{
				return Collections.singletonList(name);
			}
		}
		String needle = q.toLowerCase(Locale.ROOT);
		for (String name : known)
		{
			if (name.toLowerCase(Locale.ROOT).contains(needle))
			{
				out.add(name);
			}
		}
		return out.isEmpty() ? Collections.singletonList(q) : out;
	}

	/** Each name's kills and known spawns summed over its rows, for names with any. */
	static List<PermadeathPanel.Total> totals(List<String> names, List<PermadeathPanel.Row> rows)
	{
		List<PermadeathPanel.Total> out = new ArrayList<>();
		for (String name : names)
		{
			int kills = 0;
			int total = 0;
			int chunks = 0;
			int without = 0;
			for (PermadeathPanel.Row r : rows)
			{
				if (!r.getArea().getName().equalsIgnoreCase(name))
				{
					continue;
				}
				chunks++;
				kills += r.getKills();
				if (r.getTotal() == null)
				{
					without++;
				}
				else
				{
					total += r.getTotal();
				}
			}
			if (chunks > 0)
			{
				out.add(new PermadeathPanel.Total(name, kills, without == chunks ? null : total, chunks, without));
			}
		}
		return out;
	}

	/** Distinct chunks among the rows. */
	static int chunks(List<PermadeathPanel.Row> rows)
	{
		return (int) rows.stream().map(r -> r.getArea().getRegion()).distinct().count();
	}

	/**
	 * Result order: chunks you have kills in first, most kills first, then
	 * the rest by heading; the NPC types of one chunk stay together.
	 */
	static List<PermadeathPanel.Row> order(List<PermadeathPanel.Row> rows)
	{
		java.util.Map<Integer, Integer> killsByRegion = new java.util.HashMap<>();
		for (PermadeathPanel.Row r : rows)
		{
			killsByRegion.merge(r.getArea().getRegion(), r.getKills(), Integer::sum);
		}
		List<PermadeathPanel.Row> out = new ArrayList<>(rows);
		out.sort(Comparator
			.comparingInt((PermadeathPanel.Row r) -> -killsByRegion.get(r.getArea().getRegion()))
			.thenComparing(PermadeathPanel.Row::getHeading, String.CASE_INSENSITIVE_ORDER)
			.thenComparingInt(r -> r.getArea().getRegion())
			.thenComparing(r -> r.getArea().getName(), String.CASE_INSENSITIVE_ORDER));
		return out;
	}
}
