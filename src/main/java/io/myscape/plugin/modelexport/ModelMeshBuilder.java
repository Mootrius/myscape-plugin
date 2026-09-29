package io.myscape.plugin.modelexport;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Model;

/**
 * Converts a live {@code net.runelite.api.Model} -- the same composited
 * mesh (base skeleton + every equipped item's own geometry + current
 * animation pose, all already baked together by the game engine's own
 * rendering pipeline) that {@code Renderable#getModel()} hands back for
 * the local player or their follower -- into a {@link MeshData} ready
 * for {@link GlbWriter} to serialize as a real glTF/GLB file. No
 * screenshot involved anywhere in this: every number here is read
 * straight out of the client's own in-memory triangle data via
 * RuneLite's public Model API, the same inputs the game's own
 * (software or GPU) renderer already consumes to put this exact model
 * on screen -- see HslColor's own doc for why its color math is a
 * direct port of the client's own GPU shader rather than a generic HSL
 * formula, for the same "match the game exactly" reason.
 *
 * This approach -- reading the client's raw runtime mesh and
 * re-packaging it as a portable glTF file, rather than screenshotting
 * the player -- is the same one the (BSD-2-licensed, open source)
 * RuneProfile plugin uses for its own player-model feature; this class
 * is an independent implementation written directly against RuneLite's
 * public API and its own GPU shader source (see HslColor), not a copy
 * of RuneProfile's code, but it exists because reading how RuneProfile
 * described its own approach (its README, its repo's own file layout)
 * made clear this was possible and roughly how, before any of this was
 * written -- worth keeping in mind if this ever needs comparing back
 * against how they did it.
 *
 * <p><b>Render priority/bias:</b> a real OSRS face also carries a
 * render priority and a "bias" (see
 * {@code Model#getFaceRenderPriorities}/{@code getFaceBias}), which the
 * live client uses -- instead of relying on z-buffering alone -- to
 * resolve draw order for overlapping/near-coplanar faces (the classic
 * case being a cape or amulet modeled flush against the body, where
 * true 3D depth is ambiguous or intentionally coincident and the game
 * depends on this explicit ordering rather than depth to show the
 * right thing on top). This builder still leaves both out of the
 * per-vertex dedup key below (unlike RuneProfile's own documented
 * approach, which keeps them as per-vertex data without letting them
 * force a new primitive) -- untested against a real model where two
 * faces actually share a position/color/UV but differ only in
 * priority/bias, which in practice is rare enough that leaving it out
 * remains a reasonable place to cut scope rather than a reasonable
 * place to introduce a bug. Priority/bias ARE now folded into the
 * PRIMITIVE-splitting key a few lines below, though -- a separate,
 * coarser use: each (textureId, priority, bias, translucent) triangle
 * group becomes its own glTF primitive carrying that priority/bias as
 * {@code extras} (see GlbWriter), so the character-overview viewer can
 * reproduce the game's own draw-order override via {@code
 * THREE.Object3D#renderOrder} instead of trusting raw depth alone for
 * faces the game itself never trusted to raw depth either.
 *
 * <p><b>Confidence note on UVs:</b> the geometry/color/transparency
 * handling below is verified directly against RuneLite's own
 * Model/Mesh interface signatures and its own hsl_to_rgb.glsl (see
 * HslColor). The UV projection in {@link #computeFaceUv} is this
 * class's own from-first-principles implementation of the documented
 * "texture reference triangle" technique (a face's UV comes from
 * expressing its own vertex positions in the basis of a separate
 * reference triangle's two edges) -- mathematically sound and the
 * standard approach for this exact problem, but NOT yet checked
 * against a real captured model's actual on-screen texturing (this
 * environment has no live game client to compare against). Treat UVs
 * as the one part of this class that still needs a real in-game
 * before/after comparison before shipping, even though the rest is
 * solid.
 */
final class ModelMeshBuilder
{
	private ModelMeshBuilder()
	{
	}

	/** RuneLite's own coordinate convention (matching the game engine
	 * it wraps) is X east, Y down, Z north; glTF is right-handed with Y
	 * up. Negating Y and Z is a 180-degree rotation about the X axis
	 * (not a mirror -- a mirror would need an odd number of sign flips
	 * and would reverse every triangle's winding, silently flipping
	 * every face to point the wrong way), so triangle winding from the
	 * source model carries over unchanged and needs no special-casing
	 * below. */
	private static final float[] AXIS_SIGNS = {1f, -1f, -1f};

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

		// One Primitive per (textureId, translucent) pair actually seen,
		// created lazily and reused -- see MeshData.Primitive's own doc
		// for why those two are the only things that split a primitive.
		Map<Long, MeshData.Primitive> primitivesByKey = new HashMap<>();

		int faceCount = model.getFaceCount();
		for (int face = 0; face < faceCount; face++)
		{
			int transparency = transparencies != null ? (transparencies[face] & 0xFF) : 0;
			if (transparency == 255)
			{
				// Fully transparent -- the client itself never draws this
				// face at all (see RuneProfile's own documented test for
				// this exact case), so it contributes nothing to the
				// exported mesh either.
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

			// textureId+1 needs 17 bits (textureId is masked to 0xFFFF, so
			// +1 can reach 65536); priority and bias are each already
			// masked to a single byte above, so 8 bits apiece is exactly
			// enough and nothing here can collide with another face's key.
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

			float[] rgbA = textured
				? HslColor.textureLightnessToRgb(c1[face], brightness)
				: HslColor.toLinearRgb(c1[face], brightness);
			float[] rgbB = textured
				? HslColor.textureLightnessToRgb(c2[face], brightness)
				: HslColor.toLinearRgb(c2[face], brightness);
			float[] rgbC = textured
				? HslColor.textureLightnessToRgb(c3[face], brightness)
				: HslColor.toLinearRgb(c3[face], brightness);

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
	 * The "texture reference triangle" projection: OSRS doesn't store a
	 * UV per vertex the way most modern model formats do. Instead, each
	 * textured face points at a reference triangle (origin, uEnd, vEnd
	 * -- three of the model's own vertex positions) that defines where
	 * the texture's (0,0)-(1,0)-(0,1) corners sit in 3D; a face's own
	 * vertex's UV is just that vertex's position re-expressed in the
	 * 2D basis those two edges (uEnd-origin, vEnd-origin) form. Solved
	 * here via the standard cross-product projection for a point onto
	 * a triangle's own basis (equivalent to inverting the 2x2 system
	 * [e1 e2] * [s t]^T = f, done through cross products instead of a
	 * literal matrix inverse so it stays well-defined even when the
	 * point isn't exactly coplanar with the reference triangle, which
	 * in practice a face's own vertices usually are, but floating-point
	 * noise or a non-planar reference triangle shouldn't hard-fail on).
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
			// Degenerate reference triangle (its own three vertices are
			// collinear or coincident) -- no meaningful projection
			// exists. Not expected from real game data; (0,0) is a
			// harmless fallback rather than a divide-by-zero.
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
	 * Deduplicates face corners into glTF vertices, keyed on the
	 * (position, color+alpha, uv) tuple that would actually make two
	 * corners indistinguishable in the exported mesh -- NOT on the
	 * source Model's own vertex index, since two faces sharing a raw
	 * model vertex can still shade it differently (a hard edge), which
	 * must stay two separate glTF vertices to render correctly. See
	 * this class's own top-of-file doc for why priority/bias are left
	 * out of this key (a deliberate, flagged simplification) despite
	 * RuneProfile's own documented approach including them.
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
