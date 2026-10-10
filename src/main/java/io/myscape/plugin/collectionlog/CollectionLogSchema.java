package io.myscape.plugin.collectionlog;

import java.util.Collections;
import java.util.List;

/**
 * The collection log's tab/page/item structure, without any obtained or quantity state.
 * It is identical for every player on a given game build, so it is exported once from cache
 * data. See {@link CollectionLogSchemaExporter}.
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

	/** A top-level tab (Bosses, Raids, Clues, Minigames, Other). */
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

	/** A page within a tab (e.g. "Zulrah"). itemIds lists every item the page can show, in in-game order. */
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
