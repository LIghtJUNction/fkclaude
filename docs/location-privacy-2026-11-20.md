# 位置隐私与美国地区策略分析

日期：2026-11-20。目标应用：Claude Android `com.anthropic.claude` **1.261112.18 / 26111218**。

**0.4.4 已安装并通过严格美国配置、两次冷启动和快照故障恢复验收；当前没有已验证的美国出口，空白名单使 Claude 保持离线。** 已覆盖的本机定位与附近信息入口拒绝提供数据，必要地区字段在 Claude 进程内采用美国值。联网准入同时要求美国探测结果、明确 IP／CIDR、匹配的地址族与有效许可；手机系统地区设置保持原样。

最终源码为 `736b234`，Java 132 项与 Node 22 项通过，安装 APK 与交付副本的摘要一致。两次冷启动均 15/15 回读，原生观察器均 7/7 安装、0 失败；稳定窗口的 971 条有效审计事件没有应用正字节或探测记录。该结论限定于审计可见路径，本轮没有系统全流量抓包。补修前的 593 条样本与 partial 状态单独保留，避免混用构建证据。

## 1. 位置信息从哪里进入

定位接口、网络出口和账户内容是三条独立入口。每条入口需要对应的控制，单独修改语言或时区不会改变网络路由。

| 入口 | 可携带的位置信息 | 本轮控制与范围 |
| --- | --- | --- |
| 本机接口 | 当前／缓存坐标、持续定位回调、附近 Wi-Fi 或蜂窝信号、SIM 与当前网络国家、时区等 | 拒绝本机位置与附近信息；在进程内固定必要地区字段；系统权限作为独立限制 |
| 网络出口 | 服务端看到的连接来源地址，以及基于 IP 的地区判定 | 显式 CIDR、美国探测、地址族与有效期共同决定进程内准入；固定美国 VPN 与非 VPN 连接阻断是进一步所需的系统条件，本轮尚未配置 |
| 账户与内容 | 账户联系和付款资料、历史输入、上传附件、连接服务提供的数据 | 由账户设置、授权和实际发送内容决定；当前模块不修改服务器历史资料 |

### 官方声明与本次观察分别记录

Anthropic 的消费者隐私政策生效于 **2026-09-10**，列出的数据类别包括账户身份和联系资料、付款资料、输入输出，以及设备和连接信息。后者包括移动网络、运营商／ISP、时区、IP 及其地区推断、标识符和设备位置。这是官方声明的处理范围；本报告没有把该清单列为“本轮已观测到全部上传”。[隐私政策][anthropic-policy]

Anthropic 在 **2026-03-16** 的位置说明中区分两种用途：产品功能可使用 IP 推断城市／地区，可选用途有相应设置；平台安全和反滥用会使用 IP 与其他信号推断国家／地区，这部分没有关闭开关。移动端的位置访问通过设备权限管理。[位置说明][anthropic-location]

当前 Android 帮助文档说明，位置相关请求可以触发定位授权，用户可以在系统应用权限中拒绝，其他功能仍可继续使用。因此，本轮检查精确和粗略权限，不把“仅允许粗略位置”作为隐去真实地区的验收状态。[Android 功能权限][claude-android] [Android 定位权限][android-location]

账户和内容仍需独立处理。依据上述政策，用户提交的内容进入服务处理范围；由此可知，在正文、附件或历史资料中已经出现的地点，不会因为当前设备接口返回值改变而消失。该判断属于数据入口分析，本轮没有读取用户账户、历史对话或私人附件。

## 2. 已实现的美国隐私策略

### 2.1 配置在加载和保存时统一约束

`privacy_us_only` 是显式开启项。开启后，`UsPrivacyPolicy` 会强制启用本机位置及相关个人数据保护、原生与网页保护、IP 守卫和必要遥测阻断；移除经纬度配置，地区字段采用 `en-US`、`America/New_York` 与国家代码 `us`。

