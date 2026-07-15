package cn.net.rms.xaeromapsync_r.network;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class CompressionCodec {
	private static final int BUFFER_SIZE = 512;

	private CompressionCodec() {
	}

	public static byte[] encodeHeights(int[] heights, String compression) {
		ByteBuffer buffer = ByteBuffer.allocate(heights.length * Integer.BYTES);
		for (int height : heights) {
			buffer.putInt(height);
		}
		byte[] raw = buffer.array();
		if (!"zlib".equalsIgnoreCase(compression)) {
			return raw;
		}
		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		deflater.setInput(raw);
		deflater.finish();
		byte[] scratch = new byte[BUFFER_SIZE];
		ByteArrayOutputStream output = new ByteArrayOutputStream(raw.length);
		while (!deflater.finished()) {
			int count = deflater.deflate(scratch);
			output.write(scratch, 0, count);
		}
		deflater.end();
		return output.toByteArray();
	}

	public static byte[] encodeSurface(MapTileSurfaceData surface, String compression) {
		int count = surface.heights().length;
		ByteBuffer buffer = ByteBuffer.allocate(count * Integer.BYTES * 4);
		put(buffer, surface.heights());
		put(buffer, surface.blockStateIds());
		put(buffer, surface.biomeIds());
		put(buffer, surface.lightLevels());
		return compress(buffer.array(), compression);
	}

	public static MapTileSurfaceData decodeSurface(byte[] payload, int expectedCount, String compression) {
		int expectedBytes = expectedCount * Integer.BYTES * 4;
		byte[] raw = "zlib".equalsIgnoreCase(compression) ? inflate(payload, expectedBytes) : payload;
		if (raw.length != expectedBytes) throw new IllegalArgumentException("Invalid surface payload size: " + raw.length);
		ByteBuffer buffer = ByteBuffer.wrap(raw);
		return new MapTileSurfaceData(read(buffer, expectedCount), read(buffer, expectedCount), read(buffer, expectedCount), read(buffer, expectedCount));
	}

	private static void put(ByteBuffer buffer, int[] values) { for (int value : values) buffer.putInt(value); }
	private static int[] read(ByteBuffer buffer, int count) {
		int[] values = new int[count];
		for (int index = 0; index < count; index++) values[index] = buffer.getInt();
		return values;
	}

	private static byte[] compress(byte[] raw, String compression) {
		if (!"zlib".equalsIgnoreCase(compression)) return raw;
		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		deflater.setInput(raw);
		deflater.finish();
		byte[] scratch = new byte[BUFFER_SIZE];
		ByteArrayOutputStream output = new ByteArrayOutputStream(raw.length);
		while (!deflater.finished()) output.write(scratch, 0, deflater.deflate(scratch));
		deflater.end();
		return output.toByteArray();
	}

	public record MapTileSurfaceData(int[] heights, int[] blockStateIds, int[] biomeIds, int[] lightLevels) {}

	public static int[] decodeHeights(byte[] payload, int expectedCount, String compression) {
		byte[] raw = payload;
		if ("zlib".equalsIgnoreCase(compression)) {
			raw = inflate(payload, expectedCount * Integer.BYTES);
		}
		if (raw.length != expectedCount * Integer.BYTES) {
			throw new IllegalArgumentException("Invalid height payload size: " + raw.length);
		}
		ByteBuffer buffer = ByteBuffer.wrap(raw);
		int[] heights = new int[expectedCount];
		for (int index = 0; index < expectedCount; index++) {
			heights[index] = buffer.getInt();
		}
		return heights;
	}

	private static byte[] inflate(byte[] payload, int expectedSize) {
		Inflater inflater = new Inflater();
		inflater.setInput(payload);
		byte[] output = new byte[expectedSize];
		try {
			int count = inflater.inflate(output);
			if (count != expectedSize || !inflater.finished()) {
				throw new IllegalArgumentException("Invalid compressed height payload");
			}
			return output;
		} catch (DataFormatException exception) {
			throw new IllegalArgumentException("Invalid compressed height payload", exception);
		} finally {
			inflater.end();
		}
	}
}
