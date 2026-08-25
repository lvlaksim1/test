package __PACKAGE__.timeaccessibility

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

object UnityRuntimeBridge {
    private const val HOST = "127.0.0.1"
    private const val PORT = 17323
    private const val CONNECT_TIMEOUT_MS = 500
    private const val READ_TIMEOUT_MS = 1200
    private const val PROTOCOL_VERSION = 1

    fun snapshot(packageName: String): JSONObject {
        val request = JSONObject()
            .put("protocol", PROTOCOL_VERSION)
            .put("action", "snapshot")
            .put("packageName", packageName)
        return request(request)
    }

    fun invoke(packageName: String, buttonId: String): JSONObject {
        val request = JSONObject()
            .put("protocol", PROTOCOL_VERSION)
            .put("action", "invoke")
            .put("packageName", packageName)
            .put("buttonId", buttonId)
        return request(request)
    }

    private fun request(payload: JSONObject): JSONObject {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                writer.write(payload.toString())
                writer.newLine()
                writer.flush()
                val line = reader.readLine() ?: error("runtime agent returned an empty response")
                JSONObject(line)
            }
        }.getOrElse { failure ->
            JSONObject()
                .put("ok", false)
                .put("connected", false)
                .put("status", "agent_unavailable")
                .put("screen", JSONObject.NULL)
                .put("buttons", org.json.JSONArray())
                .put("message", "Unity Runtime Agent не подключён: ${failure.message.orEmpty()}")
        }
    }
}
