/* Android host services. All paths belong to the standalone Quest package. */
#include "quest_platform.h"
#include "quest_vr_android.h"
#include <SDL.h>
#include <SDL_system.h>
#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdint.h>

#include <fcntl.h>
#include <signal.h>
#include <ucontext.h>
#include <sys/syscall.h>

/* Write only fixed text/register values from the fatal-signal handler, then
 * re-raise so Android can collect its own tombstone when available. */
static void quest_write_hex(int fd, const char* label, uintptr_t value) {
    static const char digits[] = "0123456789abcdef";
    char line[96];
    size_t length = 0;
    while (*label && length < sizeof(line) - 20) line[length++] = *label++;
    line[length++] = '0'; line[length++] = 'x';
    int started = 0;
    for (int shift = (int)(sizeof(value) * 8) - 4; shift >= 0; shift -= 4) {
        unsigned nibble = (unsigned)((value >> shift) & 15u);
        if (nibble || started || shift == 0) {
            line[length++] = digits[nibble];
            started = 1;
        }
    }
    line[length++] = '\n';
    (void)write(fd, line, length);
}

static void quest_native_crash_handler(int sig, siginfo_t* info, void* context) {
    int fd = open("native-crash.txt", O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd >= 0) {
        static const char header[] = "Animal Crossing Quest native crash capture\n";
        (void)write(fd, header, sizeof(header) - 1);
        quest_write_hex(fd, "signal=", (uintptr_t)sig);
        quest_write_hex(fd, "si_code=", (uintptr_t)(info ? info->si_code : 0));
        quest_write_hex(fd, "fault_address=", (uintptr_t)(info ? info->si_addr : NULL));
        quest_write_hex(fd, "pid=", (uintptr_t)getpid());
        quest_write_hex(fd, "tid=", (uintptr_t)syscall(SYS_gettid));
#if defined(__aarch64__)
        if (context) {
            ucontext_t* uc = (ucontext_t*)context;
            quest_write_hex(fd, "pc=", (uintptr_t)uc->uc_mcontext.pc);
            quest_write_hex(fd, "lr=", (uintptr_t)uc->uc_mcontext.regs[30]);
            quest_write_hex(fd, "sp=", (uintptr_t)uc->uc_mcontext.sp);
            quest_write_hex(fd, "fp=", (uintptr_t)uc->uc_mcontext.regs[29]);
        }
#elif defined(__arm__)
        if (context) {
            ucontext_t* uc = (ucontext_t*)context;
            quest_write_hex(fd, "pc=", (uintptr_t)uc->uc_mcontext.arm_pc);
            quest_write_hex(fd, "lr=", (uintptr_t)uc->uc_mcontext.arm_lr);
            quest_write_hex(fd, "sp=", (uintptr_t)uc->uc_mcontext.arm_sp);
            quest_write_hex(fd, "fp=", (uintptr_t)uc->uc_mcontext.arm_fp);
        }
#endif
        (void)fsync(fd);
        (void)close(fd);
    }
    struct sigaction reset_action;
    reset_action.sa_handler = SIG_DFL;
    sigemptyset(&reset_action.sa_mask);
    reset_action.sa_flags = 0;
    (void)sigaction(sig, &reset_action, NULL);
    (void)syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), sig);
    _exit(128 + sig);
}

static void quest_install_native_crash_handler(void) {
    const int signals[] = {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT};
    struct sigaction action;
    action.sa_sigaction = quest_native_crash_handler;
    sigemptyset(&action.sa_mask);
    action.sa_flags = SA_SIGINFO | SA_RESETHAND;
    for (unsigned i = 0; i < sizeof(signals) / sizeof(signals[0]); ++i)
        (void)sigaction(signals[i], &action, NULL);
}

int quest_platform_prepare(void) {
    quest_install_native_crash_handler();
    const char* root = SDL_AndroidGetInternalStoragePath();
    if (!root || chdir(root) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "ACQuest", "Cannot open app data root");
        return 0;
    }
    /* Internal app storage has stable ownership; the development build's
     * run-as transfer helper imports copies without shared-storage permissions. */
    const char* directories[] = {"rom", "save", "save/card_a", "save/card_b", "shaders"};
    for (unsigned i = 0; i < sizeof(directories) / sizeof(directories[0]); ++i) {
        mkdir(directories[i], 0700);
    }
    freopen("quest-stdout.log", "w", stdout);
    freopen("quest-stderr.log", "w", stderr);
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    printf("[Quest] data=%s pointer_bytes=%u\n", root, (unsigned)sizeof(void*));
    __android_log_print(ANDROID_LOG_INFO, "ACQuest", "Starting in %s", root);
    return 1;
}

void quest_platform_bind_vr(void) {
    JNIEnv* env = (JNIEnv*)SDL_AndroidGetJNIEnv();
    jobject activity = (jobject)SDL_AndroidGetActivity();
    JavaVM* vm = NULL;
    if (!env || !activity || (*env)->GetJavaVM(env, &vm) != JNI_OK) return;
    /* Backend retains its own global reference for the XR session lifetime. */
    quest_vr_set_android(vm, activity);
    (*env)->DeleteLocalRef(env, activity);
    quest_vr_set_resumed(1);
}

void quest_platform_lifecycle(unsigned int event_type) {
    extern void pc_audio_set_paused(int paused);
    if (event_type == SDL_RENDER_DEVICE_RESET) {
        extern int g_pc_running;
        __android_log_print(ANDROID_LOG_ERROR, "ACQuest", "GL context was lost; closing for a clean restart");
        g_pc_running = 0;
        quest_vr_set_resumed(0);
        return;
    }
    if (event_type == SDL_APP_WILLENTERBACKGROUND) {
        quest_vr_set_resumed(0);
        pc_audio_set_paused(1);
    } else if (event_type == SDL_APP_DIDENTERFOREGROUND) {
        pc_audio_set_paused(0);
        quest_vr_set_resumed(1);
    }
}
