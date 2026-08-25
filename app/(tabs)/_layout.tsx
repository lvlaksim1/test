import { Tabs } from "expo-router";
import { View } from "react-native";

import { AppInspectorPanel } from "@/components/app-inspector-panel";

export default function TabLayout() {
  return (
    <View style={{ flex: 1 }}>
      <Tabs screenOptions={{ headerShown: false, tabBarStyle: { display: "none" } }}>
        <Tabs.Screen name="index" options={{ title: "Машина времени" }} />
      </Tabs>
      <AppInspectorPanel />
    </View>
  );
}
