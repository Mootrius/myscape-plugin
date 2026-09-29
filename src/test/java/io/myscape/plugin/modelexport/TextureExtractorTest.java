package io.myscape.plugin.modelexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * Checks {@link TextureExtractor} against a mocked {@code
 * TextureProvider} -- this environment has no live game client, so a
 * mock (matching this codebase's existing FakeModel approach for
 * {@code Model} itself, just via Mockito here since {@code
 * TextureProvider} is a small interface with no long tail of unrelated
 * methods to stub around) is the only way to exercise this class's own
 * logic at all. Decodes the PNG bytes it produces back out with the
 * JDK's own {@code ImageIO} -- reading the actual encoded image, not
 * just trusting the code that wrote it -- to confirm both the pixel
 * values and the zero-means-transparent convention documented on
 * TextureExtractor itself.
 */
public class TextureExtractorTest
{
	private static final int SIZE = 128;

	@Test
	public void extractsRealPixelsAndEncodesThemAsPng() throws IOException
	{
		int[] pixels = new int[SIZE * SIZE];
		// A simple recognizable pattern: top-left quadrant red, rest a
		// mid-gray -- enough to catch a row/column transposition bug in
		// the (x, y) loop, which a uniform fill wouldn't.
		for (int y = 0; y < SIZE; y++)
		{
			for (int x = 0; x < SIZE; x++)
			{
				pixels[y * SIZE + x] = (x < SIZE / 2 && y < SIZE / 2) ? 0xFF0000 : 0x404040;
			}
		}

		TextureProvider provider = mockProviderWithTexture(7, pixels);

		Map<Integer, byte[]> result = TextureExtractor.extractPngs(provider, setOf(7));

		assertTrue("texture 7 was extracted", result.containsKey(7));
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.get(7)));
		assertEquals(SIZE, decoded.getWidth());
		assertEquals(SIZE, decoded.getHeight());
		assertEquals("red quadrant round-trips, opaque", 0xFFFF0000, decoded.getRGB(10, 10));
		assertEquals("gray region round-trips, opaque", 0xFF404040, decoded.getRGB(100, 100));
	}

	@Test
	public void zeroPixelValueDecodesAsTransparentNotBlack()
	{
		int[] pixels = new int[SIZE * SIZE];
		pixels[0] = 0x000000; // the format's own "no data" marker, not literal black
		pixels[1] = 0xFFFFFF; // a real (opaque) white pixel, for contrast

		TextureProvider provider = mockProviderWithTexture(2, pixels);

		Map<Integer, byte[]> result = TextureExtractor.extractPngs(provider, setOf(2));

		BufferedImage decoded = decode(result.get(2));
		assertEquals("packed 0 -> fully transparent, alpha channel zeroed", 0x00000000, decoded.getRGB(0, 0));
		assertEquals("a real white pixel stays opaque white", 0xFFFFFFFF, decoded.getRGB(1, 0));
	}

	@Test
	public void pinsBrightnessToOneForTheDurationOfExtractionThenRestoresIt()
	{
		TextureProvider provider = mockProviderWithTexture(1, new int[SIZE * SIZE]);
		when(provider.getBrightness()).thenReturn(0.8d);

		TextureExtractor.extractPngs(provider, setOf(1));

		// See TextureExtractor's own top-of-file doc: TextureProvider
		// bakes its own current brightness into load()'s pixels, and
		// this package already applies brightness separately per-vertex
		// (HslColor#textureLightnessToRgb), so extraction must happen at
		// 1.0 and the player's real setting must come back afterward.
		InOrder order = Mockito.inOrder(provider);
		order.verify(provider).getBrightness();
		order.verify(provider).setBrightness(1.0d);
		order.verify(provider).load(1);
		order.verify(provider).setBrightness(0.8d);
	}

	@Test
	public void restoresBrightnessEvenIfATextureThrowsMidBatch()
	{
		TextureProvider provider = mock(TextureProvider.class);
		when(provider.getBrightness()).thenReturn(0.8d);
		Texture[] textures = new Texture[]{mock(Texture.class), mock(Texture.class)};
		when(provider.getTextures()).thenReturn(textures);
		when(provider.load(0)).thenThrow(new RuntimeException("boom"));

		try
		{
			TextureExtractor.extractPngs(provider, setOf(0));
			org.junit.Assert.fail("expected the mocked load() failure to propagate");
		}
		catch (RuntimeException expected)
		{
			// expected -- extraction doesn't swallow a genuinely
			// unexpected RuntimeException from the provider itself
			// (unlike the documented, expected-in-practice cases --
			// missing texture, wrong-sized array -- which are handled
			// by returning null, not throwing); what this test actually
			// checks is the finally block below, not this exception.
		}

		// The whole point of the try/finally bracket: even though
		// load(0) blew up mid-batch, the player's real brightness
		// setting must still come back, not stay pinned at 1.0.
		verify(provider).setBrightness(0.8d);
	}

	@Test
	public void unknownOrOutOfRangeTextureIdIsSkippedNotFailed()
	{
		TextureProvider provider = mock(TextureProvider.class);
		when(provider.getTextures()).thenReturn(new Texture[]{null, mock(Texture.class)});

		Map<Integer, byte[]> result = TextureExtractor.extractPngs(provider, setOf(0, 1, 99, -1));

		assertTrue("id 0 has a null Texture entry -> skipped", !result.containsKey(0));
		assertTrue("id 99 is out of bounds -> skipped", !result.containsKey(99));
		assertTrue("id -1 is never a real texture id -> skipped", !result.containsKey(-1));
		// id 1 has a non-null Texture but load() isn't stubbed (returns
		// null by default), so it's skipped too -- nothing should be in
		// the result map at all here.
		assertTrue(result.isEmpty());
	}

	@Test
	public void wrongSizedPixelArrayIsSkipped()
	{
		TextureProvider provider = mock(TextureProvider.class);
		when(provider.getTextures()).thenReturn(new Texture[]{mock(Texture.class)});
		when(provider.load(0)).thenReturn(new int[]{1, 2, 3}); // not 128*128

		Map<Integer, byte[]> result = TextureExtractor.extractPngs(provider, setOf(0));

		assertTrue(result.isEmpty());
	}

	@Test
	public void nullProviderReturnsEmptyMapRatherThanThrowing()
	{
		assertTrue(TextureExtractor.extractPngs(null, setOf(1)).isEmpty());
	}

	@Test
	public void emptyRequestedIdsSkipsProviderEntirely()
	{
		TextureProvider provider = mock(TextureProvider.class);

		Map<Integer, byte[]> result = TextureExtractor.extractPngs(provider, Collections.emptySet());

		assertTrue(result.isEmpty());
		verify(provider, times(0)).getTextures();
	}

	private static TextureProvider mockProviderWithTexture(int id, int[] pixels)
	{
		TextureProvider provider = mock(TextureProvider.class);
		Texture[] textures = new Texture[id + 1];
		textures[id] = mock(Texture.class);
		when(provider.getTextures()).thenReturn(textures);
		when(provider.load(id)).thenReturn(pixels);
		return provider;
	}

	private static BufferedImage decode(byte[] png)
	{
		try
		{
			return ImageIO.read(new ByteArrayInputStream(png));
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	private static Set<Integer> setOf(int... ids)
	{
		Set<Integer> set = new HashSet<>();
		for (int id : ids)
		{
			set.add(id);
		}
		return set;
	}
}
