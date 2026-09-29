package io.myscape.plugin.modelexport;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes a {@link MeshData} into a real binary glTF (.glb) file --
 * the same container (a JSON chunk describing the scene/accessors, plus
 * one binary buffer chunk holding the actual vertex/index bytes, per
 * the glTF 2.0 core spec) that RuneProfile's own plugin produces and
 * that @react-three/fiber / three.js load directly, no server-side
 * conversion step needed. Written by hand against the bare glTF 2.0
 * spec (the format's small enough, and a RuneLite plugin already pulls
 * in Gson via the client -- see {@code MyscapeClient}'s own usage --
 * so no new dependency is needed just to emit some JSON) rather than
 * adapted from any existing glTF-authoring library or from
 * RuneProfile's own (unseen) source; the JSON shape itself is just
 * standard glTF, not anything RuneProfile-specific.
 *
 * <p><b>Known gap, matching ModelMeshBuilder's own flagged scope:</b>
 * every material this class emits is untextured -- {@code
 * baseColorFactor} stays white and every vertex's own COLOR_0 (from
 * {@link ModelMeshBuilder}) does all the shading. For a textured face
 * that's only the lightness multiplier {@link HslColor#textureLightnessToRgb}
 * baked in, not that face's real texture pixels, since nothing in this
 * package extracts texture images yet (see ModelMeshBuilder's own top
 * comment) -- a model that used a patterned cloth or skin texture
 * in-game will come out of this writer as a flat, correctly-lit but
 * untextured shape. Geometry, color-per-vertex, and transparency are
 * all real; only texture detail is missing.
 *
 * <p>Materials use the KHR_materials_unlit extension deliberately:
 * OSRS's own lighting is already fully baked into each face's HSL
 * color by the game engine itself (see HslColor's own doc), so a
 * physically-based renderer's own extra directional/ambient lighting
 * on top of that would double-light the model and wash out its real
 * shading. Unlit tells a compliant viewer (three.js's GLTFLoader
 * included) to render COLOR_0 * baseColorFactor as-is instead.
 */
final class GlbWriter
{
	private GlbWriter()
	{
	}

	private static final int GLB_MAGIC = 0x46546C67; // ASCII "glTF" read little-endian.
	private static final int GLB_VERSION = 2;
	private static final int CHUNK_TYPE_JSON = 0x4E4F534A; // ASCII "JSON" read little-endian.
	private static final int CHUNK_TYPE_BIN = 0x004E4942; // ASCII "BIN\0" read little-endian.
	private static final int GLB_HEADER_LENGTH = 12;
	private static final int CHUNK_HEADER_LENGTH = 8;

	private static final int COMPONENT_TYPE_UNSIGNED_SHORT = 5123;
	private static final int COMPONENT_TYPE_UNSIGNED_INT = 5125;
	private static final int COMPONENT_TYPE_FLOAT = 5126;

	private static final int TARGET_ARRAY_BUFFER = 34962;
	private static final int TARGET_ELEMENT_ARRAY_BUFFER = 34963;

	private static final Gson GSON = new Gson();

	static byte[] write(MeshData mesh)
	{
		BinaryBuffer bin = new BinaryBuffer();
		JsonArray bufferViews = new JsonArray();
		JsonArray accessors = new JsonArray();

		int positionAccessor = writeVec3Accessor(mesh.positions, mesh.boundsMin, mesh.boundsMax, bin, bufferViews, accessors);
		int colorAccessor = writeVec4Accessor(mesh.colors, bin, bufferViews, accessors);
		Integer uvAccessor = mesh.uvs != null ? writeVec2Accessor(mesh.uvs, bin, bufferViews, accessors) : null;

		// glTF's unsigned-short index type can only address 65535
		// distinct vertices; a model with more needs the wider (and
		// larger-per-index) unsigned-int type instead. A real OSRS
		// player+gear model is nowhere near this in practice, but
		// picking correctly rather than assuming keeps this from
		// silently corrupting a large model's indices.
		boolean useIntIndices = mesh.positions.size() > 65535;

		JsonArray primitivesJson = new JsonArray();
		JsonArray materialsJson = new JsonArray();
		Map<Long, Integer> materialIndexByKey = new LinkedHashMap<>();

		for (MeshData.Primitive primitive : mesh.primitives)
		{
			if (primitive.indices.isEmpty())
			{
				// Can happen if every face that would have fed this
				// primitive was fully transparent and dropped by
				// ModelMeshBuilder -- an empty glTF primitive is invalid,
				// so skip it rather than emit one.
				continue;
			}

			long materialKey = ((long) (primitive.textureId + 1) << 1) | (primitive.translucent ? 1 : 0);
			Integer materialIndex = materialIndexByKey.get(materialKey);
			if (materialIndex == null)
			{
				materialIndex = materialsJson.size();
				materialIndexByKey.put(materialKey, materialIndex);
				materialsJson.add(buildMaterial(primitive.translucent));
			}

			int indicesAccessor = writeIndicesAccessor(primitive.indices, useIntIndices, bin, bufferViews, accessors);

			JsonObject attributes = new JsonObject();
			attributes.addProperty("POSITION", positionAccessor);
			attributes.addProperty("COLOR_0", colorAccessor);
			if (uvAccessor != null)
			{
				attributes.addProperty("TEXCOORD_0", uvAccessor);
			}

			JsonObject primitiveJson = new JsonObject();
			primitiveJson.add("attributes", attributes);
			primitiveJson.addProperty("indices", indicesAccessor);
			primitiveJson.addProperty("material", materialIndex);
			primitiveJson.addProperty("mode", 4); // TRIANGLES
			primitivesJson.add(primitiveJson);
		}

		JsonObject meshJson = new JsonObject();
		meshJson.add("primitives", primitivesJson);
		JsonArray meshesJson = new JsonArray();
		meshesJson.add(meshJson);

		JsonObject nodeJson = new JsonObject();
		nodeJson.addProperty("mesh", 0);
		JsonArray nodesJson = new JsonArray();
		nodesJson.add(nodeJson);

		JsonArray sceneNodes = new JsonArray();
		sceneNodes.add(0);
		JsonObject sceneJson = new JsonObject();
		sceneJson.add("nodes", sceneNodes);
		JsonArray scenesJson = new JsonArray();
		scenesJson.add(sceneJson);

		JsonObject assetJson = new JsonObject();
		assetJson.addProperty("version", "2.0");
		assetJson.addProperty("generator", "myscape-plugin model exporter");

		JsonObject bufferJson = new JsonObject();
		bufferJson.addProperty("byteLength", bin.size());
		JsonArray buffersJson = new JsonArray();
		buffersJson.add(bufferJson);

		JsonArray extensionsUsed = new JsonArray();
		extensionsUsed.add("KHR_materials_unlit");

		JsonObject root = new JsonObject();
		root.add("asset", assetJson);
		root.add("extensionsUsed", extensionsUsed);
		root.addProperty("scene", 0);
		root.add("scenes", scenesJson);
		root.add("nodes", nodesJson);
		root.add("meshes", meshesJson);
		root.add("materials", materialsJson);
		root.add("accessors", accessors);
		root.add("bufferViews", bufferViews);
		root.add("buffers", buffersJson);

		byte[] json = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
		return assembleGlb(json, bin.toByteArray());
	}

	private static JsonObject buildMaterial(boolean translucent)
	{
		JsonObject pbr = new JsonObject();
		JsonArray baseColorFactor = new JsonArray();
		baseColorFactor.add(1.0f);
		baseColorFactor.add(1.0f);
		baseColorFactor.add(1.0f);
		baseColorFactor.add(1.0f);
		pbr.add("baseColorFactor", baseColorFactor);
		pbr.addProperty("metallicFactor", 0.0f);
		pbr.addProperty("roughnessFactor", 1.0f);

		JsonObject material = new JsonObject();
		material.add("pbrMetallicRoughness", pbr);
		material.addProperty("alphaMode", translucent ? "BLEND" : "OPAQUE");
		// OSRS's own renderer draws both sides of most thin gear/cloth
		// geometry; without this a real captured model would show
		// missing back-faces from some camera angles.
		material.addProperty("doubleSided", true);

		JsonObject unlit = new JsonObject();
		JsonObject extensions = new JsonObject();
		extensions.add("KHR_materials_unlit", unlit);
		material.add("extensions", extensions);

		return material;
	}

	private static int writeVec3Accessor(java.util.List<float[]> vectors, float[] min, float[] max,
		BinaryBuffer bin, JsonArray bufferViews, JsonArray accessors)
	{
		bin.align4();
		int byteOffset = bin.size();
		for (float[] v : vectors)
		{
			bin.writeFloat(v[0]);
			bin.writeFloat(v[1]);
			bin.writeFloat(v[2]);
		}
		int byteLength = vectors.size() * 3 * 4;

		int bufferViewIndex = bufferViews.size();
		bufferViews.add(buildBufferView(byteOffset, byteLength, TARGET_ARRAY_BUFFER));

		JsonObject accessor = new JsonObject();
		accessor.addProperty("bufferView", bufferViewIndex);
		accessor.addProperty("componentType", COMPONENT_TYPE_FLOAT);
		accessor.addProperty("count", vectors.size());
		accessor.addProperty("type", "VEC3");
		// Required by the glTF spec specifically for the accessor
		// backing a POSITION attribute (a compliant viewer uses it to
		// frame/cull without decoding every vertex first).
		accessor.add("min", floatArray(min[0], min[1], min[2]));
		accessor.add("max", floatArray(max[0], max[1], max[2]));
		int accessorIndex = accessors.size();
		accessors.add(accessor);
		return accessorIndex;
	}

	private static int writeVec4Accessor(java.util.List<float[]> vectors, BinaryBuffer bin, JsonArray bufferViews, JsonArray accessors)
	{
		bin.align4();
		int byteOffset = bin.size();
		for (float[] v : vectors)
		{
			bin.writeFloat(v[0]);
			bin.writeFloat(v[1]);
			bin.writeFloat(v[2]);
			bin.writeFloat(v[3]);
		}
		int byteLength = vectors.size() * 4 * 4;

		int bufferViewIndex = bufferViews.size();
		bufferViews.add(buildBufferView(byteOffset, byteLength, TARGET_ARRAY_BUFFER));

		JsonObject accessor = new JsonObject();
		accessor.addProperty("bufferView", bufferViewIndex);
		accessor.addProperty("componentType", COMPONENT_TYPE_FLOAT);
		accessor.addProperty("count", vectors.size());
		accessor.addProperty("type", "VEC4");
		int accessorIndex = accessors.size();
		accessors.add(accessor);
		return accessorIndex;
	}

	private static int writeVec2Accessor(java.util.List<float[]> vectors, BinaryBuffer bin, JsonArray bufferViews, JsonArray accessors)
	{
		bin.align4();
		int byteOffset = bin.size();
		for (float[] v : vectors)
		{
			bin.writeFloat(v[0]);
			bin.writeFloat(v[1]);
		}
		int byteLength = vectors.size() * 2 * 4;

		int bufferViewIndex = bufferViews.size();
		bufferViews.add(buildBufferView(byteOffset, byteLength, TARGET_ARRAY_BUFFER));

		JsonObject accessor = new JsonObject();
		accessor.addProperty("bufferView", bufferViewIndex);
		accessor.addProperty("componentType", COMPONENT_TYPE_FLOAT);
		accessor.addProperty("count", vectors.size());
		accessor.addProperty("type", "VEC2");
		int accessorIndex = accessors.size();
		accessors.add(accessor);
		return accessorIndex;
	}

	private static int writeIndicesAccessor(java.util.List<Integer> indices, boolean useIntIndices,
		BinaryBuffer bin, JsonArray bufferViews, JsonArray accessors)
	{
		bin.align4();
		int byteOffset = bin.size();
		for (int index : indices)
		{
			if (useIntIndices)
			{
				bin.writeUInt32(index);
			}
			else
			{
				bin.writeUInt16(index);
			}
		}
		int byteLength = indices.size() * (useIntIndices ? 4 : 2);

		int bufferViewIndex = bufferViews.size();
		bufferViews.add(buildBufferView(byteOffset, byteLength, TARGET_ELEMENT_ARRAY_BUFFER));

		JsonObject accessor = new JsonObject();
		accessor.addProperty("bufferView", bufferViewIndex);
		accessor.addProperty("componentType", useIntIndices ? COMPONENT_TYPE_UNSIGNED_INT : COMPONENT_TYPE_UNSIGNED_SHORT);
		accessor.addProperty("count", indices.size());
		accessor.addProperty("type", "SCALAR");
		int accessorIndex = accessors.size();
		accessors.add(accessor);
		return accessorIndex;
	}

	private static JsonObject buildBufferView(int byteOffset, int byteLength, int target)
	{
		JsonObject bufferView = new JsonObject();
		bufferView.addProperty("buffer", 0);
		bufferView.addProperty("byteOffset", byteOffset);
		bufferView.addProperty("byteLength", byteLength);
		bufferView.addProperty("target", target);
		return bufferView;
	}

	private static JsonArray floatArray(float a, float b, float c)
	{
		JsonArray array = new JsonArray();
		array.add(a);
		array.add(b);
		array.add(c);
		return array;
	}

	/**
	 * Packs a JSON chunk and a binary chunk into one .glb file per the
	 * glTF 2.0 container spec: a 12-byte header (magic, version, total
	 * length), then each chunk as its own (length, type, data) header
	 * -- the JSON chunk's data padded with ASCII spaces (0x20) and the
	 * binary chunk's with zero bytes, each to a 4-byte boundary, exactly
	 * as the spec requires so every chunk (and the whole file) stays
	 * 4-byte aligned.
	 */
	private static byte[] assembleGlb(byte[] json, byte[] bin)
	{
		byte[] jsonPadded = padTo4((json), (byte) 0x20);
		byte[] binPadded = padTo4(bin, (byte) 0x00);

		int totalLength = GLB_HEADER_LENGTH
			+ CHUNK_HEADER_LENGTH + jsonPadded.length
			+ CHUNK_HEADER_LENGTH + binPadded.length;

		ByteBuffer buffer = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(GLB_MAGIC);
		buffer.putInt(GLB_VERSION);
		buffer.putInt(totalLength);

		buffer.putInt(jsonPadded.length);
		buffer.putInt(CHUNK_TYPE_JSON);
		buffer.put(jsonPadded);

		buffer.putInt(binPadded.length);
		buffer.putInt(CHUNK_TYPE_BIN);
		buffer.put(binPadded);

		return buffer.array();
	}

	private static byte[] padTo4(byte[] data, byte padByte)
	{
		int remainder = data.length % 4;
		if (remainder == 0)
		{
			return data;
		}
		int padding = 4 - remainder;
		byte[] padded = new byte[data.length + padding];
		System.arraycopy(data, 0, padded, 0, data.length);
		for (int i = data.length; i < padded.length; i++)
		{
			padded[i] = padByte;
		}
		return padded;
	}

	/** A little-endian byte accumulator for the GLB binary chunk --
	 * plain {@link ByteArrayOutputStream} doesn't expose an endianness
	 * choice, and every multi-byte value glTF's binary buffer holds
	 * (float32 positions/colors/uvs, uint16/uint32 indices) is defined
	 * by the spec as little-endian. */
	private static final class BinaryBuffer
	{
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		int size()
		{
			return out.size();
		}

		void align4()
		{
			while (out.size() % 4 != 0)
			{
				out.write(0);
			}
		}

		void writeFloat(float v)
		{
			ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
			b.putFloat(v);
			out.writeBytes(b.array());
		}

		void writeUInt16(int v)
		{
			ByteBuffer b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
			b.putShort((short) v);
			out.writeBytes(b.array());
		}

		void writeUInt32(int v)
		{
			ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
			b.putInt(v);
			out.writeBytes(b.array());
		}

		byte[] toByteArray()
		{
			return out.toByteArray();
		}
	}
}
