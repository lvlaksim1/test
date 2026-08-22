package __PACKAGE__.timeaccessibility

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

data class ShizukuState(
    val isRunning: Boolean,
    val isPermissionGranted: Boolean,
)

data class ShizukuCommandOutcome(
    val isSuccess: Boolean,
    val detail: String,
)

object TimeShizukuController {
    private const val REQUEST_CODE = 7201
    private const val SERVICE_TAG = "time-cycler-direct-time-v1"
    private const val SERVICE_VERSION = 2
    private const val SERVICE_PROCESS_SUFFIX = "timecycler"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var remoteService: ITimeShizukuService? = null
    private var isBinding = false
    private var pendingRequest: Pair<Long, (ShizukuCommandOutcome) -> Unit>? = null

    fun state(): ShizukuState {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return ShizukuState(running, granted)
    }

    fun requestPermission(): Boolean {
        val current = state()
        if (!current.isRunning) return false
        if (current.isPermissionGranted) return true
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(true)) return false
        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            false
        }.getOrDefault(false)
    }

    fun applyTime(context: Context, targetMillis: Long, callback: (ShizukuCommandOutcome) -> Unit) {
        if (!state().isPermissionGranted) {
            deliver(callback, ShizukuCommandOutcome(false, "Shizuku не запущен или доступ к нему не выдан."))
            return
        }
        var refused = false
        val existing = synchronized(lock) {
            val service = remoteService
            if (service == null) {
                if (pendingRequest != null) {
                    refused = true
                } else {
                    pendingRequest = targetMillis to callback
                    if (!isBinding) {
                        isBinding = true
                        bind(context)
                    }
                }
            }
            service
        }
        if (refused) {
            deliver(callback, ShizukuCommandOutcome(false, "Предыдущая команда Shizuku ещё выполняется."))
            return
        }
        if (existing != null) invoke(existing, targetMillis, callback)
    }

    private fun bind(context: Context) {
        runCatching {
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(ComponentName(context, TimeShizukuUserService::class.java))
                    .tag(SERVICE_TAG)
                    .version(SERVICE_VERSION)
                    .processNameSuffix(SERVICE_PROCESS_SUFFIX)
                    .daemon(false),
                connection,
            )
        }.onFailure { failure ->
            val request = synchronized(lock) {
                isBinding = false
                pendingRequest.also { pendingRequest = null }
            }
            request?.second?.let { callback ->
                deliver(callback, ShizukuCommandOutcome(false, "Не удалось подключить Shizuku: ${failure.message.orEmpty()}"))
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val request = synchronized(lock) {
                remoteService = ITimeShizukuService.Stub.asInterface(binder)
                isBinding = false
                pendingRequest.also { pendingRequest = null }
            }
            if (remoteService == null) {
                request?.second?.let { deliver(it, ShizukuCommandOutcome(false, "Shizuku не вернул службу прямого управления временем.")) }
                return
            }
            request?.let { invoke(remoteService!!, it.first, it.second) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) { remoteService = null }
        }
    }

    private fun invoke(service: ITimeShizukuService, targetMillis: Long, callback: (ShizukuCommandOutcome) -> Unit) {
        executor.execute {
            val detail = runCatching { service.applyTime(targetMillis) }
                .getOrElse { "ОШИБКА: ${it.message.orEmpty()}" }
            deliver(callback, ShizukuCommandOutcome(detail.startsWith("OK:"), detail))
        }
    }

    private fun deliver(callback: (ShizukuCommandOutcome) -> Unit, outcome: ShizukuCommandOutcome) {
        mainHandler.post { callback(outcome) }
    }
}
