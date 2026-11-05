# V0.1 测试与交付报告

日期：2026-10-18。结论：本地实现、可执行测试与测试包已完成；尚未完成真实手机、真实 API 和微信端到端验收。用户授权先交付 APK，在小米 15 下载后测试。

## 交付构建

- 名称：手机助手；包名：`cn.elder.assistant`；版本：0.1.0 / versionCode 1。
- 最低 Android 11 / API 30，目标 API 35；Kotlin 原生 Views。
- APK：`dist/phone-assistant-0.1.0-debug.apk`；大小：3782996 字节；开发调试签名。
- SHA-256：`0cd9d78712d50182ccdcb0a081421e4461f7ca6575f8f23872afd93fb4cd018f`。
- APK ZIP 完整性、包名、SDK、v2 签名验证通过。证据见 `test-results/apk.json`、`apk-signature.txt`、`apk-badging.txt`。实际安装尚未验证。
- 源码包不含 API 密钥、签名私钥、构建工具缓存、现有来源不明 APK 或 sources/ 参考材料。

## 最终全量本地验证

```sh
sh tools/build-local.sh :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 tools/test_report.py
python3 tools/check_architecture.py
python3 tools/check_apk.py
```

| 验证 | 结果 | 证据 |
|---|---|---|
| 应用单元、HTTP 模拟与 Robolectric 测试 | 94 项，0 失败、0 错误、0 跳过 | test-results/summary.json |
| Python 导出日志统计测试 | 7 项全部通过 | test-results/python-tests.txt |
| Android 严格 lint | 通过，warningsAsErrors / abortOnError 保持启用 | test-results/DELIVERY-all.log |
| APK 构建 | BUILD SUCCESSFUL，51 个任务执行 | test-results/DELIVERY-all.log |
| 自动动作/清文网络/组件暴露检查 | 21 个生产文件检查通过 | test-results/architecture.json |
| 安装包完整性与签名 | 通过 | test-results/apk.json |

共 **101 项自动化测试**。Gradle 没有源码的编译任务标记 NO-SOURCE 不代表跳过测试；实际 JUnit skipped 为 0。未运行手机 instrumented tests，Robolectric 是电脑上的 Android 环境模拟。

| 应用测试组 | 数量 | 验证重点 |
|---|---:|---|
| FoundationTest | 10 | 固定任务、数据契约、日志与脱敏 |
| ObservationTest | 16 | 指纹、坐标、快照一致性、页面变化与浮层策略 |
| ModelTest | 20 | 模型协议、无效输出、目标验证、HTTP 错误/超时/取消 |
| FlowTest | 21 | 会话代次、失效响应、调用预算、联系人与完成证据 |
| MetricsTest | 10 | 错误/超时统计、分位数、附件、损坏日志 |
| PipelineTest | 2 | 人工合成页面的跨模块回放；不是真实微信样本 |
| MainActivityTest | 3 | 页面入口与权限状态 |
| DebugActivityTest | 3 | 空日志提示、验收准备开关与运行中禁用 |
| SpeakerTest | 4 | TTS 初始化、去重、取消与关闭 |
| RunTimingTest | 5 | 起点准备不计时、预算不被切页/重试重置、准备阶段停止不虚报耗时 |

Python 测试验证未标注运行不被排除、失败/超时保留在分母、执行前取消不计调用、ZIP 读取、损坏日志明确报错，以及引导耗时/任务耗时/错误比例的计算。

## 阶段修复记录

阶段日志保留于 test-results。M1/M2/M3/M4/M5 的累计测试分别为 12/26/45/67/79 项；最终补充计时、取消及回归覆盖后为 94 项应用测试。

- M3：原测试直接比对 JSON 原文，Unicode 转义导致失败；改为解码真实请求后精确断言固定目标和节点内容，保留有效断言，复测通过。
- M5：严格 lint 报告依赖、备份规则、图标、绘制分配等问题；修复配置及代码后通过，没有基线豁免或关闭检查。
- 交付核对：补上 Debug 起点准备门控，计时改为从首份稳定有效观察开始；切页/重试不重置总预算，准备期间终止不记虚假的零耗时。
- 汇总工具保留未知结果与失败样本；零展示时错误指引比例为 null，不写成 0%。

## 已实现范围

任务/我的、唯一固定任务、权限与模型配置、UI Tree 与必要截图、DeepSeek 云端 Provider、目标与置信度校验、只引导的 Overlay、TTS、页面变化后继续、基础偏航重新判断、过期响应隔离、联系人与完成证据保护、停止/重试、输入输出日志、Debug 附件/导出/清理与人工标注均已接入。

没有登录、用户历史、自定义语音输入、ASR、多任务、自动点击、自动返回/滑动/输入或独立后台。所有微信动作由人执行。

## 尚未验收与使用限制

真实 DeepSeek 文本/图片请求 **0 次**；真实微信完整运行 **0 次**；已验证手机 **无**。没有可报告的实际成功率、错误指引率或真实调用时延。

必须继续执行原 PRD：E01–E06 共 24 次基线、D01–D02 至少 6 次偏航、A01–A09 专项异常，以及一次成功/偏航/API 失败的完整 Debug 追溯。机型小米 15；Android、HyperOS、微信版本待用户填写。具体步骤见 [安装与测试说明](INSTALL_AND_TEST.md)。

重点核对实际节点可读性、截图是否成功、浮层坐标与点击透传、中文发声、服务后台存活、错误联系人保护及真实呼叫完成判断。confidence 0.80 是初始工程参数，尚未真实样本校准；不能当作成功率承诺。依赖账号可用模型能力，默认模型名称可在 App 修改。

执行验收时不要修改原始失败日志。每个运行停止后在 Debug 填写人工标注并导出 ZIP；同批次使用 `python3 tools/summarize_runs.py run1.zip run2.zip` 按起点及总体汇总，设备/模型/提示词有变化时另建批次。
