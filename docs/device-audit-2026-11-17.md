# Claude Android 设备审计：启动行为与交互验收

更新日期：2026-11-17。第 1–9 节保留 fkclaude 0.4.0 的第一段设备实测；第 10 节追加最终 0.4.1 的设备交互与导出验收，不覆盖首窗原始证据。

## 1. 这一段实测能够确认什么

在操作员没有发送新聊天消息的 31.818 秒启动观测窗口内，Claude 进程发出了 **9 个可识别的遥测 HTTP 请求组**，并收到了相应 HTTP 响应：Datadog RUM 3 组、Datadog spans 3 组、Claude 产品事件批次 2 组、experiences/track 1 组。另有 13 个启动或业务相关 HTTP 请求组，部分路径已脱敏，不能全部归为遥测。[D01、D03、D09]

同一窗口出现一次 Sift 采集入口调用，随后被既有 `block_sift` 配置拦截；本日志没有 `mobile_events` HTTP 记录。这证明本次采集尝试被模块阻断，不能证明未修改的 Claude 不会采集或上传 Sift 数据。[D02、D04]

这些记录说明：**不发送新聊天消息时，仍可能发生遥测通信。** 本次保留了应用数据，日志只记录元数据，因此无法区分每个批次中的业务事件是本次启动产生，还是先前队列的补发。HTTP 200/202 只证明被观察的请求获得对应状态响应，不能单凭状态码确定服务端最终存储、进一步分析了哪些字段。[D03、D09]

本次也发现审计弹窗初始化失败，错误为 `NoClassDefFoundError`；因此第一段证据只能验证已记录的采集、网络与落盘观察链路，**不能作为弹窗搜索、筛选、开关和导出功能均已通过的证据**。[D08]

## 2. 实验条件与原始证据

| 项目 | 本次条件 |
| --- | --- |
| 目标应用 | Claude Android `com.anthropic.claude`，1.261112.18 / 26111218 |
| 审计插件 | fkclaude 0.4.0；版本与部署步骤来自操作员实验记录 |
| 操作 | 启动目标应用；本段没有发送新聊天消息 |
| 配置 | 日志记录为 `sift_block=true;ip_guard=false`，属于经过模块修改的环境 |
| 数据状态 | 保留既有应用数据；没有以清空账号、清空队列建立全新基线 |
| 观测范围 | 已注入的当前应用进程；本文件不证明其他进程或其他 UID 的行为 |
| 起止时间（UTC） | 2026-11-17 03:27:44.734804 至 03:28:16.553128 |
| 时间差 | 日志单调时钟差 31,818 ms；墙钟差 31.818324 s |
| 原始文件 | `artifacts/privacy-audit-2026-11-17/device/01-startup.jsonl` |
| 大小与记录数 | 339,478 字节；627 条 JSONL 记录 |
| 完整性检查 | 1 个本地会话；序号 1–627 连续；627 行均能解析；无重复序号 |
| 文件 SHA-256 | `d6e21882daf5fdff2cd1b1fe3f2a98dfc4aee595c428affa76316da80d8e5a15` |

时间窗是这份文件第一条与最后一条事件之间的间隔，不代表从操作系统启动或点击图标开始的完整时间。原始日志已确认处于 Git 忽略范围，未纳入提交；本报告不包含会话 ID、账号或组织 ID、目的 IP 原值、请求正文、响应正文及 APK。[D01]

### 2.1 计数方法

一条 JSONL 是一个观察阶段，不等同于一次上传。本文件仅包含一个会话；HTTP 使用 `(session_id, request_id)` 分组：本次 22 组均恰好包含 1 条 `attempted` 和 1 条 `response_received`，因此不会将开始、结束记录重复计为两个请求。分类依据为已记录的目标域名、脱敏路径和方法；`:id`、`:segment` 是记录时生成的占位符，不反推被移除的内容。下文共有 15 类脱敏路径模板；不同原始接口可能归入同一模板，因此这不等于已确认 15 个唯一原始接口。[D03]

原生 socket、Java socket、DNS 与 HTTP 可能观察同一次通信的不同层。下文分别报告，**不将这些层的数量或字节相加作为独立上传总量**。API 调用和字段结构观察也分别统计，不将其直接算作网络发送。[D03、D05、D06]

