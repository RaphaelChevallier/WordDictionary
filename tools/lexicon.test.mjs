import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { gzipSync, zstdCompressSync } from "node:zlib";
import { test } from "node:test";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { once } from "node:events";
import { writePackedLexicon } from "./packed-lexicon.mjs";
import {
  anagramKey,
  buildPack,
  downloadSource,
  comparePack,
  normalizeSearch,
  validateChanges,
  verifyPack,
} from "./lexicon.mjs";

const source = {
  language: "fr",
  edition: "fr",
  url: "https://kaikki.org/dictionary/downloads/fr/fr-extract.jsonl.gz",
  minimumEntries: 2,
};

function record(word, gloss, extra = {}) {
  return {
    word,
    lang_code: "fr",
    pos: "noun",
    senses: [{ glosses: [gloss] }],
    ...extra,
  };
}

async function fixture(t, rows) {
  const directory = await mkdtemp(join(tmpdir(), "maxfluency-test-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const input = join(directory, "source.jsonl.gz");
  await writeFile(input, gzipSync(rows.map((row) => JSON.stringify(row)).join("\n")));
  return { directory, input, output: join(directory, "fr.sqlite") };
}

test("accent-insensitive lookup does not conflate distinct dictionary identities", () => {
  assert.equal(normalizeSearch("  C\u0152UR  "), "coeur");
  assert.equal(normalizeSearch("c\u00f4t\u00e9"), "cote");
  assert.equal(normalizeSearch("ma\u00f1ana"), "manana");
  assert.equal(anagramKey("\u00c9t\u00e9!"), "eet");
});

test("imports native-language definitions, merges senses, and excludes form-only entries", async (t) => {
  const data = await fixture(t, [
    record("cote", "Une mesure ou une valeur."),
    record("c\u00f4t\u00e9", "Une partie lat\u00e9rale."),
    record("c\u00f4t\u00e9", "Un aspect d'une chose.", {
      pos: "noun",
      sounds: [{ ipa: "/ko.te/" }],
      synonyms: [{ word: "aspect" }],
    }),
    record("c\u00f4t\u00e9s", "Pluriel de c\u00f4t\u00e9.", {
      senses: [{ glosses: ["Pluriel de c\u00f4t\u00e9."], form_of: [{ word: "c\u00f4t\u00e9" }] }],
    }),
    { ...record("cat", "A small animal."), lang_code: "en" },
  ]);
  const result = await buildPack({ ...data, source, release: "0.1.0" });
  assert.equal(result.entries, 2);
  const db = new DatabaseSync(data.output, { readOnly: true });
  try {
    const entries = db.prepare("SELECT lookup_key, senses, pronunciation, related FROM entries ORDER BY lookup_key").all();
    assert.notEqual(entries[0].lookup_key, entries[1].lookup_key);
    const word = db.prepare("SELECT * FROM entries WHERE lookup_key = ?").get("cote\x1fc\u00f4t\u00e9");
    assert.equal(JSON.parse(word.senses).length, 2);
    assert.equal(word.pronunciation, "/ko.te/");
    assert.deepEqual(JSON.parse(word.related), ["aspect"]);
    assert.deepEqual(db.prepare("SELECT lookup_key FROM entries WHERE lookup_key >= ? AND lookup_key < ? ORDER BY lookup_key")
      .all("cote", "cote\uffff").map((row) => row.lookup_key.split("\x1f")[1]), ["cote", "c\u00f4t\u00e9"]);
  } finally {
    db.close();
  }
});

test("bad upstream data fails rather than publishing a partial or empty dictionary", async (t) => {
  const data = await fixture(t, [record("mot", "Un \u00e9l\u00e9ment du langage.")]);
  await assert.rejects(buildPack({ ...data, source, release: "0.1.0" }), /minimum/i);
  await writeFile(data.input, gzipSync('{"word":'));
  await assert.rejects(buildPack({
    ...data, output: join(data.directory, "malformed.sqlite"), source, release: "0.1.0",
  }), /JSON|line/i);
});

test("JSONL framing accepts Unicode paragraph separators inside valid JSON strings", async (t) => {
  const data = await fixture(t, [
    { ...record("Zahnarzt", "Ein Text.\u2029Noch ein Text."), lang_code: "de" },
    record("arbre", "Un v\u00e9g\u00e9tal.\u2029Il poss\u00e8de un tronc."),
    record("livre", "Un ensemble de pages reli\u00e9es."),
  ]);
  const result = await buildPack({ ...data, source, release: "0.1.0" });
  assert.equal(result.entries, 2);
  const db = new DatabaseSync(data.output, { readOnly: true });
  try {
    const word = db.prepare("SELECT senses FROM entries WHERE lookup_key = ?").get("arbre\x1farbre");
    assert.equal(JSON.parse(word.senses)[0].definition, "Un v\u00e9g\u00e9tal. Il poss\u00e8de un tronc.");
  } finally {
    db.close();
  }
});

test("a truncated compressed source rejects the build instead of accepting a partial stream", async (t) => {
  const data = await fixture(t, [
    record("arbre", "Un v\u00e9g\u00e9tal poss\u00e9dant un tronc."),
    record("livre", "Un ensemble de pages reli\u00e9es."),
  ]);
  const bytes = await readFile(data.input);
  await writeFile(data.input, bytes.subarray(0, bytes.length - 8));
  await assert.rejects(buildPack({ ...data, source, release: "0.1.0" }), /end|gzip|stream|check/i);
});
test("real SQLite reconciliation reports additions, content changes, and source removals", async (t) => {
  const previous = await fixture(t, [record("ancien", "Une ancienne entrée."),
    record("stable", "Une entrée conservée."), record("modifié", "La première définition.")]);
  const metadata = await buildPack({ ...previous, source, release: "0.1.0" });
  const oldPack = join(previous.directory, "previous.sqlite.zst");
  await writeFile(oldPack, zstdCompressSync(await readFile(previous.output)));
  const current = await fixture(t, [record("nouveau", "Une nouvelle entrée."),
    record("stable", "Une entrée conservée."), record("modifié", "Une définition corrigée.")]);
  await buildPack({ ...current, source, release: "0.2.0" });
  assert.deepEqual(await comparePack(oldPack, metadata, current.output, current.directory),
    { added: 1, removed: 1, changed: 1, unchanged: 1 });
  assert.throws(() => validateChanges({ added: 0, removed: 11, changed: 0, unchanged: 89 }, 0.1), /removal/i);
  assert.doesNotThrow(() => validateChanges({ added: 0, removed: 10, changed: 0, unchanged: 90 }, 0.1));
});

test("compressed pack verification detects corruption and checks exact content version", async (t) => {
  const data = await fixture(t, [
    record("arbre", "Un v\u00e9g\u00e9tal poss\u00e9dant un tronc."),
    record("livre", "Un ensemble de pages reli\u00e9es."),
  ]);
  const metadata = await buildPack({ ...data, source, release: "0.1.0" });
  const compressed = join(data.directory, "fr.sqlite.zst");
  await writeFile(compressed, zstdCompressSync(await readFile(data.output)));
  await verifyPack(compressed, metadata, "0.1.0");
  await assert.rejects(verifyPack(compressed, metadata, "0.2.0"), /version|release/i);
  const bytes = await readFile(compressed);
  bytes[bytes.length - 4] ^= 0xff;
  await writeFile(compressed, bytes);
  await assert.rejects(verifyPack(compressed, metadata, "0.1.0"), /hash|integrity|length|check|corrupt|frame/i);
});

test("multiple sources merge one headword, deduplicate equivalent senses, and retain provenance", async (t) => {
  const first = await fixture(t, [record("café", "Une boisson préparée avec des grains."),
    record("cote", "Une valeur."), record("côté", "Une partie latérale.")]);
  const second = await fixture(t, [record("cafe\u0301", "Une  boisson préparée avec des grains."),
    record("café", "Un établissement où l’on sert des boissons."), record("livre", "Des pages reliées.")]);
  const sources = [{ ...source, id: "fr-main", priority: 10 }, { ...source, id: "fr-extra", priority: 20 }];
  const result = await buildPack({
    output: first.output, release: "0.1.0",
    inputs: [{ input: second.input, source: sources[1] }, { input: first.input, source: sources[0] }],
  });
  assert.equal(result.entries, 4);
  const db = new DatabaseSync(first.output, { readOnly: true });
  try {
    const word = db.prepare("SELECT senses,sources FROM entries WHERE lookup_key = ?").get("cafe\x1fcafé");
    const senses = JSON.parse(word.senses);
    assert.equal(senses.length, 2);
    assert.deepEqual(senses[0].sourceIds, ["fr-main", "fr-extra"]);
    assert.deepEqual(JSON.parse(word.sources), ["fr-main", "fr-extra"]);
    assert.equal(db.prepare("SELECT count(*) AS n FROM entries WHERE lookup_key >= ? AND lookup_key < ?")
      .get("cote", "cote\uffff").n, 2);
  } finally { db.close(); }
});

test("source removal only deletes a word after it leaves the successful source union", async (t) => {
  const a = await fixture(t, [record("commun", "Un mot partagé."), record("ancien", "Un ancien mot.")]);
  const b = await fixture(t, [record("commun", "Un mot partagé."), record("stable", "Un mot stable.")]);
  const primary = { ...source, id: "main", minimumEntries: 1 };
  const extra = { ...source, id: "extra", minimumEntries: 1, priority: 20 };
  await buildPack({ output: a.output, release: "0.1.0",
    inputs: [{ input: a.input, source: primary }, { input: b.input, source: extra }] });
  const c = await fixture(t, [record("nouveau", "Un nouveau mot.")]);
  const next = join(c.directory, "next.sqlite");
  await buildPack({ output: next, release: "0.2.0",
    inputs: [{ input: c.input, source: primary }, { input: b.input, source: extra }] });
  const db = new DatabaseSync(next, { readOnly: true });
  try {
    assert.ok(db.prepare("SELECT 1 FROM entries WHERE lookup_key = ?").get("commun\x1fcommun"));
    assert.equal(db.prepare("SELECT 1 FROM entries WHERE lookup_key = ?").get("ancien\x1fancien"), undefined);
  } finally { db.close(); }
  await writeFile(c.input, gzipSync('{"word":'));
  await assert.rejects(buildPack({ output: join(c.directory, "failed.sqlite"), release: "0.2.0",
    inputs: [{ input: a.input, source: primary }, { input: c.input, source: extra }] }), /JSON|line/i);
});

test("unchanged source headers reuse a verified cache without a conditional multi-GB GET", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "mf-download-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const bytes = gzipSync("cached source");
  let downloads = 0;
  const server = createServer((request, response) => {
    if (request.method === "HEAD") {
      response.writeHead(200, { ETag: '"stable"', "Content-Length": bytes.length });
      response.end();
    } else {
      downloads++;
      response.writeHead(500);
      response.end();
    }
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const url = `http://127.0.0.1:${server.address().port}/source.gz`;
  await writeFile(join(directory, "test.jsonl.gz"), bytes);
  await writeFile(join(directory, "test.source.json"), JSON.stringify({
    url, etag: '"stable"', bytes: bytes.length, sha256: createHash("sha256").update(bytes).digest("hex"),
  }));
  await downloadSource({ id: "test", language: "en", url }, directory);
  assert.equal(downloads, 0);
  await writeFile(join(directory, "test.jsonl.gz"), Buffer.from("corrupt"));
  await assert.rejects(downloadSource({ id: "test", language: "en", url }, directory), /hash mismatch/i);
});

test("unchanged filtered daily indexes do not appear changed against the unfiltered build corpus", async (t) => {
  const data = await fixture(t, [
    record("arbre", "Un végétal."),
    record("livre", "Des pages reliées."),
    record("vite", "Avec rapidité.", { pos: "adv" }),
  ]);
  await buildPack({ ...data, source, release: "0.1.0" });
  const db = new DatabaseSync(data.output, { readOnly: true });
  const path = join(data.directory, "previous.mflx");
  let metadata;
  try {
    metadata = await writePackedLexicon(db, path, {
      language: "fr", sources: [{ id: "fr-wiktionary", edition: "fr", title: "Wiktionary", license: "CC-BY-SA-4.0" }],
    }, { discovery: { excludedPartsOfSpeech: ["adv"], excludedWords: { fr: [] } } });
  } finally { db.close(); }
  assert.equal(metadata.dailyEntries, 2);
  assert.deepEqual(await comparePack(path, { ...metadata, language: "fr" }, data.output, data.directory, path),
    { added: 0, removed: 0, changed: 0, unchanged: 3 });
});
