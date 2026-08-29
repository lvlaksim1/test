// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility

import android.os.Process
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.abs

/**
 * Detached process started by adb shell with app_process.
 * It keeps uid=2000 and survives the normal application process being killed.
 */
object TimeShellServer {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) return
        val token = args[0]
        val port = args[1].toIntOrNull() ?: return
        if (token.length != 64 || port !in 1024..65535 || Process.myUid() != 2000) return

        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
        while (true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            runCatching { handle(socket, token) }
            runCatching { socket.close() }
        }
    }

    private fun handle(socket: Socket, token: String) {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
        val line = reader.readLine() ?: return
        val parts = line.split('\t', limit = 3)
        if (parts.size < 2 || parts[0] != token) {
            writeReply(writer, false, "unauthorized")
            return
        }

        val command = parts[1]
        val argument = if (parts.size >= 3) parts[2] else ""
        val reply = when (command) {
            "PING" -> ServerReply(Process.myUid() == 2000, "uid=${Process.myUid()}")
            "SET_TIME" -> setTime(argument)
            "AUTO_TIME" -> setAutomaticTime(argument)
            "WIFI" -> setWifi(argument)
            else -> ServerReply(false, "unknown command")
        }
        writeReply(writer, reply.success, reply.detail)
    }

    private fun setTime(argument: String): ServerReply {
        val targetMillis = argument.toLongOrNull() ?: return ServerReply(false, "invalid time")
        if (targetMillis <= 0L) return ServerReply(false, "invalid time")

        val autoOff = shell("settings put global auto_time 0")
        val alarm = shell("cmd alarm set-time $targetMillis")
        val setResult = if (alarm.exitCode == 0) alarm else shell("date -s @${targetMillis / 1000L}")
        val automatic = shell("settings get global auto_time")
        val now = shell("date +%s")
        val currentMillis = now.stdout.trim().toLongOrNull()?.times(1000L)
        val verified = autoOff.exitCode == 0 && setResult.exitCode == 0 && automatic.stdout.trim() == "0" &&
            currentMillis != null && abs(currentMillis - targetMillis) <= 90_000L

        return if (verified) {
            ServerReply(true, "time set")
        } else {
            ServerReply(false, "auto=${clean(automatic.stdout)} alarm=${alarm.exitCode} set=${setResult.exitCode} now=${currentMillis ?: -1}")
        }
    }

    private fun setAutomaticTime(argument: String): ServerReply {
        if (argument != "0" && argument != "1") return ServerReply(false, "invalid auto_time")
        val change = shell("settings put global auto_time $argument")
        val actual = shell("settings get global auto_time")
        val verified = change.exitCode == 0 && actual.stdout.trim() == argument
        return if (verified) ServerReply(true, "auto_time=$argument") else ServerReply(false, "auto=${clean(actual.stdout)} exit=${change.exitCode}")
    }

    private fun setWifi(argument: String): ServerReply {
        val action = when (argument) {
            "0" -> "disable"
            "1" -> "enable"
            else -> return ServerReply(false, "invalid wifi")
        }
        val result = shell("svc wifi $action")
        return if (result.exitCode == 0) ServerReply(true, "wifi=$argument") else ServerReply(false, "wifi exit=${result.exitCode}")
    }

    private fun shell(command: String): CommandResult {
        return runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val exit = process.waitFor()
            CommandResult(exit, output.trim())
        }.getOrElse { CommandResult(-1, it.message.orEmpty()) }
    }

    private fun writeReply(writer: java.io.BufferedWriter, success: Boolean, detail: String) {
        writer.write(if (success) "OK\t" else "ERR\t")
        writer.write(clean(detail))
        writer.newLine()
        writer.flush()
    }

    private fun clean(value: String): String = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(300)
    private data class ServerReply(val success: Boolean, val detail: String)
    private data class CommandResult(val exitCode: Int, val stdout: String)
}
