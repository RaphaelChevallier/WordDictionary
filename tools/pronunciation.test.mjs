import assert from "node:assert/strict";
import test from "node:test";
import { buildGuideAsset, parseCmuDictionary, respellPronunciation } from "./pronunciation.mjs";

test("CMU stress and checked vowels produce readable disaster syllables", () => {
  assert.equal(respellPronunciation("D IH0 Z AE1 S T ER0"), "dih-ZAS-ter");
  assert.equal(respellPronunciation("K AH0 M P Y UW1 T ER0"), "kuhm-PYOO-ter");
});

test("common vowel and consonant groups stay readable without IPA", () => {
  const examples = new Map([
    ["K AE1 T", "KAT"],
    ["N AY1 T", "NYTE"],
    ["TH R UW1", "THROO"],
    ["TH AO1 T", "THAWT"],
    ["B Y UW1 T AH0 F AH0 L", "BYOO-tuh-fuhl"],
    ["T IY1 CH ER0", "TEE-cher"],
    ["CH EH1 R", "CHAIR"],
    ["AY1 S", "EYESS"],
    ["N AY1 S", "NYCE"],
    ["S AY1 Z", "SYZE"],
    ["EY1 S", "AYSS"],
  ]);
  for (const [phones, expected] of examples) assert.equal(respellPronunciation(phones), expected);
});

test("source-listed variants are deduplicated without choosing a heteronym", () => {
  const entries = parseCmuDictionary("read R IY1 D\nread(2) R EH1 D\nread(3) R IY1 D\n");
  assert.equal(entries.get("read"), "REED / RED");
});

test("unexpected phonemes and missing vowel stress fail explicitly", () => {
  assert.throws(() => respellPronunciation("D XX1 T"), /phoneme/);
  assert.throws(() => respellPronunciation("D IH T"), /phoneme/);
  assert.throws(() => parseCmuDictionary("disaster\n"), /entry/);
});

test("indexed assets are deterministic and preserve the sorted word-to-guide association", () => {
  const input = [["night", "NYTE"], ["disaster", "dih-ZAS-ter"], ["read", "REED / RED"]];
  const first = buildGuideAsset(new Map(input));
  assert.deepEqual(first, buildGuideAsset(new Map(input.toReversed())));
  const count = first.readUInt32LE(8);
  const start = 24 + (count + 1) * 4;
  const records = Array.from({ length: count }, (_, i) =>
    first.subarray(start + first.readUInt32LE(24 + i * 4),
      start + first.readUInt32LE(28 + i * 4)).toString("utf8"));
  assert.deepEqual(records, ["disaster\tdih-ZAS-ter", "night\tNYTE", "read\tREED / RED"]);
});
