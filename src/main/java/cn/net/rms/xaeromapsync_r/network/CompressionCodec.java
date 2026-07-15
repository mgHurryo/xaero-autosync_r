package cn.net.rms.xaeromapsync_r.network;

import cn.net.rms.xaeromapsync_r.map.MapTile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class CompressionCodec {
	private static final int SURFACE_MAGIC = 0x4d545333;
	private static final int BUFFER_SIZE = 512;
	private static final int MAX_SURFACE_BYTES = 64 * 1024;

	private CompressionCodec() {
	}

	public static byte[] encodeHeights(int[] heights, String compression) {
		ByteBuffer buffer = ByteBuffer.allocate(heights.length * Integer.BYTES);
		for (int height : heights) buffer.putInt(height);
		return compress(buffer.array(), compression);
	}

	public static byte[] encodeSurface(MapTileSurfaceData surface, String compression) {
		validateSurfaceCounts(surface);
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				output.writeInt(SURFACE_MAGIC);
				output.writeInt(MapTile.FORMAT_VERSION);
				output.writeShort(surface.baseStateIds().length);
				writeInts(output, surface.baseStateIds());
				writeInts(output, surface.baseHeights());
				writeInts(output, surface.topHeights());
				writeInts(output, surface.biomeIds());
				output.write(surface.lightAbove());
				writeBooleans(output, surface.glowing());
				writeBooleans(output, surface.cave());
				for (List<MapTile.Overlay> column : surface.overlays()) {
					output.writeByte(column.size());
					for (MapTile.Overlay overlay : column) {
						output.writeInt(overlay.blockStateId());
						output.writeFloat(overlay.transparency());
						output.writeByte(overlay.lightAbove());
						output.writeByte(overlay.glowing() ? 1 : 0);
					}
				}
			}
			byte[] raw = bytes.toByteArray();
			if (raw.length > MAX_SURFACE_BYTES) throw new IllegalArgumentException("Surface payload is too large: " + raw.length);
			return compress(raw, compression);
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to encode map tile surface", exception);
		}
	}

	public static MapTileSurfaceData decodeSurface(byte[] payload, int expectedCount, String compression) {
		byte[] raw = "zlib".equalsIgnoreCase(compression) ? inflate(payload, MAX_SURFACE_BYTES) : payload;
		if (raw.length > MAX_SURFACE_BYTES) throw new IllegalArgumentException("Surface payload is too large: " + raw.length);
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw))) {
			if (input.readInt() != SURFACE_MAGIC) throw new IllegalArgumentException("Unsupported surface payload format");
			int formatVersion = input.readInt();
			if (formatVersion != MapTile.FORMAT_VERSION) {
				throw new IllegalArgumentException("Unsupported map tile format version: " + formatVersion);
			}
			int count = input.readUnsignedShort();
			if (count != expectedCount) throw new IllegalArgumentException("Invalid surface column count: " + count);
			int[] baseStates = readInts(input, count);
			int[] baseHeights = readInts(input, count);
			int[] topHeights = readInts(input, count);
			int[] biomes = readInts(input, count);
			byte[] lights = new byte[count];
			input.readFully(lights);
			boolean[] glowing = readBooleans(input, count);
			boolean[] cave = readBooleans(input, count);
			List<List<MapTile.Overlay>> overlays = new ArrayList<>(count);
			for (int columnIndex = 0; columnIndex < count; columnIndex++) {
				int overlayCount = input.readUnsignedByte();
				if (overlayCount > MapTile.MAX_OVERLAYS_PER_COLUMN) {
					throw new IllegalArgumentException("Invalid overlay count: " + overlayCount);
				}
				List<MapTile.Overlay> column = new ArrayList<>(overlayCount);
				for (int overlayIndex = 0; overlayIndex < overlayCount; overlayIndex++) {
					int stateId = input.readInt();
					float transparency = input.readFloat();
					byte light = input.readByte();
					int overlayGlowing = input.readUnsignedByte();
					if (overlayGlowing > 1) throw new IllegalArgumentException("Invalid overlay glowing value: " + overlayGlowing);
					column.add(new MapTile.Overlay(stateId, transparency, light, overlayGlowing == 1));
				}
				overlays.add(List.copyOf(column));
			}
			if (input.read() != -1) throw new IllegalArgumentException("Trailing surface payload data");
			return new MapTileSurfaceData(baseStates, baseHeights, topHeights, biomes, lights, glowing, cave,
					List.copyOf(overlays));
		} catch (EOFException exception) {
			throw new IllegalArgumentException("Truncated surface payload", exception);
		} catch (IOException exception) {
			throw new IllegalArgumentException("Invalid surface payload", exception);
		}
	}

	public static int[] decodeHeights(byte[] payload, int expectedCount, String compression) {
		int expectedBytes = expectedCount * Integer.BYTES;
		byte[] raw = "zlib".equalsIgnoreCase(compression) ? inflate(payload, expectedBytes) : payload;
		if (raw.length != expectedBytes) throw new IllegalArgumentException("Invalid height payload size: " + raw.length);
		ByteBuffer buffer = ByteBuffer.wrap(raw);
		int[] heights = new int[expectedCount];
		for (int index = 0; index < expectedCount; index++) heights[index] = buffer.getInt();
		return heights;
	}

	private static void validateSurfaceCounts(MapTileSurfaceData surface) {
		int count = surface.baseStateIds().length;
		if (count > 0xffff || surface.baseHeights().length != count || surface.topHeights().length != count
				|| surface.biomeIds().length != count || surface.lightAbove().length != count
				|| surface.glowing().length != count || surface.cave().length != count || surface.overlays().size() != count) {
			throw new IllegalArgumentException("Map tile surface data must have equal column counts");
		}
		for (List<MapTile.Overlay> column : surface.overlays()) {
			if (column.size() > MapTile.MAX_OVERLAYS_PER_COLUMN) {
				throw new IllegalArgumentException("Too many overlays in map tile column");
			}
		}
	}

	private static void writeInts(DataOutputStream output, int[] values) throws IOException {
		for (int value : values) output.writeInt(value);
	}

	private static int[] readInts(DataInputStream input, int count) throws IOException {
		int[] values = new int[count];
		for (int index = 0; index < count; index++) values[index] = input.readInt();
		return values;
	}

	private static void writeBooleans(DataOutputStream output, boolean[] values) throws IOException {
		for (boolean value : values) output.writeByte(value ? 1 : 0);
	}

	private static boolean[] readBooleans(DataInputStream input, int count) throws IOException {
		boolean[] values = new boolean[count];
		for (int index = 0; index < count; index++) {
			int value = input.readUnsignedByte();
			if (value > 1) throw new IllegalArgumentException("Invalid surface boolean value: " + value);
			values[index] = value == 1;
		}
		return values;
	}

	private static byte[] compress(byte[] raw, String compression) {
		if (!"zlib".equalsIgnoreCase(compression)) return raw;
		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		deflater.setInput(raw);
		deflater.finish();
		byte[] scratch = new byte[BUFFER_SIZE];
		ByteArrayOutputStream output = new ByteArrayOutputStream(raw.length);
		try {
			while (!deflater.finished()) output.write(scratch, 0, deflater.deflate(scratch));
			return output.toByteArray();
		} finally {
			deflater.end();
		}
	}

	private static byte[] inflate(byte[] payload, int maximumSize) {
		Inflater inflater = new Inflater();
		inflater.setInput(payload);
		byte[] scratch = new byte[BUFFER_SIZE];
		ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumSize, payload.length * 2));
		try {
			while (!inflater.finished()) {
				int count = inflater.inflate(scratch);
				if (count == 0) {
					if (inflater.needsInput() || inflater.needsDictionary()) {
						throw new IllegalArgumentException("Truncated compressed payload");
					}
					throw new IllegalArgumentException("Compressed payload made no progress");
				}
				if (output.size() + count > maximumSize) throw new IllegalArgumentException("Compressed payload exceeds size limit");
				output.write(scratch, 0, count);
			}
			if (inflater.getRemaining() != 0) throw new IllegalArgumentException("Trailing compressed payload data");
			return output.toByteArray();
		} catch (DataFormatException exception) {
			throw new IllegalArgumentException("Invalid compressed payload", exception);
		} finally {
			inflater.end();
		}
	}

	public record MapTileSurfaceData(int[] baseStateIds, int[] baseHeights, int[] topHeights, int[] biomeIds,
			byte[] lightAbove, boolean[] glowing, boolean[] cave, List<List<MapTile.Overlay>> overlays) {
		public static MapTileSurfaceData fromTile(MapTile tile) {
			return new MapTileSurfaceData(tile.baseStateIds(), tile.baseHeights(), tile.topHeights(), tile.biomeIds(),
					tile.lightAbove(), tile.glowing(), tile.cave(), tile.overlays());
		}
	}
}
