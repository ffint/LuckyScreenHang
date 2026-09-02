# LuckyScreenHang

轻量 Android 息屏挂机工具。当前正式版本：**v1.4.5**，面向 Android 17 / API 37。

## 工作方式

1. 启动 App 后检查悬浮窗权限和 Shizuku 授权。
2. 通过 Shizuku API 启动一个 daemon `UserService`，以 shell UID 2000 运行；不再在每次点击时临时启动 `rish/app_process`。
3. 权限准备完成后显示一个小型 `TYPE_APPLICATION_OVERLAY` 按钮，文字为“息屏挂机”，Activity 随后退出。
4. 点击按钮后先显示短暂黑色遮罩，再由 UserService 对物理 Display token 调用 `SurfaceControl.setDisplayPowerMode(..., POWER_MODE_OFF)`；只有直接物理控制不可用时才回退到一次 `cmd display power-off 0`。
5. Android 仍保持 interactive，应用/游戏可继续运行；后台只保留 daemon UserService 和阻塞式 Power 键 watcher，不进行周期轮询。
6. 第一次按 Power 让系统正常进入 sleep/锁屏，watcher 确认系统电源状态后恢复 `screen_off_timeout` 并结束本次 session；第二次按 Power 由 Android 正常唤醒屏幕。
7. 如果旧 watcher 异常残留，新一次请求会先清理旧 session 再继续，不会永久卡在 `already running`。

## v1.4.5

- 保持已验证稳定的 v1.4.4 息屏/恢复逻辑不变。
- 悬浮按钮调整为 `96dp × 44dp`。
- 按钮文字大小调整为 `14sp`。
- 按钮文字改为 **“息屏挂机”**。
- `versionCode 15` / `versionName 1.4.5`。

## 平台与权限

- Android 17 / `targetSdkVersion 37`
- 包名：`com.lucky.screenhang.v13`
- Shizuku API `13.1.5`
- 权限：`SYSTEM_ALERT_WINDOW`、Shizuku API 权限
- 无网络权限、无存储权限

## 构建

v1.4 工程位于 `v14/`：

```sh
cd v14
gradle :app:assembleDebug
```

正式版本由 GitHub Actions 的 `Release LuckyScreenHang v1.4.5` 工作流构建和发布。仓库只保存正式签名的公钥证书；私钥必须放在 GitHub Actions Secret `LUCKY_RELEASE_KEY_PEM_B64` 中，绝不提交到仓库。

## Debug / 日志

UserService 会把运行日志写到：

```text
/sdcard/Download/LuckyScreenHang-v14.log
```

悬浮按钮支持长按复制当前 UserService 日志。

## 紧急恢复

极少数异常情况下可以通过 ADB 执行：

```sh
adb shell cmd display power-reset 0
adb shell settings put system screen_off_timeout <原值>
```

必要时可使用硬件组合键强制重启。

旧的 v1.3.x 极简 native 实现仍保留在仓库根目录作为历史参考；当前主线实现为 `v14/` 下的 Shizuku UserService 架构。
