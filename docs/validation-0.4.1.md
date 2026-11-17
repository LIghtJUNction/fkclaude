# 0.4.1 验证记录

日期：2026-11-17。模块版本：**0.4.1 / versionCode 6**。适配目标：Claude Android `com.anthropic.claude` **1.261112.18 / 26111218**。

**最终修复版已通过本地构建、52 项 Java 测试、制品合同及签名检查，并完成主要审计交互的真机验收。** 真实 SAF 导出发现的 NDJSON 入口问题已修复，最终 Python 测试为 **14/14**；Android Lint 为 0 错误、10 警告。[V01–V06、V09–V10]

0.4.0 已取得实际启动日志，其中确认了遥测请求、原生 socket 观察以及弹窗初始化故障，详见[首个设备启动窗口报告](device-audit-2026-11-17.md)。0.4.1 针对该故障、启动时的打开请求和日志可读性进行了修复。前一版本的运行结果与本版本的构建结果分开记录，不能相互替代。[V07–V08]

## 1. 版本与制品

| 项目 | 本次已核验结果 |
| --- | --- |
| APK 源码提交 | `444bb9f9a5adc71d81b41472b16a1af64dd0ae10` |
| CLI 导出入口修复提交 | `4273ffc4ba3a787aa9450d90434a8b6a189af83c` |
| 模块版本 | `0.4.1 / 6` |
| APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 文件大小 | **4,616,756 字节** |
| SHA-256 | `ed14fe65c6aa8772386245f5c4b7dec5f03294ff8b8a72afae6276785a42b085` |
| 构建类型 | Debug；Android 调试签名 |
| APK 签名 | APK Signature Scheme v2 验证通过 |
| 签名证书 SHA-256 | `cc0403fce3af9c85a03d14b8c9826d2db52c92bbe280fcc4c6ba9ac1f7735221` |
| 原生 ABI | `arm64-v8a` |
| 原生对齐 | ELF 与 APK ZIP 的 16 KiB 对齐检查通过 |

APK 的实际字节数、摘要和 `output-metadata.json` 已独立读取，与最终验证结果文件一致。手机上实际安装的 APK 摘要也已核对一致。上述摘要绑定的是包含冷启动请求保留修复的最终制品；CLI 文件入口修复不改变 APK，中间构建和后续重建如有不同摘要，应作为不同制品记录。[V01、V05–V06、V09–V10]

## 2. 本次修复及验证含义

| 问题或需求 | 0.4.1 源码中的处理 | 目前可确认到的层级 |
| --- | --- | --- |
| 结果回调安装失败可能连带跳过弹窗挂载 | 按 `onActivityResult(int, int, Intent)` 确切签名查找，逐层处理 Activity 父类；结果 hook 安装和弹窗调度分别捕获异常 | 已通过构建；最终冷启动会话未出现此前的 observer_error，面板显示成功 |
| 冷启动或新 Intent 中的打开请求可能在后续生命周期丢失 | 在 `callActivityOnCreate`、`callActivityOnNewIntent` 前保存 `fkclaude.openAudit` 请求，恢复后调度挂载；只有弹窗显示成功才消费已保存请求 | 冷启动已观察到 before_create → preserved_intent → visible；新 Intent 路径也已观察 |
| 延迟挂载碰到已暂停或销毁的 Activity | 跟踪恢复状态；挂载前检查暂停、结束和销毁；销毁时移除待处理请求 | 进程结束后重新启动的流程已覆盖；其他生命周期组合未穷尽 |
| 弹窗失败缺少可追查信息 | 增加打开请求、显示成功和有界 UI 失败诊断；记录错误类型与经限制的类/调用帧信息 | 新日志已记录打开请求与 visible；旧缺失类的完整根因仍不由旧记录证明 |
| 日志列表难以直接理解 | 将动作、结果和状态摘要转换为中文说明，保留接口、主机、路径等技术信息，详细 JSON 保持结构化记录 | 最终截图与 XML 已验证主要显示、搜索及详情交互 |
| 固定诊断说明被误脱敏成 `[token]` | 只对指定详情键下的六个源码固定值作精确匹配，其余内容继续走原脱敏规则 | 新增 Java 回归测试通过 |
| CLI 将实际诊断动作或状态归为未知 | 增加原生观察器、环境配置、弹窗请求/显示动作与相关状态；明确 socket 操作集合 | Python 回归测试通过 |
| 原生队列丢弃计数可能因重复输入或筛选失真 | 先按会话和序号去重，再累计原生丢弃增量；与 Java 导出累计计数分开 | Python 回归测试通过 |
| 系统文件选择器生成的 `.ndjson` 被 CLI 文件入口忽略 | 同时识别 `.jsonl`、`.ndjson` 及相应轮转文件；直接读取原始导出 | 真正的 SAF 导出读取成功；新增回归后 14/14 Python 测试通过 |

