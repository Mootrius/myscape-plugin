package io.myscape.plugin.collectionlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;

/**
 * Exercises {@link CollectionLogItemCapture} against a fully mocked
 * {@code Client} -- same reasoning as {@code
 * CollectionLogSchemaExporterTest}'s own doc: this environment has no
 * live game client, so a Mockito mock of the handful of {@code Client}
 * calls this class makes (menuAction/runScript/getVarbitValue/
 * getTickCount) is the only way to exercise its sequencing at all,
 * including the parts (the re-entrancy guard, the tick-settle buffer)
 * that only ever show up across several calls in a row.
 */
public class CollectionLogItemCaptureTest
{
	private static final int COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID = 4100;
	private static final int COLLECTION_LOG_SETUP_SCRIPT_ID = 7797;
	private static final int COLLECTION_LOG_INIT_SCRIPT_ID = 2240;
	private static final int UNRELATED_SCRIPT_ID = 1234;

	@Test
	public void triggersSearchToggleAndInitScriptOnOpen()
	{
		Client client = notOpenedFromAdventureLog();

		new CollectionLogItemCapture().onCollectionLogOpened(COLLECTION_LOG_SETUP_SCRIPT_ID, client);

		verify(client).menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		verify(client).runScript(COLLECTION_LOG_INIT_SCRIPT_ID);
	}

	@Test
	public void ignoresUnrelatedScriptIds()
	{
		Client client = mock(Client.class);
		CollectionLogItemCapture capture = new CollectionLogItemCapture();

		capture.onCollectionLogOpened(UNRELATED_SCRIPT_ID, client);
		capture.onScriptPreFired(UNRELATED_SCRIPT_ID, new Object[] { 0, 12922, 1 }, client);

		// Neither call should even need to check the adventure-log
		// varbit, since both bail out on the script id check first.
		verifyNoInteractions(client);
		assertTrue(capture.snapshotCapturedItems().isEmpty());
	}

	@Test
	public void doesNotRetriggerWhileACapturePassIsAlreadyInFlight()
	{
		Client client = notOpenedFromAdventureLog();
		CollectionLogItemCapture capture = new CollectionLogItemCapture();

		// The init script's own side effect is to re-fire the setup
		// script -- without the re-entrancy guard, this second call
		// would trigger a second search-toggle + init-script, which
		// would trigger a third, forever.
		capture.onCollectionLogOpened(COLLECTION_LOG_SETUP_SCRIPT_ID, client);
		capture.onCollectionLogOpened(COLLECTION_LOG_SETUP_SCRIPT_ID, client);

		verify(client, times(1)).runScript(COLLECTION_LOG_INIT_SCRIPT_ID);
	}

	@Test
	public void retriggersOnceThePreviousCapturePassHasSettled()
	{
		Client client = notOpenedFromAdventureLog();
		CollectionLogItemCapture capture = new CollectionLogItemCapture();

		capture.onCollectionLogOpened(COLLECTION_LOG_SETUP_SCRIPT_ID, client);
		capture.onScriptPreFired(COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID, new Object[] { 0, 12922, 1 }, client);

		when(client.getTickCount()).thenReturn(10, 13);
		// tick=10 is when the item above fired; tick=13 is 3 ticks
		// later, past the settle buffer, so this is where a pass is
		// decided to be finished and the guard resets.
		capture.onGameTick(client);
		capture.onGameTick(client);

		capture.onCollectionLogOpened(COLLECTION_LOG_SETUP_SCRIPT_ID, client);

		verify(client, times(2)).runScript(COLLECTION_LOG_INIT_SCRIPT_ID);
	}

	@Test
	public void capturesItemIdAndQuantityFromDelayedTransmit()
	{
		Client client = notOpenedFromAdventureLog();
		CollectionLogItemCapture capture = new CollectionLogItemCapture();

		capture.onScriptPreFired(COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID, new Object[] { 0, 12922, 3 }, client);

		Map<Integer, Integer> items = capture.snapshotCapturedItems();
		assertEquals(1, items.size());
		assertEquals(Integer.valueOf(3), items.get(12922));
	}

