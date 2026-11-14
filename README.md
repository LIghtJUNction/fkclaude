# fkclaude

为 Claude Android 提供可配置的进程环境视图的 LSPosed 模块。当前适配 `com.anthropic.claude` **1.261112.18 / 26111218**；其他版本跳过注入。

当前版本 **0.2.1**，90 个配置项。支持设备 ID 持久随机化、Android ID、设备与系统字段、语言时区、运营商、局域网、屏幕电池、内存存储，以及部分隐私 API 的拦截和自定义。重点覆盖已定位的 **Sift 环境采集、事件队列与上传链路**。不修改 Claude 原始安装包或原始设备 ID 偏好。

新增 arm64 原生接口覆盖、虚拟 `getprop`／`mount` 查询和 WebView 页面开始执行前的脚本覆盖。Build、系统属性和查询输出共用配置映射；原生层和网页可读取同一组 CPU／容量／语言时区配置。WebView 可选择阻断 Canvas、WebGL、音频和设备枚举，可能影响图表或语音。

这不是完整 Android 沙箱：直接系统调用、procfs、Worker、公网 IP 和硬件证明仍有边界。默认预设会阻断 Sift 等部分采集，其他字段仍可按需覆盖；详见覆盖清单。

## 使用

1. 构建：`./gradlew :app:assembleDebug`。产物为 `app/build/outputs/apk/debug/app-debug.apk`，为调试签名构建。
2. 安装模块，在 LSPosed 中启用并仅选择 Claude。
3. 打开模块设置，选择各项沿用、拦截或自定义，点击保存。
4. 完全重启 Claude 后生效。更换设备 ID 也需要保存并重启；配置不会每次请求随机变化。

关闭配置开关或停用模块，再完全重启 Claude，恢复原有数据来源。修改 API 级别、屏幕和容量等值可能影响兼容性或 UI。

模块没有联网权限；私有配置 Provider 仅接受模块和 Claude 的 UID。目标进程无法取得 Provider 时，回退读取 LSPosed API 93+ 的共享快照；快照只存虚拟环境配置，不存账号或令牌。已有私有配置会迁移，保留设备 ID。配置不可用或版本不匹配时跳过覆盖，日志不记录实际 ID／令牌。

构建需要 Android SDK 36、NDK 27.1.12297006、CMake 3.22.1 和 JDK 21；当前原生制品只包含 `arm64-v8a`。旧配置升级时保留已有字段，新原生／查询／WebView 总开关缺省启用；新增 Canvas 等阻断开关缺省关闭，应用隐私预设才会开启。

## 本地验证

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
bash scripts/test_native.sh
node --test tests/web_environment.test.mjs
python scripts/verify_artifacts.py
```

`verify_artifacts.py` 需要本地目标 APK，核对实际 DEX Hook 签名、字段类型、语言资源和模块 APK 入口，不依赖反编译器伪代码作为合同。

已按后续授权启用模块并启动 Claude，确认 103 个 Java Hook、当前配置的 4 个原生 Hook 安装及 5 项读取自检；没有登录。结果与边界见[设备验证](docs/device-validation.md)，不代表所有采集点已验收。

## 分析材料

- [环境信息与模块覆盖清单](docs/environment-coverage.md)
- [原生、子进程与 WebView 的实现和边界](docs/native-web-isolation.md)
- [账号风控静态分析](docs/claude-static-analysis.md)
- [设备 ID 的设计与约束](docs/device-id-requirements.md)
- 本地目标安装包、SHA-256、反编译源码：`artifacts/claude-1.261112.18/`，不纳入 Git。

仓库未配置自动 CI；日常验证在本机执行。
