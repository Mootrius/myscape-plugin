package io.myscape.plugin.modelexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Checks the sRGB linearization added to {@link HslColor} after a real
 * in-game comparison showed exported models too washed out / low on
 * contrast -- see that class's own top-of-file doc for the full
 * diagnosis (three.js's GLTFLoader takes COLOR_0 as already linear on
 * faith and re-encodes it for display; handing it an already display-
 * space value double-encodes it). The core property under test: a
 * standard sRGB *encode* applied on top of what this class now returns
 * must reproduce the exact display-space value the client's own shader
 * would have shown -- i.e. encode(HslColor's output) == the old,
 * pre-fix value -- since that round trip is the entire point of the
 * fix and is exactly what a spec-correct glTF viewer does at render
 * time.
 */
public class HslColorTest
{
	private static final float EPS = 1e-4f;

	@Test
	public void textureLightnessFullyLit_staysExactlyWhite()
	{
		// 127 (fully lit) is a fixed point of the sRGB curve at both
		// ends (0 -> 0, 1 -> 1 always), so this boundary case is
		// unaffected by the fix and should still be exactly white --
		// matching HslColor's own documented "127 expands to white"
		// contract that ModelMeshBuilderTest also relies on.
		float[] rgb = HslColor.textureLightnessToRgb(127, HslColor.DEFAULT_BRIGHTNESS);
		assertEquals(1f, rgb[0], EPS);
		assertEquals(1f, rgb[1], EPS);
		assertEquals(1f, rgb[2], EPS);
	}

	@Test
	public void textureLightnessZero_staysExactlyBlack()
	{
		float[] rgb = HslColor.textureLightnessToRgb(0, HslColor.DEFAULT_BRIGHTNESS);
		assertEquals(0f, rgb[0], EPS);
		assertEquals(0f, rgb[1], EPS);
		assertEquals(0f, rgb[2], EPS);
	}

	@Test
	public void textureLightness_roundTripsThroughStandardSrgbEncodeBackToTheDisplayValue()
	{
		// A handful of non-boundary lightness/brightness combinations --
		// each checks that re-applying the standard sRGB OETF (the exact
		// inverse of the EOTF HslColor now applies) on top of HslColor's
		// new output reproduces the old, pre-fix "pow(lightness, brightness)"
		// display-space value HslColor's own doc says the client's shader
		// actually puts on screen.
		int[] lightnesses = {1, 32, 64, 90, 120, 126};
		double[] brightnesses = {0.5, HslColor.DEFAULT_BRIGHTNESS, 1.0};

		for (int lightness : lightnesses)
		{
			for (double brightness : brightnesses)
			{
				float displayValue = (float) Math.pow(lightness / 127.0, brightness);
				float linear = HslColor.textureLightnessToRgb(lightness, brightness)[0];
				assertEquals(
					"lightness=" + lightness + " brightness=" + brightness,
					displayValue, srgbEncode(linear), 1e-3f);
			}
		}
	}

	@Test
	public void textureLightness_isMonotonicallyIncreasing()
	{
		// The fix recalibrates the space these values live in, but must
		// not reorder them relative to each other -- a brighter source
		// lightness must still come out brighter after the fix, or every
		// face's shading relative to its neighbors would be scrambled.
		float previous = -1f;
		for (int lightness = 0; lightness <= 127; lightness++)
		{
			float v = HslColor.textureLightnessToRgb(lightness, HslColor.DEFAULT_BRIGHTNESS)[0];
			assertTrue("lightness " + lightness + " should be >= the previous step", v >= previous);
			previous = v;
		}
	}

	@Test
	public void toLinearRgb_everyChannelStaysInZeroToOneRange()
	{
		// Sweep every hue/sat/lightness combination is overkill, but a
		// representative spread across the packed HSL space catches a
		// clamp/overflow bug in either the existing HSL math or the new
		// sRGB step wrapped around it.
		for (int hue = 0; hue < 64; hue += 9)
		{
			for (int sat = 0; sat < 8; sat += 3)
			{
				for (int lightness = 0; lightness < 128; lightness += 17)
				{
					int hsl = (hue << 10) | (sat << 7) | lightness;
					float[] rgb = HslColor.toLinearRgb(hsl, HslColor.DEFAULT_BRIGHTNESS);
					for (float channel : rgb)
					{
						assertTrue("hsl=" + hsl + " channel=" + channel, channel >= 0f && channel <= 1f);
					}
				}
			}
		}
	}

	/** The standard sRGB OETF (the "encode from linear" direction) --
	 * the exact inverse of the private HslColor#srgbToLinear this test
	 * is checking against, reimplemented here rather than reflectively
	 * reaching into HslColor's own private method, so the test is an
	 * independent check of the round trip rather than the same code
	 * calling itself. */
	private static float srgbEncode(float linear)
	{
		if (linear <= 0.0031308f)
		{
			return linear * 12.92f;
		}
		return (float) (1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055);
	}
}
