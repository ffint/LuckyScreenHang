package com.lucky.screenhang.v13;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class LuckyUserService extends ILuckyUserService.Stub {
    private final StringBuilder log = new StringBuilder();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int oldTimeout = 60000;
    private volatile Process watcherProcess;

    public LuckyUserService() { add("UserService created"); }

    @Override public void destroy() { add("destroy"); System.exit(0); }

    @Override public synchronized int screenOff() {
        add("screenOff request uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());
        if (!running.compareAndSet(false, true)) { add("already running"); return 46; }
        try {
            String old = command("/system/bin/settings", "get", "system", "screen_off_timeout").trim();
            try { oldTimeout = Integer.parseInt(old); } catch (Throwable ignored) { oldTimeout = 60000; }
            int set = commandCode("/system/bin/settings", "put", "system", "screen_off_timeout", "2147483647");
            if (set != 0) { add("set timeout failed=" + set); running.set(false); return 43; }

            watcherProcess = null;
            Thread watcher = new Thread(this::watchPowerAndRestore, "LuckyPowerWatcher");
            watcher.setDaemon(false);
            watcher.start();

            for (int i=0; i<10 && watcherProcess==null && running.get(); i++) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            Process wp = watcherProcess;
            if (wp == null || !wp.isAlive()) {
                add("watcher failed to stay alive");
                restore(); running.set(false); return 44;
            }

            int off = commandCode("/system/bin/cmd", "display", "power-off", "0");
            add("power-off rc=" + off);
            if (off != 0) {
                try { wp.destroy(); } catch (Throwable ignored) {}
                restore(); running.set(false); return 45;
            }
            return 0;
        } catch (Throwable t) {
            add("screenOff exception: " + t);
            restore(); running.set(false); return 47;
        }
    }

    private void watchPowerAndRestore() {
        Process p = null;
        try {
            add("watcher starting getevent");
            p = new ProcessBuilder("/system/bin/getevent", "-ql").redirectErrorStream(true).start();
            watcherProcess = p;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("KEY_POWER") && line.contains("UP")) { add("KEY_POWER UP detected"); break; }
                }
            }
            for (int i=0;i<20;i++) {
                String power = command("/system/bin/dumpsys", "power");
                if (power.contains("mWakefulness=Asleep") || power.contains("mWakefulness=Dozing") || power.contains("mInteractive=false")) break;
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        } catch (Throwable t) { add("watcher exception: " + t); }
        finally {
            watcherProcess = null;
            if (p != null) p.destroy();
            restore(); running.set(false); add("watcher restored display");
        }
    }

    private synchronized void restore() {
        int rc = commandCode("/system/bin/cmd", "display", "power-reset", "0");
        if (rc != 0) commandCode("/system/bin/cmd", "display", "power-on", "0");
        commandCode("/system/bin/settings", "put", "system", "screen_off_timeout", Integer.toString(oldTimeout));
    }

    private String command(String... cmd) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String s; while ((s=br.readLine())!=null) out.append(s).append('\n');
            }
            int rc=p.waitFor();
            if (rc!=0) add("command rc="+rc+" cmd="+String.join(" ",cmd)+" out="+out.toString().trim());
        } catch(Throwable t) { add("command exception "+String.join(" ",cmd)+": "+t); }
        return out.toString();
    }

    private int commandCode(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder out=new StringBuilder();
            try (BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream()))) { String s; while((s=br.readLine())!=null) out.append(s).append('\n'); }
            int rc=p.waitFor(); if(rc!=0) add("command rc="+rc+" cmd="+String.join(" ",cmd)+" out="+out.toString().trim()); return rc;
        } catch(Throwable t) { add("command exception "+String.join(" ",cmd)+": "+t); return 127; }
    }

    private synchronized void add(String s) {
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        log.append(ts).append(' ').append(s).append('\n');
        if (log.length()>20000) log.delete(0, log.length()-16000);
        try (FileOutputStream f = new FileOutputStream("/sdcard/Download/LuckyScreenHang-v14.log")) {
            f.write(log.toString().getBytes(StandardCharsets.UTF_8));
        } catch(Throwable ignored) {}
    }

    @Override public String status() { return "uid="+android.os.Process.myUid()+", pid="+android.os.Process.myPid()+", running="+running.get(); }
    @Override public synchronized String getLog() { return log.toString(); }
}
