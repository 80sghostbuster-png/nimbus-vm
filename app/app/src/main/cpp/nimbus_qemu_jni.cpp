
#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <spawn.h>
#include <string>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>
#include <cstring>
#include <csignal>
#include <fcntl.h>

#define TAG "NimbusVM-JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern char **environ;

/**
 * Start QEMU (packaged as libqemu-system-*.so) from nativeLibraryDir.
 * Android 10+ cannot exec from app data, but extracted jniLibs are typically executable.
 * Returns the child pid, or -errno on failure.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_lex_nimbusvm_NativeQemu_spawn(JNIEnv *env, jclass, jobjectArray argv) {
    const jsize n = env->GetArrayLength(argv);
    if (n < 1) return -EINVAL;
    std::vector<std::string> storage(n);
    std::vector<char *> cargv(n + 1, nullptr);
    for (jsize i = 0; i < n; ++i) {
        auto js = (jstring) env->GetObjectArrayElement(argv, i);
        const char *utf = env->GetStringUTFChars(js, nullptr);
        storage[i] = utf ? utf : "";
        env->ReleaseStringUTFChars(js, utf);
        env->DeleteLocalRef(js);
        cargv[i] = storage[i].data();
    }
    pid_t pid = 0;
    int rc = posix_spawn(&pid, cargv[0], nullptr, nullptr, cargv.data(), environ);
    if (rc != 0) {
        LOGE("posix_spawn(%s) failed: %s", cargv[0], strerror(rc));
        return -rc;
    }
    LOGI("spawned qemu pid=%d", (int) pid);
    return (jint) pid;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lex_nimbusvm_NativeQemu_waitpid(JNIEnv *, jclass, jint pid, jint timeoutMs) {
    int status = 0;
    // Non-blocking poll loop so the JVM can still be interrupted.
    const int step = 50;
    int waited = 0;
    for (;;) {
        pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
        if (r == (pid_t) pid) {
            if (WIFEXITED(status)) return WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
            return status;
        }
        if (r < 0) return -errno;
        if (waited >= timeoutMs) return -ETIMEDOUT;
        usleep(step * 1000);
        waited += step;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lex_nimbusvm_NativeQemu_kill(JNIEnv *, jclass, jint pid, jint sig) {
    return (kill((pid_t) pid, sig) == 0) ? 0 : -errno;
}
