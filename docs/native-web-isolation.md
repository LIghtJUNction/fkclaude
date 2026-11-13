# 0.2.0：原生、子进程与 WebView

目标仍为 Claude `1.261112.18 / 26111218`。只读取安装包做静态分析，不启动 Claude、不登录；本版本没有安装到手机。

## 原生接口

入口为 `assets/native_init`、`cpp/native_api.hpp` 与 `cpp/module.cpp`。采用 [LSPosed Native Hook ABI](https://github.com/LSPosed/LSPosed/wiki/Native-Hook)。`native_init` 只取得框架函数表；Java 核对包名、版本、启用状态并加载配置后，`NativeBridge` 才加载库、通过 JNI 下发快照并安装函数覆盖。库使用静态 C++ 运行库，仅导出框架入口和 JNI 配置入口。

| 配置 | 原生入口 | 行为 |
| --- | --- | --- |
| Build／系统属性 | `__system_property_get`、`__system_property_read`、`__system_property_read_callback` | 仅替换已配置键；旧式缓冲区保留 92 字节约束，按 UTF-8 边界截断；回调保留原序列号 |
| native_env、语言时区 | `getenv` | 已配置变量返回固定值，空值隐藏；未配置项沿用 |
| 五个内核字段 | `uname` | 原调用成功后替换 sysname／nodename／release／version／machine，其他字段保留 |
| CPU／内存 | `sysconf`、`sysinfo` | 核心数、物理／可用内存；按真实页大小和 mem_unit 换算，不假定 4 KiB 页 |
| 存储 | `statvfs`、`fstatvfs`、`statfs`、`fstatfs` | 原调用成功后按真实块大小替换块计数，保留原失败与其他元数据 |

`NativePayload.java` 和 `native_config.cpp` 采用带版本的长度编码 UTF-8，而非 JNI Modified UTF-8。整体／字段长度、重复键、NUL、数值范围和 uname 长度有检查。配置完整解析后才发布；相同快照可重复提交，不同快照拒绝，保证 `getenv` 返回指针在进程内持续有效。实际修改后必须完整重启 Claude。

报告区分 requested／installed／failed，不把“框架可用”当作全部安装成功。框架不可用、符号不存在或原生库加载失败会记录诊断，Java 覆盖继续安装；这是部分降级，不是强制容器。当前制品仅 arm64，ELF 与 APK 中未压缩库均核对 16 KiB 对齐。

不提供 property_find／foreach 的属性区虚拟化；也不强制刷新已有属性／时区缓存，native localtime／strftime 等仍可能使用真实平台或之前缓存的时区。getenv 中的 TZ／LANG 覆盖表示读取结果，不等于替换整个 libc 区域设置。

## 子进程查询

`PropertyView` 统一主字段与高级系统属性；`EnvironmentHook.environmentAndCommands` 覆盖 Java `System.getenv`、`ProcessBuilder.start` 与 `Runtime.exec`。

`VirtualCommand` 只接管无参数／单键 `getprop` 和**无参数** `mount`。返回只读虚拟进程流，不创建实际子进程：完整 getprop 只列配置映射，单个未配置键输出空行；mount 默认空输出，可配置多行文本。带参数的 mount、其他命令、shell 和管道不模拟；不执行写挂载操作，也不改变真实系统。

原生 `exec*`／`popen`、隐藏后端和直接读 `/proc/mounts` 尚未覆盖。虚拟查询不等于对子进程建立独立环境。

## WebView 页面

`WebEnvironment.java` 从模块 APK 读取 `assets/environment.js`，在 WebView 构造后注册文档开始脚本。使用 [WebViewCompat.addDocumentStartJavaScript](https://developer.android.com/reference/androidx/webkit/WebViewCompat)，检查 DOCUMENT_START_SCRIPT；不支持时记录并跳过，不采用可能晚于页面脚本的回退。AndroidX WebKit 依赖为 [1.15.0](https://developer.android.com/jetpack/androidx/releases/webkit)。源站规则默认 `*`，可逗号分隔限制范围；规则错误会导致对应注册失败并记录诊断。

- navigator：语言、CPU、内存、UA、平台／厂商和可配置 UA Client Hints；高熵请求从配置生成，不向原接口请求真实高熵值。
- screen：像素／密度换算 CSS 尺寸，同时覆盖 devicePixelRatio。
- Intl／Date：默认区域时区、常见本地时间 getter、偏移与字符串格式；保留显式传入的时区、UTC 和时间戳，不更改真实时钟。
- 定位：拒绝或返回配置坐标；watch 只回调一次，不生成轨迹。
- 可选阻断：Canvas／OffscreenCanvas 上下文、WebGL、Canvas 导出、WebGPU、AudioContext、插件和设备枚举。可能影响网页绘制、音频或视频设备选择，可单独关闭。

覆盖定位到属性／方法所在的原型，避免直接调用原型 getter 读回原值；日期／格式化器的 constructor 同步到包装后的入口。传入网页的 JSON 使用字段白名单，**不包含 Claude ID、Android ID、账号数据或任意环境变量**；没有向页面开放 JavaScript 原生桥。

这仍不是对抗任意网页代码的安全隔离边界：Worker、字体、WebRTC、权限查询、其他未替换 API，以及已经存在的文档或执行缓存仍有边界。Date 本地分量构造、无时区文本解析和 setter 仍使用原引擎规则；不能据此声称所有时区语义一致。外部浏览器、Custom Tabs 和公网出口不受此脚本控制。硬件证明、可信设备密钥与 ptrace 保持原有行为。

## 验证范围

Java 配置与查询测试、跨语言黄金载荷、C++ ASan／UBSan、11 个原生接口适配器及并发读取、Node 隔离上下文中的 5 组脚本测试均已执行。C++ 使用替身符号与框架函数表，验证适配逻辑，**没有模拟真实 inline patch／跳板**；Node 测试不等于 Android WebView 提供程序验收。真实 Claude 中的命中率和兼容性仍待验证。
