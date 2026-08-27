import "./scripts/load-env.js";
import type { ExpoConfig } from "expo/config";

export const APP_VERSION = "23";
export const ANDROID_VERSION_CODE = 123;

const rawBundleId = "com.app.androidtimecycler";
const bundleId = rawBundleId
  .replace(/[-_]/g, ".")
  .replace(/[^a-zA-Z0-9.]/g, "")
  .replace(/\.+/g, ".")
  .replace(/^\.+|\.+$/g, "")
  .toLowerCase()
  .split(".")
  .map((segment) => (/^[a-zA-Z]/.test(segment) ? segment : `x${segment}`))
  .join(".") || "space.manus.app";

const config: ExpoConfig = {
  name: "Машина времени",
  slug: "android-time-cycler",
  version: APP_VERSION,
  orientation: "portrait",
  icon: "./assets/images/icon.png",
  scheme: "timecycler",
  userInterfaceStyle: "automatic",
  newArchEnabled: true,
  ios: {
    supportsTablet: true,
    bundleIdentifier: bundleId,
    infoPlist: { ITSAppUsesNonExemptEncryption: false },
  },
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
    package: bundleId,
    versionCode: ANDROID_VERSION_CODE,
    permissions: ["POST_NOTIFICATIONS"],
  },
  plugins: [
    "expo-router",
    "./plugins/with-time-shizuku",
    "expo-font",
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
        android: { buildArchs: ["arm64-v8a"], minSdkVersion: 24 },
      },
    ],
  ],
  experiments: { typedRoutes: true, reactCompiler: true },
};

export default config;