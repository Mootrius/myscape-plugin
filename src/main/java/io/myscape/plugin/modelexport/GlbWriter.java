package io.myscape.plugin.modelexport;

import com.google.gson.Gson;
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
 * Serializes a {@link MeshData} into a real binary glTF (.glb) file --
 * the same container (a JSON chunk describing the scene/accessors, plus
 * one binary buffer chunk holding the actual vertex/index/image bytes,
 * per the glTF 2.0 core spec) that RuneProfile's own plugin produces and
 * that @react-three/fiber / three.js load directly, no server-side
 * conversion step needed. Written by hand against the bare glTF 2.0
 * spec (the format's small enough, and a RuneLite plugin already pulls
 * in Gson via the client -- see {@code MyscapeClient}'s own usage --
 * so no new dependency is needed just to emit some JSON) rather than
 * adapted from any existing glTF-authoring library or from
 * RuneProfile's own (unseen) source; the JSON shape itself is just
 * standard glTF, not anything RuneProfile-specific.
 *
 * <p><b>Texture images:</b> a caller that supplies real PNG bytes per
 * texture id (see {@link #write(MeshData, Map)}, and
 * {@link TextureExtractor} for how those bytes get pulled off a live
 * client) gets those images embedded as real glTF {@code images}/
 * {@code textures} and referenced from each textured primitive's own
 * material as {@code baseColorTexture} -- {@code baseColorFactor}
 * stays white and every vertex's own COLOR_0 (from
 * {@link ModelMeshBuilder}, still just the lightness multiplier {@link
 * HslColor#textureLightnessToRgb} baked in) keeps doing exactly the
 * multiplicative shading it always has, since glTF defines COLOR_0,
 * when present, as multiplied into the sampled base color same as it
 * multiplies into baseColorFactor -- true of the core metallic-
 * roughness model and unchanged by KHR_materials_unlit, which only
 * removes the lighting equation, not that modulation. A primitive
 * whose texture id has no corresponding entry in the supplied map (no
 * caller-supplied bytes at all -- see {@link #write(MeshData)} -- or
 * {@link TextureExtractor} couldn't extract that particular one) falls
 * back to the previous flat-vertex-color-only material rather than
 * failing the whole export.
 *
 * <p><b>Render priority/bias:</b> each primitive also carries its own
 * OSRS render priority/bias (see MeshData.Primitive's own doc) as a
 * glTF {@code extras} object -- glTF/three.js have no native concept
 * of OSRS's own priority-based draw ordering, so this class doesn't
 * try to resolve it into anything itself; it just carries the raw data
 * through for the character-overview viewer's own JS to turn into a
 * {@code THREE.Object3D#renderOrder} after load (see that page's own
 * comment for why -- in short, OSRS relies on this explicit ordering,
 * not depth alone, for exactly the flush-against-the-body gear -- a
 * cape, an amulet -- that a naive z-buffered render can otherwise show
 * wrong).
 *
 * <p>Materials use the KHR_materials_unlit extension deliberately:
 * OSRS's own lighting is already fully baked into each face's HSL
 * color by the game engine itself (see HslColor's own doc), so a
 * physically-based renderer's own extra directional/ambient lighting
 * on top of that would double-light the model and wash out its real
 * shading. Unlit tells a compliant viewer (three.js's GLTFLoader
 * included) to render (COLOR_0 * baseColorTexture) * baseColorFactor
 * as-is instead.
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

	// glTF sampler enums (WebGL/OpenGL's own texture-filter/wrap
	// constants, reused as-is by the glTF spec).
	private static final int FILTER_NEAREST = 9728;
	private static final int WRAP_REPEAT = 10497;

	private static final Gson GSON = new Gson();

	/** Writes {@code mesh} with every material untextured, same as
	 * before this class could embed real texture images at all -- see
	 * {@link #write(MeshData, Map)}'s own doc for why that overload is
	 * preferred whenever the caller actually has texture pixel data
	 * (e.g. a real client capture, as opposed to a synthetic test
	 * mesh). */
	static byte[] write(MeshData mesh)
	{
		return write(mesh, Collections.emptyMap());
	}

	/**
	 * @param texturePngs PNG bytes per OSRS texture id (see
	 *                    {@link TextureExtractor#extractPngs}), used to
	 *                    embed real {@code baseColorTexture} images for
	 *                    any primitive whose own texture id has an
	 *                    entry here. Pass {@link Collections#emptyMap()}
	 *                    (or use {@link #write(MeshData)}) for the old,
	 *                    flat-vertex-color-only behavior.
	 */
	static byte[] write(MeshData mesh, Map<Integer, byte[]> texturePngs)
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

		JsonArray imagesJson = new JsonArray();
		JsonArray samplersJson = new JsonArray();
		JsonArray texturesJson = new JsonArray();
		// OSRS texture id -> glTF `textures` array index, so the same
		// texture referenced by several primitives (e.g. the same cloth
		// pattern on both sleeves) is only embedded once.
		Map<Integer, Integer> glTfTextureIndexByTextureId = new LinkedHashMap<>();

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
			// OSRS's own render priority/bias for this primitive's own
			// faces (see MeshData.Primitive's own doc) -- an ordinary
			// object under glTF's spec-legal "extras" mechanism, not a
			// bare number, since three.js's GLTFLoader only copies extras
			// into userData when they're an object (confirmed against its
			// own assignExtrasToUserData, which otherwise just warns and
			// drops it). The character-overview viewer reads this back
			// off each primitive's own geometry.userData to set
			// THREE.Object3D#renderOrder -- see pages.py's own comment.
			//
			// missingTexture: true exactly when this primitive WANTED a
			// texture (textureId >= 0) but texturePngs has no PNG bytes
			// for it -- TextureExtractor couldn't pull that one id off
			// the live client for whatever reason (see its own doc for
			// the "skip, don't fail the capture" contract). Without this
			// flag such a primitive is visually indistinguishable from a
			// deliberately plain one: it falls back to COLOR_0 alone,
			// which for a textured face is only ever
			// HslColor#textureLightnessToRgb's lightness-only value (a
			// real captured model's texture data carries no hue/
			// saturation of its own -- the pattern lives entirely in the
			// missing image), so it renders as a flat, oddly bright
			// near-white/gray patch that reads as a rendering bug even
			// though it's really a missing-asset fallback working exactly
			// as designed. The viewer uses this to dim that patch instead
			// of leaving it looking broken -- see pages.py's own comment.
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

		byte[] json = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
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
			// See this class's own top-of-file doc for why
			// baseColorFactor staying white and COLOR_0's own
			// modulation need no change here -- this is purely an
			// additional reference, not a replacement for either.
			JsonObject baseColorTexture = new JsonObject();
			baseColorTexture.addProperty("index", glTfTextureIndex);
			baseColorTexture.addProperty("texCoord", 0);
			pbr.add("baseColorTexture", baseColorTexture);
		}

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

	/** One sampler, shared by every embedded texture -- OSRS's own
	 * renderer samples textures with nearest-neighbor filtering, not
	 * smoothed/bilinear (see the GPU plugin's own GL_NEAREST choice for
	 * both min/mag filters, confirmed from its real source), so
	 * matching that here keeps a captured model's texture detail crisp
	 * instead of blurring it. Repeat wrap on both axes since a face's
	 * own UV (see ModelMeshBuilder's own "texture reference triangle"
	 * doc) isn't guaranteed to stay inside 0..1 for a tiled pattern. */
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

	/** Embeds one already-encoded PNG's bytes into the binary chunk and
	 * points a new glTF {@code images} entry at them. Returns that
	 * entry's index. */
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

	/** Same as {@link #buildBufferView} but with no {@code target} --
	 * the glTF spec's bufferView.target enum only covers the vertex/
	 * index buffer roles ({@code ARRAY_BUFFER}/{@code
	 * ELEMENT_ARRAY_BUFFER}); an image bufferView is neither of those
	 * and should omit target entirely rather than pick one that doesn't
	 * really apply. */
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
	 * by the spec as little-endian. Raw image bytes (PNG) have no
	 * endianness of their own and are just copied through as-is by
	 * {@link #writeBytes}. */
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
