import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { deflateSync } from "node:zlib";
import { PackedLexicon } from "./packed-lexicon.mjs";
import {
  AppleLexicon, buildApplePack, loadPinnedSources, prepareAppleContent, verifyAppleContent,
} from "./apple-content.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const sources = [
  { id: "fr-main", edition: "fr", title: "Français", license: "CC-BY-SA-4.0" },
  { id: "fr-extra", edition: "en", title: "English Wiktionary", license: "CC-BY-SA-4.0" },
];
const records = [
  { word: "cœur", pronunciation: "/kœʁ/", sourceIds: ["fr-main", "fr-extra"], related: ["courage"],
    senses: [
      { partOfSpeech: "noun", definition: "(figuré) Le centre.", sourceIds: ["fr-main"],
        examples: ["Un sens précis, avec ses accents."] },
      { partOfSpeech: "adv", definition: "Une autre acception.", sourceIds: ["fr-extra"] },
    ] },
  { word: "vite", pronunciation: "/vit/", sourceIds: ["fr-main"], related: [],
    senses: [{ partOfSpeech: "adv", definition: "Rapidement.", sourceIds: ["fr-main"] }] },
];
const fixture = {
  entries: records.length, metadata: { language: "fr", sources }, daily: [0],
  entry: (ordinal) => ({ ...records[ordinal], key: `${records[ordinal].word}\x1f${records[ordinal].word}` }),
};

function rewriteIndex(bytes, change) {
  const length = bytes.readUInt32LE(8);
  const index = JSON.parse(bytes.subarray(12, 12 + length));
  change(index);
  const text = Buffer.from(JSON.stringify(index));
  const header = Buffer.alloc(12);
  header.write("MFAP0001");
  header.writeUInt32LE(text.length, 8);
  return Buffer.concat([header, text, bytes.subarray(12 + length)]);
}

test("Apple frames preserve complete meanings, source identities, examples and lookup-only records", () => {
  const ranks = new Map([["coeur", 2001], ["vite", 4]]);
  const bytes = buildApplePack(fixture, ranks);
  assert.deepEqual(buildApplePack(fixture, ranks), bytes, "generation must be deterministic");
  const pack = new AppleLexicon(bytes);
  assert.deepEqual(pack.dailyOrdinals, [0]);
  assert.equal(pack.rows[0].level, "INTERMEDIATE");
  assert.equal(pack.rows[1].level, "BEGINNER");
  assert.equal(pack.rows[1].daily, false);
  assert.deepEqual(pack.entry(0), {
    id: "fr:cœur", language: "fr", word: "cœur", pronunciation: "/kœʁ/",
    senses: records[0].senses.map((sense) => ({ ...sense, examples: sense.examples ?? [] })),
    related: ["courage"], sourceEdition: "fr", sources, pronunciationGuide: "",
  });
  assert.deepEqual(pack.entry(1).senses[0].examples, []);
  assert.equal(pack.entry(0).senses.length, 2, "discovery filtering must not remove full-card meanings");
});

test("reader rejects malformed headers, offsets, UTF-8, truncated and damaged deflate frames", () => {
  const bytes = buildApplePack(fixture, new Map());
  for (const cut of [0, 7, 11, bytes.length - 1]) {
    assert.throws(() => new AppleLexicon(bytes.subarray(0, cut)), /header|truncated|length|offset/i);
  }
  const badUTF8 = Buffer.from(bytes);
  badUTF8[12] = 0xff;
  assert.throws(() => new AppleLexicon(badUTF8), /UTF|index/i);
  for (const change of [
    (index) => { index.rows[0].offset = -1; },
    (index) => { index.rows[1].offset++; },
    (index) => { index.rows[0].decoded = 0xffffffff; },
    (index) => { index.rows[0].daily = false; },
    (index) => { index.dailyOrdinals = [0, 0]; },
    (index) => { index.rows[0].level = "UNKNOWN"; },
    (index) => { index.rows[1].word = index.rows[0].word; },
  ]) assert.throws(() => new AppleLexicon(rewriteIndex(bytes, change)), /invalid|mismatch/i);
  const broken = Buffer.from(bytes);
  broken[broken.length - 1] ^= 0xff;
  assert.throws(() => new AppleLexicon(broken).entry(1), /deflate|corrupt|check/i);
  const wrongWord = rewriteIndex(bytes, (index) => { index.rows[0].word = "autre"; });
  assert.throws(() => new AppleLexicon(wrongWord).entry(0), /identity|mismatch/i);
  assert.throws(() => new AppleLexicon(rewriteIndex(bytes, (index) => { index.rows[0].decoded++; })).entry(0),
    /length|deflate/i);
});

