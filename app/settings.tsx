import { useRouter } from "expo-router";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";

import { ScreenContainer } from "@/components/screen-container";
import { useColors } from "@/hooks/use-colors";
import { openDateTimeSettings, openDeveloperSettings } from "@/lib/time-control";

export default function SettingsScreen() {
  const colors = useColors();
  const router = useRouter();

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

        <View style={[styles.infoCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          <Text style={[styles.infoTitle, { color: colors.text }]}>Системный сервис</Text>
          <Text style={[styles.infoText, { color: colors.muted }]}>После первого сопряжения служебный процесс продолжает работать независимо от «Машины времени». Закрытие и повторный запуск приложения не требуют нового подключения. После перезагрузки телефона сервис нужно запустить снова.</Text>
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
  infoCard: { marginHorizontal: 16, marginTop: 16, borderWidth: 1, borderRadius: 14, padding: 16 },
  infoTitle: { fontSize: 17, fontWeight: "800" },
  infoText: { fontSize: 13, lineHeight: 19, marginTop: 8 },
});