修复依据包括首窗中的 `activity_panel / NoClassDefFoundError`，但旧记录没有具体缺失类名，不能仅凭该异常断言每一次失败的完整根因。按确切签名查找和异常隔离，解决的是已识别的脆弱控制路径；最终冷启动样本已显示面板成功打开；完整根因与未覆盖场景仍应保留边界。[V07–V08、V10]

诊断说明的精确白名单不扩大到任意同形字符串：测试覆盖了换详情键、添加后缀、任意长 token 和禁止的敏感字段。修复只影响后续记录，已写入旧日志的 `[token]` 不会被回填。[V02、V07]

## 3. 最终本地验证结果

最终 APK 验证任务记录了四个步骤，退出码全部为 0，总状态为成功。设备导出之后又执行 CLI 入口回归，测试数由当时的 13 项增至 14 项；两次记录分别保留。[V01、V09]

| 检查 | 已确认结果 | 证据 |
| --- | --- | --- |
| Gradle 综合任务 | 成功；包装脚本记录耗时 41.08 秒 | `final-0.4.1-gradle.log`、`final-0.4.1-result.json` |
| Java 单元测试 | **52 项通过**；失败 0、错误 0、跳过 0 | 5 份 JUnit XML，已独立累加核对 |
| Android Lint | **0 错误、10 警告** | Lint XML，已独立核对严重性计数 |
| Python 分析器测试 | APK 构建阶段 **13/13**；SAF 兼容修复后 **14/14** | `final-0.4.1-python.log`、`ndjson-python-tests.log` |
| DEX 合同 | **8 项方法、31 项字段** | `final-0.4.1-contracts.log` |
| 配置与语言资源 | **94 个配置项；英文、简体中文完整** | 同上 |
| 模块打包 | Xposed 入口存在，未捆绑框架 API，WebView 页面起始注入脚本已打包 | 同上 |
| 原生打包 | arm64-v8a；环境、IP 守卫和 3 个审计 JNI 入口齐全 | 同上 |
| 对齐与签名 | 16 KiB 对齐通过；APK v2 签名验证通过 | 合同与签名日志 |

Gradle 输出中的“52 actionable tasks”是任务数量，Java 的 52 项测试来自 JUnit XML，两者碰巧相同，不能用构建任务数替代测试数。[V02]

Lint 警告分布如下，保留为已知检查结果：

| Lint 标识 | 数量 |
| --- | ---: |
| OldTargetApi | 1 |
| RequiresFeature | 2 |
| AndroidGradlePluginVersion | 1 |
| GradleDependency | 2 |
| ChromeOsAbiSupport | 1 |
| HardwareIds | 1 |
| SetTextI18n | 1 |
| RtlHardcoded | 1 |
| **合计** | **10** |

制品合同中的 `runtime_hooks_tested` 为 `false`。该检查证明签名和入口被打包，不执行 Android 上的真实 hook，因此不能把它写成设备运行通过。[V03、V05]

### 3.1 与上一轮测试的关系

0.4.0 的 19 项 JavaScript 测试、原生网络审计、IP 守卫和既有原生适配器测试保留在[0.4.0 验证记录](validation-0.4.0.md)。本次最终 APK 任务记录四个步骤，后续补充 CLI 入口回归；未将旧版 JavaScript 或原生结果改写为本次重新执行的结果。既有原生测试使用宿主假 hook 引擎，也不替代设备内联补丁验证。[V01、V08]

