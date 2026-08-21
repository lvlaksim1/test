import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Clipboard from "expo-clipboard";
import * as Haptics from "expo-haptics";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
  AppState,
  FlatList,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

import { ScreenContainer } from "@/components/screen-container";
import { type CycleForm, formatDateTime, getDefaultForm, parseCycleForm } from "@/lib/cycle-utils";
import { formatJournalForCopy } from "@/lib/journal-export";
import {
  clearAccessibilityEvents,
  getAccessibilityStatus,
  isNativeAccessibilityAvailable,
  openAccessibilitySettings,
  startAccessibilityCycle,
  stopAccessibilityCycle,
  type AccessibilityStatus,
} from "@/lib/time-accessibility";

const FORM_STORAGE_KEY = "time-cycler-form-v1";

const initialStatus: AccessibilityStatus = {
  isAccessibilityEnabled: false,
  isRunning: false,
  completedCycles: 0,
  totalCycles: 0,
  nextTargetMillis: null,
  events: [],
};

type FieldProps = {
  label: string;
  value: string;
  onChangeText: (value: string) => void;
  placeholder: string;
  keyboardType?: "default" | "number-pad";
};

function Field({ label, value, onChangeText, placeholder, keyboardType = "default" }: FieldProps) {
  return (
    <View style={styles.fieldWrap}>
      <Text style={styles.fieldLabel}>{label}</Text>
      <TextInput
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        placeholderTextColor="#79849A"
        keyboardType={keyboardType}
        returnKeyType="done"
        style={styles.input}
      />
    </View>
  );
}

