# Claude Android 非对话采集与上传：静态证据报告

审查日期：2026-11-17（Asia/Taipei）。样本为用户设备上已安装的 `com.anthropic.claude` **1.261112.18 / 26111218**。本报告记录安装包分析与官方披露核验；本轮实机日志、安装和弹窗验收由运行验证记录单独提供。

## 1. 已确认的核心结论

**该版本具有自动读取设备环境、记录使用行为、并将非对话事件批量上传的明确实现。** 最直接的路径是 Sift：Activity 创建回调在开关允许时启动环境采集，采集结果经事件队列进入 `v3/accounts/{account}/mobile_events`。因此，触发这条逻辑不要求用户先发送一条对话。采集字段包括 Android ID、设备与系统信息、运营商、Root／修改环境相关证据、电池状态和网络地址。[E01–E07、E17]

**产品分析和故障诊断也有独立的非对话链路。** `event_logging/v2/batch` 的事件类型包括产品行为、实验分组、窗口布局和应用性能指标。产品事件结构可携带账号／组织／匿名标识；分析上下文包含自有设备 ID、语言和 locale。Sentry 由应用启动代码手动初始化。Firebase 安装注册、FCM 通知注册和通知打开跟踪是另外的通信类别。[E08–E16、X01]

**当前证据支持“自动采集及非对话上传实现”，对“窃取”“用户完全不知情”“绕过系统权限”不作定性。** Anthropic 现行隐私政策已在类别层面披露自动设备、连接、使用和故障信息收集；用户实际看过哪些页面、当时选择、设备权限、组织策略和本轮网络请求结果需要对应记录。报告将这些问题逐项保留，避免把代码能力写成已经发生的设备事件。[P01–P03]

## 2. 样本、获取和证据范围

通过已授权 ADB 连接对 Claude 的 `pm path` 结果逐包执行 `adb pull`。本轮原件位于 `artifacts/privacy-audit-2026-11-17/apk/`，未进入 Git。四个安装包的本地 SHA-256 全部与既有 `artifacts/claude-1.261112.18/` 样本一致，因此既有 JADX 输出适用于本轮样本。

| 文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| base.apk | 60,220,071 | `73b5024d5bb6d1910391ccb3ebefb8a2692ee33d83c478e59a5407b72d6e91c4` |
| split_config.arm64_v8a.apk | 6,185,893 | `a308e4496e393239237b7fa297eaddc43fcf587fa2cdcc367c6f18cf51ae0e18` |
| split_config.xxxhdpi.apk | 61,648 | `2ae723119e61fb037a450e1f2d0ef57f582897948c949a534d5a0225a022f4c4` |
| split_config.zh.apk | 819,410 | `8aa009e758ea6e9841bec6457e254dfae3062ad53b7b2306f0209cac58bbcda3` |

既有 JADX 1.5.6 全量结果不完整，复杂协程中存在恢复失败和伪代码警告。本轮进一步直接解析原始 DEX 的方法／字段表，并用 Android `dexdump -d` 校验关键 endpoint 的指令引用。证据优先级为：原始 APK／DEX、精确签名、可定位的反编译调用链、未激活的 SDK 字符串。搜索未命中不作为“功能不存在”的结论。

本报告没有读取 Claude 账号偏好、会话内容、令牌、联系人、健康记录或用户安装应用清单。字段名与包探测常量均来自 APK 静态材料。

## 3. Sift：环境信息如何自动进入上传队列

### 3.1 触发与开关

`f2a0` 实现 `Application.ActivityLifecycleCallbacks`，`onActivityCreated(Activity, Bundle)` 在 `h2a0.q` 为 true 时调用采集协程 `v6g`，并传入 Activity 类的简单名称。[E01]

`re90` 的依赖装配分支把 `!zab0.x()` 作为 `h2a0` 的初始启用状态；`zab0.x()` 读取 `third_party_analytics_disabled_for_org`，缺省为 false。[E02、E03] 这说明样本包含组织层面的禁用条件；当前账号／组织的实际值应由运行配置确认。

`h2a0.b(eaj, y0u, boolean)` 还包含启用检查、相同事件去重、数量和时间判断，随后调度 `g2a0` 上传协程。[E07] 不能把每次敏感 API 读取数直接当成请求数。

### 3.2 设备与运行状态字段

| 结构 | 字段 |
| --- | --- |
| AndroidDeviceProperties | `app_name`、`app_version`、`sdk_version`、`mobile_carrier_name`、`mobile_iso_country_code`、`device_manufacturer`、`device_model`、`device_system_version`、`android_id`、`build_tags` |
| 环境证据列表 | `evidence_files_present`、`evidence_packages_present`、`evidence_properties`、`evidence_directories_writable`、可选 `installed_apps` |
| AndroidAppState | `activity_class_name`、`sdk_version`、`battery_level`、`battery_state`、`battery_health`、`plug_state`、`network_addresses` |

