// Lucky Screen Hang v1.3.3 debug wrapper.
// The proven v1.3.2 core remains untouched; normal mode delegates to it verbatim.
#define Java_com_lucky_screenhang_v13_MainActivity_nativeOnCreate lsh_core_nativeOnCreate
#define Java_com_lucky_screenhang_v13_MainActivity_nativeOnResume lsh_core_nativeOnResume
#define Java_com_lucky_screenhang_v13_MainActivity_nativeOnClick  lsh_core_nativeOnClick
#define worker lsh_core_worker
#include "luckyhang.c"
#undef worker
#undef Java_com_lucky_screenhang_v13_MainActivity_nativeOnClick
#undef Java_com_lucky_screenhang_v13_MainActivity_nativeOnResume
#undef Java_com_lucky_screenhang_v13_MainActivity_nativeOnCreate

typedef void **JavaVMTable;
typedef JavaVMTable *JavaVM;
typedef jint (*FnGetJavaVM)(JNIEnv, JavaVM *);
typedef jint (*FnAttachCurrentThread)(JavaVM, void **, void *);
typedef jint (*FnDetachCurrentThread)(JavaVM);
#define JGetJavaVM2(e,v) JNI_FN((e),219,FnGetJavaVM)((e),(v))
#define JVM_FN2(vm,idx,type) ((type)((*(vm))[idx]))
#define JVMAttach2(vm,penv) JVM_FN2((vm),4,FnAttachCurrentThread)((vm),(void **)(penv),0)
#define JVMDetach2(vm) JVM_FN2((vm),5,FnDetachCurrentThread)((vm))

extern int open(const char *path, int flags, ...);
extern long write(int fd, const void *buf, unsigned long count);
extern int close(int fd);

static JavaVM g_debug_vm;
static jobject g_debug_ctx;
static volatile int g_debug_enabled;
static unsigned int g_debug_seq;
static unsigned long g_debug_len;
#define DEBUG_CAP 12288
static char g_debug_buf[DEBUG_CAP];

static const char *debug_reason(int code) {
    switch (code) {
        case 0: return "success";
        case 41: return "cmd display does not expose power-off";
        case 42: return "failed to save original screen_off_timeout";
        case 43: return "failed to extend screen_off_timeout";
        case 44: return "Power-key watcher exited immediately";
        case 45: return "cmd display power-off failed";
        case 90: return "failed to extract Shizuku rish dex";
        case 250: return "no usable Shizuku context / privilege mode";
        case 251: return "native allocation failed";
        case 255: return "system() failed before child exit status";
        default: return "unmapped exit code";
    }
}

static char *debug_path(void) {
    if (!g_files_dir) return 0;
    size_t n=strlen(g_files_dir)+32;
    char *p=(char *)malloc(n);
    if (p) snprintf(p,n,"%s/debug-last.txt",g_files_dir);
    return p;
}

static void debug_write_all(void) {
    if (!g_debug_enabled) return;
    char *p=debug_path();
    if (!p) return;
    int fd=open(p,1|64|512,0600); /* WRONLY|CREAT|TRUNC */
    if (fd>=0) { if (g_debug_len) write(fd,g_debug_buf,g_debug_len); close(fd); }
    free(p);
}

static void debug_line(const char *msg) {
    if (!g_debug_enabled || !msg) return;
    char line[640];
    int w=snprintf(line,sizeof(line),"[%03u] %s\n",++g_debug_seq,msg);
    if (w<=0) return;
    unsigned long n=(unsigned long)w;
    if (n>=sizeof(line)) n=sizeof(line)-1;
    if (g_debug_len+n>=DEBUG_CAP) return;
    for (unsigned long i=0;i<n;i++) g_debug_buf[g_debug_len+i]=line[i];
    g_debug_len+=n; g_debug_buf[g_debug_len]=0;
    debug_write_all();
}

static void debug_code(const char *label,int code) {
    char tmp[256];
    snprintf(tmp,sizeof(tmp),"%s code=%d (%s)",label,code,debug_reason(code));
    debug_line(tmp);
}

static void debug_set_button_text(JNIEnv env,const char *text) {
    if (!g_overlay_button) return;
    jclass bc=JGetObjectClass(env,g_overlay_button);
    if (!bc) { clear_exception(env); return; }
    jmethodID st=JGetMethodID(env,bc,"setText","(Ljava/lang/CharSequence;)V");
    if (st) JCallVoidMethod(env,g_overlay_button,st,JNewStringUTF(env,text));
    clear_exception(env);
}

