import { Pressable, Text, View } from "react-native";

import type { SavedConfiguration } from "@/features/time-machine/form-utils";
import { styles } from "@/features/time-machine/styles";
import { useColors } from "@/hooks/use-colors";

export function ConfigurationsCard({ configurations, activeName, expanded, running, onSave, onToggle, onLoad }: {
  configurations: SavedConfiguration[];
  activeName: string | null;
  expanded: boolean;
  running: boolean;
  onSave: () => void;
  onToggle: () => void;
  onLoad: (configuration: SavedConfiguration) => void;
}) {
  const colors = useColors();
  return (
    <View style={[styles.logCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <View style={styles.configurationHeader}>
        <Pressable onPress={onSave} style={({ pressed }) => [styles.saveConfigurationButton, { borderColor: colors.primary }, pressed && styles.pressed]}>
          <Text style={[styles.saveConfigurationButtonText, { color: colors.primary }]}>Сохранить конфигурацию</Text>
        </Pressable>
        <Pressable onPress={onToggle} hitSlop={8} style={({ pressed }) => [styles.configurationToggle, pressed && styles.pressed]}>
          <Text style={[styles.chevron, { color: colors.primary }]}>{expanded ? "⌃" : "⌄"}</Text>
        </Pressable>
      </View>
      {expanded && (
        <View style={styles.configurationList}>
          {configurations.length === 0 ? (
            <Text style={[styles.emptyLogText, styles.configurationEmptyText, { color: colors.muted }]}>Нет сохранённых конфигураций.</Text>
          ) : configurations.map((configuration) => (
            <Pressable
              key={configuration.name}
              disabled={running}
              onPress={() => onLoad(configuration)}
              style={({ pressed }) => [styles.configurationItem, { backgroundColor: colors.background }, (pressed || running) && styles.pressed]}
            >
              <Text style={[styles.configurationItemName, { color: colors.text }]} numberOfLines={1}>{configuration.name}</Text>
              {configuration.name === activeName && <Text style={[styles.configurationCurrent, { color: colors.primary }]}>текущая</Text>}
            </Pressable>
          ))}
        </View>
      )}
    </View>
  );
}
