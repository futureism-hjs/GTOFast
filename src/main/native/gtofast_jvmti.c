/*
 * gtofast_jvmti.c - JVMTI agent + watchdog for GTOFast.
 *
 * Mirrors GTOCutCorners' patcher.c + jvmti_patch.c approach:
 *
 *   Layer 1: ClassFileLoadHook -- parses RecipeModifier class structure,
 *            finds the 3-arg overclocking method, patches code[5] dconst_1->dconst_0.
 *            Only activates when durationFactor <= 0 (1-tick mode).
 *
 *   Layer 2: RecipeLogic linked list -- Mixin calls nativeRegisterRecipeLogic /
 *            nativeUnregisterRecipeLogic. C-side lock-free singly-linked list.
 *
 *   Layer 3: Watchdog thread -- every 3s iterates linked list, fixes
 *            duration > 1 -> 1. Bootstrap world scan on cold start.
 *
 *   Extra: nativeMassPatch -- sets all GT recipe definition durations
 *          to target value via native JNI (faster than Java reflection).
 *
 * Loaded via System.load() -> JNI_OnLoad.
 */

#include <jvmti.h>
#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdint.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#include <unistd.h>
#endif

/* ---- types ---- */
typedef unsigned char  ju1;
typedef unsigned short ju2;
typedef unsigned int   ju4;

static ju2 rd_u2(const ju1* p){ return ((ju2)p[0]<<8)|p[1]; }
static ju4 rd_u4(const ju1* p){ return ((ju4)p[0]<<24)|((ju4)p[1]<<16)|((ju4)p[2]<<8)|p[3]; }
static void wr_u2(ju1* p, ju2 v){ p[0]=(ju1)(v>>8); p[1]=(ju1)v; }
static void wr_u4(ju1* p, ju4 v){ p[0]=(ju1)(v>>24); p[1]=(ju1)(v>>16); p[2]=(ju1)(v>>8); p[3]=(ju1)v; }

/* ---- logging ---- */
static FILE* g_log = NULL;

static void L(const char* fmt, ...) {
    char buf[2048];
    va_list a;
    va_start(a, fmt);
    vsnprintf(buf, sizeof(buf), fmt, a);
    va_end(a);
    printf("[GTOFast-Native] %s\n", buf);
    if (!g_log) g_log = fopen("gtofast_native.log", "w");
    if (g_log) { fprintf(g_log, "[GTOFast-Native] %s\n", buf); fflush(g_log); }
}

/* ---- JVMTI helpers (constant pool) ---- */

static int cp_utf8(const ju1* data, jint len, ju2 idx, char* buf, int bsz) {
    ju2 cc = rd_u2(data+8);
    if (idx == 0 || idx >= cc) return -1;
    int o = 10;
    for (ju2 i = 1; i < idx; i++) {
        if (o >= len) return -1;
        ju1 t = data[o]; o++;
        switch (t) {
            case 1: { ju2 sl = rd_u2(data+o); o += 2+sl; break; }
            case 3: case 4: o += 4; break;
            case 5: case 6: o += 8; i++; break;
            case 7: case 8: case 16: case 19: case 20: o += 2; break;
            case 9: case 10: case 11: case 12: case 17: case 18: o += 4; break;
            case 15: o += 3; break;
            default: return -1;
        }
    }
    if (o >= len || data[o] != 1) return -1;
    ju2 sl = rd_u2(data+o+1);
    if (o+3+sl > len || sl >= bsz) return -1;
    memcpy(buf, data+o+3, sl);
    buf[sl] = 0;
    return 0;
}

static ju1 cp_tag_off(const ju1* data, jint len, ju2 idx, int* oo) {
    ju2 cc = rd_u2(data+8);
    if (idx == 0 || idx >= cc) return 0;
    int o = 10;
    for (ju2 i = 1; i < idx; i++) {
        if (o >= len) return 0;
        ju1 t = data[o]; o++;
        switch (t) {
            case 1: { ju2 sl = rd_u2(data+o); o += 2+sl; break; }
            case 3: case 4: o += 4; break;
            case 5: case 6: o += 8; i++; break;
            case 7: case 8: case 16: case 19: case 20: o += 2; break;
            case 9: case 10: case 11: case 12: case 17: case 18: o += 4; break;
            case 15: o += 3; break;
            default: return 0;
        }
    }
    if (o >= len) return 0;
    *oo = o;
    return data[o];
}

