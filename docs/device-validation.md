# 0.2.1 设备验证

2026-11-14，用户授权启用模块、启动 Claude 并测试，不进行登录。设备 PHK110，Android API 36，Vector 2.2 / 3080 / API 102；目标 Claude 版本码 26111218。

## 已确认

- 模块已启用，应用作用域为 Claude / 用户 0，没有更改其他模块。
- 初次测试发现配置 Provider 在 Claude 内无法解析，初始化退出。shell 可取得同一 Provider 并被 UID 检查拒绝；常规可见性和启动时机调整均未解决。尚未定位系统服务内部具体拒绝原因，不把包可见性猜测当成已确认根因。
- 0.2.1 保留私有 Provider，并增加 LSPosed 官方共享快照通道。目标日志确认使用 `configuration channel=framework preferences`。
- 日志确认 `native framework=1 requested=4 installed=4 failed=0 accepted=1`。当前配置只需要三个系统属性入口和 getenv；未配置的 CPU、容量、uname 不安装，不声称 11 个接口都已在设备测试。
- 日志确认安装 103 个 Java Hook。启动后的受控读回自检为 `passed=5 checked=5`：Android ID、默认 Locale、默认 TimeZone、TZ 和 LANG。日志只记通过数量，不记实际值。
- 原有 Claude 设备 ID 与 Android ID 通过迁移前后摘要比较保持一致。共享快照实际存在，原私有文件保留；没有重置 Claude 数据或账号状态。
- Claude 冷启动成功，启动检查时在前台；用户后续切换到其他应用后未再切回 Claude，进程 PID 仍未变化；目标 PID 日志未发现 Java FATAL EXCEPTION 或 native Fatal signal。模块 APK 在目标进程有映射。
- shell 未授权读取 Provider 仍被 `Caller not allowed` 拒绝。

## 配置与诊断变更

`xposedsharedprefs=true` 启用 [LSPosed New XSharedPreferences](https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences)。`ProfileStore` 在框架偏好目录变更时读取原应用私有 XML、校验并迁移，发布 `environment_bridge` 快照。首次设置／升级后，打开模块设置会发布快照；本次由受控 Provider 启动完成迁移与发布。

共享快照使用框架支持的 MODE_WORLD_READABLE 和随机偏好目录，内容只有虚拟环境配置；它不具有私有 Provider 的逐调用 UID 访问检查，不能用于保存账号令牌或秘密。私有 Provider 的 UID 限制保持不变。无框架支持时保留私有路径，不改 chmod 或公开外部存储文件。

初始化只在取得配置后锁定；attach 阶段读取失败可在 Application.onCreate 之前再试一次，避免瞬时失败永久跳过。失败只记录异常类型和栈帧，不记录异常消息、配置或账号信息；成功后只执行一次读回自检。

## 边界

以上确认模块已加载、安装成功及所列五项读取一致。未验证 Claude 设备 ID 的真实出站请求、每个 Sift 分支、全部原生入口、WebView 文档开始脚本实际执行、登录、账号风控和服务端效果。没有手动点击登录或发送消息。启动应用本身可能执行其正常后台请求，不声称没有任何网络活动。

本地日志与摘要在 `artifacts/validation/runtime-*`；只保存模块诊断和汇总，不保存完整系统日志、账号数据或安装应用清单。源码未配置远程地址，只有本地提交，没有正式发布。

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，版本 0.2.1 / 3，SHA-256 为 `c66a5328ffa5e057a94085662f14accd16ec7af9a68017a5a5c9ed726d36a67f`，已逐字节哈希核对设备安装文件。最终目标 PID 为 13235，读回仍为 5/5，模块有可执行映射。21 项 Java 单元测试、构建、Lint（0 错误、6 警告，新增 HardwareIds 提示来自不输出值的 ID 读回自检）、DEX／资源／原生打包合同与 APK 签名检查通过；详情为 `module-apk-0.2.1.json` 和 `runtime-final-build.log`。

可见性诊断曾使用 `adb install --force-queryable`；最终普通安装后，设备仍显示该模块的安装 override=true，Manifest 的 forceQueryable=false。此标志不是初始化成功的依据，也没有解决 Provider 问题；未用卸载／清数据来强行清除此安装属性。最终源码不设置 forceQueryable。
