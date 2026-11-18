import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { prepareResources } from "./ios-resources.mjs";
import { prepareProject } from "./ios-project.mjs";
import { checkSwiftSources } from "./swift-syntax.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const ios = join(root, "apps", "ios");
const command = process.argv[2] ?? "verify";
if (!["prepare", "verify", "build", "test"].includes(command)) throw new Error("Use prepare, verify, build or test.");

function run(executable, arguments_, options = {}) {
  const result = spawnSync(executable, arguments_, { cwd: root, encoding: "utf8", ...options });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${executable} failed (${result.status}): ${result.stderr ?? ""}`);
  return result.stdout;
}

run(process.execPath, [join(root, "tools", "apple-content.mjs"), command === "verify" ? "verify" : "prepare"], { stdio: "inherit" });
prepareResources(command === "verify");
prepareProject(command === "verify");
await checkSwiftSources();
if (command === "prepare" || command === "verify") {
  console.log("Shared iOS content, project wiring and Swift syntax checked. Apple SDK compilation and device behavior are NOT verified by this command.");
  process.exit(0);
}
if (process.platform !== "darwin") {
  throw new Error("An iOS build/test requires a Mac with full Xcode 16+ selected by xcode-select. Use npm run verify:ios here, then run this command on your Mac.");
}
const version = run("xcodebuild", ["-version"]);
const major = Number(version.match(/Xcode (\d+)/)?.[1]);
if (!major || major < 16) throw new Error("Full Xcode 16 or newer is required for iOS 18 and Swift 6 package resolution.");

let destination = "generic/platform=iOS Simulator";
if (command === "test") {
  const devices = JSON.parse(run("xcrun", ["simctl", "list", "devices", "available", "--json"]));
  const candidates = Object.entries(devices.devices ?? {}).flatMap(([runtime, values]) => {
    const version = runtime.match(/iOS-(\d+)(?:-(\d+))?/);
    if (!version || Number(version[1]) < 18) return [];
    return values.filter((device) => device.isAvailable).map((device) => ({ ...device, version: Number(version[1]) * 100 + Number(version[2] ?? 0) }));
  }).sort((a, b) => Number(b.state === "Booted") - Number(a.state === "Booted") ||
    b.version - a.version || Number(b.name.startsWith("iPhone")) - Number(a.name.startsWith("iPhone")));
  const requested = process.env.MAXFLUENCY_IOS_DEVICE_UDID;
  const selected = requested ? candidates.find((device) => device.udid === requested) : candidates[0];
  if (!selected) throw new Error("Install an iOS 18+ simulator in Xcode Settings, or choose an available MAXFLUENCY_IOS_DEVICE_UDID.");
  destination = `platform=iOS Simulator,id=${selected.udid}`;
}
const project = join(ios, "MaxFluency.xcodeproj");
if (!existsSync(project)) throw new Error("The generated Xcode project is missing.");
run("xcodebuild", ["-project", project, "-scheme", "MaxFluency", "-configuration", "Debug",
  "-destination", destination, "-derivedDataPath", join(ios, "build"), "CODE_SIGNING_ALLOWED=NO",
  command === "test" ? "test" : "build"], { stdio: "inherit" });
