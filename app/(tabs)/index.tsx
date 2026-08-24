import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Clipboard from "expo-clipboard";
import * as Haptics from "expo-haptics";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert, AppState, FlatList, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View, type TextInputProps } from "react-native";

import { ScreenContainer } from "@/components/screen-container";
import { useColors } from "@/hooks/use-colors";
import { type CycleForm, formatDateTime, getDefaultForm, parseCycleForm, toFormStart } from "@/lib/cycle-utils";
import { formatJournalForCopy } from "@/lib/journal-export";
import {
  clearTimeEvents,
  getTimeControlStatus,
  isNativeTimeControlAvailable,
  requestShizukuPermission,
  setAutomaticTime,
  startTimeCycle,
  stopTimeCycle,
  type TimeControlStatus,
} from "@/lib/time-control";

const FORM_STORAGE_KEY = "time-cycler-form-v1";

const initialStatus: TimeControlStatus = {
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
  onFocus?: TextInputProps["onFocus"];
  placeholder?: string;
  keyboardType?: TextInputProps["keyboardType"];
  editable?: boolean;
};

function Field({ label, value, onChangeText, onFocus, placeholder = "", keyboardType = "default", editable = true }: FieldProps) {
  const colors = useColors();
  return (
    <View style={styles.fieldWrap}>
      <Text style={[styles.fieldLabel, { color: colors.muted }]}>{label}</Text>
      <TextInput
        value={value}
        onChangeText={onChangeText}
        onFocus={onFocus}
        placeholder={placeholder}
        placeholderTextColor={colors.muted}
        keyboardType={keyboardType}
        inputMode={keyboardType === "default" ? "text" : "numeric"}
        returnKeyType="done"
        editable={editable}
        style={[styles.input, { backgroundColor: colors.background, borderColor: colors.border, color: colors.text }]}
      />
    </View>
  );
}

type AdjustableField = keyof CycleForm;
type NumericField = Exclude<AdjustableField, "date" | "time">;

const fieldTitles: Record<AdjustableField, string> = {
  date: "Дата",
  time: "Время",
  stepDays: "Дней",
  stepHours: "Часов",
  stepMinutes: "Минут",
  pauseSeconds: "Пауза",
  totalCycles: "Циклов",
};

function normalizeNumericInput(value: string, allowNegative: boolean): string {
  const stripped = value.replace(/[^\d-]/g, "");
  const hasMinus = allowNegative && stripped.startsWith("-");
  const digits = stripped.replace(/-/g, "");
  return hasMinus ? `-${digits}` : digits;
}

async function getRealCurrentTimeMillis(): Promise<number> {
  const response = await fetch("https://www.google.com/generate_204", {
    method: "HEAD",
    cache: "no-store",
  });
  const dateHeader = response.headers.get("date");
  if (!dateHeader) throw new Error("Сервер времени не вернул дату.");
  const millis = Date.parse(dateHeader);
  if (!Number.isFinite(millis)) throw new Error("Не удалось распознать время сервера.");
  return millis;
}

