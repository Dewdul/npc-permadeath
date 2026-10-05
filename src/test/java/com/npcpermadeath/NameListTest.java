package com.npcpermadeath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class NameListTest
{
	@Test
	public void parseTrimsAndDropsBlanks()
	{
		assertEquals(Arrays.asList("Goblin", "Cow*"), NameList.parse(" Goblin ,, Cow* ,"));
		assertTrue(NameList.parse("").isEmpty());
		assertTrue(NameList.parse(null).isEmpty());
	}

	@Test
	public void csvRoundTrips()
	{
		List<String> names = Arrays.asList("Goblin", "Cow*", "Giant rat");
		assertEquals("Goblin, Cow*, Giant rat", NameList.toCsv(names));
		assertEquals(names, NameList.parse(NameList.toCsv(names)));
	}

	@Test
	public void addAppendsTheTypedText()
	{
		List<String> list = NameList.add(Collections.emptyList(), "  Cow* ");
		assertEquals(Collections.singletonList("Cow*"), list);
		assertEquals(Arrays.asList("Cow*", "Goblin"), NameList.add(list, "Goblin"));
	}

	@Test
	public void addIgnoresDuplicatesWithoutCase()
	{
		List<String> list = Arrays.asList("Goblin");
		assertSame(list, NameList.add(list, "goblin"));
		assertSame(list, NameList.add(list, " GOBLIN "));
	}

	@Test
	public void addIgnoresBlankText()
	{
		List<String> list = Arrays.asList("Goblin");
		assertSame(list, NameList.add(list, ""));
		assertSame(list, NameList.add(list, "   "));
		assertSame(list, NameList.add(list, " , "));
	}

	@Test
	public void addSplitsSeveralNamesAndSkipsRepeatsWithinThem()
	{
		List<String> list = NameList.add(Arrays.asList("Cow"), "Goblin, cow, Imp, imp");
		assertEquals(Arrays.asList("Cow", "Goblin", "Imp"), list);
	}

	@Test
	public void removeDropsTheEntryWithoutCase()
	{
		List<String> list = Arrays.asList("Goblin", "Cow*", "Imp");
		assertEquals(Arrays.asList("Goblin", "Imp"), NameList.remove(list, "cow*"));
	}

	@Test
	public void removeOfAnAbsentEntryChangesNothing()
	{
		List<String> list = Arrays.asList("Goblin");
		assertSame(list, NameList.remove(list, "Imp"));
	}

	@Test
	public void resultsCannotBeModified()
	{
		List<String> list = NameList.add(Collections.emptyList(), "Goblin");
		try
		{
			list.add("Imp");
		}
		catch (UnsupportedOperationException e)
		{
			assertFalse(NameList.contains(list, "Imp"));
			return;
		}
		throw new AssertionError("list should be read-only");
	}

	@Test
	public void containsIgnoresCaseAndPadding()
	{
		assertTrue(NameList.contains(Arrays.asList("Goblin"), " goblin "));
		assertFalse(NameList.contains(Arrays.asList("Goblin"), "Gob"));
	}
}
