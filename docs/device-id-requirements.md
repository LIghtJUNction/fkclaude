# 设备 ID 随机化需求

状态：已实现设备 ID 配置与 Hook 代码，并加入更广的环境配置。目标进程的实际 Hook 效果尚未验收，见 [验证记录](validation.md)。

## 标识范围

目标是 Claude 自有的 `device_id_prefs/device_id` 及其派生的 `s2h.a`、`Anthropic-Device-ID`、分析事件 `ant_device_id`。不将其等同于 Android ID、IMEI、广告 ID 或可信设备 ID。

已见：`sq1.invoke(Object)` 的 `case 12` 从私有偏好读取 `device_id`，缺失时调用 `isg.a().b()` 并保存，随后执行 `new s2h(strB)`。`isg.a()` 使用 SecureRandom 生成 UUID v4。详细位置见静态分析报告。

## 已实现的默认行为

- 首次启用生成一个随机 UUID v4；持续使用，重启 Claude 后仍保持一致。
- 提供“重新生成设备 ID”；更换后提示完全重启 Claude，使依赖注入单例和分析上下文同时更新。
- 模块独立保存随机值，不覆盖 Claude 原始偏好；关闭模块并重启后恢复原有来源。
- 配置必须能够被 Claude 所在进程可靠读取。当前采用只允许模块与 Claude UID 读取的只读 ContentProvider，不依赖 XSharedPreferences 或可被其他应用读取的偏好文件。
- 多进程读取相同已保存值；禁止在每个进程或每次请求内各自随机生成。
- 界面文本使用 Android 字符串资源，至少提供默认与简体中文资源。

## 候选 Hook 与兼容约束

主要入口：已分析版本的 `defpackage.s2h(String)` 构造器，在构造前替换唯一 String 参数。这样能在读取后的共享值对象处统一替换，避免全局 Hook UUID/SharedPreferences。`s2h.a` 是 final 字段，优先替换构造参数而非事后反射写字段。

APK 中原始 DEX 描述符为 `Ls2h;`，JADX 将默认包展示为 `defpackage.s2h`；运行时应使用原始类名 `s2h`，不能直接照抄 JADX 的 `defpackage` 前缀。构造器 Hook 及两个已定位输出端的兜底覆盖已实现；尚未验证 LSPosed 下优化或内联的实际影响。

仅对 `com.anthropic.claude` 生效；最初适配版本为 `26111218`。未知版本或结构不符时跳过 Hook 并记录不含实际 ID 的诊断，不猜测混淆类名。已确认网络来源 `shx → s2h.a → kxa → pm3.r` 可作为后续一致性检查点；分析上下文 `tdz` 同样需检查。

不改动 `__Host-ant_trusted_device`、`X-Trusted-Device-*`、服务端签发的 `device_id`、密钥或 Play Integrity 证明。它们是独立机制。

## 后续验收

本地验证 UUID 格式、持久化、手动更换、多进程一致性、关闭后恢复及版本不匹配时不注入。构建检查只能证明模块可编译，不能证明 Hook 已在目标进程生效。用户要求本轮不打开应用或登录测试，因此这些实机项仍未验收。

随机化不代表解除封禁或消除关联；服务端如何使用该字段仍未知。
