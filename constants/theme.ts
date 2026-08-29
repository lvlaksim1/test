export type ColorScheme = "light" | "dark";

export const Colors = {
  light: {
    primary: "#3157C9",
    background: "#F6F8FC",
    surface: "#FFFFFF",
    text: "#152033",
    muted: "#657187",
    border: "#E1E7F0",
    success: "#1E8E5A",
    warning: "#B7791F",
    error: "#C23B3B",
  },
  dark: {
    primary: "#8BA7FF",
    background: "#101725",
    surface: "#182235",
    text: "#F1F5FF",
    muted: "#A6B2C8",
    border: "#2B3951",
    success: "#58C98E",
    warning: "#F0C06A",
    error: "#F08080",
  },
} as const;

export type ThemeColorPalette = (typeof Colors)[ColorScheme];
