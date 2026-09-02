package com.lucky.screenhang.v13;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class LuckyUserService extends ILuckyUserService.Stub {
    private static final int PATH_NONE = 0;
    private static final int PATH_SURFACE_CONTROL = 1;
    private static final int PATH_DISPLAY_MANAGER = 2;

    private final StringBuilder log = new StringBuilder();
    private final Object logLock = new Object();
    private final Object sessionLock = new Object();
    private final PhysicalDisplayPower panelPower = new PhysicalDisplayPower();

    private volatile boolean running;
    private volatile long currentSession;
    private volatile boolean stopRequested;
    private volatile int oldTimeout = 60000;
    private volatile int activePowerPath = PATH_NONE;
    private volatile Process watcherProcess;

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
                        + " is active; cancelling stale session");
                cancelCurrentSessionLocked("new screenOff request");
            }
            session = ++currentSession;
            running = true;
            stopRequested = false;
            activePowerPath = PATH_NONE;
        }

        add("screenOff request session=" + session
                + " uid=" + android.os.Process.myUid()
                + " pid=" + android.os.Process.myPid());

        try {
            saveAndExtendScreenTimeout();

            CountDownLatch watcherReady = new CountDownLatch(1);
            Thread watcher = new Thread(
                    () -> watchPowerAndRestore(session, watcherReady),
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

            Process process = watcherProcess;
            if (!isCurrent(session) || !ready || process == null || !process.isAlive()) {
                add("watcher failed session=" + session
                        + " ready=" + ready
                        + " process=" + (process != null)
                        + " alive=" + (process != null && process.isAlive()));
                finishSession(session, false, false, "watcher startup failed");
                return 44;
            }
            add("watcher ready session=" + session);

            PhysicalDisplayPower.Result direct;
            synchronized (sessionLock) {
                if (!isCurrent(session) || stopRequested) {
                    finishSession(session, false, false, "cancelled before power-off");
                    return 48;
                }
                direct = panelPower.setMode(PhysicalDisplayPower.MODE_OFF);
                if (direct.success) {
                    activePowerPath = PATH_SURFACE_CONTROL;
                }
            }

            int shellRc = 0;
            if (!direct.success) {
                shellRc = commandCode("/system/bin/cmd", "display", "power-off", "0");
                if (shellRc == 0) activePowerPath = PATH_DISPLAY_MANAGER;
            }

            add("panel off session=" + session
                    + " direct=" + direct.success
                    + " backend=" + direct.backend
                    + " displays=" + direct.displayCount
                    + " directError=" + compact(direct.error)
                    + " shellRc=" + shellRc);

            if (!direct.success && shellRc != 0) {
                finishSession(session, false, false, "all power-off paths failed");
                return 45;
            }

            return 0;
        } catch (Throwable t) {
            add("screenOff exception session=" + session + ": " + stackMessage(t));
            finishSession(session, false, false, "screenOff exception");
            return 47;
        }
    }

    private void saveAndExtendScreenTimeout() throws Exception {
        String old = command("/system/bin/settings", "get", "system",
                "screen_off_timeout").trim();
        try {
            oldTimeout = Integer.parseInt(old);
        } catch (Throwable ignored) {
            oldTimeout = 60000;
            add("invalid old timeout='" + old + "'; fallback=" + oldTimeout);
        }

        int rc = commandCode("/system/bin/settings", "put", "system",
                "screen_off_timeout", "2147483647");
        if (rc != 0) throw new IllegalStateException("set timeout rc=" + rc);
    }

    private void watchPowerAndRestore(long session, CountDownLatch ready) {
        Process process = null;
        boolean wakeKey = false;
        boolean sleepConfirmed = false;
        try {
            add("watcher starting getevent session=" + session);
            process = new ProcessBuilder("/system/bin/getevent", "-ql")
                    .redirectErrorStream(true)
                    .start();
            if (!isCurrent(session)) {
                process.destroy();
                ready.countDown();
                return;
            }
            watcherProcess = process;
            ready.countDown();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while (isCurrent(session) && (line = reader.readLine()) != null) {
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
                sleepConfirmed = waitForSystemSleep();
                add("power transition session=" + session
                        + " sleepConfirmed=" + sleepConfirmed);
            }
        } catch (Throwable t) {
            ready.countDown();
            add("watcher exception session=" + session + ": " + stackMessage(t));
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
            if (watcherProcess == process) watcherProcess = null;
            if (isCurrent(session)) {
                finishSession(session, wakeKey, sleepConfirmed,
                        wakeKey ? "wake key" : "watcher ended");
            } else {
                add("old watcher exited session=" + session
                        + " current=" + currentSession);
            }
        }
    }

    private boolean waitForSystemSleep() {
        for (int i = 0; i < 30; i++) {
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
        boolean released = line.contains(" UP")
                || line.endsWith("UP")
                || line.contains(" 00000000");
        if (!released) return false;
        return line.contains("KEY_POWER")
                || line.contains("KEY_WAKEUP")
                || line.contains("KEY_SLEEP");
    }

    private boolean isCurrent(long session) {
        return currentSession == session;
    }

    private void finishSession(long session, boolean wakeKey,
            boolean sleepConfirmed, String reason) {
        synchronized (sessionLock) {
            if (!isCurrent(session)) return;
            stopRequested = true;
            Process process = watcherProcess;
            watcherProcess = null;
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
            restorePower(wakeKey, sleepConfirmed);
            running = false;
            activePowerPath = PATH_NONE;
            add("session finished session=" + session
                    + " reason=" + reason
                    + " wakeKey=" + wakeKey
                    + " sleepConfirmed=" + sleepConfirmed);
        }
    }

    private void cancelCurrentSessionLocked(String reason) {
        if (!running) return;
        long oldSession = currentSession;
        stopRequested = true;
        currentSession++;
        Process process = watcherProcess;
        watcherProcess = null;
        if (process != null) {
            try { process.destroy(); } catch (Throwable ignored) {}
        }
        restorePower(false, false);
        running = false;
        activePowerPath = PATH_NONE;
        add("session cancelled oldSession=" + oldSession + " reason=" + reason);
    }

    private void restorePower(boolean wakeKey, boolean sleepConfirmed) {
        int path = activePowerPath;
        if (wakeKey && sleepConfirmed) {
            if (path == PATH_DISPLAY_MANAGER) {
                int rc = commandCode("/system/bin/cmd", "display", "power-reset", "0");
                add("display-manager override reset after sleep rc=" + rc);
            } else {
                add("direct panel mode handed back to sleeping PowerManager");
            }
        } else {
            if (path == PATH_SURFACE_CONTROL) {
                PhysicalDisplayPower.Result result =
                        panelPower.setMode(PhysicalDisplayPower.MODE_NORMAL);
                add("panel emergency restore success=" + result.success
                        + " backend=" + result.backend
                        + " error=" + compact(result.error));
                if (!result.success) {
                    commandCode("/system/bin/cmd", "display", "power-reset", "0");
                    commandCode("/system/bin/cmd", "display", "power-on", "0");
                }
            } else if (path == PATH_DISPLAY_MANAGER) {
                int rc = commandCode("/system/bin/cmd", "display", "power-reset", "0");
                if (rc != 0) {
                    commandCode("/system/bin/cmd", "display", "power-on", "0");
                }
            }
        }

        commandCode("/system/bin/settings", "put", "system",
                "screen_off_timeout", Integer.toString(oldTimeout));
    }

    private String command(String... command) {
        StringBuilder output = new StringBuilder();
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            int rc = process.waitFor();
            if (rc != 0) {
                add("command rc=" + rc + " cmd=" + String.join(" ", command)
                        + " out=" + compact(output.toString()));
            }
        } catch (Throwable t) {
            add("command exception " + String.join(" ", command)
                    + ": " + stackMessage(t));
        }
        return output.toString();
    }

    private int commandCode(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }
            int rc = process.waitFor();
            if (rc != 0) {
                add("command rc=" + rc + " cmd=" + String.join(" ", command)
                        + " out=" + compact(output.toString()));
            }
            return rc;
        } catch (Throwable t) {
            add("command exception " + String.join(" ", command)
                    + ": " + stackMessage(t));
            return 127;
        }
    }

    private String compact(String value) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 360
                ? oneLine.substring(0, 360) + "…" : oneLine;
    }

    private String stackMessage(Throwable t) {
        return t.getClass().getName() + ": " + String.valueOf(t.getMessage());
    }

    private void add(String message) {
        synchronized (logLock) {
            String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                    .format(new Date());
            log.append(timestamp).append(' ').append(message).append('\n');
            if (log.length() > 30000) log.delete(0, log.length() - 24000);
            try (FileOutputStream stream = new FileOutputStream(
                    "/sdcard/Download/LuckyScreenHang-v14.log")) {
                stream.write(log.toString().getBytes(StandardCharsets.UTF_8));
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