export default function HomeScreen() {
  const [form, setForm] = useState<CycleForm>(() => getDefaultForm());
  const [status, setStatus] = useState<AccessibilityStatus>(initialStatus);
  const [isBusy, setIsBusy] = useState(false);
  const [isLogExpanded, setIsLogExpanded] = useState(false);

  const refreshStatus = useCallback(async () => {
    try {
      setStatus(await getAccessibilityStatus());
    } catch {
      // Android may momentarily restart the native service after the setting is changed.
    }
  }, []);

  useEffect(() => {
    AsyncStorage.getItem(FORM_STORAGE_KEY)
      .then((stored) => {
        if (stored) setForm(JSON.parse(stored) as CycleForm);
      })
      .catch(() => undefined);
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

  const parsed = useMemo(() => parseCycleForm(form), [form]);
  const enabled = status.isAccessibilityEnabled;
  const running = status.isRunning;

  const updateField = (field: keyof CycleForm) => (value: string) => {
    setForm((previous) => {
      const next = { ...previous, [field]: value };
      void AsyncStorage.setItem(FORM_STORAGE_KEY, JSON.stringify(next));
      return next;
    });
  };

  const handleEnableService = async () => {
    try {
      await openAccessibilitySettings();
    } catch (error) {
      Alert.alert("Недоступно", error instanceof Error ? error.message : "Не удалось открыть системные настройки.");
    }
  };

  const handleStart = async () => {
    if (!parsed.config) {
      Alert.alert("Проверьте параметры", parsed.error ?? "Заполните поля.");
      return;
    }
    if (!enabled) {
      Alert.alert("Сначала включите службу", "Откройте специальные возможности и включите службу приложения.");
      return;
    }
    setIsBusy(true);
    try {
      setStatus(await startAccessibilityCycle(parsed.config));
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    } catch (error) {
      Alert.alert("Запуск не выполнен", error instanceof Error ? error.message : "Служба не смогла начать автоматизацию.");
    } finally {
      setIsBusy(false);
    }
  };

  const handleEmergencyStop = async () => {
    setIsBusy(true);
    try {
      setStatus(await stopAccessibilityCycle());
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
    } catch (error) {
      Alert.alert("Не удалось остановить", error instanceof Error ? error.message : "Повторите попытку.");
    } finally {
      setIsBusy(false);
    }
  };

  const handleClearLog = async () => {
    try {
      await clearAccessibilityEvents();
      await refreshStatus();
    } catch (error) {
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
            <View style={styles.mark}><Text style={styles.markText}>↻</Text></View>
            <Text style={styles.title}>Циклическое время</Text>
          </View>

          {!isNativeAccessibilityAvailable && (
            <View style={styles.previewNotice}><Text style={styles.previewText}>Автоматизация доступна в Android APK.</Text></View>
          )}

          <View style={[styles.serviceCard, enabled ? styles.statusReady : styles.statusWaiting]}>
            <View style={styles.serviceLine}>
              <View style={[styles.statusDot, enabled ? styles.dotReady : styles.dotWaiting]} />
              <Text style={styles.statusTitle}>{enabled ? "Служба включена" : "Служба не включена"}</Text>
              {running && <Text style={styles.runningBadge}>Выполняется {status.completedCycles}/{status.totalCycles}</Text>}
            </View>
            {!enabled && (
              <Pressable onPress={handleEnableService} style={({ pressed }) => [styles.enableButton, pressed && styles.pressed]}>
                <Text style={styles.enableButtonText}>Включить службу</Text>
              </Pressable>
            )}
          </View>

          <View style={styles.card}>
            <Text style={styles.cardTitle}>Старт</Text>
            <View style={styles.row}>
              <View style={styles.rowPrimary}><Field label="Дата" value={form.date} onChangeText={updateField("date")} placeholder="ДД.ММ.ГГГГ" /></View>
              <View style={styles.rowSecondary}><Field label="Время" value={form.time} onChangeText={updateField("time")} placeholder="ЧЧ:ММ" /></View>
            </View>
          </View>

          <View style={styles.card}>
            <Text style={styles.cardTitle}>Шаг изменения</Text>
            <View style={styles.tripleRow}>
              <Field label="Дней" value={form.stepDays} onChangeText={updateField("stepDays")} placeholder="0" keyboardType="number-pad" />
              <Field label="Часов" value={form.stepHours} onChangeText={updateField("stepHours")} placeholder="2" keyboardType="number-pad" />
              <Field label="Минут" value={form.stepMinutes} onChangeText={updateField("stepMinutes")} placeholder="0" keyboardType="number-pad" />
            </View>
          </View>

          <View style={styles.card}>
            <Text style={styles.cardTitle}>Повторение</Text>
            <View style={styles.row}>
              <View style={styles.rowPrimary}><Field label="Пауза, сек." value={form.pauseSeconds} onChangeText={updateField("pauseSeconds")} placeholder="2" keyboardType="number-pad" /></View>
              <View style={styles.rowSecondary}><Field label="Циклов" value={form.totalCycles} onChangeText={updateField("totalCycles")} placeholder="2" keyboardType="number-pad" /></View>
            </View>
          </View>

          <View style={styles.logCard}>
            <Pressable onPress={() => setIsLogExpanded((previous) => !previous)} style={({ pressed }) => [styles.logToggle, pressed && styles.pressed]}>
              <Text style={styles.cardTitle}>Журнал действий</Text>
              <Text style={styles.chevron}>{isLogExpanded ? "⌃" : "⌄"}</Text>
            </Pressable>
            {isLogExpanded && (
              <View>
                <View style={styles.logActions}>
                  <Pressable onPress={handleCopyLog} disabled={!status.events.length} style={({ pressed }) => [styles.textAction, (!status.events.length || pressed) && styles.pressed]}><Text style={styles.textActionLabel}>Копировать</Text></Pressable>
                  <Pressable onPress={handleClearLog} disabled={!status.events.length} style={({ pressed }) => [styles.textAction, (!status.events.length || pressed) && styles.pressed]}><Text style={styles.textActionLabel}>Очистить</Text></Pressable>
                </View>
                <FlatList
                  data={status.events.slice().reverse()}
                  scrollEnabled={false}
                  keyExtractor={(item, index) => `${item.at}-${index}`}
                  ListEmptyComponent={<Text style={styles.emptyLogText}>Пока нет событий.</Text>}
                  renderItem={({ item }) => (
                    <View style={styles.logItem}>
                      <Text style={styles.logTime}>{formatDateTime(item.at)}</Text>
                      <Text style={styles.logMessage}>{item.message}</Text>
                    </View>
                  )}
                />
              </View>
            )}
          </View>
        </ScrollView>

        <View style={styles.footer}>
          {running ? (
            <Pressable disabled={isBusy} onPress={handleEmergencyStop} style={({ pressed }) => [styles.stopButton, (pressed || isBusy) && styles.pressed]}>
              <Text style={styles.primaryButtonText}>Экстренно остановить</Text>
            </Pressable>
          ) : (
            <Pressable disabled={isBusy || !isNativeAccessibilityAvailable} onPress={handleStart} style={({ pressed }) => [styles.primaryButton, (pressed || isBusy || !isNativeAccessibilityAvailable) && styles.pressed]}>
              <Text style={styles.primaryButtonText}>Запустить цикл</Text>
            </Pressable>
          )}
        </View>
      </View>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  content: { paddingHorizontal: 16, paddingTop: 14, paddingBottom: 12, gap: 9 },
  header: { flexDirection: "row", alignItems: "center", gap: 10, marginBottom: 2 },
  mark: { width: 36, height: 36, borderRadius: 12, backgroundColor: "#3157C9", alignItems: "center", justifyContent: "center" },
  markText: { color: "#FFFFFF", fontSize: 24, lineHeight: 28, fontWeight: "800" },
  title: { color: "#152033", fontSize: 22, fontWeight: "800", lineHeight: 27 },
  previewNotice: { backgroundColor: "#FFF8E9", borderRadius: 10, paddingVertical: 8, paddingHorizontal: 11, borderWidth: 1, borderColor: "#F4D697" },
  previewText: { color: "#765F34", fontSize: 12, fontWeight: "600" },
  serviceCard: { borderRadius: 14, padding: 12, borderWidth: 1 },
  statusReady: { backgroundColor: "#EDF9F2", borderColor: "#B4E4C9" },
  statusWaiting: { backgroundColor: "#FFF8E9", borderColor: "#F4D697" },
  serviceLine: { flexDirection: "row", alignItems: "center", gap: 7 },
  statusDot: { width: 9, height: 9, borderRadius: 5 },
  dotReady: { backgroundColor: "#1E8E5A" },
  dotWaiting: { backgroundColor: "#B7791F" },
  statusTitle: { color: "#152033", fontSize: 15, fontWeight: "800" },
  runningBadge: { marginLeft: "auto", color: "#1E8E5A", fontSize: 12, fontWeight: "800" },
  enableButton: { marginTop: 10, borderWidth: 1, borderColor: "#3157C9", borderRadius: 10, paddingVertical: 8, alignItems: "center" },
  enableButtonText: { color: "#3157C9", fontWeight: "800", fontSize: 13 },
  card: { backgroundColor: "#FFFFFF", borderRadius: 14, padding: 12, borderWidth: 1, borderColor: "#E1E7F0" },
  cardTitle: { color: "#152033", fontSize: 15, lineHeight: 20, fontWeight: "800" },
  row: { flexDirection: "row", gap: 8, marginTop: 9 },
  rowPrimary: { flex: 1.35 },
  rowSecondary: { flex: 1 },
  tripleRow: { flexDirection: "row", gap: 7, marginTop: 9 },
  fieldWrap: { flex: 1 },
  fieldLabel: { color: "#657187", fontSize: 11, fontWeight: "700", marginBottom: 4 },
  input: { backgroundColor: "#F6F8FC", borderWidth: 1, borderColor: "#D8E0EC", borderRadius: 9, paddingHorizontal: 9, height: 38, color: "#152033", fontSize: 14, fontWeight: "700" },
  logCard: { backgroundColor: "#FFFFFF", borderRadius: 14, borderWidth: 1, borderColor: "#E1E7F0", overflow: "hidden" },
  logToggle: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", padding: 12 },
  chevron: { color: "#3157C9", fontSize: 20, lineHeight: 20, fontWeight: "800" },
  logActions: { flexDirection: "row", gap: 6, paddingHorizontal: 12, paddingBottom: 8 },
  textAction: { paddingVertical: 5, paddingHorizontal: 7 },
  textActionLabel: { color: "#3157C9", fontSize: 12, fontWeight: "800" },
  emptyLogText: { color: "#79849A", fontSize: 13, paddingHorizontal: 12, paddingBottom: 12 },
  logItem: { marginHorizontal: 10, marginBottom: 7, backgroundColor: "#F6F8FC", borderRadius: 10, padding: 9 },
  logTime: { color: "#657187", fontSize: 10, fontWeight: "800", marginBottom: 3 },
  logMessage: { color: "#29364C", fontSize: 12, lineHeight: 16 },
  footer: { paddingHorizontal: 16, paddingVertical: 10, backgroundColor: "#F6F8FC", borderTopWidth: 1, borderTopColor: "#E1E7F0" },
  primaryButton: { height: 50, backgroundColor: "#3157C9", borderRadius: 14, alignItems: "center", justifyContent: "center" },
  stopButton: { height: 50, backgroundColor: "#C23B3B", borderRadius: 14, alignItems: "center", justifyContent: "center" },
  primaryButtonText: { color: "#FFFFFF", fontSize: 16, fontWeight: "800" },
  pressed: { opacity: 0.68, transform: [{ scale: 0.98 }] },
});
