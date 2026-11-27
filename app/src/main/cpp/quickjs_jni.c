/*
 * QuickJS 的 JNI 桥（M1：ES Module + 原生桥 + 入口调用）。
 *
 * ★★ 设计要点（来自对 TVBox(takagen99/Box) APK 的反查）：
 *   · 它的 JS 爬虫是 **ES module**，`import` 分为两类：
 *       - `assets://js/lib/xxx.js`  → APK 内置库（cheerio / crypto-js / pako …）
 *       - `./xxx.js`               → 与爬虫同目录的兄弟文件
 *   · 原生侧**只需要一个网络原语 `_http(url, optionsJson)`**（TVBox 的 `net.js` 就是这么包的）。
 *     不需要 Node 的 http.Server，也不需要 catServerFactory。
 *
 * 这个文件提供 5 个原生函数给 JS：
 *   `_http(url, optsJson)`            同步 HTTP（Java 侧 OkHttp），返回 JSON 对象
 *   `_pdfh(html, rule, base)`         单条选择器取值（Java 侧 jsoup）
 *   `_pdfa(html, rule, base)`         多条选择器取值，返回数组
 *   `__log(msg)`                      日志
 *
 * 以及给 Kotlin 的入口：
 *   nativeInit() / nativeEval() / nativeEvalModule(name) / nativeCall(fn, argsJson) / nativeGetGlobal(key)
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <stdbool.h>
#include <android/log.h>

#include "quickjs.h"

#define TAG "QuickJs"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* 单次 JS 执行的最长时间（毫秒）——防止死循环把线程挂死 */
#define EVAL_TIMEOUT_MS 60000

static JavaVM *g_vm = NULL;
static jclass g_cls = NULL;              /* com.reelix.app.data.source.js.QuickJs */

static jmethodID g_mid_module_source = NULL;
static jmethodID g_mid_http_sync = NULL;
static jmethodID g_mid_pdfh = NULL;
static jmethodID g_mid_pdfa = NULL;
static jmethodID g_mid_log = NULL;

static JSRuntime *g_rt = NULL;
static JSContext *g_ctx = NULL;
static int64_t g_deadline_ms = 0;

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ---------------- JNI 小工具 ---------------- */

typedef struct {
    JNIEnv *env;
    bool attached;
} EnvScope;

static EnvScope env_acquire(void) {
    EnvScope s = { NULL, false };

    if (g_vm == NULL) {
        return s;
    }

    jint r = (*g_vm)->GetEnv(g_vm, (void **) &s.env, JNI_VERSION_1_6);

    if (r == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &s.env, NULL) == JNI_OK) {
            s.attached = true;
        } else {
            s.env = NULL;
        }
    }

    return s;
}

