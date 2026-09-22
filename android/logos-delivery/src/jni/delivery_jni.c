// JNI shim bridging com.fryorcraken.logos.delivery.DeliveryNative (Kotlin
// `object`, so its `external fun`s are static native methods -- see
// docs/adr/0003-jni-shim-per-module.md section on Kotlin object JNI
// mangling) to liblogosdelivery.so's C FFI.
//
// liblogosdelivery.h pulls in library/generated/logosdelivery.h, which is a
// BUILD ARTIFACT (emitted by nim-ffi's genBindings() macro from the
// {.ffi.}-annotated procs in library/*.nim -- see make target
// libLogosDeliveryAndroid in the submodule's logos_delivery.nimble, and
// scripts/build-jni-shims.sh, which builds it before compiling this file).
// Do not hand-edit the generated header; if the Nim API changes, rebuild it
// and reconcile this file and DeliveryNative.kt against the new output.
//
// API shape (confirmed against the real generated header, not guessed):
// every logosdelivery_ctx_* call is ASYNCHRONOUS -- it returns immediately
// and the terminal result arrives later via a C callback, invoked from
// nim-ffi's own dispatch thread (see library/generated/nim_ffi_prelude.h's
// top-of-file contract comment). nativeCreate/nativeStart/nativeStop below
// block the calling (JVM) thread on a pthread condvar until that callback
// fires, so DeliveryNative presents the same synchronous-native-return shape
// StorageNative.kt already established for logos-storage's own async C API
// (see StorageNode.kt's blockingCall/resultCall) -- both wrapped libraries
// turn out to be fully async under the hood, despite Milestone 2's stub
// assuming delivery was synchronous; NodeLifecycle's blocking start()/stop()
// contract is satisfied here in the shim rather than in Kotlin, mirroring
// where storage's JNI methods do the same blocking (Milestone 4 territory,
// but the two shims should stay structurally consistent).
//
// Event listeners are different: logosdelivery_add_event_listener/
// _remove_event_listener are NOT part of the generated CBOR ctx_* layer --
// they're plain-C, hand-declared directly in liblogosdelivery.h, taking the
// raw ctx pointer (not the LogosDeliveryCtx* wrapper) and firing an
// FFICallback from a dedicated, long-lived native event thread for as long
// as the listener is registered. That callback attaches the calling native
// thread to the JVM once (JNI_OnLoad caches the JavaVM*) and never detaches
// it explicitly -- see onEventCallback()'s comment.

#include "liblogosdelivery.h"

#include <android/log.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "delivery_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// JavaVM caching (JNI_OnLoad) + per-call JNIEnv attach/detach for the native
// event thread, which is never a JVM thread on its own.
// ---------------------------------------------------------------------------

static JavaVM *g_jvm = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
  (void)reserved;
  g_jvm = vm;
  return JNI_VERSION_1_6;
}

// Attaches the current native thread to the JVM if it is not already
// attached. Returns the JNIEnv* to use, or NULL on failure. `*didAttach` is
// set to true if this call performed the attach (so the caller knows whether
// it should detach when done -- the JVM-originated calling thread must never
// be detached here).
static JNIEnv *attachCurrentThread(bool *didAttach) {
  *didAttach = false;
  JNIEnv *env = NULL;
  jint status = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
  if (status == JNI_OK) {
    return env;
  }
  if (status == JNI_EDETACHED) {
    if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) {
      LOGE("AttachCurrentThread failed");
      return NULL;
    }
    *didAttach = true;
    return env;
  }
  LOGE("GetEnv failed with status %d", status);
  return NULL;
}

// ---------------------------------------------------------------------------
// Blocking bridge: turns a single async logosdelivery_ctx_* call into a
// synchronous C call, so the JNI trampoline can return a plain jint/jlong
// directly to Kotlin (see file header comment).
// ---------------------------------------------------------------------------

typedef struct {
  pthread_mutex_t mutex;
  pthread_cond_t cond;
  bool done;
  int errCode;
  char *reply;    // heap copy (nimffi_dup_cstr'd), NULL if none
  char *errMsg;   // heap copy, NULL if none
} BlockingCallState;

