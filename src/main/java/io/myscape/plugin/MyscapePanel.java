package io.myscape.plugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.CardLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
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
 * Sidebar panel for linking the plugin to a myscape.io account.
 * The player pastes the short-lived code from myscape.io, which is exchanged
 * for an API key stored by MyscapeClient. Data syncing runs silently in the
 * background (see syncInBackground).
 *
 * <p>Four CardLayout cards: checking the stored key, not logged into the game,
 * ready to link, and linked.
 */
class MyscapePanel extends PluginPanel
{
	private static final String CARD_CHECKING = "checking";
	private static final String CARD_NOT_LOGGED_IN = "not-logged-in";
	private static final String CARD_LINK = "link";
	private static final String CARD_LINKED = "linked";

	// Matches the gold of the wordmark on myscape.io.
	private static final Color MYSCAPE_GOLD = new Color(0xff981f);


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

	// Button and failure message for uploading the character model.
	// The status label is only shown on failure.
	private final JButton exportModelButton = new JButton("UPDATE MODEL");
	private final JLabel exportModelStatusLabel = new JLabel();


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

		// The logo and social links sit above every card. BorderLayout has a single
		// NORTH slot, so they share a wrapper with cardPanel. GridBagLayout is used
		// because a BoxLayout column does not reliably stretch rows to the full width.
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

	/** Text wordmark shown above every card. */
	private JLabel buildLogo()
	{
		JLabel logo = new JLabel("myscape");
		logo.setFont(wordmarkFont());
		logo.setForeground(MYSCAPE_GOLD);
		logo.setAlignmentX(Component.CENTER_ALIGNMENT);
		logo.setHorizontalAlignment(SwingConstants.CENTER);
		return logo;
	}

	/** Loads the bundled RuneScape wordmark font; falls back to the default font if unavailable. */
	private static Font wordmarkFont()
	{
		Font base = null;
		try (InputStream in = MyscapePanel.class.getResourceAsStream("RuneScapePlain11.otf"))
		{
			if (in != null)
			{
				base = Font.createFont(Font.TRUETYPE_FONT, in);
			}
		}
		catch (FontFormatException | IOException e)
		{
			base = null;
		}
		if (base == null)
		{
			return new JLabel().getFont().deriveFont(Font.PLAIN, 28f);
		}
		Map<TextAttribute, Object> attributes = new HashMap<>();
		attributes.put(TextAttribute.SIZE, 28f);
		attributes.put(TextAttribute.WEIGHT, TextAttribute.WEIGHT_REGULAR);
		// Letter-spacing of 0.03em.
		attributes.put(TextAttribute.TRACKING, 0.03f);
		return base.deriveFont(attributes);
	}

	/** Icon-only links to the site, GitHub, Discord and Patreon, shown under the wordmark. */
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

