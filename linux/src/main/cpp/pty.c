/*
 * Atom - pseudo-terminal helper.
 * Copyright (C) 2026 namanwtf
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Opens a pty pair, forks a child that becomes a session leader with the slave as its controlling
 * terminal, and execs the requested program there. The Kotlin side owns the master fd and does all
 * reads on one blocking thread, so an idle terminal costs no CPU.
 */
#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#ifndef __NR_close_range
#define __NR_close_range 436
#endif

static void throw_io(JNIEnv *env, const char *what, int err) {
    char msg[256];
    snprintf(msg, sizeof msg, "%s: %s", what, strerror(err));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, msg);
}

/* Copies a Java String[] into a NULL-terminated, malloc'd C array. Returns NULL on OOM. */
static char **to_c_array(JNIEnv *env, jobjectArray array) {
    jsize n = array == NULL ? 0 : (*env)->GetArrayLength(env, array);
    char **out = calloc((size_t) n + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *utf = s == NULL ? "" : (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(utf == NULL ? "" : utf);
        if (s != NULL && utf != NULL) (*env)->ReleaseStringUTFChars(env, s, utf);
        if (s != NULL) (*env)->DeleteLocalRef(env, s);
        if (out[i] == NULL) return out; /* truncated; caller frees */
    }
    return out;
}

static void free_c_array(char **array) {
    if (array == NULL) return;
    for (char **p = array; *p != NULL; p++) free(*p);
    free(array);
}

static void close_from(int lowest) {
    if (syscall(__NR_close_range, (unsigned) lowest, ~0U, 0) == 0) return;
    struct rlimit rl;
    long max = (getrlimit(RLIMIT_NOFILE, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY) ? (long) rl.rlim_cur : 4096;
    if (max > 65536) max = 65536;
    for (long fd = lowest; fd < max; fd++) close((int) fd);
}

JNIEXPORT jint JNICALL
Java_atom_linux_pty_Pty_spawn(JNIEnv *env, jclass clazz, jstring jpath, jobjectArray jargv, jobjectArray jenvp,
                              jstring jcwd, jint rows, jint cols, jintArray jresult) {
    (void) clazz;
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) {
        throw_io(env, "posix_openpt", errno);
        return -1;
    }
    char slave_name[128];
    if (grantpt(master) != 0 || unlockpt(master) != 0 || ptsname_r(master, slave_name, sizeof slave_name) != 0) {
        int err = errno;
        close(master);
        throw_io(env, "pty setup", err);
        return -1;
    }

    struct winsize ws = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(master, TIOCSWINSZ, &ws);

    /* UTF-8 aware line editing (erase removes a whole character), DEL as erase. */
    struct termios tio;
    if (tcgetattr(master, &tio) == 0) {
        tio.c_iflag |= IUTF8;
        tio.c_cc[VERASE] = 0x7f;
        tcsetattr(master, TCSANOW, &tio);
    }

    const char *path_utf = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *cwd_utf = jcwd == NULL ? NULL : (*env)->GetStringUTFChars(env, jcwd, NULL);
    char *path = strdup(path_utf);
    char *cwd = cwd_utf == NULL ? NULL : strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, jpath, path_utf);
    if (cwd_utf != NULL) (*env)->ReleaseStringUTFChars(env, jcwd, cwd_utf);
    char **argv = to_c_array(env, jargv);
    char **envp = to_c_array(env, jenvp);
    if (path == NULL || argv == NULL || envp == NULL) {
        free(path); free(cwd); free_c_array(argv); free_c_array(envp);
        close(master);
        throw_io(env, "spawn", ENOMEM);
        return -1;
    }

    /* A self-pipe lets close() wake the reader and any blocked writer without polling. */
    int wake[2];
    if (pipe2(wake, O_CLOEXEC) != 0) {
        int err = errno;
        free(path); free(cwd); free_c_array(argv); free_c_array(envp);
        close(master);
        throw_io(env, "pipe", err);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        int err = errno;
        free(path); free(cwd); free_c_array(argv); free_c_array(envp);
        close(master);
        close(wake[0]);
        close(wake[1]);
        throw_io(env, "fork", err);
        return -1;
    }

    if (pid == 0) {
        /* Child: only async-signal-safe calls from here on. */
        sigset_t all;
        sigemptyset(&all);
        sigprocmask(SIG_SETMASK, &all, NULL);
        for (int sig = 1; sig < NSIG; sig++) signal(sig, SIG_DFL);

        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(126);
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        close_from(3);

        if (cwd != NULL && chdir(cwd) != 0) {
            if (chdir("/") != 0) { /* ignore */ }
        }
        execvpe(path, argv, envp);

        const char *prefix = "atom: cannot run ";
        const char *reason = strerror(errno);
        write(2, prefix, strlen(prefix));
        write(2, path, strlen(path));
        write(2, ": ", 2);
        write(2, reason, strlen(reason));
        write(2, "\r\n", 2);
        _exit(127);
    }

    free(path); free(cwd); free_c_array(argv); free_c_array(envp);
    fcntl(master, F_SETFL, fcntl(master, F_GETFL) | O_NONBLOCK);
    jint result[3] = {pid, wake[0], wake[1]};
    (*env)->SetIntArrayRegion(env, jresult, 0, 3, result);
    return master;
}

