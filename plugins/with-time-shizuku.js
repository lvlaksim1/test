const { createRunOncePlugin, withAndroidManifest, withAppBuildGradle, withDangerousMod, withMainApplication } = require("@expo/config-plugins");
const fs = require("fs");
const path = require("path");

const PLUGIN_NAME = "with-time-shizuku";
const PLUGIN_VERSION = "1.1.0";

function withTimeShizuku(config) {
  config = withAndroidManifest(config, (mod) => {
    const manifest = mod.modResults.manifest;
    const application = manifest.application?.[0];
    if (!application) throw new Error("Android application node was not found in AndroidManifest.xml");

    manifest["uses-permission"] = manifest["uses-permission"] ?? [];
    for (const permissionName of ["android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE"]) {
      if (!manifest["uses-permission"].some((permission) => permission.$?.["android:name"] === permissionName)) {
        manifest["uses-permission"].push({ $: { "android:name": permissionName } });
      }
    }

    application.provider = application.provider ?? [];
    const providerName = "rikka.shizuku.ShizukuProvider";
    if (!application.provider.some((provider) => provider.$?.["android:name"] === providerName)) {
      application.provider.push({
        $: {
          "android:name": providerName,
          "android:authorities": `${config.android.package}.shizuku`,
          "android:multiprocess": "false",
          "android:enabled": "true",
          "android:exported": "true",
          "android:permission": "android.permission.INTERACT_ACROSS_USERS_FULL",
        },
      });
    }

    application.service = application.service ?? [];
    const serviceName = `${config.android.package}.timeaccessibility.TimeCycleForegroundService`;
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
              "android:value": "User-started cyclic system time operation",
            },
          },
        ],
      });
    }
    return mod;
  });

  config = withAppBuildGradle(config, (mod) => {
    if (!mod.modResults.contents.includes("dev.rikka.shizuku:api:13.1.5")) {
      mod.modResults.contents = mod.modResults.contents.replace(/dependencies\s*\{/, 'dependencies {\n    implementation "dev.rikka.shizuku:api:13.1.5"\n    implementation "dev.rikka.shizuku:provider:13.1.5"');
    }
    if (!mod.modResults.contents.includes("junit:junit:4.13.2")) {
      mod.modResults.contents = mod.modResults.contents.replace(/dependencies\s*\{/, 'dependencies {\n    testImplementation "junit:junit:4.13.2"');
    }
    if (!mod.modResults.contents.includes("aidl true")) {
      mod.modResults.contents = mod.modResults.contents.replace(/android\s*\{/, "android {\n    buildFeatures {\n        aidl true\n    }");
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
    if (!packageName) throw new Error("android.package must be set before adding the Shizuku bridge");
    const projectRoot = mod.modRequest.platformProjectRoot;
    const sourceRoot = path.join(__dirname, "..", "native", "timeaccessibility");
    const testSourceRoot = path.join(__dirname, "..", "native", "timeaccessibility-tests");
    const packageParts = packageName.split(".");
    const kotlinRoot = path.join(projectRoot, "app", "src", "main", "java", ...packageParts, "timeaccessibility");
    const aidlRoot = path.join(projectRoot, "app", "src", "main", "aidl", ...packageParts, "timeaccessibility");
    const testRoot = path.join(projectRoot, "app", "src", "test", "java", ...packageParts, "timeaccessibility");
    fs.mkdirSync(kotlinRoot, { recursive: true });
    fs.mkdirSync(aidlRoot, { recursive: true });
    fs.mkdirSync(testRoot, { recursive: true });

    for (const fileName of [
      "CycleEngine.kt",
      "CycleScheduler.kt",
      "TimeControlModule.kt",
      "TimeControlPackage.kt",
      "TimeCycleStore.kt",
      "TimeShizukuController.kt",
      "TimeShizukuCycleRunner.kt",
      "TimeShizukuUserService.kt",
      "TimeCycleForegroundService.kt",
    ]) {
      const source = fs.readFileSync(path.join(sourceRoot, fileName), "utf8");
      fs.writeFileSync(path.join(kotlinRoot, fileName), source.replaceAll("__PACKAGE__", packageName));
    }

    const aidlSource = fs.readFileSync(path.join(sourceRoot, "ITimeShizukuService.aidl"), "utf8");
    fs.writeFileSync(path.join(aidlRoot, "ITimeShizukuService.aidl"), aidlSource.replaceAll("__PACKAGE__", packageName));

    const engineTest = fs.readFileSync(path.join(testSourceRoot, "CycleEngineTest.kt"), "utf8");
    fs.writeFileSync(path.join(testRoot, "CycleEngineTest.kt"), engineTest.replaceAll("__PACKAGE__", packageName));
    return mod;
  }]);
  return config;
}

module.exports = createRunOncePlugin(withTimeShizuku, PLUGIN_NAME, PLUGIN_VERSION);
