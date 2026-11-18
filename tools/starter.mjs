import { createHash } from "node:crypto";
import { access, mkdir, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";
import { PackedLexicon, writePackedLexicon } from "./packed-lexicon.mjs";
import { anagramKey, normalizeSearch } from "./lexicon.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const target = join(root, "content", "starter");
const version = (await readFile(join(root, "version.properties"), "utf8")).match(/^versionName=(.+)$/m)[1].trim();
const verifyOnly = process.argv[2] === "verify";
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const builderSha256 = hash(Buffer.concat([await readFile(fileURLToPath(import.meta.url)),
  await readFile(join(root, "tools", "packed-lexicon.mjs"))]));
const supplementPath = join(root, "content", "supplements", "es-coverage.json");
const supplement = JSON.parse(await readFile(supplementPath, "utf8"));
if (supplement.schemaVersion !== 1 || supplement.language !== "es" || !Array.isArray(supplement.entries)) {
  throw new Error("Invalid Spanish coverage supplement");
}
const examplesPath = join(root, "content", "supplements", "usage-examples.json");
const usage = JSON.parse(await readFile(examplesPath, "utf8"));
const contentBuilderSha256 = hash(Buffer.concat(await Promise.all(
  ["usage-examples.mjs", "lexicon.mjs", "content-quality.mjs"].map((file) => readFile(join(root, "tools", file))))));
if (usage.schemaVersion !== 2 || usage.builderSha256 !== contentBuilderSha256 || !Array.isArray(usage.languages)) {
  throw new Error("Content polish needs regeneration: npm run examples:generate");
}
const supplementHashes = { es: hash(await readFile(supplementPath)), examples: hash(await readFile(examplesPath)) };
async function exists(path) {
  try { await access(path); return true; }
  catch (error) { if (error.code !== "ENOENT") throw error; return false; }
}
if (await exists(join(target, "manifest.json"))) {
  const manifest = JSON.parse(await readFile(join(target, "manifest.json"), "utf8"));
  if (manifest.releaseVersion === version && manifest.builderSha256 === builderSha256 &&
      manifest.supplementHashes?.es === supplementHashes.es &&
      manifest.supplementHashes?.examples === supplementHashes.examples) {
    for (const pack of manifest.languages) {
      if (hash(await readFile(join(target, "packs", pack.file))) !== pack.sha256) throw new Error("Starter pack hash mismatch");
      if (hash(await readFile(join(target, "ranks", `${pack.language}.txt`))) !== manifest.rankHashes?.[pack.language]) {
        throw new Error("Vocabulary rank asset hash mismatch");
      }
    }
    console.log(`Verified pinned ${version} starter dictionaries.`);
    process.exit(0);
  }
}
if (verifyOnly) throw new Error("The starter bank needs preparation; run npm run content:prepare");

await mkdir(join(target, "packs"), { recursive: true });
await mkdir(join(target, "ranks"), { recursive: true });
const source = JSON.parse(await readFile(join(root, "content", "manifest.json"), "utf8"));
const languages = [];
const rankHashes = {};
for (const original of source.languages) {
  const language = original.language;
  const frequencyPath = join(root, ".cache", "frequency", `${language}.txt`);
  if (!await exists(frequencyPath)) {
    await mkdir(join(root, ".cache", "frequency"), { recursive: true });
    const url = `https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/${language}/${language}_50k.txt`;
    const response = await fetch(url, { signal: AbortSignal.timeout(30000) });
    if (!response.ok) throw new Error(`Frequency source failed: ${response.status}`);
    await writeFile(frequencyPath, await response.text());
  }
  const frequency = (await readFile(frequencyPath, "utf8")).trim().split(/\r?\n/).map((line) => line.split(" ")[0].normalize("NFC"));
  if (frequency.length < 40000) throw new Error("Frequency source minimum coverage failed");
  const rankText = frequency.join("\n");
  await writeFile(join(target, "ranks", `${language}.txt`), rankText);
  if (await exists(join(target, "ranks", `${language}.txt.gz`))) await rm(join(target, "ranks", `${language}.txt.gz`));
  rankHashes[language] = hash(rankText);
  const full = new PackedLexicon(await readFile(join(root, "content", "packs", original.file)));
  const wanted = new Map(frequency.map((word, i) => [word, i + 1]));
  const eligible = new Set(full.daily);
  const lookupWords = new Set([...source.discovery.excludedWords[language],
    ...({ en: ["quickly", "cat", "dog"], fr: ["rapidement"], es: ["r\u00e1pidamente"] }[language])]);
  const lookupOnly = [];
  const groups = [[], [], []];
  for (let i = 0; i < full.entries; i++) {
    const word = full.word(i).split("\x1f")[1];
    if (lookupWords.has(word)) lookupOnly.push([i, wanted.get(word) ?? 50001]);
    const rank = wanted.get(word);
    if (!rank || !eligible.has(i)) continue;
    groups[rank <= 2000 ? 0 : rank <= 10000 ? 1 : 2].push([i, rank]);
  }
  const selected = [...new Map([...groups.flatMap((group) => group.sort((a, b) => a[1] - b[1]).slice(0, 900)),
    ...lookupOnly].map((pair) => [pair[0], pair])).values()];
  if (groups.some((group) => group.length < 30)) throw new Error("Insufficient words for a starter vocabulary band");
  const db = new DatabaseSync(":memory:");
  try {
    db.exec(`CREATE TABLE entries(lookup_key TEXT PRIMARY KEY,anagram_key TEXT,pronunciation TEXT,senses TEXT,related TEXT,sources TEXT);
      CREATE TABLE daily_words(position INTEGER PRIMARY KEY,entry_key TEXT);`);
    const insert = db.prepare("INSERT INTO entries VALUES (?,?,?,?,?,?)");
    const daily = db.prepare("INSERT INTO daily_words VALUES (?,?)");
    selected.forEach(([ordinal], index) => {
      const entry = full.entry(ordinal);
      insert.run(entry.key, anagramKey(entry.word), entry.pronunciation, JSON.stringify(entry.senses),
        JSON.stringify(entry.related), JSON.stringify(entry.sourceIds));
      if (eligible.has(ordinal)) daily.run(index, entry.key);
    });
    const exampleEntries = usage.languages.find((item) => item.language === language)?.entries;
    if (!exampleEntries) throw new Error(`Missing ${language} usage example supplement`);
    const additions = [...(language === "es" ? supplement.entries : []), ...exampleEntries];
    if (additions.length) {
      const find = db.prepare("SELECT * FROM entries WHERE lookup_key=?");
      const update = db.prepare("UPDATE entries SET senses=?,related=?,sources=? WHERE lookup_key=?");
      for (const entry of additions) {
        const key = normalizeSearch(entry.word) + "\x1f" + entry.word.normalize("NFC");
        const previous = find.get(key);
        if (previous) {
          const senses = entry.replaceSenses ? entry.senses : JSON.parse(previous.senses);
          for (const sense of entry.replaceSenses ? [] : entry.senses) {
            const existing = senses.find((old) => old.partOfSpeech === sense.partOfSpeech && old.definition === sense.definition);
            if (existing) {
              const examples = [...new Set([...(existing.examples ?? []), ...(sense.examples ?? [])])].slice(0, 3);
              if (examples.length) existing.examples = examples;
              existing.sourceIds = [...new Set([...(existing.sourceIds ?? []), ...(sense.sourceIds ?? [])])];
            } else senses.push(sense);
          }
          if (senses.length > 64) throw new Error("Merged entry exceeds the supported sense count");
          update.run(JSON.stringify(senses), JSON.stringify([...new Set([...JSON.parse(previous.related), ...entry.related])]),
            JSON.stringify([...new Set([...JSON.parse(previous.sources), ...entry.sourceIds])]), key);
        } else {
          insert.run(key, anagramKey(entry.word), entry.pronunciation, JSON.stringify(entry.senses),
            JSON.stringify(entry.related), JSON.stringify(entry.sourceIds));
        }
      }
    }
    const exclusions = usage.languages.find((item) => item.language === language).excludedHeadwords ?? [];
    for (const { word } of exclusions) {
      const key = normalizeSearch(word) + "\x1f" + word.normalize("NFC");
      db.prepare("DELETE FROM daily_words WHERE entry_key=?").run(key);
      if (db.prepare("DELETE FROM entries WHERE lookup_key=?").run(key).changes !== 1) {
        throw new Error(`Undefined source placeholder ${word} did not match the starter bank`);
      }
    }
    const file = join(target, `${language}.partial.mflx`);
    if (await exists(file)) await rm(file);
    const metadata = await writePackedLexicon(db, file, full.metadata, { discovery: source.discovery, blockEntries: 512 });
    const name = `${language}-${metadata.sha256.slice(0, 16)}.mflx`;
    await rename(file, join(target, "packs", name));
    languages.push({ ...metadata, language, edition: language, releaseVersion: version,
      compressedSha256: metadata.sha256, file: name });
    console.log(`${language}: ${metadata.entries} starter words, ${(metadata.compressedBytes / 1e6).toFixed(2)} MB`);
  } finally { db.close(); }
}
const active = new Set(languages.map((pack) => pack.file));
for (const name of await readdir(join(target, "packs"))) {
  if (/^[a-z]{2,3}-[a-f0-9]{16}\.mflx$/.test(name) && !active.has(name)) await rm(join(target, "packs", name));
}
await writeFile(join(target, "manifest.json"), JSON.stringify({
  schemaVersion: 3, compression: "zstd-blocks", releaseVersion: version, builderSha256, builtAt: new Date().toISOString(),
  license: "CC-BY-SA-4.0", attribution: `${source.attribution} Vocabulary frequency bands: FrequencyWords / OpenSubtitles.`,
  modifications: "Small native-language starter bank selected by frequency bands, audited Spanish lookup additions and pinned source meanings/examples. Detached footnotes and broken wiki markup removed; quality warnings and source usage qualifiers retained. Attributed/embedded quotations omitted; examples belong to their precise meaning. Example-only headword additions are lookup-only; daily discovery is unchanged. Full dictionaries are optional public downloads.",
  languages, rankHashes, supplementHashes, discovery: source.discovery,
}, null, 2) + "\n");
