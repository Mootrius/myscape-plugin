package io.myscape.plugin.modelexport;

import java.util.ArrayList;
import java.util.List;

/**
 * The plain, glTF-shaped result of {@link ModelMeshBuilder#build}: one
 * {@link Primitive} per (texture id, translucent?) pair the source
 * {@code net.runelite.api.Model} actually used, each with its own
 * deduplicated vertex buffers and triangle index list, plus the whole
 * mesh's own bounding box (glTF requires every accessor to state its
 * own min/max, and POSITION's is also what a viewer uses to frame the
 * model on load). Doesn't know anything about GLB/glTF's binary
 * layout itself -- {@link GlbWriter} is what turns this into actual
 * file bytes; this class exists so ModelMeshBuilder's own job (reading
 * the game's model format) stays entirely separate from GlbWriter's
 * (writing glTF's format), each independently testable.
 */
final class MeshData
{
	/**
	 * One glTF primitive: a run of triangles that all share the same
	 * texture id and translucency, since those are the two things that
	 * change which glTF material a triangle needs -- everything else a
	 * face carries (its per-vertex color, its position) rides along in
	 * the shared vertex buffers below rather than forcing a split of
	 * its own. A model with N different textures in view (plus
	 * possibly one more for "untextured") ends up as N (or N+1)
	 * primitives, same as RuneProfile's own documented approach -- see
	 * ModelMeshBuilder's own class doc for why that's the right split
	 * and not, say, one primitive per priority/bias value too.
	 */
	static final class Primitive
	{
		/** OSRS's own texture id (see net.runelite.api.Model#getFaceTextures），
		 * or -1 for the untextured primitive, if this mesh has one. Purely
		 * informational for GlbWriter, which uses it to decide which
		 * material/texture image this primitive's accessor should
		 * reference -- MeshData itself never resolves it to actual pixels. */
		final int textureId;
		/** Whether every face folded into this primitive was partially
		 * transparent (see ModelMeshBuilder#build's own translucency
		 * handling) -- glTF has no native "translucent" flag on a
		 * primitive, so this is GlbWriter's own cue to build this
		 * primitive's material with alphaMode: BLEND instead of OPAQUE. */
		final boolean translucent;
		/** Triangle corner indices into this MeshData's own positions/
		 * colors/uvs arrays below (NOT into the source Model's vertex
		 * array -- see VertexBuffer's own doc in ModelMeshBuilder for why
		 * those numberings differ). Always a multiple of 3. */
		final List<Integer> indices = new ArrayList<>();

		Primitive(int textureId, boolean translucent)
		{
			this.textureId = textureId;
			this.translucent = translucent;
		}
	}

	/** Every primitive this mesh needs, in the order their (textureId,
	 * translucent) key was first seen while walking the source Model's
	 * faces -- see ModelMeshBuilder#build. Deliberately not sorted or
	 * deduplicated any further than that key already guarantees. */
	final List<Primitive> primitives = new ArrayList<>();

	/** Deduplicated vertex positions, one {x, y, z} triple per vertex,
	 * already axis-converted to glTF's right-handed coordinate system
	 * (see ModelMeshBuilder.AXIS_SIGNS) -- NOT the source Model's own
	 * getVerticesX/Y/Z arrays, which are per-MODEL-vertex, not
	 * per-FACE-CORNER the way a glTF vertex needs to be once color/UV
	 * are folded in (see VertexBuffer). */
	final List<float[]> positions = new ArrayList<>();
	/** Parallel to positions -- one {r, g, b, a} quadruple per vertex,
	 * each channel already linear 0..1 (see HslColor#toLinearRgb),
	 * alpha from the owning face's own transparency (see
	 * ModelMeshBuilder#build's translucency handling; 1.0 for a face
	 * with no transparency byte at all). */
	final List<float[]> colors = new ArrayList<>();
	/** Parallel to positions -- one {u, v} pair per vertex, or null
	 * (the whole list, not per-entry) if this mesh has no textured
	 * faces at all, matching RuneProfile's own documented behavior
	 * ("UVs omitted for entirely untextured models") rather than
	 * emitting a meaningless all-zero UV set nothing will ever sample. */
	List<float[]> uvs;

	/** The whole mesh's own bounding box across every primitive's
	 * positions -- glTF's POSITION accessor requires min/max, and a
	 * glTF viewer typically also uses this to frame/center the camera
	 * on load. {Float.MAX_VALUE, ...} / {-Float.MAX_VALUE, ...} until
	 * ModelMeshBuilder#build's first vertex narrows it; a mesh with no
	 * vertices at all (never expected in practice -- a Renderable with
	 * a Model always has at least one face) would leave these at their
	 * initial, inverted values, which GlbWriter should treat as "no
	 * bounds" rather than trust literally. */
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
