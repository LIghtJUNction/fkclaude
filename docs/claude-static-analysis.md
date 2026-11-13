# Claude Android 账号风控静态分析

分析日期：2026-11-14。目标：`com.anthropic.claude`，版本 `1.261112.18`，versionCode `26111218`。仅静态读取安装包，没有启动应用、登录测试、读取账号私有数据或向 Claude 服务发请求。

## 后续补充：发现 Sift 专用环境采集

本轮补回了原先缺失的序列化器，已确认 `com.anthropic.claude.sift.AndroidDeviceProperties` 与 `AndroidAppState`。它们采集文件／应用／系统属性／可写目录证据、已安装应用、Android ID、运营商、电池和网络地址。上传代码使用 `v3/accounts/{account}/mobile_events`。这扩充了原先仅确认 Sentry Root 检查的结论；具体字段、入口和模块覆盖见 [环境信息与覆盖清单](environment-coverage.md)。

## 结论

**已见**：客户端具有应用自有随机设备 ID、登录 Play Integrity 证明、可信设备密钥绑定和生物识别重新认证、Cloudflare 挑战识别、账号禁用／验证／地区限制错误处理。普通设备 ID 与可信设备 ID 是两套不同身份。

**推断**：随机化自有设备 ID 可以改变部分请求和分析事件携带的标识，但不能由此推断服务端会解除关联或封禁。

**未知**：服务端风控模型、封禁阈值、各字段权重、该设备是否能获得 Integrity 证明、当前账号状态和随机化后的实际效果。APK 无法证明这些信息。

## 样本与复查位置

四个已安装 APK 已通过 `adb pm path` 获取并用 `adb pull` 拉取。总计 67,287,022 字节，约 64.2 MiB。逐包计算手机端和本机 SHA-256，全部一致；本机 ZIP CRC 检查全部通过。

- [主包](../artifacts/claude-1.261112.18/base.apk)
- [样本清单](../artifacts/claude-1.261112.18/inventory.json)
- [SHA256SUMS](../artifacts/claude-1.261112.18/SHA256SUMS)
- [反编译源码目录](../artifacts/claude-1.261112.18/decompiled/sources/)

主包 SHA-256：`73b5024d5bb6d1910391ccb3ebefb8a2692ee33d83c478e59a5407b72d6e91c4`。

工具为 JADX 1.5.6。全量 `--show-bad-code` 反编译在 32523/37884（85%）持续停滞并出现交换空间压力，已停止；缺失的关键类使用 `--decompilation-mode simple --single-class` 定向输出。**不是完整、可重新编译的源码**；复杂协程、枚举和被内联的方法存在反编译警告。报告只将能定位的代码／字符串称作“已见”，不以缺少搜索命中证明功能不存在。相关日志随样本保存。

下文 `D/` 表示 `../artifacts/claude-1.261112.18/decompiled/sources/`。JADX 展示的 `defpackage` 是默认包显示前缀，原始 DEX 中是 `Ls2h;`、`Lsq1;`、`Lkxa;` 等，运行时类名没有 `defpackage.`。

## 1. 应用自己的设备 ID

**已见**调用链：

```text
lfg.invoke case 8
  Context.getSharedPreferences("device_id_prefs", 0)
  → t2h(SharedPreferences)
sq1.invoke case 12
  getString("device_id", null)
  缺失 → isg.a().b() → putString("device_id", value).apply()
  → s2h(value)
```

证据：`D/defpackage/lfg.java:66–68`、`sq1.java:83–97`、`t2h.java:6–11`、`s2h.java:4–8`。

`isg.java:10–16` 用 SecureRandom 生成 16 字节，设置版本位为 4、变体位为 RFC UUID 变体，再构造 `klg0`。**已见／推断结合**：这是随机 UUID v4 的生成逻辑，不是从 Android ID、IMEI 或序列号派生。仅针对这一生成路径，不代表整包完全不读取其他设备信息。

```java
String value = prefs.getString("device_id", null);
if (value == null) {
    value = generator.a().b();
    prefs.edit().putString("device_id", value).apply();
}
return new s2h(value);
```

以上为便于阅读的等义摘录，原始混淆标识与反编译行号以本地文件为准。

### 网络与分析事件

