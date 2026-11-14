import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { test } from "node:test";
import { PackedLexicon, writePackedLexicon } from "./packed-lexicon.mjs";

async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), "mf-pack-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const db = new DatabaseSync(join(directory, "build.sqlite"));
  db.exec(`
    CREATE TABLE entries(lookup_key TEXT PRIMARY KEY,anagram_key TEXT,pronunciation TEXT,senses TEXT,related TEXT,sources TEXT);
    CREATE TABLE daily_words(position INTEGER PRIMARY KEY,entry_key TEXT);
  `);
  const insert = db.prepare("INSERT INTO entries VALUES (?,?,?,?,?,?)");
  const expected = [];
  for (let i = 0; i < 1100; i++) {
    const word = `mot${String(i).padStart(4, "0")}`;
    const key = `${word}\x1f${word}`;
    const row = {
      key, word, pronunciation: i % 2 ? "/mo/" : "",
      senses: [{ partOfSpeech: "noun", definition: `Définition ${i} avec « accents », apostrophe ' et \n une ligne.`, sourceIds: ["fr-main", "fr-extra"] }],
      related: ["cœur", "côté"], sourceIds: ["fr-main", "fr-extra"],
    };
    expected.push(row);
    insert.run(key, "mot", row.pronunciation, JSON.stringify(row.senses), JSON.stringify(row.related), JSON.stringify(row.sourceIds));
    if (i % 3 === 0) db.prepare("INSERT INTO daily_words VALUES (?,?)").run(i / 3, key);
  }
  const output = join(directory, "fr.mflx");
  const sources = [
    { id: "fr-main", edition: "fr", title: "Main", license: "CC-BY-SA-4.0" },
    { id: "fr-extra", edition: "fr", title: "Extra", license: "CC-BY-SA-4.0" },
  ];
  return { db, output, directory, expected, sources };
}

test("compressed blocks round-trip every field across word pages and payload blocks", async (t) => {
  const data = await fixture(t);
  try {
    const metadata = await writePackedLexicon(data.db, data.output, { language: "fr", sources: data.sources }, { blockEntries: 512 });
    const pack = new PackedLexicon(await readFile(data.output));
    assert.equal(metadata.entries, data.expected.length);
    assert.ok(metadata.compressedBytes < metadata.unpackedBytes);
    assert.equal(pack.metadata.language, "fr");
    for (let i = 0; i < data.expected.length; i++) assert.deepEqual(pack.entry(i), data.expected[i]);
    assert.deepEqual([...pack.daily], data.expected.map((_, i) => i).filter((i) => i % 3 === 0));
    assert.equal(pack.word(128), data.expected[128].key);
    assert.equal(pack.word(512), data.expected[512].key);
    assert.equal(pack.word(1099), data.expected[1099].key);
  } finally { data.db.close(); }
});

test("invalid header, truncated data, and corrupt compressed frames are rejected", async (t) => {
  const data = await fixture(t);
  try {
    await writePackedLexicon(data.db, data.output, { language: "fr", sources: data.sources });
    const bytes = await readFile(data.output);
    assert.throws(() => new PackedLexicon(bytes.subarray(0, 40)), /header|truncated/i);
    const header = Buffer.from(bytes);
    header.writeUInt32LE(0xffffffff, 28);
    assert.throws(() => new PackedLexicon(header), /index|size|limit/i);
    assert.throws(() => new PackedLexicon(bytes.subarray(0, bytes.length - 1)), /length|truncated/i);
    const payload = Buffer.from(bytes);
    payload[payload.length - 1] ^= 0xff;
    const reader = new PackedLexicon(payload);
    assert.throws(() => reader.entry(1099), /corrupt|check|frame/i);
    await writeFile(join(data.directory, "corrupt.mflx"), payload);
  } finally { data.db.close(); }
});

test("daily filtering excludes grammar and basic words without removing lookup meanings", async (t) => {
  const data = await fixture(t);
  try {
    const update = data.db.prepare("UPDATE entries SET senses = ? WHERE lookup_key = ?");
    const sense = (partOfSpeech) => ({ partOfSpeech, definition: "A retained definition.", sourceIds: ["fr-main"] });
    update.run(JSON.stringify([sense("adv")]), "mot0000\x1fmot0000");
    update.run(JSON.stringify([sense("adv"), sense("noun")]), "mot0003\x1fmot0003");
    update.run(JSON.stringify([sense("article")]), "mot0006\x1fmot0006");
    update.run(JSON.stringify([sense("det")]), "mot0009\x1fmot0009");
    const policy = { excludedPartsOfSpeech: ["adv", "article", "det"], excludedWords: { fr: ["mot0012"] } };
    const metadata = await writePackedLexicon(data.db, data.output, { language: "fr", sources: data.sources },
      { discovery: policy });
    const pack = new PackedLexicon(await readFile(data.output));
    assert.equal(pack.entries, data.expected.length);
    assert.equal(metadata.dailyEntries, 363);
    for (const ordinal of [0, 6, 9, 12]) assert.ok(!pack.daily.includes(ordinal));
    assert.ok(pack.daily.includes(3));
    assert.equal(pack.entry(0).senses[0].partOfSpeech, "adv");
    assert.deepEqual(pack.entry(3).senses.map((item) => item.partOfSpeech), ["adv", "noun"]);
    assert.equal(pack.entry(6).senses[0].partOfSpeech, "article");
    assert.equal(pack.entry(12).word, "mot0012");
  } finally { data.db.close(); }
});