### 3.2 复核命令

在项目根目录执行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
python3 -B -m unittest discover -s tests -p test_audit_logs.py -v
python3 scripts/verify_artifacts.py
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

最终签名验证使用 Android Build Tools 37.0.0 的 `apksigner verify --verbose --print-certs`。本次结果对应上文 APK 摘要；重新构建之后应重新核对摘要，不以版本号相同推断文件相同。CLI 另直接执行原始 `13-saf-export.ndjson` 输入，未通过改名绕过扩展名问题。[V01、V06、V09]

## 4. 最终 APK 真机验收

验收对象为上文 **SHA-256 为 ed14fe…42b085 的最终 0.4.1 APK**，手机实际安装文件摘要与之相同。操作在同一手机的 scrcpy 临时独立显示 3（1240 × 2772、560 dpi）完成。原始日志、截图和 XML 保留在本地忽略目录，不纳入 Git 或云端附件；临时录屏已在提取验收画面后删除。[V10]

| 项目 | 结果 | 主要证据 |
| --- | --- | --- |
| 最终安装对应关系 | 手机 APK SHA-256 与最终构建一致 | 最终安装校验与 `device-final-state.json` |
| 冷启动自动打开 | 通过；最新会话 seq 44 before_create → 163 preserved_intent → 190 visible | `05-final-cold.jsonl`、`09-cold-panel.jpg` |
| 新 Intent 请求 | 已观察到 before_new_intent 与 preserved_intent，已显示面板保留状态 | `07-after-refresh-trigger.jsonl` |
| 中文卡片与状态 | 可见完整控件、卡片无相互遮挡，显示方法/路径/主机/状态/耗时 | `09-cold-panel.jpg`、`15-filtered-final.jpg`，操作员视觉检查 |
| 搜索、类别与非对话筛选 | datadog 的完整可见卡片匹配；可选择“遥测”，非对话开关 true | `10-search-result.xml`、`12-category-menu.xml`、`13-paused-list.xml` |
| JSON 详情 | 所选 Datadog spans 事件 JSON 可解析，与 HTTP 202 卡片对应 | `11-event-detail.xml` |
| 只暂停刷新 | 暂停前后列表条目相同，记录仍开启；日志新增 22 条，恢复后显示实时刷新 | `13/14` 暂停 XML、`06/07` 日志、`15-filtered-final.xml` |
| 停止记录跨进程重启 | 新进程显示关闭；有效前后日志逐字相同，无追加 | `11-fresh-disabled.jsonl`、`12-off-confirmed.jsonl`、`18-off-confirmed.xml` |
| SAF 保存与读取 | 保存到 Download；1,001 行 = 1 元信息 + 1,000 事件；原始 NDJSON 直接经 CLI 校验 | `13-saf-export.ndjson` 与导出聚合 |
| 清空确认 | 确认后仅保留清空事件 1 条、326 字节 | `23-cleared-confirmed.xml`、`15-cleared-confirmed.jsonl` |
| 恢复记录与收起后重开 | 记录恢复 true，产生新事件，入口可重新打开面板 | `24-reopened-final.xml`、`16-resumed-final.jsonl` |
| 长时间运行、网络切换、其他显示组合、IP 守卫出口变化 | 未进行完整验收 | 仍需独立受控实验 |

