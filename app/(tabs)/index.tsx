import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Clipboard from "expo-clipboard";
import * as Haptics from "expo-haptics";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  Alert,
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
import {
  type CycleForm,
  formatDateTime,
  getDefaultForm,
  parseCycleForm,
  targetAt,
} from "@/lib/cycle-utils";
import {
  clearAccessibilityEvents,
  getAccessibilityStatus,
  isNativeAccessibilityAvailable,
  openAccessibilitySettings,
  startAccessibilityCycle,
  stopAccessibilityCycle,
  type AccessibilityStatus,
} from "@/lib/time-accessibility";
import { formatJournalForCopy } from "@/lib/journal-export";

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

  const refreshStatus = useCallback(async () => {
    try {
      setStatus(await getAccessibilityStatus());
    } catch {
      // The explanatory status card remains visible when Android has stopped the native service.
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
    const interval = setInterval(() => void refreshStatus(), status.isRunning ? 1500 : 5000);
    return () => clearInterval(interval);
  }, [refreshStatus, status.isRunning]);

  const parsed = useMemo(() => parseCycleForm(form), [form]);
  const preview = parsed.config ? targetAt(parsed.config, Math.max(status.completedCycles, 0)) : null;

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
    if (!status.isAccessibilityEnabled) {
      Alert.alert(
        "Сначала включите службу",
        "Android должен вручную включить службу в разделе «Специальные возможности». После этого вернитесь в приложение.",
      );
      return;
    }
    setIsBusy(true);
    try {
      const nextStatus = await startAccessibilityCycle(parsed.config);
      setStatus(nextStatus);
      if (Platform.OS !== "web") await Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success);
    } catch (error) {
      Alert.alert("Запуск не выполнен", error instanceof Error ? error.message : "Служба не смогла начать автоматизацию.");
    } finally {
      setIsBusy(false);
    }
  };

  const handleStop = async () => {
    setIsBusy(true);
    try {
      setStatus(await stopAccessibilityCycle());
      if (Platform.OS !== "web") await Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium);
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
      Alert.alert("Журнал скопирован", "Все записи, включая диагностику системного диалога, помещены в буфер обмена.");
    } catch (error) {
      Alert.alert("Не удалось скопировать", error instanceof Error ? error.message : "Повторите попытку.");
    }
  };

  const enabled = status.isAccessibilityEnabled;
  const running = status.isRunning;

  return (
    <ScreenContainer edges={["top", "left", "right", "bottom"]} containerClassName="bg-background">
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <View style={styles.header}>
          <View style={styles.mark}><Text style={styles.markText}>↻</Text></View>
          <View style={styles.headerCopy}>
            <Text style={styles.title}>Циклическое время</Text>
            <Text style={styles.subtitle}>Настройте смену даты и времени по шагам</Text>
          </View>
        </View>

        {!isNativeAccessibilityAvailable && (
          <View style={[styles.notice, styles.warningNotice]}>
            <Text style={styles.noticeTitle}>Предпросмотр интерфейса</Text>
            <Text style={styles.noticeText}>Автоматизация доступна только в собранном Android APK, а не в браузере.</Text>
          </View>
        )}

        <View style={[styles.statusCard, enabled ? styles.statusReady : styles.statusWaiting]}>
          <View style={styles.statusTopline}>
            <View style={[styles.statusDot, enabled ? styles.dotReady : styles.dotWaiting]} />
            <Text style={styles.statusTitle}>{enabled ? "Служба включена" : "Служба не включена"}</Text>
          </View>
          <Text style={styles.statusText}>
            {enabled
              ? "Приложение может взаимодействовать с экраном даты и времени Android."
              : "Нажмите кнопку ниже и вручную разрешите специальную возможность в настройках Android."}
          </Text>
          {!enabled && (
            <Pressable onPress={handleEnableService} style={({ pressed }) => [styles.secondaryButton, pressed && styles.pressed]}>
              <Text style={styles.secondaryButtonText}>Открыть специальные возможности</Text>
            </Pressable>
          )}
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>1. Стартовые дата и время</Text>
          <View style={styles.row}>
            <View style={styles.rowPrimary}><Field label="Дата" value={form.date} onChangeText={updateField("date")} placeholder="ДД.ММ.ГГГГ" /></View>
            <View style={styles.rowSecondary}><Field label="Время" value={form.time} onChangeText={updateField("time")} placeholder="ЧЧ:ММ" /></View>
          </View>
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>2. Шаг изменения</Text>
          <View style={styles.tripleRow}>
            <Field label="Дней" value={form.stepDays} onChangeText={updateField("stepDays")} placeholder="0" keyboardType="number-pad" />
            <Field label="Часов" value={form.stepHours} onChangeText={updateField("stepHours")} placeholder="0" keyboardType="number-pad" />
            <Field label="Минут" value={form.stepMinutes} onChangeText={updateField("stepMinutes")} placeholder="0" keyboardType="number-pad" />
          </View>
        </View>

        <View style={styles.card}>
          <Text style={styles.cardTitle}>3–4. Повторение</Text>
          <View style={styles.row}>
            <View style={styles.rowPrimary}><Field label="Пауза, секунд" value={form.pauseSeconds} onChangeText={updateField("pauseSeconds")} placeholder="10" keyboardType="number-pad" /></View>
            <View style={styles.rowSecondary}><Field label="Циклов" value={form.totalCycles} onChangeText={updateField("totalCycles")} placeholder="1" keyboardType="number-pad" /></View>
          </View>
        </View>

        <View style={styles.previewCard}>
          <Text style={styles.previewLabel}>{running ? "Следующее целевое значение" : "Первое целевое значение"}</Text>
          <Text style={styles.previewValue}>{formatDateTime(status.nextTargetMillis ?? preview?.getTime())}</Text>
          <Text style={styles.previewSubtext}>
            {running ? `Выполнено ${status.completedCycles} из ${status.totalCycles}` : "Первый цикл установит стартовую дату и время."}
          </Text>
        </View>

        <Pressable
          disabled={isBusy || !isNativeAccessibilityAvailable}
          onPress={running ? handleStop : handleStart}
          style={({ pressed }) => [styles.primaryButton, running && styles.stopButton, (pressed || isBusy || !isNativeAccessibilityAvailable) && styles.pressed]}
        >
          <Text style={styles.primaryButtonText}>{running ? "Остановить цикл" : "Запустить цикл"}</Text>
        </Pressable>

        <View style={[styles.notice, styles.warningNotice]}>
          <Text style={styles.noticeTitle}>Важно</Text>
          <Text style={styles.noticeText}>Автоматизация зависит от языка, версии Android и оболочки производителя. Перед первым запуском проверьте один цикл при разблокированном экране.</Text>
        </View>

        <View style={styles.logHeading}>
          <Text style={styles.cardTitle}>Журнал действий</Text>
          {status.events.length > 0 && (
            <View style={styles.logActions}>
              <Pressable onPress={handleCopyLog} style={({ pressed }) => [styles.copyButton, pressed && styles.pressed]}>
                <Text style={styles.copyButtonText}>Копировать</Text>
              </Pressable>
              <Pressable onPress={handleClearLog} style={({ pressed }) => [styles.clearButton, pressed && styles.pressed]}>
                <Text style={styles.clearButtonText}>Очистить</Text>
              </Pressable>
            </View>
          )}
        </View>
        <FlatList
          data={status.events.slice().reverse()}
          scrollEnabled={false}
          keyExtractor={(item, index) => `${item.at}-${index}`}
          ListEmptyComponent={<View style={styles.emptyLog}><Text style={styles.emptyLogText}>Пока нет событий.</Text></View>}
          renderItem={({ item }) => (
            <View style={styles.logItem}>
              <Text style={styles.logTime}>{formatDateTime(item.at)}</Text>
              <Text style={styles.logMessage}>{item.message}</Text>
            </View>
          )}
        />
      </ScrollView>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  content: { padding: 20, paddingBottom: 36, gap: 14 },
  header: { flexDirection: "row", alignItems: "center", gap: 12, marginBottom: 4 },
  mark: { width: 46, height: 46, borderRadius: 15, backgroundColor: "#3157C9", alignItems: "center", justifyContent: "center" },
  markText: { color: "#FFFFFF", fontSize: 30, lineHeight: 34, fontWeight: "700" },
  headerCopy: { flex: 1 },
  title: { color: "#152033", fontSize: 25, fontWeight: "800", lineHeight: 30 },
  subtitle: { color: "#657187", fontSize: 14, lineHeight: 20, marginTop: 2 },
  statusCard: { borderRadius: 18, padding: 16, borderWidth: 1 },
  statusReady: { backgroundColor: "#EDF9F2", borderColor: "#B4E4C9" },
  statusWaiting: { backgroundColor: "#FFF8E9", borderColor: "#F4D697" },
  statusTopline: { flexDirection: "row", alignItems: "center", gap: 8 },
  statusDot: { width: 10, height: 10, borderRadius: 5 },
  dotReady: { backgroundColor: "#1E8E5A" },
  dotWaiting: { backgroundColor: "#B7791F" },
  statusTitle: { color: "#152033", fontSize: 16, fontWeight: "800" },
  statusText: { color: "#4B5870", fontSize: 14, lineHeight: 20, marginTop: 7 },
  secondaryButton: { marginTop: 13, borderWidth: 1, borderColor: "#3157C9", borderRadius: 12, paddingVertical: 11, alignItems: "center" },
  secondaryButtonText: { color: "#3157C9", fontWeight: "700", fontSize: 14 },
  card: { backgroundColor: "#FFFFFF", borderRadius: 18, padding: 16, borderWidth: 1, borderColor: "#E1E7F0" },
  cardTitle: { color: "#152033", fontSize: 16, lineHeight: 22, fontWeight: "800", marginBottom: 13 },
  row: { flexDirection: "row", gap: 10 },
  rowPrimary: { flex: 1.4 },
  rowSecondary: { flex: 1 },
  tripleRow: { flexDirection: "row", gap: 8 },
  fieldWrap: { flex: 1 },
  fieldLabel: { color: "#657187", fontSize: 12, fontWeight: "600", marginBottom: 6 },
  input: { backgroundColor: "#F6F8FC", borderWidth: 1, borderColor: "#D8E0EC", borderRadius: 11, paddingHorizontal: 11, height: 44, color: "#152033", fontSize: 15, fontWeight: "600" },
  previewCard: { backgroundColor: "#EAF0FF", borderRadius: 18, padding: 17, borderWidth: 1, borderColor: "#C9D7FF" },
  previewLabel: { color: "#4860A8", fontSize: 13, fontWeight: "700" },
  previewValue: { color: "#193DAB", fontSize: 21, lineHeight: 27, fontWeight: "800", marginTop: 4 },
  previewSubtext: { color: "#526580", fontSize: 13, lineHeight: 18, marginTop: 4 },
  primaryButton: { backgroundColor: "#3157C9", borderRadius: 15, height: 54, alignItems: "center", justifyContent: "center", shadowColor: "#3157C9", shadowOpacity: 0.25, shadowRadius: 12, shadowOffset: { width: 0, height: 5 }, elevation: 3 },
  stopButton: { backgroundColor: "#C23B3B" },
  primaryButtonText: { color: "#FFFFFF", fontSize: 16, fontWeight: "800" },
  pressed: { opacity: 0.72, transform: [{ scale: 0.98 }] },
  notice: { borderRadius: 14, padding: 14, borderWidth: 1 },
  warningNotice: { backgroundColor: "#FFF8E9", borderColor: "#F4D697" },
  noticeTitle: { color: "#795414", fontWeight: "800", fontSize: 14, marginBottom: 4 },
  noticeText: { color: "#765F34", fontSize: 13, lineHeight: 19 },
  logHeading: { flexDirection: "row", justifyContent: "space-between", alignItems: "baseline", marginTop: 4 },
  logActions: { flexDirection: "row", alignItems: "center", gap: 2 },
  copyButton: { paddingVertical: 5, paddingHorizontal: 7 },
  copyButtonText: { color: "#3157C9", fontSize: 13, fontWeight: "800" },
  clearButton: { paddingVertical: 5, paddingHorizontal: 7 },
  clearButtonText: { color: "#3157C9", fontSize: 13, fontWeight: "700" },
  emptyLog: { backgroundColor: "#FFFFFF", borderRadius: 15, borderWidth: 1, borderColor: "#E1E7F0", padding: 16 },
  emptyLogText: { color: "#79849A", fontSize: 14 },
  logItem: { backgroundColor: "#FFFFFF", borderRadius: 14, borderWidth: 1, borderColor: "#E1E7F0", padding: 13, marginBottom: 8 },
  logTime: { color: "#657187", fontSize: 12, fontWeight: "700", marginBottom: 4 },
  logMessage: { color: "#29364C", fontSize: 14, lineHeight: 19 },
});
