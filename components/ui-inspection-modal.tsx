import { useMemo, useState } from "react";
import { Modal, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";

import { useColors } from "@/hooks/use-colors";
import { invokeAppElement, type OpenAppInfo, type UiElementInfo } from "@/lib/time-control";

type Props = {
  visible: boolean;
  app: OpenAppInfo | null;
  elements: UiElementInfo[];
  loading: boolean;
  error: string | null;
  onClose: () => void;
};

type Column = {
  key: string;
  title: string;
  width: number;
  value: (element: UiElementInfo) => string;
};

const FLAG_KEYS = ["clickable", "enabled", "focusable", "focused", "scrollable", "long-clickable", "checkable", "checked", "selected", "password"];
const PRIMARY_KEYS = new Set(["index", "text", "resource-id", "class", "package", "content-desc", "bounds", ...FLAG_KEYS]);
const BOUNDS_PATTERN = /^\[\d+,\d+]\[\d+,\d+]$/;

function attribute(element: UiElementInfo, key: string): string {
  const value = element.attributes?.[key];
  return typeof value === "string" ? value : "";
}

function flags(element: UiElementInfo): string {
  return FLAG_KEYS.map((key) => `${key}=${attribute(element, key) || "?"}`).join("\n");
}

function otherAttributes(element: UiElementInfo): string {
  return Object.entries(element.attributes ?? {})
    .filter(([key]) => !PRIMARY_KEYS.has(key))
    .map(([key, value]) => `${key}=${value}`)
    .join("\n");
}

function isClickable(element: UiElementInfo): boolean {
  return attribute(element, "clickable") === "true" && attribute(element, "enabled") !== "false" && BOUNDS_PATTERN.test(attribute(element, "bounds"));
}

function buttonCaption(element: UiElementInfo): string {
  return attribute(element, "text") || attribute(element, "content-desc") || attribute(element, "resource-id").split("/").pop() || element.name || "Кнопка";
}

const columns: Column[] = [
  { key: "number", title: "№", width: 48, value: (item) => String(item.sequence + 1) },
  { key: "depth", title: "Глубина", width: 72, value: (item) => String(item.depth) },
  { key: "name", title: "Название элемента", width: 190, value: (item) => item.name },
  { key: "id", title: "ID / resource-id", width: 220, value: (item) => attribute(item, "resource-id") },
  { key: "class", title: "Класс", width: 190, value: (item) => attribute(item, "class") },
  { key: "text", title: "Текст", width: 220, value: (item) => attribute(item, "text") },
  { key: "description", title: "Content description", width: 220, value: (item) => attribute(item, "content-desc") },
  { key: "package", title: "Package", width: 190, value: (item) => attribute(item, "package") },
  { key: "bounds", title: "Bounds", width: 150, value: (item) => attribute(item, "bounds") },
  { key: "index", title: "Index", width: 62, value: (item) => attribute(item, "index") },
  { key: "flags", title: "Состояния", width: 220, value: flags },
  { key: "other", title: "Другое", width: 260, value: otherAttributes },
];

export function UiInspectionModal({ visible, app, elements, loading, error, onClose }: Props) {
  const colors = useColors();
  const [invokingKey, setInvokingKey] = useState<string | null>(null);
  const [invokeError, setInvokeError] = useState<string | null>(null);
  const clickableElements = useMemo(() => elements.filter(isClickable), [elements]);
  const unitySurfaceDetected = useMemo(() => elements.some((element) => {
    const className = attribute(element, "class").toLowerCase();
    const name = element.name.toLowerCase();
    return className.includes("surfaceview") || className.includes("gameview") || name.includes("surfaceview") || name.includes("gameview");
  }), [elements]);
  const screenKind = unitySurfaceDetected ? "Unity / SurfaceView" : (elements[0] ? attribute(elements[0], "class") || elements[0].name : "—");

  const invoke = async (element: UiElementInfo) => {
    if (!app || invokingKey) return;
    const bounds = attribute(element, "bounds");
    const key = `${element.sequence}-${bounds}`;
    setInvokingKey(key);
    setInvokeError(null);
    try {
      await invokeAppElement(app.packageName, bounds);
    } catch (failure) {
      setInvokeError(failure instanceof Error ? failure.message : "Не удалось выполнить нажатие.");
    } finally {
      setInvokingKey(null);
    }
  };

  return (
    <Modal visible={visible} animationType="slide" onRequestClose={onClose}>
      <View style={[styles.root, { backgroundColor: colors.background }]}>
        <View style={[styles.header, { borderBottomColor: colors.border, backgroundColor: colors.surface }]}>
          <View style={styles.headerText}>
            <Text style={[styles.title, { color: colors.text }]}>Элементы экрана</Text>
            <Text style={[styles.subtitle, { color: colors.muted }]} numberOfLines={2}>
              {app ? `${app.label} (${app.processNames.join(", ") || app.packageName})` : ""}
            </Text>
            {!loading && !error && <Text style={[styles.count, { color: colors.muted }]}>Найдено элементов: {elements.length}; кнопок: {clickableElements.length}</Text>}
          </View>
          <Pressable onPress={onClose} style={({ pressed }) => [styles.closeButton, { borderColor: colors.border }, pressed && styles.pressed]}>
            <Text style={[styles.closeText, { color: colors.primary }]}>Закрыть</Text>
          </Pressable>
        </View>

        {loading ? (
          <View style={styles.messageWrap}><Text style={[styles.message, { color: colors.muted }]}>Получение UI hierarchy…</Text></View>
        ) : error ? (
          <View style={styles.messageWrap}><Text style={[styles.message, { color: colors.error }]}>{error}</Text></View>
        ) : (
          <ScrollView style={styles.vertical} contentContainerStyle={styles.verticalContent}>
            <View style={[styles.summaryCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
              <Text style={[styles.summaryTitle, { color: colors.text }]}>Текущий экран</Text>
              <Text style={[styles.summaryValue, { color: colors.muted }]}>{screenKind}</Text>
              {unitySurfaceDetected && (
                <Text style={[styles.unityHint, { color: colors.warning }]}>Обнаружен Unity SurfaceView. Android UIAutomation видит только экспортированные Unity элементы; внутренние BTN_* сюда не добавляются искусственно.</Text>
              )}
            </View>

            <View style={[styles.summaryCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
              <Text style={[styles.summaryTitle, { color: colors.text }]}>Кнопки текущего экрана</Text>
              {clickableElements.length ? (
                <View style={styles.buttonList}>
                  {clickableElements.map((element) => {
                    const bounds = attribute(element, "bounds");
                    const key = `${element.sequence}-${bounds}`;
                    return (
                      <Pressable key={key} disabled={Boolean(invokingKey)} onPress={() => void invoke(element)} style={({ pressed }) => [styles.mirrorButton, { borderColor: colors.primary, backgroundColor: colors.background }, (pressed || invokingKey === key) && styles.pressed]}>
                        <Text style={[styles.mirrorButtonText, { color: colors.primary }]}>{invokingKey === key ? "Выполнение…" : buttonCaption(element)}</Text>
                        <Text style={[styles.mirrorButtonMeta, { color: colors.muted }]}>{bounds}</Text>
                      </Pressable>
                    );
                  })}
                </View>
              ) : (
                <Text style={[styles.emptyButtons, { color: colors.muted }]}>{unitySurfaceDetected ? "Unity не экспортировал кликабельные кнопки в Android UI hierarchy." : "Кликабельные элементы на текущем экране не найдены."}</Text>
              )}
              {invokeError && <Text style={[styles.invokeError, { color: colors.error }]}>{invokeError}</Text>}
            </View>

            <ScrollView horizontal showsHorizontalScrollIndicator>
              <View style={[styles.table, { borderColor: colors.border }]}>
                <View style={[styles.tableRow, styles.headerRow, { backgroundColor: colors.surface, borderBottomColor: colors.border }]}>
                  {columns.map((column) => (
                    <View key={column.key} style={[styles.cell, styles.headerCell, { width: column.width, borderRightColor: colors.border }]}>
                      <Text style={[styles.headerLabel, { color: colors.text }]}>{column.title}</Text>
                    </View>
                  ))}
                </View>
                {elements.map((element) => (
                  <View key={`${element.sequence}-${attribute(element, "resource-id")}-${attribute(element, "bounds")}`} style={[styles.tableRow, { borderBottomColor: colors.border }]}>
                    {columns.map((column) => (
                      <View key={column.key} style={[styles.cell, { width: column.width, borderRightColor: colors.border }]}>
                        <Text selectable style={[styles.cellText, { color: colors.text }]}>{column.value(element) || "—"}</Text>
                      </View>
                    ))}
                  </View>
                ))}
              </View>
            </ScrollView>
          </ScrollView>
        )}
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  header: { minHeight: 92, paddingHorizontal: 14, paddingVertical: 12, borderBottomWidth: 1, flexDirection: "row", alignItems: "center", gap: 10 },
  headerText: { flex: 1 },
  title: { fontSize: 19, fontWeight: "800" },
  subtitle: { fontSize: 12, marginTop: 3 },
  count: { fontSize: 11, marginTop: 3, fontWeight: "700" },
  closeButton: { borderWidth: 1, borderRadius: 9, paddingHorizontal: 10, paddingVertical: 7 },
  closeText: { fontSize: 13, fontWeight: "800" },
  messageWrap: { flex: 1, alignItems: "center", justifyContent: "center", padding: 24 },
  message: { fontSize: 14, lineHeight: 20, textAlign: "center" },
  vertical: { flex: 1 },
  verticalContent: { padding: 10, gap: 10 },
  summaryCard: { borderWidth: 1, borderRadius: 10, padding: 12 },
  summaryTitle: { fontSize: 14, fontWeight: "800" },
  summaryValue: { fontSize: 12, marginTop: 4 },
  unityHint: { fontSize: 12, lineHeight: 17, marginTop: 8 },
  buttonList: { marginTop: 10, gap: 8 },
  mirrorButton: { borderWidth: 1, borderRadius: 9, paddingHorizontal: 12, paddingVertical: 10 },
  mirrorButtonText: { fontSize: 14, fontWeight: "800" },
  mirrorButtonMeta: { fontSize: 10, marginTop: 3 },
  emptyButtons: { fontSize: 12, lineHeight: 17, marginTop: 8 },
  invokeError: { fontSize: 12, lineHeight: 17, marginTop: 8 },
  table: { borderWidth: 1, borderRadius: 8, overflow: "hidden" },
  tableRow: { flexDirection: "row", borderBottomWidth: 1, alignItems: "stretch" },
  headerRow: { minHeight: 46 },
  cell: { paddingHorizontal: 7, paddingVertical: 7, borderRightWidth: 1, justifyContent: "flex-start" },
  headerCell: { justifyContent: "center" },
  headerLabel: { fontSize: 11, lineHeight: 14, fontWeight: "800" },
  cellText: { fontSize: 10, lineHeight: 14 },
  pressed: { opacity: 0.65 },
});