static void blockingCallStateInit(BlockingCallState *state) {
  pthread_mutex_init(&state->mutex, NULL);
  pthread_cond_init(&state->cond, NULL);
  state->done = false;
  state->errCode = -1;
  state->reply = NULL;
  state->errMsg = NULL;
}

static void blockingCallStateDestroy(BlockingCallState *state) {
  pthread_mutex_destroy(&state->mutex);
  pthread_cond_destroy(&state->cond);
  free(state->reply);
  free(state->errMsg);
}

// Shared reply-callback body for logosdelivery_ctx_start_node /
// _ctx_stop_node (both LogosDelivery{Start,Stop}NodeReplyFn: identical
// shape, so one trampoline covers both).
static void onLifecycleReply(int errCode, const char *const *reply, const char *errMsg, void *userData) {
  BlockingCallState *state = (BlockingCallState *)userData;
  pthread_mutex_lock(&state->mutex);
  state->errCode = errCode;
  if (reply != NULL && *reply != NULL) {
    state->reply = strdup(*reply);
  }
  if (errMsg != NULL) {
    state->errMsg = strdup(errMsg);
  }
  state->done = true;
  pthread_cond_signal(&state->cond);
  pthread_mutex_unlock(&state->mutex);
}

static void waitForCompletion(BlockingCallState *state) {
  pthread_mutex_lock(&state->mutex);
  while (!state->done) {
    pthread_cond_wait(&state->cond, &state->mutex);
  }
  pthread_mutex_unlock(&state->mutex);
}

// Separate state/trampoline for logosdelivery_ctx_create: its reply shape
// (LogosDeliveryCreateFn) hands back a LogosDeliveryCtx* instead of the
// `const char *const *reply` every other ctx_* call uses, so it cannot share
// BlockingCallState's `reply` field without unsafe type-punning.
typedef struct {
  pthread_mutex_t mutex;
  pthread_cond_t cond;
  bool done;
  int errCode;
  LogosDeliveryCtx *ctx; // NULL on failure, owned by the caller on success
  char *errMsg;          // heap copy, NULL if none
} CreateCallState;

static void createCallStateInit(CreateCallState *state) {
  pthread_mutex_init(&state->mutex, NULL);
  pthread_cond_init(&state->cond, NULL);
  state->done = false;
  state->errCode = -1;
  state->ctx = NULL;
  state->errMsg = NULL;
}

static void createCallStateDestroy(CreateCallState *state) {
  pthread_mutex_destroy(&state->mutex);
  pthread_cond_destroy(&state->cond);
  free(state->errMsg);
  // state->ctx is intentionally NOT freed here: on success it transfers to
  // the Kotlin caller (released later via nativeDestroy); on failure it is
  // already NULL.
}

static void waitForCreateCompletion(CreateCallState *state) {
  pthread_mutex_lock(&state->mutex);
  while (!state->done) {
    pthread_cond_wait(&state->cond, &state->mutex);
  }
  pthread_mutex_unlock(&state->mutex);
}

static void onCreateReply(int errCode, LogosDeliveryCtx *ctx, const char *errMsg, void *userData) {
  CreateCallState *state = (CreateCallState *)userData;
  pthread_mutex_lock(&state->mutex);
  state->errCode = errCode;
  state->ctx = ctx;
  if (errMsg != NULL) {
    state->errMsg = strdup(errMsg);
  }
  state->done = true;
  pthread_cond_signal(&state->cond);
  pthread_mutex_unlock(&state->mutex);
}

// ---------------------------------------------------------------------------
// NativeCallback bridging: invokes
// com.fryorcraken.logos.common.NativeCallback#onResult(int, byte[]) on a
// Kotlin object held as a JNI global ref.
// ---------------------------------------------------------------------------

static jclass g_nativeCallbackClass = NULL;
static jmethodID g_onResultMethod = NULL;

