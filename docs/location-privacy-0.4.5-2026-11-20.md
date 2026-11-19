# 0.4.5：Pixel 与虚构美国 SIM 信息、网络边界和缓存清理

日期：2026-11-20。目标应用：Claude Android `com.anthropic.claude` 1.261112.18 / 26111218。

**本轮把严格美国配置扩展为 Pixel 8 基础设备字段、保留用途的虚构美国号码和模拟运营商信息；真实位置与基站信息继续不可用。** 网络准入规则沿用 0.4.4，当前仍没有可信的美国出口或明确 CIDR，因此保持空白名单阻断。手机 user 0 的应用缓存清理已经完成；最终修复版 APK 已安装，构建、安装端与交付副本摘要一致；两次冷启动均 47/47 诊断回读，启动保护和空名单拒绝生效。

本报告描述目标进程能读到的字段与已经执行的验证。基础字段覆盖不会更换实体 SIM、改变基带、建立美国网络路由或产生 Google 设备认证；未核验的固件信息不作为已实现的伪装结果。上轮的完整记录保留在 [0.4.4 位置隐私报告](location-privacy-2026-11-20.md)，本轮结构化数据见 [0.4.5 发布摘要](releases/0.4.5.json)。

## 1. 目标与当前状态

| 需求 | 本轮处理 | 当前证据 |
| --- | --- | --- |
| 手机型号显示为 Google Pixel | 严格配置采用 Google / google / Pixel 8 / shiba 基础 Build 字段 | 配置、本地回归与两次最终设备读回通过 |
| SIM 手机号不暴露真实号码 | 使用 `+12025550142`，属于 555-0100 至 555-0199 保留虚构范围 | 来源、配置回归及最终诊断读取通过 |
| 运营商与国家字段采用美国值 | `310260`、T-Mobile、`us` | 参照 AOSP 模拟器配置；不代表实体美国 SIM |
| 基站与附近位置 | 真实基站、邻区及定位结果继续采用不可用语义 | 保护与最终诊断通过；未扩大为全部回调分支实测 |
| SDK 与网页标识一致 | 保留真实 SDK；UA 按实际 WebView Chrome 版本生成，统一相关接口与 metadata | 本地回归与设备 UA / metadata getter 通过；未捕获远端 HTTP 头 |
| 阻止未经批准的网络出口 | 仅美国、明确 CIDR、地址族与有效期条件；空名单阻断 | C++ 未改；最终两次冷启动明确空名单拒绝 |
| 清理手机缓存 | 系统 cache-only 接口限定 user 0，共 303 包 | 303/303 成功，0 失败、0 超时；历史和快照保持 |

配置回归、设备 getter 读回、审计观察和远端流量证据分别记录；一种验证不替代另一种。

## 2. 虚构身份字段及其边界

### 2.1 Pixel 8 基础字段

严格配置提供以下基础字段：

| 字段 | 配置值 |
| --- | --- |
| Manufacturer | Google |
| Brand | google |
| Model | Pixel 8 |
| Device / Product | shiba / shiba |

Google 的官方驱动页面将 Pixel 8 对应为 `shiba`，为型号与产品代号提供依据。[Google 官方驱动][google-pixel]

本轮保留原有虚拟设备 ID、批准的 CIDR 以及不相关的自定义配置。未核验的 10 个固件 Build 字段明确返回空字符串，另外清空 SOC／SKU 的 4 个公开字段；本轮不宣称指纹、硬件、完整固件或设备证明全部等同于真实 Pixel。系统和硬件层仍可能具有独立的可识别特征。

### 2.2 虚构号码与模拟运营商

新增配置键 `sim_phone_number`，配置总数由 95 增为 **96**。严格模式固定号码 `+12025550142`，运营商代码 `310260`、名称 T-Mobile、SIM 与当前网络国家代码 `us`。

NANPA 明确保留 555-0100 至 555-0199 作为虚构、不可工作的号码范围；并非所有 555 号码都属于这一范围。本轮使用的 555-0142 落在该区间内，不能作为真实可接收电话或验证码的订户号码。[NANPA 555 号码说明][nanpa]

`310260` 与 T-Mobile 的映射可在 AOSP 模拟器的运营商配置中核对。它作为进程内模拟数据来源，不表示更改实体 SIM、运营商订阅、无线注册或账单地区。[AOSP 模拟运营商配置][aosp-operator]

