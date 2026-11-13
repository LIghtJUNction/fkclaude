# Claude 环境采集与模块覆盖

目标版本 `1.261112.18 / 26111218`。**本表的“实现”表示已编写相应 Hook，不表示已观察到它在 Claude 进程中执行。** 用户要求不打开 Claude、不登录，本轮保持此约束。

## 首要发现：Sift 专用采集链

新增的定向反编译证据位于 `artifacts/claude-1.261112.18/selected/`；原始 APK 和实际 DEX 方法／字段签名仍为依据，伪代码不是原始源码。

`selected/dt0.java:14–29` 定义 `com.anthropic.claude.sift.AndroidDeviceProperties` 的全部 15 个序列化字段：

| 字段 | 对应来源或含义 | 模块控制 |
| --- | --- | --- |
| app_name / app_version / sdk_version | 应用标签、应用版本、采集 SDK 版本 | 三个 Sift 专用自定义字段 |
| mobile_carrier_name / mobile_iso_country_code | TelephonyManager | 运营商与 SIM 国家，同时覆盖事件字段 |
| device_manufacturer / device_model / device_system_version | Build / Build.VERSION | 设备与系统配置，同时覆盖事件字段 |
| android_id | Settings.Secure | Android ID 配置，同时覆盖事件字段 |
| build_tags | Build.TAGS | Build 标签，同时覆盖事件字段 |
| evidence_files_present | 特定文件存在性 | Sift 文件探测结果，沿用／清空／自定义字符串列表 |
| evidence_packages_present | 特定应用包探测 | Sift 应用探测结果，沿用／清空／自定义字符串列表 |
| evidence_properties | getprop 探测结果 | Sift 属性探测结果，沿用／清空／自定义字符串列表 |
| evidence_directories_writable | mount 输出中的可写目录 | Sift 可写目录结果，沿用／清空／自定义字符串列表 |
| installed_apps | 可选应用列表字段；当前普通构造器默认空列表 | Sift 已安装应用字段，沿用／清空／自定义字符串列表 |

`selected/io0.java:14–21` 定义 `AndroidAppState`：`activity_class_name`、`sdk_version`、`battery_level`、`battery_state`、`battery_health`、`plug_state`、`network_addresses`。模块提供专用界面类名／SDK 版本以及共用电池、局域网配置。

`decompiled/sources/defpackage/v6g.java:245` 起的 Sift 分支收集电池广播、遍历 NetworkInterface、读取 Android ID、运营商、应用标签和版本，并探测以下证据（辅助常量为 `cg40.java:9–12`）：

- 10 个文件路径，包括 `/system/app/Superuser.apk`、`/sbin/su`、`/system/bin/su`、`/system/xbin/su` 等。
- 17 个特定应用包，包括 Root 管理器、`de.robv.android.xposed.installer`、Substrate 及其他修改工具。
- `getprop` 输出中的 `ro.debuggable=1` 和 `ro.secure=0`。
- `mount` 输出中 `/system`、`/system/bin`、`/system/sbin`、`/system/xbin`、`/vendor/bin`、`/sbin`、`/etc` 的 `rw` 标志。

原伪代码存在不完整的循环和异常恢复，不应把所有路径当作观察到的正常执行；序列化字段、常量和命令字串均有实际 APK 证据。是否因某个证据封禁、权重多少，仍未知。

### Sift 发送路径与阻断

```text
v6g（捕获的 r 是 h2a0 时）→ et0 / jo0
  → j810 / f11 → y0u
  → h2a0.b(eaj, y0u, boolean)：入队和触发发送
  → g2a0.invokeSuspend：JSON → gzip
  → v3/accounts/{account}/mobile_events
```

路径构造见 `selected/g2a0.java:139–150`。不记录或展示运行时 Authorization、账号或令牌。

`block_sift=true` 时，模块在 **三个位置**拒绝继续：精确检查接收对象类型后的采集协程、事件队列、上传协程。额外在已分析 OkHttp 执行点匹配 `/v3/accounts/…/mobile_events` 拦截请求。R8 合并类 `v6g` 还承担其他业务，必须检查其 `r` 对象类名等于 `h2a0`，不能无条件截断整个类。

