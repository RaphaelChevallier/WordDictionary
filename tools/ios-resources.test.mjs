import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { appleFormat, localization } from "./ios-resources.mjs";
import { swiftFiles } from "./swift-syntax.mjs";

test("Apple localization converts positional Java formats and escapes without breaking literal percent", () => {
  assert.equal(appleFormat("%1$s · %2$,d · 50%% &amp; l\\'app"), "%1$@ · %2$ld · 50%% & l'app");
});

test("every literal native interface key is translated in all supported languages", () => {
  const root = fileURLToPath(new URL("../", import.meta.url));
  const keys = new Set(swiftFiles(join(root, "apps", "ios", "MaxFluency")).flatMap((file) =>
    [...readFileSync(file, "utf8").matchAll(/\bmodel\.(?:text|format|plural)\("([a-z0-9_]+)"/g)].map((match) => match[1])));
  assert.ok(keys.size > 0);
  for (const language of ["en", "fr", "es"]) {
    const { strings, plurals } = localization(language);
    for (const key of keys) assert.ok(strings.has(key) || plurals.has(key), `${language}: ${key}`);
  }
});

test("native platform instructions and singular/plural hints exist in every interface language", () => {
  for (const language of ["en", "fr", "es"]) {
    const { strings, plurals } = localization(language);
    assert.ok(strings.get("translation_credit").includes("Apple"));
    assert.ok(!strings.get("privacy_note").includes("Google"));
    assert.ok(!strings.get("anki_export_description").includes("AnkiDroid"));
    assert.ok(plurals.get("more_meanings").one.includes("%1$ld"));
    assert.ok(plurals.get("more_meanings").other.includes("%1$ld"));
    for (const value of strings.values()) assert.doesNotMatch(value, /%\d+\$,?[sd]/);
  }
});
