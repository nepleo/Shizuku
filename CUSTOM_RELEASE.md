# Shizuku 自定义版交付说明

交付日期：2026-10-25。版本：`13.6.0.r1091.b844bc49.recovery.3`，versionCode：`1094`。

[下载已签名 APK](artifacts/Shizuku-recovery.3-clean-notifications-release.apk) · [校验值](artifacts/SHA256SUMS) · [版本信息](artifacts/release-info.json)

## 此次提交包含的全部定制

- 隐藏最近任务卡片开关，默认关闭；不改变 Shizuku 服务本身。
- 开机后首次解锁时提交启动任务；无线启动等待非计费网络（通常为普通 Wi-Fi），失败时有界退避重试。
- 可选前台看门狗：监听 Binder 死亡，意外停止后尝试恢复。用户手动停止时持久化暂停状态并取消恢复。
- 可选 TCP 模式，默认关闭：无线连接成功后切换到 ADB TCP 5555；本次开机的后续恢复可不依赖 Wi-Fi。每次手机重启后的首次无线启动仍需 Wi-Fi。
- 看门狗仅保留一条静默状态通知，等待 Wi-Fi、启动和重试更新同一张卡；启动失败或权限问题需要处理时单独提醒。
- 首次配对的输入通知保留，成功后撤掉；配对失败可点击打开 Shizuku。
- 升级时删除旧版 `shizuku_recovery` 通道；若仍被前台服务使用，先切换新通道再清理。通知构建、通道和迁移集中在 `RecoveryNotifications`，只保留配对、状态和需要处理的提醒。沿用现有状态/告警通道 ID，保留用户关闭或静默设置。

恢复设计借鉴 [thedjchi/Shizuku](https://github.com/thedjchi/Shizuku)，按现有原版管理端适配；没有整套替换该分支。`server`、`starter`、`common`、API 子模块及 JNI 与原始基线 `b844bc491f1790c72328e1a8e5b2349f8978f0ea` 保持一致。

## 使用与边界

1. 首次配对并手动成功启动 Shizuku；启动权限需要正确授予。
2. 根据需要打开开机启动、异常停止后自动恢复；在 HyperOS 中允许系统自启动及后台运行。
3. 如需离开 Wi-Fi 后也能恢复，启用 TCP 模式，并在 Wi-Fi 下成功启动一次。只接受自己发起的调试授权。关闭 TCP 开关不会关闭已存在的端口，可重启手机关闭。

Shizuku 正常运行不需要始终连接 Wi-Fi。前台服务和持久化任务不能保证不被系统终止，WorkManager 执行也可能受休眠影响；这不是通知实时到达的保证。

## 验证与构建

- 5 项恢复策略单元测试通过，Release 构建成功。
- 排除原版已有的 124 项 lint 错误后，自定义改动检查无错误；未宣称原版完整 lint 全绿。
- APK ZIP 完整性、签名及通知通道 DEX 标记均已校验。未进行真实 HyperOS 开机、锁屏、配对与通知显示验证。
- 已固定当前发行版本号，提交源代码后不会因 Git 提交数改变而漂移。

环境：JDK 21、Android SDK/Build Tools 36、NDK `29.0.13113456`、CMake `3.31.0`。使用 `git clone --recurse-submodules` 获取 API 子模块；配置本机 SDK 及自己的 `signing.properties` 后执行：

```sh
./gradlew :manager:testDebugUnitTest :manager:assembleRelease
```

仓库包含已签名安装包，不包含签名私钥、密码、`local.properties` 或构建缓存。继续覆盖更新需使用同一签名；`artifacts/release-info.json` 记录签名证书指纹。

详细恢复审查见 [recovery-port-review.md](docs/recovery-port-review.md)。
