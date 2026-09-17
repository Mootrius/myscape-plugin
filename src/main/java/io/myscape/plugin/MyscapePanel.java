package io.myscape.plugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The plugin's sidebar panel -- the RuneLite-side half of the two-step
 * account-linking handoff described in myscape's backend
 * (app/models.py / app/routers/linking.py): the player pastes the
 * short-lived code shown by the "Generate code" banner on myscape.io in
 * here, and this exchanges it for a long-lived API key that
 * MyscapeClient stores and reuses for every future request. Also shows
 * a small status line on the linked card confirming the bank/inventory/
 * equipment sync MyscapePlugin triggers in the background is actually
 * working (see syncInBackground below), plus a dedicated Status section
 * underneath listing every storage this plugin can sync (bank,
 * equipment, inventory, potion storage, seed vault, rune pouch, looting
 * bag) and whether each one is currently synced to the website (see
 * refreshSyncStatusInBackground/renderStatusRow below) -- the website's
 * own Possessions Monitoring feature reads exactly these storages, so
 * this is what tells a player whether it has everything it needs yet.
 *
 * Four states, one per CardLayout card: checking the stored key against
 * the server on startup, not logged into the game yet (nothing to link
 * with), ready to link, and linked.
 */
class MyscapePanel extends PluginPanel
{
	private static final String CARD_CHECKING = "checking";
	private static final String CARD_NOT_LOGGED_IN = "not-logged-in";
	private static final String CARD_LINK = "link";
	private static final String CARD_LINKED = "linked";

	// The storages the Status section lists, in the exact order asked
	// for -- matches POSSESSION_STORAGE_KEYS server-side (see
	// SyncStatusOut's docstring in the Mootrius/myscape repo's
	// app/schemas.py), which is also what GET /api/players/me/sync-status
	// (fetchSyncStatus below) reports back keyed by. More storages will
	// likely join this list later -- nothing here assumes exactly
	// twelve.
	private static final String[] STATUS_STORAGE_KEYS = {
		"bank", "equipment", "inventory", "potion_storage", "seed_vault", "rune_pouch", "looting_bag",
		"tackle_box", "poh", "forestry_kit", "master_scroll_book", "seed_box",
	};
	private static final Map<String, String> STATUS_STORAGE_LABELS = buildStatusStorageLabels();

	private static Map<String, String> buildStatusStorageLabels()
	{
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("bank", "Bank");
		labels.put("equipment", "Equipment");
		labels.put("inventory", "Inventory");
		labels.put("potion_storage", "Potion Storage");
		labels.put("seed_vault", "Seed Vault");
		labels.put("rune_pouch", "Rune Pouch");
		labels.put("looting_bag", "Looting Bag");
		labels.put("tackle_box", "Tackle Box");
		labels.put("poh", "PoH");
		labels.put("forestry_kit", "Forestry Kit");
		labels.put("master_scroll_book", "Master Scroll Book");
		labels.put("seed_box", "Seed Box");
		return Collections.unmodifiableMap(labels);
	}

	private final MyscapeClient client;
	private final MyscapePlugin plugin;

	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel(cards);

	private final JTextField codeField = new JTextField();
	private final JButton linkButton = new JButton("Link account");
	private final JLabel linkErrorLabel = new JLabel();

	private final JLabel linkedNameLabel = new JLabel();
	private final JButton unlinkButton = new JButton("Unlink");
	private final JLabel unlinkErrorLabel = new JLabel();
	private final JLabel syncStatusLabel = new JLabel();

	// The Status section's own per-storage row labels (see
	// buildLinkedCard) and the synced/not-synced state currently drawn
	// into each -- Boolean rather than boolean so "not yet checked"
	// (null, drawn as "Checking...") is a distinct third state from
	// "checked, and it's genuinely never synced" (false). Populated by
	// refreshSyncStatusInBackground below (the authoritative source,
	// GET /api/players/me/sync-status) and updated optimistically the
	// instant this session's own sync of that storage succeeds (see
	// syncCallback), so the row doesn't have to wait for the next poll
	// to reflect a sync that just visibly happened.
	private final Map<String, JLabel> statusRowLabels = new LinkedHashMap<>();
	private final Map<String, Boolean> storageSynced = new LinkedHashMap<>();

