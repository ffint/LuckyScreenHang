package com.lucky.screenhang.v13;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQUEST_SHIZUKU = 4201;
    private static volatile ILuckyUserService remote;
    private static WindowManager windowManager;
    private static View overlay;
    private static boolean binding;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = ILuckyUserService.Stub.asInterface(binder);
            binding = false;
            try {
                toast("UserService 已连接 · " + remote.status());
            } catch (Throwable t) {
                toast("Shizuku UserService 已连接");
            }
            showButtonAndFinish();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            remote = null;
            binding = false;
            removeOverlayNow();
            toast("UserService 已断开，请重新打开 Lucky");
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = this::prepareShizuku;
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        remote = null;
        binding = false;
        removeOverlayNow();
        toast("Shizuku 服务已断开");
    };
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code, result) -> {
        if (code == REQUEST_SHIZUKU && result == PackageManager.PERMISSION_GRANTED) {
            bindUserService();
        } else if (code == REQUEST_SHIZUKU) {
            toast("需要 Shizuku 授权");
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Lucky " + BuildConfig.VERSION_NAME + "\n正在连接 Shizuku…");
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        text.setPadding(dp(24), dp(48), dp(24), dp(24));
        setContentView(text);

        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission();
        } else {
            prepareShizuku();
        }
    }

    private void requestOverlayPermission() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            toast("请手动开启悬浮窗权限");
        }
    }

    private void prepareShizuku() {
        if (!Settings.canDrawOverlays(this)) return;
        try {
            if (!Shizuku.pingBinder()) {
                toast("等待 Shizuku Binder…");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_SHIZUKU);
                return;
            }
            if (remote != null) {
                showButtonAndFinish();
                return;
            }
            bindUserService();
        } catch (Throwable t) {
            toast("Shizuku 初始化失败: " + t.getClass().getSimpleName());
        }
    }

    private void bindUserService() {
        if (binding) return;
        binding = true;
        try {
            int serviceVersion = 140000 + BuildConfig.VERSION_CODE;
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(this, LuckyUserService.class))
                    .daemon(true)
                    .processNameSuffix("lucky_daemon")
                    .tag("lucky-screen-hang-" + BuildConfig.APPLICATION_ID)
                    .version(serviceVersion);
            Shizuku.bindUserService(args, connection);
        } catch (Throwable t) {
            binding = false;
            toast("UserService bind 失败: " + t);
        }
    }

    private void showButtonAndFinish() {
        runOnUiThread(() -> {
            removeOverlayInternal();
            Context app = getApplicationContext();
            windowManager = (WindowManager) app.getSystemService(WINDOW_SERVICE);

            TextView button = new TextView(app);
            button.setText("息屏挂机");
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setTextColor(Color.WHITE);
            button.setSingleLine(true);
            button.setIncludeFontPadding(false);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(10), 0, dp(10), 0);
            button.setElevation(dp(8));

            GradientDrawable background = new GradientDrawable();
            background.setColor(0xED266FEF);
            background.setCornerRadius(dp(22));
            background.setStroke(Math.max(1, dp(1)), 0x66FFFFFF);
            button.setBackground(background);

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    dp(96), dp(44),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            params.x = dp(12);
            button.setOnClickListener(v -> screenOff());
            button.setOnLongClickListener(v -> {
                copyCurrentLog();
                return true;
            });

            try {
                windowManager.addView(button, params);
                overlay = button;
            } catch (Throwable t) {
                toast("悬浮窗创建失败: " + t);
                return;
            }
            finishAndRemoveTask();
        });
    }

    private void showBlackCurtain() {
        runOnUiThread(() -> {
            removeOverlayInternal();
            Context app = getApplicationContext();
            windowManager = (WindowManager) app.getSystemService(WINDOW_SERVICE);
            View curtain = new View(app);
            curtain.setBackgroundColor(Color.BLACK);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            params.gravity = Gravity.FILL;
            try {
                windowManager.addView(curtain, params);
                overlay = curtain;
            } catch (Throwable t) {
                toast("遮罩创建失败: " + t.getClass().getSimpleName());
            }
        });
    }

    private void copyCurrentLog() {
        ILuckyUserService service = remote;
        if (service == null) {
            toast("UserService 未连接");
            return;
        }
        new Thread(() -> {
            try {
                String currentLog = service.getLog();
                runOnUiThread(() -> copyAndToast(currentLog, "日志已复制"));
            } catch (Throwable t) {
                toast("读取日志失败: " + t.getClass().getSimpleName());
            }
        }, "LuckyCopyLog").start();
    }

    private void removeOverlayNow() {
        runOnUiThread(this::removeOverlayInternal);
    }

    private void removeOverlayInternal() {
        View old = overlay;
        overlay = null;
        if (old != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(old);
            } catch (Throwable ignored) {}
        }
    }

    private void screenOff() {
        final ILuckyUserService service = remote;
        if (service == null) {
            removeOverlayNow();
            toast("UserService 未连接，请重新打开 Lucky");
            return;
        }

        showBlackCurtain();

        new Thread(() -> {
            try {
                try {
                    Thread.sleep(40L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                int rc = service.screenOff();
                if (rc != 0) {
                    String currentLog = service.getLog();
                    getSharedPreferences("debug", 0).edit()
                            .putString("last", currentLog).apply();
                    runOnUiThread(() -> {
                        removeOverlayInternal();
                        copyAndToast(currentLog,
                                "息屏失败 rc=" + rc + "，日志已复制");
                        showButtonAndFinish();
                    });
                } else {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            } catch (Throwable t) {
                String currentLog = "UserService 调用异常: " + t;
                try {
                    currentLog = service.getLog() + "\n" + currentLog;
                } catch (Throwable ignored) {}
                final String finalLog = currentLog;
                runOnUiThread(() -> {
                    removeOverlayInternal();
                    copyAndToast(finalLog,
                            "UserService 调用失败: " + t.getClass().getSimpleName());
                    showButtonAndFinish();
                });
            }
        }, "LuckyScreenOffCall").start();
    }

    private void copyAndToast(String text, String message) {
        try {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("Lucky v1.4 log", text));
        } catch (Throwable ignored) {}
        toast(message);
    }

    private int dp(int value) {
        return Math.max(1,
                Math.round(value * getResources().getDisplayMetrics().density));
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(getApplicationContext(), message,
                Toast.LENGTH_LONG).show());
    }
}
