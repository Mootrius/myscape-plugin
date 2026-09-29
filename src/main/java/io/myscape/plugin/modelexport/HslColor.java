package io.myscape.plugin.modelexport;

/**
 * Converts OSRS's own packed-HSL face colors (what
 * {@code net.runelite.api.Model#getFaceColors1/2/3} actually return)
 * into linear 0..1 RGB, so ModelMeshBuilder can bake them straight
 * into glTF vertex colors instead of exporting the raw packed ints and
 * leaving every viewer to reimplement OSRS's own color model itself.
 *
 * This is a direct line-for-line port of RuneLite's own hslToRgb GLSL
 * shader (net/runelite/client/plugins/gpu/hsl_to_rgb.glsl, bundled in
 * the runelite-client jar this plugin already depends on) -- that
 * shader is what the GPU plugin actually runs on every face color to
 * put it on screen, so matching it exactly (rather than a generic HSL
 * formula found elsewhere) is what makes an exported model's colors
 * actually match how the game itself renders that same model, packed-
 * value quirks included. Confirmed against the real shader source
 * rather than assumed from OSRS's general reputation for using packed
 * HSL -- see this plugin's own commit message for the extraction.
 *
 * The packed format itself (confirmed by the shader's own divisors):
 * a 16-bit value, hue in the high 6 bits (0..63), saturation in the
 * next 3 (0..7), lightness in the low 7 (0..127) -- i.e.
 * {@code (hue << 10) | (sat << 7) | lightness}. A textured face's own
 * "color" ints are NOT a full packed HSL value at all despite coming
 * from the same getFaceColors1/2/3 arrays -- OSRS reuses just the low
 * 7 bits (the lightness channel) as a straight 0..127 brightness
 * multiplier against that face's texture, with hue/saturation
 * meaningless noise above it for a textured face (see
 * ModelMeshBuilder#build's own textured-vs-untextured branch, and
 * RuneProfile's own documented test: "textured face lightness 127
 * expands to white"). textureLightnessToRgb below is that multiplier
 * path; toLinearRgb is the untextured, full-HSL path.
 */
final class HslColor
{
	private HslColor()
	{
	}

	/**
	 * The client's own default TextureProvider#getBrightness() --
	 * 0.8, the OSRS client's standard brightness setting (its slider's
	 * middle/default position) unless a player has actually changed
	 * it. ModelMeshBuilder takes this as a parameter rather than
	 * hardcoding it in this class specifically so a real capture
	 * (which has a live TextureProvider to ask) doesn't silently
	 * ignore a player's own brightness setting, while a synthetic test
	 * (see ModelMeshBuilderTest) can just use this default without
	 * needing to fake a whole TextureProvider for it.
	 */
	static final double DEFAULT_BRIGHTNESS = 0.8;

	/** Untextured faces: hsl is the full packed value described above.
	 * Mirrors hslToRgb(vec3 hsl) in hsl_to_rgb.glsl exactly, including
	 * its own variable names in this class's own comments below, so a
	 * side-by-side diff against the real shader is easy for whoever
	 * checks this later. Returns {r, g, b}, each already
	 * gamma-corrected by brightness (pow(., brightness) in the
	 * shader) -- NOT sRGB-encoded on top of that; glTF's own
	 * COLOR_0 accessor is defined as linear, and a correct glTF
	 * viewer applies its own display transform on top, same as it
	 * would for any other glTF file. */
	static float[] toLinearRgb(int hsl, double brightness)
	{
		double hue = (hsl >> 10 & 0x3F) / 64.0 + 0.0078125;
		double sat = (hsl >> 7 & 0x7) / 8.0 + 0.0625;
		double lum = (hsl & 0x7F);

		double var11 = lum / 128.0;
		double r = var11;
		double g = var11;
		double b = var11;

		double var19 = var11 < 0.5 ? var11 * (1.0 + sat) : var11 + sat - var11 * sat;
		double var21 = 2.0 * var11 - var19;

		double var23 = hue + (1.0 / 3.0);
		if (var23 > 1.0)
		{
			var23 -= 1.0;
		}
		double var27 = hue - (1.0 / 3.0);
		if (var27 < 0.0)
		{
			var27 += 1.0;
		}

		r = channel(var23, var19, var21);
		g = channel(hue, var19, var21);
		b = channel(var27, var19, var21);

		return new float[]{
			(float) Math.pow(clamp01(r), brightness),
			(float) Math.pow(clamp01(g), brightness),
			(float) Math.pow(clamp01(b), brightness),
		};
	}

	/** Shared by all three channels in toLinearRgb above -- the shader
	 * repeats this same six-way piecewise formula three times (once per
	 * channel, each with its own hue-like input: var23 for red, the
	 * bare hue for green, var27 for blue), so it's factored out here
	 * rather than tripled. */
	private static double channel(double h, double var19, double var21)
	{
		if (6.0 * h < 1.0)
		{
			return var21 + (var19 - var21) * 6.0 * h;
		}
		if (2.0 * h < 1.0)
		{
			return var19;
		}
		if (3.0 * h < 2.0)
		{
			return var21 + (var19 - var21) * ((2.0 / 3.0) - h) * 6.0;
		}
		return var21;
	}

	/** Textured faces: hsl's low 7 bits are a straight 0..127
	 * brightness multiplier (see this class's own top-of-file doc for
	 * why) -- 127 (the OSRS engine's own "fully lit" constant) maps to
	 * {1, 1, 1} (multiplying a texture's own texel by white leaves it
	 * unchanged), matching RuneProfile's own documented test case for
	 * this exact conversion. brightness's pow() still applies on top,
	 * same as the untextured path, since it's a display-wide gamma
	 * correction rather than something specific to hue/saturation. */
	static float[] textureLightnessToRgb(int hsl, double brightness)
	{
		float lightness = clamp01((hsl & 0x7F) / 127.0f);
		float v = (float) Math.pow(lightness, brightness);
		return new float[]{v, v, v};
	}

	private static float clamp01(double v)
	{
		if (v < 0.0)
		{
			return 0f;
		}
		if (v > 1.0)
		{
			return 1f;
		}
		return (float) v;
	}
}
