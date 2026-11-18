import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const androidRoot = join(root, "apps", "android");
const windows = process.platform === "win32";
const environment = { ...process.env };
const tasks = process.argv.slice(2);
if (!tasks.length || tasks.some((task) => !/^[a-zA-Z0-9_:.*=+-]+$/.test(task))) {
  throw new Error("Provide Gradle task names/options, for example: node tools/android.mjs assembleDebug");
}

const programFiles = environment.ProgramFiles || "C:\\Program Files";
const javaDirectory = join(programFiles, "Java");
const candidates = [
  environment.MAXFLUENCY_JAVA_HOME,
  environment.JAVA_HOME,
  ...(windows && existsSync(javaDirectory)
    ? readdirSync(javaDirectory).filter((name) => /^jdk-(17|21|24)(\D|$)/.test(name))
      .sort().map((name) => join(javaDirectory, name))
    : []),
  ...(windows ? [join(programFiles, "Android", "Android Studio", "jbr")] : []),
].filter(Boolean);

let javaHome;
for (const candidate of candidates) {
  const executable = join(candidate, "bin", windows ? "java.exe" : "java");
  if (!existsSync(executable)) continue;
  const result = spawnSync(executable, ["-version"], { encoding: "utf8", windowsHide: true });
  const version = `${result.stdout}${result.stderr}`.match(/version "(?:1\.)?(\d+)/)?.[1];
  if (result.status === 0 && version && Number(version) >= 17 && Number(version) <= 24) {
    javaHome = candidate;
    break;
  }
}
if (!javaHome) throw new Error("A JDK from 17 through 24 is required. Set JAVA_HOME or MAXFLUENCY_JAVA_HOME (JDK 21 recommended).");
environment.JAVA_HOME = javaHome;

const sdk = [
  environment.ANDROID_HOME,
  environment.ANDROID_SDK_ROOT,
  join(root, ".tools", "android-sdk"),
  ...(windows && environment.LOCALAPPDATA ? [join(environment.LOCALAPPDATA, "Android", "Sdk")] : []),
].find((path) => path && existsSync(join(path, "platforms", "android-36")));
if (!sdk) throw new Error("Android SDK platform 36 is required. Set ANDROID_HOME or install it in .tools\\android-sdk.");
environment.ANDROID_HOME = sdk;
environment.ANDROID_SDK_ROOT = sdk;
environment.GRADLE_USER_HOME ||= join(root, ".gradle-user-home");

const arguments_ = ["--no-daemon", "--max-workers=2", ...tasks];
console.log(`Building MaxFluency with ${javaHome}`);
const result = windows
  ? spawnSync(environment.ComSpec || "cmd.exe",
    ["/d", "/s", "/c", ["gradlew.bat", ...arguments_].join(" ")],
    { cwd: androidRoot, env: environment, stdio: "inherit", windowsHide: true })
  : spawnSync("sh", [join(androidRoot, "gradlew"), ...arguments_],
    { cwd: androidRoot, env: environment, stdio: "inherit" });
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
if (result.status === 0) {
  for (const [task, variant] of [
    ["assembleDebug", "debug"],
    ["assembleRelease", "release"],
  ]) {
    if (!tasks.some((requested) => requested === task || requested.endsWith(`:${task}`))) continue;
    const directory = join(androidRoot, "app", "build", "outputs", "apk", variant);
    const metadata = JSON.parse(readFileSync(join(directory, "output-metadata.json"), "utf8"));
    if (!metadata.elements?.length) throw new Error("The build produced no installable APK outputs.");
    for (const output of metadata.elements) {
      const bytes = statSync(join(directory, output.outputFile)).size;
      console.log(`${output.outputFile}: ${(bytes / 1e6).toFixed(2)} MB (${bytes.toLocaleString("en")} bytes)`);
      if (bytes >= 100_000_000) throw new Error("APK size budget exceeded: the complete app must stay below 100 MB.");
      if (variant === "release") {
        const { targetReleaseApkBytes } = JSON.parse(readFileSync(join(root, "content", "sources.json"), "utf8"));
        if (bytes > targetReleaseApkBytes) {
          throw new Error(`SIZE TARGET NOT MET: ${output.outputFile} exceeds the ${(targetReleaseApkBytes / 1e6).toFixed(1)} MB per-device whole-app goal. No dictionary coverage was removed.`);
        }
      }
    }
  }
}
