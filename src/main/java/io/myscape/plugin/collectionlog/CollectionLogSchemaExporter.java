package io.myscape.plugin.collectionlog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;

/**
 * Reads the collection log's tab/page/item structure from the client's cache data (struct and
 * enum compositions). Structure only: obtained state is not cache data and is captured
 * separately by {@link CollectionLogItemCapture}.
 *
 * <p>Credit: the tab struct ids and the struct param ids below, and the read order, come from the
 * BSD-2-Clause "Collection Log" plugin (github.com/evansloan/collection-log,
 * CollectionLogManager#initCollectionLog).
 */
public final class CollectionLogSchemaExporter
{
	/** Top-level tab structs, in in-game order. */
	private static final List<Integer> TAB_STRUCT_IDS = Collections.unmodifiableList(
		java.util.Arrays.asList(471, 472, 473, 474, 475) // Bosses, Raids, Clues, Minigames, Other
	);

	private static final int TAB_NAME_PARAM_ID = 682;
	private static final int TAB_PAGE_ENUM_PARAM_ID = 683;
	private static final int PAGE_NAME_PARAM_ID = 689;
	private static final int PAGE_ITEMS_ENUM_PARAM_ID = 690;

	/**
	 * Items whose id in a page's item enum differs from the id the collection log widget
	 * displays. Without this remap the same drop would appear under two ids and fail to match
	 * a live capture. Values are from evansloan/collection-log (ITEM_ID_MAP).
	 */
	private static final Map<Integer, Integer> PAGE_STRUCT_ITEM_ID_TO_WIDGET_ITEM_ID = buildItemIdRemap();

	private static Map<Integer, Integer> buildItemIdRemap()
	{
		Map<Integer, Integer> remap = new HashMap<>();
		remap.put(10859, 25617); // Tea flask
		remap.put(10877, 25618); // Red satchel
		remap.put(10878, 25619); // Green satchel
		remap.put(10879, 25620); // Red satchel
		remap.put(10880, 25621); // Black satchel
		remap.put(10881, 25622); // Gold satchel
		remap.put(10882, 25623); // Rune satchel
		remap.put(13273, 25624); // Unsired
		remap.put(12019, 25627); // Coal bag
		remap.put(12020, 25628); // Gem bag
		remap.put(24882, 25629); // Plank sack
		remap.put(12854, 25630); // Flamtaer bag
		return Collections.unmodifiableMap(remap);
	}

	private CollectionLogSchemaExporter()
	{
	}

	/**
	 * Walks the tab structs and everything they reference into a {@link CollectionLogSchema}.
	 * Needs only a loaded cache, not an open widget. Call from the client thread.
	 */
	public static CollectionLogSchema export(Client client)
	{
		List<CollectionLogSchema.Tab> tabs = new ArrayList<>();

		for (int tabStructId : TAB_STRUCT_IDS)
		{
			StructComposition tabStruct = client.getStructComposition(tabStructId);
			String tabName = tabStruct.getStringValue(TAB_NAME_PARAM_ID);
			int tabPageEnumId = tabStruct.getIntValue(TAB_PAGE_ENUM_PARAM_ID);
			EnumComposition tabPageEnum = client.getEnum(tabPageEnumId);
			// getEnum can return null for an unresolved id; skip the tab rather than fail the export.
			if (tabPageEnum == null)
			{
				continue;
			}

			List<CollectionLogSchema.Page> pages = new ArrayList<>();
			for (int pageStructId : tabPageEnum.getIntVals())
			{
				pages.add(readPage(client, pageStructId));
			}

			tabs.add(new CollectionLogSchema.Tab(tabName, pages));
		}

		return new CollectionLogSchema(tabs);
	}

	private static CollectionLogSchema.Page readPage(Client client, int pageStructId)
	{
		StructComposition pageStruct = client.getStructComposition(pageStructId);
		String pageName = pageStruct.getStringValue(PAGE_NAME_PARAM_ID);
		int pageItemsEnumId = pageStruct.getIntValue(PAGE_ITEMS_ENUM_PARAM_ID);
		EnumComposition pageItemsEnum = client.getEnum(pageItemsEnumId);

		List<Integer> itemIds = new ArrayList<>();
		if (pageItemsEnum == null)
		{
			return new CollectionLogSchema.Page(pageName, itemIds);
		}

		for (int rawItemId : pageItemsEnum.getIntVals())
		{
			Integer remapped = PAGE_STRUCT_ITEM_ID_TO_WIDGET_ITEM_ID.get(rawItemId);
			itemIds.add(remapped != null ? remapped : rawItemId);
		}

		return new CollectionLogSchema.Page(pageName, itemIds);
	}
}
