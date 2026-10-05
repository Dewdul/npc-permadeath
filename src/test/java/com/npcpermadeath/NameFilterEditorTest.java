package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JTextField;
import org.junit.Test;

/** Drives the editor the way the keyboard does, without showing it. */
public class NameFilterEditorTest
{
	private final List<List<String>> changes = new ArrayList<>();
	private final NpcNameIndex index = new NpcNameIndex();
	private final NameFilterEditor editor;
	private final JTextField field;
	private final JList<?> suggestionList;

	public NameFilterEditorTest()
	{
		index.setSeedNames(Arrays.asList("Cow", "Cow calf", "Goblin", "Giant rat", "Rat"));
		editor = new NameFilterEditor("Never", "d", "None.", index::suggest, changes::add);
		field = find(editor, JTextField.class);
		suggestionList = find(editor, JList.class);
	}

	private static <T extends Component> T find(Container root, Class<T> type)
	{
		for (Component c : root.getComponents())
		{
			if (type.isInstance(c))
			{
				return type.cast(c);
			}
			if (c instanceof Container)
			{
				T inner = find((Container) c, type);
				if (inner != null)
				{
					return inner;
				}
			}
		}
		return null;
	}

	private static void collect(Container root, Class<? extends Component> type, List<Component> out)
	{
		for (Component c : root.getComponents())
		{
			if (type.isInstance(c))
			{
				out.add(c);
			}
			if (c instanceof Container)
			{
				collect((Container) c, type, out);
			}
		}
	}

	private void press(int keyCode)
	{
		KeyEvent event = new KeyEvent(field, KeyEvent.KEY_PRESSED, 0, 0, keyCode, KeyEvent.CHAR_UNDEFINED);
		for (java.awt.event.KeyListener listener : field.getKeyListeners())
		{
			listener.keyPressed(event);
		}
	}

	@Test
	public void typingShowsSuggestionsAndEmptyTextHidesThem()
	{
		field.setText("cow");
		assertTrue(suggestionList.isVisible());
		assertEquals(2, suggestionList.getModel().getSize());

		field.setText("");
		assertFalse(suggestionList.isVisible());
	}

	@Test
	public void enterAddsExactlyWhatWasTypedWhenNothingIsHighlighted()
	{
		field.setText("Cow*");
		field.postActionEvent();

		assertEquals(Collections.singletonList(Collections.singletonList("Cow*")), changes);
		assertEquals("", field.getText());
	}

	@Test
	public void arrowKeysAndEnterPickASuggestion()
	{
		field.setText("cow");
		press(KeyEvent.VK_DOWN);
		press(KeyEvent.VK_DOWN);
		field.postActionEvent();

		assertEquals(Collections.singletonList(Collections.singletonList("Cow calf")), changes);
	}

	@Test
	public void upFromTheFirstSuggestionGoesBackToTheTypedText()
	{
		field.setText("cow");
		press(KeyEvent.VK_DOWN);
		press(KeyEvent.VK_UP);
		field.postActionEvent();

		assertEquals(Collections.singletonList(Collections.singletonList("cow")), changes);
	}

	@Test
	public void escapeClosesTheSuggestions()
	{
		field.setText("cow");
		press(KeyEvent.VK_ESCAPE);

		assertFalse(suggestionList.isVisible());
		assertEquals("cow", field.getText());
	}

	@Test
	public void namesAlreadyListedAreNotSuggestedOrAddedTwice()
	{
		field.setText("Cow");
		field.postActionEvent();
		field.setText("cow");
		assertEquals(1, suggestionList.getModel().getSize());
		assertEquals("Cow calf", suggestionList.getModel().getElementAt(0));

		field.postActionEvent();
		assertEquals(1, changes.size());
		assertEquals("", field.getText());
	}

	@Test
	public void theRemoveButtonDropsTheEntry()
	{
		editor.setEntries(Arrays.asList("Goblin", "Rat"));
		List<Component> buttons = new ArrayList<>();
		collect(editor, JButton.class, buttons);
		assertEquals(2, buttons.size());

		((JButton) buttons.get(0)).doClick();

		assertEquals(Collections.singletonList(Collections.singletonList("Rat")), changes);
		assertEquals(Collections.singletonList("Rat"), editor.getEntries());
	}

	@Test
	public void entriesFromTheSettingsPageDoNotEchoBack()
	{
		editor.setEntries(Arrays.asList("Goblin"));

		assertTrue(changes.isEmpty());
		assertEquals(Arrays.asList("Goblin"), editor.getEntries());
	}
}
