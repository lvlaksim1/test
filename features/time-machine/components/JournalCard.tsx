import { FlatList, Pressable, Text, View } from "react-native";

import { styles } from "@/features/time-machine/styles";
import { useColors } from "@/hooks/use-colors";
import { formatDateTime } from "@/lib/cycle-utils";
import type { CycleEvent } from "@/lib/time-control";

export function JournalCard({ events, expanded, onToggle, onCopy, onClear }: {
  events: CycleEvent[];
  expanded: boolean;
  onToggle: () => void;
  onCopy: () => void;
  onClear: () => void;
}) {
  const colors = useColors();
  return (
    <View style={[styles.logCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <Pressable onPress={onToggle} style={({ pressed }) => [styles.logToggle, pressed && styles.pressed]}>
        <Text style={[styles.cardTitle, { color: colors.text }]}>Журнал действий</Text>
        <Text style={[styles.chevron, { color: colors.primary }]}>{expanded ? "⌃" : "⌄"}</Text>
      </Pressable>
      {expanded && (
        <View>
          <View style={styles.logActions}>
            <Pressable onPress={onCopy} disabled={!events.length} style={({ pressed }) => [styles.textAction, (!events.length || pressed) && styles.pressed]}>
              <Text style={[styles.textActionLabel, { color: colors.primary }]}>Копировать</Text>
            </Pressable>
            <Pressable onPress={onClear} disabled={!events.length} style={({ pressed }) => [styles.textAction, (!events.length || pressed) && styles.pressed]}>
              <Text style={[styles.textActionLabel, { color: colors.primary }]}>Очистить</Text>
            </Pressable>
          </View>
          <FlatList
            data={events.slice().reverse()}
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
  );
}
