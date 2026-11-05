# V0.1.7 橘猫形象版

仅将首页与悬浮窗共享 CatView 中的旧猫咪绘制替换为用户选定橘猫的透明 PNG。布局、尺寸、文字、点击区域、无障碍语义和全部回调均未改动；除 WarmStyle.kt 内 CatView 绘制外，31 个 Kotlin 文件 SHA-256 与 V0.1.6 一致。包名保持不变，版本号 8 / 0.1.7。

## 验证

145 项 Android 测试全通过，0 跳过；严格 lint、APK 构建、7 项 Python 测试和架构检查通过。新 APK 签名与 V0.1.6 一致，满足覆盖安装条件；手机实际安装尚未验证。

实际 Android View 的本地原生渲染图（不是实机截图）：
- [首页](design/ui-0.1.7/task.png)
- [橘猫悬浮窗](design/ui-0.1.7/overlay.png)
- [大字体长提示](design/ui-0.1.7/overlay-large-font.png)

橘猫使用内置 image_gen 基于用户选中图制作透明底；提示要求只移除白底，保留趴姿、卷尾、闭眼笑脸、奶油色口鼻和爪子、橘色条纹与无描边手绘质感，不添加文字/阴影。资源为 app/src/main/res/drawable-nodpi/cat_orange.png。

## 安装

直接安装 dist/phone-assistant-0.1.7-debug.apk 更新，不卸载旧版。真机仍需检查橘猫显示、拖动/点击透传、停止返回 App 和成功提示 1 秒后关闭；未将这些未执行项目计作通过。其余验收清单沿用 RELEASE_0.1.6.md。