	/** Flat icon button that opens the given URL in the desktop browser via LinkBrowser. */
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
		JLabel label = new JLabel("<html>Log in to start.</html>");
		label.setAlignmentX(Component.CENTER_ALIGNMENT);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		panel.add(label);
		return panel;
	}

	/**
	 * Uses GridBagLayout instead of BoxLayout, which does not reliably center children
	 * (see buildLinkedCard()).
	 *
	 * <p>The intro text centers itself with HTML because JLabel#setHorizontalAlignment
	 * is ignored for wrapped HTML. The last row has weighty=1 and anchor=NORTH so the
	 * content stays at the top when cardPanel is stretched to the tallest card.
	 */
	private JPanel buildLinkCard()
	{
		JPanel panel = new JPanel(new GridBagLayout());

		JLabel intro = new JLabel("<html><div style='text-align: center;'>Enter your code from myscape.io</div></html>");
		intro.setHorizontalAlignment(SwingConstants.CENTER);
		GridBagConstraints introConstraints = new GridBagConstraints();
		introConstraints.gridx = 0;
		introConstraints.gridy = 0;
		introConstraints.anchor = GridBagConstraints.CENTER;
		introConstraints.fill = GridBagConstraints.HORIZONTAL;
		introConstraints.weightx = 1.0;
		panel.add(intro, introConstraints);

		GridBagConstraints codeConstraints = new GridBagConstraints();
		codeConstraints.gridx = 0;
		codeConstraints.gridy = 1;
		codeConstraints.anchor = GridBagConstraints.WEST;
		codeConstraints.fill = GridBagConstraints.HORIZONTAL;
		codeConstraints.weightx = 1.0;
		codeConstraints.insets = new Insets(8, 0, 0, 0);
		panel.add(codeField, codeConstraints);

		linkButton.setText("LINK");
		linkButton.addActionListener(this::onLinkClicked);
		GridBagConstraints buttonConstraints = new GridBagConstraints();
		buttonConstraints.gridx = 0;
		buttonConstraints.gridy = 2;
		buttonConstraints.anchor = GridBagConstraints.CENTER;
		buttonConstraints.weightx = 1.0;
		buttonConstraints.insets = new Insets(8, 0, 0, 0);
		panel.add(linkButton, buttonConstraints);

		linkErrorLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
		linkErrorLabel.setVisible(false);
		GridBagConstraints errorConstraints = new GridBagConstraints();
		errorConstraints.gridx = 0;
		errorConstraints.gridy = 3;
		errorConstraints.anchor = GridBagConstraints.NORTH;
		errorConstraints.fill = GridBagConstraints.HORIZONTAL;
		errorConstraints.weightx = 1.0;
		errorConstraints.weighty = 1.0;
		errorConstraints.insets = new Insets(6, 0, 0, 0);
		panel.add(linkErrorLabel, errorConstraints);

		return panel;
	}

	/**
	 * Uses a single-column GridBagLayout instead of nested BoxLayout panels, which
	 * did not stretch rows to the full card width. weightx=1 gives the column the
	 * full width; fill=HORIZONTAL stretches a row, anchor=CENTER centers it at its
	 * natural size.
	 */
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

		// Uploads the player's current character model to myscape.io (see onExportModelClicked).
		exportModelButton.addActionListener(this::onExportModelClicked);
		GridBagConstraints exportButtonConstraints = new GridBagConstraints();
		exportButtonConstraints.gridx = 0;
		exportButtonConstraints.gridy = 3;
		exportButtonConstraints.anchor = GridBagConstraints.CENTER;
		exportButtonConstraints.weightx = 1.0;
		exportButtonConstraints.insets = new Insets(14, 0, 0, 0);
		panel.add(exportModelButton, exportButtonConstraints);

		exportModelStatusLabel.setVisible(false);
		GridBagConstraints exportStatusConstraints = new GridBagConstraints();
		exportStatusConstraints.gridx = 0;
		exportStatusConstraints.gridy = 4;
		exportStatusConstraints.anchor = GridBagConstraints.WEST;
		exportStatusConstraints.fill = GridBagConstraints.HORIZONTAL;
		exportStatusConstraints.weightx = 1.0;
		exportStatusConstraints.insets = new Insets(6, 0, 0, 0);
		panel.add(exportModelStatusLabel, exportStatusConstraints);


		return panel;
	}

	/** Called on startup. Re-checks the stored API key with the server, since it may have been revoked. */
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

	/**
	 * Called when the game login state changes so the "log in" card swaps for the link form.
	 * Both callers run on the client thread, so this hops onto the EDT.
	 */
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

	/**
	 * Captures the local player's model and uploads it. The capture callbacks run on the
	 * client thread, so every Swing update is wrapped in SwingUtilities.invokeLater.
	 */
	private void onExportModelClicked(ActionEvent event)
	{
		exportModelButton.setEnabled(false);
		exportModelStatusLabel.setVisible(false);

		plugin.captureCharacterModel(new MyscapePlugin.ModelCaptureCallback()
		{
			@Override
			public void onSuccess(byte[] glb)
			{
				client.syncCharacterModel(glb, new MyscapeClient.SyncCallback()
				{
					@Override
					public void onSuccess(int byteCount)
					{
						SwingUtilities.invokeLater(() -> exportModelButton.setEnabled(true));
					}

					@Override
					public void onFailure(String message)
					{
						SwingUtilities.invokeLater(() -> {
							exportModelButton.setEnabled(true);
							exportModelStatusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
							exportModelStatusLabel.setText("<html>" + escape(message) + "</html>");
							exportModelStatusLabel.setVisible(true);
						});
					}
				});
			}

			@Override
			public void onFailure(String message)
			{
				SwingUtilities.invokeLater(() -> {
					exportModelButton.setEnabled(true);
					exportModelStatusLabel.setForeground(ColorScheme.PROGRESS_ERROR_COLOR);
					exportModelStatusLabel.setText("<html>" + escape(message) + "</html>");
					exportModelStatusLabel.setVisible(true);
				});
			}
		});
	}

	/**
	 * Syncs every non-null argument. Called off the EDT by the plugin after storage and
	 * progress data have settled (see MyscapePlugin#scheduleSyncDebounced). Failures
	 * are ignored; the next change retries.
	 */
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
		List<String> inProgressQuestNames,
		List<MyscapeClient.DiaryTierItem> diaryTierItems,
		List<MyscapeClient.HuntsmansKitItem> huntsmansKitItems,
		List<MyscapeClient.BoatCargoItem> boatCargo1Items,
		List<MyscapeClient.BoatCargoItem> boatCargo2Items,
		List<MyscapeClient.BoatCargoItem> boatCargo3Items,
		List<MyscapeClient.BoatCargoItem> boatCargo4Items,
		List<MyscapeClient.BoatCargoItem> boatCargo5Items,
		MyscapeClient.CharacterSummaryItem characterSummary,
		List<MyscapeClient.CollectionLogItem> collectionLogItems)
	{
		if (bankItems != null)
		{
			client.syncBank(bankItems, syncCallback());
		}
		if (inventoryItems != null)
		{
			client.syncInventory(inventoryItems, syncCallback());
		}
		if (equipmentItems != null)
		{
			client.syncEquipment(equipmentItems, syncCallback());
		}
		if (potionStorageItems != null)
		{
			client.syncPotionStorage(potionStorageItems, syncCallback());
		}
		if (runePouchItems != null)
		{
			client.syncRunePouch(runePouchItems, syncCallback());
		}
		if (lootingBagItems != null)
		{
			client.syncLootingBag(lootingBagItems, syncCallback());
		}
		if (seedVaultItems != null)
		{
			client.syncSeedVault(seedVaultItems, syncCallback());
		}
		if (tackleBoxItems != null)
		{
			client.syncTackleBox(tackleBoxItems, syncCallback());
		}
		if (pohItems != null)
		{
			client.syncPoh(pohItems, syncCallback());
		}
		if (forestryKitItems != null)
		{
			client.syncForestryKit(forestryKitItems, syncCallback());
		}
		if (seedBoxItems != null)
		{
			client.syncSeedBox(seedBoxItems, syncCallback());
		}
		if (masterScrollBookItems != null)
		{
			client.syncMasterScrollBook(masterScrollBookItems, syncCallback());
		}
		if (huntsmansKitItems != null)
		{
			client.syncHuntsmansKit(huntsmansKitItems, syncCallback());
		}
		if (boatCargo1Items != null)
		{
			client.syncBoatCargo(1, boatCargo1Items, syncCallback());
		}
		if (boatCargo2Items != null)
		{
			client.syncBoatCargo(2, boatCargo2Items, syncCallback());
		}
		if (boatCargo3Items != null)
		{
			client.syncBoatCargo(3, boatCargo3Items, syncCallback());
		}
		if (boatCargo4Items != null)
		{
			client.syncBoatCargo(4, boatCargo4Items, syncCallback());
		}
		if (boatCargo5Items != null)
		{
			client.syncBoatCargo(5, boatCargo5Items, syncCallback());
		}
		if (skillItems != null)
		{
			client.syncSkills(skillItems, syncCallback());
		}
		if (completedQuestNames != null && inProgressQuestNames != null)
		{
			client.syncQuests(completedQuestNames, inProgressQuestNames, syncCallback());
		}
		if (diaryTierItems != null)
		{
			client.syncDiaries(diaryTierItems, syncCallback());
		}
		if (characterSummary != null)
		{
			client.syncCharacterSummary(characterSummary, syncCallback());
		}
		if (collectionLogItems != null)
		{
			client.syncCollectionLogItems(collectionLogItems, syncCallback());
		}
	}

	/**
	 * Sends a loot event immediately. Not debounced, since a loot event is a one-off
	 * occurrence rather than current state. Failures are ignored.
	 */
	void syncLootInBackground(MyscapeClient.LootEvent event)
	{
		client.syncLootEvent(event, new MyscapeClient.SyncCallback()
		{
			@Override
			public void onSuccess(int addedCount)
			{
				// Nothing to update.
			}

			@Override
			public void onFailure(String message)
			{
				// Ignored; later loot events are sent independently.
			}
		});
	}

	/**
	 * Reports the character's display name when it changes (see MyscapePlugin#onGameTick).
	 * onReported runs once the server accepts it; failures are retried on a later tick.
	 */
	void syncRunescapeNameInBackground(long accountHash, String runescapeName, Runnable onReported)
	{
		client.syncRunescapeName(accountHash, runescapeName, new MyscapeClient.SyncCallback()
		{
			@Override
			public void onSuccess(int updated)
			{
				onReported.run();
			}

			@Override
			public void onFailure(String message)
			{
				// Ignored; the plugin retries after its cooldown.
			}
		});
	}

	/**
	 * Sends a kill count parsed from chat (see MyscapePlugin#onChatMessage). Not debounced,
	 * like syncLootInBackground. Failures are ignored.
	 */
	void syncKillCountInBackground(String bossName, int killCount)
	{
		client.syncKillCountUpdate(bossName, killCount, new MyscapeClient.SyncCallback()
		{
			@Override
			public void onSuccess(int appliedCount)
			{
				// Nothing to update.
			}

			@Override
			public void onFailure(String message)
			{
				// Ignored; a later kill count or hiscores resync catches up.
			}
		});
	}

	/**
	 * Sends a newly unlocked collection log item by name. Not debounced. Failures are
	 * ignored; the next full collection log capture catches up.
	 */
	void syncCollectionLogItemInBackground(String itemName)
	{
		client.syncCollectionLogItemAdded(itemName, syncCallback());
	}

	/** Returns a callback that ignores both success and failure. */
	private MyscapeClient.SyncCallback syncCallback()
	{
		return new MyscapeClient.SyncCallback()
		{
			@Override
			public void onSuccess(int itemCount)
			{
				// Nothing to update; the sidebar shows no sync status.
			}

			@Override
			public void onFailure(String message)
			{
				// Ignored; the next sync retries.
			}
		};
	}

	private static String escape(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
