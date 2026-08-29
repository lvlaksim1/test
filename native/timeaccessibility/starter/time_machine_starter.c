// SPDX-License-Identifier: Apache-2.0
//
// Process bootstrap design derived from Shizuku's starter.cpp:
// https://github.com/RikkaApps/Shizuku
// Copyright (c) Shizuku contributors.
// Modified for the Time Machine application.

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

#define SHELL_UID 2000

static const char *arg_value(int argc, char **argv, const char *prefix) {
    size_t prefix_len = strlen(prefix);
    int i;
    for (i = 1; i < argc; ++i) {
        if (strncmp(argv[i], prefix, prefix_len) == 0) {
            return argv[i] + prefix_len;
        }
    }
    return NULL;
}

static void redirect_standard_streams(void) {
    int fd = open("/dev/null", O_RDWR);
    if (fd < 0) return;
    dup2(fd, STDIN_FILENO);
    dup2(fd, STDOUT_FILENO);
    dup2(fd, STDERR_FILENO);
    if (fd > STDERR_FILENO) close(fd);
}

static void run_server(
        const char *apk,
        const char *main_class,
        const char *process_name,
        const char *package_name,
        const char *authority,
        const char *app_uid) {
    char java_class_path[PATH_MAX + 32];
    char nice_name[256];
    char *server_argv[9];

    if (setenv("CLASSPATH", apk, 1) != 0) _exit(3);

    snprintf(java_class_path, sizeof(java_class_path), "-Djava.class.path=%s", apk);
    snprintf(nice_name, sizeof(nice_name), "--nice-name=%s", process_name);

    server_argv[0] = "/system/bin/app_process";
    server_argv[1] = java_class_path;
    server_argv[2] = "/system/bin";
    server_argv[3] = nice_name;
    server_argv[4] = (char *) main_class;
    server_argv[5] = (char *) package_name;
    server_argv[6] = (char *) authority;
    server_argv[7] = (char *) app_uid;
    server_argv[8] = NULL;

    execv(server_argv[0], server_argv);
    _exit(5);
}

int main(int argc, char **argv) {
    const char *apk;
    const char *main_class;
    const char *process_name;
    const char *package_name;
    const char *authority;
    const char *app_uid;
    pid_t pid;

    if (getuid() != SHELL_UID) {
        fprintf(stderr, "fatal: starter must run as adb shell uid=2000 (uid=%d)\n", getuid());
        return 6;
    }

    apk = arg_value(argc, argv, "--apk=");
    main_class = arg_value(argc, argv, "--class=");
    process_name = arg_value(argc, argv, "--name=");
    package_name = arg_value(argc, argv, "--package=");
    authority = arg_value(argc, argv, "--authority=");
    app_uid = arg_value(argc, argv, "--app-uid=");

    if (!apk || !*apk || !main_class || !*main_class || !process_name || !*process_name ||
        !package_name || !*package_name || !authority || !*authority || !app_uid || !*app_uid) {
        fprintf(stderr, "fatal: missing starter argument\n");
        return 2;
    }

    if (access(apk, R_OK) != 0) {
        fprintf(stderr, "fatal: apk is not readable: %s\n", apk);
        return 7;
    }

    pid = fork();
    if (pid < 0) {
        fprintf(stderr, "fatal: fork failed: %s\n", strerror(errno));
        return 4;
    }

    if (pid == 0) {
        if (setsid() < 0) _exit(4);
        chdir("/");
        redirect_standard_streams();
        run_server(apk, main_class, process_name, package_name, authority, app_uid);
    }

    printf("info: time_machine_server pid is %d\n", pid);
    printf("info: time_machine_starter exit with 0\n");
    fflush(stdout);
    return 0;
}
