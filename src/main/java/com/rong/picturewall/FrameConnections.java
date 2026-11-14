package com.rong.picturewall;

import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class FrameConnections {
	private static final int MAX_GROUP_SIZE = 512;

	private FrameConnections() {
	}

	static boolean connect(ServerPlayerEntity player, World world, BlockPos firstPos, BlockPos secondPos) {
		if (!(world.getBlockEntity(firstPos) instanceof PictureFrameBlockEntity first)
				|| !(world.getBlockEntity(secondPos) instanceof PictureFrameBlockEntity second)) {
			player.sendMessage(Text.translatable("picturewall.link.invalid_frame"), true);
			return false;
		}
		BlockState firstState = world.getBlockState(firstPos);
		BlockState secondState = world.getBlockState(secondPos);
		Direction facing = firstState.get(PictureFrameBlock.FACING);
		if (!secondState.isOf(PictureWallMod.PICTURE_FRAME)
				|| secondState.get(PictureFrameBlock.FACING) != facing
				|| !areAdjacentInFramePlane(firstPos, secondPos, facing)) {
			player.sendMessage(Text.translatable("picturewall.link.not_adjacent"), true);
			return false;
		}

		Component firstComponent = collect(world, first);
		Component secondComponent = collect(world, second);
		if (firstComponent.overflow || secondComponent.overflow) {
			player.sendMessage(Text.translatable("picturewall.link.too_large"), true);
			return false;
		}
		Set<BlockPos> positions = new HashSet<>(firstComponent.positions);
		if (secondComponent.positions.stream().anyMatch(positions::contains)) {
			player.sendMessage(Text.translatable("picturewall.link.already_connected"), true);
			return false;
		}
		if (positions.size() + secondComponent.positions.size() > MAX_GROUP_SIZE) {
			player.sendMessage(Text.translatable("picturewall.link.too_large"), true);
			return false;
		}

		PictureFrameBlockEntity firstImage = findImage(firstComponent.frames);
		PictureFrameBlockEntity secondImage = findImage(secondComponent.frames);
		if (firstImage != null && secondImage != null
				&& (!firstImage.getSource().equals(secondImage.getSource())
				|| !firstImage.getCrop().equals(secondImage.getCrop()))) {
			player.sendMessage(Text.translatable("picturewall.link.image_conflict"), true);
			return false;
		}

		String group = UUID.randomUUID().toString();
		PictureFrameBlockEntity imageFrame = firstImage != null ? firstImage : secondImage;
		String source = imageFrame == null ? "" : imageFrame.getSource();
		ImageCrop crop = imageFrame == null ? ImageCrop.NONE : imageFrame.getCrop();
		List<BlockPos> members = new ArrayList<>(positions);
		members.addAll(secondComponent.positions);
		for (BlockPos pos : members) {
			if (world.getBlockEntity(pos) instanceof PictureFrameBlockEntity frame) {
				frame.setLinkGroup(group);
				frame.setImage(source, crop);
			}
		}
		player.sendMessage(Text.translatable("picturewall.link.connected", members.size()), true);
		return true;
	}

	private static PictureFrameBlockEntity findImage(List<PictureFrameBlockEntity> frames) {
		return frames.stream().filter(frame -> !frame.getSource().isEmpty()).findFirst().orElse(null);
	}

	static List<PictureFrameBlockEntity> collectLinkedFrames(World world, PictureFrameBlockEntity start) {
		return collect(world, start).frames;
	}

	private static Component collect(World world, PictureFrameBlockEntity start) {
		String group = start.getLinkGroup();
		if (group.isEmpty()) {
			BlockPos pos = start.getPos().toImmutable();
			return new Component(List.of(pos), List.of(start), false);
		}
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		Set<BlockPos> visited = new HashSet<>();
		List<BlockPos> positions = new ArrayList<>();
		List<PictureFrameBlockEntity> frames = new ArrayList<>();
		BlockPos origin = start.getPos().toImmutable();
		Direction facing = world.getBlockState(origin).get(PictureFrameBlock.FACING);
		Direction[] neighbors = facing.getAxis() == Direction.Axis.Z
				? new Direction[]{Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN}
				: new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN};
		queue.add(origin);

		while (!queue.isEmpty()) {
			BlockPos pos = queue.removeFirst();
			if (!visited.add(pos) || !world.isChunkLoaded(pos)) {
				continue;
			}
			if (!(world.getBlockEntity(pos) instanceof PictureFrameBlockEntity frame)
					|| !world.getBlockState(pos).isOf(PictureWallMod.PICTURE_FRAME)
					|| world.getBlockState(pos).get(PictureFrameBlock.FACING) != facing
					|| !frame.getLinkGroup().equals(group)) {
				continue;
			}
			positions.add(pos);
			frames.add(frame);
			if (positions.size() > MAX_GROUP_SIZE) {
				return new Component(positions, frames, true);
			}
			for (Direction direction : neighbors) {
				BlockPos next = pos.offset(direction);
				if (!visited.contains(next) && world.isChunkLoaded(next)) {
					queue.addLast(next.toImmutable());
				}
			}
		}
		return new Component(positions, frames, false);
	}

	private static boolean areAdjacentInFramePlane(BlockPos first, BlockPos second, Direction facing) {
		int dx = Math.abs(first.getX() - second.getX());
		int dy = Math.abs(first.getY() - second.getY());
		int dz = Math.abs(first.getZ() - second.getZ());
		if (dx + dy + dz != 1) {
			return false;
		}
		return facing.getAxis() == Direction.Axis.Z ? dz == 0 : dx == 0;
	}

	private record Component(List<BlockPos> positions, List<PictureFrameBlockEntity> frames, boolean overflow) {
	}
}