完整过程、样本 SHA、会话分组、XML 与日志交叉证据见[设备审计报告第 10 节](device-audit-2026-11-17.md#10-041-最终设备验收)和[可分析的聚合 JSON](device-audit-2026-11-17-export-validation.json)。聚合只包含摘要、计数、本地会话别名和进程元数据，不包含原始会话 ID 或目的 IP。[V10]

### 4.1 排除陈旧记录与重复快照

早期 `16-off-after-restart.xml` 的画面新鲜度存在疑问，未纳入关闭记录跨重启验收。有效实验确认旧进程 10143 消失后才启动新进程 10951；新鲜的 `18-off-confirmed.xml` 绑定该新进程，并显示记录开关 false。对应的 `11-fresh-disabled.jsonl` 与 `12-off-confirmed.jsonl` 都为 1,914,642 字节，SHA-256 均为 `0656d59383ccbcae1e2c3fab8ae32667b822958ff288b7926c422a9f6fa69a7d`。没有仅凭旧画面或相同文件摘要宣称重启通过。[V10]

`05-final-cold.jsonl` 包含 4 个会话，冷启动验收只使用最新会话的 655 条；该会话 Java hook 安装计数为 155、不可用 15，原生安装 7/7，没有 observer_error。不同阶段的累计快照不能相加为总事件数，也不用于覆盖 0.4.0 的首窗故障。[V08、V10]

### 4.2 导出范围与最终状态

真实 SAF 文件为 **553,903 字节**，SHA-256 为 `dc3043dd5166b113a97149f44b1494be1fef419dca41082eea51d8bd13bea8d0`。它的 1,000 条事件恰好对应关闭记录时磁盘快照的最后 1,000 条，包含 3 个历史会话；不能写成导出进程在关闭期间新采集了 1,000 条。[V10]

元信息中的 queue_dropped、disk_errors、memory_evicted、invalid_history 四项为 0，只描述导出进程本身。窗口之外的旧事件不在导出内，不能由这些零计数断言全部历史无丢失。预设隐私扫描未命中所检查的敏感字段或模式；允许的远端目的 IP 仍只保留在原始本地日志，因此结果不是全面无隐私信息的保证。[V10]

测试结束前记录开关已恢复 true 并落盘，随后 Claude 停止；临时显示和测试资源已清理，模块环境配置前后校验文件逐字相同。没有公开其他应用的前台信息或内部临时连接标识。[V10]

## 5. 证据位置

以下材料位于开发机项目中。构建日志和 APK 留在忽略目录；共享文档只保留可复核摘要。

| ID | 文件或来源 | 对应主张 |
| --- | --- | --- |
| V01 | `artifacts/privacy-audit-2026-11-17/device/final-0.4.1-result.json`；实际 APK；`output-metadata.json` | 版本、字节、摘要、执行步骤及总体结果 |
| V02 | `final-0.4.1-gradle.log`；`app/build/test-results/testDebugUnitTest/TEST-*.xml` | 构建和 52 项 Java 测试 |
| V03 | `app/build/reports/lint-results-debug.xml` | Lint 错误/警告及类型 |
| V04 | `artifacts/privacy-audit-2026-11-17/device/final-0.4.1-python.log` | 13 项 Python 测试 |
| V05 | 同目录 `final-0.4.1-contracts.log`；`artifacts/validation/static-contracts-0.4.1.json` | DEX、语言、配置、入口、ABI 和对齐 |
| V06 | 同目录 `final-0.4.1-signature.log` | APK v2 签名、调试证书 |
| V07 | 源码提交 `444bb9f9a5adc71d81b41472b16a1af64dd0ae10` 中的 AuditHooks、AuditPanel、AuditSanitizer、分析器及回归测试 | 修复实现和测试覆盖 |
| V08 | [首窗与后续设备报告](device-audit-2026-11-17.md)；[0.4.0 验证记录](validation-0.4.0.md) | 先前运行发现与历史测试边界 |
| V09 | CLI 提交 `4273ffc4ba3a787aa9450d90434a8b6a189af83c`；`ndjson-python-tests.log` | 原始 NDJSON 入口修复、14 项 Python 回归 |
| V10 | [设备审计报告](device-audit-2026-11-17.md)第 10 节；[聚合 JSON](device-audit-2026-11-17-export-validation.json)；最终设备状态记录 | 最终 APK 的真机交互、导出、恢复与实验限制 |

Java 可视测试报告为 `app/build/reports/tests/testDebugUnitTest/index.html`；Lint 可视报告为 `app/build/reports/lint-results-debug.html`。本轮真机结果已按独立阶段追加，注明版本、文件摘要与操作；首窗原始 JSONL 和 0.4.0 历史验证结论保持不变。长期和跨环境测试仍应另行记录。
