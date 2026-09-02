# LuckyScreenHang v1.4 UserService experiment

This branch replaces the per-click `rish_shizuku.dex` path with Shizuku API 13.1.5 + a daemon `UserService`.

Key differences:
- Shizuku permission is requested through the official API.
- A daemon UserService runs as shell/root identity and survives the app process.
- Each screen-off action is one Binder RPC; there is no per-click `app_process`/rish handshake.
- Power-key watching and display restore are owned by the UserService Java process.
- Experimental visible log: `/sdcard/Download/LuckyScreenHang-v14.log` when shell SELinux/storage policy allows it.

The stable v1.3.x implementation remains on `main`.
