const { createRunOncePlugin, withAndroidManifest, withAppBuildGradle, withDangerousMod, withMainApplication, withProjectBuildGradle } = require("@expo/config-plugins");
const fs = require("fs");
const path = require("path");

const PLUGIN_NAME = "with-time-bridge";
const PLUGIN_VERSION = "1.0.1";

function withTimeBridge(config) {
  config = withAndroidManifest(config, (mod) => {
    const manifest = mod.modResults.manifest;
    const application = manifest.application?.[0];
    if (!application) throw new Error("Android application node was not found in AndroidManifest.xml");

    manifest["uses-permission"] = manifest["uses-permission"] ?? [];
    for (const permissionName of [
      "android.permission.INTERNET",
      "android.permission.ACCESS_NETWORK_STATE",
      "android.permission.FOREGROUND_SERVICE",
      "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    ]) {
      if (!manifest["uses-permission"].some((permission) => permission.$?.["android:name"] === permissionName)) {
        manifest["uses-permission"].push({ $: { "android:name": permissionName } });
      }
    }

    application.service = application.service ?? [];
    for (const [className, subtype] of [
      ["TimeCycleForegroundService", "User-started cyclic system time operation"],
      ["TimePairingService", "User-started wireless debugging pairing operation"],
    ]) {
      const serviceName = `${config.android.package}.timeaccessibility.${className}`;
      if (!application.service.some((service) => service.$?.["android:name"] === serviceName)) {
        application.service.push({
          $: {
            "android:name": serviceName,
            "android:enabled": "true",
            "android:exported": "false",
            "android:foregroundServiceType": "specialUse",
          },
          property: [
            {
              $: {
                "android:name": "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE",
                "android:value": subtype,
              },
            },
          ],
        });
      }
    }

    application.activity = application.activity ?? [];
    const launcherActivityName = `${config.android.package}.timeaccessibility.TimePairingLauncherActivity`;
    if (!application.activity.some((activity) => activity.$?.["android:name"] === launcherActivityName)) {
      application.activity.push({
        $: {
          "android:name": launcherActivityName,
          "android:enabled": "true",
          "android:exported": "false",
          "android:excludeFromRecents": "true",
          "android:theme": "@android:style/Theme.Translucent.NoTitleBar",
        },
      });
    }
    return mod;
  });

  config = withProjectBuildGradle(config, (mod) => {
    if (!mod.modResults.contents.includes("jitpack.io")) {
      if (/allprojects\s*\{[\s\S]*?repositories\s*\{/.test(mod.modResults.contents)) {
        mod.modResults.contents = mod.modResults.contents.replace(
          /(allprojects\s*\{[\s\S]*?repositories\s*\{)/,
          "$1\n        maven { url 'https://jitpack.io' }",
        );
      } else {
        mod.modResults.contents += "\nallprojects {\n    repositories {\n        maven { url 'https://jitpack.io' }\n    }\n}\n";
      }
    }
    return mod;
  });

  config = withAppBuildGradle(config, (mod) => {
    const dependencies = [
      'implementation "com.github.MuntashirAkon:libadb-android:3.1.1"',
      'implementation "com.github.MuntashirAkon:sun-security-android:1.1"',
      'implementation "org.conscrypt:conscrypt-android:2.5.3"',
    ];
    for (const dependency of dependencies) {
      if (!mod.modResults.contents.includes(dependency)) {
        mod.modResults.contents = mod.modResults.contents.replace(/dependencies\s*\{/, `dependencies {\n    ${dependency}`);
      }
    }
    return mod;
  });

  config = withMainApplication(config, (mod) => {
    const packageName = config.android?.package;
    if (!packageName || mod.modResults.language !== "kt") return mod;
    const importLine = `import ${packageName}.timeaccessibility.TimeControlPackage`;
    if (!mod.modResults.contents.includes(importLine)) {
      mod.modResults.contents = mod.modResults.contents.replace(/^(package\s+[^\n]+)$/m, `$1\n\n${importLine}`);
    }
    if (!mod.modResults.contents.includes("add(TimeControlPackage())")) {
      mod.modResults.contents = mod.modResults.contents.replace(/(PackageList\(this\)\.packages\.apply\s*\{)/, "$1\n          add(TimeControlPackage())");
    }
    return mod;
  });

  config = withDangerousMod(config, ["android", async (mod) => {
    const packageName = config.android?.package;
    if (!packageName) throw new Error("android.package must be set before adding the Time Bridge");
    const projectRoot = mod.modRequest.platformProjectRoot;
    const sourceRoot = path.join(__dirname, "..", "native", "timeaccessibility");
    const kotlinRoot = path.join(projectRoot, "app", "src", "main", "java", ...packageName.split("."), "timeaccessibility");
    fs.mkdirSync(kotlinRoot, { recursive: true });
    for (const fileName of [
      "TimeControlModule.kt",
      "TimeControlPackage.kt",
      "TimeCycleStore.kt",
      "TimeLocalAdbConnectionManager.kt",
      "TimeLocalAdbController.kt",
      "TimeLocalAdbExperiment.kt",
      "TimeCycleRunner.kt",
      "TimeCycleForegroundService.kt",
      "TimePairingService.kt",
      "TimePairingLauncherActivity.kt",
    ]) {
      const source = fs.readFileSync(path.join(sourceRoot, fileName), "utf8");
      fs.writeFileSync(path.join(kotlinRoot, fileName), source.replaceAll("__PACKAGE__", packageName));
    }
    return mod;
  }]);

  return config;
}

module.exports = createRunOncePlugin(withTimeBridge, PLUGIN_NAME, PLUGIN_VERSION);