真实基站标识、邻区和坐标继续不可用，不构造一个看似真实的美国基站。虚构号码和国家代码不能改变服务器看到的出口 IP，也不能清除账户历史中的地区资料。

### 2.3 保留真实 SDK

SDK 整数会影响应用选择哪些 Android 方法和兼容分支。严格模式移除旧配置中的 `sdk_level` 与嵌套属性 `ro.build.version.sdk` 覆盖，保留运行时真实 SDK；属性规范化也不能把旧 SDK 覆盖重新引入。普通模式的自定义语义保持。

OS release、安全补丁、屏幕等既有配置并未因此被整体改造成一份“完整 Pixel 固件”。报告将基础身份、运行时能力和固件证明分别描述，避免用一个型号字段代替其余证据。

### 2.4 配置解析与保留规则

严格规则集中在配置层执行。已有虚拟身份和明确出口规则保留，普通预设不能静默关闭已开启的严格保护。嵌套 properties 使用标准转义和不含自动日期注释的稳定编码，阻止转义换行把本应是值的内容变成新的地区配置项。

配置侧独立回归 **37/37** 已通过，其中新增虚构身份测试 9 项。该结果只验证配置及规范化行为；37 项已包含在最终 Java 170 项内，不重复相加。新配置 9 项、运行时 21 项与启动状态 8 项，加上原有 132 项组成本轮集成总数；设备验证单独记录。

源码入口：[UsPrivacyPolicy.java](../app/src/main/java/io/github/lightjunction/fkclaude/UsPrivacyPolicy.java)、[Profile.java](../app/src/main/java/io/github/lightjunction/fkclaude/Profile.java)、[Schema.java](../app/src/main/java/io/github/lightjunction/fkclaude/Schema.java)。

## 3. WebView UA 与 Client Hints

UA 字符串、JavaScript 返回值和 HTTP Client Hints 是不同入口。按实际 WebView 的 Chrome 版本生成 UA，可以避免把固定的旧浏览器版本带到不同 WebView 引擎；同时还需要分别验证 `WebSettings`、`http.agent`、网页脚本与 Client Hints。

Android 官方文档说明，WebView 的 `UserAgentMetadata` 用于生成客户端提示，可包含系统与设备信息。没有显式 metadata、且自定义 UA 不包含默认 UA 时，仅生成低熵提示；该条件不等于“设置 UA 就清空已有 metadata”。`setUserAgentMetadata` 自 AndroidX WebKit 1.9.0 提供，应通过 `WebViewFeature.isFeatureSupported(USER_AGENT_METADATA)` 检测实际 WebView 支持。[WebSettingsCompat][web-settings-compat] [WebViewFeature][webview-feature]

模型字段尤其需要显式处理：`UserAgentMetadata.Builder.setModel(null)` 会使用系统默认模型来生成 `sec-ch-ua-model`。本轮的目标是将模型明确设为 Pixel 8，并保持引擎版本来源一致；对应 getter、JS 与 metadata 的本地回归已通过。最终设备记录了 9 个 metadata 字段的实际 getter 读回；这证明配置接口的返回结果，未扩展为远端 HTTP 服务器所收到的请求头捕获。[UserAgentMetadata.Builder][ua-builder]

最终源码对严格模式下 metadata 能力不足或设置失败直接停止目标进程，避免设置后的异常被宿主捕获后仍继续加载。号码自检采用窄范围静态检查注释和异常处理，没有为自检申请真实电话权限。

本轮不扩展为完整浏览器指纹覆盖，也不使用设备认证或所有高熵提示均不可识别的表述。WebView 能力不足、配置失败或未覆盖接口需要明确记录结果，不能把它们计作伪装成功。

源码入口：[WebEnvironment.java](../app/src/main/java/io/github/lightjunction/fkclaude/WebEnvironment.java)、[EnvironmentHook.java](../app/src/main/java/io/github/lightjunction/fkclaude/EnvironmentHook.java)。

### 启动时序修复

