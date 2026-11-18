import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { attachSourceExamples, mergeSourceContent } from "./usage-examples.mjs";
import { PackedLexicon } from "./packed-lexicon.mjs";
import { cleanDefinition, selectUsageExamples } from "./content-quality.mjs";

test("usage samples attach by source and exact meaning, never by the headword alone", () => {
  const entry = { senses: [
    { partOfSpeech: "verb", definition: "A first meaning.", sourceIds: ["main"] },
    { partOfSpeech: "verb", definition: "A second meaning.", sourceIds: ["other"] },
  ] };
  attachSourceExamples(entry, { pos: "verb", senses: [
    { glosses: ["A first meaning."], examples: [{ text: "The first example." }] },
    { glosses: ["A second meaning."], examples: [{ text: "Do not attach from the wrong source." }] },
    { glosses: ["A different meaning."], examples: [{ text: "Never attach to another meaning." }] },
  ] }, "main");
  assert.deepEqual(entry.senses[0].examples, ["The first example."]);
  assert.equal(entry.senses[1].examples, undefined);
});

test("pinned starter examples preserve both meanings of abjurer and do not enter daily discovery", async () => {
  const root = fileURLToPath(new URL("../", import.meta.url));
  const manifest = JSON.parse(await readFile(join(root, "content", "starter", "manifest.json"), "utf8"));
  const pack = manifest.languages.find((item) => item.language === "fr");
  const reader = new PackedLexicon(await readFile(join(root, "content", "starter", "packs", pack.file)));
  const words = Array.from({ length: reader.entries }, (_, i) => reader.word(i).split("\x1f")[1]);
  const ordinal = words.indexOf("abjurer");
  assert.ok(ordinal >= 0);
  const entry = reader.entry(ordinal);
  assert.equal(entry.senses.length, 2);
  assert.ok(!entry.senses[0].examples?.length, "Attributed quotations must not be copied as usage samples");
  assert.ok(entry.senses[1].examples.length >= 2);
  assert.equal(reader.daily.includes(ordinal), false);
  assert.ok(entry.senses.every((sense) => sense.sourceIds.includes("fr-wiktionary")));
});

test("polish restores missing source meanings and qualifiers without duplicating an existing unlabelled sense", () => {
  const entry = { sourceIds: ["main"], senses: [
    { partOfSpeech: "verb", definition: "Un sens.", sourceIds: ["main"] },
  ] };
  const record = { lang_code: "fr", pos: "verb", senses: [
    { glosses: ["Un sens."], tags: ["figuratively"], examples: [{ text: "Cet exemple est précis." }] },
    { glosses: ["Un autre sens."], examples: [{ text: "Un autre exemple reste associé." }] },
  ] };
  mergeSourceContent(entry, record, "main");
  mergeSourceContent(entry, record, "main");
  assert.equal(entry.senses.length, 2);
  assert.equal(entry.senses[0].definition, "(sens figuré) Un sens.");
  assert.deepEqual(entry.senses[1].examples, ["Un autre exemple reste associé."]);
});

test("polished bundled data increases useful coverage without raw markup or leaked citation examples", async () => {
  const root = fileURLToPath(new URL("../", import.meta.url));
  const manifest = JSON.parse(await readFile(join(root, "content", "starter", "manifest.json"), "utf8"));
  let totalWords = 0, meanings = 0, examples = 0, coveredWords = 0;
  for (const pack of manifest.languages) {
    const reader = new PackedLexicon(await readFile(join(root, "content", "starter", "packs", pack.file)));
    totalWords += reader.entries;
    for (let i = 0; i < reader.entries; i++) {
      const entry = reader.entry(i);
      if (entry.senses.some((sense) => sense.examples?.length)) coveredWords++;
      const unique = new Set();
      for (const sense of entry.senses) {
        meanings++;
        const key = `${sense.partOfSpeech}\0${sense.definition}`;
        assert.equal(unique.has(key), false, `${entry.word} has a duplicate meaning`);
        unique.add(key);
        assert.equal(cleanDefinition(sense.definition), sense.definition, entry.word);
        const samples = sense.examples ?? [];
        examples += samples.length;
        assert.deepEqual(selectUsageExamples(samples), samples, entry.word);
      }
    }
  }
  assert.equal(totalWords, 8235, "Retain valid headwords; omit the one verified undefined source placeholder");
  assert.ok(meanings > 32948, "Recover genuine source senses excluded by the former eight-meaning limit");
  assert.ok(examples > 16156, "Increase usable source-backed examples over the measured baseline");
  assert.ok(coveredWords > 3779, "Increase headwords with at least one usable example");
  const supplement = JSON.parse(await readFile(join(root, "content", "supplements", "usage-examples.json"), "utf8"));
  assert.deepEqual(supplement.languages.flatMap((language) => language.excludedHeadwords.map(({ word }) => word)),
    ["funeraria"], "Every omitted undefined headword must be explicit and auditable");
});
