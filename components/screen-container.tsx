import type { ReactNode } from "react";
import { View } from "react-native";
import { SafeAreaView, type Edge } from "react-native-safe-area-context";

import { useColors } from "@/hooks/use-colors";

export function ScreenContainer({ children, edges = ["top", "left", "right"] }: { children: ReactNode; edges?: Edge[] }) {
  const colors = useColors();
  return (
    <View style={{ flex: 1, backgroundColor: colors.background }}>
      <SafeAreaView edges={edges} style={{ flex: 1 }}>
        {children}
      </SafeAreaView>
    </View>
  );
}
