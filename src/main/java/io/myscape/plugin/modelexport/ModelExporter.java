package io.myscape.plugin.modelexport;

import net.runelite.api.Model;

/**
 * This package's one public entry point. {@link MeshData},
 * {@link ModelMeshBuilder}, and {@link GlbWriter} all stay
 * package-private -- each is already fully covered by its own test in
 * this same package (see ModelMeshBuilderTest/GlbWriterTest) -- so
 * {@code MyscapePlugin} (a different package: {@code io.myscape.plugin})
 * needs exactly one call to go from a live {@code Model} straight to a
 * ready-to-upload .glb byte array, without reaching past this
 * package's own internals or duplicating either class's own doc
 * comments at the call site.
 */
public final class ModelExporter
{
	private ModelExporter()
	{
	}

	/**
	 * Captures {@code model} at {@link HslColor#DEFAULT_BRIGHTNESS}
	 * (the client's own default TextureProvider brightness) as a
	 * self-contained .glb file. Prefer
	 * {@link #captureGlb(Model, double)} with the live client's actual
	 * {@code TextureProvider#getBrightness()} when one is available --
	 * see that overload's own doc.
	 */
	public static byte[] captureGlb(Model model)
	{
		return GlbWriter.write(ModelMeshBuilder.build(model));
	}

	/**
	 * Captures {@code model} -- the same composited mesh (base skeleton
	 * + every equipped item's own geometry + current animation pose)
	 * that {@code Renderable#getModel()} hands back for the local
	 * player -- as a self-contained .glb file ready to hand to
	 * {@code MyscapeClient#syncCharacterModel}.
	 *
	 * <p>Callers must already be on the RuneLite client thread (e.g.
	 * inside {@code ClientThread#invoke}) before calling this: {@code
	 * model}'s own vertex/face arrays are only valid to read there (see
	 * {@link ModelMeshBuilder}'s own top-of-file doc), and this method
	 * does not hop threads on a caller's behalf.
	 *
	 * @param brightness the live client's own {@code
	 *                    TextureProvider#getBrightness()} where one is
	 *                    available, so a player who's changed that
	 *                    slider from its 0.8 default sees their own
	 *                    export match (see
	 *                    {@link HslColor#DEFAULT_BRIGHTNESS}'s own doc)
	 */
	public static byte[] captureGlb(Model model, double brightness)
	{
		return GlbWriter.write(ModelMeshBuilder.build(model, brightness));
	}
}
