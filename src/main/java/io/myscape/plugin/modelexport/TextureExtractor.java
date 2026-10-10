package io.myscape.plugin.modelexport;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;

/**
 * Extracts texture pixels from the client's {@code TextureProvider} and encodes each as a PNG for
 * embedding in the GLB. Follows the GPU plugin's {@code TextureManager}.
 *
 * <p>{@code TextureProvider#load(int)} returns 128x128 packed {@code 0xRRGGBB} ints with no alpha;
 * 0 marks a transparent pixel rather than black, as in {@code TextureManager.convertPixels}.
 * Brightness is set to 1.0 around the load calls because the provider bakes its brightness into
 * the pixels, and the player's brightness is already applied per vertex (see
 * {@link HslColor#textureLightnessToRgb}).
 */
final class TextureExtractor
{
	private TextureExtractor()
	{
	}

	/** Texture width and height. Any {@code load()} result of another length is skipped, as in {@code TextureManager}. */
	private static final int TEXTURE_SIZE = 128;

	/**
	 * PNG-encodes every id in {@code textureIds} that {@code provider} can supply. Missing,
	 * unloaded or wrongly sized textures are skipped so the export still succeeds.
	 *
	 * @param provider   the client's texture provider; null returns an empty map
	 * @param textureIds texture ids referenced by the captured model
	 * @return texture id to PNG bytes, for the ids that could be extracted; never null
	 */
	static Map<Integer, byte[]> extractPngs(TextureProvider provider, Set<Integer> textureIds)
	{
		Map<Integer, byte[]> result = new HashMap<>();
		if (provider == null || textureIds.isEmpty())
		{
			return result;
		}

		Texture[] textures = provider.getTextures();
		if (textures == null)
		{
			return result;
		}

		// Brightness is pinned to 1.0 only while loading; see class doc.
		double savedBrightness = provider.getBrightness();
		provider.setBrightness(1.0d);
		try
		{
			for (int textureId : textureIds)
			{
				byte[] png = extractOne(provider, textures, textureId);
				if (png != null)
				{
					result.put(textureId, png);
				}
			}
		}
		finally
		{
			provider.setBrightness(savedBrightness);
		}
		return result;
	}

	private static byte[] extractOne(TextureProvider provider, Texture[] textures, int textureId)
	{
		if (textureId < 0 || textureId >= textures.length || textures[textureId] == null)
		{
			return null;
		}

		int[] srcPixels = provider.load(textureId);
		if (srcPixels == null || srcPixels.length != TEXTURE_SIZE * TEXTURE_SIZE)
		{
			return null;
		}

		BufferedImage image = new BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
		int i = 0;
		for (int y = 0; y < TEXTURE_SIZE; y++)
		{
			for (int x = 0; x < TEXTURE_SIZE; x++)
			{
				int rgb = srcPixels[i++];
				// rgb == 0 marks a transparent pixel, not black.
				int argb = rgb == 0 ? 0 : (0xFF000000 | (rgb & 0xFFFFFF));
				image.setRGB(x, y, argb);
			}
		}

		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			// Not expected for an in-memory stream; skip this texture rather than abort the capture.
			return null;
		}
	}
}
