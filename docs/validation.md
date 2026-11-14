# 0.2.0 历史验证记录

后续 0.2.1 已启用并进行设备测试，最新结果见[设备验证](device-validation.md)。以下保留当时记录。

日期：2026-11-14。目标 Claude：`1.261112.18 / 26111218`。当前本地模块：**0.2.0 / 2**。

## 当前本地验证

- `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 成功。21 项 Java 测试通过，0 失败、0 错误、0 跳过。包括原有 15 项配置测试，以及属性优先级、环境映射、跨语言 UTF-8 黄金载荷、只读查询和新字段校验。
- `bash scripts/test_native.sh` 成功。宿主 C++ 使用 ASan／UBSan 与泄漏检测，验证载荷截断、非法值、11 个原生接口适配器、保留原失败状态、真实 16 KiB 页／块换算、固定指针和 8 线程共 40,000 次读取。
- `node --test tests/web_environment.test.mjs`：5 组全部通过。覆盖原接口沿用、原型 getter、navigator／screen／Client Hints、DST／非整点时区、默认与显式格式选项、Canvas／音频／GPU／设备枚举阻断、定位拒绝与自定义坐标。
- Android Lint：0 错误、5 警告，分别为目标 API、compileSdk、Gradle、WebKit 可升级提示与未提供 ChromeOS x86_64 原生库。当前制品有意只包含 arm64。
- `python scripts/verify_artifacts.py` 成功：原始 APK DEX 的 8 个方法合同、28 个字段类型；90 个配置项的英文／简体中文资源；Xposed 和 native 入口、JS 资产；未误打包框架 API；仅导出两个原生入口；ARM64 ELF 与 APK 内库均满足 16 KiB 对齐。
- `apksigner verify --verbose` 成功，使用调试签名及 APK Signature Scheme v2；`zipalign -c -P 16 -v 4` 成功。
- 最终 APK 的权限清单没有 INTERNET。

本轮日志与摘要位于 `artifacts/validation/`：`gradle-0.2.log`、`native-0.2.log`、`web-0.2.log`、`static-contracts-0.2.0.json`、`module-apk-0.2.json`、签名／对齐／权限检查日志。JUnit 和 Lint 报告位于 `app/build/`。

C++ 测试使用替身符号／框架函数表，验证接口适配器，不代表真实 LSPosed inline patch 或跳板已验收。Node 使用隔离 JS 上下文，不是 Android WebView 提供程序。配置快照测试中的读取者也不是 Claude 真实多进程。

## 设备与界面状态

**0.2.0 没有安装到手机，本轮没有操作手机界面。** 新增两组设置未进行设备渲染验收。以下均为先前 0.1.0 的历史检查，不能用于声称新原生或 WebView 覆盖已生效：

- 0.1.0 调试 APK 已安装，安装文件与当时本地构建 SHA-256 一致：`c1afdbdcf80a8ce7655292e74cc71b49d53f6cac2396d568d66a593b55cfa175`。旧摘要保留在 `artifacts/validation/module-apk.json`。
- 当时仅查看本模块设置 Activity 的中文、ID、分组和按钮。配置存在稳定设备 ID／Android ID 及 `block_sift=true`。
- 当时 shell UID 读取 Provider 被 `SecurityException: Caller not allowed` 拒绝。
- 发现前台切到 LSPosed 作用域设置页后停止界面操作；没有保留管理页截图／应用列表，也没有继续手动保存／换 ID 的界面验收。

未由代理执行 LSPosed 激活；作用域当前状态未核对。模块界面的启用开关不等同于 LSPosed 已激活。

## 尚未验收的边界

- 没有启动 Claude、登录或发送消息，没有证明真实账号风控效果。
- Claude UID 配置读取、真实 inline patch、ART 优化后命中、多进程、WebView 提供程序、网络效果与兼容性均未实机验收。
- 原生系统调用、procfs、文件／链接、Binder、原生子进程、Worker、字体／WebRTC 等仍有未覆盖入口，见[实现边界](native-web-isolation.md)。

## 当前本地调试 APK

路径：`app/build/outputs/apk/debug/app-debug.apk`，4,613,351 字节。

SHA-256：`6cdd9b71e8e44ed87a1150c68bb14cebe202071b32ba24701f39c084f634823c`。

这是本地调试制品，未发布、未安装。源码保存在单一 main 分支；仓库未配置远程地址，无法推送，不把本地提交说成已发布。
