#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
extern "C" int grantpt(int);
extern "C" int unlockpt(int);
extern "C" char *ptsname(int);
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <vector>
#include <string>

#define LOG_TAG "RikkahubPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::vector<std::string> toStrings(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> out;
    if (!array) return out;
    const jsize count = env->GetArrayLength(array);
    out.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (!item) {
            out.emplace_back("");
            continue;
        }
        const char *chars = env->GetStringUTFChars(item, nullptr);
        out.emplace_back(chars ? chars : "");
        if (chars) env->ReleaseStringUTFChars(item, chars);
        env->DeleteLocalRef(item);
    }
    return out;
}

static std::vector<char *> toArgv(std::vector<std::string> &strings) {
    std::vector<char *> ptrs;
    ptrs.reserve(strings.size() + 1);
    for (auto &s: strings) ptrs.push_back(const_cast<char *>(s.c_str()));
    ptrs.push_back(nullptr);
    return ptrs;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeStart(
        JNIEnv *env, jobject, jobjectArray argvArray, jobjectArray envArray, jint columns, jint rows) {
    auto argvStrings = toStrings(env, argvArray);
    auto envStrings = toStrings(env, envArray);
    if (argvStrings.empty()) return nullptr;

    int masterFd = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (masterFd < 0) {
        LOGE("open /dev/ptmx failed: %s", strerror(errno));
        return nullptr;
    }
    if (grantpt(masterFd) != 0 || unlockpt(masterFd) != 0) {
        LOGE("grantpt/unlockpt failed: %s", strerror(errno));
        close(masterFd);
        return nullptr;
    }
    char *slaveName = ptsname(masterFd);
    if (!slaveName) {
        LOGE("ptsname failed: %s", strerror(errno));
        close(masterFd);
        return nullptr;
    }

    struct winsize ws{};
    ws.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    ioctl(masterFd, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(masterFd);
        return nullptr;
    }

    if (pid == 0) {
        setsid();
        int slaveFd = open(slaveName, O_RDWR | O_NOCTTY);
        if (slaveFd < 0) _exit(127);
        ioctl(slaveFd, TIOCSCTTY, 0);
        ioctl(slaveFd, TIOCSWINSZ, &ws);
        dup2(slaveFd, STDIN_FILENO);
        dup2(slaveFd, STDOUT_FILENO);
        dup2(slaveFd, STDERR_FILENO);
        if (slaveFd > STDERR_FILENO) close(slaveFd);
        close(masterFd);

        auto argv = toArgv(argvStrings);
        auto envp = toArgv(envStrings);
        execve(argv[0], argv.data(), envp.data());
        _exit(127);
    }

    jlong values[2] = { static_cast<jlong>(pid), static_cast<jlong>(masterFd) };
    jlongArray result = env->NewLongArray(2);
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeRead(
        JNIEnv *env, jobject, jint fd, jbyteArray buffer, jint length) {
    if (fd < 0 || !buffer || length <= 0) return -1;
    jsize capacity = env->GetArrayLength(buffer);
    if (length > capacity) length = capacity;
    jbyte *bytes = env->GetByteArrayElements(buffer, nullptr);
    ssize_t readBytes;
    do {
        readBytes = read(fd, bytes, static_cast<size_t>(length));
    } while (readBytes < 0 && errno == EINTR);
    env->ReleaseByteArrayElements(buffer, bytes, 0);
    if (readBytes < 0) return -errno;
    return static_cast<jint>(readBytes);
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeWrite(
        JNIEnv *env, jobject, jint fd, jbyteArray buffer, jint length) {
    if (fd < 0 || !buffer || length <= 0) return -1;
    jsize capacity = env->GetArrayLength(buffer);
    if (length > capacity) length = capacity;
    jbyte *bytes = env->GetByteArrayElements(buffer, nullptr);
    ssize_t written;
    do {
        written = write(fd, bytes, static_cast<size_t>(length));
    } while (written < 0 && errno == EINTR);
    env->ReleaseByteArrayElements(buffer, bytes, JNI_ABORT);
    if (written < 0) return -errno;
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeResize(
        JNIEnv *, jobject, jint fd, jint columns, jint rows) {
    if (fd < 0) return -1;
    struct winsize ws{};
    ws.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    return ioctl(fd, TIOCSWINSZ, &ws) == 0 ? 0 : -errno;
}

static jint statusToExitCode(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return status;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeWait(
        JNIEnv *, jobject, jint pid) {
    int status = 0;
    pid_t result;
    do {
        result = waitpid(static_cast<pid_t>(pid), &status, 0);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return -errno;
    return statusToExitCode(status);
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeWaitNoHang(
        JNIEnv *, jobject, jint pid) {
    int status = 0;
    pid_t result;
    do {
        result = waitpid(static_cast<pid_t>(pid), &status, WNOHANG);
    } while (result < 0 && errno == EINTR);
    if (result == 0) return -100000; // still running sentinel
    if (result < 0) {
        if (errno == ECHILD) return -100001; // already reaped/unknown
        return -errno;
    }
    return statusToExitCode(status);
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeKill(
        JNIEnv *, jobject, jint pid, jint signal) {
    if (pid <= 0) return -1;
    if (kill(-pid, signal) == 0) return 0;
    if (kill(pid, signal) == 0) return 0;
    return -errno;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeClose(
        JNIEnv *, jobject, jint fd) {
    if (fd >= 0) close(fd);
}