/* ---- Global state ---- */
static jvmtiEnv* g_jvmti = NULL;
static JavaVM* g_jvm = NULL;
static volatile int g_jvmti_ready = 0;
static volatile int g_watchdog_running = 0;
static int g_patch_applied = 0; /* cache: already patched RecipeModifier */
static ju1* g_cached_patched = NULL;
static jint g_cached_patched_len = 0;

/* Forward declarations */
static void JNICALL class_file_load_hook(jvmtiEnv*, JNIEnv*, jclass, jobject, const char*, jobject, jint, const unsigned char*, jint*, unsigned char**);
static int init_jvmti(JavaVM* vm);
static void redefine_loaded_classes(JNIEnv* env);

/* ================================================================
 * JNI_OnLoad -- called when System.load() loads the DLL
 * ================================================================ */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    g_log = fopen("gtofast_native.log", "w");
    L("JNI_OnLoad: vm=%p", (void*)vm);
    init_jvmti(vm);
    return JNI_VERSION_1_2;
}

/* ================================================================
 * init_jvmti -- enable ClassFileLoadHook
 * ================================================================ */
static int init_jvmti(JavaVM* vm) {
    jvmtiEnv* jt = NULL;
    if ((*vm)->GetEnv(vm, (void**)&jt, JVMTI_VERSION_1_0) != JNI_OK || !jt) {
        L("ERROR: GetEnv failed");
        return 0;
    }

    jvmtiCapabilities cp;
    memset(&cp, 0, sizeof(cp));
    cp.can_retransform_classes = 1;
    cp.can_redefine_classes = 1;
    cp.can_generate_all_class_hook_events = 1;
    if ((*jt)->AddCapabilities(jt, &cp) != JVMTI_ERROR_NONE) {
        L("ERROR: AddCapabilities failed");
        return 0;
    }

    jvmtiEventCallbacks cb;
    memset(&cb, 0, sizeof(cb));
    cb.ClassFileLoadHook = &class_file_load_hook;
    if ((*jt)->SetEventCallbacks(jt, &cb, sizeof(cb)) != JVMTI_ERROR_NONE) {
        L("ERROR: SetEventCallbacks failed");
        return 0;
    }

    if ((*jt)->SetEventNotificationMode(jt, JVMTI_ENABLE,
            JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, NULL) != JVMTI_ERROR_NONE) {
        L("ERROR: SetEventNotificationMode failed");
        return 0;
    }

    g_jvmti = jt;
    g_jvmti_ready = 1;
    L("JVMTI ClassFileLoadHook registered OK");
    return 1;
}

/* ================================================================
 * ClassFileLoadHook -- patch RecipeModifier.overclocking()
 * ================================================================ */