// Looks up and caches NativeCallback's class + onResult method id. Safe to
// call more than once (idempotent); called lazily from the first JNI entry
// point that needs it, since JNI_OnLoad's env is only valid on the thread
// that called it and this repo's other shims (storage_jni) resolve this the
// same way.
static bool ensureNativeCallbackBound(JNIEnv *env) {
  if (g_nativeCallbackClass != NULL) {
    return true;
  }
  jclass localClass = (*env)->FindClass(env, "com/fryorcraken/logos/common/NativeCallback");
  if (localClass == NULL) {
    LOGE("NativeCallback class not found");
    return false;
  }
  g_nativeCallbackClass = (jclass)(*env)->NewGlobalRef(env, localClass);
  (*env)->DeleteLocalRef(env, localClass);
  g_onResultMethod = (*env)->GetMethodID(env, g_nativeCallbackClass, "onResult", "(I[B)V");
  if (g_onResultMethod == NULL) {
    LOGE("NativeCallback#onResult(int, byte[]) not found");
    return false;
  }
  return true;
}

// Invokes callback.onResult(retCode, message) where message is the len-byte
// buffer at `data` (which may be NULL/len==0 for an empty payload). Clears
// any pending exception rather than letting it escape into native code.
static void invokeNativeCallback(JNIEnv *env, jobject callback, jint retCode, const char *data, size_t len) {
  if (callback == NULL || !ensureNativeCallbackBound(env)) {
    return;
  }
  jbyteArray bytes = (*env)->NewByteArray(env, (jsize)len);
  if (bytes != NULL && len > 0 && data != NULL) {
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)len, (const jbyte *)data);
  }
  (*env)->CallVoidMethod(env, callback, g_onResultMethod, retCode, bytes);
  if ((*env)->ExceptionCheck(env)) {
    (*env)->ExceptionClear(env);
  }
  if (bytes != NULL) {
    (*env)->DeleteLocalRef(env, bytes);
  }
}

// ---------------------------------------------------------------------------
// Event-listener bridge: one GlobalRef'd NativeCallback per registered
// listener, invoked from liblogosdelivery's own event thread.
// ---------------------------------------------------------------------------

typedef struct EventListenerContext {
  jobject callbackGlobalRef;
  uint64_t listenerId;
  struct EventListenerContext *next;
} EventListenerContext;

// Process-wide registry so nativeRemoveEventListener can find and free the
// EventListenerContext/GlobalRef a given listener id owns -- the C API only
// gives us the id back, not the userData pointer we registered it with.
// Guarded by its own mutex since add/remove can race across threads (e.g. a
// caller tearing down while an event is mid-delivery).
static pthread_mutex_t g_listenerRegistryMutex = PTHREAD_MUTEX_INITIALIZER;
static EventListenerContext *g_listenerRegistry = NULL;

static void registerListenerContext(EventListenerContext *listenerCtx) {
  pthread_mutex_lock(&g_listenerRegistryMutex);
  listenerCtx->next = g_listenerRegistry;
  g_listenerRegistry = listenerCtx;
  pthread_mutex_unlock(&g_listenerRegistryMutex);
}

// Unlinks and returns the context for `listenerId`, or NULL if not found
// (e.g. already removed). Does not free it -- the caller does that once it
// is safe (after the native library confirms removal).
static EventListenerContext *unregisterListenerContext(uint64_t listenerId) {
  pthread_mutex_lock(&g_listenerRegistryMutex);
  EventListenerContext **link = &g_listenerRegistry;
  EventListenerContext *found = NULL;
  while (*link != NULL) {
    if ((*link)->listenerId == listenerId) {
      found = *link;
      *link = found->next;
      break;
    }
    link = &(*link)->next;
  }
  pthread_mutex_unlock(&g_listenerRegistryMutex);
  return found;
}

static void onEventCallback(int callerRet, const char *msg, size_t len, void *userData) {
  EventListenerContext *ctx = (EventListenerContext *)userData;
  if (ctx == NULL) {
    return;
  }
  bool didAttach = false;
  JNIEnv *env = attachCurrentThread(&didAttach);
  if (env == NULL) {
    return;
  }
  invokeNativeCallback(env, ctx->callbackGlobalRef, callerRet, msg, len);
  // Deliberately never DetachCurrentThread here: liblogosdelivery invokes
  // this repeatedly from the same long-lived native event thread for the
  // life of the listener, and attach/detach per call is both wasteful and,
  // per JNI semantics, invalidates any thread-local JNIEnv* a future call
  // on this same thread would otherwise reuse. The thread is a native
  // pthread the library owns; it never calls back into a path that could
  // observe a stale attachment, and the process exiting cleans it up.
  (void)didAttach;
}