### 2.2 事件类别

| 类别 | JSONL 事件数 | 解释 |
| --- | ---: | --- |
| packages | 260 | 包信息或 Intent 查询的调用及结果 |
| network | 237 | 一般 HTTP、DNS、Java socket、原生 socket 等观察阶段 |
| device | 84 | 系统属性、内存、电池、运营商相关 API 调用及结果 |
| telemetry | 24 | 遥测 HTTP、Sift 入口、产品上下文或批次入口观察 |
| system | 20 | 观察器安装、范围、配置和自身故障 |
| integrity | 2 | 1 次环境文件存在性探测的调用与返回 |
| **合计** | **627** | 事件数与上传次数不同 |

以上类别由观察器记录，计数可从原文件复算。[D01]

## 3. HTTP：目标、方法、响应和次数

### 3.1 可识别的遥测通信

| 目标域名 | 已记录的路径模板 | 方法 | 请求组数 | 响应状态 | 证据序号（尝试 → 响应） |
| --- | --- | --- | ---: | --- | --- |
| `browser-intake-us5-datadoghq.com` | `/api/v2/rum` | POST | 3 | 202 × 3 | 158→532；546→568；618→622 |
| `browser-intake-us5-datadoghq.com` | `/api/v2/spans` | POST | 3 | 202 × 3 | 573→596；597→603；623→625 |
| `claude.ai` | `/api/event_logging/v2/batch` | POST | 2 | 200 × 2 | 426→605；608→610 |
| `claude.ai` | `/api/organizations/:id/experiences/track` | POST | 1 | 200 × 1 | 594→604 |
| **合计** |  |  | **9** | **200 × 3；202 × 6** |  |

第一条 Datadog RUM 请求尝试发生于日志起点后 301 ms；产品事件批次的第一次请求尝试在 772 ms；experiences/track 在 2,947 ms。它们均处于本次未发送新聊天的窗口，但这些相对时间不能证明批次中的全部事件均在这一时间窗内产生。[D03、D09]

所有这些 HTTP 事件标注 `metadata_only_body_not_read`，由 `ko10#f` 的请求执行观察点产生。日志未读取请求体，因而不能声称已动态证实某个具体设备标识、包列表或聊天内容位于这些实际请求中。静态结构与运行时目标的对应关系可参见[静态证据报告](privacy-analysis-2026-11-17.md)，但二者之间仍需保留这一证据界限。[D03、D05]

### 3.2 其余 HTTP 通信

这些请求在同一窗口出现，均收到 HTTP 200。能够从路径识别出 bootstrap、settings、experiences、notification 等用途；脱敏后的未知段只按原样列出，不能据此恢复具体接口、判断正文内容或判定为遥测。[D03]

| 目标域名 | 已记录的路径模板 | 方法 | 请求组数 | 响应状态 |
| --- | --- | --- | ---: | --- |
| `claude.ai` | `/:segment` | GET | 1 | 200 |
| `claude.ai` | `/:segment/:segment/:segment` | POST | 2 | 200 × 2 |
| `claude.ai` | `/api/bootstrap` | GET | 1 | 200 |
| `claude.ai` | `/api/bootstrap/:segment/:segment` | GET | 1 | 200 |
| `claude.ai` | `/api/organizations/:id/:segment` | GET | 1 | 200 |
| `claude.ai` | `/api/organizations/:id/:segment/:segment` | GET | 1 | 200 |
| `claude.ai` | `/api/organizations/:id/:segment/settings` | GET | 2 | 200 × 2 |
| `claude.ai` | `/api/organizations/:id/:segment/v2/bootstrap` | GET | 1 | 200 |
| `claude.ai` | `/api/organizations/:id/experiences` | GET | 1 | 200 |
| `claude.ai` | `/api/organizations/:id/notification/:segment` | POST | 1 | 200 |
| `claude.ai` | `/v1/:segment/check` | POST | 1 | 200 |
| **合计** |  | **GET 9；POST 4** | **13** | **200 × 13** |

合并两张表，共有 22 个请求组：GET 9、POST 13；HTTP 200 共 16 次、202 共 6 次。每组都在本文件中配到了响应，不存在本段已观察 HTTP 请求的未配对尾项。该结论仅涉及这一观察点记录到的请求，不意味着设备所有网络请求都已覆盖。[D03、D07]

