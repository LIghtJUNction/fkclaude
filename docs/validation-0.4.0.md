# 0.4.0 验证记录

日期：2026-11-17（Asia/Taipei）。模块版本：**0.4.0 / versionCode 5**。目标样本：`com.anthropic.claude` **1.261112.18 / 26111218**。

**0.4.0 的本地构建、单元测试、原生测试和制品合同检查已通过，设备集成验收待连接恢复。** 手机断连前最后读取到的 fkclaude 安装版本为 0.3.0；随后 ADB 设备列表与 mDNS 发现均为空。本轮未安装 0.4.0，未完成真实弹窗渲染、运行时 Hook 命中或非对话上传日志验收。

## 1. 证据层次

| 层次 | 本轮实际完成 | 结论范围 |
| --- | --- | --- |
| APK 静态证据 | 重新拉取四个 Claude APK，摘要与既有样本一致；复查 DEX、序列化字段和调用链 | 确认这一版本包含哪些采集与上传实现，详见[静态隐私报告](privacy-analysis-2026-11-17.md) |
| 宿主机测试与构建 | Java、JavaScript、Python 测试，Android 构建、Lint 和打包检查通过 | 验证测试场景中的分类、脱敏、协议、导出与制品结构 |
| 原生模拟 Hook 测试 | 网络审计、IP 守卫、既有原生适配器的宿主测试通过 | 验证包装函数、队列、返回值和并发语义；Android 上的真实内联补丁仍待验收 |
| 设备集成 | ADB 连接已断开；没有本轮 0.4.0 安装和运行记录 | 弹窗布局、实际观察点、设备网络栈与长期稳定性保留为待验收项 |

旧版的设备记录独立保留在[0.2.1 设备验证](device-validation.md)。其中的 103 个 Java Hook、4 个原生 Hook 和 5 项读回是该历史版本的结果；本轮 0.4.0 的验收状态以本记录为准。

## 2. 本地验证结果

| 检查 | 结果 | 已验证内容 |
| --- | --- | --- |
| Gradle 综合任务 | 成功；最终复核命令耗时 28.44 秒 | `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` |
| Java 单元测试 | **51 项通过** | 配置、隔离、IP 守卫，以及审计脱敏、端点分类、筛选和 JSONL 边界 |
| Android Lint | **0 错误、10 警告** | 完整告警保留在 Lint 报告中 |
| JavaScript 测试 | **19/19 通过** | 既有网页环境逻辑和新增网页审计脚本；使用宿主 JS 测试上下文 |
| Python 分析器测试 | **11/11 通过** | 去重与冲突、未知类别、阶段计数、跨会话／网页关联、序号缺口、尾部截断、隐私与输入上限 |
| 原生网络审计 | **ASan / UBSan 通过** | 宿主环境中的原生审计包装函数和队列行为 |
| IP 守卫原生测试 | **通过** | 宿主环境中的既有守卫回归用例 |
| 既有原生适配器 | **11 个适配器、40,000 次并发读取通过** | 环境配置编解码和原生读取适配 |
| `verify_artifacts.py` | **通过** | 8 项 DEX 方法合同、31 项字段合同、94 个配置项，中英文资源完整 |
| APK / ELF 结构 | **通过** | `audit.js`、审计类和 3 个新增审计 JNI 入口已打包；原生 ABI 为 arm64-v8a，ELF 与 ZIP 满足 16 KiB 对齐 |

三项新增审计 JNI 入口为 `nativeInstall`、`nativeDrain`、`nativeSetEnabled`。它们与既有环境配置／IP 守卫入口共同存在于制品中。制品检查的 `runtime_hooks_tested` 为 `false`，保留了“已打包”和“已在设备执行”的证据区别。

