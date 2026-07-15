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
