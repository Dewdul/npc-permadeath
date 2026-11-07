package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.Test;

/**
 * Drives the search box and the panel the way the keyboard does, without
 * showing them. RuneLite's search field may only be used on the Swing thread,
 * as in the client, so each test runs there.
 */
public class NpcSearchBoxTest
{
	private final List<String> searches = new ArrayList<>();
	private final NpcNameIndex index = new NpcNameIndex();

	public NpcSearchBoxTest()
	{
		index.setSeedNames(Arrays.asList("Goblin", "Skeleton", "Skeleton mage"));
	}

	private static void onEdt(Runnable test) throws Exception
	{
		Throwable[] failure = new Throwable[1];
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				test.run();
			}
			catch (Throwable t)
			{
				failure[0] = t;
			}
		});
		if (failure[0] instanceof Error)
		{
			throw (Error) failure[0];
		}
		if (failure[0] != null)
		{
			throw new AssertionError(failure[0]);
		}
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

	/** An unfocused field drops dispatched key events, so the listeners are called directly. */
	private static void press(JTextField field, int key)
	{
		KeyEvent event = new KeyEvent(field, KeyEvent.KEY_PRESSED, 0, 0, key, KeyEvent.CHAR_UNDEFINED);
		for (java.awt.event.KeyListener listener : field.getKeyListeners())
		{
			listener.keyPressed(event);
		}
	}

	@Test
	public void pickingASuggestionSearchesForIt() throws Exception
	{
		onEdt(() ->
		{
			NpcSearchBox box = new NpcSearchBox(index::suggest, searches::add);
			JTextField field = find(box, JTextField.class);
			JList<?> suggestions = find(box, JList.class);

			field.setText("skel");
			assertTrue(suggestions.isVisible());
			assertEquals(2, suggestions.getModel().getSize());
			press(field, KeyEvent.VK_DOWN);
			press(field, KeyEvent.VK_DOWN);
			field.postActionEvent();

			assertEquals(Collections.singletonList("Skeleton mage"), searches);
			assertEquals("Skeleton mage", field.getText());
			assertEquals("Skeleton mage", box.getActive());
			assertFalse(suggestions.isVisible());
		});
	}

	@Test
	public void enterWithoutASuggestionSearchesTheText() throws Exception
	{
		onEdt(() ->
		{
			NpcSearchBox box = new NpcSearchBox(index::suggest, searches::add);
			JTextField field = find(box, JTextField.class);

			field.setText("Skeleton*");
			field.postActionEvent();

			assertEquals(Collections.singletonList("Skeleton*"), searches);
		});
	}

	@Test
	public void emptyingTheFieldEndsTheSearch() throws Exception
	{
		onEdt(() ->
		{
			NpcSearchBox box = new NpcSearchBox(index::suggest, searches::add);
			JTextField field = find(box, JTextField.class);
			field.setText("Goblin");
			field.postActionEvent();

			field.setText("");

			assertEquals(Arrays.asList("Goblin", null), searches);
			assertNull(box.getActive());
		});
	}

	@Test
	public void panelShowsResultsThenTheKillListAgain() throws Exception
	{
		onEdt(() ->
		{
			PermadeathPanel panel = new PermadeathPanel(a -> { }, () -> { }, r -> { }, index::suggest, (k, v) -> { }, q -> { });
			int edgeville = ((3072 >> 6) << 8) | (9856 >> 6);
			List<PermadeathPanel.Row> rows = Arrays.asList(
				new PermadeathPanel.Row(new AreaKey("Skeleton", edgeville), "Edgeville Dungeon (3072, 9856)", 3, 8, 3),
				new PermadeathPanel.Row(new AreaKey("Skeleton", 12850), "Lumbridge (3200, 3200)", 0, 2, 0));

			List<String> names = Collections.singletonList("Skeleton");
			panel.showSearch(new PermadeathPanel.SearchResult("Skeleton", names, false,
				NpcSearch.totals(names, rows), NpcSearch.chunks(rows), rows, false));

			assertEquals("Skeleton", panel.getShownQuery());
			String text = allText(panel);
			assertTrue(text, text.contains("Skeleton in 2 chunks"));
			// the total comes first, then the chunks
			assertTrue(text, text.contains("Total\n3 / 10\n"));
			assertTrue(text, text.indexOf("Total") < text.indexOf("Edgeville Dungeon"));
			assertTrue(text, text.indexOf("Edgeville Dungeon") < text.indexOf("Lumbridge (3200, 3200)"));

			panel.update(Collections.emptyList());

			assertNull(panel.getShownQuery());
			assertTrue(allText(panel).contains("Nothing slain yet."));
		});
	}

	private static String allText(Container root)
	{
		StringBuilder out = new StringBuilder();
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel)
			{
				out.append(((JLabel) c).getText()).append('\n');
			}
			if (c instanceof Container)
			{
				out.append(allText((Container) c));
			}
		}
		return out.toString();
	}
}
