import { useCallback, useEffect, useState } from "react";
import { Modal, Platform, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";

import { UiInspectionModal } from "@/components/ui-inspection-modal";
import { useColors } from "@/hooks/use-colors";
import { getOpenApps, getTimeControlStatus, inspectAppScreen, type OpenAppInfo, type UiElementInfo } from "@/lib/time-control";

export function AppInspectorPanel() {
  const colors = useColors();
  const [listVisible, setListVisible] = useState(false);
  const [apps, setApps] = useState<OpenAppInfo[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedApp, setSelectedApp] = useState<OpenAppInfo | null>(null);
  const [elements, setElements] = useState<UiElementInfo[]>([]);
  const [inspectionVisible, setInspectionVisible] = useState(false);
  const [inspectionLoading, setInspectionLoading] = useState(false);
  const [inspectionError, setInspectionError] = useState<string | null>(null);

  const refreshApps = useCallback(async () => {
    if (Platform.OS !== "android") return;
    setLoading(true);
    setError(null);
    try {
      const status = await getTimeControlStatus();
      if (!status.isShizukuRunning || !status.isShizukuPermissionGranted) {
        setApps([]);
        setError("Для инспектора нужно запустить Shizuku и выдать приложению доступ.");
        return;
      }
      setApps(await getOpenApps());
    } catch (failure) {
      setApps([]);
      setError(failure instanceof Error ? failure.message : "Не удалось получить список запущенных приложений.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!listVisible) return;
    void refreshApps();
    const timer = setInterval(() => void refreshApps(), 3000);
    return () => clearInterval(timer);
  }, [listVisible, refreshApps]);

  const inspect = async (app: OpenAppInfo) => {
    setSelectedApp(app);
    setElements([]);
    setInspectionError(null);
    setInspectionLoading(true);
    setInspectionVisible(true);
    setListVisible(false);
    try {
      setElements(await inspectAppScreen(app.packageName));
    } catch (failure) {
      setInspectionError(failure instanceof Error ? failure.message : "Не удалось получить элементы экрана.");
    } finally {
      setInspectionLoading(false);
    }
  };

  if (Platform.OS !== "android") return null;

  return (
    <>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Открыть инспектор приложений"
        onPress={() => setListVisible(true)}
        style={({ pressed }) => [styles.floatingButton, { backgroundColor: colors.primary }, pressed && styles.pressed]}
      >
        <Text style={styles.floatingText}>UI</Text>
      </Pressable>

      <Modal visible={listVisible} animationType="slide" onRequestClose={() => setListVisible(false)}>
        <View style={[styles.modalRoot, { backgroundColor: colors.background }]}>
          <View style={[styles.header, { borderBottomColor: colors.border }]}>
            <View>
              <Text style={[styles.title, { color: colors.text }]}>Инспектор приложений</Text>
              <Text style={[styles.subtitle, { color: colors.muted }]}>Запущенные приложения и их текущий UI</Text>
            </View>
            <Pressable onPress={() => setListVisible(false)} style={[styles.closeButton, { borderColor: colors.border }]}>
              <Text style={[styles.closeText, { color: colors.primary }]}>Закрыть</Text>
            </Pressable>
          </View>

          <ScrollView contentContainerStyle={styles.content}>
            {loading && <Text style={[styles.message, { color: colors.muted }]}>Получение списка…</Text>}
            {error && <Text style={[styles.message, { color: colors.error }]}>{error}</Text>}
            {!loading && !error && apps.length === 0 && <Text style={[styles.message, { color: colors.muted }]}>Запущенные приложения не найдены.</Text>}
            {apps.map((app) => (
              <Pressable key={app.packageName} onPress={() => void inspect(app)} style={({ pressed }) => [styles.appCard, { backgroundColor: colors.surface, borderColor: colors.border }, pressed && styles.pressed]}>
                <Text style={[styles.appLabel, { color: colors.text }]}>{app.label}</Text>
                <Text style={[styles.appMeta, { color: colors.muted }]}>{app.packageName}</Text>
                <Text style={[styles.appMeta, { color: colors.muted }]}>{app.processNames.join(", ") || app.packageName}</Text>
              </Pressable>
            ))}
          </ScrollView>
        </View>
      </Modal>

      <UiInspectionModal
        visible={inspectionVisible}
        app={selectedApp}
        elements={elements}
        loading={inspectionLoading}
        error={inspectionError}
        onClose={() => setInspectionVisible(false)}
      />
    </>
  );
}

const styles = StyleSheet.create({
  floatingButton: { position: "absolute", right: 14, top: 46, zIndex: 1000, elevation: 12, width: 48, height: 48, borderRadius: 24, alignItems: "center", justifyContent: "center" },
  floatingText: { color: "#FFFFFF", fontWeight: "900", fontSize: 15 },
  pressed: { opacity: 0.65 },
  modalRoot: { flex: 1 },
  header: { paddingTop: 48, paddingHorizontal: 16, paddingBottom: 12, borderBottomWidth: 1, flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: 12 },
  title: { fontSize: 20, fontWeight: "900" },
  subtitle: { fontSize: 12, marginTop: 3 },
  closeButton: { borderWidth: 1, borderRadius: 9, paddingHorizontal: 10, paddingVertical: 7 },
  closeText: { fontSize: 13, fontWeight: "800" },
  content: { padding: 12, gap: 8 },
  message: { fontSize: 13, lineHeight: 18, padding: 8 },
  appCard: { borderWidth: 1, borderRadius: 12, padding: 12 },
  appLabel: { fontSize: 15, fontWeight: "800" },
  appMeta: { fontSize: 11, marginTop: 3 },
});
