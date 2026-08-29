import * as Clipboard from "expo-clipboard";
import { useRouter } from "expo-router";
import { useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";

import { ScreenContainer } from "@/components/screen-container";
import { useColors } from "@/hooks/use-colors";
import {
  enableWifiViaLocalAdb,
  openDateTimeSettings,
  openDeveloperSettings,
  prepareLocalAdbDiagnostic,
  testLocalAdbWithoutWifi,
  type LocalAdbDiagnostic,
} from "@/lib/time-control";

export default function SettingsScreen() {
  const colors = useColors();
  const router = useRouter();
  const [busy, setBusy] = useState<string | null>(null);
  const [diagnostic, setDiagnostic] = useState("");

  const runDiagnostic = async (name: string, action: () => Promise<LocalAdbDiagnostic>) => {
    if (busy) return;
    setBusy(name);
    try {
      const result = await action();
      setDiagnostic(`${result.success ? "OK" : "ОШИБКА"}\n${result.detail}`);
    } catch (error) {
      setDiagnostic(`ОШИБКА\n${error instanceof Error ? error.message : String(error)}`);
    } finally {
      setBusy(null);
    }
  };

  return (
    <ScreenContainer edges={["top", "left", "right", "bottom"]} containerClassName="bg-background">
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} style={styles.back}>
          <Text style={[styles.backText, { color: colors.primary }]}>‹</Text>
        </Pressable>
        <Text style={[styles.title, { color: colors.text }]}>Настройки</Text>
      </View>

      <ScrollView contentContainerStyle={styles.content}>
        <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          <Pressable onPress={() => void openDeveloperSettings()} style={[styles.row, { borderBottomColor: colors.border }]}>
            <View style={styles.rowText}>
              <Text style={[styles.rowTitle, { color: colors.text }]}>Сопряжение устройства</Text>
              <Text style={[styles.rowHint, { color: colors.muted }]}>Беспроводная отладка и код сопряжения</Text>
            </View>
            <Text style={[styles.chevron, { color: colors.primary }]}>›</Text>
          </Pressable>
          <Pressable onPress={() => void openDateTimeSettings()} style={styles.row}>
            <View style={styles.rowText}>
              <Text style={[styles.rowTitle, { color: colors.text }]}>Дата и время</Text>
              <Text style={[styles.rowHint, { color: colors.muted }]}>Системная синхронизация даты и времени</Text>
            </View>
            <Text style={[styles.chevron, { color: colors.primary }]}>›</Text>
          </Pressable>
        </View>

        <View style={[styles.experimentCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          <Text style={[styles.experimentTitle, { color: colors.text }]}>Локальный ADB — эксперимент</Text>
          <Text style={[styles.experimentHint, { color: colors.muted }]}>
            Сначала подключите обычный системный доступ по беспроводной отладке. Первый тест переводит adbd на TCP-порт 5555 и пытается переподключиться к 127.0.0.1. Второй тест сам отключает Wi-Fi и проверяет повторное локальное подключение.
          </Text>

          <Pressable
            disabled={Boolean(busy)}
            onPress={() => void runDiagnostic("prepare", prepareLocalAdbDiagnostic)}
            style={[styles.actionButton, { backgroundColor: colors.primary, opacity: busy ? 0.55 : 1 }]}
          >
            <Text style={styles.actionText}>{busy === "prepare" ? "Выполняется…" : "1. Подготовить 127.0.0.1:5555"}</Text>
          </Pressable>

          <Pressable
            disabled={Boolean(busy)}
            onPress={() => void runDiagnostic("wifiOff", testLocalAdbWithoutWifi)}
            style={[styles.actionButton, { backgroundColor: colors.primary, opacity: busy ? 0.55 : 1 }]}
          >
            <Text style={styles.actionText}>{busy === "wifiOff" ? "Выполняется…" : "2. Отключить Wi-Fi и проверить"}</Text>
          </Pressable>

          <Pressable
            disabled={Boolean(busy)}
            onPress={() => void runDiagnostic("wifiOn", enableWifiViaLocalAdb)}
            style={[styles.secondaryButton, { borderColor: colors.primary, opacity: busy ? 0.55 : 1 }]}
          >
            <Text style={[styles.secondaryText, { color: colors.primary }]}>{busy === "wifiOn" ? "Выполняется…" : "Включить Wi-Fi через ADB"}</Text>
          </Pressable>

          {diagnostic ? (
            <View style={[styles.logBox, { borderColor: colors.border }]}>
              <Text selectable style={[styles.logText, { color: colors.text }]}>{diagnostic}</Text>
              <Pressable onPress={() => void Clipboard.setStringAsync(diagnostic)} style={styles.copyButton}>
                <Text style={[styles.copyText, { color: colors.primary }]}>Копировать результат</Text>
              </Pressable>
            </View>
          ) : null}
        </View>
      </ScrollView>
    </ScreenContainer>
  );
}

const styles = StyleSheet.create({
  header: { height: 64, flexDirection: "row", alignItems: "center", paddingHorizontal: 16, gap: 8 },
  back: { width: 42, height: 42, alignItems: "center", justifyContent: "center" },
  backText: { fontSize: 38, lineHeight: 40 },
  title: { fontSize: 22, fontWeight: "800" },
  content: { paddingBottom: 28 },
  card: { marginHorizontal: 16, marginTop: 10, borderWidth: 1, borderRadius: 14, overflow: "hidden" },
  row: { minHeight: 76, paddingHorizontal: 16, flexDirection: "row", alignItems: "center", justifyContent: "space-between", borderBottomWidth: 1 },
  rowText: { flex: 1, paddingRight: 12 },
  rowTitle: { fontSize: 16, fontWeight: "800" },
  rowHint: { fontSize: 12, marginTop: 4 },
  chevron: { fontSize: 28, fontWeight: "700" },
  experimentCard: { marginHorizontal: 16, marginTop: 16, borderWidth: 1, borderRadius: 14, padding: 16 },
  experimentTitle: { fontSize: 17, fontWeight: "800" },
  experimentHint: { fontSize: 13, lineHeight: 19, marginTop: 8, marginBottom: 14 },
  actionButton: { minHeight: 48, borderRadius: 10, alignItems: "center", justifyContent: "center", paddingHorizontal: 12, marginTop: 8 },
  actionText: { color: "#FFFFFF", fontSize: 14, fontWeight: "800", textAlign: "center" },
  secondaryButton: { minHeight: 46, borderWidth: 1, borderRadius: 10, alignItems: "center", justifyContent: "center", paddingHorizontal: 12, marginTop: 8 },
  secondaryText: { fontSize: 14, fontWeight: "800", textAlign: "center" },
  logBox: { marginTop: 14, borderWidth: 1, borderRadius: 10, padding: 12 },
  logText: { fontSize: 12, lineHeight: 18 },
  copyButton: { alignSelf: "flex-start", marginTop: 10, paddingVertical: 5 },
  copyText: { fontSize: 13, fontWeight: "800" },
});