同一规则作用于普通字段、相关系统属性和原生环境变量，避免另一份配置覆盖回真实地区。已有身份、IP 白名单和无关自定义字段保留；自定义域名阻断列表与策略必需项合并。普通预设应用到已经开启的严格配置时继续保留该策略。需要修改必需保护时，应先明确关闭仅美国策略。

`Profile` 在输入检查后应用规则，再验证扩展后的值。构造、解析、预设和界面保存共用这一过程，保存后界面显示规范化后的有效配置。无效 CIDR、越界间隔、过长字段等输入会拒绝处理。

源码入口：[UsPrivacyPolicy.java](../app/src/main/java/io/github/lightjunction/fkclaude/UsPrivacyPolicy.java)、[Profile.java](../app/src/main/java/io/github/lightjunction/fkclaude/Profile.java)、[MainActivity.java](../app/src/main/java/io/github/lightjunction/fkclaude/MainActivity.java)。

### 2.2 本机位置与地区信号

位置保护需要同时覆盖缓存、单次请求和持续回调。Android 的 `getCurrentLocation` 使用异步结果，并定义取消行为；Google Fused Location Provider 也有独立入口。网页定位还涉及 WebView 设置及按 origin 保存的许可状态。[LocationManager][location-manager] [Fused Location Provider][fused-location] [WebView 定位设置][web-settings]

本轮补强系统位置、蜂窝与附近设备入口，并将严格模式下 WebView 定位关闭；关键初始化失败采取拒绝继续的处理。地区覆盖涉及 Java／ICU、系统属性和原生环境。具体覆盖范围以源码与对应回归为准，尚未执行的设备分支保留为未验证。

SIM 国家与当前网络国家需要分开处理。`getNetworkCountryIso()` 及其 slot 重载可以返回当前运营商或附近基站国家，`getSimCountryIso()` 则描述 SIM 提供商；当前网络国家还有广播入口。附近 Wi-Fi 与 Bluetooth 权限也分别约束相关接口。[TelephonyManager][telephony] [Wi-Fi 权限][wifi-permissions] [Bluetooth 权限][bluetooth-permissions]

源码入口：[EnvironmentHook.java](../app/src/main/java/io/github/lightjunction/fkclaude/EnvironmentHook.java)、[WebEnvironment.java](../app/src/main/java/io/github/lightjunction/fkclaude/WebEnvironment.java)、[LocationPrivacy.java](../app/src/main/java/io/github/lightjunction/fkclaude/LocationPrivacy.java)。

### 2.3 联网准入

严格模式要求以下条件同时满足：

1. 白名单由用户明确配置，匹配当前被检查的出口地址；空名单拒绝联网，并且不进行出口探测。
2. 探测响应包含唯一合法 IP 和唯一、明确的 `loc=US`；缺失、冲突、非美国或解析失败均拒绝。
3. IPv4 和 IPv6 分别核验，返回地址必须与被检查的地址族一致。
4. 许可受有效期和网络状态约束，错误或未知状态保持拒绝。

新预设固定使用 `https://www.cloudflare.com/cdn-cgi/trace`，预检不先访问 Claude 域名。探测服务仍会看到访问它的出口地址；报告仅保留准入结果，不记录该地址。

源码已按可验证改动分五批推送：

