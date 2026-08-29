import { Pressable, Switch, Text, View } from "react-native";

import { useColors } from "@/hooks/use-colors";
import { isNativeTimeControlAvailable, type TimeControlStatus } from "@/lib/time-control";
import { styles } from "@/features/time-machine/styles";

export function ShizukuCard({ status, shizukuReady, running, isBusy, onRequestAccess, onAutomaticTimeChange }: {
  status: TimeControlStatus;
  shizukuReady: boolean;
  running: boolean;
  isBusy: boolean;
  onRequestAccess: () => void;
  onAutomaticTimeChange: (enabled: boolean) => void;
}) {
  const colors = useColors();
  return (
    <View style={[styles.shizukuCard, { backgroundColor: colors.surface, borderColor: shizukuReady ? colors.success : colors.warning }]}>
      <Pressable onPress={onRequestAccess} style={({ pressed }) => [styles.syncButton, { borderColor: shizukuReady ? colors.success : colors.border }, pressed && styles.pressed]}>
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
        <Switch
          value={status.isAutomaticTimeEnabled}
          onValueChange={onAutomaticTimeChange}
          disabled={isBusy || running || !isNativeTimeControlAvailable}
          trackColor={{ false: colors.border, true: colors.success }}
          thumbColor={status.isAutomaticTimeEnabled ? "#FFFFFF" : colors.muted}
        />
      </View>
    </View>
  );
}