补修前源码 `c8c12cbdac015145d955697a3bc3bd991f4e4ac9` 的 APK 通过 Java 162 项和 Node 23 项本地测试，但第一次手机启动在 `Application.attach.before` 中读取默认 UA 时出现 NPE：系统的初始 Application 尚未就绪，WebView 支持检查无法取得应用上下文，严格模式随即停止目标进程。该构建及失败记录只作为补修前证据，未计为设备通过。

Android 的启动顺序中，`attach` 发生在 Application 创建流程内部，系统的初始 Application 随后才赋值；仅把读取操作改到 `attach.after` 仍不足以保证就绪。源码因此把早期隐私保护与 WebView 能力初始化分为两个阶段。[ActivityThread 启动顺序][activity-thread] [Instrumentation 创建应用][instrumentation] [WebViewFactory 支持检查][webview-factory]

| 阶段 | 控制 |
| --- | --- |
| attach 早期 | 先安装位置、身份与网络保护；网页配置未完成时不发起探测或授予原生联网许可 |
| Application 与主线程就绪 | 内部只读取一次原始 UA；仅允许同线程的单个临时 WebView 校验 metadata，不加载 URL |
| 校验结束 | 在 finally 中销毁临时对象并撤销初始化特例，然后进入 READY |
| 任一步失败 | 失败状态与原生许可通过同一守卫锁协调，保证失败不会被并发许可覆盖；阻断异常也不能跳过 finally 中的进程退出 |

READY 仅表示启动保护准备完成，后续仍必须满足 IP 守卫的全部条件；当前空白名单继续拒绝。该修复增加 8 项启动状态回归，最终源码为 `fea6b9696f547faac55805f46d122b1d0195e6c6`。修复版安装摘要一致，两次手机启动均按“初始化保持 → 9 字段 metadata 校验 → privacy startup ready → 空名单阻断”的顺序完成，且没有初始化失败。该设备结果与 8 项本地启动状态测试分别列入验收。

## 4. 网络审计与真正的阻断

**目前没有已验证的受信美国出口，白名单为空，Claude 进程继续按策略拒绝联网。** 本轮没有更改 0.4.4 的 C++ 网络守卫，也没有为展示新字段临时放行。美国语言、虚构手机号或 Pixel 型号都不会建立 VPN 或改变真实出口。

审计面板中的“非对话网络”仅过滤显示。实际阻断由已适配的 Sift 采集／队列／派发入口、预置及用户合并的遥测域名规则，以及 IP 守卫执行。严格准入仍要求唯一合法 IP 与 `loc=US`、明确非空 CIDR、匹配的地址族和有效许可；Cloudflare `colo` 是机房代码，不作为出口国家证据。[0.4.4 网络实现与验证](ip-guard.md)

元数据审计记录目的地、路径、结果、耗时和可观察字节统计，不读取完整 TLS 正文。本轮不声称已经识别所有敏感负载；没有全设备抓包，也不能以“没有观察到某类事件”证明所有流量都不存在。未覆盖的原生直连、直接系统调用、同域 API 遥测、未知 SDK／版本和其他 UID 代发均有独立边界。

账户历史、正文、附件及服务器的处理规则不会因为进程内字段变化而消失。Anthropic 说明会使用 IP 和其他信号处理平台安全与地区判断，因此没有“零信息处理”或“不会封号”的保证。[Claude 位置说明][anthropic-location]

恢复联网前，需要明确批准的美国出口与 CIDR，并验证必要请求链路、遥测拒绝结果和适用的系统 VPN 路由条件。此前未配置 always-on VPN 与非 VPN 阻断；本轮手机网络检查也未观察到活动 VPN。美国在线正例、出口切换、断 VPN 等场景继续列为未验证。[Android VPN][android-vpn]

## 5. 手机缓存清理结果

### 5.1 范围与方法

仅针对当前 **user 0** 中确认有非空缓存的 303 个已安装包，通过系统接口执行：

```text
pm clear --user 0 --cache-only PACKAGE
```

设备帮助已确认参数语义。AOSP 的 cache-only 分支调用独立缓存接口，针对 CE／DE 的 cache 与 code_cache，以及该包的外部 cache 路径；普通应用数据清除使用不同分支。[PackageManager 命令实现][pm-shell] [Android 16 缓存接口][pm-service] [installd 路径处理][installd]

