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
 * Pulls real texture pixel data out of a live client's own {@code
 * TextureProvider} and encodes each one as a standalone PNG, so
 * {@link GlbWriter} can embed it as a real glTF image instead of the
 * flat, untextured materials this package used to emit exclusively
 * (see GlbWriter's own top-of-file doc for that now-superseded gap).
 *
 * <p>Mirrors the exact same approach the GPU plugin's own {@code
 * net.runelite.client.plugins.gpu.TextureManager} uses to upload these
 * same textures to the graphics card (confirmed against that class's
 * real source, not guessed): {@code TextureProvider#load(int)} returns
 * a fixed 128x128 array of packed {@code 0xRRGGBB} ints with no alpha
 * channel of its own, and the single value 0 is that format's own
 * "transparent / no texture data here" marker rather than meaning
 * literal black -- {@code TextureManager.convertPixels} skips writing
 * any color for a zero pixel, leaving its destination alpha at 0, and
 * this class reproduces that exact convention rather than treating 0
 * as opaque black, so a texture that relies on it (a cutout pattern on
 * cloth, for instance) comes out right. Also mirrors that same class's
 * {@code setBrightness(1.0d)}-then-restore bracket around its own load
 * calls, for the same reason it does it there: {@code TextureProvider}
 * bakes its own current brightness setting into the pixels {@code
 * load(int)} hands back, and this package already applies the player's
 * brightness separately and correctly per-vertex (see
 * HslColor#textureLightnessToRgb, which every textured face's COLOR_0
 * already carries) -- loading texture pixels at anything other than
 * 1.0 here would double-apply that brightness on top of it, darkening
 * the final result twice instead of once.
 */
final class TextureExtractor
{
	private TextureExtractor()
	{
	}

	/** OSRS's own fixed texture resolution in high-detail mode -- the
	 * only mode a live client's TextureProvider ever hands back. Mirrors
	 * TextureManager's own identical constant and the identical guard
	 * below it (confirmed from RuneLite's real source): any load()
	 * result of a different length is treated there as "not actually
	 * usable yet" and skipped, same as here. */
	private static final int TEXTURE_SIZE = 128;

	/**
	 * Extracts and PNG-encodes every id in {@code textureIds} that
	 * {@code provider} can actually supply pixels for, skipping (never
	 * failing on) any id that turns out missing, not yet loaded, or an
	 * unexpected size -- a model should still export, just without that
	 * one texture's image, rather than aborting the whole capture over
	 * a single bad texture (matching this package's existing graceful-
	 * degradation approach elsewhere, e.g. ModelMeshBuilder's own
	 * degenerate-reference-triangle fallback).
	 *
	 * @param provider   the live client's own {@code
	 *                   Client#getTextureProvider()}; null returns an
	 *                   empty map rather than throwing, so a caller with
	 *                   no TextureProvider available can pass it through
	 *                   unconditionally
	 * @param textureIds the distinct texture ids actually referenced by
	 *                   the model being captured (see
	 *                   ModelExporter's own usedTextureIds) -- no point
	 *                   extracting a texture nothing on screen uses
	 * @return a map from texture id to that texture's PNG bytes,
	 *         containing only the ids that could actually be extracted
	 *         (may be smaller than {@code textureIds}, or empty, but
	 *         never null)
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

		// See this class's own top-of-file doc for why brightness is
		// pinned to 1.0 for exactly the duration of these load() calls
		// and restored afterward rather than left changed.
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
				// rgb == 0 is this format's own transparency marker, not
				// literal black -- see this class's own top-of-file doc.
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
			// Writing PNG bytes to an in-memory stream doesn't fail in
			// practice, but ImageIO's checked signature has to be
			// satisfied either way -- treated the same as any other
			// per-texture extraction failure: skip this one texture,
			// don't abort the rest of the capture over it.
			return null;
		}
	}
}