// ---------------------------------------------------------------------------
// JNI entry points -- names/signatures must match
// DeliveryNative.kt's `external fun` declarations exactly.
// ---------------------------------------------------------------------------

// jlong ctx encodes a LogosDeliveryCtx* (the typed wrapper struct, not the
// raw void* nim-ffi handle -- logosdelivery_ctx_destroy expects the wrapper
// and frees it; event (de)registration below unwraps ->ptr as documented in
// library/MESSAGE_EVENTS.md).

JNIEXPORT jlong JNICALL
Java_com_fryorcraken_logos_delivery_DeliveryNative_nativeCreate(
    JNIEnv *env, jobject thiz, jstring configJson, jobject callback) {
  (void)thiz;
  const char *config = (*env)->GetStringUTFChars(env, configJson, NULL);
  if (config == NULL) {
    return 0;
  }

  CreateCallState state;
  createCallStateInit(&state);
  int submitRet = logosdelivery_ctx_create(config, onCreateReply, &state);
  (*env)->ReleaseStringUTFChars(env, configJson, config);

  if (submitRet != 0) {
    createCallStateDestroy(&state);
    invokeNativeCallback(env, callback, submitRet, "logosdelivery_ctx_create dispatch failed", 0);
    return 0;
  }

  waitForCreateCompletion(&state);

  LogosDeliveryCtx *ctx = state.ctx;
  int errCode = state.errCode;
  char *errMsg = state.errMsg;
  invokeNativeCallback(env, callback, errCode, errMsg != NULL ? errMsg : "", errMsg != NULL ? strlen(errMsg) : 0);
  createCallStateDestroy(&state); // does not free `ctx`; see its comment

  if (errCode != 0 || ctx == NULL) {
    return 0;
  }
  return (jlong)(intptr_t)ctx;
}

JNIEXPORT jint JNICALL
Java_com_fryorcraken_logos_delivery_DeliveryNative_nativeStart(
    JNIEnv *env, jobject thiz, jlong ctx, jobject callback) {
  (void)thiz;
  LogosDeliveryCtx *node = (LogosDeliveryCtx *)(intptr_t)ctx;
  if (node == NULL) {
    invokeNativeCallback(env, callback, NIMFFI_RET_ERR, "null context", 0);
    return NIMFFI_RET_ERR;
  }

  BlockingCallState state;
  blockingCallStateInit(&state);
  int submitRet = logosdelivery_ctx_start_node(node, onLifecycleReply, &state);
  if (submitRet != 0) {
    blockingCallStateDestroy(&state);
    invokeNativeCallback(env, callback, submitRet, "logosdelivery_ctx_start_node dispatch failed", 0);
    return submitRet;
  }

  waitForCompletion(&state);
  int errCode = state.errCode;
  const char *msg = state.errCode != 0 ? state.errMsg : state.reply;
  invokeNativeCallback(env, callback, errCode, msg != NULL ? msg : "", msg != NULL ? strlen(msg) : 0);
  blockingCallStateDestroy(&state);
  return errCode;
}

JNIEXPORT jint JNICALL
Java_com_fryorcraken_logos_delivery_DeliveryNative_nativeStop(
    JNIEnv *env, jobject thiz, jlong ctx, jobject callback) {
  (void)thiz;
  LogosDeliveryCtx *node = (LogosDeliveryCtx *)(intptr_t)ctx;
  if (node == NULL) {
    invokeNativeCallback(env, callback, NIMFFI_RET_ERR, "null context", 0);
    return NIMFFI_RET_ERR;
  }

  BlockingCallState state;
  blockingCallStateInit(&state);
  int submitRet = logosdelivery_ctx_stop_node(node, onLifecycleReply, &state);
  if (submitRet != 0) {
    blockingCallStateDestroy(&state);
    invokeNativeCallback(env, callback, submitRet, "logosdelivery_ctx_stop_node dispatch failed", 0);
    return submitRet;
  }

  waitForCompletion(&state);
  int errCode = state.errCode;
  const char *msg = state.errCode != 0 ? state.errMsg : state.reply;
  invokeNativeCallback(env, callback, errCode, msg != NULL ? msg : "", msg != NULL ? strlen(msg) : 0);
  blockingCallStateDestroy(&state);
  return errCode;
}

