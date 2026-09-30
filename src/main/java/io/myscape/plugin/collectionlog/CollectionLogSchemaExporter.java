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
 * Reads the collection log's full tab/page/item structure straight out
 * of the client's own cache data -- struct and enum compositions the
 * game ships with -- rather than needing it hand-compiled from the
 * wiki or copied from another project's own static file. The client
 * already knows every tab, every page within each tab, and every item
 * id a page can ever show; this just walks that data the same way the
 * game's own collection log interface does to build itself.
 *
 * <p>The five top-level tab struct ids, and the four struct param ids
 * used to pull a tab's name/page-enum and a page's name/item-enum off
 * of them, are not documented anywhere -- they were worked out by the
 * (BSD-2-licensed, open source) RuneLite "Collection Log" plugin
 * (github.com/evansloan/collection-log, see its
 * CollectionLogManager#initCollectionLog), which reads this exact same
 * cache data for its own local save file. The ids and the read order
 * below are taken from that plugin, credited here rather than
 * silently reproduced -- there is no independent way to derive them
 * that doesn't amount to the same reverse-engineering that project
 * already did. Everything else here (the {@link CollectionLogSchema}
 * shape, how it gets exported/used) is our own.
 *
 * <p>This intentionally stops at structure: which items exist on each
 * page, not which of them any particular player has obtained. Obtained
 * state isn't cache data -- it only becomes readable by actually
 * opening the collection log widget in-game (see RuneProfile's
 * CollectionLogWidgetSubscriber, credited separately where that gets
 * built) -- so it can't be read here alongside the structure, and
 * doesn't need to be: the structure is identical for every player on a
 * given game build, so it only ever needs exporting once and can then
 * live as a static resource on the myscape backend, rather than being
 * re-derived (or re-sent) on every player's own sync.
 */
public final class CollectionLogSchemaExporter
{
	/** The five top-level collection log tabs, in the order the
	 * in-game interface itself shows them along the top. */
	private static final List<Integer> TAB_STRUCT_IDS = Collections.unmodifiableList(
		java.util.Arrays.asList(471, 472, 473, 474, 475) // Bosses, Raids, Clues, Minigames, Other
	);

	private static final int TAB_NAME_PARAM_ID = 682;
	private static final int TAB_PAGE_ENUM_PARAM_ID = 683;
	private static final int PAGE_NAME_PARAM_ID = 689;
	private static final int PAGE_ITEMS_ENUM_PARAM_ID = 690;

	/** A dozen items where the id used inside a page's own item enum
	 * (the "page struct" id) differs from the id the collection log
	 * widget actually displays and the player actually receives (the
	 * "item widget" id) -- both are real, valid item ids, they just
	 * don't agree with each other for these specific items (mostly
	 * newer "recolored"/variant drops added after the original id was
	 * already in the enum). Left unmapped, the same physical drop
	 * would show up under two different ids depending on whether it
	 * was read from here or from a live obtained-item capture, and
	 * silently fail to match. Values taken from the same
	 * evansloan/collection-log project credited above (its own
	 * ITEM_ID_MAP), which found these by comparing the two sources
	 * directly against real accounts. */
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

	/** Walks the five tab structs, and everything they point to, into
	 * a full {@link CollectionLogSchema}. Reads cache data only --
	 * struct/enum compositions -- so unlike a capture of a player's
	 * actual obtained items, this does not need the collection log
	 * widget to be open, or even the local player to exist yet; it
	 * only needs a live {@code Client} with the game's cache loaded,
	 * which is true from login onward. Still only ever meant to be
	 * called from the client thread, same as every other cache-data
	 * read in this plugin (see MyscapePlugin#captureCharacterModel's
	 * own doc for why that split matters generally). */
	public static CollectionLogSchema export(Client client)
	{
		List<CollectionLogSchema.Tab> tabs = new ArrayList<>();

		for (int tabStructId : TAB_STRUCT_IDS)
		{
			StructComposition tabStruct = client.getStructComposition(tabStructId);
			String tabName = tabStruct.getStringValue(TAB_NAME_PARAM_ID);
			int tabPageEnumId = tabStruct.getIntValue(TAB_PAGE_ENUM_PARAM_ID);
			EnumComposition tabPageEnum = client.getEnum(tabPageEnumId);
			// client.getEnum can return null for an id that doesn't
			// resolve (see this same defensive check already used
			// elsewhere in this plugin, e.g. readPotionStorageItems) --
			// skip rather than NPE, so one unexpected tab doesn't take
			// the whole export down with it.
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