static void JNICALL class_file_load_hook(
        jvmtiEnv* jt_env, JNIEnv* jni_env,
        jclass class_being_redefined, jobject loader,
        const char* name, jobject protection_domain,
        jint class_data_len, const unsigned char* class_data,
        jint* new_class_data_len, unsigned char** new_class_data) {

    if (!name) return;

    /* Only process RecipeModifier */
    if (strcmp(name, "com/gregtechceu/gtceu/api/recipe/modifier/RecipeModifier") != 0)
        return;

    L("RecipeModifier: ClassFileLoadHook fired, dlen=%d", class_data_len);

    /* Already patched? Return cached copy */
    if (g_patch_applied && g_cached_patched && class_data_len == g_cached_patched_len) {
        jvmtiError err = (*jt_env)->Allocate(jt_env, class_data_len, new_class_data);
        if (err == JVMTI_ERROR_NONE) {
            memcpy(*new_class_data, g_cached_patched, class_data_len);
            *new_class_data_len = class_data_len;
        }
        return;
    }

    /* Parse constant pool count */
    const ju1* data = class_data;
    int dlen = class_data_len;
    if (dlen < 10) goto rm_done;

    ju2 cc = rd_u2(data + 8);
    int o = 10;

    /* Walk constant pool */
    for (ju2 i = 1; i < cc; i++) {
        if (o >= dlen) { L("RM: CP overflow at idx %d", i); goto rm_done; }
        ju1 t = data[o];
        switch (t) {
            case 1: { ju2 sl = rd_u2(data+o+1); o += 1+2+sl; break; }
            case 3: case 4: o += 5; break;
            case 5: case 6: o += 9; i++; break;
            case 7: case 8: case 16: case 19: case 20: o += 3; break;
            case 9: case 10: case 11: case 12: case 17: case 18: o += 5; break;
            case 15: o += 4; break;
            default: L("RM: bad CP tag %d at idx %d", t, i); goto rm_done;
        }
    }

    /* Skip access_flags + this_class + super_class + interfaces */
    if (o + 8 > dlen) { L("RM: class header past end"); goto rm_done; }
    o += 2+2+2;
    ju2 ic = rd_u2(data + o);
    o += 2 + ic*2;

    /* Skip fields */
    if (o + 2 > dlen) { L("RM: fields header past end"); goto rm_done; }
    ju2 fc = rd_u2(data + o); o += 2;
    for (ju2 f = 0; f < fc; f++) {
        o += 6;
        ju2 fa = rd_u2(data + o); o += 2;
        for (ju2 a = 0; a < fa; a++) {
            ju4 al = rd_u4(data + o + 2);
            o += 6 + al;
        }
    }

    /* Methods */
    if (o + 2 > dlen) { L("RM: methods header past end"); goto rm_done; }
    ju2 mc = rd_u2(data + o); o += 2;
    L("RM: scanning %d methods", (int)mc);

    int rm_found = 0;
    for (ju2 m = 0; m < mc && !rm_found; m++) {
        int ms = o;
        ju2 ma_flags = rd_u2(data + o); o += 2;
        ju2 ni = rd_u2(data + o); o += 2;
        ju2 di = rd_u2(data + o); o += 2;
        ju2 ac = rd_u2(data + o); o += 2;

        char mn[256], md[512];
        cp_utf8(data, dlen, ni, mn, sizeof(mn));
        cp_utf8(data, dlen, di, md, sizeof(md));

        if (strcmp(mn, "overclocking") == 0) {
            int is_static = (ma_flags & 0x0008) != 0;
            L("RM: method[%d] overclocking static=%d desc=%s", (int)m, is_static, md);

            /* Match the 3-arg public API: static, has IRecipeHandlerHolder,
             * RecipeHandlerUnit, GTRecipe, and NOT ZDDD (the inner 10-arg method) */
            if (is_static
                && strstr(md, "IRecipeHandlerHolder")
                && strstr(md, "RecipeHandlerUnit")
                && strstr(md, "GTRecipe")
                && !strstr(md, "ZDDD")) {

                L("RM: *** TARGET: public 3-arg overclocking found! ***");

                int ao = o;
                for (ju2 a = 0; a < ac; a++) {
                    ju2 ani = rd_u2(data + ao);
                    ju4 al = rd_u4(data + ao + 2);
                    char an[256];
                    cp_utf8(data, dlen, ani, an, sizeof(an));

                    if (strcmp(an, "Code") == 0) {
                        int c_off = ao + 6;
                        ju4 cl = rd_u4(data + c_off + 4);
                        int cs = c_off + 8;

                        L("RM: Code len=%d", (int)cl);
                        if (cl >= 13) {
                            L("RM: code[3]=0x%02X code[4]=0x%02X code[5]=0x%02X code[6]=0x%02X",
                                data[cs+3], data[cs+4], data[cs+5], data[cs+6]);

                            if (data[cs+5] == 0x0F) {
                                L("RM: code[5]==0x0F (dconst_1=speed=1.0) -> patching to 0x0E (dconst_0=speed=0.0)");

                                /* Patch in-place, then cache */
                                ju1* mod = (ju1*)malloc(dlen);
                                memcpy(mod, data, dlen);
                                mod[cs+5] = 0x0E;  /* dconst_1 -> dconst_0 */

                                jvmtiError err = (*jt_env)->Allocate(jt_env, dlen, new_class_data);
                                if (err == JVMTI_ERROR_NONE) {
                                    memcpy(*new_class_data, mod, dlen);
                                    *new_class_data_len = dlen;

                                    /* Cache for subsequent hook fires */
                                    g_cached_patched = (ju1*)malloc(dlen);
                                    memcpy(g_cached_patched, mod, dlen);
                                    g_cached_patched_len = dlen;
                                    g_patch_applied = 1;

                                    L("RM: *** PATCH APPLIED speed=1.0->0.0 (duration will be forced to 1) ***");
                                    rm_found = 1;
                                } else {
                                    L("RM: Allocate failed err=%d", (int)err);
                                }
                                free(mod);
                            } else {
                                L("RM: code[5]=0x%02X != 0x0F, not dconst_1? skip", data[cs+5]);
                            }
                        } else {
                            L("RM: Code too short: %d < 13", (int)cl);
                        }
                        break;
                    }
                    ao += 6 + al;
                }
            }
        }

        /* Skip method attributes */
        for (ju2 a = 0; a < ac; a++) {
            ju4 al = rd_u4(data + o + 2);
            o += 6 + al;
        }
    }

    if (!rm_found)
        L("RM: 3-arg overclocking NOT patched (not found / already modified / wrong layout)");
    else
        L("RM: patch OK, returning modified bytecode");

    if (rm_found) return;

rm_done:
    L("RM: parse aborted or not patched");
}

