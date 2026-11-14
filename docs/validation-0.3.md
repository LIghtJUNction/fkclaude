# 0.3.0 本地验证

本次只验证本地源码与构建产物。没有安装新版、切换手机前台应用、登录或发送消息。手机此前验证的是 0.2.1；不能用历史环境 Hook 结果证明新 IP 守卫生效。尚未提供实际白名单，也未做手机出口切换测试。

- `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`：成功；41 项 Java 测试通过，0 失败／错误／跳过。Lint 为 0 错误、6 警告。
- `bash scripts/test_ip_guard.sh`：ASan／UBSan 通过。覆盖六个原生入口、部分安装失败、安装回入、IPv4／IPv6／映射地址／Unix IPC、回环 TCP 撤销、许可到期、过期结果拒绝、探测豁免及描述符重用、无法枚举描述符时终止。宿主测试捕获 Hook 包装函数，不模拟 LSPosed 实际内联补丁。
- `bash scripts/test_native.sh`：原有编解码器及 11 个适配器、40,000 次并发读取通过。
- `node --test tests/web_environment.test.mjs`：5 组通过。
- `python scripts/verify_artifacts.py`：8 项 DEX 方法契约、28 项字段契约、94 个配置项、中英资源完整；6 个原生导出、arm64 ELF 与 ZIP 16 KiB 对齐、入口和网页脚本打包检查通过，未打包框架 API。
- `git diff --check`：通过。

Java 测试包含 CIDR／映射地址、严格 URL／响应解析、显式代理拒绝和预设保持设备标识／守卫配置等用例。此前一项原生测试试图在许可恢复后复用已被撤销的 UDP socket；测试已按撤销语义改为新建 socket，生产阻断逻辑没有因此放宽。

调试签名 APK：`app/build/outputs/apk/debug/app-debug.apk`，版本 0.3.0／versionCode 4。

SHA-256：`36c753324aac3d96189e928dc129d43cc29c99c7e79da009d72fe08a291a032c`。

IP 守卫的检测窗口、路由假设和未覆盖入口见 [实现说明](ip-guard.md)。无账户安全或防封保证。
