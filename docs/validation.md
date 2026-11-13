# 验证记录

日期：2026-11-14。目标 Claude 版本：1.261112.18 / 26111218。

## 已完成的本地验证

- `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 成功。
- 15 项单元测试通过，0 失败、0 错误、0 跳过。覆盖随机 UUID 格式、独立读取者的快照一致性、序列化、手动更换、停用保留配置、域名边界、实际 Datadog 域名、字段有效性和容量一致性。
- Android Lint：0 错误、2 警告；分别为 targetSdk／compileSdk 可升级提示。当前编译和目标 API 为 36。
- `python scripts/verify_artifacts.py` 通过：对照原始 APK DEX 验证 8 个方法合同、28 个字段类型；75 个配置项均有英文和简体中文资源；Xposed 入口已打包，框架 API 实现没有误打进模块。
- `apksigner verify` 成功。产物为 Android 调试签名 APK，并非正式发布制品。

测试报告及构建日志位于 `artifacts/validation/` 和 `app/build/reports/`。单元测试中的“不同读取者”是本地配置解码实例，不是 Claude 真实多进程验收。

## 已完成的设备检查

- 调试 APK 已安装到设备，安装后 APK 的 SHA-256 与本机最终构建一致。
- 模块自己的设置 Activity 已启动并实际查看；简体中文、ID 显示、类别分组、状态栏和操作按钮已检查。设置能够读取本模块保存的配置。
- 使用 shell UID 调用配置 Provider，被 `SecurityException: Caller not allowed` 拒绝。
- 已保存的模块配置中存在稳定设备 ID 和 Android ID，且 `block_sift=true`。
- 发现前台切到 LSPosed 作用域设置页后停止了界面操作；未继续执行计划中的手动保存／换 ID 界面验收。不保留该管理页截图和应用列表。

未由代理执行 LSPosed 激活操作；用户是否另外更改作用域未核对。模块界面的“启用环境覆盖”是配置开关，不等同于 LSPosed 已激活。

## 尚未验收

- 没有由代理启动 Claude、登录或发送消息。
- 没有证明 Claude UID 的跨进程配置读取、每个 Hook 的命中率、ART 优化后的覆盖、Claude 多进程一致性、登录兼容性或真实出站请求效果。
- 没有实现 native libc／JNI 和完整 WebView 指纹隔离；原生导入检查确认存在相应能力，详见覆盖清单。
- 没有验证随机 ID 对服务端账号风控的影响。

## 最终调试 APK

路径：`app/build/outputs/apk/debug/app-debug.apk`。

SHA-256：`c1afdbdcf80a8ce7655292e74cc71b49d53f6cac2396d568d66a593b55cfa175`。

设备侧安装文件的哈希已逐字节对应检查；见 `artifacts/validation/module-apk.json`。

## 源码与发布状态

源码与调试产物保存在本机，未发布正式版本。初始目录不是 Git 仓库，收尾时建立单一 main 分支保存源码提交；没有配置远程地址，因此未推送。
