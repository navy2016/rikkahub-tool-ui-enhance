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
#include <algorithm>

#define LOG_TAG "RikkahubPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void configureCookedPty(struct termios &tio) {
    tio.c_iflag |= ICRNL | IXON;
#ifdef IXANY
    tio.c_iflag |= IXANY;
#endif
    tio.c_oflag |= OPOST | ONLCR;
    tio.c_lflag |= ISIG | ICANON | ECHO;
#ifdef ECHOE
    tio.c_lflag |= ECHOE;
#endif
#ifdef ECHOK
    tio.c_lflag |= ECHOK;
#endif
}

static void configureRawPty(struct termios &tio) {
    cfmakeraw(&tio);
    // Keep terminal-generated signals working for Ctrl+C/Ctrl+Z/Ctrl+\ while
    // leaving input bytes untouched: no ICRNL/INLCR/IGNCR, no ICANON/ECHO,
    // no OPOST/ONLCR. This makes Enter arrive as the exact byte written by
    // the app, which is required by Claude Code and other full-screen TUIs.
    tio.c_lflag |= ISIG;
#ifdef IXON
    tio.c_iflag &= ~IXON;
#endif
#ifdef IXANY
    tio.c_iflag &= ~IXANY;
#endif
}

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
        JNIEnv *env, jobject, jobjectArray argvArray, jobjectArray envArray, jint columns, jint rows, jint ptyMode) {
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
    ws.ws_col = static_cast<unsigned short>(std::max(1, std::min(300, static_cast<int>(columns))));
    ws.ws_row = static_cast<unsigned short>(std::max(1, std::min(120, static_cast<int>(rows))));
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
        struct termios tio{};
        if (tcgetattr(slaveFd, &tio) == 0) {
            if (ptyMode == 1) configureRawPty(tio);
            else configureCookedPty(tio);
            tcsetattr(slaveFd, TCSANOW, &tio);
        }
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
        JNIEnv *env, jobject, jint fd, jbyteArray buffer, jint offset, jint length) {
    if (fd < 0 || !buffer || offset < 0 || length <= 0) return -1;
    jsize capacity = env->GetArrayLength(buffer);
    if (offset >= capacity) return -1;
    if (length > capacity - offset) length = capacity - offset;
    jbyte *bytes = env->GetByteArrayElements(buffer, nullptr);
    ssize_t readBytes;
    do {
        readBytes = read(fd, bytes + offset, static_cast<size_t>(length));
    } while (readBytes < 0 && errno == EINTR);
    env->ReleaseByteArrayElements(buffer, bytes, 0);
    if (readBytes < 0) return -errno;
    return static_cast<jint>(readBytes);
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeWrite(
        JNIEnv *env, jobject, jint fd, jbyteArray buffer, jint offset, jint length) {
    if (fd < 0 || !buffer || offset < 0 || length <= 0) return -1;
    jsize capacity = env->GetArrayLength(buffer);
    if (offset >= capacity) return -1;
    if (length > capacity - offset) length = capacity - offset;
    jbyte *bytes = env->GetByteArrayElements(buffer, nullptr);
    ssize_t written;
    do {
        written = write(fd, bytes + offset, static_cast<size_t>(length));
    } while (written < 0 && errno == EINTR);
    env->ReleaseByteArrayElements(buffer, bytes, JNI_ABORT);
    if (written < 0) return -errno;
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeDrain(
        JNIEnv *env, jobject, jint fd, jint maxBytes) {
    if (fd < 0 || maxBytes <= 0) return env->NewByteArray(0);
    int oldFlags = fcntl(fd, F_GETFL, 0);
    if (oldFlags >= 0) fcntl(fd, F_SETFL, oldFlags | O_NONBLOCK);

    std::vector<jbyte> out;
    out.reserve(static_cast<size_t>(maxBytes > 4096 ? 4096 : maxBytes));
    char chunk[512];
    while (static_cast<jint>(out.size()) < maxBytes) {
        size_t want = sizeof(chunk);
        jint remaining = maxBytes - static_cast<jint>(out.size());
        if (remaining < static_cast<jint>(want)) want = static_cast<size_t>(remaining);
        ssize_t n = read(fd, chunk, want);
        if (n > 0) {
            out.insert(out.end(), reinterpret_cast<jbyte *>(chunk), reinterpret_cast<jbyte *>(chunk + n));
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        break;
    }

    if (oldFlags >= 0) fcntl(fd, F_SETFL, oldFlags);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (!out.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_rikkahub_data_container_NativePtyBridge_nativeResize(
        JNIEnv *, jobject, jint fd, jint columns, jint rows) {
    if (fd < 0) return -1;
    struct winsize ws{};
    ws.ws_col = static_cast<unsigned short>(std::max(1, std::min(300, static_cast<int>(columns))));
    ws.ws_row = static_cast<unsigned short>(std::max(1, std::min(120, static_cast<int>(rows))));
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
