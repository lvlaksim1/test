import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Clipboard from "expo-clipboard";
import * as Haptics from "expo-haptics";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Alert, AppState, FlatList, Platform, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View } from "react-native";

import { ScreenContainer } from "@/components/screen-container";
import { useColors } from "@/hooks/use-colors";
import { type CycleForm, formatDateTime, getDefaultForm, parseCycleForm, toFormStart } from "@/lib/cycle-utils";
import { formatJournalForCopy } from "@/lib/journal-export";
import {
  clearAccessibilityEvents,
  getAccessibilityStatus,
  isNativeAccessibilityAvailable,
  requestShizukuPermission,
  setAutomaticTime,
  startAccessibilityCycle,
  stopAccessibilityCycle,
  type AccessibilityStatus,
} from "@/lib/time-accessibility";
import { openAccessibilitySettings } from "@/lib/time-accessibility";

const FORM_STORAGE_KEY = "time-cycler-form-v1";

const initialStatus: AccessibilityStatus = {
  isAccessibilityEnabled: false,
  isShizukuRunning: false,
  isShizukuPermissionGranted: false,
  isAutomaticTimeEnabled: true,
  isRunning: false,
  completedCycles: 0,
  totalCycles: 0,
  nextTargetMillis: null,
  lastAppliedMillis: null,
  events: [],
};

type FieldProps = {
  label: string;
  value: string;
  onChangeText: (value: string) => void;
  placeholder?: string;
  keyboardType?: "default" | "number-pad";
};

function Field({ label, value, onChangeText, placeholder = "", keyboardType = "default" }: FieldProps) {
  const colors = useColors();
  return (
    <View style={styles.fieldWrap}>
      <Text style={[styles.fieldLabel, { color: colors.muted }]}>{label}</Text>
      <TextInput
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor={colors.muted}
        keyboardType={keyboardType}
        returnKeyType="done"
        style={[styles.input, { backgroundColor: colors.background, borderColor: colors.border, color: colors.text }]}
      />
    </View>
  );
}

function CycleCountField({ value, onChangeText, onAdjust, disabled }: {
  value: string;
  onChangeText: (value: string) => void;
  onAdjust: (delta: number) => void;
  disabled: boolean;
}) {
  const colors = useColors();
  return (
    <View style={styles.fieldWrap}>
      <Text style={[styles.fieldLabel, { color: colors.muted }]}>Циклов</Text>
      <View style={[styles.countInput, { backgroundColor: colors.background, borderColor: colors.border }]}>
        <TextInput
          value={value}
          onChangeText={onChangeText}
          keyboardType="number-pad"
          returnKeyType="done"
          editable={!disabled}
          style={[styles.countTextInput, { color: colors.text }]}
        />
        <View style={[styles.countStepper, { borderLeftColor: colors.border }]}>
          <Pressable disabled={disabled} onPress={() => onAdjust(1)} style={({ pressed }) => [styles.stepperButton, pressed && styles.pressed]}>
            <Text style={[styles.stepperGlyph, { color: colors.primary }]}>⌃</Text>
          </Pressable>
          <Pressable disabled={disabled} onPress={() => onAdjust(-1)} style={({ pressed }) => [styles.stepperButton, { borderTopColor: colors.border }, pressed && styles.pressed]}>
            <Text style={[styles.stepperGlyph, { color: colors.primary }]}>⌄</Text>
          </Pressable>
        </View>
      </View>
    </View>
  );
}