/* ================================================================
 * redefine_loaded_classes -- retransform already-loaded classes
 * ================================================================ */
static void redefine_loaded_classes(JNIEnv* env) {
    if (!g_jvmti_ready) return;

    /* RecipeModifier: retransform for overclocking patch */
    {
        const char* rm = "com/gregtechceu/gtceu/api/recipe/modifier/RecipeModifier";
        jclass c = (*env)->FindClass(env, rm);
        if (c) {
            jvmtiError e = (*g_jvmti)->RetransformClasses(g_jvmti, 1, &c);
            L("RecipeModifier retransform: %s", e == JVMTI_ERROR_NONE ? "OK" : "FAIL");
            (*env)->DeleteLocalRef(env, c);
        } else {
            (*env)->ExceptionClear(env);
            L("RecipeModifier: FindClass failed (may not be loaded yet)");
        }
    }
}

/* =================================================================
 * RecipeLogic linked list (for watchdog)
 * ================================================================= */

typedef struct rl_node {
    jobject global_ref;
    volatile struct rl_node* next;
} rl_node_t;

static rl_node_t* g_rl_head = NULL;
static jfieldID g_rl_dur_fid = NULL;

#ifdef _WIN32
static volatile LONG g_rl_lock = 0;
#define RL_LOCK()   while(InterlockedExchange(&g_rl_lock, 1)) { Sleep(0); }
#define RL_UNLOCK() InterlockedExchange(&g_rl_lock, 0)
#else
static volatile int g_rl_lock = 0;
#define RL_LOCK()   while(__sync_lock_test_and_set(&g_rl_lock, 1)) { usleep(0); }
#define RL_UNLOCK() __sync_lock_release(&g_rl_lock)
#endif

/* ---- Register / Unregister ---- */

JNIEXPORT void JNICALL Java_com_gtofast_NativeAgent_nativeRegisterRecipeLogic
    (JNIEnv *e, jclass cls, jobject logic) {
    if (!logic) return;
    rl_node_t* node = (rl_node_t*)malloc(sizeof(rl_node_t));
    if (!node) return;
    node->global_ref = (*e)->NewGlobalRef(e, logic);
    if (!node->global_ref) { free(node); return; }

    if (!g_rl_dur_fid) {
        jclass rlCls = (*e)->GetObjectClass(e, logic);
        g_rl_dur_fid = (*e)->GetFieldID(e, rlCls, "duration", "I");
        if (!g_rl_dur_fid) (*e)->ExceptionClear(e);
    }

    RL_LOCK();
    node->next = g_rl_head;
    g_rl_head = node;
    RL_UNLOCK();
}

JNIEXPORT void JNICALL Java_com_gtofast_NativeAgent_nativeUnregisterRecipeLogic
    (JNIEnv *e, jclass cls, jobject logic) {
    if (!logic || !g_rl_head) return;
    RL_LOCK();
    rl_node_t** prev = &g_rl_head;
    rl_node_t* curr = g_rl_head;
    while (curr) {
        if ((*e)->IsSameObject(e, curr->global_ref, logic)) {
            *prev = (rl_node_t*)curr->next;
            (*e)->DeleteGlobalRef(e, curr->global_ref);
            free(curr);
            break;
        }
        prev = (rl_node_t**)&curr->next;
        curr = (rl_node_t*)curr->next;
    }
    RL_UNLOCK();
}

/* ---- Bootstrap: world scan to discover RecipeLogics ---- */