此次没有把 Downloads、DCIM、聊天、账号数据、安装包或其他用户文件当作垃圾。审计历史的 `no_backup` 区域与模块配置快照保留。包名清单只临时保存在开发机的私有 0600 文件中，清理完成后已删除，不进入报告或云盘。

### 5.2 实际结果

| 指标 | 结果 |
| --- | ---: |
| 处理包数 | 303 |
| 成功 / 失败 / 超时 | **303 / 0 / 0** |
| 耗时 | 53.6 秒 |
| 清理前缓存占用 | 2,621,252,608 字节 |
| 清理后缓存占用 | 5,378,048 字节 |
| 同口径缓存减少 | **2,615,874,560 字节** |
| 清理前可用空间 | 59,206,746,112 字节 |
| 清理后可用空间 | 61,949,087,744 字节 |
| 可用空间增加 | **2,742,341,632 字节** |
| 配置快照 | 内容保持 |
| 审计历史 | 1 个文件，内容保持 |
| 私有包名清单 | 已删除 |

缓存减少量与可用空间变化分别测量。系统后台活动和文件系统分配会影响总可用空间，不能把全部空间增加都归因于本次缓存删除；残余缓存也不等于存在用户垃圾文件。

正式证据位于开发机 `artifacts/iteration-0.4.5-2026-11-20/cache-cleanup.json`。清理前的目录盘点和系统历史估计用于选择安全范围，本表采用最终清理验证的同一口径。

盘点阶段曾调用一次 `dumpsys diskstats`；官方实现包含临时性能自测，已记录该限制，随后改为读取现有统计文件与目录元数据。该自测不作为缓存清理结果。[DiskStatsService][diskstats]

## 6. 0.4.5 构建与设备验收状态

### 6.1 源码与制品绑定