Sift 自定义字段在事件对象构造后覆盖；若选择自定义而没有关闭 Sift，原始探测仍可能在本地发生，覆盖目标是事件字段。要阻止本地这段采集，应开启整条 Sift 阻断。旧队列和其他未识别的缓存路径不能假设已经被逐字段清洗。

## 其他已见信息与实现入口

下表提到的 `EnvironmentHook` 为本仓库源码；所有 Hook 仅在 `com.anthropic.claude` 且版本码匹配时安装。

| 类别 | 目标代码证据 | 已实现的控制入口 | 范围 |
| --- | --- | --- | --- |
| Claude 设备 ID | sq1 → s2h；shx → kxa → pm3.r；wqv → tdz | s2h 构造参数、pm3.r 的头参数、tdz 分析上下文参数 | 随机值保存后固定；不覆盖原始偏好 |
| Android ID | v6g / et0 | Settings.Secure.getString / getStringForUser，Sift 事件字段 | Java 读取与已定位事件 |
| 制造商、品牌、型号、代号、硬件、指纹、构建信息 | wqv、et0、j77、Sentry q0 等 | Build 静态字段及相应 SystemProperties | 不改宿主系统属性 |
| OS 版本、安全补丁、API 级别 | et0、j77、shx | Build.VERSION 和 SystemProperties | API 级别为显式可选，修改可能影响功能判断 |
| 序列号 | 配置可覆盖，当前未证明业务上传该值 | Build.SERIAL / getSerial | 预防性能力，不当作已见上传 |
| 语言、国家、时区与偏移 | wqv、j77、ns0、Sentry x0 | Locale / LocaleList 默认值、Resources 配置、TimeZone 默认值 | 不改变真实时钟 |
| 运营商、SIM 国家、MCC/MNC | v6g、j77、psj | TelephonyManager 的对应 getter | 不伪造基带／SIM 卡 |
| 局域网地址与网卡 MAC | v6g、jo0 | NetworkInterface、LinkProperties、WifiInfo | 不改变真实接口或公网出口 |
| Wi-Fi SSID/BSSID/MAC | 通用预防性能力；未确认全部参与账号采集 | WifiInfo getters；配置相关值后扫描列表为空 | 扫描结果不生成虚假 AP |
| 网络类型和移动子类型 | j77 | NetworkInfo；NetworkCapabilities 传输类型 | 不改变真实路由、DNS 或代理 |
| 屏幕像素、密度、DPI | rii0、Sentry x0:465–489 | Resources、Display metrics / size | 不覆盖全部新 WindowMetrics 路径；可能影响 UI |
| 电量、充电、温度、健康 | v6g、Sentry x0 | 特定电池 Intent 的字段、BatteryManager；Sift jo0 字段 | 未覆盖直接读取 Bundle 或电池 native 服务的全部路径 |
| 总内存、可用内存、CPU 数量 | Sentry x0 | ActivityManager.MemoryInfo、Runtime.availableProcessors | 不钩真实 GC 堆限制或调度器 |
| 总存储、可用存储 | Sentry x0 | StatFs 字节与块数、File.get*Space | 不改变实际磁盘大小或可写权限 |
| 定位 | zdm、up 等 | LocationManager、Location 的坐标 getter | 阻断标准定位请求；GMS/Binder/raw parcel 尚非完整覆盖 |
| 传感器 | SDK 接口预防性能力 | SensorManager 列表、默认传感器、注册 | 阻断模式，不生成虚假传感器流 |
| 已安装应用列表 | Sift 字段、Sentry root 工具探测 | ApplicationPackageManager 列表过滤，Sift 事件字段 | 直接指定包名查询、Intent 查询不在普通列表过滤范围 |
| 剪贴板、联系人、日历 | Manifest 能力与通用预防性能力 | ClipboardManager 读取、相关 ContentResolver.query | 未确认这些数据都用于风控；不包含健康服务／整个用户文件系统 |
| Root／模拟器上下文 | Sentry util.m、q0；另有 Sift 证据链 | Sentry 对应 getter、Sift 拦截或事件自定义 | 不声称所有 Root／注入检测均被改写 |
| 调试器 | 预防性能力 | Debug.isDebuggerConnected / waitingForDebugger | 不覆盖 native ptrace、procfs 或 JDWP 状态 |
| Java／Android 系统属性 | Sift 直接执行 getprop；其他 SDK Java getter | 已定位 Sift 阻断；System.getProperty(s)、SystemProperties getters 自定义 | Java getter 不能改变子进程的真实 getprop 输出 |
| WebView 用户代理 | 通用能力 | 默认 UA、WebView 实际 Settings provider getter/setter | 不伪造 WebGL、Canvas、字体、JS 全部指纹 |
| 遥测域名 | Sentry / CCT / Datadog 库存在 | 可配置域名后缀列表；Java DNS、URLConnection、已定位 OkHttp 执行点 | 不是系统防火墙，native/其他网络栈可能绕过 |

