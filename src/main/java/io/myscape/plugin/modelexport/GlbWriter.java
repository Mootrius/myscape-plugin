package io.myscape.plugin.modelexport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes a {@link MeshData} into a binary glTF 2.0 (.glb) file: a JSON chunk plus one binary
 * chunk holding vertex, index and image bytes. The JSON is built with Gson, with no glTF library.
 *
 * <p>Textures: PNG bytes supplied per texture id (see {@link TextureExtractor}) are embedded as
 * {@code images}/{@code textures} and referenced as {@code baseColorTexture}. {@code baseColorFactor}
 * stays white and COLOR_0 (the lightness multiplier from {@link HslColor#textureLightnessToRgb})
 * modulates the sampled color, which glTF defines and KHR_materials_unlit does not change. A
 * primitive with no PNG falls back to a vertex-color-only material.
 *
 * <p>Render priority/bias: each primitive carries OSRS priority and bias as glTF {@code extras}.
 * Neither glTF nor three.js has an equivalent, so a viewer can map them to a render order.
 *
 * <p>Materials use KHR_materials_unlit because the game already bakes lighting into each face's HSL
 * color, and extra scene lighting would double-light the model.
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

	// glTF sampler enums (WebGL/OpenGL constants).
	private static final int FILTER_NEAREST = 9728;
	private static final int WRAP_REPEAT = 10497;

	/** Writes {@code mesh} with untextured materials. */
	static byte[] write(MeshData mesh)
	{
		return write(mesh, Collections.emptyMap());
	}

	/**
	 * Writes {@code mesh}, embedding textures where available.
	 *
	 * @param texturePngs PNG bytes per OSRS texture id (see {@link TextureExtractor#extractPngs});
	 *                    primitives without an entry use vertex-color-only materials
	 */
	static byte[] write(MeshData mesh, Map<Integer, byte[]> texturePngs)
	{
		BinaryBuffer bin = new BinaryBuffer();
		JsonArray bufferViews = new JsonArray();
		JsonArray accessors = new JsonArray();

		int positionAccessor = writeVec3Accessor(mesh.positions, mesh.boundsMin, mesh.boundsMax, bin, bufferViews, accessors);
		int colorAccessor = writeVec4Accessor(mesh.colors, bin, bufferViews, accessors);
		Integer uvAccessor = mesh.uvs != null ? writeVec2Accessor(mesh.uvs, bin, bufferViews, accessors) : null;

		// Unsigned-short indices address at most 65535 vertices; larger meshes need unsigned int.
		boolean useIntIndices = mesh.positions.size() > 65535;

		JsonArray primitivesJson = new JsonArray();
		JsonArray materialsJson = new JsonArray();
		Map<Long, Integer> materialIndexByKey = new LinkedHashMap<>();

		JsonArray imagesJson = new JsonArray();
		JsonArray samplersJson = new JsonArray();
		JsonArray texturesJson = new JsonArray();
		// OSRS texture id -> glTF `textures` index, so a texture shared by primitives is embedded once.
		Map<Integer, Integer> glTfTextureIndexByTextureId = new LinkedHashMap<>();

		for (MeshData.Primitive primitive : mesh.primitives)
		{
			if (primitive.indices.isEmpty())
			{
				// An empty primitive is invalid glTF (all its faces were dropped), so skip it.
				continue;
			}

			long materialKey = ((long) (primitive.textureId + 1) << 1) | (primitive.translucent ? 1 : 0);
			Integer materialIndex = materialIndexByKey.get(materialKey);
			if (materialIndex == null)
			{
				Integer glTfTextureIndex = null;
				byte[] png = primitive.textureId >= 0 ? texturePngs.get(primitive.textureId) : null;
				if (png != null)
				{
					glTfTextureIndex = glTfTextureIndexByTextureId.get(primitive.textureId);
					if (glTfTextureIndex == null)
					{
						if (samplersJson.size() == 0)
						{
							samplersJson.add(buildSampler());
						}
						int imageIndex = writeImage(png, bin, bufferViews, imagesJson);
						JsonObject textureJson = new JsonObject();
						textureJson.addProperty("sampler", 0);
						textureJson.addProperty("source", imageIndex);
						glTfTextureIndex = texturesJson.size();
						texturesJson.add(textureJson);
						glTfTextureIndexByTextureId.put(primitive.textureId, glTfTextureIndex);
					}
				}

				materialIndex = materialsJson.size();
				materialIndexByKey.put(materialKey, materialIndex);
				materialsJson.add(buildMaterial(primitive.translucent, glTfTextureIndex));
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
			// Priority/bias go in an extras object because three.js GLTFLoader copies extras to
			// userData only when they are an object.
			// missingTexture marks a primitive that wanted a texture the extractor could not
			// supply. It would otherwise render as a flat bright patch, so the viewer dims it.
			boolean missingTexture = primitive.textureId >= 0 && !texturePngs.containsKey(primitive.textureId);
			JsonObject extras = new JsonObject();
			extras.addProperty("osrsPriority", primitive.priority);
			extras.addProperty("osrsBias", primitive.bias);
			extras.addProperty("missingTexture", missingTexture);
			primitiveJson.add("extras", extras);
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
		if (imagesJson.size() > 0)
		{
			root.add("images", imagesJson);
			root.add("samplers", samplersJson);
			root.add("textures", texturesJson);
		}
		root.add("accessors", accessors);
		root.add("bufferViews", bufferViews);
		root.add("buffers", buffersJson);

		byte[] json = root.toString().getBytes(StandardCharsets.UTF_8);
		return assembleGlb(json, bin.toByteArray());
	}

	private static JsonObject buildMaterial(boolean translucent, Integer glTfTextureIndex)
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
		if (glTfTextureIndex != null)
		{
			// Added alongside baseColorFactor and COLOR_0; see class doc.
			JsonObject baseColorTexture = new JsonObject();
			baseColorTexture.addProperty("index", glTfTextureIndex);
			baseColorTexture.addProperty("texCoord", 0);
			pbr.add("baseColorTexture", baseColorTexture);
		}

		JsonObject material = new JsonObject();
		material.add("pbrMetallicRoughness", pbr);
		material.addProperty("alphaMode", translucent ? "BLEND" : "OPAQUE");
		// OSRS draws both sides of thin gear and cloth geometry.
		material.addProperty("doubleSided", true);

		JsonObject unlit = new JsonObject();
		JsonObject extensions = new JsonObject();
		extensions.add("KHR_materials_unlit", unlit);
		material.add("extensions", extensions);

		return material;
	}

	/**
	 * One sampler shared by all textures. Nearest filtering matches the game's GPU plugin and keeps
	 * texture detail crisp. Repeat wrap because UVs can fall outside 0..1 for tiled patterns.
	 */
	private static JsonObject buildSampler()
	{
		JsonObject sampler = new JsonObject();
		sampler.addProperty("magFilter", FILTER_NEAREST);
		sampler.addProperty("minFilter", FILTER_NEAREST);
		sampler.addProperty("wrapS", WRAP_REPEAT);
		sampler.addProperty("wrapT", WRAP_REPEAT);
		return sampler;
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
		// glTF requires min/max on the POSITION accessor.
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

	/** Embeds PNG bytes in the binary chunk and adds an {@code images} entry. Returns its index. */
	private static int writeImage(byte[] pngBytes, BinaryBuffer bin, JsonArray bufferViews, JsonArray imagesJson)
	{
		bin.align4();
		int byteOffset = bin.size();
		bin.writeBytes(pngBytes);
		int byteLength = pngBytes.length;

		int bufferViewIndex = bufferViews.size();
		bufferViews.add(buildImageBufferView(byteOffset, byteLength));

		JsonObject image = new JsonObject();
		image.addProperty("bufferView", bufferViewIndex);
		image.addProperty("mimeType", "image/png");
		int imageIndex = imagesJson.size();
		imagesJson.add(image);
		return imageIndex;
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

	/** Like {@link #buildBufferView} but without {@code target}, which applies only to vertex and index buffers. */
	private static JsonObject buildImageBufferView(int byteOffset, int byteLength)
	{
		JsonObject bufferView = new JsonObject();
		bufferView.addProperty("buffer", 0);
		bufferView.addProperty("byteOffset", byteOffset);
		bufferView.addProperty("byteLength", byteLength);
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
	 * Packs the chunks into a .glb: a 12-byte header (magic, version, total length), then each chunk as
	 * (length, type, data). Chunks are padded to 4-byte boundaries: JSON with spaces (0x20), binary
	 * with zeros, as the spec requires.
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

	/** Little-endian byte accumulator for the GLB binary chunk (glTF buffers are little-endian). */
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

		void writeBytes(byte[] data)
		{
			out.write(data, 0, data.length);
		}

		byte[] toByteArray()
		{
			return out.toByteArray();
		}
	}
}
