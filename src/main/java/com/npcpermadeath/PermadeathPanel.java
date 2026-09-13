package com.npcpermadeath;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import lombok.Value;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/** Sidebar list of everything slain, grouped by place. */
class PermadeathPanel extends PluginPanel
{
	/** One NPC type in one map chunk. */
	@Value
	static class Row
	{
		AreaKey area;
		/** Wiki place name for the chunk, or a fallback. */
		String place;
		int kills;
		/** Spawns in the area per the wiki, or null if unknown. */
		Integer total;
		int hiddenHere;
	}

	private final Consumer<AreaKey> onForget;
	private final JLabel summary = new JLabel();
	private final JPanel list = new JPanel();

	PermadeathPanel(Consumer<AreaKey> onForget)
	{
		super(false);
		this.onForget = onForget;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		JLabel title = new JLabel("NPC Permadeath");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		header.add(title, BorderLayout.NORTH);
		summary.setFont(FontManager.getRunescapeSmallFont());
		summary.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		header.add(summary, BorderLayout.SOUTH);
		add(header, BorderLayout.NORTH);

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(list, BorderLayout.CENTER);

		update(java.util.Collections.emptyList());
	}

	/** Rebuilds the list. Must be called on the Swing thread. */
	void update(List<Row> rows)
	{
		list.removeAll();
		int totalKills = rows.stream().mapToInt(Row::getKills).sum();
		if (rows.isEmpty())
		{
			summary.setText("Nothing slain yet.");
			JLabel hint = new JLabel("<html>Kill something. Every kill keeps one more of its kind hidden in that area.</html>");
			hint.setFont(FontManager.getRunescapeSmallFont());
			hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			list.add(hint);
		}
		else
		{
			summary.setText(totalKills + " slain across " + countPlaces(rows) + " chunk" + (countPlaces(rows) == 1 ? "" : "s"));
			Map<String, java.util.Set<Integer>> chunksPerPlace = new java.util.HashMap<>();
			for (Row row : rows)
			{
				chunksPerPlace.computeIfAbsent(row.getPlace().toLowerCase(), p -> new java.util.HashSet<>())
					.add(row.getArea().getRegion());
			}
			Map<String, List<Row>> byChunk = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
			for (Row row : rows)
			{
				String heading = row.getPlace();
				if (chunksPerPlace.get(row.getPlace().toLowerCase()).size() > 1)
				{
					heading += " " + chunkCorner(row.getArea().getRegion());
				}
				byChunk.computeIfAbsent(heading, p -> new java.util.ArrayList<>()).add(row);
			}
			byChunk.forEach((place, placeRows) ->
			{
				list.add(placeHeader(place));
				placeRows.sort((a, b) -> a.getArea().getName().compareToIgnoreCase(b.getArea().getName()));
				for (Row row : placeRows)
				{
					list.add(rowPanel(row));
				}
				list.add(Box.createVerticalStrut(6));
			});
		}
		list.revalidate();
		list.repaint();
	}

	private static long countPlaces(List<Row> rows)
	{
		return rows.stream().map(r -> r.getArea().getRegion()).distinct().count();
	}

	/** The south-west world coordinate of a chunk, to tell chunks of the same place apart. */
	private static String chunkCorner(int region)
	{
		return "(" + ((region >> 8) << 6) + ", " + ((region & 0xff) << 6) + ")";
	}

	private static JLabel placeHeader(String place)
	{
		JLabel label = new JLabel(place);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
		label.setAlignmentX(LEFT_ALIGNMENT);
		return label;
	}

	private JPanel rowPanel(Row row)
	{
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 3));
		panel.setAlignmentX(LEFT_ALIGNMENT);
		panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));

		GridBagConstraints c = new GridBagConstraints();
		c.gridy = 0;
		c.insets = new Insets(0, 0, 0, 4);
		c.anchor = GridBagConstraints.WEST;

		JLabel name = new JLabel(row.getArea().getName());
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(java.awt.Color.WHITE);
		c.gridx = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		panel.add(name, c);

		String tally = row.getTotal() == null
			? row.getKills() + " slain"
			: row.getKills() + " / " + row.getTotal();
		JLabel count = new JLabel(tally, SwingConstants.RIGHT);
		count.setFont(FontManager.getRunescapeSmallFont());
		count.setForeground(row.getTotal() != null && row.getKills() >= row.getTotal()
			? ColorScheme.PROGRESS_ERROR_COLOR
			: ColorScheme.BRAND_ORANGE);
		count.setToolTipText(row.getHiddenHere() + " hidden on this world"
			+ (row.getTotal() == null ? "" : ", " + row.getTotal() + " spawns here per the wiki"));
		c.gridx = 1;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		panel.add(count, c);

		JButton forget = new JButton("x");
		forget.setFont(FontManager.getRunescapeSmallFont());
		forget.setToolTipText("Forget these kills and bring them back");
		forget.setMargin(new Insets(0, 4, 0, 4));
		forget.setFocusPainted(false);
		forget.addActionListener(e -> onForget.accept(row.getArea()));
		c.gridx = 2;
		c.insets = new Insets(0, 0, 0, 0);
		panel.add(forget, c);

		JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 1));
		wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
		wrapper.setAlignmentX(LEFT_ALIGNMENT);
		wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		panel.setPreferredSize(new Dimension(PANEL_WIDTH - 20, 26));
		wrapper.add(panel);
		return wrapper;
	}
}
