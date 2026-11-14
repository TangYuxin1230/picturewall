package com.rong.picturewall;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.net.URI;

public class PictureFrameBlockEntity extends BlockEntity {
	private String source = "";
	private ImageCrop crop = ImageCrop.NONE;
	private String linkGroup = "";

	public PictureFrameBlockEntity(BlockPos pos, BlockState state) {
		super(PictureWallMod.PICTURE_FRAME_ENTITY, pos, state);
	}

	public String getSource() {
		return source;
	}

	public ImageCrop getCrop() {
		return crop;
	}

	public String getLinkGroup() {
		return linkGroup;
	}

	public void setImage(String source, ImageCrop crop) {
		this.source = source;
		this.crop = crop;
		markAndSync();
	}

	public void setLinkGroup(String linkGroup) {
		this.linkGroup = isValidLinkGroup(linkGroup) ? linkGroup : "";
		markAndSync();
	}

	private void markAndSync() {
		markDirty();
		if (world != null) {
			world.updateListeners(pos, getCachedState(), getCachedState(), 3);
		}
	}

	private static boolean isValidLinkGroup(String linkGroup) {
		if (linkGroup == null || linkGroup.isEmpty()) {
			return false;
		}
		try {
			java.util.UUID.fromString(linkGroup);
			return true;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	public static boolean isValidSource(String source) {
		if (source == null || source.isBlank() || source.length() > 2048) {
			return false;
		}
		if (source.startsWith("local:")) {
			String filename = source.substring("local:".length());
			return filename.matches("[a-zA-Z0-9._-]{1,128}")
					&& !filename.equals(".")
					&& !filename.equals("..");
		}
		try {
			URI uri = URI.create(source);
			return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
					&& uri.getHost() != null
					&& uri.getUserInfo() == null;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.writeNbt(nbt, registries);
		nbt.putString("ImageSource", source);
		nbt.putInt("ImageCropLeft", crop.left());
		nbt.putInt("ImageCropTop", crop.top());
		nbt.putInt("ImageCropRight", crop.right());
		nbt.putInt("ImageCropBottom", crop.bottom());
		nbt.putString("LinkGroup", linkGroup);
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		String saved = nbt.getString("ImageSource");
		source = isValidSource(saved) ? saved : "";
		ImageCrop savedCrop = new ImageCrop(
				nbt.getInt("ImageCropLeft"),
				nbt.getInt("ImageCropTop"),
				nbt.getInt("ImageCropRight"),
				nbt.getInt("ImageCropBottom")
		);
		crop = savedCrop.isValid() ? savedCrop : ImageCrop.NONE;
		String savedLinkGroup = nbt.getString("LinkGroup");
		linkGroup = isValidLinkGroup(savedLinkGroup) ? savedLinkGroup : "";
	}

	@Override
	public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registries) {
		return createNbt(registries);
	}

	@Nullable
	@Override
	public BlockEntityUpdateS2CPacket toUpdatePacket() {
		return BlockEntityUpdateS2CPacket.create(this);
	}
}
