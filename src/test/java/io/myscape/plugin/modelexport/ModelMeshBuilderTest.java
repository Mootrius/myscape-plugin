package io.myscape.plugin.modelexport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.runelite.api.Model;
import org.junit.Test;

public class ModelMeshBuilderTest
{
	private static final float EPS = 1e-4f;

	@Test
	public void singleUntexturedFace_onePrimitive_threeVertices()
	{
		// A flat, untextured triangle: (0,0,0), (1,0,0), (0,1,0) in
		// RuneLite's own X-east/Y-down/Z-north space, all sharing a
		// single flat-shaded HSL color -- the simplest possible input.
		int hsl = packHsl(32, 4, 100);
		Model model = FakeModel.builder()
			.faceCount(1)
			.vertices(new float[]{0f, 1f, 0f}, new float[]{0f, 0f, 1f}, new float[]{0f, 0f, 0f})
			.faceIndices(new int[]{0}, new int[]{1}, new int[]{2})
			.faceColors(new int[]{hsl}, new int[]{hsl}, new int[]{hsl})
			.build();

		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		assertEquals("one texture/translucency combination -> one primitive", 1, mesh.primitives.size());
		MeshData.Primitive primitive = mesh.primitives.get(0);
		assertEquals(-1, primitive.textureId);
		assertFalse(primitive.translucent);
		assertEquals("one triangle -> 3 indices", 3, primitive.indices.size());
		assertEquals("three distinct positions -> three distinct vertices, no accidental merging",
			3, mesh.positions.size());
		assertNull("no textured faces anywhere in this mesh -> no UV list at all", mesh.uvs);
	}

	@Test
	public void axisConversion_negatesYAndZ_leavesXAlone()
	{
		int hsl = packHsl(0, 0, 64);
		// Three genuinely distinct raw points on one triangle, chosen
		// so each axis's min AND max comes from a different vertex --
		// exercises the per-vertex sign flip and the running bounding
		// box together, with every value hand-checkable.
		Model model = FakeModel.builder()
			.faceCount(1)
			.vertices(new float[]{5f, -10f, 7f}, new float[]{2f, 9f, -4f}, new float[]{-1f, 3f, 8f})
			.faceIndices(new int[]{0}, new int[]{1}, new int[]{2})
			.faceColors(new int[]{hsl}, new int[]{hsl}, new int[]{hsl})
			.build();

		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		// First vertex added is raw vertex 0: (5, 2, -1) -> (5, -2, 1).
		float[] p0 = mesh.positions.get(0);
		assertEquals("X unchanged", 5f, p0[0], EPS);
		assertEquals("Y negated (game Y-down -> glTF Y-up)", -2f, p0[1], EPS);
		assertEquals("Z negated", 1f, p0[2], EPS);

		// Converted points: (5,-2,1), (-10,-9,-3), (7,4,-8).
		assertEquals("min X", -10f, mesh.boundsMin[0], EPS);
		assertEquals("max X", 7f, mesh.boundsMax[0], EPS);
		assertEquals("min Y", -9f, mesh.boundsMin[1], EPS);
		assertEquals("max Y", 4f, mesh.boundsMax[1], EPS);
		assertEquals("min Z", -8f, mesh.boundsMin[2], EPS);
		assertEquals("max Z", 1f, mesh.boundsMax[2], EPS);
	}

	@Test
	public void sharedRawVertex_differentColorsPerFace_doesNotMerge()
	{
		// Two faces both reference raw model vertex 0, but each shades
		// it a different HSL color (a hard edge) -- these must stay two
		// separate glTF vertices, not collapse into a shared one that
		// would smear the two faces' colors together.
		int hslRed = packHsl(0, 7, 100);
		int hslBlue = packHsl(42, 7, 100);
		Model model = FakeModel.builder()
			.faceCount(2)
			.vertices(
				new float[]{0f, 1f, 0f, 2f},
				new float[]{0f, 0f, 1f, 0f},
				new float[]{0f, 0f, 0f, 1f})
			.faceIndices(new int[]{0, 0}, new int[]{1, 2}, new int[]{2, 3})
			.faceColors(
				new int[]{hslRed, hslBlue},
				new int[]{hslRed, hslBlue},
				new int[]{hslRed, hslBlue})
			.build();

		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		// 2 faces x 3 corners = 6 corners total; raw vertex 0 is shared
		// but shaded two different colors by its two faces, raw
		// vertices 1/2 are each used once, raw vertex 3 once -- nothing
		// else repeats a (position, color) pair, so all 6 stay distinct.
		assertEquals(6, mesh.positions.size());
	}