export default function HomeScreen() {
  const colors = useColors();
  const scrollRef = useRef<ScrollView>(null);
  const [form, setForm] = useState<CycleForm>(() => getDefaultForm());
  const [status, setStatus] = useState<TimeControlStatus>(initialStatus);
  const [isBusy, setIsBusy] = useState(false);
  const [isLogExpanded, setIsLogExpanded] = useState(false);
  const [activeField, setActiveField] = useState<AdjustableField | null>(null);

  const refreshStatus = useCallback(async () => {
    try { setStatus(await getTimeControlStatus()); } catch { /* Shizuku may be restarting. */ }
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
    const interval = setInterval(() => void refreshStatus(), status.isRunning ? 700 : 4000);
    return () => clearInterval(interval);
  }, [refreshStatus, status.isRunning]);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", (nextState) => {
      if (nextState === "active") void refreshStatus();
    });
    return () => subscription.remove();
  }, [refreshStatus]);

  useEffect(() => {
    if (!status.lastAppliedMillis) return;
    const appliedStart = toFormStart(status.lastAppliedMillis);
    persistForm((previous) => previous.date === appliedStart.date && previous.time === appliedStart.time
      ? previous
      : { ...previous, ...appliedStart });
  }, [persistForm, status.lastAppliedMillis]);

  const parsed = useMemo(() => parseCycleForm(form), [form]);
  const shizukuReady = status.isShizukuRunning && status.isShizukuPermissionGranted;
  const running = status.isRunning;
  const activeCycle = running ? Math.max(1, Math.min(status.completedCycles + 1, status.totalCycles)) : 0;

  const updateField = (field: keyof CycleForm) => (value: string) => persistForm((previous) => ({ ...previous, [field]: value }));
  const updateNumericField = (field: NumericField, allowNegative = false) => (value: string) => {
    persistForm((previous) => ({ ...previous, [field]: normalizeNumericInput(value, allowNegative) }));
  };
  const setCurrentStart = async () => {
    if (running || isBusy) return;
    setIsBusy(true);
    try {
      const realNow = await getRealCurrentTimeMillis();
      persistForm((previous) => ({ ...previous, ...toFormStart(realNow) }));
      if (Platform.OS !== "web") await Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    } catch (error) {
      Alert.alert("Не удалось получить текущее время", error instanceof Error ? error.message : "Проверьте подключение к интернету.");
    } finally {
      setIsBusy(false);
    }
  };
  const focusField = (field: AdjustableField): NonNullable<TextInputProps["onFocus"]> => (event) => {
    setActiveField(field);
    if (Platform.OS !== "android") return;
    const target = event.target;
    setTimeout(() => {
      scrollRef.current?.scrollResponderScrollNativeHandleToKeyboard(target, 110, true);
    }, 250);
  };

  const adjustActiveField = (delta: number) => {
    if (!activeField || running) return;
    persistForm((previous) => {
      if (activeField === "date" || activeField === "time") {
        const dateMatch = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(previous.date.trim());
        const timeMatch = /^(\d{2}):(\d{2})$/.exec(previous.time.trim());
        if (!dateMatch || !timeMatch) return previous;
        const candidate = new Date(Number(dateMatch[3]), Number(dateMatch[2]) - 1, Number(dateMatch[1]), Number(timeMatch[1]), Number(timeMatch[2]), 0, 0);
        if (candidate.getFullYear() !== Number(dateMatch[3]) || candidate.getMonth() !== Number(dateMatch[2]) - 1 || candidate.getDate() !== Number(dateMatch[1])) return previous;
        if (activeField === "date") candidate.setDate(candidate.getDate() + delta);
        else candidate.setMinutes(candidate.getMinutes() + delta);
        return { ...previous, ...toFormStart(candidate.getTime()) };
      }
      const currentValue = previous[activeField].trim();
      const numeric = /^-?\d+$/.test(currentValue) ? Number(currentValue) : 0;
      let next = numeric + delta;
      if (activeField === "pauseSeconds") next = Math.max(0, next);
      if (activeField === "totalCycles") next = Math.max(1, Math.min(99999, next || 1));
      return { ...previous, [activeField]: String(next) };
    });
    if (Platform.OS !== "web") void Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
  };

  const handleRequestShizuku = async () => {
    try {
      const granted = await requestShizukuPermission();
      await refreshStatus();
      if (!granted) Alert.alert("Подтвердите Shizuku", "В Shizuku должна быть запущена служба. Затем подтвердите доступ для «Циклического времени».");
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
    setIsBusy(true);
    try {
      setStatus(await startTimeCycle(parsed.config));
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    } catch (error) {
      Alert.alert("Запуск не выполнен", error instanceof Error ? error.message : "Не удалось запустить цикл Shizuku.");
    } finally { setIsBusy(false); }
  };

  const handleEmergencyStop = async () => {
    setIsBusy(true);
    try {
      setStatus(await stopTimeCycle());
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
    } catch (error) {
      Alert.alert("Не удалось остановить", error instanceof Error ? error.message : "Повторите попытку.");
    } finally { setIsBusy(false); }
  };

  const handleClearLog = async () => {
    try { await clearTimeEvents(); await refreshStatus(); } catch (error) {
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
      <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === "ios" ? "padding" : undefined}>
        <ScrollView ref={scrollRef} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled" keyboardDismissMode={Platform.OS === "ios" ? "interactive" : "on-drag"}>
          <View style={styles.header}>
            <Text style={[styles.headerGlyph, { color: colors.primary }]}>◷</Text>
            <Text style={[styles.title, { color: colors.text }]}>Циклическое время</Text>
          </View>

          {!isNativeTimeControlAvailable && (
            <View style={[styles.previewNotice, { backgroundColor: colors.surface, borderColor: colors.warning }]}>
              <Text style={[styles.previewText, { color: colors.warning }]}>Изменение времени доступно в Android APK.</Text>
            </View>
          )}

          <View style={[styles.shizukuCard, { backgroundColor: colors.surface, borderColor: shizukuReady ? colors.success : colors.warning }]}>
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
              <Switch value={status.isAutomaticTimeEnabled} onValueChange={handleAutomaticTime} disabled={isBusy || running || !isNativeTimeControlAvailable} trackColor={{ false: colors.border, true: colors.success }} thumbColor={status.isAutomaticTimeEnabled ? "#FFFFFF" : colors.muted} />
            </View>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <View style={styles.cardHeading}>
              <Text style={[styles.cardTitle, { color: colors.text }]}>Старт</Text>
              <Pressable disabled={running || isBusy} onPress={setCurrentStart} style={({ pressed }) => [styles.nowButton, { borderColor: colors.primary }, (pressed || running || isBusy) && styles.pressed]}>
                <Text style={[styles.nowButtonText, { color: colors.primary }]}>Сейчас</Text>
              </Pressable>
            </View>
            <View style={styles.row}>
              <View style={styles.rowPrimary}><Field label="Дата" value={form.date} onChangeText={updateField("date")} onFocus={focusField("date")} placeholder="ДД.ММ.ГГГГ" editable={!running} /></View>
              <View style={styles.rowSecondary}><Field label="Время" value={form.time} onChangeText={updateField("time")} onFocus={focusField("time")} placeholder="ЧЧ:ММ" editable={!running} /></View>
            </View>
          </View>

          <View style={[styles.adjusterPanel, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.adjusterLabel, { color: colors.muted }]}>{activeField ? `Изменить: ${fieldTitles[activeField]}` : "Выберите поле для изменения"}</Text>
            <View style={[styles.adjuster, { backgroundColor: colors.background, borderColor: colors.border }]}>
              <Pressable disabled={running || !activeField} onPress={() => adjustActiveField(-1)} style={({ pressed }) => [styles.adjusterButton, (pressed || running || !activeField) && styles.pressed]}><Text style={[styles.adjusterGlyph, { color: colors.primary }]}>−</Text></Pressable>
              <View style={[styles.adjusterDivider, { backgroundColor: colors.border }]} />
              <Pressable disabled={running || !activeField} onPress={() => adjustActiveField(1)} style={({ pressed }) => [styles.adjusterButton, (pressed || running || !activeField) && styles.pressed]}><Text style={[styles.adjusterGlyph, { color: colors.primary }]}>+</Text></Pressable>
            </View>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>Шаг изменения</Text>
            <View style={styles.tripleRow}>
              <Field label="Дней" value={form.stepDays} onChangeText={updateNumericField("stepDays", true)} onFocus={focusField("stepDays")} keyboardType="numeric" editable={!running} />
              <Field label="Часов" value={form.stepHours} onChangeText={updateNumericField("stepHours", true)} onFocus={focusField("stepHours")} keyboardType="numeric" editable={!running} />
              <Field label="Минут" value={form.stepMinutes} onChangeText={updateNumericField("stepMinutes", true)} onFocus={focusField("stepMinutes")} keyboardType="numeric" editable={!running} />
            </View>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>Повторение</Text>
            <View style={styles.row}>
              <View style={styles.rowPrimary}><Field label="Пауза, сек." value={form.pauseSeconds} onChangeText={updateNumericField("pauseSeconds")} onFocus={focusField("pauseSeconds")} keyboardType="number-pad" editable={!running} /></View>
              <View style={styles.rowSecondary}><Field label="Циклов" value={form.totalCycles} onChangeText={updateNumericField("totalCycles")} onFocus={focusField("totalCycles")} keyboardType="number-pad" editable={!running} /></View>
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
                <FlatList data={status.events.slice().reverse()} scrollEnabled={false} keyExtractor={(item, index) => `${item.at}-${index}`} ListEmptyComponent={<Text style={[styles.emptyLogText, { color: colors.muted }]}>Пока нет событий.</Text>} renderItem={({ item }) => <View style={[styles.logItem, { backgroundColor: colors.background }]}><Text style={[styles.logTime, { color: colors.muted }]}>{formatDateTime(item.at)}</Text><Text style={[styles.logMessage, { color: colors.text }]}>{item.message}</Text></View>} />
              </View>
            )}
          </View>
        </ScrollView>

        <View style={[styles.footer, { backgroundColor: colors.background, borderTopColor: colors.border }]}>
          {running ? (
            <Pressable disabled={isBusy} onPress={handleEmergencyStop} style={({ pressed }) => [styles.stopButton, { backgroundColor: colors.error }, (pressed || isBusy) && styles.pressed]}><Text style={styles.primaryButtonText}>Цикл {activeCycle} из {status.totalCycles} · остановить</Text></Pressable>
          ) : (
            <Pressable disabled={isBusy || !isNativeTimeControlAvailable} onPress={handleStart} style={({ pressed }) => [styles.primaryButton, { backgroundColor: colors.primary }, (pressed || isBusy || !isNativeTimeControlAvailable) && styles.pressed]}><Text style={styles.primaryButtonText}>Запустить цикл</Text></Pressable>
          )}
        </View>
      </KeyboardAvoidingView>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 }, content: { paddingHorizontal: 16, paddingTop: 14, paddingBottom: 96, gap: 9 },
  header: { flexDirection: "row", alignItems: "center", gap: 8, marginBottom: 1 }, headerGlyph: { fontSize: 30, lineHeight: 34, fontWeight: "500" }, title: { fontSize: 22, fontWeight: "800", lineHeight: 27 },
  previewNotice: { borderRadius: 10, paddingVertical: 8, paddingHorizontal: 11, borderWidth: 1 }, previewText: { fontSize: 12, fontWeight: "600" },
  shizukuCard: { borderRadius: 14, padding: 10, borderWidth: 1, gap: 8 }, syncButton: { minHeight: 34, flexDirection: "row", alignItems: "center", justifyContent: "space-between", borderWidth: 1, borderRadius: 9, paddingHorizontal: 9 }, syncButtonText: { fontSize: 13, fontWeight: "800" }, syncChevron: { fontSize: 21, lineHeight: 21, fontWeight: "700" },
  automaticTimeRow: { borderTopWidth: 1, paddingTop: 8, flexDirection: "row", alignItems: "center", justifyContent: "space-between" }, automaticTimeText: { flex: 1, paddingRight: 8 }, automaticTimeTitle: { fontSize: 13, fontWeight: "800" }, automaticTimeHint: { fontSize: 11, marginTop: 2 },
  card: { borderRadius: 14, padding: 12, borderWidth: 1 }, cardHeading: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" }, cardTitle: { fontSize: 15, lineHeight: 20, fontWeight: "800" }, nowButton: { borderWidth: 1, borderRadius: 8, paddingHorizontal: 9, paddingVertical: 4 }, nowButtonText: { fontSize: 12, fontWeight: "800" },
  row: { flexDirection: "row", gap: 8, marginTop: 9 }, rowPrimary: { flex: 1.35 }, rowSecondary: { flex: 1 }, tripleRow: { flexDirection: "row", gap: 7, marginTop: 9 }, fieldWrap: { flex: 1 }, fieldLabel: { fontSize: 11, fontWeight: "700", marginBottom: 4 }, input: { borderWidth: 1, borderRadius: 9, paddingHorizontal: 9, height: 40, fontSize: 15, fontWeight: "700" },
  adjusterPanel: { borderRadius: 14, padding: 10, borderWidth: 1, gap: 6 }, adjusterLabel: { textAlign: "center", fontSize: 12, fontWeight: "800" }, adjuster: { height: 56, flexDirection: "row", borderRadius: 14, borderWidth: 1, overflow: "hidden" }, adjusterButton: { flex: 1, alignItems: "center", justifyContent: "center" }, adjusterDivider: { width: 1 }, adjusterGlyph: { fontSize: 34, lineHeight: 38, fontWeight: "700" },
  logCard: { borderRadius: 14, borderWidth: 1, overflow: "hidden" }, logToggle: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", padding: 12 }, chevron: { fontSize: 20, lineHeight: 20, fontWeight: "800" }, logActions: { flexDirection: "row", gap: 6, paddingHorizontal: 12, paddingBottom: 8 }, textAction: { paddingVertical: 5, paddingHorizontal: 7 }, textActionLabel: { fontSize: 12, fontWeight: "800" }, emptyLogText: { fontSize: 13, paddingHorizontal: 12, paddingBottom: 12 }, logItem: { marginHorizontal: 10, marginBottom: 7, borderRadius: 10, padding: 9 }, logTime: { fontSize: 10, fontWeight: "800", marginBottom: 3 }, logMessage: { fontSize: 12, lineHeight: 16 },
  footer: { paddingHorizontal: 16, paddingVertical: 10, borderTopWidth: 1 }, primaryButton: { height: 50, borderRadius: 14, alignItems: "center", justifyContent: "center" }, stopButton: { height: 50, borderRadius: 14, alignItems: "center", justifyContent: "center" }, primaryButtonText: { color: "#FFFFFF", fontSize: 16, fontWeight: "800" }, pressed: { opacity: 0.68, transform: [{ scale: 0.98 }] },
});
