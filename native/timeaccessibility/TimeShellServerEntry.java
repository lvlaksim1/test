// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility;

import android.os.Process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Minimal Java entry point for app_process, intentionally independent of React/Kotlin runtime. */
public final class TimeShellServerEntry {
    private TimeShellServerEntry() {}

    public static void main(String[] args) throws Exception {
        if (args == null || args.length < 2) return;
        String token = args[0];
        int port;
        try { port = Integer.parseInt(args[1]); } catch (Throwable ignored) { return; }
        if (token.length() != 64 || port < 1024 || port > 65535 || Process.myUid() != 2000) return;

        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
        System.err.println("READY uid=" + Process.myUid() + " port=" + port);
        System.err.flush();

        while (true) {
            Socket socket = null;
            try {
                socket = server.accept();
                handle(socket, token);
            } catch (Throwable failure) {
                System.err.println("CLIENT_ERROR " + clean(String.valueOf(failure)));
                System.err.flush();
            } finally {
                if (socket != null) try { socket.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void handle(Socket socket, String token) throws Exception {
        socket.setSoTimeout(5000);
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        if (line == null) return;
        String[] parts = line.split("\\t", 3);
        if (parts.length < 2 || !token.equals(parts[0])) {
            reply(writer, false, "unauthorized");
            return;
        }

        String command = parts[1];
        String argument = parts.length >= 3 ? parts[2] : "";
        Result result;
        switch (command) {
            case "PING": result = new Result(Process.myUid() == 2000, "uid=" + Process.myUid()); break;
            case "SET_TIME": result = setTime(argument); break;
            case "AUTO_TIME": result = setAutomaticTime(argument); break;
            case "WIFI": result = setWifi(argument); break;
            default: result = new Result(false, "unknown command"); break;
        }
        reply(writer, result.success, result.detail);
    }

    private static Result setTime(String value) {
        long target;
        try { target = Long.parseLong(value); } catch (Throwable ignored) { return new Result(false, "invalid time"); }
        if (target <= 0) return new Result(false, "invalid time");

        CommandResult autoOff = shell("settings put global auto_time 0");
        CommandResult alarm = shell("cmd alarm set-time " + target);
        CommandResult set = alarm.exitCode == 0 ? alarm : shell("date -s @" + (target / 1000L));
        CommandResult automatic = shell("settings get global auto_time");
        CommandResult now = shell("date +%s");
        long current = -1;
        try { current = Long.parseLong(now.output.trim()) * 1000L; } catch (Throwable ignored) {}

        boolean ok = autoOff.exitCode == 0 && set.exitCode == 0 && "0".equals(automatic.output.trim()) && current > 0 && Math.abs(current - target) <= 90000L;
        return ok ? new Result(true, "time set") : new Result(false, "auto=" + clean(automatic.output) + " alarm=" + alarm.exitCode + " set=" + set.exitCode + " now=" + current);
    }

    private static Result setAutomaticTime(String value) {
        if (!"0".equals(value) && !"1".equals(value)) return new Result(false, "invalid auto_time");
        CommandResult change = shell("settings put global auto_time " + value);
        CommandResult actual = shell("settings get global auto_time");
        boolean ok = change.exitCode == 0 && value.equals(actual.output.trim());
        return ok ? new Result(true, "auto_time=" + value) : new Result(false, "auto=" + clean(actual.output) + " exit=" + change.exitCode);
    }

    private static Result setWifi(String value) {
        String action;
        if ("0".equals(value)) action = "disable";
        else if ("1".equals(value)) action = "enable";
        else return new Result(false, "invalid wifi");
        CommandResult result = shell("svc wifi " + action);
        return result.exitCode == 0 ? new Result(true, "wifi=" + value) : new Result(false, "wifi exit=" + result.exitCode);
    }

    private static CommandResult shell(String command) {
        try {
            java.lang.Process process = new ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) output.append('\n');
                output.append(line);
            }
            int exit = process.waitFor();
            return new CommandResult(exit, output.toString());
        } catch (Throwable failure) {
            return new CommandResult(-1, String.valueOf(failure));
        }
    }

    private static void reply(BufferedWriter writer, boolean success, String detail) throws Exception {
        writer.write(success ? "OK\t" : "ERR\t");
        writer.write(clean(detail));
        writer.newLine();
        writer.flush();
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.substring(0, Math.min(cleaned.length(), 400));
    }

    private static final class Result {
        final boolean success;
        final String detail;
        Result(boolean success, String detail) { this.success = success; this.detail = detail; }
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;
        CommandResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output == null ? "" : output; }
    }
}
