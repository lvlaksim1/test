// SPDX-License-Identifier: Apache-2.0
package __PACKAGE__.timeaccessibility

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder

/**
 * Receives the privileged Binder directly from the detached shell process.
 * Only uid=2000 (ADB shell) is accepted.
 */
class TimeBinderProvider : ContentProvider() {
    companion object {
        private const val SHELL_UID = 2000

        @Volatile
        private var acknowledgementBinder: IBinder = Binder()
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != TimePrivilegedBridge.METHOD_SEND_BINDER) return super.call(method, arg, extras)
        if (Binder.getCallingUid() != SHELL_UID) return null

        val binder = extras?.getBinder(TimePrivilegedBridge.EXTRA_BINDER) ?: return null
        if (!binder.isBinderAlive || !binder.pingBinder()) return null

        TimePrivilegedBridge.install(binder)
        acknowledgementBinder = Binder()
        return Bundle().apply {
            putBinder(TimePrivilegedBridge.EXTRA_ACK_BINDER, acknowledgementBinder)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
