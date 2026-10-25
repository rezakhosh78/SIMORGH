#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdint>
#include <atomic>
#include <mutex>
#include <string>
#include <unistd.h>
#include <errno.h>
#include <cstring>

#define LOG_TAG "SimorghTun2Proxy"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Stable Android path for SIMORGH:
// Use the official Android fd-run ABI exported by tun2proxy v0.7.x Android libs.
// v0.8.x removed this exported symbol in some Android release artifacts and forced
// CLI entrypoints; the CLI entrypoint can terminate the whole Android process on
// fatal errors. For Android VpnService we must not call a native function that can
// kill the app process. Pin Gradle to v0.7.20 and call tun2proxy_with_fd_run only.
typedef int (*tun2proxy_with_fd_run_fn)(
        const char *proxy_url,
        int tun_fd,
        bool close_fd_on_drop,
        bool tun_pre_up,
        uint16_t tun_mtu,
        int dns_strategy,
        int verbosity
);

typedef void (*tun2proxy_stop_fn)();

static std::mutex g_load_mutex;
static void *g_handle = nullptr;
static tun2proxy_with_fd_run_fn g_run_fd = nullptr;
static tun2proxy_stop_fn g_stop = nullptr;
static std::atomic_bool g_running(false);
static std::atomic_bool g_exit_process_on_return(false);
static char g_last_error[1024] = {0};

static void set_error(const char *stage, const char *detail) {
    snprintf(g_last_error, sizeof(g_last_error), "%s%s%s",
             stage ? stage : "unknown",
             detail && detail[0] ? ": " : "",
             detail && detail[0] ? detail : "");
    LOGE("%s", g_last_error);
}

static void clear_error() {
    g_last_error[0] = '\0';
}

static void clear_dlerror() {
    while (dlerror() != nullptr) {}
}

static const char *dns_name(int dns_strategy) {
    switch (dns_strategy) {
        case 0: return "virtual";
        case 1: return "over-tcp";
        case 2: return "direct";
        default: return "direct";
    }
}

static const char *verbosity_name(int verbosity) {
    switch (verbosity) {
        case 1: return "error";
        case 2: return "warn";
        case 3: return "info";
        case 4: return "debug";
        default: return "info";
    }
}