| 项目 | 已完成结果 |
| --- | --- |
| 最终源码 | [`fea6b9696f547faac55805f46d122b1d0195e6c6`](https://github.com/LIghtJUNction/fkclaude/commit/fea6b9696f547faac55805f46d122b1d0195e6c6)，24 个源码文件已核对，开发机内容与推送一致 |
| 版本 | **0.4.5 / versionCode 10** |
| 配置项 | 96 |
| 构建 APK | `app/build/outputs/apk/debug/app-debug.apk` |
| APK 大小 | **4,500,933 字节** |
| APK SHA-256 | `17ed6c85553592918e2a364c4e96beaa6cd4e36baa2c1f90e5a4d26eb5f60a6d` |
| Java 集成 | **170/170 通过**：原有 132 + 新配置 9 + 新运行时 21 + 启动状态 8 |
| 配置独立回归 | 37/37，通过；已包含在 Java 总数中 |
| Node | **23/23 通过**：审计 14 + 环境 9 |
| Lint | **0 errors / 16 warnings**，分类见下表 |
| 制品合同 | 8 项方法、31 项字段、96 项配置、英文与简体中文通过 |
| 对齐与签名 | arm64 ELF／ZIP 16 KiB 对齐、APK v2 签名通过 |
| 安装端摘要 | 修复版 APK 已安装，读回 SHA-256 与本节一致 |
| 开发机交付副本 | `artifacts/fkclaude-0.4.5.apk`，大小与 SHA-256 均一致 |
| 原生宿主回归 | C++ 相比 0.4.4 未改，本轮没有重复执行宿主原生测试 |

Lint 警告按最终 XML 实际记录，不沿用上轮“10 个既有警告”的表述：

| Lint 类别 | 数量 |
| --- | ---: |
| RequiresFeature | 4 |
| GradleDependency | 2 |
| HardwareIds | 2 |
| TypographyDashes | 2 |
| AndroidGradlePluginVersion | 1 |
| ChromeOsAbiSupport | 1 |
| OldTargetApi | 1 |
| RtlHardcoded | 1 |
| SetTextI18n | 1 |
| UseRequiresApi | 1 |

构建摘要位于 `artifacts/iteration-0.4.5-2026-11-20/build-final-summary.json`。构建与单元回归不代替手机运行结果；上轮两次 15/15、7/7 原生观察器及 971 条审计窗口仍属于 0.4.4 制品。

### 6.2 手机联网诊断与恢复记录

用户要求恢复手机网络后，检查确认移动网络具有 INTERNET / VALIDATED 状态，公网目标与 `example.com` 域名的 ping 检查均成功，没有活动 VPN。Wi-Fi 原处于关闭状态，随后使用系统 `svc wifi enable` 开启。

后续稳定读回仍为：**Wi-Fi 已开启但未关联，移动网络 INTERNET / VALIDATED，无活动 VPN。** 手机常规联网可通过移动网络使用；Wi-Fi 开启不等于已经连上某个热点。此处不保存 SSID、实际地址、网关、设备序列号或地区线索。

系统网络可达性与 Claude 的准入分别评价。Claude 没有可信美国出口或非空 CIDR，仍受空名单约束；系统移动网络可用不表示 Claude 已放行，也不代表出口已核验为美国。此前 cache-only 清理与本次 Wi-Fi 开启动作分别记录，没有以缓存清理成功推断网络恢复。

阶段及稳定读回证据位于 `artifacts/iteration-0.4.5-2026-11-20/network-recovery.json`。

### 6.3 最终设备验收

最终 APK 两次冷启动均从 framework 活动配置通道读取，**每次 47/47 诊断回读通过**。日志依次记录初始化保持、Web metadata 9 字段校验、启动就绪与空白名单拒绝，没有初始化失败。

| 验证项 | 最终观察 |
| --- | --- |
| 严格策略 | 严格 US、IP 守卫、空 CIDR、定位与 Sift 阻断均保持 |
| 虚构身份 | Google / Pixel 8 / shiba、虚构美国号码与模拟运营商字段符合配置 |
| SDK / UA 配置 | 无旧 SDK 覆盖，无自定义静态 UA；使用就绪后生成的运行时 UA |
| 整体 hook 安装记录 | 273 项；不作为原生网络入口数 |
| 原生属性／环境支持 | 两次均 framework=1、requested=4、installed=4、failed=0、accepted=1；属于属性／环境支持项 |
| 原生网络观察器 | 两次均 installed=7、requested=7、failed=0 |
| WebView metadata | 9 个字段实际 getter 读回；未捕获远端 HTTP 请求头 |
| 定位系统限制 | 精确／粗略权限均 denied，对应 AppOps 为 ignore |
| 虚拟身份稳定性 | 原有虚拟 ID 保持 |
| 配置可靠性 | 私有快照校验通过；与 framework 按语义及 trim 后文本一致，无自动日期／时区注释 |
| 界面 | 已视觉检查正常 |

47 项诊断覆盖其实际调用的接口，不代表所有定位、订阅、异步回调或原生旁路都已设备验收。JS 行为具有独立 Node 回归；metadata 的 Java getter 不能直接替代网页端所有调用或远端 HTTP 头验证。

最终审计样本如下：

| 审计指标 | 结果 |
| --- | ---: |
| 有效记录 / 无效记录 | **1058 / 0** |
| DNS 调用 / 失败 | 37 / 37 |
| HTTP 尝试 / 失败 | 57 / 57 |
| 原生连接阻断 | 8 |
| 应用正字节观察结果 | 0 |
| 探测事件 | 0 |
| 观察器错误 | 0 |

调用类别不能相加为唯一请求数。“应用正字节观察为 0”只描述已覆盖的元数据观察窗口，本轮没有完整 TLS 正文分析或全设备流量抓包。空名单拒绝与零探测记录一致，不据此宣称未知路径也全部经过观察器。

### 6.4 最终恢复、清理与证据

| 项目 | 结果 |
| --- | --- |
| 原审计历史 | 1 个文件已恢复，内容指纹完全一致 |
| 任务日志与备份 | 手机内本轮生成的测试审计记录及历史备份已删除；安全启动日志与脱敏汇总保留在开发机 |
| 临时测试资源 | 11 个本地临时文件／目录已删除；另已移除任务所属虚拟显示和设备端 JAR，scrcpy 任务已取消 |
| ADB | 服务保留 |
| Claude | 已停止；严格策略、空名单、定位拒绝和原虚拟 ID 保留 |
| 系统 VPN | always-on=false、lockdown=false、active VPN=false |
| 手机网络 | Wi-Fi 开启但未关联；移动网络 INTERNET / VALIDATED，可用于常规联网 |
| 交付 APK | `artifacts/fkclaude-0.4.5.apk`，4,500,933 字节，摘要与构建及安装端一致 |

最终设备证据位于 `artifacts/iteration-0.4.5-2026-11-20/device/`，包括 `device-startup.json`、`device-restart.json`、`device-audit-summary.json`、`installed.json`、`device-cleanup.json` 与 `profile-and-network-final.json`。启动与重启的模块日志留在同一私有证据目录；补修前记录放在上级 `pre-init-fix/` 中，与最终结果分开。

保留的安全截图为 `device/module-saved.jpg` 和 `device/audit-ui-us-pixel.jpg`。前者记录 0.4.5 配置 UI 保存后状态，界面代码与最终版一致；后者来自**最终 APK 第一次冷启动**，已视觉确认不含真实身份值。截图、日志及本机证据目录不作为未经检查的原始数据上传；公开分析只保留脱敏状态、计数和必要的虚构配置值。

## 7. 声明与证据自审

| 检查 | 结论 |
| --- | --- |
| 目标是否清楚 | 本机字段、真实位置、网络出口、服务器历史分别处理；缓存清理与身份覆盖独立验收。 |
| 来源是否匹配 | 虚构号码范围、Pixel 代号、模拟运营商、WebView metadata 与缓存接口分别引用官方来源。 |
| 实验是否支持结论 | 配置回归、两次 47/47、metadata getter 和审计窗口分别列出；未将 getter 当作远端头捕获，缓存减少也不等于总空间归因。 |
| 边界是否完整 | 不宣称实体 SIM、完整固件、硬件认证、全部 TLS 内容识别、全设备流量证明或防封号。 |
| 交付是否可追溯 | 清理、修复源码、构建、安装与交付摘要、两次冷启动、网络状态和原历史恢复均有对应证据。 |

## 参考资料

以下官方资料用于说明字段来源与平台行为；产品实际行为仍以对应源码、版本和设备证据为准。

- [NANPA 555 号码范围][nanpa]。
- [Google Android 驱动与 Pixel 产品代号][google-pixel]。
- [AOSP 模拟器运营商配置][aosp-operator]。
- [WebSettingsCompat][web-settings-compat]、[UserAgentMetadata.Builder][ua-builder]、[WebViewFeature][webview-feature]。
- [Android PackageManager 缓存命令][pm-shell]、[Android 16 服务实现][pm-service]、[installd 缓存路径][installd]、[DiskStatsService][diskstats]。
- [Claude 位置说明][anthropic-location]、[Android VPN][android-vpn]。
- [ActivityThread][activity-thread]、[Instrumentation][instrumentation]、[WebViewFactory][webview-factory]。

[nanpa]: https://www.nanpa.com/numbering/555-line-numbers
[google-pixel]: https://developers.google.com/android/drivers
[aosp-operator]: https://android.googlesource.com/platform/prebuilts/android-emulator-build/system-images/+/refs/heads/main/generic/system-images/android-33/google_apis/x86_64/data/misc/modem_simulator/etc/modem_simulator/files/numeric_operator.xml
[web-settings-compat]: https://developer.android.com/reference/androidx/webkit/WebSettingsCompat
[ua-builder]: https://developer.android.com/reference/androidx/webkit/UserAgentMetadata.Builder
[webview-feature]: https://developer.android.com/reference/androidx/webkit/WebViewFeature
[pm-shell]: https://android.googlesource.com/platform/frameworks/base/+/f76de3d6346de3c4a1a7f982cced357e402300df/services/core/java/com/android/server/pm/PackageManagerShellCommand.java
[pm-service]: https://android.googlesource.com/platform/frameworks/base/+/android16-release/services/core/java/com/android/server/pm/PackageManagerService.java
[installd]: https://android.googlesource.com/platform/frameworks/native/+/android16-qpr2-release/cmds/installd/InstalldNativeService.cpp
[diskstats]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/DiskStatsService.java
[anthropic-location]: https://privacy.claude.com/en/articles/11186740-does-claude-use-my-location
[android-vpn]: https://developer.android.com/develop/connectivity/vpn

[activity-thread]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/java/android/app/ActivityThread.java
[instrumentation]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/java/android/app/Instrumentation.java
[webview-factory]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/java/android/webkit/WebViewFactory.java
