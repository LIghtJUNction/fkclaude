# fkclaude

为 Claude Android 提供可配置的进程环境视图与本地行为审计的 LSPosed 模块。当前适配 `com.anthropic.claude` **1.261112.18 / 26111218**；其他版本跳过定向环境覆盖；IP 守卫使用独立的通用接口。

当前源码版本 **0.4.1**，94 个配置项。支持设备 ID 持久随机化、Android ID、设备与系统字段、语言时区、运营商、局域网、屏幕电池、内存存储，以及部分隐私 API 的拦截和自定义。重点覆盖已定位的 **Sift 环境采集、事件队列与上传链路**。不修改 Claude 原始安装包或原始设备 ID 偏好。

新增 arm64 原生接口覆盖、虚拟 `getprop`／`mount` 查询和 WebView 页面开始执行前的脚本覆盖。Build、系统属性和查询输出共用配置映射；原生层和网页可读取同一组 CPU／容量／语言时区配置。WebView 可选择阻断 Canvas、WebGL、音频和设备枚举，可能影响图表或语音，0.3.0 预设默认关闭该项。新预设沿用系统语言／时区，局域网地址用不可用语义；重复应用预设保留已有设备 ID 和 IP 守卫配置。

这不是完整 Android 沙箱：直接系统调用、procfs、Worker、公网 IP 和硬件证明仍有边界。默认预设会阻断 Sift 等部分采集，其他字段仍可按需覆盖；详见覆盖清单。

## 使用

1. 构建：`./gradlew :app:assembleDebug`。产物为 `app/build/outputs/apk/debug/app-debug.apk`，为调试签名构建。
2. 安装模块，在 LSPosed 中启用并仅选择 Claude。
3. 打开模块设置，选择各项沿用、拦截或自定义，点击保存。
4. 完全重启 Claude 后生效。更换设备 ID 也需要保存并重启；配置不会每次请求随机变化。

关闭配置开关或停用模块，再完全重启 Claude，恢复原有数据来源。修改 API 级别、屏幕和容量等值可能影响兼容性或 UI。

模块没有联网权限；私有配置 Provider 仅接受模块和 Claude 的 UID。目标进程无法取得 Provider 时，回退读取 LSPosed API 93+ 的共享快照；快照只存虚拟环境配置，不存账号或令牌。已有私有配置会迁移，保留设备 ID。配置无法读取或初始化失败时停止目标进程，避免绕过可能已启用的守卫。版本不匹配时跳过定向环境覆盖，IP 守卫仍独立工作。日志不记录实际 ID／令牌。

构建需要 Android SDK 36、NDK 27.1.12297006、CMake 3.22.1 和 JDK 21；当前原生制品只包含 `arm64-v8a`。旧配置升级时保留已有字段，新原生／查询／WebView 总开关缺省启用；新增 Canvas 等阻断开关缺省关闭，应用隐私预设才会开启。

## 实时行为审计

打开设置中的“打开 Claude 行为审计”，或点 Claude 内可拖动的“审计”入口。弹窗支持中文实时日志卡片、搜索、分类、非对话网络筛选、完整 JSON 查看、记录开关、暂停刷新、关闭重开、清空确认与系统文件选择器导出。卡片保留请求方法、路径、主机、状态和耗时；JSONL／NDJSON 便于脚本和 AI 继续分析。

审计独立于环境覆盖开关，观察隐私 API、Sift／产品分析、HTTP、WebView 和原生 socket。记录只包含经过限制的元数据，正文、令牌、Cookie 和设备 ID 原值不入日志；原生层保留远端目的 IP，便于追踪未知流量，不记录本机源 IP 或数据缓冲区。私有日志排除 Android 自动备份，容量有界，丢弃和错误可见。

详见[使用与日志说明](docs/audit-logging.md)和[本轮静态隐私证据](docs/privacy-analysis-2026-11-17.md)。开发机可用 `python3 scripts/audit_logs.py <JSONL或NDJSON文件或目录> --json` 生成汇总。

## IP 守卫