static bool ensure_loaded_locked() {
    if (g_handle && g_run_fd) return true;

    clear_error();

    // Official Android integration uses dlopen("libtun2proxy.so", RTLD_LAZY)
    // and dlsym("tun2proxy_with_fd_run"). Keep the handle cached; never dlclose
    // the Rust library while the app process is alive.
    clear_dlerror();
    g_handle = dlopen("libtun2proxy.so", RTLD_LAZY);
    const char *open_err = dlerror();
    if (!g_handle) {
        char msg[512];
        snprintf(msg, sizeof(msg), "dlopen libtun2proxy.so failed: %s", open_err ? open_err : "<none>");
        set_error("tun2proxy load failed", msg);
        return false;
    }

    clear_dlerror();
    g_run_fd = reinterpret_cast<tun2proxy_with_fd_run_fn>(dlsym(g_handle, "tun2proxy_with_fd_run"));
    const char *run_err = dlerror();
    if (!g_run_fd) {
        char msg[512];
        snprintf(msg, sizeof(msg), "dlsym tun2proxy_with_fd_run failed: %s. This APK probably packaged tun2proxy v0.8.x/CLI-only libs; rebuild with the pinned v0.7.20 android-libs.zip.",
                 run_err ? run_err : "<none>");
        set_error("tun2proxy entrypoint missing", msg);
        return false;
    }

    clear_dlerror();
    g_stop = reinterpret_cast<tun2proxy_stop_fn>(dlsym(g_handle, "tun2proxy_stop"));
    const char *stop_err = dlerror();
    if (!g_stop) {
        LOGE("tun2proxy_stop not exported: %s", stop_err ? stop_err : "<none>");
    }

    LOGI("tun2proxy fd-run ABI ready: run_fd=1 stop=%d", g_stop ? 1 : 0);
    return true;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rkh_vpn_core_Tun2ProxyBridge_startTun2proxyNative(
        JNIEnv *env,
        jclass,
        jstring proxy_url,
        jint tun_fd,
        jboolean close_fd_on_drop,
        jint tun_mtu,
        jint dns_strategy,
        jint verbosity) {
    const char *proxy = env->GetStringUTFChars(proxy_url, nullptr);
    if (!proxy) {
        set_error("GetStringUTFChars failed", "proxy_url is null");
        return -3;
    }

    {
        std::lock_guard<std::mutex> lock(g_load_mutex);
        if (!ensure_loaded_locked()) {
            env->ReleaseStringUTFChars(proxy_url, proxy);
            return -1;
        }
        bool expected = false;
        if (!g_running.compare_exchange_strong(expected, true)) {
            set_error("tun2proxy already running", "previous native loop did not stop yet; close VPN and wait one second before reconnecting");
            env->ReleaseStringUTFChars(proxy_url, proxy);
            return -4;
        }
    }

    const uint16_t mtu = static_cast<uint16_t>(tun_mtu <= 0 ? 1500 : tun_mtu);
    LOGI("Starting tun2proxy fd-run ABI: proxy=%s fd=%d close_fd_on_drop=%d mtu=%u dns=%s verbosity=%s",
         proxy,
         static_cast<int>(tun_fd),
         close_fd_on_drop == JNI_TRUE ? 1 : 0,
         static_cast<unsigned int>(mtu),
         dns_name(static_cast<int>(dns_strategy)),
         verbosity_name(static_cast<int>(verbosity)));

    int rc = g_run_fd(proxy,
                      static_cast<int>(tun_fd),
                      close_fd_on_drop == JNI_TRUE,
                      false,
                      mtu,
                      static_cast<int>(dns_strategy),
                      static_cast<int>(verbosity));

    LOGI("tun2proxy fd-run ABI returned: %d", rc);
    g_running.store(false);
    env->ReleaseStringUTFChars(proxy_url, proxy);
    if (g_exit_process_on_return.load()) {
        LOGI("tun2proxy returned during disconnect; exiting isolated VPN service process before libtun2proxy teardown can SIGSEGV");
        _exit(0);
    }
    return rc;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rkh_vpn_core_Tun2ProxyBridge_armExitOnReturnNative(JNIEnv *, jclass) {
    g_exit_process_on_return.store(true);
    LOGI("tun2proxy return armed to exit isolated VPN service process");
}

extern "C" JNIEXPORT void JNICALL
Java_com_rkh_vpn_core_Tun2ProxyBridge_disarmExitOnReturnNative(JNIEnv *, jclass) {
    g_exit_process_on_return.store(false);
    LOGI("tun2proxy return process-exit disarmed for in-process reconnect");
}

extern "C" JNIEXPORT void JNICALL
Java_com_rkh_vpn_core_Tun2ProxyBridge_stopTun2proxyNative(JNIEnv *, jclass) {
    // Do not call tun2proxy_stop() from Android disconnect.
    // Device logs showed fd-run returned 0 and then the app process crashed with
    // NATIVE_CRASH/SIGSEGV. The safer shutdown path is: Kotlin closes the
    // detached TUN fd, fd-run naturally returns, and Java never races the native
    // stop symbol against Rust/Go teardown.
    LOGI("tun2proxy_stop ignored intentionally; close the detached TUN fd to stop fd-run safely");
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rkh_vpn_core_Tun2ProxyBridge_closeFdNative(JNIEnv *, jclass, jint fd) {
    const int raw = static_cast<int>(fd);
    if (raw < 0) {
        set_error("close fd skipped", "invalid fd");
        return -1;
    }
    errno = 0;
    const int rc = close(raw);
    if (rc != 0) {
        char msg[256];
        snprintf(msg, sizeof(msg), "fd=%d errno=%d %s", raw, errno, strerror(errno));
        set_error("close detached TUN fd failed", msg);
        return -errno;
    }
    LOGI("closed detached TUN fd from JNI: fd=%d", raw);
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rkh_vpn_core_Tun2ProxyBridge_lastNativeError(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_last_error[0] ? g_last_error : "");
}
