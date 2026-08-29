import { useColorScheme } from "react-native";

import { Colors, type ThemeColorPalette } from "@/constants/theme";

export function useColors(): ThemeColorPalette {
  return Colors[useColorScheme() === "dark" ? "dark" : "light"];
}