/*
 * Non-interactive spawn: stdin is /dev/null, stdout/stderr are pipes. The child calls setsid() so the
 * whole process tree can be killed as one group on timeout. result = [pid, stdoutFd, stderrFd].
 */
JNIEXPORT void JNICALL
Java_atom_linux_pty_Pty_spawnPiped(JNIEnv *env, jclass clazz, jstring jpath, jobjectArray jargv, jobjectArray jenvp,
                                   jstring jcwd, jintArray jresult) {
    (void) clazz;
    int out[2], err[2];
    if (pipe2(out, O_CLOEXEC) != 0) {
        throw_io(env, "pipe", errno);
        return;
    }
    if (pipe2(err, O_CLOEXEC) != 0) {
        int e = errno;
        close(out[0]); close(out[1]);
        throw_io(env, "pipe", e);
        return;
    }
    const char *path_utf = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *cwd_utf = jcwd == NULL ? NULL : (*env)->GetStringUTFChars(env, jcwd, NULL);
    char *path = strdup(path_utf);
    char *cwd = cwd_utf == NULL ? NULL : strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, jpath, path_utf);
    if (cwd_utf != NULL) (*env)->ReleaseStringUTFChars(env, jcwd, cwd_utf);
    char **argv = to_c_array(env, jargv);
    char **envp = to_c_array(env, jenvp);

    pid_t pid = (path == NULL || argv == NULL || envp == NULL) ? -1 : fork();
    if (pid < 0) {
        int e = pid == -1 && path != NULL && argv != NULL && envp != NULL ? errno : ENOMEM;
        free(path); free(cwd); free_c_array(argv); free_c_array(envp);
        close(out[0]); close(out[1]); close(err[0]); close(err[1]);
        throw_io(env, "fork", e);
        return;
    }
    if (pid == 0) {
        sigset_t all;
        sigemptyset(&all);
        sigprocmask(SIG_SETMASK, &all, NULL);
        for (int sig = 1; sig < NSIG; sig++) signal(sig, SIG_DFL);
        setsid();
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) dup2(devnull, 0);
        dup2(out[1], 1);
        dup2(err[1], 2);
        close_from(3);
        if (cwd != NULL && chdir(cwd) != 0) {
            if (chdir("/") != 0) { /* ignore */ }
        }
        execvpe(path, argv, envp);
        const char *prefix = "atom: cannot run ";
        const char *reason = strerror(errno);
        write(2, prefix, strlen(prefix));
        write(2, path, strlen(path));
        write(2, ": ", 2);
        write(2, reason, strlen(reason));
        write(2, "\n", 1);
        _exit(127);
    }
    free(path); free(cwd); free_c_array(argv); free_c_array(envp);
    close(out[1]);
    close(err[1]);
    jint result[3] = {pid, out[0], err[0]};
    (*env)->SetIntArrayRegion(env, jresult, 0, 3, result);
}

/*
 * Background spawn: stdin /dev/null, stdout and stderr appended to [jlog]. The child is its own session (and
 * process group), so stopping the job kills the whole tree. Nothing reads the output while it runs: the log is
 * the record. Returns the pid.
 */