	@Test
	public void identicalCorners_acrossDifferentFaces_doMerge()
	{
		// Two faces that share ONLY raw vertex 0 (and shade it the
		// exact same color there) -- every other corner is its own
		// distinct raw vertex, so exactly one of the six corners should
		// collapse into an existing glTF vertex, not the other five.
		int hsl = packHsl(10, 3, 90);
		Model model = FakeModel.builder()
			.faceCount(2)
			.vertices(
				new float[]{0f, 1f, 0f, 2f, 2f},
				new float[]{0f, 0f, 1f, 0f, 1f},
				new float[]{0f, 0f, 0f, 0f, 0f})
			.faceIndices(new int[]{0, 0}, new int[]{1, 3}, new int[]{2, 4})
			.faceColors(
				new int[]{hsl, hsl},
				new int[]{hsl, hsl},
				new int[]{hsl, hsl})
			.build();

		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		// Corner (raw vertex 0, hsl) is identical between both faces ->
		// merges. 6 total corners minus that 1 merge = 5 distinct glTF
		// vertices (raw vertices 1, 2, 3, 4 each still their own).
		assertEquals(5, mesh.positions.size());
		// Both primitives' triangles should include the SAME index for
		// that shared corner.
		assertEquals(1, mesh.primitives.size());
		int firstFaceCornerA = mesh.primitives.get(0).indices.get(0);
		int secondFaceCornerA = mesh.primitives.get(0).indices.get(3);
		assertEquals("shared corner reused, not duplicated", firstFaceCornerA, secondFaceCornerA);
	}

	@Test
	public void fullyTransparentFace_isDropped()
	{
		int hsl = packHsl(0, 0, 64);
		Model model = FakeModel.builder()
			.faceCount(2)
			.vertices(new float[]{0f, 1f, 0f, 5f, 6f, 5f}, new float[]{0f, 0f, 1f, 5f, 5f, 6f}, new float[]{0f, 0f, 0f, 5f, 5f, 5f})
			.faceIndices(new int[]{0, 3}, new int[]{1, 4}, new int[]{2, 5})
			.faceColors(new int[]{hsl, hsl}, new int[]{hsl, hsl}, new int[]{hsl, hsl})
			.faceTransparencies(new byte[]{(byte) 255, 0})
			.build();

		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		assertEquals("only the opaque face survives -> one primitive", 1, mesh.primitives.size());
		assertFalse(mesh.primitives.get(0).translucent);
		assertEquals("only the opaque face's 3 corners", 3, mesh.positions.size());
	}

	@Test
	public void partiallyTransparentFace_marksTranslucentPrimitive_withCorrectAlpha()
	{
		int hsl = packHsl(0, 0, 64);
		int transparency = 64; // out of 255
		Model model = FakeModel.builder()
			.faceCount(1)
			.vertices(new float[]{0f, 1f, 0f}, new float[]{0f, 0f, 1f}, new float[]{0f, 0f, 0f})
			.faceIndices(new int[]{0}, new int[]{1}, new int[]{2})
			.faceColors(new int[]{hsl}, new int[]{hsl}, new int[]{hsl})
			.faceTransparencies(new byte[]{(byte) transparency})
			.build();

		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		assertTrue(mesh.primitives.get(0).translucent);
		float expectedAlpha = 1f - (transparency / 255f);
		assertEquals(expectedAlpha, mesh.colors.get(0)[3], EPS);
	}

	@Test
	public void texturedFace_splitsIntoOwnPrimitive_andComputesAxisAlignedUv()
	{
		// Reference triangle: origin (0,0,0), U axis end (1,0,0), V
		// axis end (0,1,0) -- an orthonormal, axis-aligned basis, so
		// each face vertex's UV should equal its own (x, y) coordinate
		// exactly, hand-checkable without needing the general
		// projection math to be re-derived here.
		//
		// Model vertices: 0/1/2 are the reference triangle itself;
		// 3/4/5 are the actual rendered face, at (0.5,0.3,0),
		// (1,1,0), (0,0.5,0) -- all in the same z=0 plane as the
		// reference triangle so the projection is exact, not just a
		// least-squares approximation.
		short textureId = 42;
		int litHsl = 127; // textured-face "color" is a pure lightness multiplier -- see HslColor.
		Model model = FakeModel.builder()
			.faceCount(1)
			.vertices(
				new float[]{0f, 1f, 0f, 0.5f, 1f, 0f},
				new float[]{0f, 0f, 1f, 0.3f, 1f, 0.5f},
				new float[]{0f, 0f, 0f, 0f, 0f, 0f})
			.faceIndices(new int[]{3}, new int[]{4}, new int[]{5})
			.faceColors(new int[]{litHsl}, new int[]{litHsl}, new int[]{litHsl})
			.faceTextures(new short[]{textureId})
			.textureTriangles(new byte[]{0}, new int[]{0}, new int[]{1}, new int[]{2})
			.build();

		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		assertEquals(1, mesh.primitives.size());
		assertEquals(textureId, mesh.primitives.get(0).textureId);
		assertNull("bare int, no need for the sign/width dance -- textureId already unsigned", null);

		assertEquals("mesh has a textured face -> UV list exists", 3, mesh.uvs.size());
		assertUv(mesh.uvs.get(0), 0.5f, 0.3f);
		assertUv(mesh.uvs.get(1), 1f, 1f);
		assertUv(mesh.uvs.get(2), 0f, 0.5f);

		// A fully-lit (127) textured face's color should come out as
		// white, per HslColor's own documented behavior for this case.
		for (float[] color : mesh.colors)
		{
			assertEquals(1f, color[0], EPS);
			assertEquals(1f, color[1], EPS);
			assertEquals(1f, color[2], EPS);
		}
	}

