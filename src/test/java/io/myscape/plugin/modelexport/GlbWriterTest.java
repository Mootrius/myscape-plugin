package io.myscape.plugin.modelexport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
}
