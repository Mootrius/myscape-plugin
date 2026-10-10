package io.myscape.plugin.modelexport;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Model;

/**
 * Converts a live {@code net.runelite.api.Model} (the composited mesh from
 * {@code Renderable#getModel()}) into a {@link MeshData} for {@link GlbWriter}. All data is read from
 * the client's in-memory triangle arrays through RuneLite's public Model API.
 *
 * <p>The approach of re-packaging the runtime mesh as glTF is the one used by the RuneProfile plugin
 * (BSD-2-Clause); this is an independent implementation.
 *
 * <p>Render priority and bias are not part of the per-vertex dedup key. They are part of the primitive
 * key: each (textureId, priority, bias, translucent) group becomes its own primitive carrying
 * priority/bias as {@code extras}, so a viewer can reproduce the game's draw-order override for
 * near-coplanar faces such as capes.
 */
final class ModelMeshBuilder
{
	private ModelMeshBuilder()
	{
	}

	/**
	 * RuneLite uses X east, Y down, Z north; glTF is right-handed with Y up. Negating Y and Z is a
	 * 180-degree rotation about X, not a mirror, so triangle winding is preserved.
	 */
	private static final float[] AXIS_SIGNS = {1f, -1f, -1f};

	/** Third-corner color -1 means flat shading: the whole triangle uses corner 1's color. */
	private static final int FLAT_SHADED = -1;

	/**
	 * Third-corner color -2 means the game never draws the face. Both -1 and -2 decode as
	 * near-white packed HSL, so treating -2 as a color draws a stray white triangle (seen on fire and
	 * inferno capes). Such faces are skipped.
	 */
	private static final int HIDDEN_FACE = -2;

	static MeshData build(Model model)
	{
		return build(model, HslColor.DEFAULT_BRIGHTNESS);
	}

	static MeshData build(Model model, double brightness)
	{
		MeshData mesh = new MeshData();
		VertexBuffer vertexBuffer = new VertexBuffer(mesh);

		float[] vx = model.getVerticesX();
		float[] vy = model.getVerticesY();
		float[] vz = model.getVerticesZ();

		int[] i1 = model.getFaceIndices1();
		int[] i2 = model.getFaceIndices2();
		int[] i3 = model.getFaceIndices3();

		int[] c1 = model.getFaceColors1();
		int[] c2 = model.getFaceColors2();
		int[] c3 = model.getFaceColors3();

		short[] faceTextures = model.getFaceTextures();
		byte[] textureFaces = model.getTextureFaces();
		int[] tex1 = model.getTexIndices1();
		int[] tex2 = model.getTexIndices2();
		int[] tex3 = model.getTexIndices3();

		byte[] transparencies = model.getFaceTransparencies();
		byte[] priorities = model.getFaceRenderPriorities();
		byte[] biases = model.getFaceBias();

		boolean anyTextured = faceTextures != null;
		if (anyTextured)
		{
			mesh.uvs = new java.util.ArrayList<>();
		}

		// One primitive per (textureId, priority, bias, translucent) key, created lazily.
		Map<Long, MeshData.Primitive> primitivesByKey = new HashMap<>();

		int faceCount = model.getFaceCount();
		for (int face = 0; face < faceCount; face++)
		{
			if (c3[face] == HIDDEN_FACE)
			{
				// Never drawn by the game; see HIDDEN_FACE.
				continue;
			}

			int transparency = transparencies != null ? (transparencies[face] & 0xFF) : 0;
			if (transparency == 255)
			{
				// Fully transparent faces are never drawn by the client.
				continue;
			}
			boolean translucent = transparency > 0;
			float alpha = 1f - (transparency / 255f);

			int textureId = -1;
			boolean textured = false;
			if (faceTextures != null && faceTextures[face] != -1)
			{
				textureId = faceTextures[face] & 0xFFFF;
				textured = true;
			}

			int priority = priorities != null ? (priorities[face] & 0xFF) : 0;
			int bias = biases != null ? (biases[face] & 0xFF) : 0;

			// Pack the key into a long: textureId + 1 needs 17 bits (up to 65536); priority and bias
			// are one byte each.
			long key = ((long) (textureId + 1) << 17) | ((long) priority << 9) | ((long) bias << 1) | (translucent ? 1 : 0);
			MeshData.Primitive primitive = primitivesByKey.get(key);
			if (primitive == null)
			{
				primitive = new MeshData.Primitive(textureId, translucent, priority, bias);
				primitivesByKey.put(key, primitive);
				mesh.primitives.add(primitive);
			}

			int a = i1[face];
			int b = i2[face];
			int c = i3[face];

			float[] uvA = null;
			float[] uvB = null;
			float[] uvC = null;
			if (textured)
			{
				int refFace = textureFaces != null ? (textureFaces[face] & 0xFF) : face;
				int originIdx = tex1[refFace];
				int uEndIdx = tex2[refFace];
				int vEndIdx = tex3[refFace];
				float[] origin = {vx[originIdx], vy[originIdx], vz[originIdx]};
				float[] uEnd = {vx[uEndIdx], vy[uEndIdx], vz[uEndIdx]};
				float[] vEnd = {vx[vEndIdx], vy[vEndIdx], vz[vEndIdx]};
				uvA = computeFaceUv(origin, uEnd, vEnd, vx[a], vy[a], vz[a]);
				uvB = computeFaceUv(origin, uEnd, vEnd, vx[b], vy[b], vz[b]);
				uvC = computeFaceUv(origin, uEnd, vEnd, vx[c], vy[c], vz[c]);
			}

			// Flat-shaded faces use corner 1's color for all corners; see FLAT_SHADED.
			boolean flatShaded = c3[face] == FLAT_SHADED;
			int color2 = flatShaded ? c1[face] : c2[face];
			int color3 = flatShaded ? c1[face] : c3[face];

			float[] rgbA = textured
				? HslColor.textureLightnessToRgb(c1[face], brightness)
				: HslColor.toLinearRgb(c1[face], brightness);
			float[] rgbB = textured
				? HslColor.textureLightnessToRgb(color2, brightness)
				: HslColor.toLinearRgb(color2, brightness);
			float[] rgbC = textured
				? HslColor.textureLightnessToRgb(color3, brightness)
				: HslColor.toLinearRgb(color3, brightness);

			int ia = vertexBuffer.add(vx[a], vy[a], vz[a], rgbA, alpha, uvA);
			int ib = vertexBuffer.add(vx[b], vy[b], vz[b], rgbB, alpha, uvB);
			int ic = vertexBuffer.add(vx[c], vy[c], vz[c], rgbC, alpha, uvC);

			primitive.indices.add(ia);
			primitive.indices.add(ib);
			primitive.indices.add(ic);
		}

		return mesh;
	}

