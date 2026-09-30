package io.myscape.plugin.collectionlog;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import org.junit.Test;

/**
 * Exercises {@link CollectionLogSchemaExporter} against a fully mocked
 * {@code Client} -- this environment has no live game client, so a
 * Mockito mock of the struct/enum compositions it reads (matching this
 * codebase's existing TextureExtractorTest approach for {@code
 * TextureProvider}) is the only way to exercise the walk logic at all.
 * The fake data below deliberately covers a two-tab, two-page-per-tab
 * shape and includes one item id from {@code
 * PAGE_STRUCT_ITEM_ID_TO_WIDGET_ITEM_ID}'s own remap table, so the test
 * fails if either the tab/page/item structure or the remap is wired up
 * wrong.
 */
public class CollectionLogSchemaExporterTest
{
	@Test
	public void walksTabsPagesAndItemsInOrder()
	{
		Client client = mock(Client.class);

		// Tab 471 ("Bosses") -> page enum 9001 -> page structs 501, 502.
		stubTab(client, 471, "Bosses", 9001, new int[] { 501, 502 });
		stubPage(client, 501, "Zulrah", 9101, new int[] { 12922, 12923 });
		stubPage(client, 502, "Vorkath", 9102, new int[] { 21295 });

		// Tab 472 ("Raids") -> page enum 9002 -> page struct 503, whose
		// item enum includes 13273 (Abyssal Sire's "Unsired"), which the
		// remap table maps to widget id 25624 -- the one case this test
		// asserts on directly, so a broken or missing remap fails loudly
		// rather than only showing up as a mismatched item id downstream.
		stubTab(client, 472, "Raids", 9002, new int[] { 503 });
		stubPage(client, 503, "Theatre of Blood", 9103, new int[] { 13273, 22326 });

		// Tabs 473-475 (Clues, Minigames, Other) resolve to a page enum
		// that itself doesn't exist -- getEnum returns null, same as a
		// real client would for an id that doesn't resolve -- to check
		// that one bad tab is skipped entirely (not included with zero
		// pages, just left out) rather than throwing.
		stubTabWithMissingPageEnum(client, 473, "Clues", 9003);
		stubTabWithMissingPageEnum(client, 474, "Minigames", 9004);
		stubTabWithMissingPageEnum(client, 475, "Other", 9005);

		CollectionLogSchema schema = CollectionLogSchemaExporter.export(client);

		List<CollectionLogSchema.Tab> tabs = schema.getTabs();
		assertEquals(2, tabs.size());

		CollectionLogSchema.Tab bosses = tabs.get(0);
		assertEquals("Bosses", bosses.getName());
		assertEquals(2, bosses.getPages().size());

		CollectionLogSchema.Page zulrah = bosses.getPages().get(0);
		assertEquals("Zulrah", zulrah.getName());
		assertEquals(List.of(12922, 12923), zulrah.getItemIds());

		CollectionLogSchema.Page vorkath = bosses.getPages().get(1);
		assertEquals("Vorkath", vorkath.getName());
		assertEquals(List.of(21295), vorkath.getItemIds());

		CollectionLogSchema.Tab raids = tabs.get(1);
		assertEquals("Raids", raids.getName());
		CollectionLogSchema.Page tob = raids.getPages().get(0);
		assertEquals("Theatre of Blood", tob.getName());
		// 13273 remapped to 25624 (Unsired); 22326 passed through as-is.
		assertEquals(List.of(25624, 22326), tob.getItemIds());
	}

	private static void stubTab(Client client, int tabStructId, String tabName, int pageEnumId, int[] pageStructIds)
	{
		StructComposition tabStruct = mock(StructComposition.class);
		when(tabStruct.getStringValue(682)).thenReturn(tabName);
		when(tabStruct.getIntValue(683)).thenReturn(pageEnumId);
		when(client.getStructComposition(tabStructId)).thenReturn(tabStruct);

		EnumComposition pageEnum = mock(EnumComposition.class);
		when(pageEnum.getIntVals()).thenReturn(pageStructIds);
		when(client.getEnum(pageEnumId)).thenReturn(pageEnum);
	}

	private static void stubTabWithMissingPageEnum(Client client, int tabStructId, String tabName, int pageEnumId)
	{
		StructComposition tabStruct = mock(StructComposition.class);
		when(tabStruct.getStringValue(682)).thenReturn(tabName);
		when(tabStruct.getIntValue(683)).thenReturn(pageEnumId);
		when(client.getStructComposition(tabStructId)).thenReturn(tabStruct);
		when(client.getEnum(pageEnumId)).thenReturn(null);
	}

	private static void stubPage(Client client, int pageStructId, String pageName, int itemsEnumId, int[] itemIds)
	{
		StructComposition pageStruct = mock(StructComposition.class);
		when(pageStruct.getStringValue(689)).thenReturn(pageName);
		when(pageStruct.getIntValue(690)).thenReturn(itemsEnumId);
		when(client.getStructComposition(pageStructId)).thenReturn(pageStruct);

		EnumComposition itemsEnum = mock(EnumComposition.class);
		when(itemsEnum.getIntVals()).thenReturn(itemIds);
		when(client.getEnum(itemsEnumId)).thenReturn(itemsEnum);
	}
}
