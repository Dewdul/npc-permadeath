package com.npcpermadeath;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import lombok.Value;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Sidebar list of everything slain, one section per map chunk, or the
 * results of an NPC search: every chunk the NPC is in. Clicking a chunk
 * centres the world map on it.
 */
class PermadeathPanel extends PluginPanel
{
	/** How far along an NPC type is, in display order. */
	enum Progress
	{
		STARTED(ColorScheme.BRAND_ORANGE),
		CLEARED(ColorScheme.PROGRESS_ERROR_COLOR),
		UNTOUCHED(ColorScheme.LIGHT_GRAY_COLOR);

		final Color color;

		Progress(Color color)
		{
			this.color = color;
		}
	}

	/** One NPC type in one map chunk. */
	@Value
	static class Row
	{
		AreaKey area;
		/** Heading for the chunk, e.g. "Lumbridge (3200, 3200)". */
		String heading;
		int kills;
		/** Spawns in the chunk, or null if unknown. */
		Integer total;
		int hiddenHere;

		Progress getProgress()
		{
			if (kills == 0)
			{
				return Progress.UNTOUCHED;
			}
			return total != null && kills >= total ? Progress.CLEARED : Progress.STARTED;
		}
	}

	/** One searched NPC type summed over every chunk found. */
	@Value
	static class Total
	{
		String name;
		int kills;
		/** Spawns in the chunks that have a count, or null if none has. */
		Integer total;
		int chunks;
		/** Chunks with no spawn count, left out of {@link #total}. */
		int chunksWithoutTotal;

		Progress getProgress()
		{
			if (kills == 0)
			{
				return Progress.UNTOUCHED;
			}
			return total != null && chunksWithoutTotal == 0 && kills >= total ? Progress.CLEARED : Progress.STARTED;
		}
	}

	/** What a search found. */
	@Value
	static class SearchResult
	{
		String query;
		/** The NPC types listed, at most {@link NpcSearch#MAX_NAMES}. */
		List<String> names;
		/** Whether more NPC types matched than are listed. */
		boolean moreNames;
		/** Each listed type over every chunk found, even past {@link NpcSearch#MAX_ROWS}. */
		List<Total> totals;
		/** Distinct chunks found, even past {@link NpcSearch#MAX_ROWS}. */
		int chunks;
		/** In display order, at most {@link NpcSearch#MAX_ROWS}. */
		List<Row> rows;
		/** Whether more chunks matched than are listed. */
		boolean moreRows;
	}

	/** The list under the header: as wide as the sidebar, as tall as its rows. */
	private static class ListPanel extends JPanel implements Scrollable
	{
		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return Math.max(16, visible.height - 16);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private final Consumer<AreaKey> onForget;
	private final Consumer<Integer> onShowOnMap;
	private final JLabel summary = new JLabel();
	private final ListPanel list = new ListPanel();
	private final JScrollPane scroll = new JScrollPane(list);
	private final NpcSearchBox searchBox;
	private final JLabel filterHeader = new JLabel();
	private final JPanel filterBody = new JPanel(new GridBagLayout());
	private final NameFilterEditor neverEditor;
	private final NameFilterEditor onlyEditor;
	/** The search last shown, or null while the kill list is shown. */
	private String shownQuery;