**已见**：`D/defpackage/shx.java:98–102` 从 DI 取得 `s2h.a` 并将其作为第三个参数传给 `kxa`；`kxa.java:12–16` 再将该参数交给 `pm3.r(...)`。完整链为 `device_id_prefs → sq1 → s2h.a → shx → kxa → pm3.r → Anthropic-Device-ID`。`pm3.java:2332–2333` 返回以下请求头：

| 字段 | 值来源 |
| --- | --- |
| `Anthropic-Client-Platform` | `android` |
| `Anthropic-Client-App` | kxa 构造参数 |
| `Anthropic-Client-Version` | `1.261112.18` |
| `Anthropic-Client-Build` | `26111218` |
| `Anthropic-Client-OS-Version` | `String.valueOf(Build.VERSION.SDK_INT)` |
| `Anthropic-Device-ID` | kxa 第三个构造参数 |

**已见**：`wqv.java:159–173` 从 DI 获取 `s2h.a` 并传给 `tdz`；`tdz.java:13–15` 将其放入 `ant_device_id`，同时放入语言和 locale。`f0m.java:106,118` 还把 `s2h.a` 传给实验事件组件 `kxj`；`m4m.java:20` 的序列化描述中存在实验事件 `device_id` 字段。

**限制**：不能因为 `wqv` 读取了 Build.MANUFACTURER/MODEL，就声称这些参数一定在此事件上传：当前 `tdz` 构造伪代码中 app/os/device/library 对象为空，部分构造参数没有可见使用。窗口布局事件序列化器 `rii0.java:11–27` 单独定义了 `device_id` 和可选 `device_model`，但描述符存在不等于已经观察到发送。

## 2. 登录 Play Integrity

**已见**：`llo.java:35–92` 获取登录证明；不支持的构建、无 token、超时和异常会转成 `swa` 中的错误原因。异常文案明确写出“请求会不带 token 发出”。这是客户端降级行为，不等于服务端一定接受。

`rwa.java:11–13` 的 `ClientAttestation` 序列化字段：

```text
play_integrity_token
play_integrity_mint_error
```

`llo.java:744–787` 在请求前检查构建开关 `do10.b`，并启动异步 token 获取。`is5.java:124–198` 根据远程配置、已有请求状态和请求预算决定是否获取证明；未知远程配置值会报告一次并继续不附带 token。

**已见远程开关**：

- `dog0.java:9`：`claudeai_email_sign_in_credential_attestation`，识别 `HOLDING_CONTROL`、`HOLDING_CONTROL_UNDER_MEASUREMENT`。
- `e0m.java:6`：`claudeai_google_sign_in_credential_attestation`，另外识别 `SERVER_MAY_REFUSE`。
- `llo.java:704,727` 等：登录链接重试、重新提交和 Play 重试相关开关。
- `ozl.java:382`：`claudeai_play_integrity_purchase_attestation`。
- `apy.java:120`：`claudeai_play_integrity_credit_purchase_attestation`；`mqn.java:13` 读取额度购买证明最大生成次数。

**未知**：本机实时下发的开关值，以及服务端对各类完整性 verdict 的接受规则；没有运行验证。

## 3. 可信设备、密钥绑定与重新认证

**已见**：`mef0.java:15` 使用 `trusted_device_` 加作用域字符串建立独立存储；`f():109–113` 读取其中的 `device_id`，`h():121–125` 读取 `reattest_kid`，还有 `token` 与 `device_binding_kid`。

`kef0.java:2014–2069` 从服务端响应体或 `__Host-ant_trusted_device` Cookie 取得可信设备令牌，并保存服务端返回的设备 ID 与密钥标识。因此这里的 `device_id` **不是** 第一节的 `device_id_prefs/device_id`，不能通过全局同名键替换一并改掉。

`yr10.java:145–186`：

- AndroidKeyStore EC 密钥，曲线 `secp256r1`、摘要 `SHA-256`。
- 有 StrongBox 时优先生成，失败回退普通 AndroidKeyStore 路径，文案称回退 TEE；不能仅凭回退代码证明当前设备实际密钥安全级别。
- 可附带 attestation challenge；重新认证密钥要求用户认证，生物识别录入变化可使密钥失效。

`gs10.java:31–48` 和 `kef0.java:1878–1881` 明确展示可信设备请求签名：签名输入包含 HTTP 方法、请求路径与秒级时间戳，生成以下请求头：

