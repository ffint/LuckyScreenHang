// Lucky Screen Hang v1.3.2 - tiny JNI + shell implementation.
// No Android SDK headers are required to build this native library.
typedef unsigned char jboolean;
typedef int jint;
typedef long jlong;
typedef float jfloat;
typedef void *jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jthrowable;
typedef void *jmethodID;
typedef void *jfieldID;
typedef void **JNITable;
typedef JNITable *JNIEnv;

#define JNI_FN(env, idx, type) ((type)((*(env))[idx]))
typedef jclass (*FnFindClass)(JNIEnv, const char *);
typedef jthrowable (*FnExceptionOccurred)(JNIEnv);
typedef void (*FnExceptionClear)(JNIEnv);
typedef jobject (*FnNewGlobalRef)(JNIEnv, jobject);
typedef void (*FnDeleteGlobalRef)(JNIEnv, jobject);
typedef jobject (*FnNewObject)(JNIEnv, jclass, jmethodID, ...);
typedef jclass (*FnGetObjectClass)(JNIEnv, jobject);
typedef jmethodID (*FnGetMethodID)(JNIEnv, jclass, const char *, const char *);
typedef jmethodID (*FnGetStaticMethodID)(JNIEnv, jclass, const char *, const char *);
typedef jobject (*FnCallObjectMethod)(JNIEnv, jobject, jmethodID, ...);
typedef jboolean (*FnCallBooleanMethod)(JNIEnv, jobject, jmethodID, ...);
typedef void (*FnCallVoidMethod)(JNIEnv, jobject, jmethodID, ...);
typedef jobject (*FnCallStaticObjectMethod)(JNIEnv, jclass, jmethodID, ...);
typedef jboolean (*FnCallStaticBooleanMethod)(JNIEnv, jclass, jmethodID, ...);
typedef jfieldID (*FnGetFieldID)(JNIEnv, jclass, const char *, const char *);
typedef jobject (*FnGetObjectField)(JNIEnv, jobject, jfieldID);
typedef void (*FnSetIntField)(JNIEnv, jobject, jfieldID, jint);
typedef jstring (*FnNewStringUTF)(JNIEnv, const char *);
typedef const char *(*FnGetStringUTFChars)(JNIEnv, jstring, jboolean *);
typedef void (*FnReleaseStringUTFChars)(JNIEnv, jstring, const char *);

#define JFindClass(e,n) JNI_FN((e),6,FnFindClass)((e),(n))
#define JExceptionOccurred(e) JNI_FN((e),15,FnExceptionOccurred)((e))
#define JExceptionClear(e) JNI_FN((e),17,FnExceptionClear)((e))
#define JNewGlobalRef(e,o) JNI_FN((e),21,FnNewGlobalRef)((e),(o))
#define JDeleteGlobalRef(e,o) JNI_FN((e),22,FnDeleteGlobalRef)((e),(o))
#define JNewObject(e,c,m,...) JNI_FN((e),28,FnNewObject)((e),(c),(m),##__VA_ARGS__)
#define JGetObjectClass(e,o) JNI_FN((e),31,FnGetObjectClass)((e),(o))
#define JGetMethodID(e,c,n,s) JNI_FN((e),33,FnGetMethodID)((e),(c),(n),(s))
#define JCallObjectMethod(e,o,m,...) JNI_FN((e),34,FnCallObjectMethod)((e),(o),(m),##__VA_ARGS__)
#define JCallBooleanMethod(e,o,m,...) JNI_FN((e),37,FnCallBooleanMethod)((e),(o),(m),##__VA_ARGS__)
#define JCallVoidMethod(e,o,m,...) JNI_FN((e),61,FnCallVoidMethod)((e),(o),(m),##__VA_ARGS__)
#define JGetFieldID(e,c,n,s) JNI_FN((e),94,FnGetFieldID)((e),(c),(n),(s))
#define JGetObjectField(e,o,f) JNI_FN((e),95,FnGetObjectField)((e),(o),(f))
#define JSetIntField(e,o,f,v) JNI_FN((e),109,FnSetIntField)((e),(o),(f),(v))
#define JGetStaticMethodID(e,c,n,s) JNI_FN((e),113,FnGetStaticMethodID)((e),(c),(n),(s))
#define JCallStaticObjectMethod(e,c,m,...) JNI_FN((e),114,FnCallStaticObjectMethod)((e),(c),(m),##__VA_ARGS__)
#define JCallStaticBooleanMethod(e,c,m,...) JNI_FN((e),117,FnCallStaticBooleanMethod)((e),(c),(m),##__VA_ARGS__)
#define JNewStringUTF(e,s) JNI_FN((e),167,FnNewStringUTF)((e),(s))
#define JGetStringUTFChars(e,s,b) JNI_FN((e),169,FnGetStringUTFChars)((e),(s),(b))
#define JReleaseStringUTFChars(e,s,p) JNI_FN((e),170,FnReleaseStringUTFChars)((e),(s),(p))