	/**
	 * @param suggestNames NPC names matching some typed text, given the text and a limit
	 * @param onFilterChanged called with the config key and the new comma-separated text when the user edits a name filter
	 * @param onSearch called with the search text when the user searches, and with null when the search ends
	 */
	PermadeathPanel(Consumer<AreaKey> onForget, Runnable onOpenSettings, Consumer<Integer> onShowOnMap,
		BiFunction<String, Integer, List<String>> suggestNames, BiConsumer<String, String> onFilterChanged,
		Consumer<String> onSearch)
	{
		super(false);
		this.onForget = onForget;
		this.onShowOnMap = onShowOnMap;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		JLabel title = new JLabel("NPC Permadeath");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		header.add(title, BorderLayout.WEST);
		JButton settings = new JButton("Settings");
		settings.setFont(FontManager.getRunescapeSmallFont());
		settings.setMargin(new Insets(1, 6, 1, 6));
		settings.setFocusPainted(false);
		settings.setToolTipText("Open the plugin settings");
		settings.addActionListener(e -> onOpenSettings.run());
		header.add(settings, BorderLayout.EAST);
		summary.setFont(FontManager.getRunescapeSmallFont());
		summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(summary, BorderLayout.SOUTH);

		searchBox = new NpcSearchBox(suggestNames, onSearch);
		searchBox.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

		neverEditor = new NameFilterEditor("Never these NPCs", "Always respawn as normal. Pick a name or type one, "
			+ "wildcards like Cow* work.", "None.", suggestNames, names ->
		{
			onFilterChanged.accept(NpcPermadeathConfig.KEY_IGNORED_NAMES, NameList.toCsv(names));
			updateFilterHeader();
		});
		onlyEditor = new NameFilterEditor("Only these NPCs", "Only these can be killed for good. "
			+ "Leave empty for every NPC.", "Empty: every NPC counts.", suggestNames, names ->
		{
			onFilterChanged.accept(NpcPermadeathConfig.KEY_NPC_NAMES, NameList.toCsv(names));
			updateFilterHeader();
		});
		filterBody.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filterBody.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
		NameFilterEditor.stack(filterBody, neverEditor, 0);
		NameFilterEditor.stack(filterBody, onlyEditor, 10);
		filterBody.setVisible(false);
		filterHeader.setFont(FontManager.getRunescapeBoldFont());
		filterHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		filterHeader.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
		filterHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		filterHeader.setToolTipText("Choose which NPCs this applies to");
		filterHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				filterBody.setVisible(!filterBody.isVisible());
				updateFilterHeader();
				PermadeathPanel.this.revalidate();
				PermadeathPanel.this.repaint();
			}
		});
		JPanel filters = new JPanel(new BorderLayout());
		filters.setBackground(ColorScheme.DARK_GRAY_COLOR);
		filters.add(filterHeader, BorderLayout.NORTH);
		filters.add(filterBody, BorderLayout.CENTER);
		updateFilterHeader();

		JPanel north = new JPanel(new BorderLayout());
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(header, BorderLayout.NORTH);
		north.add(searchBox, BorderLayout.CENTER);
		north.add(filters, BorderLayout.SOUTH);
		add(north, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		update(Collections.emptyList());
	}

	/** Shows the name filters held in the config. Must be called on the Swing thread. */
	void setFilters(String neverCsv, String onlyCsv)
	{
		neverEditor.setEntries(NameList.parse(neverCsv));
		onlyEditor.setEntries(NameList.parse(onlyCsv));
		updateFilterHeader();
	}

	private void updateFilterHeader()
	{
		int active = neverEditor.getEntries().size() + onlyEditor.getEntries().size();
		String counts = active == 0 ? "" : " (" + active + ")";
		filterHeader.setText((filterBody.isVisible() ? "v " : "> ") + "NPC filters" + counts);
	}

	/** Shows the kill list. Must be called on the Swing thread. */
	void update(List<Row> rows)
	{
		boolean wasSearch = shownQuery != null;
		shownQuery = null;
		list.removeAll();
		int totalKills = rows.stream().mapToInt(Row::getKills).sum();
		long chunks = rows.stream().filter(r -> r.getKills() > 0).map(r -> r.getArea().getRegion()).distinct().count();
		if (rows.isEmpty())
		{
			summary.setText("Nothing slain yet.");
			list.add(hint("Kill something. Every kill keeps one more of its kind hidden in that chunk of the map."));
		}
		else
		{
			summary.setText("<html>" + totalKills + " slain across " + chunks + " chunk" + (chunks == 1 ? "" : "s")
				+ ". Open the world map to see them.</html>");
			Map<String, List<Row>> byChunk = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			for (Row row : rows)
			{
				byChunk.computeIfAbsent(row.getHeading(), h -> new ArrayList<>()).add(row);
			}
			addChunks(byChunk.values());
		}
		finish(wasSearch);
	}

	/** Shows what a search found. Must be called on the Swing thread. */
	void showSearch(SearchResult result)
	{
		boolean newSearch = !result.getQuery().equals(shownQuery);
		shownQuery = result.getQuery();
		list.removeAll();
		List<Row> rows = result.getRows();
		if (rows.isEmpty())
		{
			summary.setText("<html>Nothing known about " + escape(result.getQuery()) + " yet.</html>");
			list.add(hint("No spawns or kills of it are known. The spawn data covers NPCs that can be attacked; "
				+ "the wiki is asked too, so try again in a moment."));
		}
		else
		{
			int chunks = result.getChunks();
			String what = result.getNames().size() == 1
				? escape(result.getNames().get(0))
				: result.getNames().size() + " NPC types";
			StringBuilder text = new StringBuilder("<html>").append(what).append(" in ")
				.append(chunks).append(chunks == 1 ? " chunk" : " chunks")
				.append(result.isMoreRows() ? " (first " + NpcSearch.MAX_ROWS + " listed)" : "")
				.append(". Click one to see it on the world map.");
			if (result.isMoreNames())
			{
				text.append(" Only the first ").append(NpcSearch.MAX_NAMES).append(" NPC types are listed.");
			}
			summary.setText(text.append("</html>").toString());
			for (Total t : result.getTotals())
			{
				list.add(totalRow(t, result.getNames().size() == 1 ? "Total" : t.getName()));
			}
			list.add(Box.createVerticalStrut(6));
			Map<Integer, List<Row>> byChunk = new LinkedHashMap<>();
			for (Row row : rows)
			{
				byChunk.computeIfAbsent(row.getArea().getRegion(), r -> new ArrayList<>()).add(row);
			}
			addChunks(byChunk.values());
		}
		finish(newSearch);
	}

	/** The search being shown, or null while the kill list is shown. */
	String getShownQuery()
	{
		return shownQuery;
	}

	private void addChunks(Collection<List<Row>> chunks)
	{
		for (List<Row> chunkRows : chunks)
		{
			list.add(chunkHeader(chunkRows.get(0).getHeading(), chunkRows.get(0).getArea().getRegion()));
			for (Row row : chunkRows)
			{
				list.add(rowPanel(row));
			}
			list.add(Box.createVerticalStrut(6));
		}
	}

	private void finish(boolean toTop)
	{
		list.revalidate();
		list.repaint();
		if (toTop)
		{
			scroll.getVerticalScrollBar().setValue(0);
		}
	}

	private static JLabel hint(String text)
	{
		JLabel hint = new JLabel("<html>" + text + "</html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hint.setAlignmentX(LEFT_ALIGNMENT);
		return hint;
	}

	private static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private JLabel chunkHeader(String heading, int region)
	{
		JLabel label = new JLabel(heading);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
		label.setAlignmentX(LEFT_ALIGNMENT);
		// as wide as the list, so a long heading ends in "..." instead of running under the edge
		label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
		label.setToolTipText(heading + ": click to centre the world map on this chunk");
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onShowOnMap.accept(region);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(ColorScheme.BRAND_ORANGE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}
		});
		return label;
	}

	private JPanel rowPanel(Row row)
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 3));
		panel.setPreferredSize(new Dimension(0, 26));

		GridBagConstraints c = new GridBagConstraints();
		c.gridy = 0;
		c.insets = new Insets(0, 0, 0, 4);
		c.anchor = GridBagConstraints.WEST;

		JLabel name = new JLabel(row.getArea().getName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(row.getProgress() == Progress.UNTOUCHED ? ColorScheme.LIGHT_GRAY_COLOR : Color.WHITE);
		// may shrink, ending in "...", so the count and button always fit
		name.setMinimumSize(new Dimension(0, name.getPreferredSize().height));
		c.gridx = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		panel.add(name, c);

		String tally = row.getTotal() == null
			? row.getKills() + " slain"
			: row.getKills() + " / " + row.getTotal();
		JLabel count = new JLabel(tally, SwingConstants.RIGHT);
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(row.getProgress().color);
		count.setToolTipText(row.getHiddenHere() + " hidden on this world"
			+ (row.getTotal() == null ? ", no spawn data yet" : ", " + row.getTotal() + " spawns known in this chunk"));
		c.gridx = 1;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		panel.add(count, c);

		if (row.getKills() > 0)
		{
			JButton forget = new JButton("x");
			forget.setFont(FontManager.getRunescapeSmallFont());
			forget.setToolTipText("Forget these kills and bring them back");
			forget.setMargin(new Insets(0, 4, 0, 4));
			forget.setFocusPainted(false);
			forget.addActionListener(e -> onForget.accept(row.getArea()));
			c.gridx = 2;
			c.insets = new Insets(0, 0, 0, 0);
			panel.add(forget, c);
		}
		showOnMapWhenClicked(row.getArea().getRegion(), panel, name, count);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		wrapper.add(panel, BorderLayout.CENTER);
		return wrapper;
	}

	/** A searched NPC type's kills and spawns over every chunk, above the chunks. */
	private static JPanel totalRow(Total t, String label)
	{
		JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, t.getProgress().color),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)));

		JLabel name = new JLabel(label);
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(Color.WHITE);
		name.setMinimumSize(new Dimension(0, name.getPreferredSize().height));
		panel.add(name, BorderLayout.CENTER);

		JLabel count = new JLabel(t.getTotal() == null ? t.getKills() + " slain" : t.getKills() + " / " + t.getTotal(),
			SwingConstants.RIGHT);
		count.setFont(FontManager.getRunescapeBoldFont());
		count.setForeground(t.getProgress().color);
		String tip = "Your kills and the known spawns across all " + t.getChunks()
			+ (t.getChunks() == 1 ? " chunk" : " chunks");
		if (t.getChunksWithoutTotal() > 0)
		{
			tip += "; " + t.getChunksWithoutTotal() + (t.getChunksWithoutTotal() == 1 ? " has" : " have")
				+ " no spawn count yet";
		}
		count.setToolTipText(tip);
		panel.add(count, BorderLayout.EAST);

		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		wrapper.add(panel, BorderLayout.CENTER);
		return wrapper;
	}

	/** A click on the row (not its button) centres the world map on the chunk, like the heading. */
	private void showOnMapWhenClicked(int region, JComponent... parts)
	{
		MouseAdapter click = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onShowOnMap.accept(region);
			}
		};
		for (JComponent part : parts)
		{
			part.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			part.addMouseListener(click);
		}
	}
}
