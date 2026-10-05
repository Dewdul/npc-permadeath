package com.npcpermadeath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPCComposition;
import net.runelite.client.util.Text;

/**
 * The NPC names offered by the filter type-ahead: every attackable NPC the game
 * client knows about, read from its own definitions a slice at a time, so the
 * list covers the whole game and stays current without a plugin update.
 *
 * <p>Until the scan finishes, the names from the bundled spawn data are
 * offered instead, together with the extra names (see {@link #setExtraNames}).
 * {@link #step} runs on the client thread; {@link #suggest} may be called from
 * any thread.
 *
 * <p>Multi-form NPCs need no special handling: each form is an NPC id of its
 * own, so every form is visited and judged by its own name and actions.
 */
@Slf4j
class NpcNameIndex
{
	/** The most ids looked at in one slice. */
	static final int SLICE_IDS = 1000;
	/** A slice also stops once it has run this long, so the client never stutters. */
	static final long SLICE_NANOS = 4_000_000L;
	/** The highest id in the API's NpcID table; the scan always covers at least this far. */
	static final int KNOWN_MAX_ID = 16575;
	/** Past the known maximum, the scan stops after this many ids in a row without a name. */
	static final int EMPTY_RUN_LIMIT = 1000;
	/** The scan never goes past this id, whatever the client reports. */
	static final int HARD_CAP_ID = 40000;

	/** An immutable, alphabetically sorted list of names with their lower-case forms. */
	private static final class Names
	{
		static final Names NONE = new Names(new TreeMap<>());

		final List<String> names;
		final List<String> lower;

		Names(TreeMap<String, String> byLowerCase)
		{
			names = Collections.unmodifiableList(new ArrayList<>(byLowerCase.values()));
			lower = Collections.unmodifiableList(new ArrayList<>(byLowerCase.keySet()));
		}
	}

	/** The seed and extra names together, offered until the scan has finished. */
	private volatile Names beforeScan = Names.NONE;
	/** The scanned names plus the extras that exist as NPCs; null until the scan has finished. */
	private volatile Names built;
	private volatile boolean cancelled;

	// Inputs behind the published lists; guarded by lock, since they are set from any thread.
	private final Object lock = new Object();
	private List<String> seedNames = Collections.emptyList();
	private List<String> extraNames = Collections.emptyList();
	/** The attackable names of the finished scan by lower case, or null while it is unfinished. */
	private Map<String, String> scannedNames;
	/** The lower-cased name of every definition the finished scan visited, or null. */
	private Set<String> seenNames;

	// Scan state, touched by the client thread only.
	private final TreeMap<String, String> found = new TreeMap<>();
	private final Set<String> seen = new HashSet<>();
	private int nextId;
	private int emptyRun;
	private boolean scanDone;

	/** Names to offer until the scan has finished. */
	void setSeedNames(Collection<String> names)
	{
		synchronized (lock)
		{
			seedNames = new ArrayList<>(names);
			publish();
		}
	}

	/**
	 * Names that are always on offer, such as boss names that have no static spawn
	 * and may lack an Attack action. Before the scan has finished they are all
	 * offered; afterwards only those that some NPC definition actually carries.
	 * Replaces any earlier extras.
	 */
	void setExtraNames(Collection<String> names)
	{
		synchronized (lock)
		{
			extraNames = new ArrayList<>(names);
			publish();
		}
	}

	/** Rebuilds the published lists from the inputs. Call with the lock held. */
	private void publish()
	{
		TreeMap<String, String> pre = new TreeMap<>();
		addAll(pre, seedNames);
		addAll(pre, extraNames);
		beforeScan = new Names(pre);
		if (scannedNames != null)
		{
			TreeMap<String, String> after = new TreeMap<>(scannedNames);
			for (String extra : extraNames)
			{
				if (seenNames.contains(extra.toLowerCase(Locale.ROOT)))
				{
					after.putIfAbsent(extra.toLowerCase(Locale.ROOT), extra);
				}
			}
			built = new Names(after);
		}
	}

	private static void addAll(TreeMap<String, String> byLowerCase, Collection<String> names)
	{
		for (String name : names)
		{
			byLowerCase.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
		}
	}

	/** Stops the scan at the next slice. */
	void cancel()
	{
		cancelled = true;
	}

	/** Whether the full scan has finished and replaced the fallback names. */
	boolean isComplete()
	{
		return built != null;
	}

	/** How many names are on offer right now. */
	int size()
	{
		return current().names.size();
	}