复现命令：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
node --test tests/web_environment.test.mjs tests/audit_web.test.mjs
python3 -m unittest discover -s tests -p 'test_audit_logs.py' -v
bash scripts/test_network_audit.sh
bash scripts/test_ip_guard.sh
bash scripts/test_native.sh
python3 scripts/verify_artifacts.py
```

本地构建产物为 `app/build/outputs/apk/debug/app-debug.apk`，版本 0.4.0 / 5。上述结果针对本轮源码和调试构建；本记录不包含手机安装回读结果。

最终交付 APK 为调试签名，Android APK Signature Scheme v2 验证通过；签名证书 SHA-256 为 `cc0403fce3af9c85a03d14b8c9826d2db52c92bbe280fcc4c6ba9ac1f7735221`。APK 大小为 4,599,220 字节，文件 SHA-256 为 `0987376224ac8d0cf6707e7e27600d055e67bf4a3be52e599a931eca7d49c209`。对应功能源码提交为 `5cbf12467153ed733d9ed3e54b88605bae969e0a`。最终复核包含 SAF 关闭失败与确认框生命周期修复。


## 3. 记录与复查位置

本轮首次综合构建日志为 `artifacts/privacy-audit-2026-11-17/build-validation.log`，完成状态为同目录的 `build-validation-result.json`；最终修复后的构建、制品合同与签名复核分别见 `final-build.log`、`final-contracts.log`、`final-signature.log`，结果为 `final-validation-result.json`。合并网页测试记录为 `web-tests-final.log`。Java 测试报告为 `app/build/reports/tests/testDebugUnitTest/index.html`，原始 JUnit XML 位于 `app/build/test-results/testDebugUnitTest/`；Lint 报告为 `app/build/reports/lint-results-debug.html`。

制品合同摘要为 `artifacts/validation/static-contracts-0.4.0.json`，模块制品元数据为 `artifacts/privacy-audit-2026-11-17/module-apk.json`。Claude 原始安装包和静态证据索引位于 `artifacts/privacy-audit-2026-11-17/`；这些本地材料不纳入 Git。

分析器可读取后续导出的 JSONL：

```sh
python3 scripts/audit_logs.py /path/to/audit.jsonl --json
python3 scripts/audit_logs.py /path/to/audit-directory --json --non-conversation
```

分析器分别汇总操作阶段、关联组和原生 socket 返回字节。`attempted`、`blocked`、`response_received` 以及 socket 写出属于不同观察层，独立上传次数保持未知。序号缺口会报告；轮转、清空、队列饱和、异常中断或缺少文件都可能产生缺口。

## 4. 覆盖边界

**本轮完成的是有明确观察点的审计实现与本地验证。** 直接系统调用、其他 UID、外部浏览器、Worker、尚未触发的应用功能，以及安装 Hook 前已缓存的信息仍有可见性边界。Cronet／QUIC 等网络栈未逐一完成设备验证；部分流量可能经过已观察的原生 socket 接口，完整 HTTP 请求上下文仍需对应网络栈证据。

**原生记录保留目的地址与调用结果，不还原完整业务上下文。** 目的 IP、端口、返回字节和 errno 有助于寻找未知通信；单凭这些字段仍缺少域名、HTTP 路径、正文和服务端用途。加密数据写入 socket 与服务端持久保存是两个不同阶段。

**现有环境保护会改变实验结果。** Sift 阻断、遥测域名配置和 IP 守卫可能改变本地采集或阻止发送；后续观察必须记录实际配置。解释日志时应同时检查观察点覆盖和保护配置：未命中条目保留为未观察，HTTP 成功记录对应请求的客户端结果，Hook 安装单独报告观察点状态。整体覆盖还需要逐路径的设备实验。

**日志来自被审计进程内部。** 同权限代码可修改或删除日志；这套记录适合复查调用、比较配置和定位异常，尚无独立可信取证保证。详细字段、容量和语义见[本地行为审计](audit-logging.md)。

## 5. 待完成的设备验收

1. 恢复已授权 ADB 连接，核对目标版本、模块安装版本和 LSPosed／Vector 作用域；保留现有配置后安装 0.4.0。
2. 在真实 Claude Activity 中检查审计入口、拖动、弹窗边距、键盘、搜索、分类、事件详情、关闭重开和截图布局。
3. 验证记录开关立即停止 Java 与原生新事件，暂停刷新保留采集，清空后旧队列不回写；通过系统文件选择器导出并检查 JSONL 与脱敏结果。
4. 在已记录配置下分别观察冷启动、前台空闲、切换后台等不发送对话的场景，核对 API 读取、构造／入队、HTTP、原生 socket 各层证据和观察点缺失情况。
5. 验证真实 WebView 提供程序、跨页面请求关联、目标进程重启、轮转／丢弃计数及持续运行；IP 守卫的真实出口切换实验仍待完成。

## 6. 声明与证据自检

| 检查维度 | 本记录的结论 |
| --- | --- |
| 新增能力 | 审计 UI、结构化日志与分析器已进入源码和构建产物；设备可用性等待上述验收 |
| 表述清晰 | 目标样本、模块构建版本、最后可读取的安装版本分别列明 |
| 证据强度 | 本地测试与制品合同通过；没有本轮设备运行日志或网络回包证据 |
| 评估完整性 | 实际弹窗、真实 Hook、WebView、多进程及持续运行已列为待验收项 |
| 方法边界 | 记录元数据、区分观察层，并公开盲区与同进程日志的信任限制 |