static int watchdogBootstrap(JNIEnv* e) {
    L("[BOOTSTRAP] starting world scan...");

    jclass hooksCls = (*e)->FindClass(e, "net/minecraftforge/server/ServerLifecycleHooks");
    if (!hooksCls) { (*e)->ExceptionClear(e); L("[BOOTSTRAP] ServerLifecycleHooks not found"); return 0; }

    jmethodID getCurrent = (*e)->GetStaticMethodID(e, hooksCls, "getCurrentServer",
        "()Lnet/minecraft/server/MinecraftServer;");
    if (!getCurrent) { (*e)->ExceptionClear(e); L("[BOOTSTRAP] getCurrentServer not found"); return 0; }

    jobject server = (*e)->CallStaticObjectMethod(e, hooksCls, getCurrent);
    if (!server) { L("[BOOTSTRAP] server is null"); return 0; }

    jclass mcCls = (*e)->GetObjectClass(e, server);
    jmethodID getLevels = (*e)->GetMethodID(e, mcCls, "getAllLevels",
        "()Ljava/lang/Iterable;");
    if (!getLevels) { (*e)->ExceptionClear(e); L("[BOOTSTRAP] getAllLevels not found"); return 0; }

    jobject levels = (*e)->CallObjectMethod(e, server, getLevels);
    if (!levels) { L("[BOOTSTRAP] levels null"); return 0; }

    jclass levCls = (*e)->GetObjectClass(e, levels);
    jobject it = (*e)->CallObjectMethod(e, levels,
        (*e)->GetMethodID(e, levCls, "iterator", "()Ljava/util/Iterator;"));
    if (!it) { L("[BOOTSTRAP] iterator null"); return 0; }

    jclass itCls = (*e)->GetObjectClass(e, it);
    jmethodID hasNext = (*e)->GetMethodID(e, itCls, "hasNext", "()Z");
    jmethodID next = (*e)->GetMethodID(e, itCls, "next", "()Ljava/lang/Object;");

    jclass mmbeCls = (*e)->FindClass(e,
        "com/gregtechceu/gtceu/api/blockentity/MetaMachineBlockEntity");
    jclass rlmCls = (*e)->FindClass(e,
        "com/gregtechceu/gtceu/api/machine/feature/IRecipeLogicMachine");
    if (!mmbeCls) { (*e)->ExceptionClear(e); L("[BOOTSTRAP] MetaMachineBlockEntity not found"); }
    if (!rlmCls) { (*e)->ExceptionClear(e); L("[BOOTSTRAP] IRecipeLogicMachine not found"); }
    if (!mmbeCls || !rlmCls) return 0;

    int registered = 0;
    while ((*e)->CallBooleanMethod(e, it, hasNext)) {
        jobject level = (*e)->CallObjectMethod(e, it, next);
        if (!level) continue;

        jclass slCls = (*e)->GetObjectClass(e, level);
        jmethodID getCS = (*e)->GetMethodID(e, slCls, "getChunkSource",
            "()Lnet/minecraft/server/level/ServerChunkCache;");
        if (!getCS) { (*e)->ExceptionClear(e); continue; }
        jobject cs = (*e)->CallObjectMethod(e, level, getCS);
        if (!cs) continue;

        jmethodID getChunks = (*e)->GetMethodID(e, (*e)->GetObjectClass(e, cs),
            "getChunks", "()Ljava/lang/Iterable;");
        if (!getChunks) { (*e)->ExceptionClear(e); continue; }
        jobject chunks = (*e)->CallObjectMethod(e, cs, getChunks);
        if (!chunks) continue;

        jobject chunkIt = (*e)->CallObjectMethod(e, chunks,
            (*e)->GetMethodID(e, (*e)->GetObjectClass(e, chunks),
            "iterator", "()Ljava/util/Iterator;"));
        if (!chunkIt) continue;

        jclass citc = (*e)->GetObjectClass(e, chunkIt);
        jmethodID chn = (*e)->GetMethodID(e, citc, "hasNext", "()Z");
        jmethodID cnx = (*e)->GetMethodID(e, citc, "next", "()Ljava/lang/Object;");

        while ((*e)->CallBooleanMethod(e, chunkIt, chn)) {
            jobject chunk = (*e)->CallObjectMethod(e, chunkIt, cnx);
            if (!chunk) continue;

            jmethodID getBE = (*e)->GetMethodID(e, (*e)->GetObjectClass(e, chunk),
                "getBlockEntities", "()Ljava/util/Map;");
            if (!getBE) { (*e)->ExceptionClear(e); continue; }
            jobject beMap = (*e)->CallObjectMethod(e, chunk, getBE);
            if (!beMap) continue;

            jobject vals = (*e)->CallObjectMethod(e, beMap,
                (*e)->GetMethodID(e, (*e)->GetObjectClass(e, beMap),
                "values", "()Ljava/util/Collection;"));
            if (!vals) continue;

            jobject beIt = (*e)->CallObjectMethod(e, vals,
                (*e)->GetMethodID(e, (*e)->GetObjectClass(e, vals),
                "iterator", "()Ljava/util/Iterator;"));
            if (!beIt) continue;

            jclass bitc = (*e)->GetObjectClass(e, beIt);
            jmethodID bhn = (*e)->GetMethodID(e, bitc, "hasNext", "()Z");
            jmethodID bnx = (*e)->GetMethodID(e, bitc, "next", "()Ljava/lang/Object;");

            while ((*e)->CallBooleanMethod(e, beIt, bhn)) {
                jobject be = (*e)->CallObjectMethod(e, beIt, bnx);
                if (!be || !(*e)->IsInstanceOf(e, be, mmbeCls)) continue;

                jmethodID gm = (*e)->GetMethodID(e, mmbeCls, "getMetaMachine",
                    "()Lcom/gregtechceu/gtceu/api/machine/MetaMachine;");
                if (!gm) { (*e)->ExceptionClear(e); continue; }
                jobject machine = (*e)->CallObjectMethod(e, be, gm);
                if (!machine || !(*e)->IsInstanceOf(e, machine, rlmCls)) continue;

                jmethodID grl = (*e)->GetMethodID(e, rlmCls, "getRecipeLogic",
                    "()Lcom/gregtechceu/gtceu/api/machine/trait/RecipeLogic;");
                if (!grl) { (*e)->ExceptionClear(e); continue; }
                jobject logic = (*e)->CallObjectMethod(e, machine, grl);
                if (!logic) continue;

                /* Check duplicate */
                int dup = 0;
                RL_LOCK();
                rl_node_t* p = g_rl_head;
                while (p) {
                    if ((*e)->IsSameObject(e, p->global_ref, logic)) { dup = 1; break; }
                    p = (rl_node_t*)p->next;
                }
                if (!dup) {
                    rl_node_t* node = (rl_node_t*)malloc(sizeof(rl_node_t));
                    if (node) {
                        node->global_ref = (*e)->NewGlobalRef(e, logic);
                        if (node->global_ref) {
                            if (!g_rl_dur_fid) {
                                jclass rlCls = (*e)->GetObjectClass(e, logic);
                                g_rl_dur_fid = (*e)->GetFieldID(e, rlCls, "duration", "I");
                                if (!g_rl_dur_fid) (*e)->ExceptionClear(e);
                            }
                            node->next = g_rl_head;
                            g_rl_head = node;
                            registered++;
                        } else {
                            free(node);
                        }
                    }
                }
                RL_UNLOCK();
            }
        }
    }

    L("[BOOTSTRAP] done, registered=%d", registered);
    return registered;
}

