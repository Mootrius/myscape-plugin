package io.myscape.plugin.modelexport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import net.runelite.api.Model;

/**
 * A synthetic {@code net.runelite.api.Model} for testing
 * {@link ModelMeshBuilder} without a live game client -- this
 * environment has no way to actually run RuneLite/log into OSRS, so
 * this is the only way to exercise the exporter's own logic at all.
 * Built as a {@link Proxy} over the real {@code Model} interface
 * (rather than a hand-written class implementing it directly) because
 * Model pulls in a long tail of methods from Mesh/Renderable/Node this
 * builder never touches (draw calls, scene bookkeeping, linked-list
 * Node pointers, ...) -- a real implementing class would need a stub
 * for every one of them just to compile, where a Proxy only needs
 * explicit handling for the handful this test actually cares about;
 * anything else falls through to a type-appropriate zero/false/null
 * default, same approach RuneProfile's own FakeModel test double
 * (which this mirrors the shape of, independently written) uses for
 * the same reason.
 */
final class FakeModel
{
	private FakeModel()
	{
	}

	static Builder builder()
	{
		return new Builder();
	}

	static final class Builder
	{
		private int faceCount;
		private float[] vx = {};
		private float[] vy = {};
		private float[] vz = {};
		private int[] i1 = {};
		private int[] i2 = {};
		private int[] i3 = {};
		private int[] c1 = {};
		private int[] c2 = {};
		private int[] c3 = {};
		private short[] faceTextures;
		private byte[] textureFaces;
		private int[] tex1 = {};
		private int[] tex2 = {};
		private int[] tex3 = {};
		private byte[] transparencies;
		private byte[] renderPriorities;
		private byte[] biases;

		Builder faceCount(int faceCount)
		{
			this.faceCount = faceCount;
			return this;
		}

		Builder vertices(float[] vx, float[] vy, float[] vz)
		{
			this.vx = vx;
			this.vy = vy;
			this.vz = vz;
			return this;
		}

		Builder faceIndices(int[] i1, int[] i2, int[] i3)
		{
			this.i1 = i1;
			this.i2 = i2;
			this.i3 = i3;
			return this;
		}

		Builder faceColors(int[] c1, int[] c2, int[] c3)
		{
			this.c1 = c1;
			this.c2 = c2;
			this.c3 = c3;
			return this;
		}

		Builder faceTextures(short[] faceTextures)
		{
			this.faceTextures = faceTextures;
			return this;
		}

		Builder textureTriangles(byte[] textureFaces, int[] tex1, int[] tex2, int[] tex3)
		{
			this.textureFaces = textureFaces;
			this.tex1 = tex1;
			this.tex2 = tex2;
			this.tex3 = tex3;
			return this;
		}

		Builder faceTransparencies(byte[] transparencies)
		{
			this.transparencies = transparencies;
			return this;
		}

		Builder faceRenderPriorities(byte[] renderPriorities)
		{
			this.renderPriorities = renderPriorities;
			return this;
		}

		Builder faceBias(byte[] biases)
		{
			this.biases = biases;
			return this;
		}

		Model build()
		{
			InvocationHandler handler = (proxy, method, args) -> {
				switch (method.getName())
				{
					case "getFaceCount":
						return faceCount;
					case "getVerticesCount":
						return vx.length;
					case "getVerticesX":
						return vx;
					case "getVerticesY":
						return vy;
					case "getVerticesZ":
						return vz;
					case "getFaceIndices1":
						return i1;
					case "getFaceIndices2":
						return i2;
					case "getFaceIndices3":
						return i3;
					case "getFaceColors1":
						return c1;
					case "getFaceColors2":
						return c2;
					case "getFaceColors3":
						return c3;
					case "getFaceTextures":
						return faceTextures;
					case "getTextureFaces":
						return textureFaces;
					case "getTexIndices1":
						return tex1;
					case "getTexIndices2":
						return tex2;
					case "getTexIndices3":
						return tex3;
					case "getFaceTransparencies":
						return transparencies;
					case "getFaceRenderPriorities":
						return renderPriorities;
					case "getFaceBias":
						return biases;
					case "equals":
						return proxy == args[0];
					case "hashCode":
						return System.identityHashCode(proxy);
					case "toString":
						return "FakeModel";
					default:
						return defaultValueFor(method.getReturnType());
				}
			};
			return (Model) Proxy.newProxyInstance(
				FakeModel.class.getClassLoader(),
				new Class<?>[]{Model.class},
				handler);
		}

		private static Object defaultValueFor(Class<?> returnType)
		{
			if (!returnType.isPrimitive())
			{
				return null;
			}
			if (returnType == boolean.class)
			{
				return false;
			}
			if (returnType == void.class)
			{
				return null;
			}
			// Every other primitive (int, byte, short, long, float,
			// double, char) widens from 0 without a cast, which is all
			// reflection's own auto(un)boxing needs here.
			return 0;
		}
	}
}