## 4. Sift、设备 API 与字段结构

### 4.1 Sift 采集入口被阻断

序号 44、45、46 依次记录 `v6g#invokeSuspend` 的 `called`、`blocked`、`returned`。阻断原因明确为 `profile.block_sift`。三个阶段对应一次入口尝试；`returned` 不能解释为 Sift 已成功采集或上传，因为中间已执行模块阻断。[D04]

本文件没有 `mobile_events` 的 HTTP 记录。由于实验本来就启用了阻断，此处是对阻断效果的有限验证，不能拿来代表未装模块或关闭阻断时的 Claude 行为，也不能排除已脱离此入口的其他队列、其他进程或未覆盖通道。[D02、D04、D07]

### 4.2 本地信息访问

下表按 `called` 事件计数，避免把 `returned` 再算一次。[D05]

| API 或操作 | 调用数 | 本文件的证明范围 |
| --- | ---: | --- |
| `ApplicationPackageManager#getApplicationInfo` | 35 | 查询入口被调用；未记录所查包名或返回内容 |
| `ApplicationPackageManager#getPackageInfo` | 85 | 查询入口被调用；不能等同于读取完整已安装应用列表 |
| `ApplicationPackageManager#queryIntentActivities` | 10 | 查询可处理 Intent 的活动；本文件没有结果列表 |
| `SystemProperties#get/getInt/getBoolean` | 31 | 读取了系统属性入口，保留属性名而不保留返回值 |
| `ActivityManager#getMemoryInfo` | 6 | 访问内存状态 API，未记录实际内存数值 |
| `BatteryManager#getIntProperty` | 1 | 访问电池属性 API，未记录实际属性值 |
| `TelephonyManager#getSimOperator` | 4 | 访问 SIM 运营商 API，未记录运营商值 |
| `File#exists` 的环境探测 | 1 | 分类为 `system_path_metadata` 的存在性查询，无文件内容 |

130 次包相关调用中，110 次返回，20 次出现 `NameNotFoundException`。这些失败表示相应查询没有正常返回，不能当作 20 次隐私泄漏。系统属性调用的 31 个开始事件均有 31 个返回事件，其他设备 API 的 11 个开始事件均有 11 个返回事件。[D05]

部分系统属性与厂商框架和显示、性能设置有关。日志的 `caller` 在若干观察点落在 Xposed 调用桥接层，因此本段证据不能把每一个包查询或系统属性读取都归因于 Claude 的专用风控逻辑；可能包含应用、SDK 与 Android/厂商框架在当前进程内的调用。将本地读取进一步认定为上传，需要额外的数据流证据。[D05、D07]

### 4.3 产品上下文的结构观察

序号 47 显示 `tdz#<init>` 构造器被执行，日志列出观察器根据静态结构标注的 `ant_device_id,primary_language,locale` 字段名，并标注 `schema_only_values_not_read`。这证实该构造入口在运行时到达；字段值是否非空、具体值是什么、后续进入哪个请求，均不由这条记录证明。[D05]

序号 606、607 记录一次 `raj#a` 产品事件批次工厂的调用与返回；HTTP 层则记录了两组 batch 请求。两者是不同层的观察，协程、重试或其他调用路径可能影响次数，不能用一条工厂调用直接映射或覆盖所有 HTTP 批次。[D03、D05]

## 5. 原生网络：覆盖加深，内容仍不透明

原生观察器安装事件报告 7 个目标 libc 入口全部安装，失败数为 0。随后文件中实际出现 `connect` 和 `sendto` 两类事件；安装成功不等于每个入口均在本次触发过，也不等于已覆盖所有原生网络实现。[D06、D07]

| 操作 | 传输 | 目的端口 | outcome | 事件数 | 记录的发送字节 |
| --- | --- | ---: | --- | ---: | ---: |
| connect | UDP | 123 | returned | 1 | 0 |
| sendto | UDP | 123 | returned | 2 | 96 |
| connect | TCP | 443 | attempted | 15 | 0 |
| sendto | TCP | 443 | returned | 126 | 149,337 |
| connect | UDP | 443 | returned | 3 | 0 |
| sendto | UDP | 443 | returned | 6 | 7,380 |
| **合计** |  |  |  | **153** | **156,813** |

