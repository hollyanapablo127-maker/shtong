# APP 图标源文件

`app-icon.png` 来自用户 2026-09-22 提供的“生化通APP图标设计 (1).png”，原图完整保留，含原有水印和白色圆角外背景。不是重新生成的设计稿。

执行 `python tools/generate-mobile-icons.py`（需要 Pillow）生成 Android 多密度图标、自适应图标原画和 iOS 1024 AppIcon。Android 图层按 108dp 中的 72dp 可见区域保留比例，实际外轮廓由系统启动器裁切。iOS 使用无透明通道 PNG，图标集由 Xcode 编译生成各设备尺寸。

鸿蒙原生工程尚未建立。本源图可供后续工程复用，但不能把源图目录或待构建版本目录当成已发布的鸿蒙 APP。
