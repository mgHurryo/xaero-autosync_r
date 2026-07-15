package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class MapTileIndexStoreTest {
	@Test
	void upsertCreatesInitialRevisionAndRootHash() {
		MapTileIndexStore store = new MapTileIndexStore();

		MapTileIndexEntry entry = store.upsert(tile("minecraft:overworld", 2, 3, 100L));

		assertEquals(1L, entry.revision());
		assertEquals(100L, entry.contentHash());
		assertEquals(1, store.totalCount());
		assertEquals(MerkleTreeBuilder.rootHash(store.merkleSnapshot()), store.rootHash());
	}

	@Test
	void upsertWithSameHashKeepsRevisionAndTimestamp() {
		MapTileIndexStore store = new MapTileIndexStore();
		MapTileIndexEntry first = store.upsert(tile("minecraft:overworld", 2, 3, 100L));
		long rootHash = store.rootHash();

		MapTileIndexEntry second = store.upsert(tile("minecraft:overworld", 2, 3, 100L));

		assertSame(first, second);
		assertEquals(1L, second.revision());
		assertEquals(first.updatedAtMillis(), second.updatedAtMillis());
		assertEquals(rootHash, store.rootHash());
		assertEquals(1, store.totalCount());
	}

	@Test
	void upsertWithChangedHashAdvancesRevisionAndRootHash() {
		MapTileIndexStore store = new MapTileIndexStore();
		MapTileIndexEntry first = store.upsert(tile("minecraft:overworld", 2, 3, 100L));
		long firstRootHash = store.rootHash();

		MapTileIndexEntry second = store.upsert(tile("minecraft:overworld", 2, 3, 200L));

		assertNotEquals(first, second);
		assertEquals(2L, second.revision());
		assertEquals(200L, second.contentHash());
		assertEquals(1, store.totalCount());
		assertEquals(MerkleTreeBuilder.rootHash(store.merkleSnapshot()), store.rootHash());
		assertNotEquals(firstRootHash, store.rootHash());
	}

	@Test
	void rootHashIsDeterministicAndIndependentOfInsertionOrder() {
		MapTileIndexStore store = new MapTileIndexStore();
		MapTileIndexStore reverse = new MapTileIndexStore();

		store.upsert(tile("minecraft:overworld", 0, 0, 11L));
		store.upsert(tile("minecraft:the_nether", -1, 4, 22L));
		reverse.upsert(tile("minecraft:the_nether", -1, 4, 22L));
		reverse.upsert(tile("minecraft:overworld", 0, 0, 11L));

		assertEquals(store.rootHash(), reverse.rootHash());
	}

	@Test
	void snapshotCannotMutateStore() {
		MapTileIndexStore store = new MapTileIndexStore();
		store.upsert(tile("minecraft:overworld", 0, 0, 11L));
		Collection<MapTileIndexEntry> snapshot = store.snapshot();

		org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, snapshot::clear);
		assertEquals(1, store.totalCount());
	}

	@Test
	void batchedMerkleLookupMatchesIndividualLookups() {
		MapTileIndexStore store = new MapTileIndexStore();
		for (int chunkX = 0; chunkX < 8; chunkX++) {
			store.upsert(tile("minecraft:overworld", chunkX, 0, 100L + chunkX));
		}
		List<MerkleNodeAddress> roots = store.merkleRoots("minecraft:overworld").stream()
				.filter(node -> node.level() > 0).map(MerkleNodeAddress::of).toList();
		assertFalse(roots.isEmpty());
		List<MerkleNode> expected = new ArrayList<>();
		for (MerkleNodeAddress root : roots) {
			expected.addAll(store.merkleChildren(root.dimension(), root.level(), root.nodeX(), root.nodeZ()));
		}

		assertEquals(expected.stream().map(MapTileIndexStoreTest::nodeSignature).toList(),
				store.merkleChildren(roots).stream().map(MapTileIndexStoreTest::nodeSignature).toList());
	}

	@Test
	void v5IndexUsesIndependentFileName() {
		assertEquals("map_tile_index-v5.json", MapTileIndexStore.INDEX_FILE_NAME);
	}

	private static MapTile tile(String dimension, int chunkX, int chunkZ, long contentHash) {
		return new MapTile(dimension, chunkX, chunkZ, new int[] {1, 2, 3}, contentHash);
	}

	private static String nodeSignature(MerkleNode node) {
		return node.dimension() + ':' + node.level() + ':' + node.nodeX() + ':' + node.nodeZ() + ':' + node.hash();
	}
}
