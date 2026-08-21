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
import { useColors } from "@/hooks/use-colors";
import { type CycleForm, formatDateTime, getDefaultForm, parseCycleForm } from "@/lib/cycle-utils";
import { formatJournalForCopy } from "@/lib/journal-export";
import {
  clearAccessibilityEvents,
  getAccessibilityStatus,
  isNativeAccessibilityAvailable,
  openAccessibilitySettings,
  requestShizukuPermission,
  startAccessibilityCycle,
  stopAccessibilityCycle,
  type AccessibilityStatus,
} from "@/lib/time-accessibility";

const FORM_STORAGE_KEY = "time-cycler-form-v1";

const initialStatus: AccessibilityStatus = {
  isAccessibilityEnabled: false,
  isShizukuRunning: false,
  isShizukuPermissionGranted: false,
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

export default function HomeScreen() {
  const colors = useColors();
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
  const shizukuReady = status.isShizukuRunning && status.isShizukuPermissionGranted;
  const running = status.isRunning;

  const updateField = (field: keyof CycleForm) => (value: string) => {
    setForm((previous) => {
      const next = { ...previous, [field]: value };
      void AsyncStorage.setItem(FORM_STORAGE_KEY, JSON.stringify(next));
      return next;
    });
  };

  const handleOpenServiceSettings = async () => {
    try {
      await openAccessibilitySettings();
    } catch (error) {
      Alert.alert("Недоступно", error instanceof Error ? error.message : "Не удалось открыть системные настройки.");
    }
  };

  const handleRequestShizuku = async () => {
    try {
      const granted = await requestShizukuPermission();
      await refreshStatus();
      if (!granted) {
        Alert.alert("Подтвердите Shizuku", "В приложении Shizuku должна быть запущена служба. Затем подтвердите доступ для «Циклического времени».");
      }
    } catch (error) {
      Alert.alert("Shizuku недоступен", error instanceof Error ? error.message : "Установите и запустите Shizuku через беспроводную отладку.");
    }
  };

  const handleStart = async () => {
    if (!parsed.config) {
      Alert.alert("Проверьте параметры", parsed.error ?? "Заполните поля.");
      return;
    }
    if (!shizukuReady) {
      Alert.alert("Нужен Shizuku", "Запустите Shizuku и нажмите строку «Shizuku» в приложении, чтобы выдать доступ.");
      return;
    }
    if (!enabled) {
      Alert.alert("Сначала включите службу", "Нажмите «Служба» и включите приложение в специальных возможностях.");
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
              {running && <Text style={[styles.runningBadge, { color: colors.success }]}>{status.completedCycles}/{status.totalCycles}</Text>}
            </View>
            <Pressable onPress={handleRequestShizuku} style={({ pressed }) => [styles.syncButton, { borderColor: shizukuReady ? colors.success : colors.border }, pressed && styles.pressed]}>
              <Text style={[styles.syncButtonText, { color: shizukuReady ? colors.success : colors.muted }]}>
                {shizukuReady ? "Shizuku: доступ выдан" : status.isShizukuRunning ? "Shizuku: разрешить доступ" : "Shizuku: запустите службу"}
              </Text>
              <Text style={[styles.syncChevron, { color: colors.primary }]}>›</Text>
            </Pressable>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>Старт</Text>
            <View style={styles.row}>
              <View style={styles.rowPrimary}><Field label="Дата" value={form.date} onChangeText={updateField("date")} placeholder="ДД.ММ.ГГГГ" /></View>
              <View style={styles.rowSecondary}><Field label="Время" value={form.time} onChangeText={updateField("time")} placeholder="ЧЧ:ММ" /></View>
            </View>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>Шаг изменения</Text>
            <View style={styles.tripleRow}>
              <Field label="Дней" value={form.stepDays} onChangeText={updateField("stepDays")} keyboardType="number-pad" />
              <Field label="Часов" value={form.stepHours} onChangeText={updateField("stepHours")} keyboardType="number-pad" />
              <Field label="Минут" value={form.stepMinutes} onChangeText={updateField("stepMinutes")} keyboardType="number-pad" />
            </View>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>Повторение</Text>
            <View style={styles.row}>
              <View style={styles.rowPrimary}><Field label="Пауза, сек." value={form.pauseSeconds} onChangeText={updateField("pauseSeconds")} keyboardType="number-pad" /></View>
              <View style={styles.rowSecondary}><Field label="Циклов" value={form.totalCycles} onChangeText={updateField("totalCycles")} keyboardType="number-pad" /></View>
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
                  renderItem={({ item }) => (
                    <View style={[styles.logItem, { backgroundColor: colors.background }]}>
                      <Text style={[styles.logTime, { color: colors.muted }]}>{formatDateTime(item.at)}</Text>
                      <Text style={[styles.logMessage, { color: colors.text }]}>{item.message}</Text>
                    </View>
                  )}
                />
              </View>
            )}
          </View>
        </ScrollView>

        <View style={[styles.footer, { backgroundColor: colors.background, borderTopColor: colors.border }]}>
          {running ? (
            <Pressable disabled={isBusy} onPress={handleEmergencyStop} style={({ pressed }) => [styles.stopButton, { backgroundColor: colors.error }, (pressed || isBusy) && styles.pressed]}>
              <Text style={styles.primaryButtonText}>Экстренно остановить</Text>
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
  screen: { flex: 1 },
  content: { paddingHorizontal: 16, paddingTop: 14, paddingBottom: 12, gap: 9 },
  header: { flexDirection: "row", alignItems: "center", gap: 8, marginBottom: 1 },
  headerGlyph: { fontSize: 30, lineHeight: 34, fontWeight: "500" },
  title: { fontSize: 22, fontWeight: "800", lineHeight: 27 },
  previewNotice: { borderRadius: 10, paddingVertical: 8, paddingHorizontal: 11, borderWidth: 1 },
  previewText: { fontSize: 12, fontWeight: "600" },
  serviceCard: { borderRadius: 14, padding: 10, borderWidth: 1, gap: 8 },
  serviceLine: { flexDirection: "row", alignItems: "center", gap: 7 },
  serviceButton: { minHeight: 31, paddingHorizontal: 9, alignItems: "center", justifyContent: "center", borderWidth: 1, borderRadius: 9 },
  serviceButtonText: { fontSize: 12, fontWeight: "800" },
  statusDot: { width: 9, height: 9, borderRadius: 5 },
  statusTitle: { fontSize: 14, fontWeight: "800" },
  runningBadge: { marginLeft: "auto", fontSize: 12, fontWeight: "800" },
  syncButton: { minHeight: 30, flexDirection: "row", alignItems: "center", justifyContent: "space-between", borderWidth: 1, borderRadius: 9, paddingHorizontal: 9 },
  syncButtonText: { fontSize: 12, fontWeight: "700" },
  syncChevron: { fontSize: 21, lineHeight: 21, fontWeight: "700" },
  card: { borderRadius: 14, padding: 12, borderWidth: 1 },
  cardTitle: { fontSize: 15, lineHeight: 20, fontWeight: "800" },
  row: { flexDirection: "row", gap: 8, marginTop: 9 },
  rowPrimary: { flex: 1.35 },
  rowSecondary: { flex: 1 },
  tripleRow: { flexDirection: "row", gap: 7, marginTop: 9 },
  fieldWrap: { flex: 1 },
  fieldLabel: { fontSize: 11, fontWeight: "700", marginBottom: 4 },
  input: { borderWidth: 1, borderRadius: 9, paddingHorizontal: 9, height: 38, fontSize: 14, fontWeight: "700" },
  logCard: { borderRadius: 14, borderWidth: 1, overflow: "hidden" },
  logToggle: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", padding: 12 },
  chevron: { fontSize: 20, lineHeight: 20, fontWeight: "800" },
  logActions: { flexDirection: "row", gap: 6, paddingHorizontal: 12, paddingBottom: 8 },
  textAction: { paddingVertical: 5, paddingHorizontal: 7 },
  textActionLabel: { fontSize: 12, fontWeight: "800" },
  emptyLogText: { fontSize: 13, paddingHorizontal: 12, paddingBottom: 12 },
  logItem: { marginHorizontal: 10, marginBottom: 7, borderRadius: 10, padding: 9 },
  logTime: { fontSize: 10, fontWeight: "800", marginBottom: 3 },
  logMessage: { fontSize: 12, lineHeight: 16 },
  footer: { paddingHorizontal: 16, paddingVertical: 10, borderTopWidth: 1 },
  primaryButton: { height: 50, borderRadius: 14, alignItems: "center", justifyContent: "center" },
  stopButton: { height: 50, borderRadius: 14, alignItems: "center", justifyContent: "center" },
  primaryButtonText: { color: "#FFFFFF", fontSize: 16, fontWeight: "800" },
  pressed: { opacity: 0.68, transform: [{ scale: 0.98 }] },
});