static void env_release(EnvScope *s) {
    if (s->attached && g_vm != NULL) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

/* 调 Java 静态方法 (String) -> String */
static char *jni_call_string(jmethodID mid, const char *a) {
    EnvScope s = env_acquire();
    if (s.env == NULL || g_cls == NULL || mid == NULL) return NULL;

    jstring ja = (*s.env)->NewStringUTF(s.env, a ? a : "");
    jstring jr = (jstring) (*s.env)->CallStaticObjectMethod(s.env, g_cls, mid, ja);

    char *out = NULL;

    if (jr != NULL) {
        const char *chars = (*s.env)->GetStringUTFChars(s.env, jr, NULL);
        if (chars != NULL) {
            out = strdup(chars);
            (*s.env)->ReleaseStringUTFChars(s.env, jr, chars);
        }
        (*s.env)->DeleteLocalRef(s.env, jr);
    }

    (*s.env)->DeleteLocalRef(s.env, ja);
    env_release(&s);

    return out;
}

/* 调 Java 静态方法 (String, String) -> String */
static char *jni_call_string2(jmethodID mid, const char *a, const char *b) {
    EnvScope s = env_acquire();
    if (s.env == NULL || g_cls == NULL || mid == NULL) return NULL;

    jstring ja = (*s.env)->NewStringUTF(s.env, a ? a : "");
    jstring jb = (*s.env)->NewStringUTF(s.env, b ? b : "");
    jstring jr = (jstring) (*s.env)->CallStaticObjectMethod(s.env, g_cls, mid, ja, jb);

    char *out = NULL;

    if (jr != NULL) {
        const char *chars = (*s.env)->GetStringUTFChars(s.env, jr, NULL);
        if (chars != NULL) {
            out = strdup(chars);
            (*s.env)->ReleaseStringUTFChars(s.env, jr, chars);
        }
        (*s.env)->DeleteLocalRef(s.env, jr);
    }

    (*s.env)->DeleteLocalRef(s.env, ja);
    (*s.env)->DeleteLocalRef(s.env, jb);
    env_release(&s);

    return out;
}

/* ---------------- 模块名解析 ---------------- */

static char *resolve_name(const char *base, const char *name) {
    if (name == NULL) return NULL;
    if (strstr(name, "://") != NULL) return strdup(name);
    if (name[0] == '/') return strdup(name);
    if (name[0] != '.') return strdup(name);

    char dir[1024] = { 0 };

    if (base != NULL) {
        const char *slash = strrchr(base, '/');
        if (slash != NULL) {
            size_t n = (size_t) (slash - base);
            if (n >= sizeof(dir)) n = sizeof(dir) - 1;
            memcpy(dir, base, n);
            dir[n] = 0;
        }
    }

    const char *rest = name;

    while (rest[0] == '.') {
        if (rest[1] == '/') {
            rest += 2;
            break;
        }
        if (rest[1] == '.' && rest[2] == '/') {
            char *up = strrchr(dir, '/');
            if (up != NULL) *up = 0;
            rest += 3;
            continue;
        }
        break;
    }

    char out[2048];
    snprintf(out, sizeof(out), "%s/%s", dir, rest);

    char *p = out;
    while (*p) {
        if (p[0] == '/' && p[1] == '/') {
            memmove(p + 1, p + 2, strlen(p + 2) + 1);
        } else {
            p++;
        }
    }

    if (out[0] == '/') {
        return strdup(out + 1);
    }

    return strdup(out);
}

/* 调 Java 静态 void 方法 (String) */
static void jni_call_void(jmethodID mid, const char *a) {
    EnvScope s = env_acquire();
    if (s.env == NULL || g_cls == NULL || mid == NULL) return;

    jstring ja = (*s.env)->NewStringUTF(s.env, a ? a : "");
    (*s.env)->CallStaticVoidMethod(s.env, g_cls, mid, ja);
    (*s.env)->DeleteLocalRef(s.env, ja);

    env_release(&s);
}

static JSModuleDef *module_loader(JSContext *ctx, const char *module_name, void *opaque) {
    (void) opaque;

    char *src = jni_call_string(g_mid_module_source, module_name);

    if (src == NULL) {
        LOGE("module not found: %s", module_name);
        JS_ThrowReferenceError(ctx, "could not load module '%s'", module_name);
        return NULL;
    }

    JSValue v = JS_Eval(ctx, src, strlen(src), module_name,
                        JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
    free(src);

    if (JS_IsException(v)) {
        LOGE("compile failed: %s", module_name);
        return NULL;
    }

    JSModuleDef *m = JS_VALUE_GET_PTR(v);
    JS_FreeValue(ctx, v);

    return m;
}

static char *module_normalize(JSContext *ctx, const char *base, const char *name, void *opaque) {
    (void) ctx;
    (void) opaque;

    return resolve_name(base, name);
}

/* ---------------- JS 原生函数 ---------------- */

static int interrupt_handler(JSRuntime *rt, void *opaque) {
    (void) rt;
    (void) opaque;

    if (g_deadline_ms > 0 && now_ms() > g_deadline_ms) {
        LOGE("JS 执行超时，已中断");
        return 1;
    }

    return 0;
}

static JSValue js_http(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void) this_val;

    if (argc < 1) {
        return JS_ThrowTypeError(ctx, "_http(url) 缺少参数");
    }

    const char *url = JS_ToCString(ctx, argv[0]);
    if (url == NULL) {
        return JS_ThrowTypeError(ctx, "_http: url 不是字符串");
    }

    const char *opts = "{}";

    if (argc > 1 && JS_IsString(argv[1])) {
        opts = JS_ToCString(ctx, argv[1]);
    }

    char *ret = jni_call_string2(g_mid_http_sync, url, opts);

    JS_FreeCString(ctx, url);
    if (opts != NULL && strcmp(opts, "{}") != 0) {
        JS_FreeCString(ctx, opts);
    }

    if (ret == NULL) {
        return JS_ThrowInternalError(ctx, "_http: 宿主返回空");
    }

    JSValue obj = JS_ParseJSON(ctx, ret, strlen(ret), "<_http>");
    free(ret);

    if (JS_IsException(obj)) {
        JS_FreeValue(ctx, obj);
        return JS_ThrowInternalError(ctx, "_http: 宿主返回的不是 JSON");
    }

    return obj;
}

static JSValue js_pdfh(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void) this_val;

    if (argc < 2) return JS_NewStringLen(ctx, "", 0);

    const char *html = JS_ToCString(ctx, argv[0]);
    const char *rule = JS_ToCString(ctx, argv[1]);

    if (html == NULL || rule == NULL) {
        if (html) JS_FreeCString(ctx, html);
        if (rule) JS_FreeCString(ctx, rule);
        return JS_NewStringLen(ctx, "", 0);
    }

    char *ret = jni_call_string2(g_mid_pdfh, rule, html);

    JS_FreeCString(ctx, html);
    JS_FreeCString(ctx, rule);

    if (ret == NULL) return JS_NewStringLen(ctx, "", 0);

    JSValue out = JS_ParseJSON(ctx, ret, strlen(ret), "<_pdfh>");
    free(ret);

    if (JS_IsException(out)) {
        JS_FreeValue(ctx, out);
        return JS_NewStringLen(ctx, "", 0);
    }

    return out;
}