| 改动 | 提交 |
| --- | --- |
| 网络准入 | [17f3772](https://github.com/LIghtJUNction/fkclaude/commit/17f3772541a0191b445575c7b75bf0e9a0956a38) |
| 原生写入口与探测 socket 身份 | [2cd1b93](https://github.com/LIghtJUNction/fkclaude/commit/2cd1b9364f9bf7f56f6082b32f0e322d3626ff07) |
| Android 策略与位置保护 | [80c7b0a](https://github.com/LIghtJUNction/fkclaude/commit/80c7b0ac660b259908b940e1cb292b59385df66d) |
| 原生守卫与审计共享观察入口 | [4e73ea4](https://github.com/LIghtJUNction/fkclaude/commit/4e73ea475b55603b8b60b34269cc229d23bc23b5) |
| 原子配置、保存基线和元数据最小化 | [736b234](https://github.com/LIghtJUNction/fkclaude/commit/736b234b3f1433bcde2926c6cb3bcb1f46a08999) |

最终 APK 绑定第五个源码提交；后续文档提交与制品源码分别记录。

### 2.4 正确解释 Cloudflare 探测

Cloudflare 官方文档用 trace 的 `ip` 验证代理出口，`loc` 对应出口的地区判定。`colo` 描述处理请求的数据中心，使用附近机场代码；它与访问者的 IP 地区是独立概念。判定条件使用 `loc=US`，美国机房代码不参与放行。[Cloudflare 连接验证][cloudflare-trace] [Cloudflare 地理路由][cloudflare-routing]

一次探测的证据范围是该请求的链路。不同目的地、IPv4／IPv6、TCP／UDP 或代理规则可能选择不同路径，因此全量流量还需要固定出口和系统网络限制。界面与报告应显示“探测报告美国”及有效状态，避免将一次探测扩大成所有目的地的路由结论。

### 2.5 配置持续生效与元数据最小化

发布前源码复核发现三条可削弱已开启策略的路径：Provider 可能迁移或返回旧 legacy 值，框架桥接发布失败可能留下旧配置，仍存活的旧 Activity 可能按过期界面保存并覆盖新策略。补修前两次真机启动均读取正确的 framework 配置，未观察到这些降级；缺陷根据源码触发条件修复。

最终实现使用私有、原子保存的权威快照，已保存策略不被 legacy 抢占。保存时比较界面读取的配置基线，拒绝旧恢复状态或旧 Activity 覆盖新策略；同一内容的发布重试和首次创建竞态有独立回归。损坏加载、持久化失败或框架发布失败均明确报错，目标进程在配置读取失败时停止。现在要求框架桥接可靠可用；桥接不可用会暂停编辑或拒绝发布，兼容行为不再将仅 Provider 可用视为保存完成。

配置文本不再附加 `Properties.store` 自动生成的本地日期和时区注释，转义与跨时区确定性编码已验证。快照的 SHA-256 用于检出意外损坏，不提供对同 UID 恶意写入者的身份认证。配置可靠性的 24 项回归包含在 Java 132 项总数内，涵盖原子快照校验、legacy 优先级、失败返回与异常、基线比较、重试和编码。

源码入口：[ProfileStore.java](../app/src/main/java/io/github/lightjunction/fkclaude/ProfileStore.java)、[Profile.java](../app/src/main/java/io/github/lightjunction/fkclaude/Profile.java)、[MainActivity.java](../app/src/main/java/io/github/lightjunction/fkclaude/MainActivity.java)。真机已验证损坏快照会使模块界面暂停编辑，恢复原文件后可重新读取并保存；注入期间 Claude 保持停止，目标运行时的损坏路径依据源码与本地回归评价。详见第 4 节。

### 2.6 防护入口与审计入口分别计数

原生网络守卫要求 11 个符号，新增 write／writev／sendfile／sendfile64／splice 五类写入口；sendfile 与 sendfile64 可能解析到同一地址，因此符号数不等于独立 patch 数。探测请求的写入豁免同时核对私有线程与 socket 的文件描述符身份，描述符复用不继承旧豁免。

补修前设备报告原生观察器 partial。复核发现 write／writev 被守卫和审计重复安装；最终实现由守卫统一拥有 7 个观察入口，审计复用同一元数据通道。宿主回归确认允许和阻断的 write／writev 各记录一次，审计暂停时守卫继续生效，非法数据指针不被读取。sendfile／sendfile64／splice 本轮只补防护，未扩展其审计事件类型。最终 APK 的两次冷启动均确认 7 个请求的观察入口全部安装、失败数为 0；这不代表未列入观察器的路径也已覆盖。

### 2.7 网络审计重点：记录、筛选与实际阻断

用户要求优先处理可能泄露隐私、数据或影响账号的网络上报。当前可交付的控制是：保留脱敏网络证据，在已适配入口阻断已知采集与遥测，并在缺少可信美国出口时保持空名单拒绝。此次没有为演示联网而暂时放宽策略。

审计面板的“非对话网络”开关只设置 `onlyNonConversation`，传入 `AuditLog.snapshot(...)` 过滤列表。它不会改变请求的准入结果。实际阻断路径如下：

| 阻断层 | 已实现动作 | 证据与适用范围 |
| --- | --- | --- |
| Sift 采集与上报入口 | `UsPrivacyPolicy` 强制 `block_sift`；`EnvironmentHook` 在已定位的 collect／queue／dispatch 前阻断 | 目标版本源码与入口合同；未知 SDK 和版本变化另行分析 |
| 已知遥测域名 | 必需 `TELEMETRY_HOSTS` 合并用户 `blocked_hosts`，DNS 路径拒绝清单内 Sentry／Datadog／Firebase 等域名 | 域名清单与解析路径；同一 API 域名内的遥测、未知域名和直接 IP 路径不能仅靠此层识别 |
| 网络出口准入 | `IpGuard` 与原生守卫按美国探测、明确 CIDR、地址族和有效期决定放行 | 当前空名单；最终冷启动与审计窗口支持已覆盖路径拒绝 |

审计记录目的地、请求路径、结果、耗时和可观察字节统计，不读取完整 TLS 正文。观察到某个域名或路径，不足以证明其负载包含某个私人字段；未观察到正字节，也不能证明所有底层路径均无流量。本轮没有宣称已识别或过滤每一份敏感数据包。

同域 API 遥测、未覆盖的原生直连、直接 syscall、未知 SDK／版本和账户历史仍需独立证据及约束。服务器如何使用既有记录、关联信号或限制账户，不由进程内字段和网络观察器决定，因此没有“不会封号”的保证。恢复联网前，应确认受信美国出口，并验证必要业务请求链路、遥测拒绝结果和系统路由条件；当前保持阻断。

源码入口：[AuditPanel.java](../app/src/main/java/io/github/lightjunction/fkclaude/AuditPanel.java)、[AuditLog.java](../app/src/main/java/io/github/lightjunction/fkclaude/AuditLog.java)、[EnvironmentHook.java](../app/src/main/java/io/github/lightjunction/fkclaude/EnvironmentHook.java)、[UsPrivacyPolicy.java](../app/src/main/java/io/github/lightjunction/fkclaude/UsPrivacyPolicy.java)。公开分析仅使用脱敏状态与汇总，不上传原始数据包、请求正文或私人日志。

## 3. 固定美国出口所需的系统条件

**应用内守卫之外，系统层应将 Claude 的流量固定到经过确认的美国 VPN 出口，并启用非 VPN 连接阻断。** Android 的 always-on 负责随开机启动并保持 VPN 服务；“阻止未使用 VPN 的连接”负责在隧道不可用时阻断其他网络路径。[Android VPN][android-vpn]

配置时需要检查该用户或工作资料的实际范围、应用包含／排除列表，以及 IPv4 和 IPv6 路由。Android 文档说明，普通 per-app 排除项会走系统网络；开启非 VPN 阻断后，这些应用失去网络访问。允许绕过 VPN 的网络绑定也受该系统阻断限制。

这套系统机制控制网络路径；美国出口的选择和稳定性仍由 VPN 配置与服务决定。服务端也继续保有自身的地区数据库、账户资料和内容上下文。因此，本报告分别记录“本机来源保护”“探测准入”和“系统路由条件”，不使用一个总开关状态替代三者的实际结果。

本轮不根据探测发现的地址自动生成白名单，也不将 `0.0.0.0/0` 或 `::/0` 作为自动兜底。缺少明确批准的出口时，离线是策略规定的行为。

## 4. 当前验证证据

### 4.1 手机初始状态

以下是本轮部署前的只读检查，用来说明已有约束与需要补齐的条件。

| 项目 | 初始观察 |
| --- | --- |
| 系统／架构 | Android 16 / arm64 |
| Claude 版本 | 1.261112.18 / 26111218 |
| 精确、粗略定位权限 | 已拒绝 |
| 后台定位、附近 Wi-Fi、Bluetooth、电话、媒体位置权限 | 目标包未声明 |
| Always-on VPN | 未启用 |
| 非 VPN 连接阻断 | 未启用 |
| Claude 进程 | 本轮初始未运行；保护准备完成前保持停止 |
| 模块 IP 守卫／白名单 | 守卫关闭；白名单为空 |
| 模块位置／地区字段 | 位置阻断开启、地区为美国值；时区尚未采用美国策略值 |

这些结果没有包含真实坐标、设备序列号、出口 IP、账户标识或原始日志。定位权限拒绝限制了对应本机接口，IP 地区推断由网络入口单独处理。

已执行精确／粗略定位权限撤销，并将对应 AppOps 设为 ignore，读回结果符合要求。随后通过模块 UI 应用并保存美国配置；本节表格保留激活前基线，实际生效结果见设备验收。

### 4.2 最终构建与本地回归

| 验证 | 结果 | 统计说明 |
| --- | --- | --- |
| Java 集成 | **132/132 通过**，失败／错误／跳过均为 0 | 绑定最终源码 736b234 |
| 配置可靠性 | 24 项通过 | 已包含在 Java 132 项中，覆盖快照、迁移、异常、基线与编码 |
| Node 网页回归 | **22/22 通过** | 独立网页测试；后续源码改动未涉及这些脚本 |
| 网络纯 Java 定向回归 | 25 项通过，含 16 项原有与 9 项新增 | 已包含在 Java 132 项中，不重复相加 |
| 原生宿主防护回归 | 新增 5 类写入口和探测 socket 文件描述符身份校验通过 | 真实回环 socket 与包装层，ASan／UBSan；未在宿主执行真实 inline patch |
| 原生共享审计回归 | write／writev 允许与阻断各恰好一条；暂停仍防护、无重复安装 | 非法数据指针、errno、探测标记、本地 file／pipe／Unix 分别检查 |
| NDK 编译检查 | arm64 / API 28 语法检查通过 | 原生代码编译兼容性 |
| Lint | 0 错误、10 个既有警告 | 最终 Android 构建 |
| DEX 合同 | 8 项方法、31 项字段通过 | 最终制品 |
| 配置与语言 | 95 个配置项，英文／简体中文完整 | 最终制品 |
| 原生对齐与 APK 签名 | arm64 ELF／ZIP 16 KiB 对齐、v2 签名通过 | 最终制品 |
| 同伴复核 | 策略规范化、身份与白名单保留；配置失效路径已补修 | 损坏快照的设备界面拒绝与恢复另列证据 |

本轮没有重跑 Python CLI，历史验证保留在 [0.4.3 验证记录](validation-0.4.3.md)，不计作本轮执行结果。

| 制品项 | 已核验值 |
| --- | --- |
| 模块版本 | **0.4.4 / versionCode 9** |
| 源码提交 | `736b234b3f1433bcde2926c6cb3bcb1f46a08999` |
| 构建 APK | `app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | **4,466,009 字节** |
| SHA-256 | `76f51a58d10383ba2b9507a3545bbf4c9f60c798f6302345d65f9267816b2446` |
| 手机安装端核对 | 最终 APK 安装成功，安装文件 SHA-256 与构建一致 |
| 开发机交付副本 | `artifacts/fkclaude-0.4.4.apk`，大小与 SHA-256 均一致 |

### 4.3 最终设备验收

**实际模块 UI 已保存美国隐私配置，最终 APK 的两次冷启动均读取 framework 活动配置。** strict=true、guard=true、白名单为空；地区配置为 `en-US`、`America/New_York`，SIM／当前网络国家 `us`，独立 Cloudflare 探测端点。原虚拟身份前后比较一致，报告不记录实际值或私有哈希。

| 场景 | 已观察结果 |
| --- | --- |
| 最终安装与摘要 | 0.4.4 / code 9，安装文件与第 4.2 节 SHA-256 一致 |
| 两次冷启动 | 每次均 15/15 诊断回读，framework 通道、严格策略与空名单阻断，无初始化失败 |
| hook 安装记录 | 208 个整体安装项；不作为原生网络入口数 |
| 共享原生观察器 | 两次均 installed=7、requested=7、failed=0，已无 partial 状态 |
| 权威快照 | 长度和摘要正确；与框架桥接的配置语义一致；没有本地日期／时区注释 |
| 审计界面 | 真机显示正常，安全截图已检查 |
| 原虚拟身份 | 保持不变 |

快照与框架 XML 按配置语义核对，XML 文本外侧的空白可不同；未将文本逐字相等作为必要条件。15 项回读覆盖运行时诊断值，未扩展为所有异步、取消和回调分支。`SubscriptionManager#getActiveSubscriptionInfoForIccIndex` 在该系统不可用，未计为已覆盖。

最终稳定窗口汇总如下：

| 审计观察 | 结果 |
| --- | ---: |
| 有效事件／无效记录 | 971 / 0 |
| DNS 调用／失败 | 47 / 47 |
| HTTP 尝试／失败 | 33 / 33 |
| 原生连接阻断 | 6 |
| 应用成功发送正字节的观察结果 | 0 |
| 探测事件 | 0 |

计数描述尝试与观察结果，不跨类别相加为请求总量。“正字节观察为 0”只描述已安装观察器能看到的路径；本轮未进行系统全流量抓包。空名单不启动探测的源码规则与该窗口的零探测记录一致。

### 4.4 真机快照故障注入与恢复

临时损坏模块权威快照后，设置界面仅显示“暂停编辑／重新读取”，没有保存或预设操作。恢复原文件后，内容、owner 与 mode 均核对一致，私有故障备份已删除；点击重新读取恢复正常界面，再次保存出现成功提示。严格策略、守卫、空白名单和原虚拟身份均保持。

注入期间 Claude 始终停止。该实验验证模块界面对损坏配置的拒绝与恢复；目标进程正在运行时遇到损坏的行为、桥接中断和各类存储故障由对应源码及本地回归支持，没有扩写为全部设备故障场景实测。

当前无已验证美国出口，系统 always-on VPN 与非 VPN 连接阻断均未配置；在线放行、真实出口切换和 VPN 掉线场景未验收。恢复联网需要明确批准的固定美国出口及 IP／CIDR，并完成相应系统路由验收。

### 4.5 补修前设备观察

本小节只描述源码 `80c7b0a` 的先前构建，APK SHA-256 为 `f49750d87de62a07548af09517a55c526bd31924cb5eb4c2ed9bcaa8247e5d0f`；不作为最终 `736b234` 构建的设备证据。

实际模块 UI 已应用并保存美国隐私配置，目标进程两次冷启动均读取框架活动配置。每次均有 15/15 诊断回读符合预期，空名单明确阻断，没有初始化失败；第一次启动记录 208 个整体 hook 安装项。虚拟身份前后哈希比较一致，报告不保留身份或其私有哈希；审计面板显示正常。

首次会话包含 **593 条脱敏审计事件**。原生观察器安装状态为 `partial`，下表限定于这个已覆盖窗口。

| 观察 | 结果 |
| --- | ---: |
| DNS 调用／失败 | 32 / 32 |
| HTTP 尝试／失败 | 17 / 17 |
| 原生连接阻断 | 6 |
| 应用成功发送正字节的观察结果 | 0 |
| 探测事件 | 0 |

计数描述尝试与观察结果，不跨类别相加为请求总量。15 项回读是运行时诊断值，不代表全部异步、取消和回调分支；208 也不是原生网络入口数。`SubscriptionManager#getActiveSubscriptionInfoForIccIndex` 在此系统不可用，未计为已覆盖。没有系统全流量抓包，因此正字节观察为 0 不能升级为全部出站流量为 0 的证明。

该次验收结束时，原有 1 个审计文件已恢复且内容指纹一致，测试记录、checkpoint、私有身份哈希、临时录屏、ADB 包装、辅助 JAR 和虚拟显示已删除；Claude 已停止，严格策略与定位拒绝保留，ADB 服务保留。上述清理属于补修前测试轮；最终轮的独立恢复与清理结果如下。

### 4.6 最终证据、恢复与清理

| 项目 | 最终结果 |
| --- | --- |
| 原审计历史 | 1 个文件已恢复，内容指纹与原记录一致 |
| 私有测试材料 | 测试审计、历史 checkpoint、快照故障备份和身份哈希文件已删除 |
| 设备测试资源 | 临时录屏、ADB 包装、helper／JAR 与虚拟显示已删除 |
| ADB 服务 | 保留 |
| 设备状态 | Claude 已停止；严格美国策略、守卫、空白名单与定位拒绝保留；虚拟身份未变 |
| 系统路由 | always-on VPN 与非 VPN 连接阻断均未配置；没有已验证的受信美国出口 |
| 交付副本 | `artifacts/fkclaude-0.4.4.apk`，4,466,009 字节，与构建及安装端 SHA-256 一致 |

开发机安全证据目录为 `artifacts/iteration-0.4.4-2026-11-20/`，最终设备证据位于其 `final-device/` 子目录。主要 JSON 为 `installed.json`、`snapshot-fault.json`、`device-startup.json`、`device-restart.json`、`device-audit-summary.json`、`device-cleanup.json`；构建、合同和签名摘要与最终源码及 APK 绑定。证据目录处于 Git 忽略范围。

仅保留经过检查、无个人值的 `snapshot-rejected.jpg` 和 `audit-ui-us-mode.jpg`。原始私人记录、实际地区与时区、IP、账号及设备序列号均未进入公开报告。

## 5. 结论范围与剩余入口

**这份报告针对当前目标版本及本轮已覆盖路径。** 模块在 Claude 进程内工作，系统 VPN、其他 UID 代发、未覆盖的底层路径和服务端历史数据分别有自己的边界。版本变化、网络变化和配置变化需要重新核验相关条件。

权限审计只能覆盖它实际跟踪的操作。Android AppOps 记录受保护操作的 `noteOp`／`startOp`；IP 推断、正文中的地点及其他未触发这些操作的数据入口，需要各自证据。[AppOpsManager][appops]

上传内容也是明确的输入路径。Android 为读取未去除的照片 EXIF 位置设置了 `ACCESS_MEDIA_LOCATION` 权限；正文、图片内容和主动分享的附件仍应按实际内容处理。本轮没有读取私人附件，也没有把系统定位保护描述成通用的内容脱敏器。[共享媒体与位置元数据][media-location]

服务端对账户地区的最终判断属于其自身处理范围。Anthropic 已公开说明会综合 IP 与其他信号。当前工程目标是减少本机来源、限制出站条件，并提供可核验的拒绝状态；“所有个人信息均不处理”和“账户永远被认定为美国”未作为本轮实现结果。

## 6. 声明与证据对应

| 声明 | 证据 | 状态 |
| --- | --- | --- |
| 位置存在本机、网络、账户／内容三条入口 | 官方政策、平台权限与数据入口分析 | 已支持 |
| 美国策略统一覆盖冲突配置，保留明确出口与身份 | UsPrivacyPolicy／Profile 源码及针对性回归 | 已支持 |
| 单次探测使用 loc，机房 colo 不参与国家判定 | 网络源码、回归与 Cloudflare 官方文档 | 已支持 |
| “非对话网络”仅筛选显示，实际拦截另有代码路径 | AuditPanel／AuditLog、Sift 与 DNS 规则、IpGuard 源码 | 已支持；未声称对所有 TLS 负载分类 |
| 网络审计能证明所有隐私数据包被识别或账号不会封禁 | 审计仅有有界元数据，服务端决策另有输入 | 不作此保证 |
| 空名单拒绝且不自动信任当前出口 | 策略回归、最终两次冷启动与 971 条审计窗口 | 已支持，限定覆盖路径 |
| 最终 APK 构建与本地验证完成 | 0.4.4 / 9、132 项 Java、22 项 Node、原生与制品检查 | 已支持 |
| 快照损坏、保存失败和旧界面不能静默降级 | 原子快照、保存基线及 24 项配置回归 | 本地通过；真机确认损坏快照时界面暂停编辑，恢复后保存成功 |
| 配置编码不附带本地日期／时区注释 | 确定性与转义、跨时区回归 | 已支持 |
| 共享观察器避免 write／writev 重复安装 | 共享设计、ASan／UBSan 与最终两次安装状态 | 宿主通过；真机均 7/7、0 失败 |
| 经批准的美国出口在线使用 | 尚缺受信出口与系统路由条件 | 当前空名单离线，未验收在线放行 |
| 私人数据未写入公开分析 | 本报告只记录权限、状态、计数及公开源 | 已执行 |

## 7. 五项自审

| 检查 | 结论 |
| --- | --- |
| 改动价值 | 本机位置来源、网络准入、配置持续生效分别受约束；空名单和未知状态有明确拒绝行为。 |
| 表达清晰 | 地区字段、探测地区、机房与账户判断分别定义；显示筛选、元数据审计和实际阻断分别列出，补修前结果与最终构建分开标识。 |
| 实验强度 | 最终 APK 绑定本地回归、两次冷启动、审计窗口与快照界面故障注入；在线出口和系统抓包未列为通过。 |
| 验证完整性 | 配置失效与重复观察器已修复；安装摘要、原历史恢复、身份保留和清理均有独立最终记录。 |
| 实现合理性 | 策略集中规范化、配置原子保存并比较基线，不自动扩充信任；有效期和地址族参与准入。 |

## 参考资料

以下官方页面于 2026-11-20 核验；页面明示的更新时间保留在对应条目中。

- [Anthropic Privacy Policy][anthropic-policy]：生效 2026-09-10。
- [Does Claude use my location?][anthropic-location]：2026-03-16。
- [Use Claude with Android apps][claude-android]。
- [Android 定位权限][android-location]、[LocationManager][location-manager]、[Fused Location Provider][fused-location]。
- [TelephonyManager][telephony]、[Wi-Fi 权限][wifi-permissions]、[Bluetooth 权限][bluetooth-permissions]。
- [WebSettings][web-settings]、[AppOpsManager][appops]、[共享媒体访问][media-location]。
- [Android VPN][android-vpn]：2026-11-13。
- [Cloudflare Privacy Proxy 连接验证][cloudflare-trace]：2026-04-21。
- [Cloudflare 地理路由说明][cloudflare-routing]：2026-10-14。

[anthropic-policy]: https://www.anthropic.com/legal/privacy
[anthropic-location]: https://privacy.claude.com/en/articles/11186740-does-claude-use-my-location
[claude-android]: https://support.claude.com/en/articles/11869629-use-claude-with-android-apps
[android-location]: https://developer.android.com/develop/sensors-and-location/location/permissions
[location-manager]: https://developer.android.com/reference/android/location/LocationManager
[fused-location]: https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient
[telephony]: https://developer.android.com/reference/android/telephony/TelephonyManager
[wifi-permissions]: https://developer.android.com/develop/connectivity/wifi/wifi-permissions
[bluetooth-permissions]: https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
[web-settings]: https://developer.android.com/reference/android/webkit/WebSettings
[appops]: https://developer.android.com/reference/kotlin/android/app/AppOpsManager
[media-location]: https://developer.android.com/training/data-storage/shared/media
[android-vpn]: https://developer.android.com/develop/connectivity/vpn
[cloudflare-trace]: https://developers.cloudflare.com/privacy-proxy/get-started/
[cloudflare-routing]: https://developers.cloudflare.com/support/troubleshooting/general-troubleshooting/geographic-traffic-routing/