test("reader rejects invalid entry fields and trailing compressed bytes rather than dropping content", () => {
  const base = buildApplePack(fixture, new Map());
  const indexLength = base.readUInt32LE(8);
  const index = JSON.parse(base.subarray(12, 12 + indexLength));
  const original = new AppleLexicon(base).entry(0);
  for (const entry of [
    { ...original, id: "fr:wrong" },
    { ...original, sources: [] },
    { ...original, senses: [{ ...original.senses[0], sourceIds: ["unknown"] }] },
    { ...original, senses: [{ ...original.senses[0], examples: [5] }] },
  ]) {
    const text = Buffer.from(JSON.stringify(entry));
    const frame = deflateSync(text);
    const metadata = { ...index, rows: [{ ...index.rows[0], offset: 0, bytes: frame.length, decoded: text.length }],
      dailyOrdinals: [0] };
    const metadataBytes = Buffer.from(JSON.stringify(metadata));
    const header = Buffer.from(base.subarray(0, 12));
    header.writeUInt32LE(metadataBytes.length, 8);
    assert.throws(() => new AppleLexicon(Buffer.concat([header, metadataBytes, frame])).entry(0),
      /invalid|identity|mismatch/i);
  }
  const trailing = rewriteIndex(Buffer.concat([base, Buffer.from([0])]), (metadata) => { metadata.rows[1].bytes++; });
  assert.throws(() => new AppleLexicon(trailing).entry(1), /trailing|deflate/i);
});

test("all pinned starter records and daily ordinals convert exactly, with Kotlin frequency bands", async () => {
  const pinned = await loadPinnedSources(root);
  let count = 0;
  for (const language of pinned.languages) {
    const pack = new AppleLexicon(buildApplePack(language.packed, language.ranks));
    assert.deepEqual(pack.sources, language.packed.metadata.sources);
    assert.deepEqual(pack.dailyOrdinals, language.packed.daily);
    for (let i = 0; i < pack.rows.length; i++) {
      const source = language.packed.entry(i);
      const actual = pack.entry(i);
      const expectedSources = language.packed.metadata.sources.filter((item) => source.sourceIds.includes(item.id));
      assert.deepEqual(actual, {
        id: `${language.language}:${source.word}`, language: language.language,
        word: source.word, pronunciation: source.pronunciation,
        senses: source.senses.map((sense) => ({ ...sense, examples: sense.examples ?? [] })),
        related: source.related, sourceEdition: expectedSources[0].edition,
        sources: expectedSources, pronunciationGuide: "",
      });
      assert.equal(pack.rows[i].daily, language.packed.daily.includes(i));
      const rank = language.ranks.get(source.word.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase()
        .replaceAll("œ", "oe").replaceAll("æ", "ae").replaceAll("ß", "ss").replaceAll("’", "'")
        .trim().replace(/\s+/g, " ")) ?? Infinity;
      assert.equal(pack.rows[i].level, rank <= 2000 ? "BEGINNER" : rank <= 10000 ? "INTERMEDIATE" : "ADVANCED");
    }
    count += pack.rows.length;
  }
  assert.equal(count, 8235);
});

test("prepare and verify consume only pinned inputs and validate generated manifests", async () => {
  await prepareAppleContent(root);
  const manifest = await verifyAppleContent(root);
  const starter = JSON.parse(await readFile(join(root, "content", "starter", "manifest.json")));
  assert.equal(manifest.releaseVersion, starter.releaseVersion);
  assert.equal(manifest.attribution, starter.attribution);
  assert.deepEqual(manifest.discovery, starter.discovery);
  for (const pack of manifest.languages) {
    const source = starter.languages.find((item) => item.language === pack.language);
    assert.equal(pack.entries, source.entries);
    assert.equal(pack.dailyEntries, source.dailyEntries);
    const bytes = await readFile(join(root, "content", "apple", pack.file));
    assert.equal(pack.compressedBytes, bytes.length);
    const sourcePack = new PackedLexicon(await readFile(join(root, "content", "starter", "packs", source.file)));
    assert.equal(new AppleLexicon(bytes).entry(source.entries - 1).word, sourcePack.entry(source.entries - 1).word);
  }
  await assert.rejects(verifyAppleContent(root, { manifest: { ...manifest, builderSha256: "stale" } }), /stale/i);
  const modified = structuredClone(manifest);
  modified.languages[0].sha256 = "0".repeat(64);
  await assert.rejects(verifyAppleContent(root, { manifest: modified }), /hash|integrity/i);
});