以上名称来自 `dt0` 和 `io0` 的序列化描述符。[E04、E05] 可选 `installed_apps` 的存在只确定该字段可表示；既有普通构造路径使用空列表，报告不据此声称上传了全量应用清单。

`cg40` 定义了 10 个 su／Root 相关文件、17 个特定修改工具包名、`ro.debuggable=1`／`ro.secure=0` 属性及若干系统路径的可写挂载检查。[E17] 这与任意扫描全部用户文件是不同范围；原始常量和调用证据保存在本地证据索引。

### 3.3 传输

`g2a0.invokeSuspend(Object)` 构造 `v3/accounts/{account}/mobile_events`，将事件序列化为 JSON，再 gzip 后交给网络层。APK 还包含 `https://api3.siftscience.com/`。[E06、X01] 路径里的 `{account}` 在样本装配中来自 Sift 配置对象，报告不将它直接解释为当前 Claude 用户 UUID。

日志应区分：

1. 采集调用已经执行；
2. 事件已经构建或入队；
3. 请求开始／尝试；
4. 收到 HTTP 响应或发生异常；
5. 被 fkclaude 配置阻断。

只有实际记录才能把一个具体时间点从前一阶段推进到后一阶段。Hook 的安装成功仅表示观察点准备完成。

## 4. 其他非对话通信

| 类别 | 静态目标或路径 | 已确认内容 | 运行解释 |
| --- | --- | --- | --- |
| 产品分析与实验 | `event_logging/v2/batch` | `raj.a(gw4, tnc0)` 构造批量请求；请求根字段为 `events` | 记录批次、事件类别与字段名；响应状态另记 |
| 使用体验跟踪 | `organizations/{organization}/experiences/track` | 请求构造代码 | 可属于功能展示与交互反馈；需关联触发动作 |
| 通知打开跟踪 | `organizations/{organization_uuid}/notification/push/track_open` | `lkv.d` 的 DEX endpoint 引用 | 与 FCM 注册分开分类 |
| Sentry 诊断 | `*.ingest.us.sentry.io`、`/envelope/` | 应用启动手动初始化和 envelope 路径构造 | 可含错误、性能和设备上下文；按请求元数据确认实际活动 |
| Firebase installations | `firebaseinstallations.googleapis.com/v1/` | `xkk.a(String)` 构造 URL | 安装实例／认证相关通信；不归为对话 |
| FCM 注册 | `fcmregistrations.googleapis.com/v1/projects/…` | `fd90.l` 使用 URLConnection | 可能包含推送注册凭据；日志只保存类别和路径模板 |
| Google CCT 诊断传输 | `firebaselogging.googleapis.com/v0cc/log/batch`、`firebaselogging-pa.googleapis.com/v1/firelog/legacy/batchlog` | `me6` 常量经 `bcr.J` 交错合并构成 URL；`j77` 添加设备自动元数据 | 属于随包 SDK 能力，实际调用仍需运行记录 |
| 登录与设备证明 | `auth/…`、`auth/session_reattest/device_key`、`auth/trusted_devices` | Play Integrity／可信设备代码及请求结构 | 认证类单列，避免与可选统计混在一起 |
| Datadog / Plaid SDK | Datadog intake 域名、`analytics.plaid.com/sentry/api/563/store/` | SDK 组件与 URL 字符串 | 标为静态候选；当前功能激活和实际出站状态待运行确认 |

证据见 [E08、E14–E16、X01] 及已有 [账号风控分析](claude-static-analysis.md)。

**端点命名不能单独决定用途。** `v1/code/sessions/{sessionId}/events` 和 `events/stream` 属于用户会话业务流，应归入会话／功能通信；仅凭含 `events` 就把它归入后台遥测会制造误报。普通配置获取、推送、认证、用户触发的功能上传和未知请求都需要单独类别。

### 4.1 产品事件里的标识与上下文

`ProductAnalyticsEventData` 定义 `event_name`、`event_id`、`event_timestamp`、`account_uuid`、`organization_uuid`、`anonymous_id`、`client_platform`、`client_app`、`properties`、`context`。部分字段可选。`tdz` 的上下文构造加入 `ant_device_id`、`primary_language` 和 `locale`。[E09、E10]

实验结构包含实验 ID、组别、设备／会话标识和可选用户属性；窗口结构包含布局类别、折叠／多窗口状态、版本、设备 ID 和可选型号。[E11、E12] 这些结构证明具有关联分析的表达能力；当次批次中哪些字段实际有值，以脱敏 schema 观察和日志为准。

