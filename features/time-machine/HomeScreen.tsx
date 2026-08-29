import * as Clipboard from "expo-clipboard";
import * as Haptics from "expo-haptics";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Alert, AppState, KeyboardAvoidingView, Platform, ScrollView, Text, View, type TextInputProps } from "react-native";

import { ScreenContainer } from "@/components/screen-container";
import { ConfigurationsCard } from "@/features/time-machine/components/ConfigurationsCard";
import { CycleFooter } from "@/features/time-machine/components/CycleFooter";
import { JournalCard } from "@/features/time-machine/components/JournalCard";
import { RepetitionCard, StartCard, StepCard } from "@/features/time-machine/components/CycleFormCards";
import { SaveConfigurationModal } from "@/features/time-machine/components/SaveConfigurationModal";
import { ShizukuCard } from "@/features/time-machine/components/ShizukuCard";
import { applyConfiguration, makeConfiguration, normalizeNumericInput, type NumericField, type SavedConfiguration } from "@/features/time-machine/form-utils";
import { getRealCurrentTimeMillis } from "@/features/time-machine/network-time";
import {
  loadStoredActiveConfiguration,
  loadStoredConfigurations,
  loadStoredForm,
  saveConfigurationState,
  saveStoredActiveConfiguration,
  saveStoredForm,
} from "@/features/time-machine/storage";
import { styles } from "@/features/time-machine/styles";
import { useColors } from "@/hooks/use-colors";
import { type CycleForm, getDefaultForm, parseCycleForm, toFormStart } from "@/lib/cycle-utils";
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
    try {
      setStatus(await getTimeControlStatus());
    } catch {
      // Shizuku may be restarting.
    }
  }, []);

  const persistForm = useCallback((updater: (previous: CycleForm) => CycleForm) => {
    setForm((previous) => {
      const next = updater(previous);
      void saveStoredForm(next);
      return next;
    });
  }, []);

  useEffect(() => {
    void loadStoredForm().then((stored) => {
      if (stored) setForm({ ...getDefaultForm(), ...stored });
    }).catch(() => undefined);

    void loadStoredConfigurations().then(setConfigurations).catch(() => undefined);
    void loadStoredActiveConfiguration().then(setActiveConfigurationName).catch(() => undefined);
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

  const updateField = (field: "date" | "time") => (value: string) => {
    persistForm((previous) => ({ ...previous, [field]: value }));
  };

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
    } finally {
      setIsBusy(false);
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
    setIsBusy(true);
    try {
      setStatus(await startTimeCycle(parsed.config));
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    } catch (error) {
      Alert.alert("Запуск не выполнен", error instanceof Error ? error.message : "Не удалось запустить цикл Shizuku.");
    } finally {
      setIsBusy(false);
    }
  };

  const handleEmergencyStop = async () => {
    setIsBusy(true);
    try {
      setStatus(await stopTimeCycle());
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning);
    } catch (error) {
      Alert.alert("Не удалось остановить", error instanceof Error ? error.message : "Повторите попытку.");
    } finally {
      setIsBusy(false);
    }
  };

  const handleClearLog = async () => {
    try {
      await clearTimeEvents();
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
      await saveConfigurationState(nextConfigurations, name);
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
    persistForm((previous) => applyConfiguration(previous, configuration));
    setActiveConfigurationName(configuration.name);
    try {
      await saveStoredActiveConfiguration(configuration.name);
      if (Platform.OS !== "web") await Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light);
    } catch {
      Alert.alert("Конфигурация загружена", "Параметры применены, но имя текущей конфигурации не удалось сохранить.");
    }
  };

  return (
    <ScreenContainer edges={["top", "left", "right", "bottom"]}>
      <KeyboardAvoidingView style={{ flex: 1 }} behavior={Platform.OS === "ios" ? "padding" : undefined}>
        <ScrollView
          ref={scrollRef}
          contentContainerStyle={styles.content}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode={Platform.OS === "ios" ? "interactive" : "on-drag"}
        >
          <View style={styles.header}>
            <Text style={[styles.headerGlyph, { color: colors.primary }]}>◷</Text>
            <Text style={[styles.title, { color: colors.text }]}>Машина времени</Text>
          </View>

          {!isNativeTimeControlAvailable && (
            <View style={[styles.previewNotice, { backgroundColor: colors.surface, borderColor: colors.warning }]}>
              <Text style={[styles.previewText, { color: colors.warning }]}>Изменение времени доступно в Android APK.</Text>
            </View>
          )}

          <ShizukuCard
            status={status}
            shizukuReady={shizukuReady}
            running={running}
            isBusy={isBusy}
            onRequestAccess={() => void handleRequestShizuku()}
            onAutomaticTimeChange={(enabled) => void handleAutomaticTime(enabled)}
          />
          <StartCard form={form} running={running} isBusy={isBusy} onNow={() => void setCurrentStart()} onFieldChange={updateField} onFocus={focusField} />
          <StepCard form={form} running={running} onNumericChange={updateNumericField} onFocus={focusField} />
          <RepetitionCard form={form} running={running} onNumericChange={updateNumericField} onFocus={focusField} />
          <ConfigurationsCard
            configurations={configurations}
            activeName={activeConfigurationName}
            expanded={isConfigurationsExpanded}
            running={running}
            onSave={openSaveConfigurationDialog}
            onToggle={() => setIsConfigurationsExpanded((previous) => !previous)}
            onLoad={(configuration) => void loadConfiguration(configuration)}
          />
          <JournalCard
            events={status.events}
            expanded={isLogExpanded}
            onToggle={() => setIsLogExpanded((previous) => !previous)}
            onCopy={() => void handleCopyLog()}
            onClear={() => void handleClearLog()}
          />
        </ScrollView>
        <CycleFooter
          running={running}
          isBusy={isBusy}
          activeSeries={activeSeries}
          totalSeries={totalSeries}
          activeRepeat={activeRepeat}
          repeatsPerSeries={repeatsPerSeries}
          onStart={() => void handleStart()}
          onStop={() => void handleEmergencyStop()}
        />
      </KeyboardAvoidingView>
      <SaveConfigurationModal
        visible={isSaveDialogVisible}
        name={configurationNameDraft}
        onNameChange={setConfigurationNameDraft}
        onCancel={closeSaveConfigurationDialog}
        onSave={confirmSaveConfiguration}
      />
    </ScreenContainer>
  );
}