19 次 connect 中，15 次 TCP connect 返回 `EINPROGRESS`（errno 115），因此标为 `attempted`，不能误写为已经连接成功或请求失败。134 次 sendto 均记录了非负返回值；**156,813 字节表示这些被观察的 socket 调用接受的字节数**，不是应用正文净大小，也不是对端已确认收到的字节数。加密帧、握手和协议开销可能包含在内。[D06]

这些记录覆盖 5 个不同的全局 IPv6 目的地址，本报告不公开原值。原生日志的设计允许保留远端目的 IP 供本地追查，不记录本机源 IP；因此原始日志仍属于网络元数据，不应被描述成完全不含可关联信息。[D06]

Java DNS 层记录 15 次调用及 15 次返回，已脱敏主机名分别为 `claude.ai`（12 次）、`browser-intake-us5-datadoghq.com`（1 次）、`time.google.com`（1 次）和 `:id.datadog.pool.ntp.org`（1 次）。还有 13 次 Java socket 连接及对应返回、1 次 Java datagram 发送及对应返回。DNS 解析本身不等于上传正文；仅凭 UDP 443 不能确定具体使用 QUIC，也不能据端口把它断言为某一家 SDK 的通信。[D06]

原生采集未读取数据缓冲区，覆盖标记为 `encrypted_or_opaque`。Java 线程通常每 500 ms 排空原生队列，JSONL 顶层时间是落盘前的记录时间；需要精细排序时应结合事件内的 `native_elapsed_ms`，不将批量排空造成的时间聚集解释成同一时刻发送。本窗口没有 `native_queue_dropped` 事件，但磁盘文件没有导出元信息首行，不能据此推断所有层的 dropped、磁盘错误计数都为零。[D01、D06、D07]

## 6. 观察器缺口与界面故障

| 项目 | 本段结果 | 对结论的影响 |
| --- | --- | --- |
| Java 观察点 | 安装事件报告 153 个 hook，15 个不可用 | 这是安装计数，不是整体网络或 API 覆盖率 |
| 旧 `sun.net.www.protocol.http.HttpURLConnection` | connect、getInputStream、getOutputStream、getResponseCode 共 4 个不可用 | 不能依赖这组旧类观察点；本段 HTTP 证据来自实际触发的 `ko10#f` |
| `vdz#<init>` | 1 个构造观察点不可用 | 产品事件对象构造覆盖不完整 |
| `io.sentry.Sentry` / `io.sentry.Hub` | captureEvent、captureException、captureMessage、captureEnvelope、captureTransaction 共 10 个不可用 | 无相关记录不能证明 Sentry 未运行或未发送 |
| 原生 socket | 安装 7/7；实际记录 connect、sendto | 直接系统调用、sendfile/splice、未注入进程或其他 UID 仍可能绕过这些入口 |
| 弹窗 | `activity_panel` 报 `NoClassDefFoundError` | 本段不具备弹窗交互验证结论 |

这些不可用和失败事件由审计器明确写出，便于后续修复与复核。全文件共 21 个 `failed` outcome，其中 20 个是上述包查询异常，1 个是弹窗初始化故障；它们不能统一解释为网络失败。[D05、D07、D08]

此外，本文件并非带完整会话状态的手动导出文件：序号连续只说明这份文件中已编号事件的连续性，无法排除编号之前的过滤、观察器启动之前的行为、尚在内存但未落盘的尾项，或其他进程中的事件。当前证据不支持“已追踪全部非对话上传”的无条件结论。[D01、D07]

## 7. 对隐私问题的当前判断

