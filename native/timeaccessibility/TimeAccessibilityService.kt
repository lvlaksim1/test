package __PACKAGE__.timeaccessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
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
    private val diagnosticStages = mutableSetOf<Stage>()
    private val traceKeys = mutableSetOf<String>()
    private var calendarDateMode = CalendarDateMode.NONE
    private var wheelPickerMode = WheelPickerMode.NONE

    private val driver = Runnable { driveAutomation() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        TimeCycleStore.addEvent(this, "Служба специальных возможностей подключена. ${installedBuildMarker()}")
        if (TimeCycleStore.consumeReturnToAppAfterEnable(this)) {
            handler.postDelayed({ returnToApp("После включения службы выполнен возврат в приложение.") }, 450L)
        }
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
            returnToApp("Все циклы уже завершены. Выполнен возврат в приложение.")
            return
        }

        targetMillis = TimeCycleStore.targetForCurrentCycle(this)
        stage = Stage.OPEN_SETTINGS
        attemptStartedAt = SystemClock.elapsedRealtime()
        stageRetries = 0
        settingsLaunchRequested = false
        textModeRequested = false
        diagnosticStages.clear()
        traceKeys.clear()
        calendarDateMode = CalendarDateMode.NONE
        wheelPickerMode = WheelPickerMode.NONE
        TimeCycleStore.addEvent(
            this,
            "Начата попытка ${TimeCycleStore.completedCycles(this) + 1} из ${TimeCycleStore.totalCycles(this)}.",
        )
        handler.postDelayed(driver, delayMillis)
    }

    private fun installedBuildMarker(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        }
        return "Сборка ${packageInfo.versionName} ($code), TRACE-20260821-1."
    }

    private fun returnToApp(note: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (launchIntent != null) {
            startActivity(launchIntent)
            TimeCycleStore.addEvent(this, note)
        }
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
                trace("settings.launch", "Шаг 1/6: открываю системный экран «Дата и время».")
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

        trace("settings.ready", "Шаг 2/6: системный экран «Дата и время» найден.")

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

        if (!looksLikeDateDialog(root)) {
            trace("date.dialog.missing", "Шаг 4/6: после нажатия «Дата» системный диалог не обнаружен; возвращаюсь к точной строке даты.")
            stage = Stage.OPEN_SETTINGS
            retryOrStop("Пункт «Дата» не открыл диалог выбора. Повторная попытка выполняется через точную строку даты.")
            return
        }

        if (!textModeRequested) {
            trace("date.dialog.ready", "Шаг 4/6: диалог выбора даты обнаружен.")
            val modeButton = findTextInputToggle(root, DATE_TEXT_MODE_LABELS, DATE_INPUT_TOGGLE_IDS)
            if (modeButton != null && clickNode(modeButton)) {
                textModeRequested = true
                retryOrStop("Ожидается режим текстового ввода даты.", DEFAULT_STAGE_RETRIES, INPUT_MODE_DELAY_MS)
                return
            }
            textModeRequested = true
        }

        if (findWheelPickers(root).isNotEmpty() && applyWheelPickers(root, WheelPickerMode.DATE)) return

        val editable = findEditableNodes(root)
        if (editable.isEmpty()) {
            if (applyWheelPickers(root, WheelPickerMode.DATE)) return
            logDialogDiagnostics(root, "Диагностика выбора даты")
            if (selectDateInCalendar(root)) return
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

        if (!looksLikeTimeDialog(root)) {
            trace("time.dialog.missing", "Шаг 6/6: после нажатия «Время» системный диалог не обнаружен; возвращаюсь к точной строке времени.")
            stage = Stage.OPEN_TIME
            retryOrStop("Пункт «Время» не открыл диалог выбора. Повторная попытка выполняется через точную строку времени.")
            return
        }

        if (!textModeRequested) {
            trace("time.dialog.ready", "Шаг 6/6: диалог выбора времени обнаружен.")
            val modeButton = findTextInputToggle(root, TIME_TEXT_MODE_LABELS, TIME_INPUT_TOGGLE_IDS)
            if (modeButton != null && clickNode(modeButton)) {
                textModeRequested = true
                retryOrStop("Ожидается режим текстового ввода времени.", DEFAULT_STAGE_RETRIES, INPUT_MODE_DELAY_MS)
                return
            }
            textModeRequested = true
        }

        if (findWheelPickers(root).isNotEmpty() && applyWheelPickers(root, WheelPickerMode.TIME)) return

        val editable = findEditableNodes(root)
        if (editable.isEmpty()) {
            if (applyWheelPickers(root, WheelPickerMode.TIME)) return
            logDialogDiagnostics(root, "Диагностика выбора времени")
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
        trace("transition.${stage.name}.${nextStage.name}", "Переход: ${stage.label()} → ${nextStage.label()}.")
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
        trace("retry.${stage.name}.$stageRetries", "Повтор ${stageRetries}/${retryLimit}: $failureReason")
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
        } else if (success) {
            returnToApp("Все циклы завершены. Выполнен возврат в приложение.")
        }
    }

    private fun stopWithFailure(detail: String) {
        handler.removeCallbacks(driver)
        TimeCycleStore.markAttemptFinished(this, targetMillis, false, detail)
        TimeCycleStore.stop(this, "Автоматизация остановлена: требуется проверка экрана даты и времени на этом телефоне.")
        stage = Stage.IDLE
        returnToApp("Автоматизация остановлена. Выполнен возврат в приложение.")
    }

    private fun turnOffAutomaticTimeIfNeeded(root: AccessibilityNodeInfo): Boolean {
        val automaticNode = findExactControl(root, AUTOMATIC_TIME_LABELS)
        if (automaticNode == null) {
            trace("sync.row.missing", "Шаг 3/6: строка автоматической синхронизации не найдена; продолжаю к ручной дате.")
            return false
        }
        var container: AccessibilityNodeInfo? = automaticNode
        repeat(4) {
            val switch = container?.let(::findCheckableDescendant)
            if (switch != null) {
                if (!switch.isChecked) {
                    trace("sync.already.off", "Шаг 3/6: автоматическая синхронизация уже выключена.")
                    return false
                }
                val clicked = clickNode(switch)
                trace("sync.toggle.$clicked", "Шаг 3/6: переключатель автоматической синхронизации найден, результат нажатия: $clicked.")
                return clicked
            }
            container = container?.parent
        }
        trace("sync.switch.missing", "Шаг 3/6: строка синхронизации найдена, но её переключатель не обнаружен.")
        return false
    }

    private fun clickManualSetting(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val targetName = if (labels == DATE_LABELS) "Дата" else "Время"
        val manualNode = findExactControl(root, labels)
        if (manualNode == null) {
            trace("row.${targetName}.missing", "Точная строка «$targetName» не найдена на текущем экране.")
            return false
        }
        val clicked = clickNode(manualNode)
        val step = if (targetName == "Дата") "4/6" else "6/6"
        trace("row.${targetName}.click.$clicked", "Шаг $step: строка «$targetName» найдена, результат нажатия: $clicked.")
        return clicked
    }

    private fun trace(key: String, message: String) {
        if (traceKeys.add(key)) TimeCycleStore.addEvent(this, message)
    }

    private fun Stage.label(): String = when (this) {
        Stage.OPEN_SETTINGS -> "экран настроек"
        Stage.OPEN_DATE_DIALOG -> "выбор даты"
        Stage.OPEN_TIME -> "экран времени"
        Stage.OPEN_TIME_DIALOG -> "выбор времени"
        Stage.VERIFY -> "проверка результата"
        Stage.IDLE -> "ожидание"
    }

    private fun looksLikeDateDialog(root: AccessibilityNodeInfo): Boolean =
        findNodeByResourceSuffix(root, DATE_HEADER_IDS) != null ||
            findNodeByResourceSuffix(root, DAY_PAGER_IDS) != null ||
            findWheelPickers(root).size >= 3

    private fun looksLikeTimeDialog(root: AccessibilityNodeInfo): Boolean =
        findNodeByResourceSuffix(root, TIME_INPUT_TOGGLE_IDS) != null ||
            findWheelPickers(root).size >= 2 ||
            findEditableNodes(root).isNotEmpty()

    private fun clickConfirmation(root: AccessibilityNodeInfo): Boolean =
        (findControlByResourceSuffix(root, CONFIRM_BUTTON_IDS) ?: findControl(root, CONFIRM_LABELS))
            ?.let(::clickNode) ?: false

    private fun applyWheelPickers(root: AccessibilityNodeInfo, mode: WheelPickerMode): Boolean {
        val wheels = findWheelPickers(root)
        val expectedCount = if (mode == WheelPickerMode.DATE) 3 else 2
        if (wheels.size < expectedCount) return false

        wheelPickerMode = mode
        val calendar = Calendar.getInstance().apply { timeInMillis = targetMillis }
        val targets = if (mode == WheelPickerMode.DATE) {
            listOf(
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.YEAR),
            )
        } else {
            listOf(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
        }
        val selectedWheels = wheels.take(expectedCount)
        val results = selectedWheels.mapIndexed { index, wheel -> setWheelValue(wheel, targets[index]) }

        if (results.any { it == WheelUpdate.FAILED }) {
            retryOrStop("Не удалось прочитать или прокрутить одно из колес выбора ${if (mode == WheelPickerMode.DATE) "даты" else "времени"}.")
            return true
        }
        if (results.any { it == WheelUpdate.ADJUSTING }) {
            handler.postDelayed(driver, WHEEL_STEP_DELAY_MS)
            return true
        }

        val currentRoot = rootInActiveWindow ?: root
        if (!clickConfirmation(currentRoot)) {
            retryOrStop("Не найдена кнопка подтверждения колесного выбора.")
            return true
        }

        textModeRequested = false
        wheelPickerMode = WheelPickerMode.NONE
        if (mode == WheelPickerMode.DATE) {
            moveTo(Stage.OPEN_TIME, AFTER_DATE_CONFIRM_DELAY_MS)
        } else {
            moveTo(Stage.VERIFY, VERIFY_DELAY_MS)
        }
        return true
    }

    private fun findWheelPickers(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            val className = node.className?.toString()?.lowercase().orEmpty()
            val resourceName = node.viewIdResourceName?.lowercase().orEmpty()
            val isNumberPicker = className.endsWith("numberpicker") || resourceName.contains("numberpicker")
            val canScroll = node.actionList.any { action ->
                action.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (isNumberPicker && canScroll) {
                result.add(node)
                return
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return result.sortedBy { node -> Rect().also(node::getBoundsInScreen).left }
    }

    private fun setWheelValue(wheel: AccessibilityNodeInfo, target: Int): WheelUpdate {
        val current = readWheelValue(wheel) ?: return WheelUpdate.FAILED
        if (current == target) return WheelUpdate.SETTLED
        if (kotlin.math.abs(current - target) > MAX_WHEEL_VALUE_DISTANCE) return WheelUpdate.FAILED

        val action = if (target > current) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return if (wheel.performAction(action)) WheelUpdate.ADJUSTING else WheelUpdate.FAILED
    }

    private fun readWheelValue(wheel: AccessibilityNodeInfo): Int? {
        val editable = findEditableNodes(wheel).firstOrNull()
        val fromInput = editable?.let(::nodeLabel)?.let(::parseWheelNumber)
        if (fromInput != null) return fromInput

        val candidates = mutableListOf<Pair<Int, String>>()
        val wheelBounds = Rect().also(wheel::getBoundsInScreen)
        fun visit(node: AccessibilityNodeInfo) {
            val label = nodeLabel(node)
            if (parseWheelNumber(label) != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                candidates.add(kotlin.math.abs(bounds.centerY() - wheelBounds.centerY()) to label)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(wheel)
        return candidates.minByOrNull { it.first }?.second?.let(::parseWheelNumber)
    }

    private fun parseWheelNumber(value: String): Int? =
        Regex("-?\\d{1,4}").find(value)?.value?.toIntOrNull()

    private fun selectDateInCalendar(root: AccessibilityNodeInfo): Boolean {
        val target = Calendar.getInstance().apply { timeInMillis = targetMillis }
        val targetYear = target.get(Calendar.YEAR)
        val targetMonth = target.get(Calendar.MONTH)
        val targetDay = target.get(Calendar.DAY_OF_MONTH)
        val headerYear = findControlByResourceSuffix(root, DATE_YEAR_HEADER_IDS)?.let(::nodeLabel)?.toIntOrNull()

        when (calendarDateMode) {
            CalendarDateMode.NONE -> {
                if (headerYear == null) return false
                if (matchesSelectedDateHeader(root, targetDay, targetMonth, targetYear)) {
                    calendarDateMode = CalendarDateMode.CONFIRM
                    handler.postDelayed(driver, CALENDAR_STEP_DELAY_MS)
                    return true
                }
                if (headerYear != targetYear) {
                    val yearHeader = findControlByResourceSuffix(root, DATE_YEAR_HEADER_IDS) ?: return false
                    if (!clickNode(yearHeader)) return false
                    calendarDateMode = CalendarDateMode.YEAR_LIST
                    handler.postDelayed(driver, CALENDAR_STEP_DELAY_MS)
                } else {
                    calendarDateMode = CalendarDateMode.MONTH
                    handler.postDelayed(driver, CALENDAR_STEP_DELAY_MS)
                }
                return true
            }

            CalendarDateMode.YEAR_LIST -> {
                val targetYearNode = findYearOption(root, targetYear) ?: return false
                if (!clickNode(targetYearNode)) return false
                calendarDateMode = CalendarDateMode.MONTH
                handler.postDelayed(driver, CALENDAR_STEP_DELAY_MS)
                return true
            }

            CalendarDateMode.MONTH -> {
                val displayed = findVisibleCalendarMonth(root) ?: return false
                val monthOffset = (targetYear - displayed.second) * 12 + (targetMonth - displayed.first)
                if (monthOffset == 0) {
                    calendarDateMode = CalendarDateMode.DAY
                    handler.postDelayed(driver, CALENDAR_STEP_DELAY_MS)
                    return true
                }
                if (kotlin.math.abs(monthOffset) > MAX_CALENDAR_MONTH_OFFSET) {
                    stopWithFailure("Целевая дата слишком далеко от отображаемого месяца календаря. Цикл остановлен.")
                    return true
                }
                val scrollAction = if (monthOffset > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                val pager = findNodeByResourceSuffix(root, DAY_PAGER_IDS)
                val scrolled = pager?.performAction(scrollAction) == true
                if (!scrolled) {
                    val directionIds = if (monthOffset > 0) NEXT_MONTH_IDS else PREVIOUS_MONTH_IDS
                    val directionLabels = if (monthOffset > 0) NEXT_MONTH_LABELS else PREVIOUS_MONTH_LABELS
                    val monthButton = findControlByResourceSuffix(root, directionIds) ?: findControl(root, directionLabels)
                        ?: return false
                    if (!clickNode(monthButton)) return false
                }
                handler.postDelayed(driver, CALENDAR_STEP_DELAY_MS)
                return true
            }

            CalendarDateMode.DAY -> {
                val dayNode = findVisibleDayOption(root, targetDay, targetMonth, targetYear) ?: return false
                if (!clickOrTapNode(dayNode)) return false
                calendarDateMode = CalendarDateMode.CONFIRM
                handler.postDelayed(driver, CALENDAR_STEP_DELAY_MS)
                return true
            }

            CalendarDateMode.CONFIRM -> {
                if (!clickConfirmation(root)) return false
                textModeRequested = false
                calendarDateMode = CalendarDateMode.NONE
                moveTo(Stage.OPEN_TIME, AFTER_DATE_CONFIRM_DELAY_MS)
                return true
            }
        }
    }

    private fun findYearOption(root: AccessibilityNodeInfo, targetYear: Int): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val resource = node.viewIdResourceName?.lowercase().orEmpty()
            if (nodeLabel(node) == targetYear.toString() && !resource.contains("header_year") && hasClickableAncestor(node)) {
                return node
            }
            for (index in 0 until node.childCount) {
                val result = node.getChild(index)?.let(::visit)
                if (result != null) return result
            }
            return null
        }
        return visit(root)
    }

    private fun matchesSelectedDateHeader(
        root: AccessibilityNodeInfo,
        targetDay: Int,
        targetMonth: Int,
        targetYear: Int,
    ): Boolean {
        val header = findNodeByResourceSuffix(root, DATE_HEADER_IDS)?.let(::nodeLabel) ?: return false
        val hasYear = header.contains(targetYear.toString()) ||
            findControlByResourceSuffix(root, DATE_YEAR_HEADER_IDS)?.let(::nodeLabel) == targetYear.toString()
        return hasYear && HEADER_MONTH_NAMES[targetMonth].any { month -> header.contains(month) } &&
            Regex("(^|\\D)$targetDay(\\D|$)").containsMatchIn(header)
    }

    private fun findVisibleCalendarMonth(root: AccessibilityNodeInfo): Pair<Int, Int>? {
        val pager = findNodeByResourceSuffix(root, DAY_PAGER_IDS) ?: return null
        val counts = mutableMapOf<Pair<Int, Int>, Int>()
        fun visit(node: AccessibilityNodeInfo) {
            val label = nodeLabel(node)
            val year = YEAR_REGEX.find(label)?.value?.toIntOrNull()
            val month = DISPLAY_MONTHS.indexOfFirst { name -> label.contains(name) }
            if (year != null && month >= 0 && node.isVisibleToUser) {
                val key = month to year
                counts[key] = (counts[key] ?: 0) + 1
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(pager)
        return counts.maxByOrNull { it.value }?.key
    }

    private fun findVisibleDayOption(
        root: AccessibilityNodeInfo,
        targetDay: Int,
        targetMonth: Int,
        targetYear: Int,
    ): AccessibilityNodeInfo? {
        val pager = findNodeByResourceSuffix(root, DAY_PAGER_IDS) ?: return null
        val monthNames = DAY_MONTH_NAMES[targetMonth]
        val dayPattern = Regex("(^|\\D)$targetDay(\\D|$)")
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val label = nodeLabel(node)
            val matchesDate = label.contains(targetYear.toString()) &&
                monthNames.any { monthName -> label.contains(monthName) } &&
                dayPattern.containsMatchIn(label)
            if (matchesDate && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) {
                val result = node.getChild(index)?.let(::visit)
                if (result != null) return result
            }
            return null
        }
        return visit(pager)
    }

    private fun findNodeByResourceSuffix(root: AccessibilityNodeInfo, resourceSuffixes: List<String>): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val resourceName = node.viewIdResourceName?.lowercase().orEmpty()
            if (resourceSuffixes.any { suffix -> resourceName.endsWith(suffix) || resourceName.contains(suffix) }) return node
            for (index in 0 until node.childCount) {
                val result = node.getChild(index)?.let(::visit)
                if (result != null) return result
            }
            return null
        }
        return visit(root)
    }

    private fun findTextInputToggle(
        root: AccessibilityNodeInfo,
        labels: List<String>,
        resourceSuffixes: List<String>,
    ): AccessibilityNodeInfo? =
        findControlByResourceSuffix(root, resourceSuffixes) ?: findControl(root, labels)

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

    private fun findExactControl(root: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val normalizedLabels = labels.map(::normalize).toSet()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (nodeLabel(node) in normalizedLabels && hasClickableAncestor(node)) return node
            for (index in 0 until node.childCount) {
                val result = node.getChild(index)?.let(::visit)
                if (result != null) return result
            }
            return null
        }
        return visit(root)
    }

    private fun findControlByResourceSuffix(
        root: AccessibilityNodeInfo,
        resourceSuffixes: List<String>,
    ): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val resourceName = node.viewIdResourceName?.lowercase().orEmpty()
            if (
                resourceSuffixes.any { suffix -> resourceName.endsWith(suffix) || resourceName.contains(suffix) } &&
                hasClickableAncestor(node)
            ) {
                return node
            }
            for (index in 0 until node.childCount) {
                val result = node.getChild(index)?.let(::visit)
                if (result != null) return result
            }
            return null
        }
        return visit(root)
    }

    private fun logDialogDiagnostics(root: AccessibilityNodeInfo, title: String) {
        if (!diagnosticStages.add(stage)) return
        val details = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 5 || details.size >= 24) return
            val resource = node.viewIdResourceName?.substringAfterLast('/')
            val label = nodeLabel(node)
            if (!resource.isNullOrBlank() || label.isNotBlank()) {
                details.add(
                    "${resource ?: node.className?.toString()?.substringAfterLast('.') ?: "узел"}" +
                        "[${label.take(40)}; кликаб=${node.isClickable}; редакт=${node.isEditable}]",
                )
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        TimeCycleStore.addEvent(this, "$title: ${details.joinToString(" | ")}")
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

    private fun clickOrTapNode(node: AccessibilityNodeInfo): Boolean {
        if (clickNode(node)) return true
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty()) return false
        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 70))
            .build()
        return dispatchGesture(gesture, null, null)
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
    private enum class CalendarDateMode { NONE, YEAR_LIST, MONTH, DAY, CONFIRM }
    private enum class WheelPickerMode { NONE, DATE, TIME }
    private enum class WheelUpdate { SETTLED, ADJUSTING, FAILED }

    companion object {
        private const val FLOW_TIMEOUT_MS = 60_000L
        private const val INITIAL_DELAY_MS = 700L
        private const val EVENT_SETTLE_DELAY_MS = 350L
        private const val SETTINGS_OPEN_DELAY_MS = 1_200L
        private const val AFTER_TOGGLE_DELAY_MS = 700L
        private const val DIALOG_OPEN_DELAY_MS = 600L
        private const val INPUT_MODE_DELAY_MS = 400L
        private const val AFTER_DATE_CONFIRM_DELAY_MS = 700L
        private const val VERIFY_DELAY_MS = 1_300L
        private const val RETRY_DELAY_MS = 650L
        private const val CALENDAR_STEP_DELAY_MS = 500L
        private const val WHEEL_STEP_DELAY_MS = 180L
        private const val VERIFY_TOLERANCE_MS = 120_000L
        private const val DEFAULT_STAGE_RETRIES = 7
        private const val SETTINGS_WAIT_RETRIES = 5
        private const val MAX_CALENDAR_MONTH_OFFSET = 60
        private const val MAX_WHEEL_VALUE_DISTANCE = 150

        private var activeService: TimeAccessibilityService? = null

        private val AUTOMATIC_TIME_LABELS = listOf(
            "automatic date", "automatic time", "set time automatically", "use network-provided time",
            "автоматическая дата", "автоматическое время", "использовать время сети", "автоматически",
            "настраивать время автоматически",
        )
        private val AUTOMATIC_MARKERS = listOf("automatic", "network", "автомат", "сети")
        private val DATE_LABELS = listOf("set date", "change date", "установить дату", "изменить дату", "дата")
        private val TIME_LABELS = listOf("set time", "change time", "установить время", "изменить время", "время")
        private val CONFIRM_LABELS = listOf("ok", "ок", "готово", "подтвердить", "done", "сохранить")
        private val CONFIRM_BUTTON_IDS = listOf("button1", "positive_button", "confirm_button", "ok_button")
        private val DATE_TEXT_MODE_LABELS = listOf(
            "switch to text input mode", "input mode", "text input", "keyboard input",
            "переключиться в режим текстового ввода", "режим текстового ввода", "ввод с клавиатуры", "режим ввода", "ввод даты",
        )
        private val TIME_TEXT_MODE_LABELS = listOf(
            "switch to text input mode", "keyboard input", "input mode", "text input",
            "переключиться в режим текстового ввода", "режим текстового ввода", "ввод с клавиатуры", "режим ввода", "ввод времени",
        )
        private val DATE_INPUT_TOGGLE_IDS = listOf(
            "date_picker_header_toggle", "mtrl_picker_header_toggle", "date_picker_toggle", "toggle_mode",
        )
        private val DATE_YEAR_HEADER_IDS = listOf("date_picker_header_year", "mtrl_picker_header_selection_text")
        private val DATE_HEADER_IDS = listOf("date_picker_header_date", "mtrl_picker_header_selection_text")
        private val DAY_PAGER_IDS = listOf("day_picker_view_pager", "calendar_view_pager", "month_pager")
        private val TIME_INPUT_TOGGLE_IDS = listOf(
            "input_mode", "time_picker_mode", "time_picker_header_toggle", "toggle_mode",
        )
        private val NEXT_MONTH_IDS = listOf("next", "next_month", "date_picker_next", "next_button")
        private val PREVIOUS_MONTH_IDS = listOf("prev", "previous", "previous_month", "date_picker_prev", "prev_button")
        private val NEXT_MONTH_LABELS = listOf("next month", "следующий месяц")
        private val PREVIOUS_MONTH_LABELS = listOf("previous month", "предыдущий месяц")
        private val DISPLAY_MONTHS = listOf(
            "январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
        )
        private val DAY_MONTH_NAMES = listOf(
            listOf("января"), listOf("февраля"), listOf("марта"), listOf("апреля"), listOf("мая"), listOf("июня"),
            listOf("июля"), listOf("августа"), listOf("сентября"), listOf("октября"), listOf("ноября"), listOf("декабря"),
        )
        private val HEADER_MONTH_NAMES = listOf(
            listOf("янв"), listOf("фев"), listOf("мар"), listOf("апр"), listOf("мая", "май"), listOf("июн"),
            listOf("июл"), listOf("авг"), listOf("сен"), listOf("окт"), listOf("ноя"), listOf("дек"),
        )
        private val YEAR_REGEX = Regex("\\b\\d{4}\\b")

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
            activeService?.returnToApp("Экстренная остановка выполнена. Выполнен возврат в приложение.")
        }
    }
}