	/**
	 * Looks at the next slice of NPC ids. Call again until it returns true.
	 *
	 * @param definitions the client's NPC definitions by id; may return null
	 * @return true once the scan is finished or cancelled
	 */
	boolean step(IntFunction<NPCComposition> definitions)
	{
		if (cancelled || scanDone)
		{
			return true;
		}
		long deadline = System.nanoTime() + SLICE_NANOS;
		for (int looked = 0; looked < SLICE_IDS; looked++)
		{
			if (nextId >= HARD_CAP_ID || nextId > KNOWN_MAX_ID && emptyRun >= EMPTY_RUN_LIMIT)
			{
				finish();
				return true;
			}
			visit(definitions, nextId++);
			if (looked % 50 == 49 && System.nanoTime() > deadline)
			{
				break;
			}
		}
		return false;
	}

	private void visit(IntFunction<NPCComposition> definitions, int id)
	{
		NPCComposition definition;
		try
		{
			definition = definitions.apply(id);
		}
		catch (RuntimeException e)
		{
			definition = null;
		}
		String name = definition == null ? null : cleanName(definition.getName());
		if (name == null)
		{
			emptyRun++;
			return;
		}
		emptyRun = 0;
		String lower = name.toLowerCase(Locale.ROOT);
		seen.add(lower);
		if (hasAttack(definition.getActions()))
		{
			found.putIfAbsent(lower, name);
		}
	}

	private void finish()
	{
		scanDone = true;
		if (!found.isEmpty())
		{
			synchronized (lock)
			{
				scannedNames = Collections.unmodifiableMap(new TreeMap<>(found));
				seenNames = Collections.unmodifiableSet(new HashSet<>(seen));
				publish();
			}
		}
		log.debug("Scanned {} NPC ids, {} attackable names, {} names in all", nextId, found.size(), seen.size());
	}

	/** The display name with tags removed, or null for the blank and "null" placeholders. */
	static String cleanName(String raw)
	{
		if (raw == null)
		{
			return null;
		}
		String name = Text.removeTags(raw).trim();
		return name.isEmpty() || "null".equalsIgnoreCase(name) ? null : name;
	}

	static boolean hasAttack(String[] actions)
	{
		if (actions != null)
		{
			for (String action : actions)
			{
				if ("Attack".equalsIgnoreCase(action))
				{
					return true;
				}
			}
		}
		return false;
	}

	private Names current()
	{
		Names scanned = built;
		return scanned != null ? scanned : beforeScan;
	}

	/** Up to limit names matching what was typed, best matches first. See {@link #rank}. */
	List<String> suggest(String typed, int limit)
	{
		Names names = current();
		return rank(names.names, names.lower, typed, limit);
	}

	/**
	 * Case-insensitive matches, in three tiers: names that start with the text,
	 * then names with a word that starts with it, then names that merely contain
	 * it. Each tier keeps the input order, so sorted input gives alphabetical
	 * tiers. Nothing is suggested for blank input.
	 *
	 * @param names sorted alphabetically
	 * @param lower the same names in lower case
	 */
	static List<String> rank(List<String> names, List<String> lower, String typed, int limit)
	{
		String needle = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
		if (needle.isEmpty() || limit <= 0)
		{
			return Collections.emptyList();
		}
		List<String> prefix = new ArrayList<>();
		List<String> wordStart = new ArrayList<>();
		List<String> substring = new ArrayList<>();
		for (int i = 0; i < names.size() && prefix.size() < limit; i++)
		{
			String candidate = lower.get(i);
			int at = candidate.indexOf(needle);
			if (at < 0)
			{
				continue;
			}
			if (at == 0)
			{
				prefix.add(names.get(i));
			}
			else if (startsWord(candidate, needle))
			{
				if (wordStart.size() < limit)
				{
					wordStart.add(names.get(i));
				}
			}
			else if (substring.size() < limit)
			{
				substring.add(names.get(i));
			}
		}
		List<String> out = new ArrayList<>(prefix);
		out.addAll(wordStart);
		out.addAll(substring);
		return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
	}

	/** Whether the needle occurs right after a space, dash, quote or other non-letter. */
	private static boolean startsWord(String candidate, String needle)
	{
		for (int at = candidate.indexOf(needle); at >= 0; at = candidate.indexOf(needle, at + 1))
		{
			if (at == 0 || !Character.isLetterOrDigit(candidate.charAt(at - 1)))
			{
				return true;
			}
		}
		return false;
	}
}
