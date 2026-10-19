# 开发基线

日期：2026-10-18。目标设备：用户指定小米15，Android/HyperOS/微信具体版本待用户报告。测试包最低 Android 11（API 30）。

无可复用源码；保留既有 APK，不覆盖、不反编译。新包名 cn.elder.assistant。

工具：JDK 17、Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20、compile/target SDK 35。工具安装在项目 .toolchain 内，不修改系统 Java。

实施选择：界面采用 Kotlin 原生 Android Views，减少首版运行依赖；单 app 模块，核心逻辑纯 Kotlin；串行 Handler/Executor 控制异步。开发计划中 Compose/协程为建议选型，本选择不改变产品功能或验收条件。

用户明确授权无 USB 先开发。本地测试与物理微信测试分别报告，不降低 M0 和 M6 真机标准。

依据：[AGP 8.9 兼容要求](https://developer.android.com/build/releases/agp-8-9-0-release-notes)、[无障碍截图能力](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)、[DeepSeek 图片输入](https://api-docs.deepseek.com/guides/vision/)。