	@Test
	public void untexturedAndTexturedFaces_inSameMesh_splitIntoTwoPrimitives()
	{
		int plainHsl = packHsl(20, 5, 80);
		short textureId = 7;
		Model model = FakeModel.builder()
			.faceCount(2)
			.vertices(
				new float[]{0f, 1f, 0f, 10f, 11f, 10f},
				new float[]{0f, 0f, 1f, 10f, 10f, 11f},
				new float[]{0f, 0f, 0f, 10f, 10f, 10f})
			.faceIndices(new int[]{0, 3}, new int[]{1, 4}, new int[]{2, 5})
			.faceColors(
				new int[]{plainHsl, 100},
				new int[]{plainHsl, 100},
				new int[]{plainHsl, 100})
			// -1 for the untextured face's own faceTextures entry, a
			// real id for the textured one -- exactly how a real mixed
			// model marks "this particular face has no texture" per
			// this class's own doc.
			.faceTextures(new short[]{-1, textureId})
			.textureTriangles(new byte[]{0, 1}, new int[]{0, 3}, new int[]{1, 4}, new int[]{2, 5})
			.build();

		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		assertEquals(2, mesh.primitives.size());
		boolean sawUntextured = false;
		boolean sawTextured = false;
		for (MeshData.Primitive p : mesh.primitives)
		{
			if (p.textureId == -1)
			{
				sawUntextured = true;
			}
			if (p.textureId == textureId)
			{
				sawTextured = true;
			}
		}
		assertTrue(sawUntextured);
		assertTrue(sawTextured);
		// UV list must exist (mesh has at least one textured face) and
		// have an entry for every vertex including the untextured
		// face's own three, per MeshData's own documented contract.
		assertEquals(mesh.positions.size(), mesh.uvs.size());
	}

	@Test
	public void differentRenderPriorityOrBias_splitsIntoSeparatePrimitives_evenWithSameTextureAndTranslucency()
	{
		// Three faces, all untextured and opaque (so textureId/translucent
		// alone would put them in ONE primitive under the old key) but
		// with three different (priority, bias) combinations -- the whole
		// point of folding priority/bias into the primitive key is that
		// these must come out as three separate primitives, each able to
		// carry its own draw-order extras.
		int hsl = packHsl(0, 0, 64);
		Model model = FakeModel.builder()
			.faceCount(3)
			.vertices(
				new float[]{0f, 1f, 0f, 5f, 6f, 5f, 10f, 11f, 10f},
				new float[]{0f, 0f, 1f, 5f, 5f, 6f, 10f, 10f, 11f},
				new float[]{0f, 0f, 0f, 5f, 5f, 5f, 10f, 10f, 10f})
			.faceIndices(new int[]{0, 3, 6}, new int[]{1, 4, 7}, new int[]{2, 5, 8})
			.faceColors(
				new int[]{hsl, hsl, hsl},
				new int[]{hsl, hsl, hsl},
				new int[]{hsl, hsl, hsl})
			.faceRenderPriorities(new byte[]{0, 5, 5})
			.faceBias(new byte[]{0, 0, 9})
			.build();

		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		assertEquals("three distinct (priority, bias) pairs -> three primitives", 3, mesh.primitives.size());
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (MeshData.Primitive p : mesh.primitives)
		{
			seen.add(p.priority + "," + p.bias);
		}
		assertTrue(seen.contains("0,0"));
		assertTrue(seen.contains("5,0"));
		assertTrue(seen.contains("5,9"));
	}

	@Test
	public void nullRenderPriorityAndBiasArrays_defaultToZero()
	{
		// A model that never calls faceRenderPriorities()/faceBias() at
		// all (FakeModel's own default) must behave exactly as it did
		// before priority/bias existed -- one primitive, priority 0.
		int hsl = packHsl(0, 0, 64);
		Model model = FakeModel.builder()
			.faceCount(1)
			.vertices(new float[]{0f, 1f, 0f}, new float[]{0f, 0f, 1f}, new float[]{0f, 0f, 0f})
			.faceIndices(new int[]{0}, new int[]{1}, new int[]{2})
			.faceColors(new int[]{hsl}, new int[]{hsl}, new int[]{hsl})
			.build();

		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		assertEquals(1, mesh.primitives.size());
		assertEquals(0, mesh.primitives.get(0).priority);
		assertEquals(0, mesh.primitives.get(0).bias);
	}

	private static void assertUv(float[] uv, float expectedU, float expectedV)
	{
		assertEquals(expectedU, uv[0], EPS);
		assertEquals(expectedV, uv[1], EPS);
	}

	/** Packs (hue 0..63, sat 0..7, lightness 0..127) into OSRS's own
	 * 16-bit format -- see HslColor's own doc for the bit layout,
	 * confirmed against RuneLite's real hsl_to_rgb.glsl. */
	private static int packHsl(int hue, int sat, int lightness)
	{
		return (hue << 10) | (sat << 7) | lightness;
	}
}
