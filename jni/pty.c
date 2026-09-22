// Minimal PTY shim: fork a child on a pseudo-terminal and hand the master fd
// back to Java as a FileDescriptor. Same idea as ConnectBot/ASE's
// com_google_ase_Exec, trimmed to what PyRunner needs. Only libc is used.
#include <jni.h>

#include <fcntl.h>
#include <pty.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>

static char **to_vector(JNIEnv *env, jobjectArray array) {
    if (array == NULL) {
        return NULL;
    }
    jsize n = (*env)->GetArrayLength(env, array);
    char **out = malloc(sizeof(char *) * (n + 1));
    if (out == NULL) {
        return NULL;
    }
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, s, utf);
        (*env)->DeleteLocalRef(env, s);
    }
    out[n] = NULL;
    return out;
}

static void free_vector(char **v) {
    if (v == NULL) {
        return;
    }
    for (char **p = v; *p != NULL; p++) {
        free(*p);
    }
    free(v);
}

JNIEXPORT jobject JNICALL
Java_local_pyrunner_Pty_createSubprocess(JNIEnv *env, jclass clazz, jobjectArray argv,
                                         jstring cwd, jobjectArray envv, jint rows,
                                         jint cols, jintArray pidOut) {
    char **cargv = to_vector(env, argv);
    char **cenvv = to_vector(env, envv);
    const char *ccwd = (*env)->GetStringUTFChars(env, cwd, NULL);

    struct winsize ws = {(unsigned short) rows, (unsigned short) cols, 0, 0};
    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);

    if (pid == 0) {
        if (ccwd != NULL) {
            chdir(ccwd);
        }
        execve(cargv[0], cargv, cenvv);
        _exit(127);
    }

    if (ccwd != NULL) {
        (*env)->ReleaseStringUTFChars(env, cwd, ccwd);
    }
    free_vector(cargv);
    free_vector(cenvv);

    if (pid < 0) {
        return NULL;
    }

    jint values[2] = {(jint) pid, (jint) master};
    (*env)->SetIntArrayRegion(env, pidOut, 0, 2, values);

    jclass fdClass = (*env)->FindClass(env, "java/io/FileDescriptor");
    jmethodID ctor = (*env)->GetMethodID(env, fdClass, "<init>", "()V");
    jobject fd = (*env)->NewObject(env, fdClass, ctor);
    jfieldID descriptor = (*env)->GetFieldID(env, fdClass, "descriptor", "I");
    (*env)->SetIntField(env, fd, descriptor, master);
    return fd;
}

JNIEXPORT void JNICALL
Java_local_pyrunner_Pty_setWinSize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize ws = {(unsigned short) rows, (unsigned short) cols, 0, 0};
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_local_pyrunner_Pty_waitForPid(JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    if (waitpid((pid_t) pid, &status, 0) < 0) {
        return -1;
    }
    return WIFEXITED(status) ? WEXITSTATUS(status) : -1;
}
