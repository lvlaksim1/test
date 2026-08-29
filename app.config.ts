import type { ExpoConfig } from "expo/config";

const config: ExpoConfig = {
  name: "Машина времени",
  slug: "android-time-cycler",
  version: "140",
  orientation: "portrait",
  icon: "./assets/images/icon.png",
  scheme: "timecycler",
  userInterfaceStyle: "automatic",
  newArchEnabled: true,
  android: {
    adaptiveIcon: {
      backgroundColor: "#3157C9",
      foregroundImage: "./assets/images/android-icon-foreground.png",
      backgroundImage: "./assets/images/android-icon-background.png",
      monochromeImage: "./assets/images/android-icon-monochrome.png",
    },
    edgeToEdgeEnabled: false,
    predictiveBackGestureEnabled: false,
    softwareKeyboardLayoutMode: "resize",
    package: "com.app.androidtimecycler",
    versionCode: 140,
    permissions: ["POST_NOTIFICATIONS"],
  },
  plugins: [
    "expo-router",
    "./plugins/with-time-shizuku",
    [
      "expo-splash-screen",
      {
        image: "./assets/images/splash-icon.png",
        imageWidth: 200,
        resizeMode: "contain",
        backgroundColor: "#F6F8FC",
        dark: { backgroundColor: "#101725" },
      },
    ],
    [
      "expo-build-properties",
      {
        android: { buildArchs: ["armeabi-v7a", "arm64-v8a"], minSdkVersion: 24 },
      },
    ],
  ],
};

export default config;
