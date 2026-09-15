# DingTalk Enhancement Modern

现代 libxposed API 102 模块，针对 `rimet_36180121811227.apk`（Rimet 8.5.5 / versionCode 1274）。

已用 DexSQL 查询目标 dex：

- `Lnii;->I(Map,List,List,Callback)` 与 `Lnii;->K(String,long,Callback)`：屏蔽已读状态上报。
- `Lofi;->f(String,List,ContentValues)`：拦截 `recall=1` 的本地删除动作。
- `Lofi;->c(String,Collection,boolean)`：缓存消息，在收到撤回状态后恢复文本/回复文本并写回数据库；重复事件不会再次追加 `[已撤回]`。
- `Lqfi;->W()`：新版消息数据层单例；`Lofi;->T(String,String,List)`：消息更新。

撤回提示现在使用居中的浅灰圆角提示层，文本为“用户名尝试撤回上一条消息 [已阻止]”，因此图片、文件、语音等无法直接修改正文的消息也会有提示。由于新版会话列表的系统消息对象由内部列表适配器创建，当前提示层是模块侧的稳定兼容实现，并非伪造数据库中的系统消息记录。

本版本不包含旧模块的“隐藏模块/规避注入检测”逻辑。模块默认对目标版本启用两项功能，建议仅在自有测试账号和测试设备上验证。

设置页提供“生成本机配置”和“尝试修复插件”：前者通过当前安装的钉钉 APK 的 PathClassLoader 校验本版本所需的核心类和方法签名，并把版本、通过项和失败项写入 libxposed 共享配置；后者清理旧的本机诊断结果后重新执行校验。DexSQL CLI 用于离线确认和更新签名，运行时不把 DexSQL 引擎注入钉钉进程。
