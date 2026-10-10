package io.myscape.plugin.modelexport;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Model;
import net.runelite.api.TextureProvider;

/**
 * Public entry point of this package: turns a live {@code Model} into a .glb byte array.
 * The other classes are package-private.
 */
@Slf4j
public final class ModelExporter
{
	private ModelExporter()
	{
	}

	/** Captures {@code model} at default brightness with no texture images. */
	public static byte[] captureGlb(Model model)
	{
		return GlbWriter.write(ModelMeshBuilder.build(model));
	}

	/** Captures {@code model} at the given brightness with no texture images. */
	public static byte[] captureGlb(Model model, double brightness)
	{
		return GlbWriter.write(ModelMeshBuilder.build(model, brightness));
	}

	/**
	 * Captures {@code model} as a self-contained .glb, embedding textures extracted from
	 * {@code textureProvider}. Must be called on the client thread.
	 *
	 * @param brightness      the client's {@code TextureProvider#getBrightness()}
	 * @param textureProvider may be null, in which case textures are omitted
	 */
	public static byte[] captureGlb(Model model, double brightness, TextureProvider textureProvider)
	{
		MeshData mesh = ModelMeshBuilder.build(model, brightness);
		Set<Integer> usedTextureIds = usedTextureIds(mesh);
		Map<Integer, byte[]> texturePngs = textureProvider != null
			? TextureExtractor.extractPngs(textureProvider, usedTextureIds)
			: Collections.emptyMap();

		// GlbWriter tags primitives whose texture is missing so the viewer can show a dim
		// fallback. Log the ids so the cause is diagnosable.
		if (textureProvider != null && texturePngs.size() < usedTextureIds.size())
		{
			Set<Integer> missing = new HashSet<>(usedTextureIds);
			missing.removeAll(texturePngs.keySet());
			log.warn("Character model export: {} of {} used texture(s) could not be extracted "
					+ "(ids {}); affected faces will render as a flat, untextured fallback.",
				missing.size(), usedTextureIds.size(), missing);
		}

		return GlbWriter.write(mesh, texturePngs);
	}

	/** Distinct texture ids used by {@code mesh}; -1 (untextured) is excluded. */
	private static Set<Integer> usedTextureIds(MeshData mesh)
	{
		Set<Integer> ids = new HashSet<>();
		for (MeshData.Primitive primitive : mesh.primitives)
		{
			if (primitive.textureId >= 0)
			{
				ids.add(primitive.textureId);
			}
		}
		return ids;
	}
}
