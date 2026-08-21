package __PACKAGE__.timeaccessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class TimeAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var stage = Stage.IDLE
    private var targetMillis = 0L
    private var stageStartedAt = 0L
    private var textModeRequested = false

    private val driver = Runnable { driveAutomation() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        TimeCycleStore.addEvent(this, "Служба специальных возможностей подключена.")
        if (TimeCycleStore.isRunning(this)) {
            beginFromStorage(1200L)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (activeService === this) {
            activeService = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!TimeCycleStore.isRunning(this) || stage == Stage.IDLE) return
        handler.removeCallbacks(driver)
        handler.postDelayed(driver, 350L)
    }

    override fun onInterrupt() {
        TimeCycleStore.addEvent(this, "Служба автоматизации была прервана Android.")
    }

    private fun beginFromStorage(delayMillis: Long = 700L) {
        handler.removeCallbacksAndMessages(null)
        if (!TimeCycleStore.isRunning(this)) return
        if (TimeCycleStore.completedCycles(this) >= TimeCycleStore.totalCycles(this)) {
            TimeCycleStore.stop(this, "Все запланированные циклы уже завершены.")
            return
        }
        targetMillis = TimeCycleStore.targetForCurrentCycle(this)
        stage = Stage.OPEN_SETTINGS
        stageStartedAt = SystemClock.elapsedRealtime()
        textModeRequested = false
        TimeCycleStore.addEvent(this, "Начата попытка ${TimeCycleStore.completedCycles(this) + 1} из ${TimeCycleStore.totalCycles(this)}.")
        handler.postDelayed(driver, delayMillis)
    }

    private fun driveAutomation() {
        if (!TimeCycleStore.isRunning(this)) {
            stage = Stage.IDLE
            return
        }
        if (SystemClock.elapsedRealtime() - stageStartedAt > FLOW_TIMEOUT_MS) {
            finishAttempt(false, "Превышено время ожидания системного экрана. Проверьте язык и оболочку телефона.")
            return
        }

        when (stage) {
            Stage.OPEN_SETTINGS -> openSettingsAndPrepare()
            Stage.OPEN_DATE_DIALOG -> fillDateDialog()
            Stage.OPEN_TIME -> openTimeDialog()
            Stage.OPEN_TIME_DIALOG -> fillTimeDialog()
            Stage.VERIFY -> verifyAppliedTime()
            Stage.IDLE -> Unit
        }
    }

    private fun openSettingsAndPrepare() {
        val root = rootInActiveWindow
        if (root == null || !isSettingsWindow(root)) {
            val intent = Intent(Settings.ACTION_DATE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            handler.postDelayed(driver, 1400L)
            return
        }

        if (turnOffAutomaticTimeIfNeeded(root)) {
            handler.postDelayed(driver, 700L)
            return
        }

        if (clickText(root, DATE_LABELS)) {
            stage = Stage.OPEN_DATE_DIALOG
            handler.postDelayed(driver, 600L)
            return
        }
        handler.postDelayed(driver, 700L)
    }

    private fun fillDateDialog() {
        val root = rootInActiveWindow ?: run {
            handler.postDelayed(driver, 500L)
            return
        }

        if (!textModeRequested && clickText(root, DATE_TEXT_MODE_LABELS)) {
            textModeRequested = true
            handler.postDelayed(driver, 400L)
            return
        }

        val editable = findEditableNodes(root)
        if (editable.isEmpty()) {
            handler.postDelayed(driver, 500L)
            return
        }
        val date = Date(targetMillis)
        val localeIsRussian = Locale.getDefault().language.lowercase() == "ru"
        val oneFieldValue = SimpleDateFormat(if (localeIsRussian) "dd.MM.yyyy" else "MM/dd/yyyy", Locale.getDefault()).format(date)
        val calendar = java.util.Calendar.getInstance().apply { time = date }
        val parts = if (localeIsRussian) {
            listOf(
                String.format(Locale.US, "%02d", calendar.get(java.util.Calendar.DAY_OF_MONTH)),
                String.format(Locale.US, "%02d", calendar.get(java.util.Calendar.MONTH) + 1),
                calendar.get(java.util.Calendar.YEAR).toString(),
            )
        } else {
            listOf(
                String.format(Locale.US, "%02d", calendar.get(java.util.Calendar.MONTH) + 1),
                String.format(Locale.US, "%02d", calendar.get(java.util.Calendar.DAY_OF_MONTH)),
                calendar.get(java.util.Calendar.YEAR).toString(),
            )
        }

        if (editable.size == 1) {
            setText(editable.first(), oneFieldValue)
        } else {
            editable.take(3).forEachIndexed { index, node -> setText(node, parts[index]) }
        }

        if (clickText(rootInActiveWindow ?: root, CONFIRM_LABELS)) {
            stage = Stage.OPEN_TIME
            textModeRequested = false
            handler.postDelayed(driver, 650L)
        } else {
            handler.postDelayed(driver, 450L)
        }
    }

    private fun openTimeDialog() {
        val root = rootInActiveWindow ?: run {
            handler.postDelayed(driver, 500L)
            return
        }
        if (clickText(root, TIME_LABELS)) {
            stage = Stage.OPEN_TIME_DIALOG
            handler.postDelayed(driver, 600L)
            return
        }
        handler.postDelayed(driver, 600L)
    }

    private fun fillTimeDialog() {
        val root = rootInActiveWindow ?: run {
            handler.postDelayed(driver, 500L)
            return
        }
        if (!textModeRequested && clickText(root, TIME_TEXT_MODE_LABELS)) {
            textModeRequested = true
            handler.postDelayed(driver, 400L)
            return
        }

        val editable = findEditableNodes(root)
        if (editable.isEmpty()) {
            handler.postDelayed(driver, 500L)
            return
        }
        val formatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(targetMillis))
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = targetMillis }
        if (editable.size == 1) {
            setText(editable.first(), formatted)
        } else {
            setText(editable[0], String.format(Locale.US, "%02d", calendar.get(java.util.Calendar.HOUR_OF_DAY)))
            setText(editable[1], String.format(Locale.US, "%02d", calendar.get(java.util.Calendar.MINUTE)))
        }

        if (clickText(rootInActiveWindow ?: root, CONFIRM_LABELS)) {
            stage = Stage.VERIFY
            handler.postDelayed(driver, 1300L)
        } else {
            handler.postDelayed(driver, 450L)
        }
    }

    private fun verifyAppliedTime() {
        val delta = abs(System.currentTimeMillis() - targetMillis)
        val verified = delta <= 120_000L
        val detail = if (verified) {
            "Системное время подтверждено с допустимым отклонением."
        } else {
            "Не удалось подтвердить значение по системным часам; проверьте экран даты и времени."
        }
        finishAttempt(verified, detail)
    }

    private fun finishAttempt(success: Boolean, detail: String) {
        handler.removeCallbacks(driver)
        TimeCycleStore.markAttemptFinished(this, targetMillis, success, detail)
        stage = Stage.IDLE
        if (TimeCycleStore.isRunning(this)) {
            beginFromStorage(TimeCycleStore.pauseMillis(this))
        }
    }

    private fun turnOffAutomaticTimeIfNeeded(root: AccessibilityNodeInfo): Boolean {
        val node = findByText(root, AUTOMATIC_TIME_LABELS) ?: return false
        var container: AccessibilityNodeInfo? = node
        repeat(4) {
            val switch = container?.let(::findCheckableDescendant)
            if (switch != null) {
                return if (switch.isChecked) clickNode(switch) else false
            }
            container = container?.parent
        }
        return false
    }

    private fun findCheckableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        for (index in 0 until node.childCount) {
            val result = node.getChild(index)?.let(::findCheckableDescendant)
            if (result != null) return result
        }
        return null
    }

    private fun isSettingsWindow(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString()?.contains("settings", ignoreCase = true) == true

    private fun clickText(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val node = findByText(root, labels) ?: return false
        return clickNode(node)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isClickable == true && current?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                return true
            }
            current = current?.parent
        }
        return false
    }

    private fun findByText(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val ownText = listOfNotNull(root.text?.toString(), root.contentDescription?.toString()).joinToString(" ").lowercase()
        if (labels.any { ownText.contains(it.lowercase()) }) return root
        for (index in 0 until root.childCount) {
            val result = root.getChild(index)?.let { findByText(it, labels) }
            if (result != null) return result
        }
        return null
    }

    private fun findEditableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isEditable || node.className?.toString()?.contains("EditText") == true) result.add(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::walk)
        }
        walk(root)
        return result
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private enum class Stage { IDLE, OPEN_SETTINGS, OPEN_DATE_DIALOG, OPEN_TIME, OPEN_TIME_DIALOG, VERIFY }

    companion object {
        private const val FLOW_TIMEOUT_MS = 22_000L
        private var activeService: TimeAccessibilityService? = null

        private val AUTOMATIC_TIME_LABELS = listOf(
            "automatic date", "automatic time", "set time automatically", "use network-provided time",
            "автоматическая дата", "автоматическое время", "использовать время сети",
        )
        private val DATE_LABELS = listOf("set date", "установить дату")
        private val TIME_LABELS = listOf("set time", "установить время")
        private val CONFIRM_LABELS = listOf("ok", "готово", "подтвердить", "done")
        private val DATE_TEXT_MODE_LABELS = listOf(
            "switch to text input mode", "input mode", "режим ввода", "ввод даты",
        )
        private val TIME_TEXT_MODE_LABELS = listOf(
            "switch to text input mode", "keyboard input", "режим ввода", "ввод времени",
        )

        fun isServiceActive(): Boolean = activeService != null

        fun requestStart(context: Context) {
            activeService?.beginFromStorage() ?: TimeCycleStore.addEvent(
                context,
                "Запуск отложен: служба специальных возможностей пока не подключена Android.",
            )
        }

        fun requestStop() {
            activeService?.handler?.removeCallbacksAndMessages(null)
            activeService?.stage = Stage.IDLE
        }
    }
}
