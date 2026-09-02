package com.lucky.screenhang.v13;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LuckyUserService extends ILuckyUserService.Stub {
    private static final int DISPLAY_DEFAULT = 0;
    private static final int DISPLAY_STATE_UNKNOWN = 0;
    private static final int DISPLAY_STATE_OFF = 1;

    private final StringBuilder log = new StringBuilder();
    private final Object logLock = new Object();
    private final Object sessionLock = new Object();

    private volatile boolean running;
    private volatile long currentSession;
    private volatile boolean stopRequested;
    private volatile int oldTimeout = 60000;
    private volatile Process watcherProcess;

    private volatile Object displayManagerGlobal;
    private volatile Method requestDisplayPowerMethod;

    public LuckyUserService() {
        add("UserService created version=" + BuildConfig.VERSION_NAME
                + " versionCode=" + BuildConfig.VERSION_CODE
                + " uid=" + android.os.Process.myUid()
                + " pid=" + android.os.Process.myPid());
    }

    @Override
    public void destroy() {
        add("destroy requested");
        synchronized (sessionLock) {
            cancelCurrentSessionLocked("service destroy");
        }
        System.exit(0);
    }

    @Override
    public int screenOff() {
        final long session;
        synchronized (sessionLock) {
            if (running) {
                add("new request while session=" + currentSession
                        + " is still active; cancelling stale session");
                cancelCurrentSessionLocked("new screenOff request");
            }
            session = ++currentSession;
            running = true;
            stopRequested = false;
        }

        add("screenOff request session=" + session
                + " uid=" + android.os.Process.myUid()
                + " pid=" + android.os.Process.myPid());

        try {
            String old = command("/system/bin/settings", "get", "system",
                    "screen_off_timeout").trim();
            try {
                oldTimeout = Integer.parseInt(old);
            } catch (Throwable ignored) {
                oldTimeout = 60000;
                add("invalid old timeout='" + old + "'; fallback=" + oldTimeout);
            }

            int setTimeout = commandCode("/system/bin/settings", "put", "system",
                    "screen_off_timeout", "2147483647");
            if (setTimeout != 0) {
                add("set timeout failed rc=" + setTimeout);
                finishSession(session, false, "set timeout failed");
                return 43;
            }

            CountDownLatch watcherReady = new CountDownLatch(1);
            Thread watcher = new Thread(
                    () -> watchWakeAndRestore(session, watcherReady),
                    "LuckyPowerWatcher-" + session);
            watcher.setDaemon(false);
            watcher.start();

            boolean ready;
            try {
                ready = watcherReady.await(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ready = false;
            }

            Process wp = watcherProcess;
            if (!isCurrent(session) || !ready || wp == null || !wp.isAlive()) {
                add("watcher failed session=" + session
                        + " ready=" + ready
                        + " process=" + (wp != null)
                        + " alive=" + (wp != null && wp.isAlive()));
                finishSession(session, false, "watcher startup failed");
                return 44;
            }
            add("watcher ready session=" + session);

            boolean binderOk;
            int shellRc;
            synchronized (sessionLock) {
                if (!isCurrent(session) || stopRequested) {
                    finishSession(session, false, "cancelled before power-off");
                    return 48;
                }
                binderOk = requestDisplayPower(DISPLAY_STATE_OFF);
                shellRc = binderOk ? 0
                        : commandCode("/system/bin/cmd", "display", "power-off", "0");
            }
            add("power-off requested session=" + session
                    + " binder=" + binderOk + " shellRc=" + shellRc);

            if (!binderOk && shellRc != 0) {
                finishSession(session, false, "all power-off paths failed");
                return 45;
            }

            Thread guard = new Thread(() -> keepDisplayOff(session),
                    "LuckyDisplayGuard-" + session);
            guard.setDaemon(true);
            guard.start();
            return 0;
        } catch (Throwable t) {
            add("screenOff exception session=" + session + ": " + stackMessage(t));
            finishSession(session, false, "screenOff exception");
            return 47;
        }
    }

    private void keepDisplayOff(long session) {
        int pass = 0;
        while (isCurrent(session) && running && !stopRequested) {
            try {
                Thread.sleep(pass < 8 ? 250L : 1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            boolean ok;
            int shellRc = 0;
            synchronized (sessionLock) {
                if (!isCurrent(session) || !running || stopRequested) return;
                ok = requestDisplayPower(DISPLAY_STATE_OFF);
                if (!ok && pass % 4 == 0) {
                    shellRc = commandCode("/system/bin/cmd", "display", "power-off", "0");
                }
            }
            if (!ok && pass % 4 == 0) {
                add("display guard shell fallback session=" + session + " rc=" + shellRc);
            }
            pass++;
        }
    }

    private void watchWakeAndRestore(long session, CountDownLatch ready) {
        Process p = null;
        boolean wakeKey = false;
        try {
            add("watcher starting getevent session=" + session);
            p = new ProcessBuilder("/system/bin/getevent", "-ql")
                    .redirectErrorStream(true)
                    .start();
            if (!isCurrent(session)) {
                p.destroy();
                ready.countDown();
                return;
            }
            watcherProcess = p;
            ready.countDown();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while (isCurrent(session) && (line = br.readLine()) != null) {
                    if (isWakeRelease(line)) {
                        wakeKey = true;
                        stopRequested = true;
                        add("wake key detected session=" + session
                                + " event=" + compact(line));
                        break;
                    }
                }
            }

            if (wakeKey && isCurrent(session)) {
                boolean slept = waitForSystemSleep();
                add("power transition session=" + session
                        + " sleepConfirmed=" + slept);
            }
        } catch (Throwable t) {
            ready.countDown();
            add("watcher exception session=" + session + ": " + stackMessage(t));
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
            if (watcherProcess == p) watcherProcess = null;
            if (isCurrent(session)) {
                finishSession(session, wakeKey, wakeKey ? "wake key" : "watcher ended");
            } else {
                add("old watcher exited session=" + session
                        + " current=" + currentSession);
            }
        }
    }

    private boolean waitForSystemSleep() {
        for (int i = 0; i < 25; i++) {
            String power = command("/system/bin/dumpsys", "power");
            if (power.contains("mWakefulness=Asleep")
                    || power.contains("mWakefulness=Dozing")
                    || power.contains("mInteractive=false")) {
                return true;
            }
            try {
                Thread.sleep(80L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isWakeRelease(String line) {
        if (line == null) return false;
        boolean release = line.contains(" UP")
                || line.endsWith("UP")
                || line.contains(" 00000000");
        if (!release) return false;
        return line.contains("KEY_POWER")
                || line.contains("KEY_WAKEUP")
                || line.contains("KEY_SLEEP");
    }

    private boolean isCurrent(long session) {
        return currentSession == session;
    }

    private void finishSession(long session, boolean afterPowerKey, String reason) {
        synchronized (sessionLock) {
            if (!isCurrent(session)) return;
            stopRequested = true;
            Process p = watcherProcess;
            watcherProcess = null;
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
            restore(afterPowerKey);
            running = false;
            add("session finished session=" + session
                    + " reason=" + reason
                    + " afterPowerKey=" + afterPowerKey);
        }
    }

    private void cancelCurrentSessionLocked(String reason) {
        if (!running) return;
        long oldSession = currentSession;
        stopRequested = true;
        currentSession++;
        Process p = watcherProcess;
        watcherProcess = null;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) {}
        }
        restore(false);
        running = false;
        add("session cancelled oldSession=" + oldSession + " reason=" + reason);
    }

    private void restore(boolean afterPowerKey) {
        boolean binderReset = requestDisplayPower(DISPLAY_STATE_UNKNOWN);
        int resetRc = binderReset ? 0
                : commandCode("/system/bin/cmd", "display", "power-reset", "0");
        add("display reset binder=" + binderReset + " shellRc=" + resetRc
                + " afterPowerKey=" + afterPowerKey);
        commandCode("/system/bin/settings", "put", "system",
                "screen_off_timeout", Integer.toString(oldTimeout));
    }

    private boolean requestDisplayPower(int state) {
        try {
            ensureDisplayManager();
            Object result = requestDisplayPowerMethod.invoke(
                    displayManagerGlobal, DISPLAY_DEFAULT, state);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable t) {
            add("DisplayManager state=" + state + " failed: " + stackMessage(t));
            requestDisplayPowerMethod = null;
            displayManagerGlobal = null;
            return false;
        }
    }

    private void ensureDisplayManager() throws Exception {
        if (requestDisplayPowerMethod != null && displayManagerGlobal != null) return;
        synchronized (sessionLock) {
            if (requestDisplayPowerMethod != null && displayManagerGlobal != null) return;
            Class<?> clazz = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Method getInstance = clazz.getDeclaredMethod("getInstance");
            Method request = clazz.getDeclaredMethod(
                    "requestDisplayPower", int.class, int.class);
            getInstance.setAccessible(true);
            request.setAccessible(true);
            Object instance = getInstance.invoke(null);
            if (instance == null) throw new IllegalStateException("DisplayManagerGlobal is null");
            displayManagerGlobal = instance;
            requestDisplayPowerMethod = request;
            add("DisplayManager Binder ready");
        }
    }

    private String command(String... cmd) {
        StringBuilder out = new StringBuilder();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String s;
                while ((s = br.readLine()) != null) out.append(s).append('\n');
            }
            int rc = p.waitFor();
            if (rc != 0) {
                add("command rc=" + rc + " cmd=" + String.join(" ", cmd)
                        + " out=" + compact(out.toString()));
            }
        } catch (Throwable t) {
            add("command exception " + String.join(" ", cmd)
                    + ": " + stackMessage(t));
        }
        return out.toString();
    }

    private int commandCode(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String s;
                while ((s = br.readLine()) != null) out.append(s).append('\n');
            }
            int rc = p.waitFor();
            if (rc != 0) {
                add("command rc=" + rc + " cmd=" + String.join(" ", cmd)
                        + " out=" + compact(out.toString()));
            }
            return rc;
        } catch (Throwable t) {
            add("command exception " + String.join(" ", cmd)
                    + ": " + stackMessage(t));
            return 127;
        }
    }

    private String compact(String s) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 300
                ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    private String stackMessage(Throwable t) {
        Throwable x = t;
        if (x instanceof InvocationTargetException
                && ((InvocationTargetException) x).getTargetException() != null) {
            x = ((InvocationTargetException) x).getTargetException();
        }
        return x.getClass().getName() + ": " + String.valueOf(x.getMessage());
    }

    private void add(String s) {
        synchronized (logLock) {
            String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            log.append(ts).append(' ').append(s).append('\n');
            if (log.length() > 30000) log.delete(0, log.length() - 24000);
            try (FileOutputStream f = new FileOutputStream(
                    "/sdcard/Download/LuckyScreenHang-v14.log")) {
                f.write(log.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public String status() {
        return "uid=" + android.os.Process.myUid()
                + ", pid=" + android.os.Process.myPid()
                + ", version=" + BuildConfig.VERSION_NAME
                + ", running=" + running
                + ", session=" + currentSession;
    }

    @Override
    public String getLog() {
        synchronized (logLock) {
            return log.toString();
        }
    }
}