// Synchronous: logosdelivery_ctx_destroy (unlike create/start/stop) is a
// plain blocking C call, not an async ctx_* submit -- see
// library/README.md's logosdelivery_ctx_destroy section.
JNIEXPORT void JNICALL
Java_com_fryorcraken_logos_delivery_DeliveryNative_nativeDestroy(
    JNIEnv *env, jobject thiz, jlong ctx) {
  (void)env;
  (void)thiz;
  LogosDeliveryCtx *node = (LogosDeliveryCtx *)(intptr_t)ctx;
  if (node == NULL) {
    return;
  }
  logosdelivery_ctx_destroy(node); // frees `node` itself; do not reuse ctx afterwards
}

// Returns a non-zero listener id, or 0 on failure -- matches
// DeliveryNative.kt's documented nativeAddEventListener contract.
JNIEXPORT jlong JNICALL
Java_com_fryorcraken_logos_delivery_DeliveryNative_nativeAddEventListener(
    JNIEnv *env, jobject thiz, jlong ctx, jstring eventName, jobject callback) {
  (void)thiz;
  LogosDeliveryCtx *node = (LogosDeliveryCtx *)(intptr_t)ctx;
  if (node == NULL || node->ptr == NULL) {
    return 0;
  }
  const char *name = (*env)->GetStringUTFChars(env, eventName, NULL);
  if (name == NULL) {
    return 0;
  }

  EventListenerContext *listenerCtx = (EventListenerContext *)malloc(sizeof(EventListenerContext));
  if (listenerCtx == NULL) {
    (*env)->ReleaseStringUTFChars(env, eventName, name);
    return 0;
  }
  listenerCtx->callbackGlobalRef = (*env)->NewGlobalRef(env, callback);
  listenerCtx->listenerId = 0;
  listenerCtx->next = NULL;

  // logosdelivery_add_event_listener takes the RAW ctx handle (node->ptr),
  // not the LogosDeliveryCtx* wrapper -- see library/MESSAGE_EVENTS.md's
  // `void *rawCtx = ctx->ptr;` usage note.
  uint64_t listenerId = logosdelivery_add_event_listener(node->ptr, name, onEventCallback, listenerCtx);
  (*env)->ReleaseStringUTFChars(env, eventName, name);

  if (listenerId == 0) {
    (*env)->DeleteGlobalRef(env, listenerCtx->callbackGlobalRef);
    free(listenerCtx);
    return 0;
  }
  listenerCtx->listenerId = listenerId;
  registerListenerContext(listenerCtx);
  return (jlong)listenerId;
}

JNIEXPORT jint JNICALL
Java_com_fryorcraken_logos_delivery_DeliveryNative_nativeRemoveEventListener(
    JNIEnv *env, jobject thiz, jlong ctx, jlong listenerId) {
  (void)thiz;
  LogosDeliveryCtx *node = (LogosDeliveryCtx *)(intptr_t)ctx;
  if (node == NULL || node->ptr == NULL) {
    return NIMFFI_RET_ERR;
  }
  int ret = logosdelivery_remove_event_listener(node->ptr, (uint64_t)listenerId);

  // Free our side's bookkeeping regardless of `ret`: RET_ERR here only
  // means "listener id not found or context invalid" (per
  // liblogosdelivery.h), which for an id this shim itself minted means it
  // was already removed -- still safe, and necessary, to drop our
  // GlobalRef so the Kotlin callback object isn't pinned forever.
  EventListenerContext *listenerCtx = unregisterListenerContext((uint64_t)listenerId);
  if (listenerCtx != NULL) {
    (*env)->DeleteGlobalRef(env, listenerCtx->callbackGlobalRef);
    free(listenerCtx);
  }
  return ret;
}
