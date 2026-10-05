package com.npcpermadeath;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * One editable list of NPC names for the sidebar panel: a text field with a
 * suggestion list under it, and the current entries with a remove button each.
 *
 * <p>The suggestions are an inline list rather than a popup, so the text field
 * keeps the keyboard focus the whole time. Everything here runs on the Swing
 * thread.
 */
class NameFilterEditor extends JPanel
{
	/** The most suggestions shown at once. */
	static final int MAX_SUGGESTIONS = 8;
	private static final int ROW_HEIGHT = 18;
	/** Width the description wraps at, to fit the sidebar. */
	private static final int TEXT_WIDTH = 170;

	private final BiFunction<String, Integer, List<String>> suggestions;
	private final Consumer<List<String>> onChange;
	private final String emptyText;
	private final JTextField field = new JTextField();
	private final DefaultListModel<String> model = new DefaultListModel<>();
	private final JList<String> suggestionList = new JList<>(model);
	private final JPanel entryRows = new JPanel(new GridLayout(0, 1, 0, 2));
	private List<String> entries = Collections.emptyList();

	/**
	 * @param suggestions names matching the typed text, given the text and a limit
	 * @param onChange called with the new entries after the user adds or removes one
	 */
	NameFilterEditor(String title, String description, String emptyText,
		BiFunction<String, Integer, List<String>> suggestions, Consumer<List<String>> onChange)
	{
		this.suggestions = suggestions;
		this.onChange = onChange;
		this.emptyText = emptyText;
		setLayout(new GridBagLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel heading = new JLabel(title);
		heading.setFont(FontManager.getRunescapeBoldFont());
		heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		stack(this, heading, 0);

		JLabel hint = new JLabel("<html><body style='width:" + TEXT_WIDTH + "px'>" + description + "</body></html>");
		hint.setFont(FontManager.getRunescapeSmallFont());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		stack(this, hint, 2);

		field.setFont(FontManager.getRunescapeSmallFont());
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setForeground(Color.WHITE);
		field.setCaretColor(Color.WHITE);
		field.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, 5, 3, 5)));
		field.setToolTipText("Type a name, or a wildcard such as Cow*, then press Enter");
		field.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				refreshSuggestions();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				refreshSuggestions();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				refreshSuggestions();
			}
		});
		field.addActionListener(e -> commit());
		field.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				switch (e.getKeyCode())
				{
					case KeyEvent.VK_DOWN:
						moveSelection(1);
						e.consume();
						break;
					case KeyEvent.VK_UP:
						moveSelection(-1);
						e.consume();
						break;
					case KeyEvent.VK_ESCAPE:
						hideSuggestions();
						e.consume();
						break;
					default:
						break;
				}
			}
		});
		stack(this, field, 4);

		suggestionList.setFocusable(false);
		suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		suggestionList.setFixedCellHeight(ROW_HEIGHT);
		suggestionList.setFont(FontManager.getRunescapeSmallFont());
		suggestionList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		suggestionList.setForeground(Color.WHITE);
		suggestionList.setSelectionBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
		suggestionList.setSelectionForeground(ColorScheme.BRAND_ORANGE);
		suggestionList.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		suggestionList.setVisible(false);
		suggestionList.addMouseMotionListener(new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				int row = rowAt(e);
				if (row >= 0)
				{
					suggestionList.setSelectedIndex(row);
				}
			}
		});
		suggestionList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				int row = rowAt(e);
				if (row >= 0)
				{
					addEntry(model.get(row));
				}
			}
		});
		stack(this, suggestionList, 0);

		entryRows.setBackground(ColorScheme.DARK_GRAY_COLOR);
		stack(this, entryRows, 4);
		rebuildEntries();
	}

	/** Adds a full-width component under the previous ones. */
	static void stack(JPanel parent, JComponent child, int topGap)
	{
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = parent.getComponentCount();
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.NORTHWEST;
		c.insets = new Insets(topGap, 0, 0, 0);
		parent.add(child, c);
	}

	/** Shows the entries held in the config, e.g. after the settings page changed them. */
	void setEntries(List<String> newEntries)
	{
		if (entries.equals(newEntries))
		{
			return;
		}
		entries = newEntries;
		rebuildEntries();
		refreshSuggestions();
	}

	List<String> getEntries()
	{
		return entries;
	}

	private int rowAt(MouseEvent e)
	{
		int row = suggestionList.locationToIndex(e.getPoint());
		return row >= 0 && suggestionList.getCellBounds(row, row).contains(e.getPoint()) ? row : -1;
	}

	/** Enter: the highlighted suggestion if there is one, otherwise exactly what was typed. */
	private void commit()
	{
		int row = suggestionList.getSelectedIndex();
		addEntry(row >= 0 ? model.get(row) : field.getText());
	}

	private void addEntry(String text)
	{
		field.setText("");
		List<String> next = NameList.add(entries, text);
		if (next != entries)
		{
			entries = next;
			rebuildEntries();
			onChange.accept(entries);
		}
	}

	private void removeEntry(String entry)
	{
		List<String> next = NameList.remove(entries, entry);
		if (next != entries)
		{
			entries = next;
			rebuildEntries();
			refreshSuggestions();
			onChange.accept(entries);
		}
	}

	private void moveSelection(int delta)
	{
		if (model.isEmpty())
		{
			return;
		}
		int row = Math.max(-1, Math.min(model.size() - 1, suggestionList.getSelectedIndex() + delta));
		if (row < 0)
		{
			suggestionList.clearSelection();
		}
		else
		{
			suggestionList.setSelectedIndex(row);
		}
	}

	private void hideSuggestions()
	{
		model.clear();
		suggestionList.setVisible(false);
		revalidate();
		repaint();
	}

	private void refreshSuggestions()
	{
		List<String> matches = new ArrayList<>();
		String typed = field.getText();
		if (!typed.trim().isEmpty())
		{
			// Ask for extra so that names already listed can be skipped.
			for (String name : suggestions.apply(typed, MAX_SUGGESTIONS + entries.size()))
			{
				if (matches.size() < MAX_SUGGESTIONS && !NameList.contains(entries, name))
				{
					matches.add(name);
				}
			}
		}
		model.clear();
		matches.forEach(model::addElement);
		suggestionList.setVisible(!matches.isEmpty());
		revalidate();
		repaint();
	}

	private void rebuildEntries()
	{
		entryRows.removeAll();
		if (entries.isEmpty())
		{
			JLabel none = new JLabel(emptyText);
			none.setFont(FontManager.getRunescapeSmallFont());
			none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			entryRows.add(none);
		}
		for (String entry : entries)
		{
			entryRows.add(entryRow(entry));
		}
		revalidate();
		repaint();
	}

	private JPanel entryRow(String entry)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 3));

		JLabel name = new JLabel(entry);
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		name.setToolTipText(entry);
		row.add(name, BorderLayout.CENTER);

		JButton remove = new JButton("x");
		remove.setFont(FontManager.getRunescapeSmallFont());
		remove.setToolTipText("Remove " + entry);
		remove.setMargin(new Insets(0, 4, 0, 4));
		remove.setFocusPainted(false);
		remove.addActionListener(e -> removeEntry(entry));
		row.add(remove, BorderLayout.EAST);
		return row;
	}
}