typedef unsigned long size_t;
typedef unsigned long pthread_t;
extern int system(const char *command);
extern void _exit(int status) __attribute__((noreturn));
extern void *malloc(size_t size);
extern void free(void *ptr);
extern size_t strlen(const char *s);
extern char *strcpy(char *dst, const char *src);
extern int snprintf(char *str, size_t size, const char *fmt, ...);
extern int pthread_create(pthread_t *thread, const void *attr, void *(*start)(void *), void *arg);
extern int pthread_detach(pthread_t thread);

static char *g_shizuku_apk;
static char *g_files_dir;
static volatile int g_overlay_created;
static volatile int g_requested_overlay;
static volatile int g_running;
static volatile int g_priv_ready;
static int g_priv_mode; // 1=Shizuku, 2=Root
static jobject g_overlay_wm;
static jobject g_overlay_button;

static void clear_exception(JNIEnv env) {
    if (JExceptionOccurred(env)) JExceptionClear(env);
}

static char *dup_utf(JNIEnv env, jstring s) {
    if (!s) return 0;
    const char *p = JGetStringUTFChars(env, s, 0);
    if (!p) return 0;
    size_t n = strlen(p);
    char *out = (char *)malloc(n + 1);
    if (out) strcpy(out, p);
    JReleaseStringUTFChars(env, s, p);
    return out;
}

static char *get_files_dir(JNIEnv env, jobject activity) {
    jclass ac = JGetObjectClass(env, activity);
    jmethodID mid = JGetMethodID(env, ac, "getFilesDir", "()Ljava/io/File;");
    jobject file = JCallObjectMethod(env, activity, mid);
    if (!file || JExceptionOccurred(env)) { clear_exception(env); return 0; }
    jclass fc = JGetObjectClass(env, file);
    jmethodID abs = JGetMethodID(env, fc, "getAbsolutePath", "()Ljava/lang/String;");
    jstring path = (jstring)JCallObjectMethod(env, file, abs);
    if (JExceptionOccurred(env)) { clear_exception(env); return 0; }
    return dup_utf(env, path);
}

