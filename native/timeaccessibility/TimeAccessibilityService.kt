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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class TimeAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var stage = Stage.IDLE
    private var targetMillis = 0L
    private var attemptStartedAt = 0L
    private var stageRetries = 0
    private var settingsLaunchRequested = false
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
        if (TimeCycleStore.isRunning(this)) beginFromStorage(1_200L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!TimeCycleStore.isRunning(this) || stage == Stage.IDLE) return
        handler.removeCallbacks(driver)
        handler.postDelayed(driver, EVENT_SETTLE_DELAY_MS)
    }

    override fun onInterrupt() {
        if (stage != Stage.IDLE) {
            stopWithFailure("Android прервал службу до завершения изменения времени.")
        }
    }

    private fun beginFromStorage(delayMillis: Long = INITIAL_DELAY_MS) {
        handler.removeCallbacksAndMessages(null)
        if (!TimeCycleStore.isRunning(this)) return
        if (TimeCycleStore.completedCycles(this) >= TimeCycleStore.totalCycles(this)) {
            TimeCycleStore.stop(this, "Все запланированные циклы уже завершены.")
            return
        }

        targetMillis = TimeCycleStore.targetForCurrentCycle(this)
        stage = Stage.OPEN_SETTINGS
        attemptStartedAt = SystemClock.elapsedRealtime()
        stageRetries = 0
        settingsLaunchRequested = false
        textModeRequested = false
        TimeCycleStore.addEvent(
            this,
            "Начата попытка ${TimeCycleStore.completedCycles(this) + 1} из ${TimeCycleStore.totalCycles(this)}.",
        )
        handler.postDelayed(driver, delayMillis)
    }

    private fun driveAutomation() {
        if (!TimeCycleStore.isRunning(this)) {
            stage = Stage.IDLE
            return
        }
        if (SystemClock.elapsedRealtime() - attemptStartedAt > FLOW_TIMEOUT_MS) {
            stopWithFailure("Не удалось завершить ручной ввод за отведенное время. Цикл остановлен, чтобы не открывать настройки повторно.")
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
            if (!settingsLaunchRequested) {
                settingsLaunchRequested = true
                startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                retryOrStop("Android не открыл экран даты и времени.", SETTINGS_WAIT_RETRIES, SETTINGS_OPEN_DELAY_MS)
            } else {
                retryOrStop(
                    "Экран даты и времени был закрыт или не стал активным. Цикл не будет снова открывать настройки.",
                    SETTINGS_WAIT_RETRIES,
                    SETTINGS_OPEN_DELAY_MS,
                )
            }
            return
        }

        if (turnOffAutomaticTimeIfNeeded(root)) {
            retryOrStop("Android применяет отключение автоматического времени.", DEFAULT_STAGE_RETRIES, AFTER_TOGGLE_DELAY_MS)
            return
        }

        if (clickManualSetting(root, DATE_LABELS)) {
            moveTo(Stage.OPEN_DATE_DIALOG, DIALOG_OPEN_DELAY_MS)
            return
        }

        retryOrStop(
            "Не найден пункт ручной установки даты. Проверьте, что автоматические дата и время выключены.",
        )
    }

    private fun fillDateDialog() {
        val root = rootInActiveWindow ?: run {
            retryOrStop("Диалог выбора даты не открылся.")
            return
        }

        if (!textModeRequested) {
            val modeButton = findControl(root, DATE_TEXT_MODE_LABELS)
            if (modeButton != null && clickNode(modeButton)) {
                textModeRequested = true
                retryOrStop("Ожидается режим текстового ввода даты.", DEFAULT_STAGE_RETRIES, INPUT_MODE_DELAY_MS)
                return
            }
            textModeRequested = true
        }

        val editable = findEditableNodes(root)
        if (editable.isEmpty()) {
            retryOrStop("В диалоге даты не найдены поля ввода. Возможно, оболочка телефона использует неподдерживаемый вид выбора даты.")
            return
        }

        val date = Date(targetMillis)
        val calendar = Calendar.getInstance().apply { time = date }
        val dateParts = if (Locale.getDefault().language.lowercase() == "ru") {
            listOf(
                String.format(Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH)),
                String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1),
                calendar.get(Calendar.YEAR).toString(),
            )
        } else {
            listOf(
                String.format(Locale.US, "%02d", calendar.get(Calendar.MONTH) + 1),
                String.format(Locale.US, "%02d", calendar.get(Calendar.DAY_OF_MONTH)),
                calendar.get(Calendar.YEAR).toString(),
            )
        }
        val singleValue = SimpleDateFormat(
            if (Locale.getDefault().language.lowercase() == "ru") "dd.MM.yyyy" else "MM/dd/yyyy",
            Locale.getDefault(),
        ).format(date)

        val entered = if (editable.size == 1) {
            setText(editable.first(), singleValue)
        } else {
            editable.take(3).mapIndexed { index, node -> setText(node, dateParts[index]) }.all { it }
        }
        if (!entered) {
            retryOrStop("Android не принял значение даты в поле ввода.")
            return
        }

        val currentRoot = rootInActiveWindow ?: root
        if (clickConfirmation(currentRoot)) {
            textModeRequested = false
            moveTo(Stage.OPEN_TIME, AFTER_DATE_CONFIRM_DELAY_MS)
        } else {
            retryOrStop("Не найдена кнопка подтверждения даты.")
        }
    }

    private fun openTimeDialog() {
        val root = rootInActiveWindow ?: run {
            retryOrStop("После установки даты пропал экран системных настроек.")
            return
        }
        if (!isSettingsWindow(root)) {
            retryOrStop("Экран даты и времени был закрыт до установки времени.")
            return
        }
        if (clickManualSetting(root, TIME_LABELS)) {
            moveTo(Stage.OPEN_TIME_DIALOG, DIALOG_OPEN_DELAY_MS)
        } else {
            retryOrStop("Не найден пункт ручной установки времени.")
        }
    }

    private fun fillTimeDialog() {
        val root = rootInActiveWindow ?: run {
            retryOrStop("Диалог выбора времени не открылся.")
            return
        }

        if (!textModeRequested) {
            val modeButton = findControl(root, TIME_TEXT_MODE_LABELS)
            if (modeButton != null && clickNode(modeButton)) {
                textModeRequested = true
                retryOrStop("Ожидается режим текстового ввода времени.", DEFAULT_STAGE_RETRIES, INPUT_MODE_DELAY_MS)
                return
            }
            textModeRequested = true
        }

        val editable = findEditableNodes(root)
        if (editable.isEmpty()) {
            retryOrStop("В диалоге времени не найдены поля ввода. Возможно, оболочка телефона использует неподдерживаемый вид выбора времени.")
            return
        }

        val calendar = Calendar.getInstance().apply { timeInMillis = targetMillis }
        val entered = if (editable.size == 1) {
            setText(editable.first(), SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(targetMillis)))
        } else {
            setText(editable[0], String.format(Locale.US, "%02d", calendar.get(Calendar.HOUR_OF_DAY))) &&
                setText(editable[1], String.format(Locale.US, "%02d", calendar.get(Calendar.MINUTE)))
        }
        if (!entered) {
            retryOrStop("Android не принял значение времени в поле ввода.")
            return
        }

        val currentRoot = rootInActiveWindow ?: root
        if (clickConfirmation(currentRoot)) {
            moveTo(Stage.VERIFY, VERIFY_DELAY_MS)
        } else {
            retryOrStop("Не найдена кнопка подтверждения времени.")
        }
    }

    private fun verifyAppliedTime() {
        val verified = abs(System.currentTimeMillis() - targetMillis) <= VERIFY_TOLERANCE_MS
        if (verified) {
            finishAttempt(true, "Системное время подтверждено с допустимым отклонением.")
        } else {
            stopWithFailure("Введенное значение не подтверждено системными часами. Цикл остановлен, чтобы избежать повторных переходов в настройки.")
        }
    }

    private fun moveTo(nextStage: Stage, delayMillis: Long) {
        stage = nextStage
        stageRetries = 0
        handler.postDelayed(driver, delayMillis)
    }

    private fun retryOrStop(
        failureReason: String,
        retryLimit: Int = DEFAULT_STAGE_RETRIES,
        delayMillis: Long = RETRY_DELAY_MS,
    ) {
        stageRetries += 1
        if (stageRetries >= retryLimit) {
            stopWithFailure(failureReason)
        } else {
            handler.postDelayed(driver, delayMillis)
        }
    }

    private fun finishAttempt(success: Boolean, detail: String) {
        handler.removeCallbacks(driver)
        TimeCycleStore.markAttemptFinished(this, targetMillis, success, detail)
        stage = Stage.IDLE
        if (success && TimeCycleStore.isRunning(this)) {
            beginFromStorage(TimeCycleStore.pauseMillis(this))
        }
    }

    private fun stopWithFailure(detail: String) {
        handler.removeCallbacks(driver)
        TimeCycleStore.markAttemptFinished(this, targetMillis, false, detail)
        TimeCycleStore.stop(this, "Автоматизация остановлена: требуется проверка экрана даты и времени на этом телефоне.")
        stage = Stage.IDLE
    }

    private fun turnOffAutomaticTimeIfNeeded(root: AccessibilityNodeInfo): Boolean {
        val automaticNode = findControl(root, AUTOMATIC_TIME_LABELS) ?: return false
        var container: AccessibilityNodeInfo? = automaticNode
        repeat(4) {
            val switch = container?.let(::findCheckableDescendant)
            if (switch != null) return switch.isChecked && clickNode(switch)
            container = container?.parent
        }
        return false
    }

    private fun clickManualSetting(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val manualNode = findControl(root, labels, excludeAutomatic = true) ?: return false
        return clickNode(manualNode)
    }

    private fun clickConfirmation(root: AccessibilityNodeInfo): Boolean =
        findControl(root, CONFIRM_LABELS)?.let(::clickNode) ?: false

    private fun isSettingsWindow(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString()?.contains("settings", ignoreCase = true) == true

    private fun findControl(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        excludeAutomatic: Boolean = false,
    ): AccessibilityNodeInfo? {
        val normalizedLabels = labels.map(::normalize)
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = nodeLabel(node)
            val exact = normalizedLabels.any { it == text }
            val partial = normalizedLabels.any { label -> label.length > 2 && text.contains(label) }
            val allowed = !excludeAutomatic || !looksAutomatic(text)
            if (allowed && (exact || partial) && hasClickableAncestor(node)) return node
            for (index in 0 until node.childCount) {
                val result = node.getChild(index)?.let(::visit)
                if (result != null) return result
            }
            return null
        }
        return visit(root)
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isClickable == true) return true
            current = current?.parent
        }
        return false
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ")
            .let(::normalize)

    private fun normalize(value: String): String = value.trim().lowercase()

    private fun looksAutomatic(value: String): Boolean =
        AUTOMATIC_MARKERS.any { marker -> value.contains(marker) }

    private fun findCheckableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        for (index in 0 until node.childCount) {
            val result = node.getChild(index)?.let(::findCheckableDescendant)
            if (result != null) return result
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            if (current?.isClickable == true && current?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
            current = current?.parent
        }
        return false
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
        private const val FLOW_TIMEOUT_MS = 30_000L
        private const val INITIAL_DELAY_MS = 700L
        private const val EVENT_SETTLE_DELAY_MS = 350L
        private const val SETTINGS_OPEN_DELAY_MS = 1_200L
        private const val AFTER_TOGGLE_DELAY_MS = 700L
        private const val DIALOG_OPEN_DELAY_MS = 600L
        private const val INPUT_MODE_DELAY_MS = 400L
        private const val AFTER_DATE_CONFIRM_DELAY_MS = 700L
        private const val VERIFY_DELAY_MS = 1_300L
        private const val RETRY_DELAY_MS = 650L
        private const val VERIFY_TOLERANCE_MS = 120_000L
        private const val DEFAULT_STAGE_RETRIES = 7
        private const val SETTINGS_WAIT_RETRIES = 5

        private var activeService: TimeAccessibilityService? = null

        private val AUTOMATIC_TIME_LABELS = listOf(
            "automatic date", "automatic time", "set time automatically", "use network-provided time",
            "автоматическая дата", "автоматическое время", "использовать время сети", "автоматически",
        )
        private val AUTOMATIC_MARKERS = listOf("automatic", "network", "автомат", "сети")
        private val DATE_LABELS = listOf("set date", "change date", "установить дату", "изменить дату", "дата")
        private val TIME_LABELS = listOf("set time", "change time", "установить время", "изменить время", "время")
        private val CONFIRM_LABELS = listOf("ok", "готово", "подтвердить", "done", "сохранить")
        private val DATE_TEXT_MODE_LABELS = listOf(
            "switch to text input mode", "input mode", "text input", "режим ввода", "ввод даты",
        )
        private val TIME_TEXT_MODE_LABELS = listOf(
            "switch to text input mode", "keyboard input", "input mode", "режим ввода", "ввод времени",
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
