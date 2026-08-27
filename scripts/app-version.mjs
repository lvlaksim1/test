import { readFileSync } from "node:fs";

const source = readFileSync(new URL("../app.config.ts", import.meta.url), "utf8");
const version = source.match(/^export const APP_VERSION = "([^"]+)";/m)?.[1];
const versionCode = source.match(/^export const ANDROID_VERSION_CODE = (\d+);/m)?.[1];

if (!version || !versionCode) throw new Error("Application version is not defined in app.config.ts");

console.log(JSON.stringify({ version, versionCode }));