export default function HomeScreen() {
  const colors = useColors();
  const [form, setForm] = useState<CycleForm>(() => getDefaultForm());
  const [status, setStatus] = useState<AccessibilityStatus>(initialStatus);
  const [isBusy, setIsBusy] = useState(false);
  const [isLogExpanded, setIsLogExpanded] = useState(false);

  const refreshStatus = useCallback(async () => {
    try { setStatus(await getAccessibilityStatus()); } catch { /* Service may be restarting. */ }
  }, []);

  const persistForm = useCallback((updater: (previous: CycleForm) => CycleForm) => {
    setForm((previous) => {
      const next = updater(previous);
      void AsyncStorage.setItem(FORM_STORAGE_KEY, JSON.stringify(next));
      return next;
    });
  }, []);

  useEffect(() => {
    AsyncStorage.getItem(FORM_STORAGE_KEY).then((stored) => {
      if (stored) setForm(JSON.parse(stored) as CycleForm);
    }).catch(() => undefined);
    void refreshStatus();
  }, [refreshStatus]);

  useEffect(() => {
    const interval = setInterval(() => void refreshStatus(), status.isRunning ? 1200 : 4000);
    return () => clearInterval(interval);
  }, [refreshStatus, status.isRunning]);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", (nextState) => {
      if (nextState === "active") void refreshStatus();
    });
    return () => subscription.remove();
  }, [refreshStatus]);

  useEffect(() => {
    if (!status.lastAppliedMillis || status.isRunning || status.totalCycles < 1 || status.completedCycles !== status.totalCycles) return;
    const completedStart = toFormStart(status.lastAppliedMillis);
    persistForm((previous) => previous.date === completedStart.date && previous.time === completedStart.time
      ? previous
      : { ...previous, ...completedStart });
  }, [persistForm, status.completedCycles, status.isRunning, status.lastAppliedMillis, status.totalCycles]);

  const parsed = useMemo(() => parseCycleForm(form), [form]);
  const enabled = status.isAccessibilityEnabled;
  const shizukuReady = status.isShizukuRunning && status.isShizukuPermissionGranted;
  const running = status.isRunning;
  const activeCycle = running ? Math.max(1, Math.min(status.completedCycles + 1, status.totalCycles)) : 0;

  const updateField = (field: keyof CycleForm) => (value: string) => persistForm((previous) => ({ ...previous, [field]: value }));
  const setCurrentStart = () => persistForm((previous) => ({ ...previous, ...toFormStart(Date.now()) }));
  const adjustCycleCount = (delta: number) => persistForm((previous) => {
    const numeric = /^\d+$/.test(previous.totalCycles.trim()) ? Number(previous.totalCycles) : 1;
    return { ...previous, totalCycles: String(Math.max(1, Math.min(1000, numeric + delta))) };
  });

  const handleOpenServiceSettings = async () => {
    try { await openAccessibilitySettings(); } catch (error) {
      Alert.alert("Недоступно", error instanceof Error ? error.message : "Не удалось открыть системные настройки.");
    }
  };

  const handleRequestShizuku = async () => {
    try {
      const granted = await requestShizukuPermission();
      await refreshStatus();
      if (!granted) Alert.alert("Подтвердите Shizuku", "В приложении Shizuku должна быть запущена служба. Затем подтвердите доступ для «Циклического времени».");
    } catch (error) {
      Alert.alert("Shizuku недоступен", error instanceof Error ? error.message : "Установите и запустите Shizuku через беспроводную отладку.");
    }
  };

  const handleAutomaticTime = async (enabledValue: boolean) => {
    if (!shizukuReady) {
      Alert.alert("Нужен Shizuku", "Сначала запустите Shizuku и выдайте доступ приложению.");
      return;
    }
    setIsBusy(true);
    try {
      setStatus(await setAutomaticTime(enabledValue));
      if (Platform.OS !== "web") await Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    } catch (error) {
      Alert.alert("Синхронизация не изменена", error instanceof Error ? error.message : "Повторите попытку.");
    } finally { setIsBusy(false); }
  };

  const handleStart = async () => {
    if (!parsed.config) { Alert.alert("Проверьте параметры", parsed.error ?? "Заполните поля."); return; }
    if (!shizukuReady) { Alert.alert("Нужен Shizuku", "Запустите Shizuku и нажмите строку «Shizuku» в приложении, чтобы выдать доступ."); return; }
    if (!enabled) { Alert.alert("Сначала включите службу", "Нажмите «Служба» и включите приложение в специальных возможностях."); return; }
    setIsBusy(true);
    try {
      setStatus(await startAccessibilityCycle(parsed.config));
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    } catch (error) {
      Alert.alert("Запуск не выполнен", error instanceof Error ? error.message : "Служба не смогла начать автоматизацию.");
    } finally { setIsBusy(false); }
  };

  const handleEmergencyStop = async () => {
    setIsBusy(true);
    try {
      setStatus(await stopAccessibilityCycle());
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
    } catch (error) {
      Alert.alert("Не удалось остановить", error instanceof Error ? error.message : "Повторите попытку.");
    } finally { setIsBusy(false); }
  };

  const handleClearLog = async () => {
    try { await clearAccessibilityEvents(); await refreshStatus(); } catch (error) {
      Alert.alert("Не удалось очистить журнал", error instanceof Error ? error.message : "Повторите попытку.");
    }
  };

  const handleCopyLog = async () => {
    if (!status.events.length) return;
    try {
      await Clipboard.setStringAsync(formatJournalForCopy(status.events));
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      Alert.alert("Журнал скопирован", "Все записи помещены в буфер обмена.");
    } catch (error) {
      Alert.alert("Не удалось скопировать", error instanceof Error ? error.message : "Повторите попытку.");
    }
  };

  return (
    <ScreenContainer edges={["top", "left", "right", "bottom"]} containerClassName="bg-background">
      <View style={styles.screen}>
        <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
          <View style={styles.header}>
            <Text style={[styles.headerGlyph, { color: colors.primary }]}>◷</Text>
            <Text style={[styles.title, { color: colors.text }]}>Циклическое время</Text>
          </View>

          {!isNativeAccessibilityAvailable && (
            <View style={[styles.previewNotice, { backgroundColor: colors.surface, borderColor: colors.warning }]}>
              <Text style={[styles.previewText, { color: colors.warning }]}>Автоматизация доступна в Android APK.</Text>
            </View>
          )}

          <View style={[styles.serviceCard, { backgroundColor: colors.surface, borderColor: enabled ? colors.success : colors.warning }]}>
            <View style={styles.serviceLine}>
              <Pressable onPress={handleOpenServiceSettings} style={({ pressed }) => [styles.serviceButton, { borderColor: colors.primary }, pressed && styles.pressed]}>
                <Text style={[styles.serviceButtonText, { color: colors.primary }]}>Служба</Text>
              </Pressable>
              <View style={[styles.statusDot, { backgroundColor: enabled ? colors.success : colors.warning }]} />
              <Text style={[styles.statusTitle, { color: colors.text }]}>{enabled ? "Включена" : "Не включена"}</Text>
              {running && <Text style={[styles.runningBadge, { color: colors.success }]}>Цикл {activeCycle}/{status.totalCycles}</Text>}
            </View>
            <Pressable onPress={handleRequestShizuku} style={({ pressed }) => [styles.syncButton, { borderColor: shizukuReady ? colors.success : colors.border }, pressed && styles.pressed]}>
              <Text style={[styles.syncButtonText, { color: shizukuReady ? colors.success : colors.muted }]}>
                {shizukuReady ? "Shizuku: доступ выдан" : status.isShizukuRunning ? "Shizuku: разрешить доступ" : "Shizuku: запустите службу"}
              </Text>
              <Text style={[styles.syncChevron, { color: colors.primary }]}>›</Text>
            </Pressable>
            <View style={[styles.automaticTimeRow, { borderTopColor: colors.border }]}>
              <View style={styles.automaticTimeText}>
                <Text style={[styles.automaticTimeTitle, { color: colors.text }]}>Синхронизация времени</Text>
                <Text style={[styles.automaticTimeHint, { color: colors.muted }]}>Получать дату и время из сети</Text>
              </View>
              <Switch
                value={status.isAutomaticTimeEnabled}
                onValueChange={handleAutomaticTime}
                disabled={isBusy || running || !isNativeAccessibilityAvailable}
                trackColor={{ false: colors.border, true: colors.success }}
                thumbColor={status.isAutomaticTimeEnabled ? "#FFFFFF" : colors.muted}
              />
            </View>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <View style={styles.cardHeading}>
              <Text style={[styles.cardTitle, { color: colors.text }]}>Старт</Text>
              <Pressable disabled={running} onPress={setCurrentStart} style={({ pressed }) => [styles.nowButton, { borderColor: colors.primary }, (pressed || running) && styles.pressed]}>
                <Text style={[styles.nowButtonText, { color: colors.primary }]}>Сейчас</Text>
              </Pressable>
            </View>
            <View style={styles.row}>
              <View style={styles.rowPrimary}><Field label="Дата" value={form.date} onChangeText={updateField("date")} placeholder="ДД.ММ.ГГГГ" /></View>
              <View style={styles.rowSecondary}><Field label="Время" value={form.time} onChangeText={updateField("time")} placeholder="ЧЧ:ММ" /></View>
            </View>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>Шаг изменения</Text>
            <View style={styles.tripleRow}>
              <Field label="Дней" value={form.stepDays} onChangeText={updateField("stepDays")} />
              <Field label="Часов" value={form.stepHours} onChangeText={updateField("stepHours")} />
              <Field label="Минут" value={form.stepMinutes} onChangeText={updateField("stepMinutes")} />
            </View>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>Повторение</Text>
            <View style={styles.row}>
              <View style={styles.rowPrimary}><Field label="Пауза, сек." value={form.pauseSeconds} onChangeText={updateField("pauseSeconds")} keyboardType="number-pad" /></View>
              <View style={styles.rowSecondary}><CycleCountField value={form.totalCycles} onChangeText={updateField("totalCycles")} onAdjust={adjustCycleCount} disabled={running} /></View>
            </View>
          </View>

          <View style={[styles.logCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Pressable onPress={() => setIsLogExpanded((previous) => !previous)} style={({ pressed }) => [styles.logToggle, pressed && styles.pressed]}>
              <Text style={[styles.cardTitle, { color: colors.text }]}>Журнал действий</Text>
              <Text style={[styles.chevron, { color: colors.primary }]}>{isLogExpanded ? "⌃" : "⌄"}</Text>
            </Pressable>
            {isLogExpanded && (
              <View>
                <View style={styles.logActions}>
                  <Pressable onPress={handleCopyLog} disabled={!status.events.length} style={({ pressed }) => [styles.textAction, (!status.events.length || pressed) && styles.pressed]}><Text style={[styles.textActionLabel, { color: colors.primary }]}>Копировать</Text></Pressable>
                  <Pressable onPress={handleClearLog} disabled={!status.events.length} style={({ pressed }) => [styles.textAction, (!status.events.length || pressed) && styles.pressed]}><Text style={[styles.textActionLabel, { color: colors.primary }]}>Очистить</Text></Pressable>
                </View>
                <FlatList
                  data={status.events.slice().reverse()}
                  scrollEnabled={false}
                  keyExtractor={(item, index) => `${item.at}-${index}`}
                  ListEmptyComponent={<Text style={[styles.emptyLogText, { color: colors.muted }]}>Пока нет событий.</Text>}
                  renderItem={({ item }) => <View style={[styles.logItem, { backgroundColor: colors.background }]}><Text style={[styles.logTime, { color: colors.muted }]}>{formatDateTime(item.at)}</Text><Text style={[styles.logMessage, { color: colors.text }]}>{item.message}</Text></View>}
                />
              </View>
            )}
          </View>
        </ScrollView>

        <View style={[styles.footer, { backgroundColor: colors.background, borderTopColor: colors.border }]}>
          {running ? (
            <Pressable disabled={isBusy} onPress={handleEmergencyStop} style={({ pressed }) => [styles.stopButton, { backgroundColor: colors.error }, (pressed || isBusy) && styles.pressed]}>
              <Text style={styles.primaryButtonText}>Цикл {activeCycle} из {status.totalCycles} · остановить</Text>
            </Pressable>
          ) : (
            <Pressable disabled={isBusy || !isNativeAccessibilityAvailable} onPress={handleStart} style={({ pressed }) => [styles.primaryButton, { backgroundColor: colors.primary }, (pressed || isBusy || !isNativeAccessibilityAvailable) && styles.pressed]}>
              <Text style={styles.primaryButtonText}>Запустить цикл</Text>
            </Pressable>
          )}
        </View>
      </View>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 }, content: { paddingHorizontal: 16, paddingTop: 14, paddingBottom: 12, gap: 9 },
  header: { flexDirection: "row", alignItems: "center", gap: 8, marginBottom: 1 }, headerGlyph: { fontSize: 30, lineHeight: 34, fontWeight: "500" }, title: { fontSize: 22, fontWeight: "800", lineHeight: 27 },
  previewNotice: { borderRadius: 10, paddingVertical: 8, paddingHorizontal: 11, borderWidth: 1 }, previewText: { fontSize: 12, fontWeight: "600" },
  serviceCard: { borderRadius: 14, padding: 10, borderWidth: 1, gap: 8 }, serviceLine: { flexDirection: "row", alignItems: "center", gap: 7 }, serviceButton: { minHeight: 31, paddingHorizontal: 9, alignItems: "center", justifyContent: "center", borderWidth: 1, borderRadius: 9 }, serviceButtonText: { fontSize: 12, fontWeight: "800" }, statusDot: { width: 9, height: 9, borderRadius: 5 }, statusTitle: { fontSize: 14, fontWeight: "800" }, runningBadge: { marginLeft: "auto", fontSize: 12, fontWeight: "800" },
  syncButton: { minHeight: 30, flexDirection: "row", alignItems: "center", justifyContent: "space-between", borderWidth: 1, borderRadius: 9, paddingHorizontal: 9 }, syncButtonText: { fontSize: 12, fontWeight: "700" }, syncChevron: { fontSize: 21, lineHeight: 21, fontWeight: "700" },
  automaticTimeRow: { borderTopWidth: 1, paddingTop: 8, flexDirection: "row", alignItems: "center", justifyContent: "space-between" }, automaticTimeText: { flex: 1, paddingRight: 8 }, automaticTimeTitle: { fontSize: 13, fontWeight: "800" }, automaticTimeHint: { fontSize: 11, marginTop: 2 },
  card: { borderRadius: 14, padding: 12, borderWidth: 1 }, cardHeading: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" }, cardTitle: { fontSize: 15, lineHeight: 20, fontWeight: "800" }, nowButton: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 9, paddingVertical: 4 }, nowButtonText: { fontSize: 12, fontWeight: "800" },
  row: { flexDirection: "row", gap: 8, marginTop: 9 }, rowPrimary: { flex: 1.35 }, rowSecondary: { flex: 1 }, tripleRow: { flexDirection: "row", gap: 7, marginTop: 9 }, fieldWrap: { flex: 1 }, fieldLabel: { fontSize: 11, fontWeight: "700", marginBottom: 4 }, input: { borderWidth: 1, borderRadius: 9, paddingHorizontal: 9, height: 38, fontSize: 14, fontWeight: "700" },
  countInput: { height: 38, borderWidth: 1, borderRadius: 9, flexDirection: "row", overflow: "hidden" }, countTextInput: { flex: 1, paddingHorizontal: 9, fontSize: 14, fontWeight: "700" }, countStepper: { width: 30, borderLeftWidth: 1 }, stepperButton: { flex: 1, alignItems: "center", justifyContent: "center" }, stepperGlyph: { fontSize: 14, fontWeight: "900", lineHeight: 14 },
  logCard: { borderRadius: 14, borderWidth: 1, overflow: "hidden" }, logToggle: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", padding: 12 }, chevron: { fontSize: 20, lineHeight: 20, fontWeight: "800" }, logActions: { flexDirection: "row", gap: 6, paddingHorizontal: 12, paddingBottom: 8 }, textAction: { paddingVertical: 5, paddingHorizontal: 7 }, textActionLabel: { fontSize: 12, fontWeight: "800" }, emptyLogText: { fontSize: 13, paddingHorizontal: 12, paddingBottom: 12 }, logItem: { marginHorizontal: 10, marginBottom: 7, borderRadius: 10, padding: 9 }, logTime: { fontSize: 10, fontWeight: "800", marginBottom: 3 }, logMessage: { fontSize: 12, lineHeight: 16 },
  footer: { paddingHorizontal: 16, paddingVertical: 10, borderTopWidth: 1 }, primaryButton: { height: 50, borderRadius: 14, alignItems: "center", justifyContent: "center" }, stopButton: { height: 50, borderRadius: 14, alignItems: "center", justifyContent: "center" }, primaryButtonText: { color: "#FFFFFF", fontSize: 16, fontWeight: "800" }, pressed: { opacity: 0.68, transform: [{ scale: 0.98 }] },
});
