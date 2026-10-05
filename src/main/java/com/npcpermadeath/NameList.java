package com.npcpermadeath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.client.util.Text;

/**
 * The comma-separated NPC name lists kept in the config ("Only these NPCs" and
 * "Never these NPCs"), as immutable lists. The panel and the settings page edit
 * the same text, so both go through here.
 */
final class NameList
{
	private NameList()
	{
	}

	/** Splits the config text into trimmed, non-empty entries. */
	static List<String> parse(String csv)
	{
		List<String> out = new ArrayList<>();
		for (String entry : Text.fromCSV(csv == null ? "" : csv))
		{
			String trimmed = entry.trim();
			if (!trimmed.isEmpty())
			{
				out.add(trimmed);
			}
		}
		return Collections.unmodifiableList(out);
	}

	/** The config text for the entries. */
	static String toCsv(List<String> entries)
	{
		return String.join(", ", entries);
	}

	/**
	 * Adds what was typed. Several names separated by commas are added one by
	 * one; blanks and entries already present (ignoring case) are skipped.
	 *
	 * @return a new list, or the same list when nothing was added
	 */
	static List<String> add(List<String> entries, String typed)
	{
		List<String> out = new ArrayList<>(entries);
		for (String candidate : parse(typed))
		{
			if (indexOf(out, candidate) < 0)
			{
				out.add(candidate);
			}
		}
		return out.size() == entries.size() ? entries : Collections.unmodifiableList(out);
	}

	/**
	 * @return a new list without the entry (ignoring case), or the same list
	 * when it was not there
	 */
	static List<String> remove(List<String> entries, String entry)
	{
		int at = indexOf(entries, entry.trim());
		if (at < 0)
		{
			return entries;
		}
		List<String> out = new ArrayList<>(entries);
		out.remove(at);
		return Collections.unmodifiableList(out);
	}

	static boolean contains(List<String> entries, String entry)
	{
		return indexOf(entries, entry.trim()) >= 0;
	}

	private static int indexOf(List<String> entries, String entry)
	{
		for (int i = 0; i < entries.size(); i++)
		{
			if (entries.get(i).equalsIgnoreCase(entry))
			{
				return i;
			}
		}
		return -1;
	}
}
