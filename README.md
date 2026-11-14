# 自定义画框（Picture Wall）

适用于 Minecraft 1.21.1 的 Fabric 模组，可把本地图片、动画 GIF 或 MP4 视频显示在游戏世界的墙面上。

## 安装与使用

1. 安装 Minecraft 1.21.1、Fabric Loader 0.16.10 或更新版本，以及对应的 Fabric API；多人游戏时，服务器和所有客户端都需要安装本模组。
2. 将 `build/libs/picturewall-1.5.5.jar` 放入游戏目录的 `mods` 文件夹。
3. 用 8 根木棍围住 1 个玻璃板合成画框，也可以输入 `/give @s picturewall:picture_frame` 获取。
4. 将画框贴在完整的墙面上，右键打开设置。输入图片/GIF/MP4 直链，或点击“浏览文件”进入游戏内文件浏览器选择文件；也可以将文件完整路径粘贴到第二个输入框，再点“导入本地媒体”。点击“裁剪设置”进入预览，可拖动裁剪框或直接输入边缘百分比，然后点“完成”和“保存”。
5. 清空图片来源并保存即可移除画面。
6. 用 2 个铁锭、1 个红石和 2 根木棍合成展示框连接器。手持连接器依次右键两个相邻、朝向相同的展示框即可连接；有图片的一组会保留原图，空展示框会继承该图片。若两组已有不同图片或裁剪设置，需先统一后再连接。此后在任一组内展示框保存图片或裁剪设置，都会同步到整组。

游戏内文件浏览器从用户主目录开始浏览，可进入子目录、选择图片，或输入文件夹路径后打开。导入后图片会复制到 `.minecraft/config/picturewall`。本地图片只对导入了该图片的客户端可见；网址图片需要每位玩家在画框设置界面主动保存，或打开裁剪预览，之后该玩家的客户端才会加载。

连接器连接的相邻展示框会作为一组同步图片与裁剪设置，并拼成一张大图。未使用连接器的画框仍会按相邻、朝向、图片来源和裁剪设置自动拼接。每组最多支持 512 个画框。

图片使用固定亮度渲染，不随周围环境光变化。

支持 PNG、JPEG、GIF、BMP、TIFF 和 WBMP 图片；多帧 GIF 会循环播放（最多 300 帧、每边不超过 1280 像素且最多 921,600 像素），GIF 文件最大 5 MiB。视频支持 H.264 编码的 MP4，循环播放，输出最高约 30 帧/秒，时长最多 5 分钟、每边不超过 1920 像素且最多 2,073,600 像素、文件最大 50 MiB。静态图片最长边不超过 4096 像素、最多 16 百万像素。每个客户端最多缓存 64 项媒体，最多同时播放 2 个视频和 2 个 GIF，纹理与视频帧缓冲的内存预算上限为 128 MiB。网址仅支持 HTTP(S)，且不跟随重定向。高清视频在低性能设备上的播放流畅度取决于设备。

## 开发构建

需要 JDK 21。Windows 下运行：

```powershell
.\gradlew.bat build
```

生成的模组位于 `build/libs`。

---

# Picture Wall

A Fabric mod for Minecraft 1.21.1 that adds a placeable picture frame. Right-click the frame to choose a local image, animated GIF, or MP4 video, or enter a direct HTTP(S) media URL.

## Use

1. Install Fabric Loader 0.16.10 or newer and Fabric API for Minecraft 1.21.1. In multiplayer, install this mod on the server and every client.
2. Craft the frame with eight sticks around a glass pane, or use `/give @s picturewall:picture_frame`.
3. Place it and right-click it. Enter a direct image/GIF/MP4 URL, or browse to a local media file (or paste its full path and click **Import Local Media**); click **Crop** to preview and adjust the crop, then click **Done** and **Save**. Clear the source and save to remove it.
4. Craft a Frame Linker with two iron ingots, one redstone, and two sticks. Use it on two adjacent frames with the same facing to connect them; an existing image is preserved and empty frames inherit it. If both groups have different images or crop settings, make them match before connecting. Saving an image or crop setting on any linked frame updates the whole group.
5. Connected frames tile one large image. Frames not explicitly linked still tile automatically when adjacent and matching in facing, image source, and crop settings. Groups are limited to 512 frames.

Supported still-image formats are PNG, JPEG, BMP, TIFF, and WBMP. Animated GIFs loop and are limited to 300 frames, 1280 pixels per side, 921,600 pixels total, and 5 MiB. H.264 MP4 videos loop with a 5-minute, 1920-pixel-per-side, 2,073,600-pixel, and 50 MiB limit, with output capped at about 30 FPS. Static images are limited to 5 MiB, 4096 pixels per side, and 16 megapixels total. Each client caches at most 64 media items, plays at most two videos and two GIFs simultaneously, and uses at most 128 MiB for textures and video frame buffers. Media URLs must use HTTP(S); redirects are not followed. Each client must explicitly save a URL or open its crop preview before downloading it. Video decoding is pure Java; playback smoothness depends on device performance.

Local media is copied into `.minecraft/config/picturewall`. Each client must have the same local file to display it.

Images render at a consistent full brightness independent of surrounding light.

The frame stores the selected source in the world. A local image is available only on clients that have that same imported image locally; URL images can be displayed by other clients after they approve the URL in the frame editor.

## Build

Use JDK 21:

```text
gradlew.bat build
```

The remapped mod jar is created under `build/libs`.
