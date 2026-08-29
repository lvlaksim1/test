// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility

import android.os.IBinder
import android.os.Parcel

/**
 * App-side Binder endpoint for the detached uid=2000 server.
 *
 * The Binder object itself is delivered by TimePrivilegedServer through
 * TimeBinderProvider. Unlike the previous localhost socket, this reference is
 * re-delivered whenever the application process is created again.
 */
object TimePrivilegedBridge {
    const val DESCRIPTOR = "__PACKAGE__.timeaccessibility.ITimeMachinePrivileged"
    const val METHOD_SEND_BINDER = "sendBinder"
    const val EXTRA_BINDER = "time_machine_privileged_binder"
    const val EXTRA_ACK_BINDER = "time_machine_privileged_ack"

    const val TRANSACTION_PING = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_SET_TIME = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_AUTO_TIME = IBinder.FIRST_CALL_TRANSACTION + 2

    data class Reply(val success: Boolean, val detail: String)

    @Volatile
    private var serviceBinder: IBinder? = null

    private val deathRecipient = IBinder.DeathRecipient {
        synchronized(this) {
            serviceBinder = null
        }
    }

    @Synchronized
    fun install(binder: IBinder) {
        if (!binder.isBinderAlive || !binder.pingBinder()) return
        if (serviceBinder === binder) return
        serviceBinder?.let { previous -> runCatching { previous.unlinkToDeath(deathRecipient, 0) } }
        serviceBinder = binder
        runCatching { binder.linkToDeath(deathRecipient, 0) }
            .onFailure { if (serviceBinder === binder) serviceBinder = null }
    }

    @Synchronized
    fun clear() {
        serviceBinder?.let { previous -> runCatching { previous.unlinkToDeath(deathRecipient, 0) } }
        serviceBinder = null
    }

    fun ping(): Reply = transact(TRANSACTION_PING) { }

    fun setTime(targetMillis: Long): Reply = transact(TRANSACTION_SET_TIME) { data ->
        data.writeLong(targetMillis)
    }

    fun setAutomaticTime(enabled: Boolean): Reply = transact(TRANSACTION_AUTO_TIME) { data ->
        data.writeInt(if (enabled) 1 else 0)
    }

    private fun transact(code: Int, writeArguments: (Parcel) -> Unit): Reply {
        val binder = serviceBinder ?: return Reply(false, "Системный Binder ещё не получен.")
        if (!binder.isBinderAlive || !binder.pingBinder()) {
            clear()
            return Reply(false, "Системный Binder недоступен.")
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            writeArguments(data)
            if (!binder.transact(code, data, reply, 0)) {
                clear()
                Reply(false, "Системный сервис отклонил Binder-транзакцию.")
            } else {
                reply.readException()
                val success = reply.readInt() != 0
                val detail = reply.readString().orEmpty()
                Reply(success, detail)
            }
        } catch (failure: Throwable) {
            clear()
            Reply(false, failure.message ?: failure.javaClass.simpleName)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
