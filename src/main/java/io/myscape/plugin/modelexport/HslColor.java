package io.myscape.plugin.modelexport;

/**
 * Converts OSRS packed-HSL face colors (from {@code Model#getFaceColors1/2/3}) to linear 0..1 RGB
 * for glTF vertex colors.
 *
 * <p>The HSL conversion is a port of RuneLite's {@code hslToRgb} shader
 * (net/runelite/client/plugins/gpu/hsl_to_rgb.glsl), so exported colors match how the game renders.
 *
 * <p>Packed format: 16 bits, {@code (hue << 10) | (sat << 7) | lightness}, with hue 0..63,
 * saturation 0..7 and lightness 0..127. For textured faces only the low 7 bits are meaningful: they
 * act as a 0..127 brightness multiplier on the texture (127 expands to white).
 *
 * <p>Color space: the shader output is display-ready sRGB, but glTF COLOR_0 is defined as linear and
 * viewers apply their own sRGB encode. Results are therefore passed through {@link #srgbToLinear} so
 * the displayed pixel matches the game. Embedded textures are ordinary sRGB images and need no change.
 */
final class HslColor
{
	private HslColor()
	{
	}

	/** Default {@code TextureProvider#getBrightness()}. Callers pass the live value when available. */
	static final double DEFAULT_BRIGHTNESS = 0.8;

	/**
	 * Untextured faces: converts a full packed HSL value. Mirrors {@code hslToRgb} in
	 * hsl_to_rgb.glsl, keeping its variable names for easy comparison. Returns {r, g, b}
	 * gamma-corrected by brightness, then decoded from sRGB to linear.
	 */
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
			srgbToLinear((float) Math.pow(clamp01(r), brightness)),
			srgbToLinear((float) Math.pow(clamp01(g), brightness)),
			srgbToLinear((float) Math.pow(clamp01(b), brightness)),
		};
	}

	/** The shader's piecewise hue formula, shared by all three channels (red: var23, green: hue, blue: var27). */
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

	/**
	 * Textured faces: the low 7 bits are a 0..127 brightness multiplier. 127 (fully lit) maps to
	 * white, leaving the texture unchanged. The brightness gamma applies as for untextured faces.
	 */
	static float[] textureLightnessToRgb(int hsl, double brightness)
	{
		float lightness = clamp01((hsl & 0x7F) / 127.0f);
		float v = srgbToLinear((float) Math.pow(lightness, brightness));
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

	/** Standard piecewise sRGB-to-linear decode. See the class doc for why it is applied. */
	private static float srgbToLinear(float c)
	{
		if (c <= 0.04045f)
		{
			return c / 12.92f;
		}
		return (float) Math.pow((c + 0.055) / 1.055, 2.4);
	}
}
