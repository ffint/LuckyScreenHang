# LuckyScreenHang

轻量 Android 息屏挂机工具，当前版本 **v1.3.2**，面向 Android 17 / API 37。

## 工作方式

1. 启动 App 后先检查/请求“显示在其他应用上层”权限，再预检查 Shizuku；Shizuku 不可用时使用 Magisk Root 兜底。
2. 权限准备成功后创建一个很小的 `TYPE_APPLICATION_OVERLAY` 圆形「息屏」按钮，并把自身 Activity 退到后台。
3. 不读取 Recent Tasks，不调用 `am task focus`，也不猜测“上一个 App”。
4. 在任何当前界面点击悬浮按钮：Shizuku 优先，失败则 Magisk Root 兜底；物理显示进入 OFF，但 Android 仍保持 interactive。
5. 点击按钮的同一 UI 回调中立即移除悬浮窗，然后执行息屏；App 随后退出。后台只剩一个阻塞在 `getevent` 的 shell watcher，空闲时不轮询。
6. 恢复时第一次按 Power 让系统正常进入 sleep/锁屏；watcher 检测 `KEY_POWER UP` 后解除 display power override 并恢复原 `screen_off_timeout`，随后自行退出；第二次按 Power 由 Android 正常唤醒/亮屏。

## 平台

- Android 17 / `targetSdkVersion 37`
- `arm64-v8a`
- Native ELF 16 KB page alignment
- 包名：`com.lucky.screenhang.v13`
- 权限：`SYSTEM_ALERT_WINDOW`
- 无网络权限、无存储权限

## 构建

```sh
./build.sh
```

主要需要：Python 3、clang/lld（支持 `aarch64-linux-android29` target）、OpenSSL、zip。

构建脚本会在本地 `signing/` 下生成测试签名密钥，并将产物写到 `out/`。这两个目录都不应提交到 Git。

## 紧急恢复

极少数 ROM 或 Shizuku 被强停导致 watcher 消失时：

```sh
adb shell cmd display power-reset 0
adb shell settings put system screen_off_timeout <原值>
```

必要时可使用硬件组合键强制重启。

更详细的版本说明与恢复说明见仓库中的 `CHANGES-v1.3.2.txt`、`DEX_FIX.txt` 和 `RECOVERY.txt`。