	private boolean showingLinked;

	// The most recent item count each container's sync has actually
	// confirmed with the server -- null until that container has synced
	// at least once since the panel last showed "Linked" (see
	// showLinked, which resets all three). Tracked individually rather
	// than just overwriting one shared label with whichever response
	// arrives last, since the three syncs kicked off together by one
	// debounce firing (see MyscapePlugin#scheduleSyncDebounced) resolve
	// over the network in whatever order they happen to -- without this,
	// the label would flicker between "Bank synced", "Inventory
	// synced", etc. depending on response ordering instead of settling
	// on one stable combined line.
	private Integer lastBankItemCount;
	private Integer lastInventoryItemCount;
	private Integer lastEquipmentItemCount;
	private Integer lastPotionStorageItemCount;
	private Integer lastRunePouchItemCount;
	private Integer lastLootingBagItemCount;
	private Integer lastSeedVaultItemCount;
	private Integer lastTackleBoxItemCount;
	private Integer lastPohItemCount;
	private Integer lastForestryKitItemCount;
	private Integer lastSeedBoxItemCount;
	private Integer lastMasterScrollBookItemCount;
	// Same deal, for skills/completed quests/diary tiers -- the size of
	// the most recently confirmed snapshot for each, same reasoning as
	// the five item-source counts above (and reset alongside them in
	// showLinked below).
	private Integer lastSkillCount;
	private Integer lastQuestCount;
	private Integer lastDiaryAreaCount;
	// Unlike the four counts above (the size of the most recent
	// snapshot), this is a running total of how many *new* loot events
	// this session has actually synced (see syncLootInBackground) --
	// there's no "current size" for an append-only log the way there is
	// for a container snapshot, so a cumulative count is the closest
	// equivalent proof-of-life. Still reset in showLinked below, same
	// as the other four, so it doesn't carry over a stale number from
	// before the panel last showed "Linked".
	private Integer lastLootEventCount;

	MyscapePanel(MyscapeClient client, MyscapePlugin plugin)
	{
		this.client = client;
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		cardPanel.add(buildCheckingCard(), CARD_CHECKING);
		cardPanel.add(buildNotLoggedInCard(), CARD_NOT_LOGGED_IN);
		cardPanel.add(buildLinkCard(), CARD_LINK);
		cardPanel.add(buildLinkedCard(), CARD_LINKED);
		add(cardPanel, BorderLayout.NORTH);

		cards.show(cardPanel, CARD_CHECKING);
	}

