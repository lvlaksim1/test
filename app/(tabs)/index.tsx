import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Clipboard from "expo-clipboard";
import * as Haptics from "expo-haptics";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert, AppState, FlatList, KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, View, type TextInputProps } from "react-native";

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

const FORM_STORAGE_KEY = "time-machine-form-v2";
const CONFIGURATIONS_STORAGE_KEY = "time-machine-configurations-v1";
const ACTIVE_CONFIGURATION_STORAGE_KEY = "time-machine-active-configuration-v1";

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

type SavedConfiguration = {
  name: string;
  step: Pick<CycleForm, "stepDays" | "stepHours" | "stepMinutes">;
  repetition: Pick<CycleForm, "pauseSeconds" | "repeatsPerSeries" | "seriesPauseSeconds" | "totalSeries">;
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

type NumericField = Exclude<keyof CycleForm, "date" | "time">;

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

function makeConfiguration(name: string, form: CycleForm): SavedConfiguration {
  return {
    name,
    step: {
      stepDays: form.stepDays,
      stepHours: form.stepHours,
      stepMinutes: form.stepMinutes,
    },
    repetition: {
      pauseSeconds: form.pauseSeconds,
      repeatsPerSeries: form.repeatsPerSeries,
      seriesPauseSeconds: form.seriesPauseSeconds,
      totalSeries: form.totalSeries,
    },
  };
}

export default function HomeScreen() {
  const colors = useColors();
  const scrollRef = useRef<ScrollView>(null);
  const [form, setForm] = useState<CycleForm>(() => getDefaultForm());
  const [status, setStatus] = useState<TimeControlStatus>(initialStatus);
  const [isBusy, setIsBusy] = useState(false);
  const [isLogExpanded, setIsLogExpanded] = useState(false);
  const [isConfigurationsExpanded, setIsConfigurationsExpanded] = useState(false);
  const [configurations, setConfigurations] = useState<SavedConfiguration[]>([]);
  const [activeConfigurationName, setActiveConfigurationName] = useState<string | null>(null);
  const [isSaveDialogVisible, setIsSaveDialogVisible] = useState(false);
  const [configurationNameDraft, setConfigurationNameDraft] = useState("");

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
      if (stored) setForm({ ...getDefaultForm(), ...(JSON.parse(stored) as Partial<CycleForm>) });
    }).catch(() => undefined);

    AsyncStorage.getItem(CONFIGURATIONS_STORAGE_KEY).then((stored) => {
      if (!stored) return;
      const parsed = JSON.parse(stored) as SavedConfiguration[];
      if (Array.isArray(parsed)) setConfigurations(parsed);
    }).catch(() => undefined);

    AsyncStorage.getItem(ACTIVE_CONFIGURATION_STORAGE_KEY).then((stored) => {
      setActiveConfigurationName(stored?.trim() ? stored : null);
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
  const repeatsPerSeries = parsed.config?.repeatsPerSeries ?? 1;
  const totalSeries = parsed.config?.totalSeries ?? 1;
  const activeSeries = running ? Math.max(1, Math.min(Math.floor(status.completedCycles / repeatsPerSeries) + 1, totalSeries)) : 0;
  const activeRepeat = running ? Math.max(1, Math.min((status.completedCycles % repeatsPerSeries) + 1, repeatsPerSeries)) : 0;

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

  const focusField: NonNullable<TextInputProps["onFocus"]> = (event) => {
    if (Platform.OS !== "android") return;
    const target = event.target;
    setTimeout(() => {
      scrollRef.current?.scrollResponderScrollNativeHandleToKeyboard(target, 110, true);
    }, 250);
  };

  const handleRequestShizuku = async () => {
    try {
      const granted = await requestShizukuPermission();
      await refreshStatus();
      if (!granted) Alert.alert("Подтвердите Shizuku", "В Shizuku должна быть запущена служба. Затем подтвердите доступ для «Машины времени».");
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

  const openSaveConfigurationDialog = () => {
    setConfigurationNameDraft(activeConfigurationName ?? "");
    setIsSaveDialogVisible(true);
  };

  const closeSaveConfigurationDialog = () => {
    setIsSaveDialogVisible(false);
    setConfigurationNameDraft("");
  };

  const saveConfiguration = async (name: string, existingIndex: number) => {
    const nextConfiguration = makeConfiguration(name, form);
    const nextConfigurations = existingIndex >= 0
      ? configurations.map((configuration, index) => index === existingIndex ? nextConfiguration : configuration)
      : [...configurations, nextConfiguration];

    try {
      await AsyncStorage.multiSet([
        [CONFIGURATIONS_STORAGE_KEY, JSON.stringify(nextConfigurations)],
        [ACTIVE_CONFIGURATION_STORAGE_KEY, name],
      ]);
      setConfigurations(nextConfigurations);
      setActiveConfigurationName(name);
      closeSaveConfigurationDialog();
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
      Alert.alert("Конфигурация сохранена", `«${name}» сохранена.`);
    } catch (error) {
      Alert.alert("Не удалось сохранить конфигурацию", error instanceof Error ? error.message : "Повторите попытку.");
    }
  };

  const confirmSaveConfiguration = () => {
    const name = configurationNameDraft.trim();
    if (!name) {
      Alert.alert("Введите название конфигурации", "Название не может быть пустым.");
      return;
    }

    const existingIndex = configurations.findIndex((configuration) => configuration.name === name);
    if (existingIndex < 0) {
      void saveConfiguration(name, -1);
      return;
    }

    Alert.alert(
      "Перезаписать конфигурацию?",
      `Конфигурация «${name}» уже существует. Заменить сохранённые параметры текущими?`,
      [
        { text: "Отмена", style: "cancel" },
        { text: "Перезаписать", onPress: () => void saveConfiguration(name, existingIndex) },
      ],
    );
  };

  const loadConfiguration = async (configuration: SavedConfiguration) => {
    persistForm((previous) => ({
      ...previous,
      ...configuration.step,
      ...configuration.repetition,
    }));
    setActiveConfigurationName(configuration.name);

    try {
      await AsyncStorage.setItem(ACTIVE_CONFIGURATION_STORAGE_KEY, configuration.name);
      if (Platform.OS !== "web") await Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    } catch (error) {
      Alert.alert("Конфигурация загружена", "Параметры применены, но имя текущей конфигурации не удалось сохранить.");
    }
  };

  return (
    <ScreenContainer edges={["top", "left", "right", "bottom"]} containerClassName="bg-background">
      <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === "ios" ? "padding" : undefined}>
        <ScrollView ref={scrollRef} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled" keyboardDismissMode={Platform.OS === "ios" ? "interactive" : "on-drag"}>
          <View style={styles.header}>
            <Text style={[styles.headerGlyph, { color: colors.primary }]}>◷</Text>
            <Text style={[styles.title, { color: colors.text }]}>Машина времени</Text>
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
              <View style={styles.rowPrimary}><Field label="Дата" value={form.date} onChangeText={updateField("date")} onFocus={focusField} placeholder="ДД.ММ.ГГГГ" editable={!running} /></View>
              <View style={styles.rowSecondary}><Field label="Время" value={form.time} onChangeText={updateField("time")} onFocus={focusField} placeholder="ЧЧ:ММ" editable={!running} /></View>
            </View>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>Шаг изменения</Text>
            <View style={styles.tripleRow}>
              <Field label="Дней" value={form.stepDays} onChangeText={updateNumericField("stepDays", true)} onFocus={focusField} keyboardType="numeric" editable={!running} />
              <Field label="Часов" value={form.stepHours} onChangeText={updateNumericField("stepHours", true)} onFocus={focusField} keyboardType="numeric" editable={!running} />
              <Field label="Минут" value={form.stepMinutes} onChangeText={updateNumericField("stepMinutes", true)} onFocus={focusField} keyboardType="numeric" editable={!running} />
            </View>
          </View>

          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>Повторение</Text>

            <View style={styles.repeatGroup}>
              <Text style={[styles.repeatGroupTitle, { color: colors.muted }]}>Вложенный цикл</Text>
              <View style={styles.row}>
                <View style={styles.rowPrimary}><Field label="Пауза, сек." value={form.pauseSeconds} onChangeText={updateNumericField("pauseSeconds")} onFocus={focusField} keyboardType="number-pad" editable={!running} /></View>
                <View style={styles.rowSecondary}><Field label="Повторов" value={form.repeatsPerSeries} onChangeText={updateNumericField("repeatsPerSeries")} onFocus={focusField} keyboardType="number-pad" editable={!running} /></View>
              </View>
            </View>

            <View style={[styles.repeatDivider, { backgroundColor: colors.border }]} />

            <View style={styles.repeatGroup}>
              <Text style={[styles.repeatGroupTitle, { color: colors.muted }]}>Главный цикл</Text>
              <View style={styles.row}>
                <View style={styles.rowPrimary}><Field label="Особая пауза, сек." value={form.seriesPauseSeconds} onChangeText={updateNumericField("seriesPauseSeconds")} onFocus={focusField} keyboardType="number-pad" editable={!running} /></View>
                <View style={styles.rowSecondary}><Field label="Циклов" value={form.totalSeries} onChangeText={updateNumericField("totalSeries")} onFocus={focusField} keyboardType="number-pad" editable={!running} /></View>
              </View>
            </View>
          </View>

          <View style={[styles.logCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <View style={styles.configurationHeader}>
              <Pressable onPress={openSaveConfigurationDialog} style={({ pressed }) => [styles.saveConfigurationButton, { borderColor: colors.primary }, pressed && styles.pressed]}>
                <Text style={[styles.saveConfigurationButtonText, { color: colors.primary }]}>Сохранить конфигурацию</Text>
              </Pressable>
              <Pressable onPress={() => setIsConfigurationsExpanded((previous) => !previous)} hitSlop={8} style={({ pressed }) => [styles.configurationToggle, pressed && styles.pressed]}>
                <Text style={[styles.chevron, { color: colors.primary }]}>{isConfigurationsExpanded ? "⌃" : "⌄"}</Text>
              </Pressable>
            </View>
            {isConfigurationsExpanded && (
              <View style={styles.configurationList}>
                {configurations.length === 0 ? (
                  <Text style={[styles.emptyLogText, styles.configurationEmptyText, { color: colors.muted }]}>Нет сохранённых конфигураций.</Text>
                ) : (
                  configurations.map((configuration) => (
                    <Pressable key={configuration.name} disabled={running} onPress={() => void loadConfiguration(configuration)} style={({ pressed }) => [styles.configurationItem, { backgroundColor: colors.background }, (pressed || running) && styles.pressed]}>
                      <Text style={[styles.configurationItemName, { color: colors.text }]} numberOfLines={1}>{configuration.name}</Text>
                      {configuration.name === activeConfigurationName && <Text style={[styles.configurationCurrent, { color: colors.primary }]}>текущая</Text>}
                    </Pressable>
                  ))
                )}
              </View>
            )}
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
            <Pressable disabled={isBusy} onPress={handleEmergencyStop} style={({ pressed }) => [styles.stopButton, { backgroundColor: colors.error }, (pressed || isBusy) && styles.pressed]}><Text style={styles.primaryButtonText}>Цикл {activeSeries} из {totalSeries} · повтор {activeRepeat} из {repeatsPerSeries} · остановить</Text></Pressable>
          ) : (
            <Pressable disabled={isBusy || !isNativeTimeControlAvailable} onPress={handleStart} style={({ pressed }) => [styles.primaryButton, { backgroundColor: colors.primary }, (pressed || isBusy || !isNativeTimeControlAvailable) && styles.pressed]}><Text style={styles.primaryButtonText}>Запустить цикл</Text></Pressable>
          )}
        </View>
      </KeyboardAvoidingView>

      <Modal visible={isSaveDialogVisible} transparent animationType="fade" onRequestClose={closeSaveConfigurationDialog}>
        <KeyboardAvoidingView style={styles.modalBackdrop} behavior={Platform.OS === "ios" ? "padding" : undefined}>
          <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.modalTitle, { color: colors.text }]}>Сохранить конфигурацию</Text>
            <Text style={[styles.modalHint, { color: colors.muted }]}>Введите название конфигурации</Text>
            <TextInput autoFocus selectTextOnFocus value={configurationNameDraft} onChangeText={setConfigurationNameDraft} onSubmitEditing={confirmSaveConfiguration} returnKeyType="done" placeholder="Название" placeholderTextColor={colors.muted} style={[styles.modalInput, { backgroundColor: colors.background, borderColor: colors.border, color: colors.text }]} />
            <View style={styles.modalActions}>
              <Pressable onPress={closeSaveConfigurationDialog} style={({ pressed }) => [styles.modalActionButton, pressed && styles.pressed]}><Text style={[styles.modalCancelText, { color: colors.muted }]}>Отмена</Text></Pressable>
              <Pressable onPress={confirmSaveConfiguration} style={({ pressed }) => [styles.modalActionButton, styles.modalSaveButton, { backgroundColor: colors.primary }, pressed && styles.pressed]}><Text style={styles.modalSaveText}>Сохранить</Text></Pressable>
            </View>
          </View>
        </KeyboardAvoidingView>
      </Modal>
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
  repeatGroup: { marginTop: 8 }, repeatGroupTitle: { fontSize: 11, fontWeight: "800" }, repeatDivider: { height: 1, marginTop: 11 },
  logCard: { borderRadius: 14, borderWidth: 1, overflow: "hidden" }, logToggle: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", padding: 12 }, chevron: { fontSize: 20, lineHeight: 20, fontWeight: "800" }, logActions: { flexDirection: "row", gap: 6, paddingHorizontal: 12, paddingBottom: 8 }, textAction: { paddingVertical: 5, paddingHorizontal: 7 }, textActionLabel: { fontSize: 12, fontWeight: "800" }, emptyLogText: { fontSize: 13, paddingHorizontal: 12, paddingBottom: 12 }, logItem: { marginHorizontal: 10, marginBottom: 7, borderRadius: 10, padding: 9 }, logTime: { fontSize: 10, fontWeight: "800", marginBottom: 3 }, logMessage: { fontSize: 12, lineHeight: 16 },
  configurationHeader: { minHeight: 58, flexDirection: "row", alignItems: "center", justifyContent: "space-between", paddingHorizontal: 10, paddingVertical: 8 }, saveConfigurationButton: { flexShrink: 1, borderWidth: 1, borderRadius: 9, paddingHorizontal: 11, paddingVertical: 8 }, saveConfigurationButtonText: { fontSize: 13, fontWeight: "800" }, configurationToggle: { width: 44, height: 40, marginLeft: 28, alignItems: "center", justifyContent: "center", borderRadius: 9 }, configurationList: { paddingHorizontal: 10, paddingBottom: 10, gap: 7 }, configurationEmptyText: { paddingHorizontal: 2, paddingBottom: 2 }, configurationItem: { minHeight: 42, borderRadius: 10, paddingHorizontal: 11, paddingVertical: 9, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 10 }, configurationItemName: { flex: 1, fontSize: 13, fontWeight: "700" }, configurationCurrent: { fontSize: 11, fontWeight: "800" },
  modalBackdrop: { flex: 1, alignItems: "center", justifyContent: "center", paddingHorizontal: 22, backgroundColor: "rgba(0,0,0,0.45)" }, modalCard: { width: "100%", maxWidth: 430, borderWidth: 1, borderRadius: 16, padding: 16 }, modalTitle: { fontSize: 18, fontWeight: "800" }, modalHint: { fontSize: 12, marginTop: 5, marginBottom: 10 }, modalInput: { height: 44, borderWidth: 1, borderRadius: 10, paddingHorizontal: 11, fontSize: 15, fontWeight: "700" }, modalActions: { flexDirection: "row", justifyContent: "flex-end", gap: 8, marginTop: 14 }, modalActionButton: { minHeight: 40, minWidth: 88, borderRadius: 10, alignItems: "center", justifyContent: "center", paddingHorizontal: 12 }, modalSaveButton: { minWidth: 110 }, modalCancelText: { fontSize: 13, fontWeight: "800" }, modalSaveText: { color: "#FFFFFF", fontSize: 13, fontWeight: "800" },
  footer: { paddingHorizontal: 16, paddingVertical: 10, borderTopWidth: 1 }, primaryButton: { height: 50, borderRadius: 14, alignItems: "center", justifyContent: "center" }, stopButton: { minHeight: 50, borderRadius: 14, alignItems: "center", justifyContent: "center", paddingHorizontal: 10, paddingVertical: 8 }, primaryButtonText: { color: "#FFFFFF", fontSize: 16, fontWeight: "800", textAlign: "center" }, pressed: { opacity: 0.68, transform: [{ scale: 0.98 }] },
});