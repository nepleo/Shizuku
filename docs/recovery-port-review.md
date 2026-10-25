# Shizuku 自动恢复移植审查

来源：thedjchi/Shizuku 的 BootCompleteReceiver、AdbStartWorker、WatchdogService、AdbStarter。
比较基线：b844bc49；参考分支：15ade0e4。借鉴并适配管理端恢复流程，保留原版 API、server、starter 和 JNI。

正常路径：开机（首次解锁后）/Binder 死亡 → 唯一恢复任务 → 检查 Binder → 检查授权及网络 → 本地 ADB 启动 → 等待 Binder 确认 → 清除告警。

|失败来源/位置|语义|处理策略|重启后行为|
|---|---|---|---|
|RecoveryWorker：尚无 Wi-Fi|Unavailable|有界退避重试，通知等待网络|WorkManager 持久化待执行任务|
|RecoveryWorker：缺少 WRITE_SECURE_SETTINGS|Unauthorized|不盲目重试，通知打开应用手动启动|再次手动启动后可重新申请恢复|
|RecoveryWorker：配对/TLS 或网络连接失败|Unavailable|最多 6 次，退避 30 秒至系统上限|保留 WorkManager 尝试次数|
|ADB 命令已发出，但未收到 Binder|Unknown|重试前先 pingBinder，避免重复启动|重新检查真实服务状态|
|WatchdogService：Binder 死亡|Unavailable|提交唯一恢复任务；常驻服务本身不轮询唤醒手机|START_STICKY 尽力恢复，无法保证系统一定重启它|
|用户手动停止/关闭恢复|Cancelled|持久化暂停标记，取消任务，停止看门狗|保持暂停，手动成功启动或下次开机重新恢复|
|看门狗和管理进程同时被杀/应用被强停|进程级失败|系统可能重启服务/持久化任务；强停无法自救|必须明确不保证实时恢复|
|启动前台服务被系统拒绝|Unavailable|记录日志，保留恢复任务；用户打开应用时再尝试启动前台服务|不关闭用户的设置|

副作用：ADB/无线调试设置写入为幂等；唯一任务名避免并行启动；TCP 模式使用固定 5555 端口且默认关闭，只在本次成功无线连接后切换。切换时 ADB 连接可能中断，因此后续先连端口并检查 Binder。TCP 启用后不擅自关闭 USB 调试。无外部业务数据写入。

状态：待网络 → 启动中 → 已运行，或退避重试 → 需人工处理；暂停优先于所有自动动作。设置存储在设备保护存储；WorkManager 在解锁后的普通存储运行，不在 LOCKED_BOOT_COMPLETED 初始化。

高风险：原分支 Watchdog.onDestroy 写回关闭设置，本移植避免这一行为；原分支 AdbClient.connect 使用局部 socket 会导致 close 无法可靠关闭字段中的连接，本移植保留字段并设置连接/读超时。禁用开机启动时，仅在看门狗也关闭的情况下取消待处理任务。

分步实施：恢复策略与 Worker → 前台看门狗/手动停止语义 → 设置及通知 → 可选 TCP → 策略单元测试、Release 编译、lint、签名/资源验证。真实 HyperOS 开机、息屏和进程清理行为需要设备验证。

开源归属：参考功能来自 https://github.com/thedjchi/Shizuku ，遵循项目 Apache-2.0 许可证。修改后的恢复代码为适配现有分支的实现，未整套替换分支。

调度边界：无线启动任务使用与参考分支一致的非计费网络约束（通常为普通 Wi-Fi），随后检查实际 Wi-Fi；被系统标为计费的 Wi-Fi 不会触发该任务。已启用并成功建立 TCP 的恢复不要求网络连接。手机首次解锁前不启动 WorkManager。

验证结果：5 项恢复策略测试通过；Release 编译成功；使用临时基线排除 124 项原版既有 lint 错误后，新增代码 lint 无错误（仍有 3 项警告）；完整 lint 未宣称全绿。server/starter/common/API/JNI 与基线无差异。未进行真实手机开机、息屏与杀进程验证。

recovery.2 通知调整：看门狗使用独立的静默状态通道，等待 Wi-Fi、启动和自动重试仅更新同一条前台通知；仅缺少启动权限/重试最终失败使用独立的需要处理通道。首次配对保留输入与失败操作入口，成功后撤销通知。通知代码不改变 native/server/API 或恢复策略。

recovery.2 验证：5 项单元测试通过；Release 构建与签名、ZIP/DEX 标记验证通过；排除原版既有基线后 lint 无错误。实机通知外观和系统分组尚待设备确认。

recovery.3：将通知职责集中到 RecoveryNotifications；主动删除闲置旧通道，检测到旧前台通知时延后清理；移除旧通道标签和日常状态的展开长文本。状态默认使用符合前台服务语义的 LOW 静默级别，保留现有通道用户设置。5 项既有测试、Release、APK 签名及完整性验证通过，排除原版基线后 lint 无错误。旧通道迁移的真实 HyperOS 展示仍待设备确认。