| 问题 | 本段证据支持的回答 |
| --- | --- |
| 没有发送新聊天时，是否仍有非对话通信？ | 是。至少有 9 个可识别遥测 HTTP 请求组并获得 200/202 响应。 |
| 是否调用设备、系统或应用查询 API？ | 是。实际观察到相应入口调用；未保存返回值，不能直接等同于上传。 |
| 是否确认 Sift 采集逻辑会尝试执行？ | 是。本次入口到达后被配置阻断。 |
| 是否已确认 Sift 本次将这些设备信息发至服务端？ | 否。本配置阻断入口，且本文件未观察到 mobile_events HTTP。 |
| 是否已证明 Datadog/产品事件实际包含特定设备标识或私人内容？ | 否。观察器只读取网络元数据和结构提示，没有读取实际请求体。 |
| 是否已证明用户完全不知情，或可定性为“窃取”？ | 否。需要结合实际告知、权限、组织策略、同意流程、具体字段及用途评价；本段不提供法律结论。 |
| 是否证明所有上传都被覆盖？ | 否。缺失观察点、原生通道、进程范围、短时间窗和已修改环境均限制结论。 |

官方披露与静态字段证据的对应关系见[静态证据报告](privacy-analysis-2026-11-17.md)。这里增加的是实际运行的请求和 API 观察证据，不以日志缺失或 SDK 代码存在替代事实判断。[D01–D09]

## 8. 首窗形成时的后续状态（历史记录）

此表保留 0.4.0 首窗完成时的验收状态，不代表当前 0.4.1 结果；最终版本的后续证据见第 10 节。

| 阶段 | 状态 | 本文目前是否提供验收结论 |
| --- | --- | --- |
| 0.4.0 启动观测 | 已取得原始日志并完成离线复核 | 是，仅限上述观察范围 |
| 弹窗修复后的版本启动 | 等待新版本部署及新证据 | 否 |
| 搜索、分类、展开、关闭与重新开启 | 等待交互证据 | 否 |
| 暂停记录、恢复记录 | 等待成对时间窗和状态证据 | 否 |
| JSONL/Markdown 导出及导出脱敏 | 等待实际导出文件 | 否 |
| 长时后台、联网切换与功能触发 | 尚未形成本文可用证据 | 否 |

修复版证据应保留为独立阶段，分别标明插件版本、操作、配置、时间窗和文件摘要。不能将不同版本的计数混在同一个实验样本中，也不能用修复后的截图回填成 0.4.0 弹窗已可用的证明。

## 9. 证据索引与复算

| ID | 来源或筛选条件 | 支撑内容 |
| --- | --- | --- |
| D01 | 第一段原始文件、SHA-256、JSON 解析、时间与序号检查 | 样本边界、文件大小、记录数、类别和连续性 |
| D02 | seq 19：`environment_policy` | 修改环境、Sift 阻断开启、IP guard 关闭 |
| D03 | `action=http_request`，按 `(session_id, request_id)` 分组 | 22 组 HTTP 目的、方法、阶段和响应；9 组可识别遥测 |
| D04 | seq 44–46：`sift_collect` | 一次 Sift 入口调用、阻断及返回 |
| D05 | device/packages/integrity 事件；seq 47、606–607 | 本地 API、异常、产品结构入口与批次工厂 |
| D06 | `details.layer=native`；DNS、socket、datagram 事件 | 原生发送元数据、分层计数、DNS 与 Java 网络观察 |
| D07 | seq 2–18 的 hook_coverage/coverage/native_observer_installed；观察器实现 | 已安装、不可用入口、原生队列与时间语义 |
| D08 | seq 61：`observer_error` / `activity_panel` | 弹窗初始化 NoClassDefFoundError |
| D09 | 操作员实验记录 | 部署 0.4.0、保留配置和数据、只启动而未发送新聊天；不是单凭 JSONL 推断 |

在开发机项目根目录可离线复算，不需要重新连接设备：

```bash
sha256sum artifacts/privacy-audit-2026-11-17/device/01-startup.jsonl
python scripts/audit_logs.py --json artifacts/privacy-audit-2026-11-17/device/01-startup.jsonl
```

本报告同时采用独立 JSON 解析复核，检查每组请求的两个阶段，而不是把 CLI 展示当作未经验证的事实。复核重点包括：计数口径一致；主张有明确证据层级；未配对和不可用状态显式保留；公开内容没有账号标识或 IP 原值；阶段限制与已验证结论分开。

## 10. 0.4.1 最终设备验收

本节是后续独立阶段。第 1–9 节的 0.4.0 首窗计数、配置和弹窗故障保持不变；以下结果不用于改写那一段历史样本。

### 10.1 版本、环境与证据分组

