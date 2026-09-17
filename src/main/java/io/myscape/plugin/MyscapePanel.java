package io.myscape.plugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The plugin's sidebar panel -- the RuneLite-side half of the two-step
 * account-linking handoff described in myscape's backend
 * (app/models.py / app/routers/linking.py): the player pastes the
 * short-lived code shown by the "Generate code" banner on myscape.io in
 * here, and this exchanges it for a long-lived API key that
 * MyscapeClient stores and reuses for every future request. Also shows
 * a dedicated Status section, as a Synced/Syncing/Unsynced table,
 * listing every storage this plugin can sync (bank, equipment,
 * inventory, potion storage, seed vault, rune pouch, looting bag) and
 * whether each one is currently synced to the website (see
 * refreshSyncStatusInBackground/renderStatusRow below) -- the website's
 * own Storages Viewer feature reads exactly these storages, so
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

	// The same --gold custom property the website itself uses for its
	// own text wordmark (see .logo in app/static/css/myscape.css), so
	// the plugin's copy of it (see buildLogo below) matches that
	// exactly rather than drifting from it over time.
	private static final Color MYSCAPE_GOLD = new Color(0xff981f);

	// The storages the Status section lists, alphabetized by their
	// display label (STATUS_STORAGE_LABELS below) rather than mirroring
	// POSSESSION_STORAGE_KEYS's server-side order (see SyncStatusOut's
	// docstring in the Mootrius/myscape repo's app/schemas.py) --
	// doesn't matter for correctness either way, since
	// GET /api/players/me/sync-status (fetchSyncStatus below) reports
	// back keyed by name rather than position, so this is purely about
	// what's easiest for a player scanning the table to find a
	// particular storage in. More storages will likely join this list
	// later -- nothing here assumes exactly twelve, just keep it
	// alphabetized by label when one does.
	private static final String[] STATUS_STORAGE_KEYS = {
		"bank", "equipment", "forestry_kit", "inventory", "looting_bag", "master_scroll_book",
		"poh", "potion_storage", "rune_pouch", "seed_box", "seed_vault", "tackle_box",
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
	private final JButton unlinkButton = new JButton("UNLINK");
	private final JLabel unlinkErrorLabel = new JLabel();

	// The Status section's own per-storage status-value cells (see
	// buildStatusTable) and the synced/not-synced state currently drawn
	// into each -- Boolean rather than boolean so "not yet checked"
	// (null, drawn as "Syncing") is a distinct third state from
	// "checked, and it's genuinely never synced" (false, drawn as
	// "Unsynced"). Populated by refreshSyncStatusInBackground below
	// (the authoritative source, GET /api/players/me/sync-status) and
	// updated optimistically the instant this session's own sync of
	// that storage succeeds (see syncCallback), so the cell doesn't
	// have to wait for the next poll to reflect a sync that just
	// visibly happened.
	private final Map<String, JLabel> statusValueLabels = new LinkedHashMap<>();
	private final Map<String, Boolean> storageSynced = new LinkedHashMap<>();

	private boolean showingLinked;

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

		// The wordmark (and, below it, the social links row) sits above
		// cardPanel regardless of which card is currently showing, so it
		// needs its own wrapper -- BorderLayout only has one NORTH slot,
		// already taken by cardPanel itself. GridBagLayout rather than a
		// BoxLayout column for the same reason buildLinkedCard() uses it
		// (see that method's doc comment): a BoxLayout column here
		// wouldn't reliably stretch the social links row to the panel's
		// true width for it to center within.
		JPanel topPanel = new JPanel(new GridBagLayout());

		GridBagConstraints logoConstraints = new GridBagConstraints();
		logoConstraints.gridx = 0;
		logoConstraints.gridy = 0;
		logoConstraints.anchor = GridBagConstraints.CENTER;
		logoConstraints.fill = GridBagConstraints.HORIZONTAL;
		logoConstraints.weightx = 1.0;
		topPanel.add(buildLogo(), logoConstraints);

		GridBagConstraints socialConstraints = new GridBagConstraints();
		socialConstraints.gridx = 0;
		socialConstraints.gridy = 1;
		socialConstraints.anchor = GridBagConstraints.CENTER;
		socialConstraints.weightx = 1.0;
		socialConstraints.insets = new Insets(6, 0, 0, 0);
		topPanel.add(buildSocialLinksRow(), socialConstraints);

		GridBagConstraints cardPanelConstraints = new GridBagConstraints();
		cardPanelConstraints.gridx = 0;
		cardPanelConstraints.gridy = 2;
		cardPanelConstraints.anchor = GridBagConstraints.CENTER;
		cardPanelConstraints.fill = GridBagConstraints.HORIZONTAL;
		cardPanelConstraints.weightx = 1.0;
		cardPanelConstraints.insets = new Insets(12, 0, 0, 0);
		topPanel.add(cardPanel, cardPanelConstraints);

		add(topPanel, BorderLayout.NORTH);

		cards.show(cardPanel, CARD_CHECKING);
	}

	/** The plugin's own copy of the site's text wordmark -- myscape.io
	 * itself uses plain gold text rather than an image for its logo (see
	 * .logo in app/static/css/myscape.css), so this matches that exactly
	 * instead of bundling a picture asset that would need to be kept in
	 * sync with it separately. Shown once, above every card, regardless
	 * of which one is currently showing (see the constructor). */
	private JLabel buildLogo()
	{
		JLabel logo = new JLabel("myscape");
		logo.setFont(logo.getFont().deriveFont(Font.BOLD, 20f));
		logo.setForeground(MYSCAPE_GOLD);
		logo.setAlignmentX(Component.CENTER_ALIGNMENT);
		logo.setHorizontalAlignment(SwingConstants.CENTER);
		return logo;
	}

	/** The small row of icon-only social links directly under the
	 * wordmark, shown above every card the same way the wordmark itself
	 * is (see the constructor) -- the site itself, then GitHub, Discord,
	 * and Patreon, left to right. A plain FlowLayout.CENTER is fine for
	 * this row's own internal layout (unlike the outer cards, it isn't
	 * trying to stretch anything to fill available width, just lay four
	 * small buttons out side by side at their natural size), it only
	 * needed GridBagLayout help one level up, in the constructor, to
	 * actually get centered within the sidebar's true width in the
	 * first place. */
	private JPanel buildSocialLinksRow()
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
		row.setOpaque(false);
		row.add(buildSocialButton("myscape_home.png", "myscape.io", "https://myscape.io"));
		row.add(buildSocialButton("github.png", "GitHub", "https://github.com/Mootrius/myscape-plugin"));
		row.add(buildSocialButton("discord.png", "Discord", "https://discord.gg/myscape"));
		row.add(buildSocialButton("patreon.png", "Patreon", "https://patreon.com/myscape"));
		return row;
	}

	/** One icon-only button in the social links row above -- iconResource
	 * is a plain filename resolved relative to this class's own package
	 * (see the white discord.png/github.png/patreon.png files alongside
	 * MyscapePanel.java under src/main/resources), tooltipText names the
	 * site for anyone hovering, and url is opened in the player's normal
	 * desktop browser via RuneLite's own LinkBrowser (rather than
	 * java.awt.Desktop directly) since that's what every other
	 * RuneLite/plugin-hub plugin already uses for exactly this, and it
	 * already handles the cross-platform/headless edge cases so this
	 * doesn't have to. Flat and borderless to read as a plain icon
	 * rather than a boxed button, matching the unlink button's
	 * lightweight sidebar chrome rather than competing with it. */
	private JButton buildSocialButton(String iconResource, String tooltipText, String url)
	{
		JButton button = new JButton(new ImageIcon(getClass().getResource(iconResource)));
		button.setToolTipText(tooltipText);
		button.setBorderPainted(false);
		button.setContentAreaFilled(false);
		button.setFocusPainted(false);
		button.setMargin(new Insets(2, 2, 2, 2));
		button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		button.addActionListener(event -> LinkBrowser.browse(url));
		return button;
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

	/** Unlike every other card here, this one uses GridBagLayout directly
	 * (single column, gridx=0 throughout) rather than a BoxLayout column
	 * of nested panels -- a real debug print of the actual on-screen
	 * pixel bounds showed the previous BoxLayout-plus-wrapper-panel
	 * approach silently failing to stretch the button/table wrapper rows
	 * to the card's true width (they stayed at their own narrow content
	 * width instead, right-flush rather than stretched), even though
	 * BoxLayout's Y_AXIS docs say a child expands up to its own maximum
	 * size. GridBagLayout with weightx=1 on every row instead computes
	 * each row's width fresh from the container's actual current size on
	 * every layout pass, so it isn't vulnerable to whatever stale-size
	 * caching that was. A row that should hug the full width uses
	 * fill=HORIZONTAL; a row that should just be centered at its own
	 * natural size (the button) uses anchor=CENTER with no fill --
	 * either way, weightx=1 on at least one row in the column makes
	 * GridBagLayout give that column (and so every row in it) the full
	 * available width to work with. */
	private JPanel buildLinkedCard()
	{
		JPanel panel = new JPanel(new GridBagLayout());

		linkedNameLabel.setHorizontalAlignment(SwingConstants.CENTER);
		GridBagConstraints nameConstraints = new GridBagConstraints();
		nameConstraints.gridx = 0;
		nameConstraints.gridy = 0;
		nameConstraints.anchor = GridBagConstraints.CENTER;
		nameConstraints.fill = GridBagConstraints.HORIZONTAL;
		nameConstraints.weightx = 1.0;
		panel.add(linkedNameLabel, nameConstraints);

		unlinkButton.addActionListener(this::onUnlinkClicked);
		GridBagConstraints buttonConstraints = new GridBagConstraints();
		buttonConstraints.gridx = 0;
		buttonConstraints.gridy = 1;
		buttonConstraints.anchor = GridBagConstraints.CENTER;
		buttonConstraints.weightx = 1.0;
		buttonConstraints.insets = new Insets(8, 0, 0, 0);
		panel.add(unlinkButton, buttonConstraints);

		unlinkErrorLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		unlinkErrorLabel.setVisible(false);
		GridBagConstraints errorConstraints = new GridBagConstraints();
		errorConstraints.gridx = 0;
		errorConstraints.gridy = 2;
		errorConstraints.anchor = GridBagConstraints.WEST;
		errorConstraints.fill = GridBagConstraints.HORIZONTAL;
		errorConstraints.weightx = 1.0;
		errorConstraints.insets = new Insets(6, 0, 0, 0);
		panel.add(unlinkErrorLabel, errorConstraints);

		JPanel statusTable = buildStatusTable();

		// A plain "STATUS" label used to sit here uncontrolled (see
		// commit 66d756b, which dropped it entirely) -- this brings back
		// the same idea but clickable, so the table underneath (often a
		// dozen-plus rows, mostly just confirming everything's already
		// Synced) can be tucked away without pushing the rest of the
		// card out of view. Plain ASCII text rather than a triangle/caret
		// glyph for the same reason renderStatusRow below sticks to
		// plain text instead of a Unicode dot: a glyph missing from
		// RuneLite's bundled sidebar font renders as a "tofu box"
		// instead of silently falling back, so plain text sidesteps that
		// risk entirely.
		JLabel statusToggle = new JLabel("Status (hide)");
		statusToggle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		statusToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		statusToggle.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent event)
			{
				boolean nowVisible = !statusTable.isVisible();
				statusTable.setVisible(nowVisible);
				statusToggle.setText(nowVisible ? "Status (hide)" : "Status (show)");
				// GridBagLayout (like BoxLayout) skips an invisible
				// component's preferred size entirely, so hiding the
				// table also closes up the gap it left behind -- this
				// just needs to be told to re-run that layout pass.
				panel.revalidate();
				panel.repaint();
			}
		});
		GridBagConstraints toggleConstraints = new GridBagConstraints();
		toggleConstraints.gridx = 0;
		toggleConstraints.gridy = 3;
		toggleConstraints.anchor = GridBagConstraints.WEST;
		toggleConstraints.fill = GridBagConstraints.HORIZONTAL;
		toggleConstraints.weightx = 1.0;
		toggleConstraints.insets = new Insets(14, 0, 4, 0);
		panel.add(statusToggle, toggleConstraints);

		GridBagConstraints tableConstraints = new GridBagConstraints();
		tableConstraints.gridx = 0;
		tableConstraints.gridy = 4;
		tableConstraints.anchor = GridBagConstraints.WEST;
		tableConstraints.fill = GridBagConstraints.HORIZONTAL;
		tableConstraints.weightx = 1.0;
		panel.add(statusTable, tableConstraints);

		return panel;
	}

	/** Builds the Status section as a genuine two-column table --
	 * storage name in the left column, colored Synced/Syncing/Unsynced
	 * state in the right (see renderStatusRow) -- using GridBagLayout
	 * rather than a JTable (too heavy and visually inconsistent with
	 * RuneLite's minimal sidebar chrome for a dozen static rows) or a
	 * plain GridLayout (would force both columns to the width of
	 * whichever cell is widest across BOTH columns, wasting space next
	 * to short names like "PoH"). Each column instead sizes to its own
	 * widest cell, so names and states line up like a real table
	 * without stretching either further than it needs to. Built once
	 * here; only ever re-rendered in place afterward (see
	 * renderStatusRow), never rebuilt. */
	private JPanel buildStatusTable()
	{
		JPanel table = new JPanel(new GridBagLayout());

		int row = 0;
		for (String key : STATUS_STORAGE_KEYS)
		{
			GridBagConstraints nameConstraints = new GridBagConstraints();
			nameConstraints.gridx = 0;
			nameConstraints.gridy = row;
			nameConstraints.anchor = GridBagConstraints.WEST;
			nameConstraints.insets = new Insets(2, 0, 2, 12);
			table.add(new JLabel(STATUS_STORAGE_LABELS.getOrDefault(key, key)), nameConstraints);

			GridBagConstraints valueConstraints = new GridBagConstraints();
			valueConstraints.gridx = 1;
			valueConstraints.gridy = row;
			valueConstraints.anchor = GridBagConstraints.WEST;
			valueConstraints.insets = new Insets(2, 0, 2, 0);
			// With every column's weightx left at the GridBagLayout
			// default of 0, any leftover width in the table's actual
			// (now full-card-width) container gets split as margin
			// around the whole two-column grid, centering it -- each
			// cell's own WEST anchor only positions content within its
			// own cell, it doesn't stop the grid as a block from
			// drifting off the left edge. Giving this trailing column
			// all the weight routes that leftover space to its right
			// instead, so the grid as a whole stays pinned to the left
			// wall the way the name column's own WEST anchor implies.
			valueConstraints.weightx = 1.0;
			JLabel valueLabel = new JLabel();
			statusValueLabels.put(key, valueLabel);
			table.add(valueLabel, valueConstraints);

			row++;
		}

		return table;
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
		String name = runescapeName != null ? runescapeName : plugin.getRuneScapeName();
		linkedNameLabel.setText("<html>Linked as <b>" + escape(name != null ? name : "your game account") + "</b></html>");
		cards.show(cardPanel, CARD_LINKED);

		// A fresh "Linked" screen (first link, or a re-check after
		// re-opening the panel) means whatever the Status table last
		// showed is stale too -- reset every row to "Syncing" and kick
		// off an authoritative refresh rather than keep showing state
		// left over from before (or from a different account).
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
	 * latestDiaryTierItems fields) and, for each of the twelve that
	 * have a Status-table row (see STATUS_STORAGE_KEYS), marks that
	 * row synced the instant the sync succeeds (see syncCallback). A
	 * failure is handled the same quiet way the background
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
			client.syncBank(bankItems, syncCallback("bank"));
		}
		if (inventoryItems != null)
		{
			client.syncInventory(inventoryItems, syncCallback("inventory"));
		}
		if (equipmentItems != null)
		{
			client.syncEquipment(equipmentItems, syncCallback("equipment"));
		}
		if (potionStorageItems != null)
		{
			client.syncPotionStorage(potionStorageItems, syncCallback("potion_storage"));
		}
		if (runePouchItems != null)
		{
			client.syncRunePouch(runePouchItems, syncCallback("rune_pouch"));
		}
		if (lootingBagItems != null)
		{
			client.syncLootingBag(lootingBagItems, syncCallback("looting_bag"));
		}
		if (seedVaultItems != null)
		{
			client.syncSeedVault(seedVaultItems, syncCallback("seed_vault"));
		}
		if (tackleBoxItems != null)
		{
			client.syncTackleBox(tackleBoxItems, syncCallback("tackle_box"));
		}
		if (pohItems != null)
		{
			client.syncPoh(pohItems, syncCallback("poh"));
		}
		if (forestryKitItems != null)
		{
			client.syncForestryKit(forestryKitItems, syncCallback("forestry_kit"));
		}
		if (seedBoxItems != null)
		{
			client.syncSeedBox(seedBoxItems, syncCallback("seed_box"));
		}
		if (masterScrollBookItems != null)
		{
			client.syncMasterScrollBook(masterScrollBookItems, syncCallback("master_scroll_book"));
		}
		if (skillItems != null)
		{
			client.syncSkills(skillItems, syncCallback());
		}
		if (completedQuestNames != null)
		{
			client.syncQuests(completedQuestNames, syncCallback());
		}
		if (diaryTierItems != null)
		{
			client.syncDiaries(diaryTierItems, syncCallback());
		}
	}

	/** Called by the plugin (see MyscapePlugin#onLootReceived) the
	 * moment a new loot event is captured -- unlike syncInBackground
	 * above, this fires per-event immediately rather than going through
	 * the shared debounce, since a loot event is a discrete thing that
	 * already happened, not a "current state" that benefits from
	 * collapsing a burst of changes into one send (see LootEvent's
	 * docstring in the Mootrius/myscape repo's app/models.py). Loot
	 * events aren't part of the Status table (see STATUS_STORAGE_KEYS)
	 * and have nothing else in the UI to update, so this just fires
	 * the sync and otherwise stays quiet. */
	void syncLootInBackground(MyscapeClient.LootEvent event)
	{
		client.syncLootEvent(event, new MyscapeClient.SyncCallback()
		{
			@Override
			public void onSuccess(int addedCount)
			{
				// Nothing to update -- see the method doc above.
			}

			@Override
			public void onFailure(String message)
			{
				// Ignored -- a dropped loot sync just means this one
				// event never made it, and the next kill tries fresh
				// rather than this needing any retry logic of its own.
			}
		});
	}

	/** Builds a SyncCallback whose only remaining job is to swallow a
	 * failure quietly (see the method doc below) -- used directly (no
	 * storage key) by skills/quests/diaries, which aren't part of the
	 * Status table; see the overload below for the twelve that are. */
	private MyscapeClient.SyncCallback syncCallback()
	{
		return new MyscapeClient.SyncCallback()
		{
			@Override
			public void onSuccess(int itemCount)
			{
				// Nothing to update -- skills/quests/diaries don't have a
				// Status table row of their own, and the combined
				// "Synced: N bank, ..." summary line this used to feed is
				// gone (superseded by the Status table).
			}

			@Override
			public void onFailure(String message)
			{
				// Ignored -- a dropped background sync just means this one
				// attempt never landed; the next debounce firing tries
				// again fresh rather than this needing any retry logic of
				// its own.
			}
		};
	}

	/** Same as the plain syncCallback above, plus marking storageKey
	 * synced in the Status table the instant this sync succeeds --
	 * used by the twelve item-source syncs in syncInBackground, each
	 * of which corresponds to one of STATUS_STORAGE_KEYS. This is
	 * deliberately optimistic (set the moment the response comes back,
	 * not on the next poll of GET /api/players/me/sync-status) so the
	 * row doesn't lag a sync that already succeeded --
	 * refreshSyncStatusInBackground below will confirm the same thing
	 * shortly anyway. */
	private MyscapeClient.SyncCallback syncCallback(String storageKey)
	{
		return new MyscapeClient.SyncCallback()
		{
			@Override
			public void onSuccess(int itemCount)
			{
				SwingUtilities.invokeLater(() -> {
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

	/** Redraws one Status table row from storageSynced's current view
	 * of that key -- just the status-value cell (see buildStatusTable;
	 * the storage's name lives in its own, separate, uncolored cell) --
	 * in the site's own completed-progress green for "Synced" once
	 * storageSynced.get(key) is true, its progress-error red for
	 * "Unsynced" once it's known false (nothing alarming about that --
	 * plenty of these, Seed Vault especially right now, simply haven't
	 * been wired up to sync at all yet), or its in-progress orange for
	 * "Syncing" for as long as it's still null (never fetched this
	 * "Linked" session -- see showLinked). Deliberately plain text with
	 * no dot/bullet glyph -- an earlier version used a Unicode circle
	 * character for this, which showed up as a "tofu box" (garbled
	 * placeholder glyph) for at least one player because RuneLite's own
	 * bundled sidebar font doesn't cover it; color alone carries the
	 * same information without depending on font glyph coverage at
	 * all. */
	private void renderStatusRow(String key)
	{
		JLabel value = statusValueLabels.get(key);
		if (value == null)
		{
			return;
		}
		Boolean synced = storageSynced.get(key);
		if (synced == null)
		{
			value.setText("<html><span style='color:" + toHex(ColorScheme.PROGRESS_INPROGRESS_COLOR) + ";'>Syncing</span></html>");
			return;
		}
		if (synced)
		{
			value.setText("<html><span style='color:" + toHex(ColorScheme.PROGRESS_COMPLETE_COLOR) + ";'>Synced</span></html>");
		}
		else
		{
			value.setText("<html><span style='color:" + toHex(ColorScheme.PROGRESS_ERROR_COLOR) + ";'>Unsynced</span></html>");
		}
	}

	private static String toHex(Color color)
	{
		return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
	}

	private static String escape(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