/* ---- Watchdog thread ---- */

#ifdef _WIN32
static DWORD WINAPI watchdog_thread(LPVOID arg) {
#else
static void* watchdog_thread(void* arg) {
#endif
    L("[WATCHDOG] thread started (linked-list mode)");
    g_watchdog_running = 1;

    int cycle = 0;
    while (g_watchdog_running) {
#ifdef _WIN32
        Sleep(3000);
#else
        sleep(3);
#endif
        cycle++;
        if (!g_watchdog_running) continue;

        if (!g_jvm) { L("[WATCHDOG] FATAL: g_jvm NULL"); continue; }

        JNIEnv* e = NULL;
        jint rs = (*g_jvm)->GetEnv(g_jvm, (void**)&e, JNI_VERSION_1_2);
        int need_detach = 0;
        if (rs == JNI_EDETACHED) {
            if ((*g_jvm)->AttachCurrentThread(g_jvm, (void**)&e, NULL) != JNI_OK) continue;
            need_detach = 1;
        } else if (rs != JNI_OK) continue;

        /* Bootstrap every 10 cycles or when list is empty */
        if (cycle % 10 == 1 || !g_rl_head) {
            L("[WATCHDOG] full scan (cycle=%d)...", cycle);
            int synced = watchdogBootstrap(e);
            if (synced > 0) L("[WATCHDOG] sync: added %d new RecipeLogic", synced);
            if (!g_rl_head) {
                if (need_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
                continue;
            }
        }

        /* Iterate linked list, fix durations */
        int alive = 0, patched = 0, dead = 0;
        rl_node_t* prev = NULL;
        rl_node_t* curr = NULL;

        RL_LOCK();
        curr = g_rl_head;
        RL_UNLOCK();

        while (curr) {
            rl_node_t* next_node = (rl_node_t*)curr->next;
            alive++;

            if (g_rl_dur_fid) {
                jint dur = 0;
                int valid = 1;
                dur = (*e)->GetIntField(e, curr->global_ref, g_rl_dur_fid);
                if ((*e)->ExceptionCheck(e)) {
                    (*e)->ExceptionClear(e);
                    valid = 0;
                }

                if (!valid) {
                    /* Object died */
                    RL_LOCK();
                    if (prev) prev->next = next_node;
                    else g_rl_head = next_node;
                    RL_UNLOCK();
                    (*e)->DeleteGlobalRef(e, curr->global_ref);
                    free(curr);
                    dead++;
                } else if (dur > 1) {
                    (*e)->SetIntField(e, curr->global_ref, g_rl_dur_fid, 1);
                    patched++;
                    prev = curr;
                } else {
                    prev = curr;
                }
            }
            curr = next_node;
        }

        if (cycle <= 3 || cycle % 20 == 0 || patched > 0) {
            L("[WATCHDOG] cycle=%d alive=%d patched=%d dead=%d",
                cycle, alive, patched, dead);
        }

        if (need_detach) (*g_jvm)->DetachCurrentThread(g_jvm);
    }

    L("[WATCHDOG] stopped");
#ifdef _WIN32
    return 0;
#else
    return NULL;
#endif
}

/* ---- Start / Stop watchdog ---- */

JNIEXPORT void JNICALL Java_com_gtofast_NativeAgent_nativeStartWatchdog
    (JNIEnv *e, jclass cls) {
    if (g_watchdog_running) { L("[WATCHDOG] already running"); return; }
    L("[WATCHDOG] starting daemon thread...");
#ifdef _WIN32
    HANDLE h = CreateThread(NULL, 0, watchdog_thread, NULL, 0, NULL);
    if (h) { CloseHandle(h); L("[WATCHDOG] thread created"); }
    else L("[WATCHDOG] ERROR: CreateThread failed!");
#else
    pthread_t tid;
    int rc = pthread_create(&tid, NULL, watchdog_thread, NULL);
    if (rc == 0) { pthread_detach(tid); L("[WATCHDOG] thread created"); }
    else L("[WATCHDOG] ERROR: pthread_create failed!");
#endif
}

JNIEXPORT void JNICALL Java_com_gtofast_NativeAgent_nativeStopWatchdog
    (JNIEnv *e, jclass cls) {
    g_watchdog_running = 0;
    L("[WATCHDOG] stop signal sent");
}

/* ================================================================
 * nativeInitJVMTI -- called right after System.load() in loadAndPatch
 * ================================================================ */

JNIEXPORT void JNICALL Java_com_gtofast_NativeAgent_nativeInitJVMTI
    (JNIEnv *e, jclass cls) {
    redefine_loaded_classes(e);
}

/* ================================================================
 * nativeMassPatch -- set all GT recipe durations to 'target' via native JNI
 * ================================================================ */

JNIEXPORT jint JNICALL Java_com_gtofast_NativeAgent_nativeMassPatch
    (JNIEnv *e, jclass cls, jint targetDuration) {

    L("[MASSPATCH] target=%d", targetDuration);

    /* Collect all GT recipes from GTRegistries.RECIPE_TYPES */
    jclass grCls = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/registry/GTRegistries");
    if (!grCls) { (*e)->ExceptionClear(e); L("[MASSPATCH] GTRegistries not found"); return -1; }

    jfieldID rtf = (*e)->GetStaticFieldID(e, grCls, "RECIPE_TYPES",
        "Lcom/gregtechceu/gtceu/api/registry/GTRegistry;");
    if (!rtf) { (*e)->ExceptionClear(e); L("[MASSPATCH] RECIPE_TYPES not found"); return -1; }

    jobject registry = (*e)->GetStaticObjectField(e, grCls, rtf);
    if (!registry) { L("[MASSPATCH] registry null"); return -1; }

    jclass regC = (*e)->GetObjectClass(e, registry);
    jobject typesColl = (*e)->CallObjectMethod(e, registry,
        (*e)->GetMethodID(e, regC, "values", "()Ljava/util/Collection;"));
    if (!typesColl) return -1;

    jclass collC = (*e)->GetObjectClass(e, typesColl);
    jint typeCount = (*e)->CallIntMethod(e, typesColl,
        (*e)->GetMethodID(e, collC, "size", "()I"));
    L("[MASSPATCH] %d recipe types", typeCount);

    /* Find duration field: try GTRecipe first, then GTRecipeDefinition */
    jclass recC = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/recipe/GTRecipe");
    jclass defC = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/recipe/GTRecipeDefinition");

    jfieldID durF = NULL;
    if (defC) {
        durF = (*e)->GetFieldID(e, defC, "duration", "I");
        if (durF) L("[MASSPATCH] duration on GTRecipeDefinition");
    }
    if (!durF) {
        if (defC) (*e)->ExceptionClear(e);
        if (recC) {
            durF = (*e)->GetFieldID(e, recC, "duration", "I");
            if (durF) L("[MASSPATCH] duration on GTRecipe");
        }
    }
    if (!durF && defC) (*e)->ExceptionClear(e);
    if (!durF) { L("[MASSPATCH] duration field not found on either class"); return -1; }

    /* Iterate: get recipes from each RecipeType */
    jobjectArray typesArr = (jobjectArray)(*e)->CallObjectMethod(e, typesColl,
        (*e)->GetMethodID(e, collC, "toArray", "()[Ljava/lang/Object;"));
    jint len = (*e)->GetArrayLength(e, typesArr);

    jclass rtC = (*e)->FindClass(e, "com/gregtechceu/gtceu/api/recipe/GTRecipeType");
    jclass mapC = (*e)->FindClass(e, "java/util/Map");
    jclass collC2 = (*e)->FindClass(e, "java/util/Collection");

    jclass alC = (*e)->FindClass(e, "java/util/ArrayList");
    jobject allRecipes = (*e)->NewObject(e, alC,
        (*e)->GetMethodID(e, alC, "<init>", "()V"));
    jmethodID alAddAll = (*e)->GetMethodID(e, alC, "addAll", "(Ljava/util/Collection;)Z");

    int totalDefs = 0;
    for (jint i = 0; i < len; i++) {
        jobject rt = (*e)->GetObjectArrayElement(e, typesArr, i);
        if (!rt) continue;

        /* Get recipes */
        jobject values = NULL;
        jfieldID recFld = (*e)->GetFieldID(e, rtC, "recipes", "Ljava/util/Map;");
        if (!recFld) (*e)->ExceptionClear(e);
        if (recFld) {
            /* Try as Set first (0.5.1), then Map (0.5.0) */
            jclass setC = (*e)->FindClass(e, "java/util/Set");
            jobject robj = (*e)->GetObjectField(e, rt, recFld);
            if (robj && (*e)->IsInstanceOf(e, robj, setC)) {
                values = robj;
            } else if (robj && (*e)->IsInstanceOf(e, robj, mapC)) {
                values = (*e)->CallObjectMethod(e, robj,
                    (*e)->GetMethodID(e, mapC, "values", "()Ljava/util/Collection;"));
            }
        }

        if (!values) {
            /* Try methods */
            const char* methods[] = {"getRecipes", "getRecipeMap", "recipeMap", NULL};
            const char* sigs[] = {"()Ljava/util/Collection;", "()Ljava/util/Map;", "()Ljava/util/Map;", NULL};
            for (int m = 0; methods[m]; m++) {
                jmethodID mid = (*e)->GetMethodID(e, rtC, methods[m], sigs[m]);
                if (!mid) { (*e)->ExceptionClear(e); continue; }
                jobject result = (*e)->CallObjectMethod(e, rt, mid);
                if (!result) continue;
                if ((*e)->IsInstanceOf(e, result, mapC)) {
                    values = (*e)->CallObjectMethod(e, result,
                        (*e)->GetMethodID(e, mapC, "values", "()Ljava/util/Collection;"));
                } else if ((*e)->IsInstanceOf(e, result, collC2)) {
                    values = result;
                }
                if (values) break;
            }
        }

        if (values) {
            (*e)->CallBooleanMethod(e, allRecipes, alAddAll, values);
            totalDefs++;
        }
    }

    /* Push local frame for GC safety */
    jint sz = (*e)->CallIntMethod(e, allRecipes,
        (*e)->GetMethodID(e, alC, "size", "()I"));
    L("[MASSPATCH] collected %d recipe types, %d total recipes", totalDefs, sz);

    if ((*e)->PushLocalFrame(e, sz + 512) < 0) {
        L("[MASSPATCH] ERR: PushLocalFrame"); return -1;
    }

    jobject arr = (*e)->CallObjectMethod(e, allRecipes,
        (*e)->GetMethodID(e, alC, "toArray", "()[Ljava/lang/Object;"));
    jint rlen = (*e)->GetArrayLength(e, arr);
    int patched = 0;

    for (int i = 0; i < rlen; i++) {
        jobject d = (*e)->GetObjectArrayElement(e, arr, i);
        if (!d) continue;

        jint cur = (*e)->GetIntField(e, d, durF);
        if (cur != targetDuration) {
            (*e)->SetIntField(e, d, durF, targetDuration);
            patched++;
        }

        if (i % 20000 == 0) {
            L("[MASSPATCH] %d/%d p=%d", i, rlen, patched);
        }
    }

    (*e)->PopLocalFrame(e, NULL);
    L("[MASSPATCH] DONE: %d/%d recipes patched to duration=%d", patched, rlen, targetDuration);
    return patched;
}