static void debug_copy_clipboard(JNIEnv env,int code) {
    if (!env || !g_debug_ctx || !g_debug_enabled) return;
    char tmp[320];
    snprintf(tmp,sizeof(tmp),"FINAL code=%d (%s), mode=%s",code,debug_reason(code),g_priv_mode==1?"Shizuku":(g_priv_mode==2?"Root":"unknown"));
    debug_line(tmp);
    jclass cc=JGetObjectClass(env,g_debug_ctx);
    jmethodID gss=cc?JGetMethodID(env,cc,"getSystemService","(Ljava/lang/String;)Ljava/lang/Object;"):0;
    jobject cm=gss?JCallObjectMethod(env,g_debug_ctx,gss,JNewStringUTF(env,"clipboard")):0;
    if (!cm || JExceptionOccurred(env)) { clear_exception(env); debug_line("clipboard: getSystemService failed"); return; }
    jclass clipc=JFindClass(env,"android/content/ClipData");
    jmethodID np=clipc?JGetStaticMethodID(env,clipc,"newPlainText","(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Landroid/content/ClipData;"):0;
    jobject clip=np?JCallStaticObjectMethod(env,clipc,np,JNewStringUTF(env,"LuckyScreenHang debug"),JNewStringUTF(env,g_debug_buf)):0;
    if (!clip || JExceptionOccurred(env)) { clear_exception(env); debug_line("clipboard: ClipData creation failed"); return; }
    jclass cmc=JGetObjectClass(env,cm);
    jmethodID set=cmc?JGetMethodID(env,cmc,"setPrimaryClip","(Landroid/content/ClipData;)V"):0;
    if (set) JCallVoidMethod(env,cm,set,clip);
    if (JExceptionOccurred(env)) { clear_exception(env); debug_line("clipboard: setPrimaryClip failed"); }
}

static void debug_finish_activity(JNIEnv env,jobject activity) {
    jclass ac=JGetObjectClass(env,activity);
    jmethodID finish=ac?JGetMethodID(env,ac,"finishAndRemoveTask","()V"):0;
    if (finish) JCallVoidMethod(env,activity,finish);
    clear_exception(env);
}

static void *debug_worker(void *unused) {
    (void)unused;
    debug_line("worker: entered");
    int code=250;
    if (g_priv_mode==1) {
        debug_line("worker: using Shizuku");
        code=run_shizuku_script(kPrivScript);
    } else if (g_priv_mode==2) {
        debug_line("worker: using Root");
        code=run_root_script(kPrivScript);
    } else {
        debug_line("worker: privilege mode unknown; trying Shizuku then Root");
        if (g_shizuku_apk && g_files_dir) code=run_shizuku_script(kPrivScript);
        if (code!=0) code=run_root_script(kPrivScript);
    }
    debug_code("worker: privileged script finished",code);
    if (g_debug_vm) {
        JNIEnv tenv=0;
        int ar=JVMAttach2(g_debug_vm,&tenv);
        if (ar==0 && tenv) {
            debug_copy_clipboard(tenv,code);
            JVMDetach2(g_debug_vm);
        } else {
            debug_code("worker: AttachCurrentThread failed",ar);
        }
    } else {
        debug_line("worker: JavaVM unavailable; clipboard skipped");
    }
    debug_write_all();
    _exit(code==0?0:code);
}

__attribute__((visibility("default"),used))
void Java_com_lucky_screenhang_v13_MainActivity_nativeOnCreate(JNIEnv env,jclass cls,jobject activity) {
    lsh_core_nativeOnCreate(env,cls,activity);
    if (!g_debug_vm) JGetJavaVM2(env,&g_debug_vm);
    if (!g_debug_ctx) {
        jclass ac=JGetObjectClass(env,activity);
        jmethodID gac=ac?JGetMethodID(env,ac,"getApplicationContext","()Landroid/content/Context;"):0;
        jobject ctx=gac?JCallObjectMethod(env,activity,gac):0;
        if (ctx) g_debug_ctx=JNewGlobalRef(env,ctx);
        clear_exception(env);
    }
    debug_line("activity: onCreate");
}

__attribute__((visibility("default"),used))
void Java_com_lucky_screenhang_v13_MainActivity_nativeOnResume(JNIEnv env,jclass cls,jobject activity) {
    if (g_overlay_created) {
        if (!g_debug_enabled) {
            g_debug_enabled=1; g_debug_seq=0; g_debug_len=0; g_debug_buf[0]=0;
            debug_line("DEBUG enabled by relaunch while overlay is active");
            debug_line(g_priv_mode==1?"privilege: Shizuku":(g_priv_mode==2?"privilege: Root":"privilege: unknown"));
            debug_set_button_text(env,"息屏 DBG");
            show_toast(env,activity,"Debug 已开启 · 点击息屏后日志会复制到剪切板");
        } else {
            debug_line("DEBUG disabled by relaunch");
            g_debug_enabled=0;
            debug_set_button_text(env,"息屏");
            show_toast(env,activity,"Debug 已关闭");
        }
        debug_finish_activity(env,activity);
        return;
    }
    lsh_core_nativeOnResume(env,cls,activity);
}

__attribute__((visibility("default"),used))
void Java_com_lucky_screenhang_v13_MainActivity_nativeOnClick(JNIEnv env,jclass cls,jobject activity) {
    if (!g_debug_enabled) {
        lsh_core_nativeOnClick(env,cls,activity);
        return;
    }
    debug_line("click: received");
    if (g_running) { debug_line("click: ignored because worker already running"); return; }
    g_running=1;
    remove_overlay_now(env);
    debug_line("click: overlay removed synchronously");
    show_toast(env,activity,"Debug 息屏中… 完成后日志自动复制到剪切板");
    pthread_t t;
    int pr=pthread_create(&t,0,debug_worker,0);
    if (pr==0) {
        debug_line("click: worker thread created");
        pthread_detach(t);
    } else {
        debug_code("click: pthread_create failed",pr);
        g_running=0;
        debug_copy_clipboard(env,251);
        show_toast(env,activity,"无法启动息屏任务，Debug 日志已尝试复制");
    }
}
