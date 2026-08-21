const {
  createRunOncePlugin,
  withAndroidManifest,
  withAppBuildGradle,
  withDangerousMod,
  withMainApplication,
} = require("@expo/config-plugins");
const fs = require("fs");
const path = require("path");

const PLUGIN_NAME = "with-time-accessibility";
const PLUGIN_VERSION = "1.2.0";

function withTimeAccessibility(config) {
  config = withAndroidManifest(config, (mod) => {
    const application = mod.modResults.manifest.application?.[0];
    if (!application) throw new Error("Android application node was not found in AndroidManifest.xml");

    application.service = application.service ?? [];
    application.provider = application.provider ?? [];
    const serviceName = ".timeaccessibility.TimeAccessibilityService";
    if (!application.service.some((service) => service.$?.["android:name"] === serviceName)) {
      application.service.push({
        $: {
          "android:name": serviceName,
          "android:label": "Циклическое изменение времени",
          "android:permission": "android.permission.BIND_ACCESSIBILITY_SERVICE",
          "android:exported": "true",
        },
        "intent-filter": [{ action: [{ $: { "android:name": "android.accessibilityservice.AccessibilityService" } }] }],
        "meta-data": [{ $: { "android:name": "android.accessibilityservice", "android:resource": "@xml/time_accessibility_service" } }],
      });
    }

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
    return mod;
  });

  config = withAppBuildGradle(config, (mod) => {
    if (!mod.modResults.contents.includes("dev.rikka.shizuku:api:13.1.5")) {
      mod.modResults.contents = mod.modResults.contents.replace(
        /dependencies\s*\{/, 
        'dependencies {\n    implementation "dev.rikka.shizuku:api:13.1.5"\n    implementation "dev.rikka.shizuku:provider:13.1.5"',
      );
    }
    if (!mod.modResults.contents.includes("aidl true")) {
      mod.modResults.contents = mod.modResults.contents.replace(
        /android\s*\{/, 
        "android {\n    buildFeatures {\n        aidl true\n    }",
      );
    }
    return mod;
  });

  config = withMainApplication(config, (mod) => {
    const packageName = config.android?.package;
    if (!packageName || mod.modResults.language !== "kt") return mod;
    const importLine = `import ${packageName}.timeaccessibility.TimeAccessibilityPackage`;
    if (!mod.modResults.contents.includes(importLine)) {
      mod.modResults.contents = mod.modResults.contents.replace(/^(package\s+[^\n]+)$/m, `$1\n\n${importLine}`);
    }
    if (!mod.modResults.contents.includes("add(TimeAccessibilityPackage())")) {
      mod.modResults.contents = mod.modResults.contents.replace(/(PackageList\(this\)\.packages\.apply\s*\{)/, "$1\n          add(TimeAccessibilityPackage())");
    }
    return mod;
  });

  config = withDangerousMod(config, ["android", async (mod) => {
    const packageName = config.android?.package;
    if (!packageName) throw new Error("android.package must be set before adding the accessibility service");
    const projectRoot = mod.modRequest.platformProjectRoot;
    const sourceRoot = path.join(__dirname, "..", "native", "timeaccessibility");
    const kotlinRoot = path.join(projectRoot, "app", "src", "main", "java", ...packageName.split("."), "timeaccessibility");
    const aidlRoot = path.join(projectRoot, "app", "src", "main", "aidl", ...packageName.split("."), "timeaccessibility");
    const resourceRoot = path.join(projectRoot, "app", "src", "main", "res", "xml");
    const valuesRoot = path.join(projectRoot, "app", "src", "main", "res", "values");
    fs.mkdirSync(kotlinRoot, { recursive: true });
    fs.mkdirSync(aidlRoot, { recursive: true });
    fs.mkdirSync(resourceRoot, { recursive: true });
    fs.mkdirSync(valuesRoot, { recursive: true });

    for (const fileName of [
      "TimeAccessibilityModule.kt",
      "TimeAccessibilityPackage.kt",
      "TimeAccessibilityService.kt",
      "TimeCycleStore.kt",
      "TimeShizukuController.kt",
      "TimeShizukuUserService.kt",
    ]) {
      const source = fs.readFileSync(path.join(sourceRoot, fileName), "utf8");
      fs.writeFileSync(path.join(kotlinRoot, fileName), source.replaceAll("__PACKAGE__", packageName));
    }

    const aidlSource = fs.readFileSync(path.join(sourceRoot, "ITimeShizukuService.aidl"), "utf8");
    fs.writeFileSync(path.join(aidlRoot, "ITimeShizukuService.aidl"), aidlSource.replaceAll("__PACKAGE__", packageName));
    fs.copyFileSync(path.join(sourceRoot, "time_accessibility_service.xml"), path.join(resourceRoot, "time_accessibility_service.xml"));
    fs.copyFileSync(path.join(sourceRoot, "time_accessibility_strings.xml"), path.join(valuesRoot, "time_accessibility_strings.xml"));
    return mod;
  }]);
  return config;
}

module.exports = createRunOncePlugin(withTimeAccessibility, PLUGIN_NAME, PLUGIN_VERSION);
