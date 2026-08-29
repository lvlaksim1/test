// SPDX-License-Identifier: Apache-2.0
//
// Process bootstrap design derived from Shizuku's starter.cpp:
// https://github.com/RikkaApps/Shizuku
// Copyright (c) Shizuku contributors.
// Modified for the Time Machine application.

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/types.h>
#include <unistd.h>

namespace {

constexpr uid_t kShellUid = 2000;

std::string arg_value(int argc, char **argv, const char *prefix) {
    const size_t prefix_len = std::strlen(prefix);
    for (int i = 1; i < argc; ++i) {
        if (std::strncmp(argv[i], prefix, prefix_len) == 0) {
            return std::string(argv[i] + prefix_len);
        }
    }
    return {};
}

void redirect_standard_streams() {
    int fd = open("/dev/null", O_RDWR);
    if (fd < 0) return;
    dup2(fd, STDIN_FILENO);
    dup2(fd, STDOUT_FILENO);
    dup2(fd, STDERR_FILENO);
    if (fd > STDERR_FILENO) close(fd);
}

[[noreturn]] void run_server(
        const std::string &apk,
        const std::string &main_class,
        const std::string &process_name,
        const std::string &package_name,
        const std::string &authority,
        const std::string &app_uid) {
    if (setenv("CLASSPATH", apk.c_str(), 1) != 0) _exit(3);

    std::string java_class_path = "-Djava.class.path=" + apk;
    std::string nice_name = "--nice-name=" + process_name;

    const char *argv[] = {
            "/system/bin/app_process",
            java_class_path.c_str(),
            "/system/bin",
            nice_name.c_str(),
            main_class.c_str(),
            package_name.c_str(),
            authority.c_str(),
            app_uid.c_str(),
            nullptr,
    };

    execv(argv[0], const_cast<char *const *>(argv));
    _exit(5);
}

}  // namespace

int main(int argc, char **argv) {
    if (getuid() != kShellUid) {
        std::fprintf(stderr, "fatal: starter must run as adb shell uid=2000 (uid=%d)\n", getuid());
        return 6;
    }

    const std::string apk = arg_value(argc, argv, "--apk=");
    const std::string main_class = arg_value(argc, argv, "--class=");
    const std::string process_name = arg_value(argc, argv, "--name=");
    const std::string package_name = arg_value(argc, argv, "--package=");
    const std::string authority = arg_value(argc, argv, "--authority=");
    const std::string app_uid = arg_value(argc, argv, "--app-uid=");

    if (apk.empty() || main_class.empty() || process_name.empty() ||
        package_name.empty() || authority.empty() || app_uid.empty()) {
        std::fprintf(stderr, "fatal: missing starter argument\n");
        return 2;
    }

    if (access(apk.c_str(), R_OK) != 0) {
        std::fprintf(stderr, "fatal: apk is not readable: %s\n", apk.c_str());
        return 7;
    }

    pid_t pid = fork();
    if (pid < 0) {
        std::fprintf(stderr, "fatal: fork failed: %s\n", std::strerror(errno));
        return 4;
    }

    if (pid == 0) {
        if (setsid() < 0) _exit(4);
        chdir("/");
        redirect_standard_streams();
        run_server(apk, main_class, process_name, package_name, authority, app_uid);
    }

    std::printf("info: time_machine_server pid is %d\n", pid);
    std::printf("info: time_machine_starter exit with 0\n");
    std::fflush(stdout);
    return 0;
}
