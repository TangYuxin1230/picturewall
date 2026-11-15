package com.rong.picturewall;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class ImageSourceScreen extends Screen {
	private final BlockPos pos;
	private ImageCrop crop;
	private TextFieldWidget sourceField;
	private TextFieldWidget pathField;
	private ButtonWidget cropButton;
	private String pendingSource;
	private String pendingPath = "";
	private String status = "";

	public ImageSourceScreen(BlockPos pos, String source, ImageCrop crop) {
		super(Text.translatable("picturewall.editor.title"));
		this.pos = pos;
		this.pendingSource = source;
		this.crop = crop;
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		sourceField = new TextFieldWidget(textRenderer, centerX - 150, height / 2 - 48, 300, 20, Text.empty());
		sourceField.setMaxLength(2048);
		sourceField.setPlaceholder(Text.translatable("picturewall.editor.placeholder"));
		sourceField.setText(pendingSource);
		addDrawableChild(sourceField);

		pathField = new TextFieldWidget(textRenderer, centerX - 150, height / 2 - 18, 300, 20, Text.empty());
		pathField.setMaxLength(4096);
		pathField.setPlaceholder(Text.translatable("picturewall.editor.path_placeholder"));
		pathField.setText(pendingPath);
		addDrawableChild(pathField);

		addDrawableChild(ButtonWidget.builder(Text.translatable("picturewall.editor.import"), button -> importImage())
				.dimensions(centerX - 150, height / 2 + 10, 96, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("picturewall.editor.browse"), button -> browseImages())
				.dimensions(centerX - 48, height / 2 + 10, 96, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("picturewall.editor.save"), button -> save())
				.dimensions(centerX + 54, height / 2 + 10, 96, 20).build());
		cropButton = ButtonWidget.builder(Text.translatable("picturewall.editor.crop"), button -> openCropEditor())
				.dimensions(centerX - 60, height / 2 + 36, 120, 20).build();
		cropButton.active = !sourceField.getText().isBlank();
		addDrawableChild(cropButton);
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
				.dimensions(centerX - 75, height / 2 + 62, 150, 20).build());
	}

	private void importImage() {
		String enteredPath = pathField.getText().trim();
		if (enteredPath.length() >= 2 && enteredPath.startsWith("\"") && enteredPath.endsWith("\"")) {
			enteredPath = enteredPath.substring(1, enteredPath.length() - 1);
		}
		if (enteredPath.isBlank()) {
			status = Text.translatable("picturewall.editor.path_required").getString();
			return;
		}

		try {
			importImage(Path.of(enteredPath));
		} catch (InvalidPathException exception) {
			status = Text.translatable("picturewall.editor.invalid_path").getString();
		}
	}

	void importImage(Path selectedPath) {
		try {
			if (!Files.isRegularFile(selectedPath) || Files.size(selectedPath) > PictureVideoPlayer.MAX_VIDEO_BYTES) {
				status = Text.translatable("picturewall.editor.invalid_image").getString();
				return;
			}
			byte[] imageBytes = Files.readAllBytes(selectedPath);
			PictureTextureCache.MediaType mediaType = PictureTextureCache.validateMedia(imageBytes);
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(imageBytes));
			String extension = switch (mediaType) {
				case GIF -> ".gif";
				case VIDEO -> ".mp4";
				case IMAGE -> "";
			};
			String filename = hash + extension;
			Path directory = MinecraftClient.getInstance().runDirectory.toPath().resolve("config/picturewall");
			Files.createDirectories(directory);
			Path importedImage = directory.resolve(filename);
			if (!Files.exists(importedImage)) {
				Files.write(importedImage, imageBytes);
			}
			pendingSource = "local:" + filename;
			sourceField.setText("local:" + filename);
			cropButton.active = true;
			pendingPath = "";
			pathField.setText("");
			status = Text.translatable("picturewall.editor.imported").getString();
		} catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
			PictureWallMod.LOGGER.error("Could not import image from the provided path", exception);
			status = Text.translatable("picturewall.editor.import_failed").getString();
		}
	}

	private void browseImages() {
		pendingSource = sourceField.getText();
		pendingPath = pathField.getText();
		Path startDirectory;
		try {
			String enteredPath = pendingPath.trim();
			if (enteredPath.length() >= 2 && enteredPath.startsWith("\"") && enteredPath.endsWith("\"")) {
				enteredPath = enteredPath.substring(1, enteredPath.length() - 1);
			}
			Path candidate = enteredPath.isBlank() ? Path.of(System.getProperty("user.home"))
					: Path.of(enteredPath);
			startDirectory = Files.isDirectory(candidate) ? candidate : candidate.getParent();
			if (startDirectory == null || !Files.isDirectory(startDirectory)) {
				startDirectory = Path.of(System.getProperty("user.home"));
			}
			MinecraftClient.getInstance().setScreen(new LocalImagePickerScreen(this, startDirectory));
		} catch (InvalidPathException | SecurityException exception) {
			status = Text.translatable("picturewall.editor.invalid_path").getString();
		}
	}

	private void openCropEditor() {
		String source = sourceField.getText().trim();
		if (source.isEmpty()) {
			status = Text.translatable("picturewall.editor.crop_requires_image").getString();
			return;
		}
		pendingSource = source;
		pendingPath = pathField.getText();
		MinecraftClient.getInstance().setScreen(new ImageCropScreen(this, crop, source));
	}

	void setCrop(ImageCrop crop) {
		this.crop = crop;
	}

	private void save() {
		String source = sourceField.getText().trim();
		if (!source.isEmpty() && !PictureFrameBlockEntity.isValidSource(source)) {
			status = Text.translatable("picturewall.editor.invalid_source").getString();
			return;
		}
		if (!source.isEmpty()) {
			PictureTextureCache.retry(source);
		}
		pendingSource = source;
		if (source.isEmpty()) {
			crop = ImageCrop.NONE;
		}
		PacketByteBuf buf = PacketByteBufs.create();
		new SetImagePayload(pos, source, crop).write(buf);
		ClientPlayNetworking.send(SetImagePayload.ID, buf);
		close();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 78, 0xFFFFFF);
		super.render(context, mouseX, mouseY, delta);
		if (!status.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, status, width / 2, height / 2 + 88, 0xFF7777);
		}
	}
}
