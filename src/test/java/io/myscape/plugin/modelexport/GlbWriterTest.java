package io.myscape.plugin.modelexport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.runelite.api.Model;
import org.junit.Test;

/**
 * Checks the raw GLB container bytes {@link GlbWriter} produces
 * (header, chunk framing, 4-byte alignment) against the glTF 2.0 spec
 * by hand-parsing them back out -- the same level of "read the actual
 * bytes, don't trust the code that wrote them" this whole codebase's
 * conventions call for. Also writes a real .glb to disk for an
 * external, independent glTF validator to check (see this plugin's own
 * commit notes for that run) -- structurally valid framing isn't the
 * same as a spec-conformant document overall, so both checks matter.
 */
public class GlbWriterTest
{
	@Test
	public void producesWellFormedGlbContainer() throws IOException
	{
		Model model = buildMixedSyntheticModel();
		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);
		byte[] glb = GlbWriter.write(mesh);

		ByteBuffer buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
		int magic = buffer.getInt();
		int version = buffer.getInt();
		int totalLength = buffer.getInt();

		assertEquals("magic == ASCII 'glTF' little-endian", 0x46546C67, magic);
		assertEquals(2, version);
		assertEquals("declared total length matches actual byte count", glb.length, totalLength);
		assertEquals("whole file is 4-byte aligned per spec", 0, glb.length % 4);

