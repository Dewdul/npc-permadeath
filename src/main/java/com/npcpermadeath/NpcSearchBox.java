package com.npcpermadeath;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.components.IconTextField;

/**
 * The panel's NPC search: a search field with name suggestions under it, as
 * in the name filters. Picking a suggestion, or pressing Enter on typed text,
 * searches; emptying the field (or its clear button) ends the search.
 * Everything here runs on the Swing thread.
 */
class NpcSearchBox extends JPanel
{
	private static final int ROW_HEIGHT = 18;

	private final BiFunction<String, Integer, List<String>> suggestions;
	private final Consumer<String> onSearch;
	private final IconTextField field = new IconTextField();
	private final DefaultListModel<String> model = new DefaultListModel<>();
	private final JList<String> suggestionList = new JList<>(model);
	/** Set while the box writes the field itself, so that does not offer suggestions. */
	private boolean settingText;
	/** The search being shown, or null. */
	private String active;

	/**
	 * @param suggestions names matching the typed text, given the text and a limit
	 * @param onSearch called with the search text, or with null when the search ends
	 */
	NpcSearchBox(BiFunction<String, Integer, List<String>> suggestions, Consumer<String> onSearch)
	{
		this.suggestions = suggestions;
		this.onSearch = onSearch;
		setLayout(new GridBagLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel heading = new JLabel("Find an NPC");
		heading.setFont(FontManager.getRunescapeBoldFont());
		heading.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		heading.setToolTipText("Lists every chunk the NPC spawns in, with your kills there");
		NameFilterEditor.stack(this, heading, 0);

		field.setIcon(IconTextField.Icon.SEARCH);
		field.setPreferredSize(new Dimension(0, 30));
		field.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		field.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		field.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				textChanged();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				textChanged();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				textChanged();
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
		NameFilterEditor.stack(this, field, 4);

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
					pick(model.get(row));
				}
			}
		});
		NameFilterEditor.stack(this, suggestionList, 0);
	}

	/** The search being shown, or null. */
	String getActive()
	{
		return active;
	}

	private void textChanged()
	{
		if (settingText)
		{
			return;
		}
		if (field.getText().trim().isEmpty())
		{
			hideSuggestions();
			if (active != null)
			{
				search(null);
			}
			return;
		}
		refreshSuggestions();
	}

	/** Enter: the highlighted suggestion if there is one, otherwise exactly what was typed. */
	private void commit()
	{
		int row = suggestionList.getSelectedIndex();
		pick(row >= 0 ? model.get(row) : field.getText());
	}

	private void pick(String text)
	{
		String query = text == null ? "" : text.trim();
		if (query.isEmpty())
		{
			return;
		}
		settingText = true;
		try
		{
			field.setText(query);
		}
		finally
		{
			settingText = false;
		}
		hideSuggestions();
		search(query);
	}

	private void search(String query)
	{
		active = query;
		onSearch.accept(query);
	}

	private int rowAt(MouseEvent e)
	{
		int row = suggestionList.locationToIndex(e.getPoint());
		return row >= 0 && suggestionList.getCellBounds(row, row).contains(e.getPoint()) ? row : -1;
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
		model.clear();
		suggestions.apply(field.getText(), NameFilterEditor.MAX_SUGGESTIONS).forEach(model::addElement);
		suggestionList.setVisible(!model.isEmpty());
		revalidate();
		repaint();
	}
}
