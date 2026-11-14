package com.rong.picturewall;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

final class ConnectedFrameLayouts {
	private static final int MAX_COMPONENT_SIZE = 512;
	private static final Map<World, WorldCache> CACHES = new WeakHashMap<>();

	private ConnectedFrameLayouts() {
	}

	static synchronized Layout get(World world, BlockPos origin, String source, ImageCrop crop, Direction facing) {
		WorldCache cache = CACHES.computeIfAbsent(world, ignored -> new WorldCache());
		long timeBucket = world.getTime() / 10;
		if (cache.timeBucket != timeBucket) {
			cache.layouts.clear();
			cache.timeBucket = timeBucket;
		}

		CachedLayout cached = cache.layouts.get(origin);
		if (cached != null && cached.source.equals(source) && cached.crop.equals(crop)
				&& cached.facing == facing) {
			return cached.layout;
		}

		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		HashSet<BlockPos> visited = new HashSet<>();
		ArrayList<BlockPos> component = new ArrayList<>();
		queue.add(origin.toImmutable());
		int minX = origin.getX();
		int maxX = minX;
		int minY = origin.getY();
		int maxY = minY;
		int minZ = origin.getZ();
		int maxZ = minZ;

		Direction[] neighbors = facing.getAxis() == Direction.Axis.Z
				? new Direction[]{Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN}
				: new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN};

		while (!queue.isEmpty() && component.size() < MAX_COMPONENT_SIZE) {
			BlockPos pos = queue.removeFirst();
			if (!visited.add(pos) || !matches(world, pos, source, crop, facing)) {
				continue;
			}
			component.add(pos);
			minX = Math.min(minX, pos.getX());
			maxX = Math.max(maxX, pos.getX());
			minY = Math.min(minY, pos.getY());
			maxY = Math.max(maxY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxZ = Math.max(maxZ, pos.getZ());
			for (Direction direction : neighbors) {
				BlockPos next = pos.offset(direction);
				if (world.isChunkLoaded(next) && !visited.contains(next)) {
					queue.addLast(next.toImmutable());
				}
			}
		}

		if (!queue.isEmpty() || component.isEmpty()) {
			Layout single = Layout.single(origin);
			cache.layouts.put(origin.toImmutable(), new CachedLayout(source, crop, facing, single));
			return single;
		}

		Layout layout = new Layout(minX, maxX, minY, maxY, minZ, maxZ, new HashSet<>(component));
		CachedLayout entry = new CachedLayout(source, crop, facing, layout);
		for (BlockPos pos : component) {
			cache.layouts.put(pos, entry);
		}
		return layout;
	}

	private static boolean matches(World world, BlockPos pos, String source, ImageCrop crop, Direction facing) {
		BlockState state = world.getBlockState(pos);
		if (!state.isOf(PictureWallMod.PICTURE_FRAME) || state.get(PictureFrameBlock.FACING) != facing) {
			return false;
		}
		BlockEntity blockEntity = world.getBlockEntity(pos);
		return blockEntity instanceof PictureFrameBlockEntity frame
				&& frame.getSource().equals(source)
				&& frame.getCrop().equals(crop);
	}

	private static final class WorldCache {
		private long timeBucket = Long.MIN_VALUE;
		private final Map<BlockPos, CachedLayout> layouts = new HashMap<>();
	}

	private record CachedLayout(String source, ImageCrop crop, Direction facing, Layout layout) {
	}

	record Layout(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, Set<BlockPos> members) {
		Layout {
			members = Set.copyOf(members);
		}

		static Layout single(BlockPos pos) {
			BlockPos immutablePos = pos.toImmutable();
			return new Layout(immutablePos.getX(), immutablePos.getX(), immutablePos.getY(), immutablePos.getY(),
					immutablePos.getZ(), immutablePos.getZ(), Set.of(immutablePos));
		}

		boolean contains(BlockPos pos) {
			return members.contains(pos);
		}

		int width(Direction facing) {
			return facing.getAxis() == Direction.Axis.Z ? maxX - minX + 1 : maxZ - minZ + 1;
		}

		int height() {
			return maxY - minY + 1;
		}

		int column(BlockPos pos, Direction facing) {
			return switch (facing) {
				case NORTH -> maxX - pos.getX();
				case SOUTH -> pos.getX() - minX;
				case EAST -> maxZ - pos.getZ();
				case WEST -> pos.getZ() - minZ;
				default -> 0;
			};
		}

		int row(BlockPos pos) {
			return maxY - pos.getY();
		}
	}
}