在“IP 白名单守卫”中开启独立开关，填入允许的公网 IP／CIDR，保存并完全重启 Claude。IPv4 与 IPv6 传输分别取得短期许可；名单为空、回显失败、不匹配、许可过期或网络变化时阻断连接并关闭连接池中的 Internet socket。默认关闭，**不自动信任当前出口，也不预填白名单**。

探测从 Claude 进程匿名发起 HTTPS 请求，使用独立 TLS 上下文，不复制账号头、Cookie 或客户端密钥。默认直连／VPN/TUN 路由受支持；显式 HTTP/SOCKS 客户端代理拒绝放行。每次探测有时效检查，原生许可最长 10 秒；这是进程内守卫，不能保证零延迟检测或替代系统 UID 防火墙。分域代理、其他 UID 代发及直接系统调用仍有边界。详见 [IP 守卫说明](docs/ip-guard.md)。

## 0.4.1 验证状态

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
bash scripts/test_native.sh
bash scripts/test_ip_guard.sh
node --test tests/web_environment.test.mjs tests/audit_web.test.mjs
python3 -m unittest discover -s tests -p 'test_audit_logs.py'
bash scripts/test_network_audit.sh
python scripts/verify_artifacts.py
```

`verify_artifacts.py` 需要本地目标 APK，核对实际 DEX Hook 签名、字段类型、语言资源和模块 APK 入口，不依赖反编译器伪代码作为合同。

0.4.1 / versionCode 6 已通过综合构建和 **52 项 Java 测试**；真实 SAF 导出触发的 NDJSON 入口修复后，**14 项 Python 测试通过**。Lint 为 0 错误、10 警告。制品检查确认 8 项方法合同、31 项字段合同、94 个配置项和 arm64／16 KiB 对齐。完整结果、APK 摘要和签名见[0.4.1 验证记录](docs/validation-0.4.1.md)；先前 19 项 JavaScript 与原生回归结果保留在[0.4.0 历史记录](docs/validation-0.4.0.md)。

最终 APK 已在同一手机的 scrcpy 临时独立显示 3 完成主要交互验收：冷启动自动打开、Datadog 搜索、遥测与非对话筛选、JSON 详情、暂停刷新、关闭记录跨进程重启、SAF 导出、确认清空及收起后重开。真实导出为 1 行元信息加 1,000 条历史保留事件，已直接通过 CLI 解析；测试结束前记录开关恢复开启，临时显示已清理。过程、排除的陈旧样本和边界见[设备审计报告](docs/device-audit-2026-11-17.md)。

0.4.0 首个 31.818 秒启动窗口在没有发送新聊天时观察到 9 个可识别遥测 HTTP 请求组，并收到 200/202 响应。Sift 采集入口被既有配置阻断。日志没有读取实际请求体，不能据此判定某个私人字段已上传或定性为“窃取”；静态实现、实际调用和网络响应分别保留证据。长时间稳定性、其他显示组合和 IP 守卫出口切换仍需独立验证。

## 分析材料

- [0.4.1 发布摘要与 APK 校验信息](docs/releases/0.4.1.json)

- [0.4.1 构建与真机验证记录](docs/validation-0.4.1.md)
- [启动行为与设备交互审计](docs/device-audit-2026-11-17.md)
- [设备与导出的结构化验证摘要](docs/device-audit-2026-11-17-export-validation.json)
- [0.4.0 本地验证记录（历史）](docs/validation-0.4.0.md)
- [实时审计使用与日志说明](docs/audit-logging.md)
- [2026-11-17 隐私静态证据](docs/privacy-analysis-2026-11-17.md)
- [指南检索与默认值决策](docs/guide-review.md)
- [IP 守卫的实现和边界](docs/ip-guard.md)
- [0.3.0 本地验证](docs/validation-0.3.md)
- [环境信息与模块覆盖清单](docs/environment-coverage.md)
- [原生、子进程与 WebView 的实现和边界](docs/native-web-isolation.md)
- [账号风控静态分析](docs/claude-static-analysis.md)
- [设备 ID 的设计与约束](docs/device-id-requirements.md)
- 本地目标安装包、SHA-256、反编译源码：`artifacts/claude-1.261112.18/`，不纳入 Git。

仓库未配置自动 CI；日常验证在本机执行。
