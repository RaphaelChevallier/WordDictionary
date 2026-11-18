import test from "node:test";
import assert from "node:assert/strict";
import { projectFiles } from "./ios-project.mjs";

test("the native project wires every app source and both test bundles without duplicate IDs", () => {
  const { files, appSources, objectIDs } = projectFiles();
  assert.equal(new Set(objectIDs).size, objectIDs.length);
  const project = [...files].find(([name]) => name.endsWith("project.pbxproj"))[1];
  assert.equal(new Set(appSources).size, appSources.length);
  assert.ok(appSources.some((file) => file.endsWith("MaxFluencyApp.swift")));
  assert.ok(appSources.some((file) => file.endsWith("CompressionBridge.c")));
  for (const file of appSources) assert.ok(project.includes(JSON.stringify(file.replace(/^MaxFluency\//, ""))), file);
  const scheme = [...files].find(([name]) => name.endsWith(".xcscheme"))[1];
  assert.ok(scheme.includes('BuildableName="MaxFluencyTests.xctest"'));
  assert.ok(scheme.includes('BuildableName="MaxFluencyUITests.xctest"'));
});