	@Test
	public void accumulatesMultipleItemsAcrossFirings()
	{
		Client client = notOpenedFromAdventureLog();
		CollectionLogItemCapture capture = new CollectionLogItemCapture();

		capture.onScriptPreFired(COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID, new Object[] { 0, 12922, 1 }, client);
		capture.onScriptPreFired(COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID, new Object[] { 0, 21295, 5 }, client);

		Map<Integer, Integer> items = capture.snapshotCapturedItems();
		assertEquals(2, items.size());
		assertEquals(Integer.valueOf(1), items.get(12922));
		assertEquals(Integer.valueOf(5), items.get(21295));
	}

	@Test
	public void skipsCaptureAndClearsWhenOpenedFromAdventureLog()
	{
		Client client = notOpenedFromAdventureLog();
		CollectionLogItemCapture capture = new CollectionLogItemCapture();
		capture.onScriptPreFired(COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID, new Object[] { 0, 12922, 1 }, client);
		assertFalse(capture.snapshotCapturedItems().isEmpty());

		Client adventureLogClient = openedFromAdventureLog();
		capture.onCollectionLogOpened(COLLECTION_LOG_SETUP_SCRIPT_ID, adventureLogClient);

		assertTrue(capture.snapshotCapturedItems().isEmpty());
		verify(adventureLogClient, never()).menuAction(
			anyInt(), anyInt(), any(MenuAction.class), anyInt(), anyInt(), anyString(), anyString());
	}

	@Test
	public void skipsItemCaptureWhenOpenedFromAdventureLog()
	{
		Client client = openedFromAdventureLog();
		CollectionLogItemCapture capture = new CollectionLogItemCapture();

		capture.onScriptPreFired(COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID, new Object[] { 0, 12922, 1 }, client);

		assertTrue(capture.snapshotCapturedItems().isEmpty());
	}

	@Test
	public void adventureLogVarbitChangedClearsCapturedItemsOnlyWhenOpen()
	{
		CollectionLogItemCapture capture = new CollectionLogItemCapture();
		Client client = notOpenedFromAdventureLog();
		capture.onScriptPreFired(COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID, new Object[] { 0, 12922, 1 }, client);
		assertFalse(capture.snapshotCapturedItems().isEmpty());

		capture.onAdventureLogVarbitChanged(client);
		assertFalse("varbit reporting closed should not clear anything", capture.snapshotCapturedItems().isEmpty());

		capture.onAdventureLogVarbitChanged(openedFromAdventureLog());
		assertTrue(capture.snapshotCapturedItems().isEmpty());
	}

	@Test
	public void gameTickReturnsFalseWithNoCapturePassInFlight()
	{
		Client client = mock(Client.class);
		CollectionLogItemCapture capture = new CollectionLogItemCapture();

		assertFalse(capture.onGameTick(client));
	}

	@Test
	public void gameTickSettlesOnlyAfterBufferElapses()
	{
		Client client = notOpenedFromAdventureLog();
		CollectionLogItemCapture capture = new CollectionLogItemCapture();

		when(client.getTickCount()).thenReturn(10);
		capture.onScriptPreFired(COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID, new Object[] { 0, 12922, 1 }, client);

		when(client.getTickCount()).thenReturn(11);
		assertFalse("one tick after the item fired is still within the buffer", capture.onGameTick(client));

		when(client.getTickCount()).thenReturn(12);
		assertFalse("two ticks after is still within the buffer", capture.onGameTick(client));

		when(client.getTickCount()).thenReturn(13);
		assertTrue("three ticks after is past the buffer", capture.onGameTick(client));

		assertFalse("settling should only ever report true once per pass", capture.onGameTick(client));
	}

	private static Client notOpenedFromAdventureLog()
	{
		Client client = mock(Client.class);
		when(client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN)).thenReturn(0);
		return client;
	}

	private static Client openedFromAdventureLog()
	{
		Client client = mock(Client.class);
		when(client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN)).thenReturn(1);
		return client;
	}
}