最终安装的模块为 **0.4.1 / 6**。手机上实际安装 APK 的 SHA-256 已与开发机制品核对一致：

`ed14fe65c6aa8772386245f5c4b7dec5f03294ff8b8a72afae6276785a42b085`

该 APK 为 4,616,756 字节，对应源码提交 `444bb9f9a5adc71d81b41472b16a1af64dd0ae10`。后续 CLI 的 NDJSON 入口修复为 `4273ffc4ba3a787aa9450d90434a8b6a189af83c`，不改变 APK 文件。[D10、D16]

交互测试通过 scrcpy 在**同一手机上的临时独立显示 3**进行，显示参数为 1240 × 2772、560 dpi，运行的是已安装的 Claude 应用。此条件记录在 `virtual-audit.log`；本次结论适用于该显示环境和已执行步骤，未穷尽其他屏幕、旋转或分屏组合。[D11、D16]

环境配置仍为 `sift_block=true;ip_guard=false`。测试前后模块配置校验文件逐字相同；测试期间改变的是审计记录开关、筛选和列表状态。截图、XML、原始日志和 Claude 原始安装包留在本地忽略目录，不纳入 Git 或云端附件。临时录屏在提取验收画面后已删除。可随报告共享的结构化结果为[设备与导出验证聚合 JSON](device-audit-2026-11-17-export-validation.json)。[D10、D14、D16]

文件 `05-final-cold.jsonl` 是包含历史数据的累计快照，共 2,756 条、4 个会话。冷启动分析只取其中最新会话的 **655 条、seq 1–655**，其记录范围为 04:06:58.309135–04:07:14.753213 UTC。不同阶段快照存在重叠，不能将文件行数直接相加为事件总量。[D10]

### 10.2 冷启动与可读界面

| 验收项 | 观察结果 | 证据 |
| --- | --- | --- |
| 冷启动打开请求保留 | 最新会话 seq 44 为 `before_create`，seq 163 为 `preserved_intent`，seq 190 为 `audit_panel_open / visible` | `05-final-cold.jsonl` |
| 冷启动观察器状态 | Java 安装计数 155、不可用 15；原生安装 7/7；该 655 条会话中没有 `observer_error` | 同上 |
| 面板显示与中文事件说明 | 操作员检查截图，完整可见控件和卡片没有相互遮挡；卡片显示中文状态及方法、路径、主机、HTTP 状态、耗时 | `09-cold-panel.jpg`、`15-filtered-final.jpg` |
| 搜索 | 输入 `datadog` 后，XML 中完整可见的 4 个事件卡片均为 Datadog RUM 或 spans 记录 | `10-search-result.xml` |
| JSON 详情 | 点击事件后显示可解析 JSON；所选记录为 telemetry / http_request / response_received，主机为 Datadog，路径 `/api/v2/spans`，状态 202 | `11-event-detail.xml` |
| 类别与非对话筛选 | 菜单可展开；随后类别为“遥测”，“仅非对话网络”开关为 true | `12-category-menu.xml`、`13-paused-list.xml` |
| 新 Intent 路径 | 已显示面板接收新的打开请求，记录 `before_new_intent` 与 `preserved_intent`，并保留现有面板状态 | `07-after-refresh-trigger.jsonl` 及暂停前后 XML |
| 收起后重新打开 | 点击“收起”后从“审计”入口重新打开，产生 `audit_panel_open / visible` | `24-reopened-final.xml`、`16-resumed-final.jsonl` |

搜索 XML 的底部另有一个仅露出标题的滚动条目，不将它作为主机匹配断言的样本。完整事件详情另由 JSON 对照验证；没有将屏幕上只显示一部分的内容解释为完整记录丢失。[D11]

冷启动中的 15 个不可用观察点仍是已知覆盖缺口；“没有 observer_error”仅针对这个 655 条会话，不表示 Sentry、所有网络栈或所有生命周期场景均已覆盖。0.4.0 的弹窗失败仍保留在前文。[D07–D08、D10]

### 10.3 暂停列表与停止记录分别验证

