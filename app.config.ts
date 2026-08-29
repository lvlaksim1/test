import "./scripts/load-env.js";
import type { ExpoConfig } from "expo/config";
// Release 28 - settings and notification pairing flow.
const rawBundleId="com.app.androidtimecycler"; const bundleId=rawBundleId.replace(/[-_]/g,".").replace(/[^a-zA-Z0-9.]/g,"").replace(/\.+/g,".").replace(/^\.+|\.+$/g,"").toLowerCase().split(".").map((segment)=>(/^[a-zA-Z]/.test(segment)?segment:`x${segment}`)).join(".")||"space.manus.app";
const env={appName:"Машина времени",appSlug:"android-time-cycler",logoUrl:"/manus-storage/android-time-cycler-icon_cca46255.png",scheme:"timecycler",iosBundleId:bundleId,androidPackage:bundleId};
const config:ExpoConfig={
name:env.appName,
slug:env.appSlug,
version: "1.0.28",
orientation:"portrait",icon:"./assets/images/icon.png",scheme:env.scheme,userInterfaceStyle:"automatic",newArchEnabled:true,ios:{supportsTablet:true,bundleIdentifier:env.iosBundleId,infoPlist:{ITSAppUsesNonExemptEncryption:false}},android:{adaptiveIcon:{backgroundColor:"#3157C9",foregroundImage:"./assets/images/android-icon-foreground.png",backgroundImage:"./assets/images/android-icon-background.png",monochromeImage:"./assets/images/android-icon-monochrome.png"},edgeToEdgeEnabled:false,predictiveBackGestureEnabled:false,softwareKeyboardLayoutMode:"resize",package:env.androidPackage,versionCode:128,permissions:["POST_NOTIFICATIONS"]},web:{bundler:"metro",output:"static",favicon:"./assets/images/favicon.png"},extra:{eas:{projectId:"136ab1c4-ae98-4502-a9b5-1ebd84c51e8d"}},plugins:["expo-router","./plugins/with-time-bridge","expo-audio","expo-font","expo-video","expo-web-browser",["expo-splash-screen",{image:"./assets/images/splash-icon.png",imageWidth:200,resizeMode:"contain",backgroundColor:"#F6F8FC",dark:{backgroundColor:"#101725"}}],["expo-build-properties",{android:{buildArchs:["armeabi-v7a","arm64-v8a"],minSdkVersion:24}}]],experiments:{typedRoutes:true,reactCompiler:true}}; export default config;
