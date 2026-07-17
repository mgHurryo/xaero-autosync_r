package cn.net.rms.xaeromapsync_r.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

final class MerkleTreeBuilderTest {
	@Test
	void groupsLeavesInTwoByTwoParents() {
		List<MerkleNode> nodes = MerkleTreeBuilder.build(List.of(
				entry(0, 0, 10L, 1L), entry(1, 0, 20L, 2L), entry(0, 1, 30L, 3L), entry(1, 1, 40L, 4L)));
		assertEquals(5, nodes.size());
		MerkleNode root = MerkleTreeBuilder.roots(nodes).get(0);
		assertEquals(1, root.level());
		assertEquals(4, root.childCount());
	}

	@Test
	void revisionDoesNotChangeContentRoot() {
		long first = MerkleTreeBuilder.rootHash(MerkleTreeBuilder.build(List.of(entry(-1, -1, 42L, 1L))));
		long second = MerkleTreeBuilder.rootHash(MerkleTreeBuilder.build(List.of(entry(-1, -1, 42L, 99L))));
		assertEquals(first, second);
	}

	@Test
	void coordinateAndMissingSlotAffectParentHash() {
		long left = MerkleTreeBuilder.rootHash(MerkleTreeBuilder.build(List.of(entry(0, 0, 42L, 1L), entry(1, 0, 7L, 2L))));
		long right = MerkleTreeBuilder.rootHash(MerkleTreeBuilder.build(List.of(entry(0, 0, 42L, 1L), entry(0, 1, 7L, 2L))));
		assertNotEquals(left, right);
	}

	private static MapTileIndexEntry entry(int x, int z, long hash, long revision) {
		return new MapTileIndexEntry("minecraft:overworld", x, z, hash, revision, 0L);
	}
}
