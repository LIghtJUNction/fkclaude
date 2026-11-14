# 指南检索与默认值决策

本次同时检索官方规则与中文社区“防封指南”。公开资料没有可验证的客户端“防封参数表”，不承诺修改语言、时区、指纹或出口能够避免封禁。

## 来源与可信度

| 来源 | 能支持的结论 | 本项目处理 |
| --- | --- | --- |
| [Claude Safeguards, Warnings and Appeals](https://support.claude.com/en/articles/8241253-safeguards-warnings-and-appeals) | 停用原因包括违反使用政策、服务条款及不支持的位置；官方提供申诉入口 | 不把所有停用归因于设备指纹，也不声称模块解决账户封禁 |
| [Anthropic 支持地区](https://www.anthropic.com/legal/supported-regions?swcfpc=1) | 地区资格由官方规则规定 | 不用固定美国时区或语言暗示获得服务资格 |
| [系统信任报告](https://www.anthropic.com/transparency/system-trust-reporting) | 官方执行账户政策，但未公开客户端风险权重 | 不编造安全阈值、封号概率或必然触发条件 |
| [腾讯云开发者社区指南](https://developer.cloud.tencent.com/article/2704743) | 作者将 IP 变化、地区信息不一致等列为经验性风险，亦提及查 IP 的应用与目标应用可能走不同路径 | 仅采纳路径需要一致这一工程要求；其防封归因没有当作已验证事实 |
| [VCCBus 商业指南](https://www.vccbus.com/claude-anti-ban) | 与上述社区说法存在冲突，认为 IP／设备指纹不是直接原因；含商业推荐 | 作为相互矛盾的经验说法，不转成默认指纹或付费产品推荐 |

社区资料不是技术规范。本次没有采用购买账户、轮换身份、虚构地区或所谓“纯净 IP 必保号”等建议。

## 实际调整

- 新预设不再强制 `en-US`／`UTC`，减少无依据的地域假定；已有配置保持原样。
- Canvas／WebGL／音频整体阻断改为默认关闭，用户仍可单独选择开启。阻断可能损害图形与语音功能，不能据此承诺降低账户风险。
- 默认局域网 IP 留空；不把 `192.0.2.2` 当成真实设备地址。[RFC 5737](https://www.rfc-editor.org/rfc/rfc5737) 将该段保留用于文档示例。
- 重新应用预设保留已生成的设备 ID 和完整守卫配置，避免预设按钮意外改变身份或关闭守卫。[Android 标识符指南](https://developer.android.com/identity/user-data-ids) 是稳定性与隐私设计的参考，不是 Claude 风控说明。
- 增加独立 IP／CIDR 白名单守卫；默认关闭，配置空名单后开启则阻断，不自动学习当前公网 IP。

IP 回显只验证探测请求所见的出口，不能证明分域代理的其他请求也走相同出口。实现、隐私和检测窗口见 [IP 守卫说明](ip-guard.md)。