static JSValue js_pdfa(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void) this_val;

    if (argc < 2) return JS_NewArray(ctx);

    const char *html = JS_ToCString(ctx, argv[0]);
    const char *rule = JS_ToCString(ctx, argv[1]);

    if (html == NULL || rule == NULL) {
        if (html) JS_FreeCString(ctx, html);
        if (rule) JS_FreeCString(ctx, rule);
        return JS_NewArray(ctx);
    }

    char *ret = jni_call_string2(g_mid_pdfa, rule, html);

    JS_FreeCString(ctx, html);
    JS_FreeCString(ctx, rule);

    if (ret == NULL) return JS_NewArray(ctx);

    JSValue out = JS_ParseJSON(ctx, ret, strlen(ret), "<_pdfa>");
    free(ret);

    if (JS_IsException(out)) {
        JS_FreeValue(ctx, out);
        return JS_NewArray(ctx);
    }

    return out;
}

static JSValue js_log(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void) this_val;

    if (argc >= 1) {
        const char *msg = JS_ToCString(ctx, argv[0]);
        if (msg != NULL) {
            jni_call_void(g_mid_log, msg);
            LOGI("%s", msg);
            JS_FreeCString(ctx, msg);
        }
    }

    return JS_UNDEFINED;
}

/* ---------------- 初始化 ---------------- */

static void install_globals(JSContext *ctx) {
    JSValue global = JS_GetGlobalObject(ctx);

    JS_SetPropertyStr(ctx, global, "_http",
                      JS_NewCFunction(ctx, js_http, "_http", 2));
    JS_SetPropertyStr(ctx, global, "_pdfh",
                      JS_NewCFunction(ctx, js_pdfh, "_pdfh", 3));
    JS_SetPropertyStr(ctx, global, "_pdfa",
                      JS_NewCFunction(ctx, js_pdfa, "_pdfa", 3));
    JS_SetPropertyStr(ctx, global, "__log",
                      JS_NewCFunction(ctx, js_log, "__log", 1));

    JS_FreeValue(ctx, global);
}

static bool ensure_init(void) {
    if (g_ctx != NULL) return true;

    g_rt = JS_NewRuntime();
    if (g_rt == NULL) return false;

    // 内存和栈限制。
    //
    // 实测：drpy2.min.js（66KB）内部有较深的递归（模板渲染 + 选择器链），
    // 1MB 栈不够，会抛 "Maximum call stack size exceeded"，
    // 导致 221 个基于 drpy2 的源全部加载失败。
    // 电视内存充裕（2GB+），给到 256MB / 4MB 是安全的。
    JS_SetMemoryLimit(g_rt, 256 * 1024 * 1024);
    JS_SetMaxStackSize(g_rt, 4 * 1024 * 1024);

    g_ctx = JS_NewContext(g_rt);
    if (g_ctx == NULL) return false;

    JS_SetInterruptHandler(g_rt, interrupt_handler, NULL);
    JS_SetModuleLoaderFunc(g_rt, module_normalize, module_loader, NULL);

    install_globals(g_ctx);

    return true;
}