	/**
	 * Texture reference triangle projection. OSRS stores no per-vertex UVs: each textured face points
	 * at a reference triangle (origin, uEnd, vEnd) whose edges span the texture's (0,0)-(1,0)-(0,1)
	 * corners. A vertex's UV is its position expressed in the basis of those two edges. Uses cross
	 * products rather than a matrix inverse so points slightly off the triangle's plane still work.
	 */
	private static float[] computeFaceUv(float[] origin, float[] uEnd, float[] vEnd, float px, float py, float pz)
	{
		float[] e1 = sub(uEnd, origin);
		float[] e2 = sub(vEnd, origin);
		float[] f = {px - origin[0], py - origin[1], pz - origin[2]};

		float[] n = cross(e1, e2);
		float nDotN = dot(n, n);
		if (nDotN < 1e-12f)
		{
			// Degenerate reference triangle: fall back to (0,0).
			return new float[]{0f, 0f};
		}

		float s = dot(cross(f, e2), n) / nDotN;
		float t = dot(cross(e1, f), n) / nDotN;
		return new float[]{s, t};
	}

	private static float[] sub(float[] a, float[] b)
	{
		return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static float[] cross(float[] a, float[] b)
	{
		return new float[]{
			a[1] * b[2] - a[2] * b[1],
			a[2] * b[0] - a[0] * b[2],
			a[0] * b[1] - a[1] * b[0],
		};
	}

	private static float dot(float[] a, float[] b)
	{
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	/**
	 * Deduplicates face corners into glTF vertices keyed on (position, color+alpha, uv). The source
	 * vertex index is not used: faces sharing a model vertex may shade it differently (hard edge)
	 * and need separate glTF vertices.
	 */
	private static final class VertexBuffer
	{
		private final MeshData mesh;
		private final Map<String, Integer> indexByKey = new HashMap<>();

		VertexBuffer(MeshData mesh)
		{
			this.mesh = mesh;
		}

		int add(float x, float y, float z, float[] rgb, float alpha, float[] uv)
		{
			float gx = x * AXIS_SIGNS[0];
			float gy = y * AXIS_SIGNS[1];
			float gz = z * AXIS_SIGNS[2];

			String key = gx + "," + gy + "," + gz + "|"
				+ rgb[0] + "," + rgb[1] + "," + rgb[2] + "," + alpha + "|"
				+ (uv != null ? uv[0] + "," + uv[1] : "none");

			Integer existing = indexByKey.get(key);
			if (existing != null)
			{
				return existing;
			}

			int index = mesh.positions.size();
			mesh.positions.add(new float[]{gx, gy, gz});
			mesh.colors.add(new float[]{rgb[0], rgb[1], rgb[2], alpha});
			if (mesh.uvs != null)
			{
				mesh.uvs.add(uv != null ? uv : new float[]{0f, 0f});
			}
			mesh.expandBounds(gx, gy, gz);
			indexByKey.put(key, index);
			return index;
		}
	}
}
