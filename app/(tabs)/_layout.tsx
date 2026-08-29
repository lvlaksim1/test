import { Tabs, useRouter } from "expo-router";
import { Pressable, Text } from "react-native";
import { useColors } from "@/hooks/use-colors";

export default function TabLayout() {
  const router = useRouter();
  const colors = useColors();
  return (
    <Tabs screenOptions={{
      headerShown: true,
      headerTransparent: true,
      headerTitle: "",
      headerShadowVisible: false,
      headerRight: () => (
        <Pressable onPress={() => router.push("/settings")} hitSlop={12} style={{ width: 48, height: 48, alignItems: "center", justifyContent: "center", marginRight: 8 }}>
          <Text style={{ color: colors.text, fontSize: 27, fontWeight: "700", lineHeight: 30 }}>☰</Text>
        </Pressable>
      ),
      tabBarStyle: { display: "none" },
    }}>
      <Tabs.Screen name="index" options={{ title: "Машина времени" }} />
    </Tabs>
  );
}