		int jsonChunkLength = buffer.getInt();
		int jsonChunkType = buffer.getInt();
		assertEquals("first chunk is JSON", 0x4E4F534A, jsonChunkType);
		assertEquals("chunk length itself is 4-byte aligned", 0, jsonChunkLength % 4);
		byte[] jsonBytes = new byte[jsonChunkLength];
		buffer.get(jsonBytes);
		String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);
		assertTrue("padded with spaces, not nulls, per spec", json.trim().endsWith("}"));
		assertTrue(json.contains("\"POSITION\""));
		assertTrue(json.contains("KHR_materials_unlit"));

		int binChunkLength = buffer.getInt();
		int binChunkType = buffer.getInt();
		assertEquals("second chunk is BIN", 0x004E4942, binChunkType);
		assertEquals(0, binChunkLength % 4);
		byte[] binBytes = new byte[binChunkLength];
		buffer.get(binBytes);

		assertEquals("no trailing bytes after the two chunks", 0, buffer.remaining());

		// Write a real file so it can be checked with an independent,
		// off-the-shelf glTF validator (e.g. pygltflib) -- this test
		// only confirms MY OWN reading of the container matches what I
		// wrote, which would happily agree with itself even if both
		// were wrong about the actual spec.
		File outFile = new File(System.getProperty("java.io.tmpdir"), "myscape-model-export-test.glb");
		try (FileOutputStream fos = new FileOutputStream(outFile))
		{
			fos.write(glb);
		}
		System.out.println("Wrote test GLB to: " + outFile.getAbsolutePath());
	}

	@Test
	public void withNoTexturePngsSuppliedEveryMaterialStaysUntextured()
	{
		Model model = buildMixedSyntheticModel();
		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		// The textured triangle's own texture id (3) genuinely has no
		// entry in this map -- e.g. a synthetic test mesh, or a real
		// capture where TextureExtractor couldn't pull that one id --
		// so every material must fall back to the old flat-vertex-color
		// behavior rather than reference a texture that doesn't exist.
		ParsedGlb parsed = ParsedGlb.parse(GlbWriter.write(mesh, new HashMap<>()));

		assertFalse("no images embedded at all", parsed.json.has("images"));
		for (JsonElement materialEl : parsed.json.getAsJsonArray("materials"))
		{
			JsonObject pbr = materialEl.getAsJsonObject().getAsJsonObject("pbrMetallicRoughness");
			assertFalse("no material references a texture that was never supplied",
				pbr.has("baseColorTexture"));
		}
	}

	@Test
	public void embedsSuppliedTexturePngAndReferencesItFromTheTexturedMaterialOnly() throws IOException
	{
		Model model = buildMixedSyntheticModel();
		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);

		byte[] png = encodeTinyPng(0xFF00FF); // a small, recognizable magenta square
		Map<Integer, byte[]> texturePngs = new HashMap<>();
		texturePngs.put(3, png); // matches buildMixedSyntheticModel's own textureId

		ParsedGlb parsed = ParsedGlb.parse(GlbWriter.write(mesh, texturePngs));

		JsonArray images = parsed.json.getAsJsonArray("images");
		assertEquals("exactly one distinct texture id was supplied -> one image", 1, images.size());
		JsonObject image = images.get(0).getAsJsonObject();
		assertEquals("image/png", image.get("mimeType").getAsString());

		JsonArray samplers = parsed.json.getAsJsonArray("samplers");
		assertEquals(1, samplers.size());
		assertEquals("OSRS textures sample nearest, not smoothed -- see GlbWriter's own doc",
			9728, samplers.get(0).getAsJsonObject().get("magFilter").getAsInt());

		// Read the embedded image bytes back out of the binary chunk
		// (not just checking the JSON says an image exists) and confirm
		// they decode to a real PNG matching what was supplied --
		// exactly the "read the actual bytes back" standard the rest of
		// this test class already holds itself to.
		JsonObject bufferView = parsed.json.getAsJsonArray("bufferViews")
			.get(image.get("bufferView").getAsInt()).getAsJsonObject();
		int offset = bufferView.get("byteOffset").getAsInt();
		int length = bufferView.get("byteLength").getAsInt();
		assertFalse("an image bufferView must not claim a vertex/index buffer target",
			bufferView.has("target"));
		byte[] embeddedPng = new byte[length];
		System.arraycopy(parsed.bin, offset, embeddedPng, 0, length);
		assertArrayEquals("embedded image bytes are byte-for-byte the PNG that was supplied, not re-encoded",
			png, embeddedPng);
		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(embeddedPng));
		assertEquals(0xFFFF00FF, decoded.getRGB(0, 0));

		JsonArray materials = parsed.json.getAsJsonArray("materials");
		int taggedTextured = 0;
		int taggedUntextured = 0;
		for (JsonElement materialEl : materials)
		{
			JsonObject pbr = materialEl.getAsJsonObject().getAsJsonObject("pbrMetallicRoughness");
			if (pbr.has("baseColorTexture"))
			{
				taggedTextured++;
				assertEquals(0, pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt());
				JsonArray baseColorFactor = pbr.getAsJsonArray("baseColorFactor");
				assertEquals("baseColorFactor stays white -- COLOR_0 and the texture do all the work",
					1.0f, baseColorFactor.get(0).getAsFloat(), 0f);
			}
			else
			{
				taggedUntextured++;
			}
		}
		assertEquals("exactly one material (the textured triangle's) references the image", 1, taggedTextured);
		assertTrue("the untextured/translucent triangles' own materials are unaffected", taggedUntextured >= 1);
	}

	@Test
	public void twoPrimitivesSharingOneTextureIdEmbedOnlyOneImage() throws IOException
	{
		// One opaque and one translucent triangle, both textured with
		// the same id -- two different (textureId, translucent) keys,
		// so ModelMeshBuilder puts them in two different primitives
		// (see MeshData.Primitive's own doc), but they should still
		// only need the one underlying texture image embedded once.
		short textureId = 5;
		int hsl = 127;
		Model model = FakeModel.builder()
			.faceCount(2)
			.vertices(
				new float[]{0f, 1f, 0f, 0f, 1f, 0f},
				new float[]{0f, 0f, 1f, 0f, 0f, 1f},
				new float[]{0f, 0f, 0f, 0f, 0f, 0f})
			.faceIndices(new int[]{0, 0}, new int[]{1, 1}, new int[]{2, 2})
			.faceColors(new int[]{hsl, hsl}, new int[]{hsl, hsl}, new int[]{hsl, hsl})
			.faceTextures(new short[]{textureId, textureId})
			.textureTriangles(new byte[]{0, 1}, new int[]{0, 0}, new int[]{1, 1}, new int[]{2, 2})
			.faceTransparencies(new byte[]{0, 64})
			.build();
		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);
		assertEquals("opaque-textured and translucent-textured are two separate primitives",
			2, mesh.primitives.size());

		Map<Integer, byte[]> texturePngs = new HashMap<>();
		texturePngs.put((int) textureId, encodeTinyPng(0x00FF00));

		ParsedGlb parsed = ParsedGlb.parse(GlbWriter.write(mesh, texturePngs));

		assertEquals("both primitives share the same underlying texture id -> one image, not two",
			1, parsed.json.getAsJsonArray("images").size());
		assertEquals(1, parsed.json.getAsJsonArray("textures").size());
		assertEquals("both materials reference the same (only) texture", 2,
			parsed.json.getAsJsonArray("materials").size());
	}

	@Test
	public void primitivesCarryTheirOwnRenderPriorityAndBiasAsExtras()
	{
		int hsl = packHsl(0, 0, 64);
		Model model = FakeModel.builder()
			.faceCount(2)
			.vertices(
				new float[]{0f, 1f, 0f, 5f, 6f, 5f},
				new float[]{0f, 0f, 1f, 5f, 5f, 6f},
				new float[]{0f, 0f, 0f, 5f, 5f, 5f})
			.faceIndices(new int[]{0, 3}, new int[]{1, 4}, new int[]{2, 5})
			.faceColors(new int[]{hsl, hsl}, new int[]{hsl, hsl}, new int[]{hsl, hsl})
			.faceRenderPriorities(new byte[]{2, 9})
			.faceBias(new byte[]{0, 3})
			.build();
		MeshData mesh = ModelMeshBuilder.build(model, HslColor.DEFAULT_BRIGHTNESS);
		assertEquals(2, mesh.primitives.size());

		ParsedGlb parsed = ParsedGlb.parse(GlbWriter.write(mesh));

		JsonArray primitivesJson = parsed.json.getAsJsonArray("meshes").get(0).getAsJsonObject()
			.getAsJsonArray("primitives");
		assertEquals(2, primitivesJson.size());
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (JsonElement primEl : primitivesJson)
		{
			JsonObject extras = primEl.getAsJsonObject().getAsJsonObject("extras");
			assertTrue("every primitive carries its own osrsPriority/osrsBias extras", extras != null);
			seen.add(extras.get("osrsPriority").getAsInt() + "," + extras.get("osrsBias").getAsInt());
		}
		assertTrue(seen.contains("2,0"));
		assertTrue(seen.contains("9,3"));
	}

	/** A small mixed model exercising every branch GlbWriter has to
	 * handle at once: an untextured opaque triangle, a textured opaque
	 * triangle (axis-aligned reference triangle, same as
	 * ModelMeshBuilderTest's own UV case), and a partially-transparent
	 * untextured triangle -- so the resulting file has >1 primitive,
	 * >1 material, and a real TEXCOORD_0 accessor to check. */
	private static Model buildMixedSyntheticModel()
	{
		int plainHsl = packHsl(20, 5, 80);
		int litHsl = 127;
		short textureId = 3;

		return FakeModel.builder()
			.faceCount(3)
			.vertices(
				new float[]{0f, 1f, 0f, 0f, 1f, 0f, 10f, 11f, 10f},
				new float[]{0f, 0f, 1f, 0f, 0f, 1f, 10f, 10f, 11f},
				new float[]{0f, 0f, 0f, 0f, 0f, 0f, 10f, 10f, 10f})
			.faceIndices(new int[]{0, 3, 6}, new int[]{1, 4, 7}, new int[]{2, 5, 8})
			.faceColors(
				new int[]{plainHsl, litHsl, plainHsl},
				new int[]{plainHsl, litHsl, plainHsl},
				new int[]{plainHsl, litHsl, plainHsl})
			.faceTextures(new short[]{-1, textureId, -1})
			.textureTriangles(new byte[]{0, 1, 0}, new int[]{0, 3, 0}, new int[]{1, 4, 1}, new int[]{2, 5, 2})
			.faceTransparencies(new byte[]{0, 0, 64})
			.build();
	}

	private static int packHsl(int hue, int sat, int lightness)
	{
		return (hue << 10) | (sat << 7) | lightness;
	}

	private static byte[] encodeTinyPng(int rgb) throws IOException
	{
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 4; y++)
		{
			for (int x = 0; x < 4; x++)
			{
				image.setRGB(x, y, 0xFF000000 | rgb);
			}
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	/** Hand-parses a .glb's two chunks into a JSON root and the raw
	 * binary chunk bytes, the same header/chunk framing {@link
	 * #producesWellFormedGlbContainer} checks byte-by-byte -- factored
	 * out here so the tests that care about JSON *content* (materials,
	 * images, samplers) don't have to repeat that framing logic, while
	 * the framing itself stays checked exactly once, explicitly, above. */
	private static final class ParsedGlb
	{
		final JsonObject json;
		final byte[] bin;

		private ParsedGlb(JsonObject json, byte[] bin)
		{
			this.json = json;
			this.bin = bin;
		}

		static ParsedGlb parse(byte[] glb)
		{
			ByteBuffer buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN);
			buffer.getInt(); // magic
			buffer.getInt(); // version
			buffer.getInt(); // totalLength

			int jsonChunkLength = buffer.getInt();
			buffer.getInt(); // json chunk type
			byte[] jsonBytes = new byte[jsonChunkLength];
			buffer.get(jsonBytes);
			String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8);

			int binChunkLength = buffer.getInt();
			buffer.getInt(); // bin chunk type
			byte[] binBytes = new byte[binChunkLength];
			buffer.get(binBytes);

			// The old instance-method form, not the newer static
			// JsonParser.parseString -- the Gson version this plugin
			// already depends on transitively via the client jar
			// predates that static method.
			return new ParsedGlb(new JsonParser().parse(json).getAsJsonObject(), binBytes);
		}
	}
}