**暂停刷新：列表内容保持，记录继续。** `13-paused-list.xml` 与 `14-paused-after-trigger.xml` 都显示“列表已暂停”、记录开关 true，列表条目内容相同；状态区计数仍更新，因此两个 XML 文件的整体摘要并不相同。对应的 `06-before-refresh-trigger.jsonl` 与 `07-after-refresh-trigger.jsonl` 按会话和序号去重比较，新增 **22 条事件，seq 758–779**，包括 3 个 HTTP 请求尝试、3 个 HTTP 响应和 7 次原生发送返回。随后 `15-filtered-final.xml` 显示恢复“实时刷新”。这些事实共同证明本次“暂停刷新”没有关闭采集。[D12]

**停止记录：关闭状态跨进程重启保留。** 有效对照为：

| 文件 | 字节 | 行数 | SHA-256 |
| --- | ---: | ---: | --- |
| `11-fresh-disabled.jsonl` | 1,914,642 | 3,540 | `0656d59383ccbcae1e2c3fab8ae32667b822958ff288b7926c422a9f6fa69a7d` |
| `12-off-confirmed.jsonl` | 1,914,642 | 3,540 | `0656d59383ccbcae1e2c3fab8ae32667b822958ff288b7926c422a9f6fa69a7d` |

操作员确认旧进程 10143 已停止并消失，再启动新进程 10951。新鲜的 `18-off-confirmed.xml` 显示进程 10951、“已停止记录”和记录开关 false；与上述文件逐字相同结合，说明这次关闭与重启流程中没有向被比较的日志追加事件。[D13]

早期 `16-off-after-restart.xml` 的画面新鲜度存在疑问，**排除于重启持久化验收**；相关旧快照不用于替代上述重新执行的有效对照。文件不增长只能证明记录器在这一观察范围的行为，不能解释为应用停止联网或停止其他活动。[D13]

### 10.4 真实 SAF 导出与 CLI 兼容

通过 Android 系统文件选择器保存到手机 Download 后，实际文件扩展名为 `.ndjson`。本地复核文件为 `13-saf-export.ndjson`：[D14]

| 检查项 | 结果 |
| --- | --- |
| 文件大小 | **553,903 字节** |
| SHA-256 | `dc3043dd5166b113a97149f44b1494be1fef419dca41082eea51d8bd13bea8d0` |
| 行数 | **1,001 行**：1 行导出元信息 + 1,000 条事件 |
| 首行 schema | `fkclaude.audit.export.v1` |
| 后续事件 schema | 1,000 条均为 `fkclaude.audit.v1` |
| 范围 | `current_process_retained_window`；`retained=1000`、`max_events=1000` |
| 与磁盘快照关系 | 事件内容恰好等于 `12-off-confirmed.jsonl` 的最后 1,000 条 |
| 导出时记录状态 | `enabled=false` |
| CLI 直接读取 | 修复后直接读取原始 `.ndjson` 成功；没有通过改名绕过入口问题 |
| 格式与关联 | 事件可解析，会话/序号键唯一；元信息数量、类型和事件数校验通过 |

这 1,000 条事件来自进程恢复的历史保留窗口，包含 **3 个旧会话**：一个会话 366 条（seq 440–805）、一个 17 条（seq 1–17）、一个 617 条（seq 1–617）。它们不是导出进程在关闭记录期间新产生的 1,000 条事件。[D14]

导出元信息中的 `queue_dropped`、`disk_errors`、`memory_evicted`、`invalid_history` 均为 0；这些是**导出记录器进程本身的计数**。它们不追溯代表所有历史会话，也不表示 1,000 条窗口之外没有更早事件。导出明确是有界窗口，不能当作整份磁盘历史或完整设备流量。[D14]

对导出文件的 1,000 条原始事件、8,007 个详情字符串执行预设隐私规则扫描，未匹配到所检查的敏感键、令牌/邮箱/UUID/MAC 等模式，以及 URL 认证信息、查询值或片段。允许的本地关联标识和远端目的 IP 单独处理；原始日志仍保留目的地址，因此“规则未命中”不等于不含任何可关联元数据，也不构成全面隐私保证。[D14]

真实导出揭示了 CLI 原先不接受 `.ndjson` 扩展名的问题。修复后同时支持 JSONL 和 NDJSON 入口，新增实际 `.ndjson` 命令入口与轮转文件发现回归用例；最终 **14/14 Python 测试通过**。原始文件内容没有修改。[D14、D16]

