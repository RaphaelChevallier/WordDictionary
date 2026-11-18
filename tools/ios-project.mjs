import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const ios = join(root, "apps", "ios");
const projectName = "MaxFluency";
const quote = (value) => JSON.stringify(String(value));
const logical = (value) => value.replaceAll("\\", "/");

function sourceFiles(directory) {
  if (!existsSync(directory)) throw new Error(`Missing native source directory: ${directory}`);
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? sourceFiles(path) : /\.(swift|c|h)$/.test(entry.name) ? [path] : [];
  }).sort();
}

export function projectFiles() {
  const objects = new Map(), ids = new Map();
  function id(key) {
    const value = createHash("sha256").update(`maxfluency:${key}`).digest("hex").slice(0, 24).toUpperCase();
    if (ids.has(value) && ids.get(value) !== key) throw new Error("Xcode object identifier collision.");
    ids.set(value, key);
    return value;
  }
  function add(key, isa, values) {
    const reference = id(key);
    if (objects.has(reference)) throw new Error(`Duplicate Xcode object: ${key}`);
    objects.set(reference, { isa, ...values });
    return reference;
  }
  const raw = (value) => ({ raw: value });
  const reference = (key) => raw(id(key));
  const version = readFileSync(join(root, "version.properties"), "utf8");
  const marketing = version.match(/^versionName=(.+)$/m)?.[1]?.trim();
  const build = version.match(/^versionCode=(\d+)$/m)?.[1];
  if (!marketing || !build) throw new Error("Shared release version is invalid.");

  const products = [
    ["app", "MaxFluency.app", "wrapper.application"],
    ["unit", "MaxFluencyTests.xctest", "wrapper.cfbundle"],
    ["ui", "MaxFluencyUITests.xctest", "wrapper.cfbundle"]
  ].map(([key, path, type]) => add(`product:${key}`, "PBXFileReference",
    { explicitFileType: type, includeInIndex: raw("0"), path, sourceTree: "BUILT_PRODUCTS_DIR" }));
  const config = add("project-config-file", "PBXFileReference",
    { lastKnownFileType: "text.xcconfig", path: "Config/Project.xcconfig", sourceTree: "<group>" });
  const frameworks = ["libsqlite3.tbd", "libz.tbd"].map((name) =>
    add(`framework:${name}`, "PBXFileReference", { lastKnownFileType: "sourcecode.text-based-dylib-definition", path: `usr/lib/${name}`, sourceTree: "SDKROOT" }));
  const packageReference = add("swiftsoup-package", "XCRemoteSwiftPackageReference", {
    repositoryURL: "https://github.com/scinfu/SwiftSoup.git",
    requirement: { kind: "exactVersion", version: "2.14.4" }
  });
  const soup = add("swiftsoup-product", "XCSwiftPackageProductDependency",
    { package: raw(packageReference), productName: "SwiftSoup" });
  const content = ["apple", "pronunciations", "licenses"].map((name) =>
    add(`shared:${name}`, "PBXFileReference", { lastKnownFileType: "folder", name, path: `../../content/${name}`, sourceTree: "SOURCE_ROOT" }));
  const groups = [];
  const targets = [];
  const appSources = [];
  for (const [kind, folder, productType] of [
    ["app", "MaxFluency", "com.apple.product-type.application"],
    ["unit", "MaxFluencyTests", "com.apple.product-type.bundle.unit-test"],
    ["ui", "MaxFluencyUITests", "com.apple.product-type.bundle.ui-testing"]
  ]) {
    const files = sourceFiles(join(ios, folder));
    if (!files.some((file) => file.endsWith(".swift"))) throw new Error(`No Swift sources in ${folder}.`);
    const children = [], sources = [], resources = [];
    for (const file of files) {
      const path = logical(relative(join(ios, folder), file));
      const fileKey = `source:${kind}:${path}`;
      const ref = add(fileKey, "PBXFileReference", {
        lastKnownFileType: file.endsWith(".swift") ? "sourcecode.swift" : file.endsWith(".c") ? "sourcecode.c.c" : "sourcecode.c.h",
        path, sourceTree: "<group>"
      });
      children.push(raw(ref));
      if (!file.endsWith(".h")) {
        sources.push(raw(add(`build:${fileKey}`, "PBXBuildFile", { fileRef: raw(ref) })));
        if (kind === "app") appSources.push(logical(relative(ios, file)));
      }
    }
    if (kind === "app") {
      for (const [name, type] of [
        ["Info.plist", "text.plist.xml"], ["Resources/Assets.xcassets", "folder.assetcatalog"],
        ["Resources/PrivacyInfo.xcprivacy", "text.xml"], ["Resources/SwiftSoup-LICENSE.txt", "text"]
      ]) {
        const ref = add(`resource:${name}`, "PBXFileReference", { lastKnownFileType: type, path: name, sourceTree: "<group>" });
        children.push(raw(ref));
        if (name !== "Info.plist") resources.push(raw(add(`resource-build:${name}`, "PBXBuildFile", { fileRef: raw(ref) })));
      }
      for (const name of ["Localizable.strings", "Localizable.stringsdict"]) {
        const variants = ["en", "fr", "es"].map((language) => raw(add(`locale:${language}:${name}`, "PBXFileReference", {
          lastKnownFileType: name.endsWith("stringsdict") ? "text.plist.stringsdict" : "text.plist.strings",
          name: language, path: `Resources/${language}.lproj/${name}`, sourceTree: "<group>"
        })));
        const variant = add(`locale-group:${name}`, "PBXVariantGroup", { children: variants, name, sourceTree: "<group>" });
        children.push(raw(variant));
        resources.push(raw(add(`locale-build:${name}`, "PBXBuildFile", { fileRef: raw(variant) })));
      }
      for (const [index, ref] of content.entries()) resources.push(raw(add(`shared-build:${index}`, "PBXBuildFile", { fileRef: raw(ref) })));
    }
    groups.push(raw(add(`group:${kind}`, "PBXGroup", { children, path: folder, sourceTree: "<group>" })));
    const sourcesPhase = add(`sources:${kind}`, "PBXSourcesBuildPhase", { buildActionMask: raw("2147483647"), files: sources, runOnlyForDeploymentPostprocessing: raw("0") });
    const resourcesPhase = add(`resources:${kind}`, "PBXResourcesBuildPhase", { buildActionMask: raw("2147483647"), files: resources, runOnlyForDeploymentPostprocessing: raw("0") });
    const linked = kind === "app" ? [
      ...frameworks.map((ref, index) => raw(add(`framework-build:${index}`, "PBXBuildFile", { fileRef: raw(ref) }))),
      raw(add("swiftsoup-build", "PBXBuildFile", { productRef: raw(soup) }))
    ] : [];
    const frameworksPhase = add(`frameworks:${kind}`, "PBXFrameworksBuildPhase", { buildActionMask: raw("2147483647"), files: linked, runOnlyForDeploymentPostprocessing: raw("0") });
    const configurations = ["Debug", "Release"].map((name) => {
      const settings = {
        CURRENT_PROJECT_VERSION: build, MARKETING_VERSION: marketing, PRODUCT_NAME: "$(TARGET_NAME)",
        PRODUCT_BUNDLE_IDENTIFIER: `$(MAXFLUENCY_BUNDLE_IDENTIFIER)${kind === "app" ? "" : kind === "unit" ? ".tests" : ".uitests"}`,
        SDKROOT: "iphoneos", SUPPORTED_PLATFORMS: "iphoneos iphonesimulator", TARGETED_DEVICE_FAMILY: "1,2",
        SWIFT_OPTIMIZATION_LEVEL: name === "Debug" ? "-Onone" : "-O", DEBUG_INFORMATION_FORMAT: name === "Debug" ? "dwarf" : "dwarf-with-dsym",
        LD_RUNPATH_SEARCH_PATHS: "$(inherited) @executable_path/Frameworks @loader_path/Frameworks",
        GENERATE_INFOPLIST_FILE: kind === "app" ? "NO" : "YES"
      };
      if (kind === "app") Object.assign(settings, {
        INFOPLIST_FILE: "MaxFluency/Info.plist", ASSETCATALOG_COMPILER_APPICON_NAME: "AppIcon",
        SWIFT_OBJC_BRIDGING_HEADER: "MaxFluency/Core/CompressionBridge.h", SUPPORTS_MACCATALYST: "NO",
        SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD: "NO"
      });
      if (kind === "unit") Object.assign(settings, { TEST_HOST: "$(BUILT_PRODUCTS_DIR)/MaxFluency.app/MaxFluency", BUNDLE_LOADER: "$(TEST_HOST)" });
      if (kind === "ui") settings.TEST_TARGET_NAME = "MaxFluency";
      if (name === "Debug") {
        settings.SWIFT_ACTIVE_COMPILATION_CONDITIONS = "DEBUG";
        settings.ENABLE_TESTABILITY = "YES";
      }
      return raw(add(`config:${kind}:${name}`, "XCBuildConfiguration", { baseConfigurationReference: raw(config), buildSettings: settings, name }));
    });
    const list = add(`config-list:${kind}`, "XCConfigurationList", { buildConfigurations: configurations, defaultConfigurationIsVisible: raw("0"), defaultConfigurationName: "Release" });
    const dependencies = [];
    if (kind !== "app") {
      const proxy = add(`proxy:${kind}`, "PBXContainerItemProxy", { containerPortal: reference("project"),
        proxyType: raw("1"), remoteGlobalIDString: reference("target:app"), remoteInfo: "MaxFluency" });
      dependencies.push(raw(add(`dependency:${kind}`, "PBXTargetDependency", { target: reference("target:app"), targetProxy: raw(proxy) })));
    }
    targets.push(raw(add(`target:${kind}`, "PBXNativeTarget", {
      buildConfigurationList: raw(list), buildPhases: [raw(sourcesPhase), raw(frameworksPhase), raw(resourcesPhase)],
      buildRules: [], dependencies, name: folder, productName: folder, productReference: raw(products[kind === "app" ? 0 : kind === "unit" ? 1 : 2]),
      productType, packageProductDependencies: kind === "app" ? [raw(soup)] : []
    })));
  }
  groups.push(raw(add("group:shared", "PBXGroup", { children: content.map(raw), name: "Shared content", sourceTree: "<group>" })));
  groups.push(raw(add("group:frameworks", "PBXGroup", { children: frameworks.map(raw), name: "Frameworks", sourceTree: "<group>" })));
  const productsGroup = add("group:products", "PBXGroup", { children: products.map(raw), name: "Products", sourceTree: "<group>" });
  groups.push(raw(productsGroup), raw(config));
  const main = add("group:main", "PBXGroup", { children: groups, sourceTree: "<group>" });
  const projectConfigurations = ["Debug", "Release"].map((name) => raw(add(`config:project:${name}`, "XCBuildConfiguration", {
    buildSettings: { ALWAYS_SEARCH_USER_PATHS: "NO", CLANG_ENABLE_MODULES: "YES", IPHONEOS_DEPLOYMENT_TARGET: "18.0" }, name
  })));
  const projectList = add("config-list:project", "XCConfigurationList", { buildConfigurations: projectConfigurations, defaultConfigurationIsVisible: raw("0"), defaultConfigurationName: "Release" });
  add("project", "PBXProject", {
    attributes: { BuildIndependentTargetsInParallel: "YES", LastUpgradeCheck: "1600",
      TargetAttributes: { [id("target:app")]: { CreatedOnToolsVersion: "16.0" },
        [id("target:unit")]: { CreatedOnToolsVersion: "16.0", TestTargetID: raw(id("target:app")) },
        [id("target:ui")]: { CreatedOnToolsVersion: "16.0", TestTargetID: raw(id("target:app")) } } },
    buildConfigurationList: raw(projectList), compatibilityVersion: "Xcode 14.0", developmentRegion: "en",
    hasScannedForEncodings: raw("0"), knownRegions: ["en", "fr", "es", "Base"],
    mainGroup: raw(main), productRefGroup: raw(productsGroup), packageReferences: [raw(packageReference)],
    projectDirPath: "", projectRoot: "", targets
  });

  function serialize(value) {
    if (value && typeof value === "object" && "raw" in value) return value.raw;
    if (Array.isArray(value)) return `(${value.map(serialize).join(", ")}${value.length ? "," : ""})`;
    if (value && typeof value === "object") return `{ ${Object.entries(value).map(([key, entry]) => `${quote(key)} = ${serialize(entry)};`).join(" ")} }`;
    return quote(value);
  }
  const body = [...objects].sort(([a], [b]) => a.localeCompare(b)).map(([key, object]) => `\t\t${key} = ${serialize(object)};`).join("\n");
  const project = `// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {};\n\tobjectVersion = 56;\n\tobjects = {\n${body}\n\t};\n\trootObject = ${id("project")};\n}\n`;
  function buildable(kind, name, product) {
    return `<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="${id(`target:${kind}`)}" BuildableName="${product}" BlueprintName="${name}" ReferencedContainer="container:MaxFluency.xcodeproj"/>`;
  }
  const app = buildable("app", projectName, "MaxFluency.app");
  const scheme = `<?xml version="1.0" encoding="UTF-8"?>\n<Scheme LastUpgradeVersion="1600" version="1.3">
  <BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries>
    <BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">${app}</BuildActionEntry>
  </BuildActionEntries></BuildAction>
  <TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables>
    <TestableReference skipped="NO">${buildable("unit", "MaxFluencyTests", "MaxFluencyTests.xctest")}</TestableReference>
    <TestableReference skipped="NO">${buildable("ui", "MaxFluencyUITests", "MaxFluencyUITests.xctest")}</TestableReference>
  </Testables></TestAction>
  <LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugServiceExtension="internal" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">${app}</BuildableProductRunnable></LaunchAction>
  <ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugServiceExtension="internal"><BuildableProductRunnable runnableDebuggingMode="0">${app}</BuildableProductRunnable></ProfileAction>
  <AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>\n`;
  const files = new Map([
    [join(ios, "MaxFluency.xcodeproj", "project.pbxproj"), project],
    [join(ios, "MaxFluency.xcodeproj", "xcshareddata", "xcschemes", "MaxFluency.xcscheme"), scheme],
    [join(ios, "MaxFluency.xcodeproj", "project.xcworkspace", "contents.xcworkspacedata"), '<?xml version="1.0" encoding="UTF-8"?>\n<Workspace version="1.0"><FileRef location="self:"/></Workspace>\n'],
    [join(ios, "MaxFluency.xcodeproj", "project.xcworkspace", "xcshareddata", "swiftpm", "Package.resolved"), JSON.stringify({
      pins: [{ identity: "swiftsoup", kind: "remoteSourceControl", location: "https://github.com/scinfu/SwiftSoup.git",
        state: { revision: "8397a860c6955924b584c50c5265c6aac0970015", version: "2.14.4" } }], version: 2
    }, null, 2) + "\n"]
  ]);
  return { files, appSources, objectIDs: [...objects.keys()] };
}

export function prepareProject(verify = false) {
  const { files, appSources } = projectFiles();
  for (const [file, text] of files) {
    if (verify) {
      if (!existsSync(file) || readFileSync(file, "utf8") !== text) throw new Error(`Stale Xcode project: ${file}. Run npm run ios:prepare.`);
    } else if (!existsSync(file) || readFileSync(file, "utf8") !== text) {
      mkdirSync(dirname(file), { recursive: true }); writeFileSync(file, text);
    }
  }
  console.log(`Xcode project ${verify ? "verified" : "generated"}: ${appSources.length} native source files, application/unit/UI test targets.`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const command = process.argv[2] ?? "generate";
  if (!["generate", "verify"].includes(command)) throw new Error("Use generate or verify.");
  prepareProject(command === "verify");
}