JNIEXPORT jint JNICALL
Java_atom_linux_pty_Pty_spawnToFile(JNIEnv *env, jclass clazz, jstring jpath, jobjectArray jargv, jobjectArray jenvp,
                                    jstring jcwd, jstring jlog) {
    (void) clazz;
    const char *log_utf = (*env)->GetStringUTFChars(env, jlog, NULL);
    int logfd = open(log_utf, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    (*env)->ReleaseStringUTFChars(env, jlog, log_utf);
    if (logfd < 0) {
        throw_io(env, "open log", errno);
        return -1;
    }
    const char *path_utf = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *cwd_utf = jcwd == NULL ? NULL : (*env)->GetStringUTFChars(env, jcwd, NULL);
    char *path = strdup(path_utf);
    char *cwd = cwd_utf == NULL ? NULL : strdup(cwd_utf);
    (*env)->ReleaseStringUTFChars(env, jpath, path_utf);
    if (cwd_utf != NULL) (*env)->ReleaseStringUTFChars(env, jcwd, cwd_utf);
    char **argv = to_c_array(env, jargv);
    char **envp = to_c_array(env, jenvp);
    int ok = path != NULL && argv != NULL && envp != NULL;
    pid_t pid = ok ? fork() : -1;
    if (pid < 0) {
        int e = ok ? errno : ENOMEM;
        free(path); free(cwd); free_c_array(argv); free_c_array(envp);
        close(logfd);
        throw_io(env, "fork", e);
        return -1;
    }
    if (pid == 0) {
        sigset_t all;
        sigemptyset(&all);
        sigprocmask(SIG_SETMASK, &all, NULL);
        for (int sig = 1; sig < NSIG; sig++) signal(sig, SIG_DFL);
        setsid();
        int devnull = open("/dev/null", O_RDONLY);
        if (devnull >= 0) dup2(devnull, 0);
        dup2(logfd, 1);
        dup2(logfd, 2);
        close_from(3);
        if (cwd != NULL && chdir(cwd) != 0) {
            if (chdir("/") != 0) { /* ignore */ }
        }
        execvpe(path, argv, envp);
        const char *prefix = "atom: cannot run ";
        const char *reason = strerror(errno);
        write(2, prefix, strlen(prefix));
        write(2, path, strlen(path));
        write(2, ": ", 2);
        write(2, reason, strlen(reason));
        write(2, "\n", 1);
        _exit(127);
    }
    free(path); free(cwd); free_c_array(argv); free_c_array(envp);
    close(logfd);
    return pid;
}

JNIEXPORT void JNICALL
Java_atom_linux_pty_Pty_setSize(JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    (void) env; (void) clazz;
    struct winsize ws = {.ws_row = (unsigned short) rows, .ws_col = (unsigned short) cols};
    ioctl(fd, TIOCSWINSZ, &ws);
}

/* Waits for [events] on fd or for the wake pipe. Returns 1 when fd is ready, 0 when woken, -1 on error. */
static int wait_ready(int fd, short events, int wake_fd) {
    struct pollfd fds[2] = {{.fd = fd, .events = events}, {.fd = wake_fd, .events = POLLIN}};
    for (;;) {
        int rc = poll(fds, 2, -1);
        if (rc < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (fds[1].revents != 0) return 0;
        if (fds[0].revents != 0) return 1;
    }
}

/*
 * Blocks (in poll, not spinning) until data arrives. Returns the byte count, or -1 once the slave side is
 * gone (EIO), on error, or when [wake_fd] was signalled by close().
 */
JNIEXPORT jint JNICALL
Java_atom_linux_pty_Pty_read(JNIEnv *env, jclass clazz, jint fd, jint wake_fd, jbyteArray jbuf, jint len) {
    (void) clazz;
    char buf[16384];
    if (len > (jint) sizeof buf) len = sizeof buf;
    for (;;) {
        ssize_t n = read(fd, buf, (size_t) len);
        if (n > 0) {
            (*env)->SetByteArrayRegion(env, jbuf, 0, (jsize) n, (const jbyte *) buf);
            return (jint) n;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (wait_ready(fd, POLLIN, wake_fd) != 1) return -1;
            continue;
        }
        return -1;
    }
}

/* Writes everything, waiting for room when the pty buffer is full. Returns 0, or -errno (-EPIPE when woken). */
JNIEXPORT jint JNICALL
Java_atom_linux_pty_Pty_write(JNIEnv *env, jclass clazz, jint fd, jint wake_fd, jbyteArray jbuf, jint off, jint len) {
    (void) clazz;
    jbyte *bytes = (*env)->GetByteArrayElements(env, jbuf, NULL);
    if (bytes == NULL) return -ENOMEM;
    const char *p = (const char *) bytes + off;
    jint left = len;
    int rc = 0;
    while (left > 0) {
        ssize_t n = write(fd, p, (size_t) left);
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                if (wait_ready(fd, POLLOUT, wake_fd) != 1) { rc = -EPIPE; break; }
                continue;
            }
            rc = -errno;
            break;
        }
        p += n;
        left -= (jint) n;
    }
    (*env)->ReleaseByteArrayElements(env, jbuf, bytes, JNI_ABORT);
    return rc;
}

/* Makes the wake pipe readable for good, releasing any reader or writer blocked on this session. */
JNIEXPORT void JNICALL
Java_atom_linux_pty_Pty_wake(JNIEnv *env, jclass clazz, jint wake_write_fd) {
    (void) env; (void) clazz;
    char one = 1;
    while (write(wake_write_fd, &one, 1) < 0 && errno == EINTR) { /* retry */ }
}

/* Blocks until the child exits. Exit status, or 128 + signal number. */
JNIEXPORT jint JNICALL
Java_atom_linux_pty_Pty_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/* Signals the child, or its whole process group (the child is a session leader, so pgid == pid). */
JNIEXPORT void JNICALL
Java_atom_linux_pty_Pty_signal(JNIEnv *env, jclass clazz, jint pid, jint sig, jboolean group) {
    (void) env; (void) clazz;
    if (pid <= 0) return;
    kill(group ? -pid : pid, sig);
}

JNIEXPORT void JNICALL
Java_atom_linux_pty_Pty_close(JNIEnv *env, jclass clazz, jint fd) {
    (void) env; (void) clazz;
    if (fd >= 0) close(fd);
}