	private JPanel buildCheckingCard()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.add(new JLabel("Checking link status..."), BorderLayout.NORTH);
		return panel;
	}

	private JPanel buildNotLoggedInCard()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		JLabel label = new JLabel("<html>Please log in to start the plugin.</html>");
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(label);
		return panel;
	}

	private JPanel buildLinkCard()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

		JLabel intro = new JLabel("<html>Please paste in the code from myscape.io</html>");
		intro.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(intro);
		panel.add(Box.createRigidArea(new Dimension(0, 8)));

		codeField.setAlignmentX(Component.LEFT_ALIGNMENT);
		codeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, codeField.getPreferredSize().height));
		panel.add(codeField);
		panel.add(Box.createRigidArea(new Dimension(0, 8)));

		linkButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		linkButton.addActionListener(this::onLinkClicked);
		panel.add(linkButton);

		linkErrorLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		linkErrorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		linkErrorLabel.setVisible(false);
		panel.add(Box.createRigidArea(new Dimension(0, 6)));
		panel.add(linkErrorLabel);

		return panel;
	}

	private JPanel buildLinkedCard()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

		linkedNameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(linkedNameLabel);
		panel.add(Box.createRigidArea(new Dimension(0, 8)));

		unlinkButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		unlinkButton.addActionListener(this::onUnlinkClicked);
		panel.add(unlinkButton);

		unlinkErrorLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		unlinkErrorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		unlinkErrorLabel.setVisible(false);
		panel.add(Box.createRigidArea(new Dimension(0, 6)));
		panel.add(unlinkErrorLabel);

		syncStatusLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		syncStatusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		syncStatusLabel.setVisible(false);
		panel.add(Box.createRigidArea(new Dimension(0, 10)));
		panel.add(syncStatusLabel);

		panel.add(Box.createRigidArea(new Dimension(0, 14)));
		JLabel statusHeader = new JLabel("STATUS");
		statusHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(statusHeader);
		panel.add(Box.createRigidArea(new Dimension(0, 4)));

		// One row per storage -- whether it's synced to the website yet
		// (see STATUS_STORAGE_KEYS above and the class doc on
		// storageSynced) -- built once here and only ever re-rendered
		// in place afterward (see renderStatusRow), never rebuilt.
		for (String key : STATUS_STORAGE_KEYS)
		{
			JLabel row = new JLabel();
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			statusRowLabels.put(key, row);
			panel.add(row);
		}

		return panel;
	}

	/** Called once on plugin startup -- re-checks the stored API key
	 * against the server rather than trusting local state, since the key
	 * could have been revoked. */
	void refreshLinkStatus()
	{
		cards.show(cardPanel, CARD_CHECKING);
		client.refreshLinkStatus(new MyscapeClient.StatusCallback()
		{
			@Override
			public void onLinked(String runescapeName)
			{
				SwingUtilities.invokeLater(() -> showLinked(runescapeName));
			}

			@Override
			public void onNotLinked()
			{
				SwingUtilities.invokeLater(MyscapePanel.this::showLinkOrPrompt);
			}

			@Override
			public void onError(String message)
			{
				SwingUtilities.invokeLater(() -> {
					linkErrorLabel.setText("<html>" + escape(message) + "</html>");
					linkErrorLabel.setVisible(true);
					showLinkOrPrompt();
				});
			}
		});
	}

	/** Periodic background re-check -- see MyscapePlugin's scheduled poll.
	 * Unlike refreshLinkStatus() above, this never shows the "Checking..."
	 * card (it would otherwise flash briefly every poll interval even
	 * when nothing changed) and silently ignores a failed check instead
	 * of surfacing an error (a transient network blip during a poll the
	 * player didn't ask for shouldn't interrupt them -- refreshLinkStatus()'s
	 * explicit check at startup is what surfaces a real connectivity
	 * problem). It exists specifically to catch an unlink that happened
	 * somewhere this client has no direct line to -- the website's own
	 * "Unlink" button chief among them, since it only needs a browser
	 * session and has no way to tell this client about it directly. */
	void recheckLinkStatusInBackground()
	{
		client.refreshLinkStatus(new MyscapeClient.StatusCallback()
		{
			@Override
			public void onLinked(String runescapeName)
			{
				SwingUtilities.invokeLater(() -> showLinked(runescapeName));
			}

			@Override
			public void onNotLinked()
			{
				SwingUtilities.invokeLater(() -> {
					if (showingLinked)
					{
						showLinkOrPrompt();
					}
				});
			}

			@Override
			public void onError(String message)
			{
				// Ignored -- see the method doc above.
			}
		});
	}

	/** Called by the plugin whenever the game's login state changes (or
	 * the account hash it depends on first becomes available -- see
	 * MyscapePlugin#onGameTick), so the "log in first" card swaps for
	 * the actual link form without the player having to reopen the
	 * panel. Always marshals onto the EDT itself, since -- unlike every
	 * other panel update here -- both of its callers run on the RuneLite
	 * client thread, not the EDT, and Swing components may only be
	 * touched from the EDT. */
	void onGameStateChanged()
	{
		SwingUtilities.invokeLater(() -> {
			if (!showingLinked)
			{
				showLinkOrPrompt();
			}
		});
	}

	private void showLinkOrPrompt()
	{
		showingLinked = false;
		cards.show(cardPanel, plugin.getAccountHash() == null ? CARD_NOT_LOGGED_IN : CARD_LINK);
	}

	private void showLinked(String runescapeName)
	{
		showingLinked = true;
		unlinkErrorLabel.setVisible(false);
		syncStatusLabel.setVisible(false);
		// A fresh "Linked" screen (first link, or a re-check after
		// re-opening the panel) shouldn't keep showing counts left over
		// from whatever was last synced before -- they're stale until
		// proven otherwise by an actual new sync.
		lastBankItemCount = null;
		lastInventoryItemCount = null;
		lastEquipmentItemCount = null;
		lastPotionStorageItemCount = null;
		lastRunePouchItemCount = null;
		lastLootingBagItemCount = null;
		lastSeedVaultItemCount = null;
		lastTackleBoxItemCount = null;
		lastPohItemCount = null;
		lastForestryKitItemCount = null;
		lastSeedBoxItemCount = null;
		lastMasterScrollBookItemCount = null;
		lastLootEventCount = null;
		lastSkillCount = null;
		lastQuestCount = null;
		lastDiaryAreaCount = null;
		String name = runescapeName != null ? runescapeName : plugin.getRuneScapeName();
		linkedNameLabel.setText("<html>Linked as <b>" + escape(name != null ? name : "your game account") + "</b></html>");
		cards.show(cardPanel, CARD_LINKED);

		// A fresh "Linked" screen means whatever the Status section last
		// showed is stale too -- reset every row to "Checking..." and
		// kick off an authoritative refresh rather than keep showing
		// state left over from before (or from a different account).
		storageSynced.clear();
		for (String key : STATUS_STORAGE_KEYS)
		{
			renderStatusRow(key);
		}
		refreshSyncStatusInBackground();
	}

	private void onLinkClicked(ActionEvent event)
	{
		String code = codeField.getText().trim();
		linkErrorLabel.setVisible(false);

		Long accountHash = plugin.getAccountHash();
		if (accountHash == null)
		{
			linkErrorLabel.setText("Log into the game first.");
			linkErrorLabel.setVisible(true);
			return;
		}
		if (code.isEmpty())
		{
			linkErrorLabel.setText("Paste the code from myscape.io first.");
			linkErrorLabel.setVisible(true);
			return;
		}

		linkButton.setEnabled(false);
		String runescapeName = plugin.getRuneScapeName();
		client.exchangeLinkCode(code, accountHash, runescapeName, new MyscapeClient.LinkCallback()
		{
			@Override
			public void onSuccess(String linkedName)
			{
				SwingUtilities.invokeLater(() -> {
					linkButton.setEnabled(true);
					codeField.setText("");
					showLinked(linkedName);
				});
			}

			@Override
			public void onFailure(String message)
			{
				SwingUtilities.invokeLater(() -> {
					linkButton.setEnabled(true);
					linkErrorLabel.setText("<html>" + escape(message) + "</html>");
					linkErrorLabel.setVisible(true);
				});
			}
		});
	}

	private void onUnlinkClicked(ActionEvent event)
	{
		unlinkErrorLabel.setVisible(false);
		unlinkButton.setEnabled(false);
		client.unlink(new MyscapeClient.UnlinkCallback()
		{
			@Override
			public void onSuccess()
			{
				SwingUtilities.invokeLater(() -> {
					unlinkButton.setEnabled(true);
					showLinkOrPrompt();
				});
			}

			@Override
			public void onFailure(String message)
			{
				SwingUtilities.invokeLater(() -> {
					unlinkButton.setEnabled(true);
					unlinkErrorLabel.setText("<html>" + escape(message) + "</html>");
					unlinkErrorLabel.setVisible(true);
				});
			}
		});
	}

	/** Called by the plugin (see MyscapePlugin#scheduleSyncDebounced),
	 * off the EDT, once bank/inventory/equipment/potion storage/rune
	 * pouch/looting bag/seed vault/tackle box/PoH/Forestry Kit/Seed Box/
	 * skills/quests/diaries have settled after a change --
	 * syncs whichever of these
	 * have actually been observed this session (any
	 * argument may be null; see the field docs on MyscapePlugin's
	 * latest*Items/latestSkillItems/latestCompletedQuestNames/
	 * latestDiaryTierItems fields) and, on each success, updates the
	 * small status line on
	 * the linked card so there's some visible confirmation this is
	 * actually working, the same way linking and unlinking already give
	 * feedback. A failure is shown the same quiet way the background
	 * link-status poll handles one (see
	 * MyscapePanel#recheckLinkStatusInBackground) -- this runs
	 * unprompted in the background, not from a button click, so it
	 * shouldn't interrupt the player the way a failed explicit action
	 * would; the next change just tries again. */
	void syncInBackground(
		List<MyscapeClient.BankItem> bankItems,
		List<MyscapeClient.InventoryItem> inventoryItems,
		List<MyscapeClient.EquipmentItem> equipmentItems,
		List<MyscapeClient.PotionStorageItem> potionStorageItems,
		List<MyscapeClient.RunePouchItem> runePouchItems,
		List<MyscapeClient.LootingBagItem> lootingBagItems,
		List<MyscapeClient.SeedVaultItem> seedVaultItems,
		List<MyscapeClient.TackleBoxItem> tackleBoxItems,
		List<MyscapeClient.PohItem> pohItems,
		List<MyscapeClient.ForestryKitItem> forestryKitItems,
		List<MyscapeClient.SeedBoxItem> seedBoxItems,
		List<MyscapeClient.MasterScrollBookItem> masterScrollBookItems,
		List<MyscapeClient.SkillItem> skillItems,
		List<String> completedQuestNames,
		List<MyscapeClient.DiaryTierItem> diaryTierItems)
	{
		if (bankItems != null)
		{
			client.syncBank(bankItems, syncCallback("bank", count -> lastBankItemCount = count));
		}
		if (inventoryItems != null)
		{
			client.syncInventory(inventoryItems, syncCallback("inventory", count -> lastInventoryItemCount = count));
		}
		if (equipmentItems != null)
		{
			client.syncEquipment(equipmentItems, syncCallback("equipment", count -> lastEquipmentItemCount = count));
		}
		if (potionStorageItems != null)
		{
			client.syncPotionStorage(potionStorageItems, syncCallback("potion_storage", count -> lastPotionStorageItemCount = count));
		}
		if (runePouchItems != null)
		{
			client.syncRunePouch(runePouchItems, syncCallback("rune_pouch", count -> lastRunePouchItemCount = count));
		}
		if (lootingBagItems != null)
		{
			client.syncLootingBag(lootingBagItems, syncCallback("looting_bag", count -> lastLootingBagItemCount = count));
		}
		if (seedVaultItems != null)
		{
			client.syncSeedVault(seedVaultItems, syncCallback("seed_vault", count -> lastSeedVaultItemCount = count));
		}
		if (tackleBoxItems != null)
		{
			client.syncTackleBox(tackleBoxItems, syncCallback("tackle_box", count -> lastTackleBoxItemCount = count));
		}
		if (pohItems != null)
		{
			client.syncPoh(pohItems, syncCallback("poh", count -> lastPohItemCount = count));
		}
		if (forestryKitItems != null)
		{
			client.syncForestryKit(forestryKitItems, syncCallback("forestry_kit", count -> lastForestryKitItemCount = count));
		}
		if (seedBoxItems != null)
		{
			client.syncSeedBox(seedBoxItems, syncCallback("seed_box", count -> lastSeedBoxItemCount = count));
		}
		if (masterScrollBookItems != null)
		{
			client.syncMasterScrollBook(masterScrollBookItems, syncCallback("master_scroll_book", count -> lastMasterScrollBookItemCount = count));
		}
		if (skillItems != null)
		{
			client.syncSkills(skillItems, syncCallback(count -> lastSkillCount = count));
		}
		if (completedQuestNames != null)
		{
			client.syncQuests(completedQuestNames, syncCallback(count -> lastQuestCount = count));
		}
		if (diaryTierItems != null)
		{
			client.syncDiaries(diaryTierItems, syncCallback(count -> lastDiaryAreaCount = count));
		}
	}

	/** Called by the plugin (see MyscapePlugin#onLootReceived) the
	 * moment a new loot event is captured -- unlike syncInBackground
	 * above, this fires per-event immediately rather than going through
	 * the shared debounce, since a loot event is a discrete thing that
	 * already happened, not a "current state" that benefits from
	 * collapsing a burst of changes into one send (see LootEvent's
	 * docstring in the Mootrius/myscape repo's app/models.py). */
	void syncLootInBackground(MyscapeClient.LootEvent event)
	{
		client.syncLootEvent(event, new MyscapeClient.SyncCallback()
		{
			@Override
			public void onSuccess(int addedCount)
			{
				if (addedCount <= 0)
				{
					// Already on file -- a retried/duplicate send, not a
					// new event (see LootEvent's docstring) -- nothing new
					// to reflect in the status line.
					return;
				}
				SwingUtilities.invokeLater(() -> {
					lastLootEventCount = (lastLootEventCount == null ? 0 : lastLootEventCount) + addedCount;
					updateSyncStatusLabel();
				});
			}

			@Override
			public void onFailure(String message)
			{
				// Ignored -- see syncCallback's method doc below; a
				// dropped loot sync just means this one event never made
				// it, and the next kill tries fresh rather than this
				// needing any retry logic of its own.
			}
		});
	}

	/** Builds a SyncCallback that records its result into whichever of
	 * the lastXItemCount fields `recordCount` closes over, then
	 * refreshes the combined status line -- shared by every call in
	 * syncInBackground above rather than one near-identical anonymous
	 * class per source. Used directly (no storage key) by
	 * skills/quests/diaries, which aren't part of the Status section;
	 * see the overload below for the six that are. */
	private MyscapeClient.SyncCallback syncCallback(IntConsumer recordCount)
	{
		return new MyscapeClient.SyncCallback()
		{
			@Override
			public void onSuccess(int itemCount)
			{
				SwingUtilities.invokeLater(() -> {
					recordCount.accept(itemCount);
					updateSyncStatusLabel();
				});
			}

			@Override
			public void onFailure(String message)
			{
				// Ignored -- see the method doc above.
			}
		};
	}

	/** Same as the plain syncCallback above, plus marking storageKey
	 * synced in the Status section the instant this sync succeeds --
	 * used by the six item-source syncs in syncInBackground (bank,
	 * inventory, equipment, potion storage, rune pouch, looting bag),
	 * each of which corresponds to one of STATUS_STORAGE_KEYS. This is
	 * deliberately optimistic (set the moment the response comes back,
	 * not on the next poll of GET /api/players/me/sync-status) so the
	 * row doesn't lag a successful sync the player can already see
	 * reflected in the count above it -- refreshSyncStatusInBackground
	 * below will confirm the same thing shortly anyway. */
	private MyscapeClient.SyncCallback syncCallback(String storageKey, IntConsumer recordCount)
	{
		return new MyscapeClient.SyncCallback()
		{
			@Override
			public void onSuccess(int itemCount)
			{
				SwingUtilities.invokeLater(() -> {
					recordCount.accept(itemCount);
					updateSyncStatusLabel();
					storageSynced.put(storageKey, Boolean.TRUE);
					renderStatusRow(storageKey);
				});
			}

			@Override
			public void onFailure(String message)
			{
				// Ignored -- see the method doc above.
			}
		};
	}

	/** Polls GET /api/players/me/sync-status (see MyscapeClient#fetchSyncStatus)
	 * and refreshes every row in the Status section from the
	 * authoritative server answer -- called once as soon as the panel
	 * shows "Linked" (see showLinked) and again on MyscapePlugin's own
	 * periodic background poll, the same cadence
	 * recheckLinkStatusInBackground already runs on. A failure (offline,
	 * not actually linked, ...) is ignored the same quiet way that
	 * method's own background checks are -- this runs unprompted, not
	 * from something the player clicked, so nothing here should
	 * interrupt them; whatever the section last showed just stays put
	 * until the next successful poll. */
	void refreshSyncStatusInBackground()
	{
		if (!showingLinked)
		{
			return;
		}
		client.fetchSyncStatus(new MyscapeClient.SyncStatusCallback()
		{
			@Override
			public void onSuccess(Map<String, String> storages)
			{
				SwingUtilities.invokeLater(() -> {
					for (String key : STATUS_STORAGE_KEYS)
					{
						storageSynced.put(key, storages.get(key) != null);
						renderStatusRow(key);
					}
				});
			}

			@Override
			public void onFailure(String message)
			{
				// Ignored -- see the method doc above.
			}
		});
	}

	/** Redraws one Status section row from storageSynced's current view
	 * of that key -- the storage's name in the panel's normal text
	 * color, followed by "Synced" in the site's own completed-progress
	 * green once storageSynced.get(key) is true, "Not synced yet" in a
	 * neutral gray once it's known false (nothing alarming about that --
	 * plenty of these, Seed Vault especially right now, simply haven't
	 * been wired up to sync at all yet), or "Checking..." for as long as
	 * it's still null (never fetched this "Linked" session -- see
	 * showLinked). Deliberately plain text with no dot/bullet glyph in
	 * front of it -- an earlier version used a Unicode circle character
	 * for that, which showed up as a "tofu box" (garbled placeholder
	 * glyph) for at least one player because RuneLite's own bundled
	 * sidebar font doesn't cover it; color alone carries the same
	 * information without depending on font glyph coverage at all. */
	private void renderStatusRow(String key)
	{
		JLabel row = statusRowLabels.get(key);
		if (row == null)
		{
			return;
		}
		String label = STATUS_STORAGE_LABELS.getOrDefault(key, key);
		Boolean synced = storageSynced.get(key);
		if (synced == null)
		{
			row.setText("<html>" + escape(label) + ": <span style='color:" + toHex(ColorScheme.MEDIUM_GRAY_COLOR)
				+ ";'><i>Checking...</i></span></html>");
			return;
		}
		if (synced)
		{
			row.setText("<html>" + escape(label) + ": <span style='color:" + toHex(ColorScheme.PROGRESS_COMPLETE_COLOR)
				+ ";'>Synced</span></html>");
		}
		else
		{
			row.setText("<html>" + escape(label) + ": <span style='color:" + toHex(ColorScheme.MEDIUM_GRAY_COLOR)
				+ ";'>Not synced yet</span></html>");
		}
	}

	private static String toHex(Color color)
	{
		return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}

	/** Rebuilds the combined status line from whichever of
	 * bank/inventory/equipment/potion storage have synced at least once since the panel
	 * last showed "Linked" -- called after each individual sync
	 * completes, so the line only ever grows more complete rather than
	 * flickering between single-container messages (see the field doc
	 * on lastBankItemCount and friends). */
	private void updateSyncStatusLabel()
	{
		if (!showingLinked)
		{
			return;
		}
		List<String> parts = new ArrayList<>();
		if (lastBankItemCount != null)
		{
			parts.add(lastBankItemCount + " bank");
		}
		if (lastInventoryItemCount != null)
		{
			parts.add(lastInventoryItemCount + " inventory");
		}
		if (lastEquipmentItemCount != null)
		{
			parts.add(lastEquipmentItemCount + " equipment");
		}
		if (lastPotionStorageItemCount != null)
		{
			parts.add(lastPotionStorageItemCount + " potions");
		}
		if (lastRunePouchItemCount != null)
		{
			parts.add(lastRunePouchItemCount + " rune pouch");
		}
		if (lastLootingBagItemCount != null)
		{
			parts.add(lastLootingBagItemCount + " looting bag");
		}
		if (lastSeedVaultItemCount != null)
		{
			parts.add(lastSeedVaultItemCount + " seed vault");
		}
		if (lastTackleBoxItemCount != null)
		{
			parts.add(lastTackleBoxItemCount + " tackle box");
		}
		if (lastPohItemCount != null)
		{
			parts.add(lastPohItemCount + " poh");
		}
		if (lastForestryKitItemCount != null)
		{
			parts.add(lastForestryKitItemCount + " forestry kit");
		}
		if (lastSeedBoxItemCount != null)
		{
			parts.add(lastSeedBoxItemCount + " seed box");
		}
		if (lastMasterScrollBookItemCount != null)
		{
			parts.add(lastMasterScrollBookItemCount + " master scroll book");
		}
		if (lastSkillCount != null)
		{
			parts.add(lastSkillCount + " skills");
		}
		if (lastQuestCount != null)
		{
			parts.add(lastQuestCount + " quests");
		}
		if (lastDiaryAreaCount != null)
		{
			parts.add(lastDiaryAreaCount + " diary areas");
		}
		if (lastLootEventCount != null)
		{
			parts.add(lastLootEventCount + " loot events");
		}
		if (parts.isEmpty())
		{
			return;
		}
		syncStatusLabel.setText("<html>Synced: " + String.join(", ", parts) + "</html>");
		syncStatusLabel.setVisible(true);
	}

	private static String escape(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
