package com.rong.picturewall;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class PictureFrameBlockEntityRenderer implements BlockEntityRenderer<PictureFrameBlockEntity> {
	private static final int FULL_BRIGHT = 0x00F000F0;

	public PictureFrameBlockEntityRenderer(BlockEntityRendererFactory.Context context) {
	}

	@Override
	public void render(PictureFrameBlockEntity entity, float tickDelta, MatrixStack matrices,
			VertexConsumerProvider vertexConsumers, int light, int overlay) {
		String source = entity.getSource();
		if (source.isEmpty()) {
			return;
		}
		PictureTextureCache.Texture texture = PictureTextureCache.get(source);
		if (texture == null) {
			return;
		}

		Direction facing = entity.getCachedState().get(PictureFrameBlock.FACING);
		ConnectedFrameLayouts.Layout layout = ConnectedFrameLayouts.get(
				entity.getWorld(), entity.getPos(), source, entity.getCrop(), facing);
		Crop crop = tileCrop(texture.width(), texture.height(), entity.getCrop(), layout,
				entity.getPos(), facing);
		VertexConsumer consumer = vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texture.id()));
		renderFace(consumer, matrices, facing, crop, layout, entity.getPos(), FULL_BRIGHT, overlay);
	}

	private static Crop tileCrop(int imageWidth, int imageHeight, ImageCrop imageCrop,
			ConnectedFrameLayouts.Layout layout, BlockPos pos, Direction facing) {
		float sourceMinU = imageCrop.left() / 100.0f;
		float sourceMinV = imageCrop.top() / 100.0f;
		float sourceMaxU = 1 - imageCrop.right() / 100.0f;
		float sourceMaxV = 1 - imageCrop.bottom() / 100.0f;
		float sourceWidth = sourceMaxU - sourceMinU;
		float sourceHeight = sourceMaxV - sourceMinV;
		float imageAspect = imageWidth * sourceWidth / (imageHeight * sourceHeight);
		float targetAspect = (float) layout.width(facing) / layout.height();
		float visibleMinU = sourceMinU;
		float visibleMinV = sourceMinV;
		float visibleMaxU = sourceMaxU;
		float visibleMaxV = sourceMaxV;

		if (imageAspect > targetAspect) {
			float visibleWidth = sourceHeight * targetAspect * imageHeight / imageWidth;
			float offset = (sourceWidth - visibleWidth) / 2;
			visibleMinU += offset;
			visibleMaxU = visibleMinU + visibleWidth;
		} else if (imageAspect < targetAspect) {
			float visibleHeight = sourceWidth * imageWidth / (targetAspect * imageHeight);
			float offset = (sourceHeight - visibleHeight) / 2;
			visibleMinV += offset;
			visibleMaxV = visibleMinV + visibleHeight;
		}

		int columns = layout.width(facing);
		int rows = layout.height();
		float minU = visibleMinU + (visibleMaxU - visibleMinU) * layout.column(pos, facing) / columns;
		float maxU = visibleMinU + (visibleMaxU - visibleMinU) * (layout.column(pos, facing) + 1) / columns;
		float minV = visibleMinV + (visibleMaxV - visibleMinV) * layout.row(pos) / rows;
		float maxV = visibleMinV + (visibleMaxV - visibleMinV) * (layout.row(pos) + 1) / rows;
		return new Crop(minU, minV, maxU, maxV);
	}

	private static void renderFace(VertexConsumer consumer, MatrixStack matrices, Direction facing,
			Crop crop, ConnectedFrameLayouts.Layout layout, BlockPos pos, int light, int overlay) {
		float minX = 0.03f;
		float maxX = 0.97f;
		float minY = layout.contains(pos.down()) ? 0 : 0.03f;
		float maxY = layout.contains(pos.up()) ? 1 : 0.97f;
		float minZ = 0.03f;
		float maxZ = 0.97f;
		float plane = switch (facing) {
			case NORTH, WEST -> -0.01f;
			case SOUTH, EAST -> 1.01f;
			default -> throw new IllegalStateException("Picture frames must face horizontally");
		};
		switch (facing) {
			case NORTH, SOUTH -> {
				minX = layout.contains(pos.west()) ? 0 : minX;
				maxX = layout.contains(pos.east()) ? 1 : maxX;
			}
			case EAST, WEST -> {
				minZ = layout.contains(pos.north()) ? 0 : minZ;
				maxZ = layout.contains(pos.south()) ? 1 : maxZ;
			}
			default -> {
			}
		}

		switch (facing) {
			case NORTH -> {
				vertex(consumer, matrices, minX, minY, plane, crop.maxU(), crop.maxV(), Direction.NORTH, light, overlay);
				vertex(consumer, matrices, minX, maxY, plane, crop.maxU(), crop.minV(), Direction.NORTH, light, overlay);
				vertex(consumer, matrices, maxX, maxY, plane, crop.minU(), crop.minV(), Direction.NORTH, light, overlay);
				vertex(consumer, matrices, maxX, minY, plane, crop.minU(), crop.maxV(), Direction.NORTH, light, overlay);
			}
			case SOUTH -> {
				vertex(consumer, matrices, maxX, minY, plane, crop.maxU(), crop.maxV(), Direction.SOUTH, light, overlay);
				vertex(consumer, matrices, maxX, maxY, plane, crop.maxU(), crop.minV(), Direction.SOUTH, light, overlay);
				vertex(consumer, matrices, minX, maxY, plane, crop.minU(), crop.minV(), Direction.SOUTH, light, overlay);
				vertex(consumer, matrices, minX, minY, plane, crop.minU(), crop.maxV(), Direction.SOUTH, light, overlay);
			}
			case EAST -> {
				vertex(consumer, matrices, plane, minY, maxZ, crop.minU(), crop.maxV(), Direction.EAST, light, overlay);
				vertex(consumer, matrices, plane, maxY, maxZ, crop.minU(), crop.minV(), Direction.EAST, light, overlay);
				vertex(consumer, matrices, plane, maxY, minZ, crop.maxU(), crop.minV(), Direction.EAST, light, overlay);
				vertex(consumer, matrices, plane, minY, minZ, crop.maxU(), crop.maxV(), Direction.EAST, light, overlay);
			}
			case WEST -> {
				vertex(consumer, matrices, plane, minY, minZ, crop.minU(), crop.maxV(), Direction.WEST, light, overlay);
				vertex(consumer, matrices, plane, maxY, minZ, crop.minU(), crop.minV(), Direction.WEST, light, overlay);
				vertex(consumer, matrices, plane, maxY, maxZ, crop.maxU(), crop.minV(), Direction.WEST, light, overlay);
				vertex(consumer, matrices, plane, minY, maxZ, crop.maxU(), crop.maxV(), Direction.WEST, light, overlay);
			}
			default -> {
			}
		}
	}

	private static void vertex(VertexConsumer consumer, MatrixStack matrices, float x, float y, float z,
			float u, float v, Direction facing, int light, int overlay) {
		consumer.vertex(matrices.peek().getPositionMatrix(), x, y, z)
				.color(255, 255, 255, 255)
				.texture(u, v)
				.overlay(overlay)
				.light(light)
				.normal(matrices.peek(), facing.getOffsetX(), facing.getOffsetY(), facing.getOffsetZ());
	}

	private record Crop(float minU, float minV, float maxU, float maxV) {
	}
}
