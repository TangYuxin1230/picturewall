package com.rong.picturewall;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class LocalImagePickerScreen extends Screen {
	private static final int PAGE_SIZE = 5;
	private static final int MAX_ENTRIES = 5000;

	private final ImageSourceScreen editor;
	private final Path directory;
	private final int page;
	private TextFieldWidget directoryField;
	private List<Path> entries = List.of();
	private String status = "";

	public LocalImagePickerScreen(ImageSourceScreen editor, Path directory) {
		this(editor, directory, 0);
	}

	private LocalImagePickerScreen(ImageSourceScreen editor, Path directory, int page) {
		super(Text.translatable("picturewall.picker.title"));
		this.editor = editor;
		this.directory = directory.toAbsolutePath().normalize();
		this.page = page;
	}

	@Override
	protected void init() {
		int centerX = width / 2;
		int directoryWidth = Math.min(320, width - 32);
		int directoryFieldWidth = directoryWidth - 144;
		directoryField = new TextFieldWidget(textRenderer, centerX - directoryWidth / 2,
				height / 2 - 72, directoryFieldWidth, 20, Text.empty());
		directoryField.setMaxLength(4096);
		directoryField.setText(directory.toString());
		addDrawableChild(directoryField);

		addDrawableChild(ButtonWidget.builder(Text.translatable("picturewall.picker.open"), button -> openEnteredDirectory())
				.dimensions(centerX - directoryWidth / 2 + directoryFieldWidth + 4, height / 2 - 72, 64, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("picturewall.picker.up"), button -> goToParent())
				.dimensions(centerX + directoryWidth / 2 - 68, height / 2 - 72, 68, 20).build());

		loadEntries();
		int first = page * PAGE_SIZE;
		for (int index = first; index < Math.min(first + PAGE_SIZE, entries.size()); index++) {
			Path entry = entries.get(index);
			boolean isDirectory = Files.isDirectory(entry);
			String prefix = isDirectory ? "[DIR] " : "";
			String label = textRenderer.trimToWidth(prefix + entry.getFileName(), Math.max(60, width - 48));
			int row = index - first;
			addDrawableChild(ButtonWidget.builder(Text.literal(label), button -> select(entry, isDirectory))
					.dimensions(centerX - Math.min(180, (width - 32) / 2), height / 2 - 28 + row * 21,
							Math.min(360, width - 32), 20)
					.build());
		}

		int pageCount = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		ButtonWidget previousButton = ButtonWidget.builder(Text.translatable("picturewall.picker.previous"),
						button -> changePage(page - 1))
				.dimensions(centerX - 130, height / 2 + 76, 90, 20)
				.build();
		previousButton.active = page > 0;
		addDrawableChild(previousButton);
		ButtonWidget nextButton = ButtonWidget.builder(Text.translatable("picturewall.picker.next"),
						button -> changePage(page + 1))
				.dimensions(centerX + 40, height / 2 + 76, 90, 20)
				.build();
		nextButton.active = page + 1 < pageCount;
		addDrawableChild(nextButton);
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> returnToEditor())
				.dimensions(centerX - 60, height / 2 + 99, 120, 20)
				.build());
	}

	private void loadEntries() {
		try (Stream<Path> children = Files.list(directory)) {
			entries = children
					.filter(path -> Files.isDirectory(path) || isSupportedMedia(path))
					.limit(MAX_ENTRIES + 1L)
					.sorted(Comparator.<Path, Boolean>comparing(path -> !Files.isDirectory(path))
							.thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
					.toList();
			if (entries.size() > MAX_ENTRIES) {
				entries = entries.subList(0, MAX_ENTRIES);
				status = Text.translatable("picturewall.picker.too_many").getString();
			}
			if (entries.isEmpty() && status.isEmpty()) {
				status = Text.translatable("picturewall.picker.empty").getString();
			}
		} catch (IOException | SecurityException exception) {
			entries = List.of();
			status = Text.translatable("picturewall.picker.unreadable").getString();
			PictureWallMod.LOGGER.warn("Could not list images in directory {}", directory, exception);
		}
	}

	private static boolean isSupportedMedia(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
				|| name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".tif")
				|| name.endsWith(".tiff") || name.endsWith(".wbmp") || name.endsWith(".mp4");
	}

	private void select(Path entry, boolean isDirectory) {
		if (isDirectory) {
			MinecraftClient.getInstance().setScreen(new LocalImagePickerScreen(editor, entry));
			return;
		}
		editor.importImage(entry);
		returnToEditor();
	}

	private void openEnteredDirectory() {
		try {
			Path entered = Path.of(directoryField.getText()).toAbsolutePath().normalize();
			if (!Files.isDirectory(entered)) {
				status = Text.translatable("picturewall.picker.not_directory").getString();
				return;
			}
			MinecraftClient.getInstance().setScreen(new LocalImagePickerScreen(editor, entered));
		} catch (InvalidPathException | SecurityException exception) {
			status = Text.translatable("picturewall.picker.invalid_path").getString();
		}
	}

	private void goToParent() {
		Path parent = directory.getParent();
		if (parent != null) {
			MinecraftClient.getInstance().setScreen(new LocalImagePickerScreen(editor, parent));
		}
	}

	private void changePage(int newPage) {
		MinecraftClient.getInstance().setScreen(new LocalImagePickerScreen(editor, directory, newPage));
	}

	private void returnToEditor() {
		MinecraftClient.getInstance().setScreen(editor);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context, mouseX, mouseY, delta);
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 100, 0xFFFFFF);
		if (!status.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, status, width / 2, height / 2 - 85, 0xFFAA55);
		}
		if (entries.isEmpty() && status.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, Text.translatable("picturewall.picker.empty"),
					width / 2, height / 2 + 25, 0xAAAAAA);
		}
		super.render(context, mouseX, mouseY, delta);
	}
}