`j77.java:84–139` 是 CCT 传输自动元数据：SDK、model、hardware、device、product、build ID、manufacturer、fingerprint、时区偏移、网络类型、子类型、语言国家、MCC/MNC、应用构建号。它属于 SDK 代码，存在不等于每次已发送。

Sentry `x0` 组装内存／存储／电池／屏幕／地区，`q0` 读取模拟器特征，`internal/util/m` 检查 root 文件、包及 su。不能仅凭这些 SDK 代码推导服务端处罚策略。

## 配置模型与默认值

每个字段独立选择沿用、拦截值或自定义；拦截值的含义由字段决定：字符串通常为空，列表为空列表，布尔诊断为 false，阻断开关为 true，时区为 UTC 等。SDK／屏幕等必须有效的值使用有效下限，界面显示具体值。

隐私预设会随机生成并持久化 Claude ID 和 Android ID，固定语言／时区及局域网信息，清空运营商，阻断 Sift、定位、传感器、联系人日历和普通应用列表，配置部分遥测域名。**预设不是所有字段自动清空**：Build、SDK、屏幕与容量默认沿用，用户可自行覆盖；不因选择预设就承诺物理环境全部不可见。高级系统属性和主字段可分别设置，用户应保持自定义值一致。

模块设置通过只读 ContentProvider 供 Claude 的 UID 与模块自身 UID 读取，其他 UID 被拒绝；修改只在模块界面进行。每个目标进程启动后读取同一份已保存配置，保持不变，配置变更需完整重启 Claude。

## 原生库的补充证据

`native-environment-imports.json` 记录了 arm64 拆分包的实际 ELF 导入符号。`libsentry.so` 导入 `__system_property_get`、`getenv`、`uname`、`ptrace`；`libvoiceclient.so` 导入 `__system_property_get`、`sysconf`；其他图形／语音库也有系统属性接口，SQLite 有 `getenv`、`sysconf`、`readlink`。完整动态符号表保存在本地 `native/` 目录。

这些是能力与潜在读取路径的证据，不是调用已执行、已上传或反调试用途的证明。例如 Sentry 的 ptrace 也可能用于崩溃处理。当前模块尚未实现这些 libc／JNI 路径的 native Hook。

## 仍不能称作完整隔离

- 未实施独立 UID 容器、文件系统命名空间、Binder 虚拟化或 native libc Hook。
- Java Hook 之前的静态初始化、JNI 字段读取、原生系统调用、procfs、子进程命令、WebView 独立进程可能读取真实环境。
- 公网 IP、服务器看到的连接属性、TLS 栈及账号历史不会因改 Java 字段而改变。
- Play Integrity 和可信设备硬件密钥证明仍来自真实平台，模块不伪造证明。
- 源码反编译仍部分不完整；定向恢复扩展了覆盖范围，但不是对全部采集能力的穷尽证明。
- 当前只完成静态合同、构建和本地配置层验证；Claude 中每项 Hook 的实际命中、稳定性及网络效果尚未验收。
