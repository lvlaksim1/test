// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility

import android.content.Context
import android.os.SystemClock
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Client-side bridge to the detached shell process started once through ADB. */
object TimeShellBridge {
    const val HOST = "127.0.0.1"
    const val PORT = 43721

    private const val PREFS = "time_machine_shell_bridge"
    private const val KEY_TOKEN = "token"
    private const val KEY_ACTIVE = "active"
    private const val KEY_STARTED_ELAPSED = "started_elapsed"

    data class Reply(val success: Boolean, val detail: String)

    fun token(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.getString(KEY_TOKEN, null)?.takeIf { it.length == 64 }?.let { return it }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val value = bytes.joinToString("") { "%02x".format(it) }
        preferences.edit().putString(KEY_TOKEN, value).apply()
        return value
    }

    fun isLikelyActive(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_ACTIVE, false)) return false
        val started = preferences.getLong(KEY_STARTED_ELAPSED, Long.MAX_VALUE)
        return started != Long.MAX_VALUE && SystemClock.elapsedRealtime() >= started
    }

    fun markActive(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putLong(KEY_STARTED_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    fun markInactive(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ACTIVE, false).apply()
    }

    fun ping(context: Context): Reply = call(context, "PING", "")

    fun call(context: Context, command: String, argument: String): Reply {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(HOST, PORT), 700)
            socket.soTimeout = 2_500
            val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            writer.write(token(context))
            writer.write('\t'.code)
            writer.write(command)
            writer.write('\t'.code)
            writer.write(argument)
            writer.write('\n'.code)
            writer.flush()
            val line = reader.readLine() ?: return Reply(false, "Системный сервис закрыл соединение без ответа.")
            val separator = line.indexOf('\t')
            val status = if (separator >= 0) line.substring(0, separator) else line
            val detail = if (separator >= 0) line.substring(separator + 1) else ""
            Reply(status == "OK", detail)
        } catch (failure: Throwable) {
            Reply(false, failure.message ?: failure.javaClass.simpleName)
        } finally {
            runCatching { socket.close() }
        }
    }
}
