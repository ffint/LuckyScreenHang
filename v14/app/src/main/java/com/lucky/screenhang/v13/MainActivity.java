package com.lucky.screenhang.v13;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ=4201;
    private static volatile ILuckyUserService remote;
    private static WindowManager wm;
    private static View overlay;
    private static boolean binding;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            remote=ILuckyUserService.Stub.asInterface(b); binding=false;
            try { toast("UserService 已连接 · " + remote.status()); }
            catch (Throwable t) { toast("Shizuku UserService 已连接"); }
            showOverlayAndFinish();
        }
        @Override public void onServiceDisconnected(ComponentName n) { remote=null; binding=false; }
    };

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> prepareShizuku();
    private final Shizuku.OnBinderDeadListener binderDead = () -> { remote=null; binding=false; toast("Shizuku 服务已断开"); };
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (code,result) -> {
        if(code==REQ && result==PackageManager.PERMISSION_GRANTED) bindUserService();
        else if(code==REQ) toast("需要 Shizuku 授权");
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        TextView v=new TextView(this); v.setText("Lucky v1.4 UserService 实验版\n正在连接 Shizuku…"); v.setTextSize(18); v.setPadding(40,80,40,40); setContentView(v);
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
    }

    @Override public void onResume() { super.onResume(); if(!Settings.canDrawOverlays(this)) requestOverlay(); else prepareShizuku(); }

    private void requestOverlay(){ try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName()))); } catch(Throwable t){ toast("请手动开启悬浮窗权限"); } }

    private void prepareShizuku(){
        if(!Settings.canDrawOverlays(this)) return;
        try {
            if(!Shizuku.pingBinder()) { toast("等待 Shizuku Binder…"); return; }
            if(Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(REQ); return; }
            if(remote!=null) { showOverlayAndFinish(); return; }
            bindUserService();
        } catch(Throwable t){ toast("Shizuku 初始化失败: "+t.getClass().getSimpleName()); }
    }

    private void bindUserService(){
        if(binding) return; binding=true;
        try {
            Shizuku.UserServiceArgs args=new Shizuku.UserServiceArgs(new ComponentName(this, LuckyUserService.class))
                    .daemon(true).processNameSuffix("lucky_daemon").tag("lucky-screen-hang-v14").version(1400);
            Shizuku.bindUserService(args, conn);
        } catch(Throwable t){ binding=false; toast("UserService bind 失败: "+t); }
    }

    private void showOverlayAndFinish(){ runOnUiThread(() -> {
        if(overlay==null){
            wm=(WindowManager)getApplicationContext().getSystemService(WINDOW_SERVICE);
            Button b=new Button(getApplicationContext()); b.setText("息屏 US"); b.setTextSize(13); b.setAllCaps(false);
            WindowManager.LayoutParams lp=new WindowManager.LayoutParams(150,100, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
            lp.gravity=Gravity.END|Gravity.CENTER_VERTICAL; lp.x=16;
            b.setOnClickListener(v -> doScreenOff());
            try { wm.addView(b,lp); overlay=b; } catch(Throwable t){ toast("悬浮窗创建失败: "+t); return; }
        }
        finishAndRemoveTask();
    }); }

    private void doScreenOff(){
        if(overlay!=null){ try{ wm.removeViewImmediate(overlay);}catch(Throwable ignored){} overlay=null; }
        final ILuckyUserService r=remote;
        if(r==null){ toast("UserService 未连接，请重新打开 Lucky"); return; }
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                int rc=r.screenOff();
                if(rc!=0){ String log=r.getLog(); getSharedPreferences("dbg",0).edit().putString("last",log).apply(); runOnUiThread(() -> copyAndToast(log,"息屏失败 rc="+rc+"，日志已复制")); }
                else { try{ Thread.sleep(300);}catch(Exception ignored){} android.os.Process.killProcess(android.os.Process.myPid()); }
            } catch(Throwable t){ runOnUiThread(() -> toast("UserService 调用失败: "+t.getClass().getSimpleName())); }
        });
    }

    private void copyAndToast(String s,String msg){ try{ ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Lucky v1.4 log",s)); }catch(Throwable ignored){} toast(msg); }
    private void toast(String s){ runOnUiThread(() -> Toast.makeText(getApplicationContext(),s,Toast.LENGTH_LONG).show()); }
}
