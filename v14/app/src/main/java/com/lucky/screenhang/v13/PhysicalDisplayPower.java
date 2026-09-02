package com.lucky.screenhang.v13;

import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Controls the physical panel directly through SurfaceFlinger without changing
 * Android's logical wakefulness state.
 */
final class PhysicalDisplayPower {
    static final int MODE_OFF = 0;
    static final int MODE_NORMAL = 2;

    static final class Result {
        final boolean success;
        final String backend;
        final int displayCount;
        final String error;

        Result(boolean success, String backend, int displayCount, String error) {
            this.success = success;
            this.backend = backend;
            this.displayCount = displayCount;
            this.error = error;
        }
    }

    private Method setDisplayPowerMode;
    private List<IBinder> displayTokens;
    private String tokenBackend;

    synchronized Result setMode(int mode) {
        Throwable firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureInitialized();
                for (IBinder token : displayTokens) {
                    setDisplayPowerMode.invoke(null, token, mode);
                }
                return new Result(true, tokenBackend, displayTokens.size(), "");
            } catch (Throwable t) {
                if (firstFailure == null) firstFailure = unwrap(t);
                clearCachedState();
            }
        }
        Throwable error = firstFailure == null
                ? new IllegalStateException("unknown panel control failure") : firstFailure;
        return new Result(false, "none", 0,
                error.getClass().getName() + ": " + String.valueOf(error.getMessage()));
    }

    private void ensureInitialized() throws Exception {
        if (setDisplayPowerMode != null && displayTokens != null && !displayTokens.isEmpty()) {
            return;
        }

        Class<?> surfaceControl = Class.forName("android.view.SurfaceControl");
        Method setMode = findMethod(surfaceControl, "setDisplayPowerMode",
                IBinder.class, int.class);
        setMode.setAccessible(true);

        List<IBinder> tokens = trySurfaceControlPhysicalTokens(surfaceControl);
        String backend = "SurfaceControl.physical";

        if (tokens.isEmpty()) {
            tokens = tryDisplayControlTokens();
            backend = "DisplayControl.physical";
        }

        if (tokens.isEmpty()) {
            tokens = tryInternalDisplayToken(surfaceControl);
            backend = "SurfaceControl.internal";
        }

        if (tokens.isEmpty()) {
            throw new IllegalStateException("no physical display token found");
        }

        setDisplayPowerMode = setMode;
        displayTokens = tokens;
        tokenBackend = backend;
    }

    private List<IBinder> trySurfaceControlPhysicalTokens(Class<?> surfaceControl) {
        try {
            Method getIds = findMethod(surfaceControl, "getPhysicalDisplayIds");
            Method getToken = findMethod(surfaceControl, "getPhysicalDisplayToken", long.class);
            getIds.setAccessible(true);
            getToken.setAccessible(true);
            return invokeTokenMethods(getIds, getToken);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private List<IBinder> tryDisplayControlTokens() {
        try {
            String systemServerClasspath = System.getenv("SYSTEMSERVERCLASSPATH");
            if (systemServerClasspath == null || systemServerClasspath.isEmpty()) {
                return Collections.emptyList();
            }

            Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method create = findMethod(factory, "createClassLoader",
                    String.class, String.class, String.class, ClassLoader.class,
                    int.class, boolean.class, String.class);
            create.setAccessible(true);
            ClassLoader loader = (ClassLoader) create.invoke(null,
                    systemServerClasspath, null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null);

            Class<?> displayControl = loader.loadClass(
                    "com.android.server.display.DisplayControl");
            loadAndroidServers(displayControl);

            Method getIds = findMethod(displayControl, "getPhysicalDisplayIds");
            Method getToken = findMethod(displayControl,
                    "getPhysicalDisplayToken", long.class);
            getIds.setAccessible(true);
            getToken.setAccessible(true);
            return invokeTokenMethods(getIds, getToken);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private void loadAndroidServers(Class<?> owner) throws Exception {
        Method load = Runtime.class.getDeclaredMethod(
                "loadLibrary0", Class.class, String.class);
        load.setAccessible(true);
        try {
            load.invoke(Runtime.getRuntime(), owner, "android_servers");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            String message = cause == null ? "" : String.valueOf(cause.getMessage());
            if (!message.contains("already loaded")) throw e;
        }
    }

    private List<IBinder> tryInternalDisplayToken(Class<?> surfaceControl) {
        String[] names = {"getInternalDisplayToken", "getBuiltInDisplay"};
        for (String name : names) {
            try {
                Method method;
                Object value;
                if ("getBuiltInDisplay".equals(name)) {
                    method = findMethod(surfaceControl, name, int.class);
                    method.setAccessible(true);
                    value = method.invoke(null, 0);
                } else {
                    method = findMethod(surfaceControl, name);
                    method.setAccessible(true);
                    value = method.invoke(null);
                }
                if (value instanceof IBinder) {
                    return Collections.singletonList((IBinder) value);
                }
            } catch (Throwable ignored) {
                // Try the next legacy entry point.
            }
        }
        return Collections.emptyList();
    }

    private List<IBinder> invokeTokenMethods(Method getIds, Method getToken)
            throws Exception {
        Object idsObject = getIds.invoke(null);
        if (!(idsObject instanceof long[])) return Collections.emptyList();

        List<IBinder> tokens = new ArrayList<>();
        for (long id : (long[]) idsObject) {
            Object token = getToken.invoke(null, id);
            if (token instanceof IBinder) tokens.add((IBinder) token);
        }
        return tokens;
    }

    private Method findMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException ignored) {
            return type.getDeclaredMethod(name, parameters);
        }
    }

    private Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException
                && ((InvocationTargetException) t).getTargetException() != null) {
            return ((InvocationTargetException) t).getTargetException();
        }
        return t;
    }

    private void clearCachedState() {
        setDisplayPowerMode = null;
        displayTokens = null;
        tokenBackend = null;
    }
}
