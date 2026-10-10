package io.myscape.plugin.modelexport;

import java.util.ArrayList;
import java.util.List;

/** glTF-shaped result of {@link ModelMeshBuilder#build}, serialized by {@link GlbWriter}. */
final class MeshData
{
	/**
	 * A run of triangles sharing texture id, priority, bias and translucency. Priority and bias
	 * only split primitives so each carries its own glTF {@code extras}.
	 */
	static final class Primitive
	{
		/** OSRS texture id (from {@code Model#getFaceTextures}), or -1 if untextured. */
		final int textureId;
		/** True if the faces are partially transparent (alphaMode BLEND). */
		final boolean translucent;
		/** {@code Model#getFaceRenderPriorities()} value, unsigned 0-255 (0 if absent). */
		final int priority;
		/** {@code Model#getFaceBias()} value, unsigned 0-255 (0 if absent). */
		final int bias;
		/** Triangle corner indices into this mesh's vertex lists, three per triangle. */
		final List<Integer> indices = new ArrayList<>();

		Primitive(int textureId, boolean translucent, int priority, int bias)
		{
			this.textureId = textureId;
			this.translucent = translucent;
			this.priority = priority;
			this.bias = bias;
		}
	}

	/** Primitives in order of first appearance. */
	final List<Primitive> primitives = new ArrayList<>();

	/** Deduplicated {x, y, z} positions in glTF's right-handed axes (see ModelMeshBuilder.AXIS_SIGNS). */
	final List<float[]> positions = new ArrayList<>();
	/** Parallel to positions: linear {r, g, b, a}. */
	final List<float[]> colors = new ArrayList<>();
	/** Parallel to positions: {u, v}. Null for the whole list if the mesh has no textured faces. */
	List<float[]> uvs;

	/** Bounding box (required by the glTF POSITION accessor). Inverted until a vertex is added. */
	final float[] boundsMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
	final float[] boundsMax = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};

	void expandBounds(float x, float y, float z)
	{
		boundsMin[0] = Math.min(boundsMin[0], x);
		boundsMin[1] = Math.min(boundsMin[1], y);
		boundsMin[2] = Math.min(boundsMin[2], z);
		boundsMax[0] = Math.max(boundsMax[0], x);
		boundsMax[1] = Math.max(boundsMax[1], y);
		boundsMax[2] = Math.max(boundsMax[2], z);
	}
}