JNIEXPORT void JNICALL
Java_com_reelix_app_data_source_js_QuickJs_nativeInit(JNIEnv *env, jclass clazz) {
    (void) clazz;

    (*env)->GetJavaVM(env, &g_vm);

    if (g_cls == NULL) {
        jclass local = (*env)->FindClass(env, "com/reelix/app/data/source/js/QuickJs");
        if (local != NULL) {
            g_cls = (jclass) (*env)->NewGlobalRef(env, local);
            (*env)->DeleteLocalRef(env, local);
        }
    }

    if (g_cls != NULL) {
        g_mid_module_source = (*env)->GetStaticMethodID(
                env, g_cls, "moduleSource", "(Ljava/lang/String;)Ljava/lang/String;");
        g_mid_http_sync = (*env)->GetStaticMethodID(
                env, g_cls, "httpSync", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        g_mid_pdfh = (*env)->GetStaticMethodID(
                env, g_cls, "pdfhRule", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        g_mid_pdfa = (*env)->GetStaticMethodID(
                env, g_cls, "pdfaRule", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        g_mid_log = (*env)->GetStaticMethodID(
                env, g_cls, "hostLog", "(Ljava/lang/String;)V");
    }

    ensure_init();
}

JNIEXPORT jstring JNICALL
Java_com_reelix_app_data_source_js_QuickJs_nativeVersion(JNIEnv *env, jclass clazz) {
    (void) clazz;
    return (*env)->NewStringUTF(env, JS_GetVersion());
}

/**
 * 把 JS 异常转成可读字符串。
 *
 * ★ 2026-11-01 修：原来只取 `stack`，取不到就退回 `exc`。
 *   但实测 SyntaxError 的 stack 里**没有 message**（只有位置），
 *   而 exc 是 Object 时 JS_ToCString 会得到 "[object Object]" 或空串。
 *   结果就是日志里只有 "ERROR: " 后面一片空白，完全没法定位。
 *
 *   现在按优先级取：message → stack → exc 本身。
 */
static jstring throw_to_string(JNIEnv *env, JSContext *ctx, const char *prefix) {
    JSValue exc = JS_GetException(ctx);
    char buf[4096] = {0};

    // ① 优先 message（SyntaxError / TypeError 等都有）
    JSValue msg_val = JS_GetPropertyStr(ctx, exc, "message");
    const char *msg = NULL;
    if (!JS_IsUndefined(msg_val) && !JS_IsNull(msg_val)) {
        msg = JS_ToCString(ctx, msg_val);
    }

    if (msg != NULL && msg[0] != '\0') {
        snprintf(buf, sizeof(buf), "%s%s", prefix, msg);
        JS_FreeCString(ctx, msg);
    } else {
        // ② 退回 stack（有函数调用链）
        JSValue stack = JS_GetPropertyStr(ctx, exc, "stack");
        const char *st = NULL;
        if (!JS_IsUndefined(stack) && !JS_IsNull(stack)) {
            st = JS_ToCString(ctx, stack);
        }
        if (st != NULL && st[0] != '\0') {
            snprintf(buf, sizeof(buf), "%s%s", prefix, st);
            JS_FreeCString(ctx, st);
        } else {
            // ③ 最后试 exc 本身（字符串异常走这里）
            const char *raw = JS_ToCString(ctx, exc);
            snprintf(buf, sizeof(buf), "%s%s", prefix,
                     (raw != NULL && raw[0] != '\0') ? raw : "(未知 JS 异常)");
            if (raw != NULL) JS_FreeCString(ctx, raw);
        }
        JS_FreeValue(ctx, stack);
    }

    JS_FreeValue(ctx, msg_val);
    JS_FreeValue(ctx, exc);
    return (*env)->NewStringUTF(env, buf);
}

static void drain_jobs(JSContext *ctx) {
    JSContext *ctx1;
    int err;

    for (;;) {
        err = JS_ExecutePendingJob(JS_GetRuntime(ctx), &ctx1);
        if (err <= 0) {
            if (err < 0) {
                LOGE("pending job failed");
            }
            break;
        }
        if (g_deadline_ms > 0 && now_ms() > g_deadline_ms) {
            LOGE("job 队列超时");
            break;
        }
    }
}

JNIEXPORT jstring JNICALL
Java_com_reelix_app_data_source_js_QuickJs_nativeEval(JNIEnv *env, jclass clazz, jstring js_code) {
    (void) clazz;

    if (!ensure_init()) {
        return (*env)->NewStringUTF(env, "ERROR: 无法创建 JS 运行时");
    }

    const char *code = (*env)->GetStringUTFChars(env, js_code, NULL);
    if (code == NULL) return (*env)->NewStringUTF(env, "ERROR: 代码读取失败");

    g_deadline_ms = now_ms() + EVAL_TIMEOUT_MS;

    JSValue result = JS_Eval(g_ctx, code, strlen(code), "<eval>", JS_EVAL_TYPE_GLOBAL);
    (*env)->ReleaseStringUTFChars(env, js_code, code);

    drain_jobs(g_ctx);

    jstring ret;

    if (JS_IsException(result)) {
        ret = throw_to_string(env, g_ctx, "ERROR: ");
    } else {
        const char *text = JS_ToCString(g_ctx, result);
        ret = (*env)->NewStringUTF(env, text != NULL ? text : "");
        if (text != NULL) JS_FreeCString(g_ctx, text);
    }

    JS_FreeValue(g_ctx, result);
    g_deadline_ms = 0;

    return ret;
}

JNIEXPORT jstring JNICALL
Java_com_reelix_app_data_source_js_QuickJs_nativeEvalModule(JNIEnv *env, jclass clazz, jstring name) {
    (void) clazz;

    if (!ensure_init()) {
        return (*env)->NewStringUTF(env, "ERROR: 无法创建 JS 运行时");
    }

    const char *mod = (*env)->GetStringUTFChars(env, name, NULL);
    if (mod == NULL) return (*env)->NewStringUTF(env, "ERROR: 模块名读取失败");

    // __entry 的来源有三种，按优先级取：
    //   ① 模块的 default 导出      ← drpy2 / lf_search / alist 都是这种
    //   ② 模块命名空间本身          ← 少数模块只有具名导出
    //   ③ 全局 __JS_SPIDER__        ← lf_live 这类老式脚本，不 export 而是挂全局
    char code[4096];
    snprintf(code, sizeof(code),
             "import * as __ns from '%s';"
             "globalThis.__entry = (__ns && __ns.default)"
             "  ? __ns.default"
             "  : (__ns && Object.keys(__ns).length ? __ns : globalThis.__JS_SPIDER__);"
             "globalThis.__entryName = '%s';"
             "Object.keys(globalThis.__entry || {}).length;",
             mod, mod);

    g_deadline_ms = now_ms() + EVAL_TIMEOUT_MS;

    JSValue result = JS_Eval(g_ctx, code, strlen(code), "<entry>", JS_EVAL_TYPE_MODULE);

    (*env)->ReleaseStringUTFChars(env, name, mod);

    if (JS_IsException(result)) {
        jstring err = throw_to_string(env, g_ctx, "ERROR: ");
        JS_FreeValue(g_ctx, result);
        g_deadline_ms = 0;
        return err;
    }

    JS_FreeValue(g_ctx, result);
    drain_jobs(g_ctx);
    g_deadline_ms = 0;

    return (*env)->NewStringUTF(env, "OK");
}

JNIEXPORT jstring JNICALL
Java_com_reelix_app_data_source_js_QuickJs_nativeCall(JNIEnv *env, jclass clazz, jstring fn, jstring args_json) {
    (void) clazz;

    if (!ensure_init()) {
        return (*env)->NewStringUTF(env, "ERROR: 无法创建 JS 运行时");
    }

    const char *fn_name = (*env)->GetStringUTFChars(env, fn, NULL);
    const char *args_str = (*env)->GetStringUTFChars(env, args_json, NULL);

    if (fn_name == NULL || args_str == NULL) {
        if (fn_name) (*env)->ReleaseStringUTFChars(env, fn, fn_name);
        if (args_str) (*env)->ReleaseStringUTFChars(env, args_json, args_str);
        return (*env)->NewStringUTF(env, "ERROR: 参数读取失败");
    }

    g_deadline_ms = now_ms() + EVAL_TIMEOUT_MS;

    JSValue global = JS_GetGlobalObject(g_ctx);
    JSValue entry = JS_GetPropertyStr(g_ctx, global, "__entry");
    JSValue fn_val = JS_GetPropertyStr(g_ctx, entry, fn_name);

    if (!JS_IsFunction(g_ctx, fn_val)) {
        JS_FreeValue(g_ctx, fn_val);
        JS_FreeValue(g_ctx, entry);
        JS_FreeValue(g_ctx, global);
        (*env)->ReleaseStringUTFChars(env, fn, fn_name);
        (*env)->ReleaseStringUTFChars(env, args_json, args_str);
        g_deadline_ms = 0;
        return (*env)->NewStringUTF(env, "ERROR: entry 上没有这个方法");
    }

    JSValue args = JS_ParseJSON(g_ctx, args_str, strlen(args_str), "<args>");
    int argc = 0;
    JSValue *argv = NULL;

    if (!JS_IsException(args) && JS_IsArray(args)) {
        JSValue len_val = JS_GetPropertyStr(g_ctx, args, "length");
        uint32_t len = 0;
        JS_ToUint32(g_ctx, &len, len_val);
        JS_FreeValue(g_ctx, len_val);

        if (len > 16) len = 16;

        if (len > 0) {
            argv = (JSValue *) calloc(len, sizeof(JSValue));
            argc = (int) len;
            for (uint32_t i = 0; i < len; i++) {
                argv[i] = JS_GetPropertyUint32(g_ctx, args, i);
            }
        }
    }

    if (!JS_IsException(args)) JS_FreeValue(g_ctx, args);

    JSValue result = JS_Call(g_ctx, fn_val, entry, argc, (JSValueConst *) argv);

    for (int i = 0; i < argc; i++) JS_FreeValue(g_ctx, argv[i]);
    if (argv != NULL) free(argv);

    if (JS_IsPromise(result)) {
        drain_jobs(g_ctx);

        JSPromiseStateEnum state = JS_PromiseState(g_ctx, result);
        JSValue settled = JS_PromiseResult(g_ctx, result);

        JS_FreeValue(g_ctx, result);
        result = settled;

        if (state == JS_PROMISE_REJECTED) {
            JS_Throw(g_ctx, JS_DupValue(g_ctx, result));
            jstring err = throw_to_string(env, g_ctx, "ERROR: ");
            JS_FreeValue(g_ctx, result);
            JS_FreeValue(g_ctx, fn_val);
            JS_FreeValue(g_ctx, entry);
            JS_FreeValue(g_ctx, global);
            (*env)->ReleaseStringUTFChars(env, fn, fn_name);
            (*env)->ReleaseStringUTFChars(env, args_json, args_str);
            g_deadline_ms = 0;
            return err;
        }
    }

    jstring ret;

    if (JS_IsException(result)) {
        ret = throw_to_string(env, g_ctx, "ERROR: ");
    } else {
        const char *text = JS_ToCString(g_ctx, result);
        ret = (*env)->NewStringUTF(env, text != NULL ? text : "");
        if (text != NULL) JS_FreeCString(g_ctx, text);
    }

    JS_FreeValue(g_ctx, result);
    JS_FreeValue(g_ctx, fn_val);
    JS_FreeValue(g_ctx, entry);
    JS_FreeValue(g_ctx, global);
    (*env)->ReleaseStringUTFChars(env, fn, fn_name);
    (*env)->ReleaseStringUTFChars(env, args_json, args_str);
    g_deadline_ms = 0;

    return ret;
}

JNIEXPORT jstring JNICALL
Java_com_reelix_app_data_source_js_QuickJs_nativeGetGlobal(JNIEnv *env, jclass clazz, jstring key) {
    (void) clazz;

    if (!ensure_init()) return (*env)->NewStringUTF(env, "");

    const char *k = (*env)->GetStringUTFChars(env, key, NULL);
    if (k == NULL) return (*env)->NewStringUTF(env, "");

    JSValue global = JS_GetGlobalObject(g_ctx);
    JSValue v = JS_GetPropertyStr(g_ctx, global, k);
    const char *text = JS_ToCString(g_ctx, v);

    jstring ret = (*env)->NewStringUTF(env, text != NULL ? text : "");

    if (text != NULL) JS_FreeCString(g_ctx, text);
    JS_FreeValue(g_ctx, v);
    JS_FreeValue(g_ctx, global);
    (*env)->ReleaseStringUTFChars(env, key, k);

    return ret;
}