`HealthMetricEventData` 在这里表示应用性能与可靠性：动作、界面、结果、错误类型、持续时间、重试次数、流式状态等。[E13] 这个类型名称不应被解释成人体健康记录。Health Connect 是另一组 Manifest 权限和产品能力。

### 4.2 Sentry 初始化

Manifest 的 `io.sentry.auto-init=false` 关闭 SDK 的自动初始化入口。Claude 自己在 `Application.onCreate` 的 `sentryInit` 阶段调用 `ec70.a(...)`，之后执行 `telemetryInit`。[E14] 审查时应跟踪实际应用初始化代码，而不能只看 Manifest 的这一项。

## 5. 权限与用户是否知情

静态 Manifest 声明了麦克风、精确／粗略定位、读写日历以及多种 Health Connect 读取权限。这是应用能够申请的权限清单；当前用户是否授予、触发哪个功能以及是否读取了数据，需要系统权限状态和相应 API 事件。完整声明保存在 `static-manifest.json`。本轮不把声明数量当成隐私侵害次数。

核验到的 [Anthropic 隐私政策](https://www.anthropic.com/legal/privacy) 生效日期为 **2026-09-10**。政策第 1 节披露自动设备／连接、标识符、使用和故障信息；第 2 节列出服务、安全反滥用、修复和研究等目的。[P01]

官方 [位置说明](https://privacy.claude.com/en/articles/11186740-does-claude-use-my-location) 区分可选功能位置使用与安全反滥用的 IP 推断粗略位置；移动端权限由设备管理。[P02] [Cookie 政策](https://www.anthropic.com/legal/cookies) 另列必要、分析和营销用途，但网页 Cookie 控制的范围不能自动扩大到每一个 Android 原生 SDK。[P03]

**披露粒度仍值得审查。** 上述页面以数据类别描述设备信息，没有在本轮核验段落中逐项列出 Sift 的文件／包／挂载证据字段。进一步判断“是否充分告知”需要将这份字段清单、应用内实际提示、账号和组织控制及适用要求逐项对照。这里不替代法律判断，也不推测用户此前是否阅读过告知。

## 6. 给审计实现的精确观察点

以下方法／字段已经从本轮原始 DEX 表确认，适配版本严格限定为 26111218：

| 观察点 | 合同 | 可安全记录的信息 |
| --- | --- | --- |
| OkHttp 同步／异步汇合点 | `ko10.f(): w040` | 调用 ID、开始／完成、异常类型、耗时 |
| 请求对象 | `ko10.b: bj30` | 关联到本次请求 |
| URL／方法／body | `bj30.a: o6n`、`b: String`、`d: rj30` | 方法、脱敏 host／路径、body 类别 |
| URL host／path | `o6n.d: String`、`o6n.b(): String` | 路径按模板脱敏，不保留 query／fragment |
| HTTP 状态 | `w040.d: int` | 状态码 |
| body 元数据 | `rj30.a(): long`、`b(): z7t` | 实现允许时读取长度和内容类型 |
| 产品批次 | `raj.a(gw4, tnc0)`、`fw4.serialize(ami, Object)` | 事件条数／结构名；对字段值保持脱敏 |
| Sift 队列 | `h2a0.b(eaj, y0u, boolean)` | 入队／阻断、对象类型、静态字段名 |
| Sift 上传协程 | `g2a0.invokeSuspend(Object)` | 上传链进入；与实际请求关联 |

不要为日志复放 `rj30.e(b16)`：它是写出请求体的接口，复放可能重复读取一次性流或改变应用行为。不要对未知对象调用 `toString()` 作为“方便的详情”，它可能包含账号、请求正文、Cookie 或令牌。

Java HTTP Hook 的覆盖还需要与 URLConnection、WebView、Cronet／QUIC 和原生 socket 分别核算。原生传输只能给出地址／端口和字节等低层事实时，应保留“协议／用途未知”；TLS 密文长度不足以恢复上传字段。

## 7. 日志如何支撑结论

人类可读日志与 AI 分析应共用一份结构化事件，至少区分时间、观察点、类别、阶段、关联请求、结果、脱敏细节及证据级别。静态字段列表可以作为 schema 标签，但必须标明来自静态样本，避免看起来像一次实际读取的值。

当前 fkclaude 隐私预设会拦截 Sift 和部分遥测域名。启用这些规则时，日志应显示“尝试后被阻断”；如果没有请求完成记录，就不把数据计作已到达服务端。弹窗关闭只影响展示还是同时停止采集，也必须在界面和导出中明确。

一套完整运行验收需要记录：

- 模块版本、Claude 版本、观察起止时间和有效 Hook 范围；
- 当时已启用的阻断规则和记录开关；
- 前台／后台／空闲／打开设置等实际操作；
- 请求尝试、完成、阻断和未知传输分别计数；
- 未被触发的功能、不能解析的协议、丢弃或限流事件数量。

这些是判读运行结果所必需的实验条件，本静态报告不填入未执行的次数和结果。

## 8. 证据索引与复核

本轮静态证据均位于 `artifacts/privacy-audit-2026-11-17/`：

| 文件 | 内容 |
| --- | --- |
| static-inventory.json | 四个 APK 的大小、SHA-256、与旧样本比较 |
| static-hook-contracts.json | 原始 DEX 中相关方法和字段合同 |
| static-schemas.json | Sift／产品分析／布局／实验／性能／证明结构字段 |
| static-endpoint-xrefs.json | dexdump 的关键 endpoint 类、方法、指令和相邻指令 |
| static-dex-endpoints.json | DEX 字符串候选；解释时需结合引用与实际调用 |
| static-manifest.json | 目标包权限和相关初始化元数据 |
| static-evidence-index.json | E01–E17 的源文件、行号、文件摘要和定向摘录 |

`D/` 代表 `artifacts/claude-1.261112.18/decompiled/sources/`，`S/` 代表同样本的 `selected/`。

| 编号 | 本地定位 | 支持的主张 |
| --- | --- | --- |
| E01 | `D/defpackage/f2a0.java:18–26` | Activity 创建触发采集协程 |
| E02–E03 | `D/defpackage/re90.java:142–178`；`zab0.java:740–742` | 组织分析开关和 Sift 启用装配 |
| E04–E05 | `S/dt0.java:14–29`；`S/io0.java:14–21` | 环境和运行状态的字段结构 |
| E06–E07 | `S/g2a0.java:139–158`；`D/defpackage/h2a0.java` | JSON／gzip 上传、队列与去重条件 |
| E08–E10 | `D/defpackage/raj.java:11–16`；`vdz.java`；`tdz.java:10–24` | 产品事件批次、标识字段与上下文 |
| E11–E13 | `D/defpackage/m4m.java`；`rii0.java`；`lhm.java` | 实验、布局、应用性能结构 |
| E14 | `D/com/anthropic/claude/application/ClaudeApplication.java:148–201` | 手动初始化诊断和 telemetry |
| E15–E16 | `D/defpackage/j77.java:75–138`；`me6.java:19–26`；`bcr.java:76–89` | CCT 元数据与 URL 构造 |
| E17 | `D/defpackage/cg40.java:8–11` | 固定文件、包、属性和挂载检查集合 |
| X01 | `static-endpoint-xrefs.json`、`static-dex-endpoints.json` | 原始 DEX endpoint／字符串交叉验证 |

## 9. 主张与证据自检

| 自检维度 | 结果 |
| --- | --- |
| 贡献 | 明确了自动环境采集的生命周期触发、组织开关和出站链，并把不同非对话用途分开 |
| 清晰度 | 静态能力、运行命中、请求尝试、HTTP 结果和阻断使用独立术语 |
| 实验强度 | 重新取得 APK，并复核精确 DEX 合同；本文件不报告未运行的动态次数 |
| 评估完整性 | 列出 Java、WebView、Cronet／原生及第三方 SDK 的覆盖差异；运行验收需保留缺口 |
| 方法有效性 | 不读取账号数据，不复放 body，不把 API 权限或类名直接定性为数据窃取 |

| 主要主张 | 证据 | 状态 |
| --- | --- | --- |
| 包含无需发送对话即可触发的设备采集逻辑 | E01–E03 | 已由静态调用链确认 |
| 采集结构含 Android ID、环境证据和网络／电池状态 | E04–E05、E17 | 已由结构和常量确认 |
| 存在 Sift 和产品事件上传实现 | E06–E10、X01 | 已由构造、序列化、DEX 引用确认 |
| 当前设备在某时刻把某值发到服务端 | 对应运行日志及协议证据 | 本静态报告不作此断言 |
| 用户未获充分告知或发生违法窃取 | 实际告知／选择、完整行为证据、适用规则 | 尚无足够材料作此定性 |

## 官方资料

- P01：[Anthropic Privacy Policy](https://www.anthropic.com/legal/privacy)，生效 2026-09-10，本轮访问 2026-11-17。
- P02：[Does Claude use my location?](https://privacy.claude.com/en/articles/11186740-does-claude-use-my-location)，页面日期 2026-03-16，本轮访问 2026-11-17。
- P03：[Anthropic Cookie Policy](https://www.anthropic.com/legal/cookies)，生效 2024-03-19，本轮访问 2026-11-17。
