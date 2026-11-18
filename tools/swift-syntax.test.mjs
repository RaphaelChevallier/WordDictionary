import test from "node:test";
import assert from "node:assert/strict";
import { checkSwiftSources, swiftSyntaxErrors } from "./swift-syntax.mjs";

test("Swift syntax check reports malformed source rather than a false pass", async () => {
  assert.notDeepEqual(await swiftSyntaxErrors("struct Broken { let = }"), []);
  assert.deepEqual(await swiftSyntaxErrors("struct Word { let text: String }"), []);
});

test("native iOS application and test sources parse", async () => {
  await checkSwiftSources();
});
