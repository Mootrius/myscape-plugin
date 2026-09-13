package io.myscape.plugin;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.CardLayout;
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
 * MyscapeClient stores and reuses for every future request.
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

	private final MyscapeClient client;
	private final MyscapePlugin plugin;

	private final CardLayout cards = new CardLayout();
	private final JPanel cardPanel = new JPanel(cards);

	private final JTextField codeField = new JTextField();
	private final JButton linkButton = new JButton("Link account");
	private final JLabel linkErrorLabel = new JLabel();

	private final JLabel linkedNameLabel = new JLabel();
	private final JButton unlinkButton = new JButton("Unlink");

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
		JLabel label = new JLabel("<html>Log into the game, then come back here to link your account.</html>");
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(label);
		return panel;
	}

	private JPanel buildLinkCard()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

		JLabel intro = new JLabel("<html>On myscape.io, click “Generate code” and paste it below.</html>");
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

	/** Called by the plugin whenever the game's login state changes, so
	 * the "log in first" card swaps for the actual link form (or vice
	 * versa) without the player having to reopen the panel. */
	void onGameStateChanged()
	{
		if (!showingLinked)
		{
			showLinkOrPrompt();
		}
	}

	private void showLinkOrPrompt()
	{
		showingLinked = false;
		cards.show(cardPanel, plugin.getAccountHash() == null ? CARD_NOT_LOGGED_IN : CARD_LINK);
	}

	private void showLinked(String runescapeName)
	{
		showingLinked = true;
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
		client.clearStoredLink();
		showLinkOrPrompt();
	}

	private static String escape(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