```text
X-Trusted-Device-Id
X-Trusted-Device-Timestamp
X-Trusted-Device-Signature
```

`kef0.java:1375–1394` 将重新认证提交到 `auth/session_reattest/device_key`，签名路径包含 `api/auth/session_reattest/device_key`。`kef0.java:2108–2118` 处理服务端拒绝原因：

```text
session_stepped_up_not_fresh_login
trusted_device_limit_reached
trusted_device_attestation_failed
session_too_old_for_enrollment
```

**推断**：可信设备身份受服务端令牌和密钥关系约束，单独随机化普通请求头 ID 不会自动获得一个新的可信设备登记。

## 4. 封禁、地区限制、账号验证的代码路径

**已见**：`lia.java:22–43` 定义 `signups_disabled`、`region_missing`、`signups_disabled_in_region`、`signin_temporarily_disabled`、`invalid_recaptcha`、`account_needs_verification`、`account_banned`、`account_session_invalid`、`sign_in_not_permitted`、`mfa_challenge_sends_exhausted` 等错误。

```text
HTTP 错误体／响应元数据
  → via / uia：错误响应转换
  → t26.h(...)：错误码字符串映射到 lia
  → ClaudeApiError$AuthError
  → gz3.c(sf1) → gz3.a(lia, ...)
  → AccountBanned 或 SessionInvalid 通知流
```

`t26.java:20–31` 按字符串查表；未知值映射为 `DEFAULT_UNKNOWN_ERROR`，不会在这里自行推导封禁。`gz3.java:62–138` 的日志标签为 `AuthErrorWatcher`；识别到封禁时设置状态并发出 `az3`（`AccountBanned`），识别到会话无效时发出 `bz3`。`gz3.b():30–60` 合并待处理事件时，封禁事件优先保留。

`yge.java:751–785` 的聊天预取错误处理还将 401、封禁、会话无效和账号待验证识别为相应失败分支。该分支本身不是封禁判定器。

**未知**：哪些注册、登录、IP、行为或账号历史条件让服务端返回这些错误。客户端错误枚举不提供评分公式，也不能证明某项字段必然参与封号。

## 5. 手机验证、Cloudflare 与限流

**已见**：`fwt.java:18–58` 区分验证码错误／过期、MFA 发送耗尽、`too_many_attempts`、429、503，并单独识别 `missing_client_attestation`、`attestation_rejected` 和 `send_phone_code_not_permitted`。

`uia.java:15–21` 检查 `cf-mitigated: challenge`；`via.java:72–117` 根据 429 的等待室响应、该头和 Cloudflare HTML 特征生成 `CloudflareWaitingRoomError` 或 `CloudflareChallengeError`。`si20.java:27` 等读取 `x-claude-error-code` 元数据。

这说明 UI 中的验证、封禁、Cloudflare 挑战与普通限流需要分开看待。不能把所有 403／429 都称作账号封禁。

## 6. Root／注入检测的证据边界

**已见**：`io/sentry/android/core/internal/util/m.java:25–80` 包含 test-keys、su 路径、Root 管理器包名及 `which su` 检查，属于 Sentry Android SDK。仅凭此文件不能证明 Claude 将 Root 结果提交给封禁接口，或会因检测到 Root／LSPosed 自动封号。

**后续已见**：Sift 采集链包含专门的环境证据列表，详见新增环境覆盖文档。仍未知：完整的原生 Root／反注入检测覆盖及服务端评分规则。全量反编译不完整，不能以搜索结果证明没有其他采集路径。arm64 拆分包含 Sentry、语音、SQLite 等原生库，本轮未逐一做原生反汇编。

## 7. 模块范围与下一步

用户追加要求：支持随机设备 ID。准确范围、候选 Hook、默认稳定随机值和手动重置策略记录在 [设备 ID 需求](device-id-requirements.md)。

当前建议从 `s2h(String)` 构造参数处统一覆盖自有标识，而不是只改请求头或全局改所有 UUID。**这是待验证设计**；该初步方案现已进入模块实现，具体构建与设备状态以 README 和验证记录为准；Claude 启动及登录测试仍未执行。不能声称该方案已经在目标进程生效，也不能承诺规避账号风控。
