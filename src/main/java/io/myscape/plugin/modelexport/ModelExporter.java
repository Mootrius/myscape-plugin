package io.myscape.plugin.modelexport;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Model;
import net.runelite.api.TextureProvider;

/**
 * This package's one public entry point. {@link MeshData},
 * {@link ModelMeshBuilder}, {@link GlbWriter}, and
 * {@link TextureExtractor} all stay package-private -- each is already
 * fully covered by its own test in this same package (see
 * ModelMeshBuilderTest/GlbWriterTest/TextureExtractorTest) -- so
 * {@code MyscapePlugin} (a different package: {@code io.myscape.plugin})
 * needs exactly one call to go from a live {@code Model} straight to a
 * ready-to-upload .glb byte array, without reaching past this
 * package's own internals or duplicating any of those classes' own doc
 * comments at the call site.
 */
public final class ModelExporter
{
	private ModelExporter()
	{
	}

	/**
	 * Captures {@code model} at {@link HslColor#DEFAULT_BRIGHTNESS}
	 * (the client's own default TextureProvider brightness), with no
	 * texture images embedded -- every textured face comes out as flat
	 * vertex-color shading only, same as this whole package used to
	 * produce exclusively before it could extract real texture pixels.
	 * Prefer {@link #captureGlb(Model, double, TextureProvider)} with
	 * the live client's actual {@code TextureProvider} whenever one is
	 * available, so a captured model's real textures (cloth patterns,
	 * etc.) show up instead -- see that overload's own doc.
	 */
	public static byte[] captureGlb(Model model)
	{
		return GlbWriter.write(ModelMeshBuilder.build(model));
	}

	/**
	 * Captures {@code model} at the given brightness, with no texture
	 * images embedded -- see {@link #captureGlb(Model)}'s own doc for
	 * why {@link #captureGlb(Model, double, TextureProvider)} is
	 * preferred whenever a live {@code TextureProvider} exists.
	 */
	public static byte[] captureGlb(Model model, double brightness)
	{
		return GlbWriter.write(ModelMeshBuilder.build(model, brightness));
	}

	/**
	 * Captures {@code model} -- the same composited mesh (base skeleton
	 * + every equipped item's own geometry + current animation pose)
	 * that {@code Renderable#getModel()} hands back for the local
	 * player -- as a self-contained .glb file ready to hand to {@code
	 * MyscapeClient#syncCharacterModel}, with every texture the model
	 * actually uses extracted from {@code textureProvider} and embedded
	 * as a real glTF image (see {@link TextureExtractor}'s own doc for
	 * exactly how) rather than left as flat vertex-color shading only.
	 *
	 * <p>Callers must already be on the RuneLite client thread (e.g.
	 * inside {@code ClientThread#invoke}) before calling this: {@code
	 * model}'s own vertex/face arrays, and {@code textureProvider}'s own
	 * pixel data, are only valid to read there (see
	 * {@link ModelMeshBuilder}'s own top-of-file doc), and this method
	 * does not hop threads on a caller's behalf.
	 *
	 * @param brightness      the live client's own {@code
	 *                        TextureProvider#getBrightness()}, so a
	 *                        player who's changed that slider from its
	 *                        0.8 default sees their own export match
	 *                        (see {@link HslColor#DEFAULT_BRIGHTNESS}'s
	 *                        own doc)
	 * @param textureProvider the live client's own {@code
	 *                        Client#getTextureProvider()}; pass null
	 *                        (or use one of the other overloads) if none
	 *                        is available, and the export falls back to
	 *                        the same flat vertex-color-only materials
	 *                        this package always used to produce
	 */
	public static byte[] captureGlb(Model model, double brightness, TextureProvider textureProvider)
	{
		MeshData mesh = ModelMeshBuilder.build(model, brightness);
		Map<Integer, byte[]> texturePngs = textureProvider != null
			? TextureExtractor.extractPngs(textureProvider, usedTextureIds(mesh))
			: Collections.emptyMap();
		return GlbWriter.write(mesh, texturePngs);
	}

	/** Every distinct OSRS texture id actually used by at least one of
	 * {@code mesh}'s own primitives -- see MeshData.Primitive's own doc
	 * for why -1 means "untextured" and is excluded here rather than
	 * asked of TextureExtractor as if it were a real id. */
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