### 10.5 清空、恢复与结束状态

清空操作经过确认。随后 `23-cleared-confirmed.xml` 显示保留 **1/1000**，`15-cleared-confirmed.jsonl` 仅 **326 字节、1 条 `audit_cleared`**。这一条是清空动作本身的审计记录，不是遗留业务事件。[D15]

恢复记录后，`16-resumed-final.jsonl` 为 **10,543 字节、20 条、seq 1–20**：seq 2 为 `recording_changed / enabled`，并出现新的 HTTP、原生发送和设备 API 事件；seq 10 为弹窗重新显示。该样本没有清空前的旧会话键。最后 `24-reopened-final.xml` 显示记录开关 true。[D15]

测试结束前，记录开启状态已经落盘。之后 Claude 被停止，临时显示、测试服务器与本轮临时连接资源已移除，模块环境配置保持不变；最终状态由 `device-final-state.json` 和配置校验文件保留。报告不公开其他应用的前台状态或内部连接标识。[D16]

### 10.6 后续阶段证据索引

以下路径均相对于 `artifacts/privacy-audit-2026-11-17/device/`，除共享聚合 JSON 外均为本地材料。

| ID | 证据 | 对应结论 |
| --- | --- | --- |
| D10 | `05-final-cold.jsonl`；最终 APK/安装摘要 | 最终版本绑定、冷启动最新会话、请求保留与显示、观察器状态 |
| D11 | `09-cold-panel.jpg`、`10-search-result.xml`、`11-event-detail.xml`、`12-category-menu.xml`、`15-filtered-final.jpg` | 可见界面、搜索、详情、类别 |
| D12 | `06-before-refresh-trigger.jsonl`、`07-after-refresh-trigger.jsonl`、`13-paused-list.xml`、`14-paused-after-trigger.xml`、`15-filtered-final.xml` | 暂停刷新、后台继续记录、新 Intent 路径与恢复 |
| D13 | `11-fresh-disabled.jsonl`、`12-off-confirmed.jsonl`、`18-off-confirmed.xml`，操作员进程重启记录 | 关闭记录跨真实进程重启保留；排除陈旧 XML |
| D14 | `19-export-picker.xml`、`20-picker-folder.xml`、`21-after-export.xml`、`13-saf-export.ndjson`；[聚合 JSON](device-audit-2026-11-17-export-validation.json) | SAF 保存、导出元信息、历史窗口、格式和隐私规则复核 |
| D15 | `22-clear-result.xml`、`23-cleared-confirmed.xml`、`15-cleared-confirmed.jsonl`、`24-reopened-final.xml`、`16-resumed-final.jsonl` | 清空确认、重新记录与弹窗重开 |
| D16 | `validation-actions.jsonl`、`virtual-audit.log`、`device-final-state.json`、配置校验文件、`ndjson-python-tests.log` | 操作顺序、测试显示、最终状态、14 项测试与 CLI 修复 |

关键新增文件的 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| `05-final-cold.jsonl` | `79a9db4019d487e057c7a2d056d6d74c45326e5ec324934e370b1cb76e313e4d` |
| `06-before-refresh-trigger.jsonl` | `359f281887783e8e19d02e9bf4c3a17d7aada67cb58ef015f87f68ccb9542e52` |
| `07-after-refresh-trigger.jsonl` | `379ee4a2be95ddbbb27079b440e0abbbf9bfa66f04ee3d4f23ee82d47b8f728a` |
| `15-cleared-confirmed.jsonl` | `df771050acd80041e22999f0ac87ca64d4cbf6a9569c6f155e5bdc25933f0103` |
| `16-resumed-final.jsonl` | `3fcb7a2ec954573fdbbbf85a9dd6e9f1ef3ed3808997db91dd4327ef8dfd3964` |
| 共享聚合 JSON | `85e032e30d880025f3b8d87df2cf36f28bf5766333f0bac7ca46ef78653f0307` |

主要审计交互已取得可复核设备证据。长时间后台稳定性、其他设备/显示组合、网络切换、IP 守卫出口变化和未覆盖通道仍需独立实验；本次没有扩大到所有应用功能，也没有将不同观察层的记录数合并为“全部上传次数”。
