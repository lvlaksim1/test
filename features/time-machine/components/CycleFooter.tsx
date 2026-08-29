import { Pressable, Text, View } from "react-native";

import { styles } from "@/features/time-machine/styles";
import { useColors } from "@/hooks/use-colors";
import { isNativeTimeControlAvailable } from "@/lib/time-control";

export function CycleFooter({ running, isBusy, activeSeries, totalSeries, activeRepeat, repeatsPerSeries, onStart, onStop }: {
  running: boolean;
  isBusy: boolean;
  activeSeries: number;
  totalSeries: number;
  activeRepeat: number;
  repeatsPerSeries: number;
  onStart: () => void;
  onStop: () => void;
}) {
  const colors = useColors();
  return (
    <View style={[styles.footer, { backgroundColor: colors.background, borderTopColor: colors.border }]}>
      {running ? (
        <Pressable disabled={isBusy} onPress={onStop} style={({ pressed }) => [styles.stopButton, { backgroundColor: colors.error }, (pressed || isBusy) && styles.pressed]}>
          <Text style={styles.primaryButtonText}>Цикл {activeSeries} из {totalSeries} · повтор {activeRepeat} из {repeatsPerSeries} · остановить</Text>
        </Pressable>
      ) : (
        <Pressable disabled={isBusy || !isNativeTimeControlAvailable} onPress={onStart} style={({ pressed }) => [styles.primaryButton, { backgroundColor: colors.primary }, (pressed || isBusy || !isNativeTimeControlAvailable) && styles.pressed]}>
          <Text style={styles.primaryButtonText}>Запустить цикл</Text>
        </Pressable>
      )}
    </View>
  );
}