static char *get_shizuku_source(JNIEnv env, jobject activity) {
    jclass ac = JGetObjectClass(env, activity);
    jmethodID gpm = JGetMethodID(env, ac, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    jobject pm = JCallObjectMethod(env, activity, gpm);
    if (!pm || JExceptionOccurred(env)) { clear_exception(env); return 0; }
    jclass pc = JGetObjectClass(env, pm);
    jmethodID gai = JGetMethodID(env, pc, "getApplicationInfo", "(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;");
    jstring pkg = JNewStringUTF(env, "moe.shizuku.privileged.api");
    jobject ai = JCallObjectMethod(env, pm, gai, pkg, 0);
    if (JExceptionOccurred(env)) { clear_exception(env); return 0; }
    if (!ai) return 0;
    jclass aic = JGetObjectClass(env, ai);
    jfieldID source = JGetFieldID(env, aic, "sourceDir", "Ljava/lang/String;");
    jstring path = (jstring)JGetObjectField(env, ai, source);
    if (JExceptionOccurred(env)) { clear_exception(env); return 0; }
    return dup_utf(env, path);
}

static void show_toast(JNIEnv env, jobject activity, const char *msg) {
    jclass tc = JFindClass(env, "android/widget/Toast");
    if (!tc) { clear_exception(env); return; }
    jmethodID make = JGetStaticMethodID(env, tc, "makeText", "(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;");
    jstring text = JNewStringUTF(env, msg);
    jobject toast = JCallStaticObjectMethod(env, tc, make, activity, text, 0);
    if (toast) {
        jclass oc = JGetObjectClass(env, toast);
        jmethodID show = JGetMethodID(env, oc, "show", "()V");
        JCallVoidMethod(env, toast, show);
    }
    clear_exception(env);
}

static int can_draw_overlays(JNIEnv env, jobject activity) {
    jclass sc = JFindClass(env, "android/provider/Settings");
    if (!sc) { clear_exception(env); return 0; }
    jmethodID can = JGetStaticMethodID(env, sc, "canDrawOverlays", "(Landroid/content/Context;)Z");
    jboolean ok = JCallStaticBooleanMethod(env, sc, can, activity);
    if (JExceptionOccurred(env)) { clear_exception(env); return 0; }
    return ok != 0;
}

static void request_overlay_permission(JNIEnv env, jobject activity) {
    jclass ac = JGetObjectClass(env, activity);
    jmethodID gpn = JGetMethodID(env, ac, "getPackageName", "()Ljava/lang/String;");
    jstring jp = (jstring)JCallObjectMethod(env, activity, gpn);
    char *pkg = dup_utf(env, jp);
    if (!pkg) return;
    size_t n = strlen(pkg) + 16;
    char *uri_text = (char *)malloc(n);
    if (!uri_text) { free(pkg); return; }
    snprintf(uri_text, n, "package:%s", pkg);

    jclass uc = JFindClass(env, "android/net/Uri");
    jmethodID parse = JGetStaticMethodID(env, uc, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    jobject uri = JCallStaticObjectMethod(env, uc, parse, JNewStringUTF(env, uri_text));
    jclass ic = JFindClass(env, "android/content/Intent");
    jmethodID ctor = JGetMethodID(env, ic, "<init>", "(Ljava/lang/String;Landroid/net/Uri;)V");
    jobject intent = JNewObject(env, ic, ctor,
        JNewStringUTF(env, "android.settings.action.MANAGE_OVERLAY_PERMISSION"), uri);
    jmethodID start = JGetMethodID(env, ac, "startActivity", "(Landroid/content/Intent;)V");
    show_toast(env, activity, "请允许 Lucky 显示悬浮窗");
    JCallVoidMethod(env, activity, start, intent);
    clear_exception(env);
    free(uri_text); free(pkg);
}

static int create_overlay(JNIEnv env, jobject activity) {
    jclass ac = JGetObjectClass(env, activity);
    jmethodID gac = JGetMethodID(env, ac, "getApplicationContext", "()Landroid/content/Context;");
    jobject ctx = JCallObjectMethod(env, activity, gac);
    if (!ctx) ctx = activity;
    jclass cc = JGetObjectClass(env, ctx);
    jmethodID gss = JGetMethodID(env, cc, "getSystemService", "(Ljava/lang/String;)Ljava/lang/Object;");
    jobject wm = JCallObjectMethod(env, ctx, gss, JNewStringUTF(env, "window"));
    if (!wm || JExceptionOccurred(env)) { clear_exception(env); return 0; }

    jclass bc = JFindClass(env, "android/widget/Button");
    jmethodID bctor = JGetMethodID(env, bc, "<init>", "(Landroid/content/Context;)V");
    jobject button = JNewObject(env, bc, bctor, activity);
    if (!button) { clear_exception(env); return 0; }
    jmethodID setText = JGetMethodID(env, bc, "setText", "(Ljava/lang/CharSequence;)V");
    jmethodID setTextSize = JGetMethodID(env, bc, "setTextSize", "(F)V");
    jmethodID setTextColor = JGetMethodID(env, bc, "setTextColor", "(I)V");
    jmethodID setAllCaps = JGetMethodID(env, bc, "setAllCaps", "(Z)V");
    jmethodID setPadding = JGetMethodID(env, bc, "setPadding", "(IIII)V");
    jmethodID setListener = JGetMethodID(env, bc, "setOnClickListener", "(Landroid/view/View$OnClickListener;)V");
    JCallVoidMethod(env, button, setText, JNewStringUTF(env, "息屏"));
    JCallVoidMethod(env, button, setTextSize, (jfloat)16.0f);
    JCallVoidMethod(env, button, setTextColor, (jint)0xffffffff);
    JCallVoidMethod(env, button, setAllCaps, (jboolean)0);
    JCallVoidMethod(env, button, setPadding, 0,0,0,0);
    JCallVoidMethod(env, button, setListener, activity);

    // Round blue background without any drawable resources.
    jclass gd = JFindClass(env, "android/graphics/drawable/GradientDrawable");
    jmethodID gdctor = JGetMethodID(env, gd, "<init>", "()V");
    jobject bg = JNewObject(env, gd, gdctor);
    if (bg) {
        jmethodID setColor = JGetMethodID(env, gd, "setColor", "(I)V");
        jmethodID setRadius = JGetMethodID(env, gd, "setCornerRadius", "(F)V");
        JCallVoidMethod(env, bg, setColor, (jint)0xff2563eb);
        JCallVoidMethod(env, bg, setRadius, (jfloat)999.0f);
        jmethodID setBg = JGetMethodID(env, bc, "setBackground", "(Landroid/graphics/drawable/Drawable;)V");
        JCallVoidMethod(env, button, setBg, bg);
    }
    jmethodID elevation = JGetMethodID(env, bc, "setElevation", "(F)V");
    JCallVoidMethod(env, button, elevation, (jfloat)16.0f);

    jclass lpc = JFindClass(env, "android/view/WindowManager$LayoutParams");
    jmethodID lpctor = JGetMethodID(env, lpc, "<init>", "(IIIII)V");
    // 180px ~ 50-60dp on common high-density phones. The window only intercepts this small circle.
    jobject lp = JNewObject(env, lpc, lpctor, 180,180,2038,0x28,-3);
    if (!lp) { clear_exception(env); return 0; }
    jfieldID gravity = JGetFieldID(env, lpc, "gravity", "I");
    jfieldID x = JGetFieldID(env, lpc, "x", "I");
    JSetIntField(env, lp, gravity, (jint)0x00800015); // END | CENTER_VERTICAL
    JSetIntField(env, lp, x, (jint)28);

    jclass wmc = JGetObjectClass(env, wm);
    jmethodID add = JGetMethodID(env, wmc, "addView", "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;)V");
    JCallVoidMethod(env, wm, add, button, lp);
    if (JExceptionOccurred(env)) {
        JExceptionClear(env);
        show_toast(env, activity, "悬浮窗创建失败，请检查悬浮窗权限");
        return 0;
    }

    // Keep only two global references so the click callback can synchronously remove
    // the overlay before any shell work starts. This fixes the brief stale overlay
    // visible when the user wakes the screen immediately after tapping the button.
    g_overlay_wm = JNewGlobalRef(env, wm);
    g_overlay_button = JNewGlobalRef(env, button);

    // Do not inspect/focus recents at all. The overlay belongs to the application WindowManager,
    // so the launcher Activity can be removed immediately and Android naturally reveals whatever was underneath.
    jmethodID finish = JGetMethodID(env, ac, "finishAndRemoveTask", "()V");
    JCallVoidMethod(env, activity, finish);
    clear_exception(env);
    return 1;
}

static void remove_overlay_now(JNIEnv env) {
    if (!g_overlay_wm || !g_overlay_button) return;
    jclass wmc = JGetObjectClass(env, g_overlay_wm);
    if (wmc) {
        jmethodID rm = JGetMethodID(env, wmc, "removeViewImmediate", "(Landroid/view/View;)V");
        if (rm) JCallVoidMethod(env, g_overlay_wm, rm, g_overlay_button);
    }
    clear_exception(env);
    JDeleteGlobalRef(env, g_overlay_button);
    JDeleteGlobalRef(env, g_overlay_wm);
    g_overlay_button = 0;
    g_overlay_wm = 0;
    g_overlay_created = 0;
}

static const char kProbeScript[] = "id >/dev/null 2>&1; exit $?";

static const char kPrivScript[] =
    "BASE=/data/local/tmp/lucky_screen_hang_v13; "
    "cleanup() { "
      "P=$(cat ${BASE}.pid 2>/dev/null); case x$P in x|xnull) ;; *) kill $P >/dev/null 2>&1 ;; esac; "
      "OLD=$(cat ${BASE}.timeout 2>/dev/null); case x$OLD in x|xnull) OLD=60000 ;; esac; "
      "cmd display power-reset 0 >/dev/null 2>&1 || cmd display power-on 0 >/dev/null 2>&1; "
      "settings put system screen_off_timeout $OLD >/dev/null 2>&1; "
      "rm -f ${BASE}.timeout ${BASE}.pid; "
    "}; "
    "if [ -f ${BASE}.timeout ]; then cleanup; sleep 0.2; fi; "
    "cmd display help 2>&1 | grep -q power-off || exit 41; "
    "OLD=$(settings get system screen_off_timeout 2>/dev/null); case x$OLD in x|xnull) OLD=60000 ;; esac; "
    "echo $OLD > ${BASE}.timeout || exit 42; "
    "settings put system screen_off_timeout 2147483647 >/dev/null 2>&1 || { rm -f ${BASE}.timeout; exit 43; }; "
    "nohup sh -c \""
      "OLD=$OLD; BASE=$BASE; "
      "restore() { cmd display power-reset 0 >/dev/null 2>&1 || cmd display power-on 0 >/dev/null 2>&1; settings put system screen_off_timeout $OLD >/dev/null 2>&1; rm -f ${BASE}.timeout ${BASE}.pid; }; "
      "trap \\\"restore\\\" EXIT INT TERM; "
      "getevent -ql 2>/dev/null | grep -m 1 \\\"KEY_POWER.*UP\\\" >/dev/null 2>&1; "
      "i=0; while [ \\\"$i\\\" -lt 20 ]; do dumpsys power 2>/dev/null | grep -qE \\\"mWakefulness=(Asleep|Dozing)|mInteractive=false\\\" && break; sleep 0.1; i=$((i+1)); done; "
      "restore; trap - EXIT INT TERM; "
    "\" </dev/null >/dev/null 2>&1 & "
    "WPID=$!; echo $WPID > ${BASE}.pid; "
    "sleep 1; kill -0 $WPID >/dev/null 2>&1 || { cleanup; exit 44; }; "
    "cmd display power-off 0 >/dev/null 2>&1 || { kill $WPID >/dev/null 2>&1; cleanup; exit 45; }; "
    "exit 0";

static int exit_code(int r) { if (r < 0) return 255; return (r >> 8) & 255; }

static int run_shizuku_script(const char *script) {
    if (!g_shizuku_apk || !g_files_dir) return 250;
    size_t n = strlen(g_shizuku_apk) + strlen(g_files_dir) + strlen(script) + 1000;
    char *cmd = (char *)malloc(n); if (!cmd) return 251;
    snprintf(cmd, n,
        "DEX=\"%s/rish_shizuku.dex\"; rm -f \"$DEX\"; "
        "if [ -x /system/bin/unzip ]; then /system/bin/unzip -p \"%s\" assets/rish_shizuku.dex > \"$DEX\"; "
        "else /system/bin/toybox unzip -p \"%s\" assets/rish_shizuku.dex > \"$DEX\"; fi; "
        "test -s \"$DEX\" || exit 90; chmod 400 \"$DEX\"; "
        "RISH_APPLICATION_ID=com.lucky.screenhang.v13 /system/bin/app_process -Djava.class.path=\"$DEX\" /system/bin --nice-name=lucky-rish rikka.shizuku.shell.ShizukuShellLoader -c '%s'",
        g_files_dir,g_shizuku_apk,g_shizuku_apk,script);
    int r=system(cmd); free(cmd); return exit_code(r);
}

static int run_root_script(const char *script) {
    size_t n=strlen(script)+64; char *cmd=(char *)malloc(n); if(!cmd) return 251;
    snprintf(cmd,n,"su -c '%s'",script); int r=system(cmd); free(cmd); return exit_code(r);
}

static int ensure_privileged_access(void) {
    if (g_priv_ready) return 1;
    int code = 250;
    if (g_shizuku_apk && g_files_dir) {
        code = run_shizuku_script(kProbeScript);
        if (code == 0) { g_priv_ready = 1; g_priv_mode = 1; return 1; }
    }
    code = run_root_script(kProbeScript);
    if (code == 0) { g_priv_ready = 1; g_priv_mode = 2; return 1; }
    return 0;
}

static void *worker(void *unused) {
    (void)unused;
    int code=250;
    if (g_priv_mode == 1) code=run_shizuku_script(kPrivScript);
    else if (g_priv_mode == 2) code=run_root_script(kPrivScript);
    else {
        if (g_shizuku_apk && g_files_dir) code=run_shizuku_script(kPrivScript);
        if (code!=0) code=run_root_script(kPrivScript);
    }
    // The overlay was removed synchronously on tap. Exit on both success and failure
    // so no invisible app process is left around; a failure can be retried by reopening.
    _exit(code==0 ? 0 : code);
}

__attribute__((visibility("default"),used))
void Java_com_lucky_screenhang_v13_MainActivity_nativeOnCreate(JNIEnv env, jclass cls, jobject activity) {
    (void)cls;
    if (!g_files_dir) g_files_dir=get_files_dir(env,activity);
    if (!g_shizuku_apk) g_shizuku_apk=get_shizuku_source(env,activity);
}

__attribute__((visibility("default"),used))
void Java_com_lucky_screenhang_v13_MainActivity_nativeOnResume(JNIEnv env, jclass cls, jobject activity) {
    (void)cls;
    if (g_overlay_created) return;
    if (!can_draw_overlays(env,activity)) {
        if (!g_requested_overlay) { g_requested_overlay=1; request_overlay_permission(env,activity); }
        else show_toast(env,activity,"需要开启悬浮窗权限后重新打开 Lucky");
        return;
    }

    // Permission preflight happens before the overlay is exposed. On first use this
    // is where Shizuku (preferred) or Magisk su can present its authorization UI.
    show_toast(env,activity,"正在准备 Shizuku / Root 权限…");
    if (!ensure_privileged_access()) {
        show_toast(env,activity,"未获得 Shizuku 或 Root 权限，请授权后重新打开 Lucky");
        return;
    }

    if (create_overlay(env,activity)) {
        g_overlay_created=1;
        show_toast(env,activity,g_priv_mode==1 ? "悬浮按钮已就绪 · Shizuku" : "悬浮按钮已就绪 · Root");
    }
}

__attribute__((visibility("default"),used))
void Java_com_lucky_screenhang_v13_MainActivity_nativeOnClick(JNIEnv env, jclass cls, jobject activity) {
    (void)cls;
    if (g_running) return;
    g_running=1;
    // Remove the overlay immediately on the UI thread. Do not wait for the shell
    // watcher, power-off command, or process exit.
    remove_overlay_now(env);
    show_toast(env,activity,"正在硬件息屏… 第一次 Power 正常灭屏，第二次 Power 正常唤醒");
    pthread_t t;
    if (pthread_create(&t,0,worker,0)==0) pthread_detach(t);
    else { g_running=0; show_toast(env,activity,"无法启动息屏任务"); }
}
