package io.myscape.plugin.collectionlog;

import java.util.Collections;
import java.util.List;

/**
 * The collection log's own tab/page/item structure -- "Bosses" contains
 * a page per boss, "Zulrah" contains a fixed list of item ids, and so
 * on -- with no obtained/quantity state of any kind. See {@link
 * CollectionLogSchemaExporter} for how this gets read out of the live
 * client, and that class's own top-of-file doc for why this is read
 * from the client at all rather than hand-compiled from the wiki.
 *
 * <p>This is a one-time export target, not a shape synced on every
 * player's regular update: the whole point of reading it from cache
 * data (rather than scraping the collection log widget, which is how
 * per-item obtained/quantity state has to be captured) is that it is
 * the same for every player on a given game build, so it only needs
 * capturing once and can then live as a static resource on the myscape
 * backend -- see CollectionLogSchemaExporter's own doc for the export
 * flow this feeds.
 */
public final class CollectionLogSchema
{
	private final List<Tab> tabs;

	public CollectionLogSchema(List<Tab> tabs)
	{
		this.tabs = Collections.unmodifiableList(tabs);
	}

	public List<Tab> getTabs()
	{
		return tabs;
	}

	/** One of the five top-level tabs (Bosses, Raids, Clues, Minigames,
	 * Other) shown along the top of the in-game collection log. */
	public static final class Tab
	{
		private final String name;
		private final List<Page> pages;

		public Tab(String name, List<Page> pages)
		{
			this.name = name;
			this.pages = Collections.unmodifiableList(pages);
		}

		public String getName()
		{
			return name;
		}

		public List<Page> getPages()
		{
			return pages;
		}
	}

	/** One entry in a tab's own left-hand page list (e.g. "Zulrah"
	 * within the "Bosses" tab) -- itemIds is every item that page can
	 * ever show, obtained or not, in the same order the in-game log
	 * itself lists them. */
	public static final class Page
	{
		private final String name;
		private final List<Integer> itemIds;

		public Page(String name, List<Integer> itemIds)
		{
			this.name = name;
			this.itemIds = Collections.unmodifiableList(itemIds);
		}

		public String getName()
		{
			return name;
		}

		public List<Integer> getItemIds()
		{
			return itemIds;
		}
	}
}
