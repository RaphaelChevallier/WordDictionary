import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { jsonLines, sourceSenses, normalizeSearch } from "./lexicon.mjs";
import { PackedLexicon } from "./packed-lexicon.mjs";
import { cleanDefinition, definitionIdentity, selectUsageExamples } from "./content-quality.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const senseKey = (sense) => `${sense.partOfSpeech}\0${sense.definition.normalize("NFC")}`;
const extraLookups = { fr: ["abjurer"] };

export function attachSourceExamples(entry, record, sourceId) {
  for (const incoming of sourceSenses(record)) {
    const sense = entry.senses.find((item) => senseKey(item) === senseKey(incoming) && item.sourceIds.includes(sourceId));
    if (!sense) continue;
    const examples = selectUsageExamples([...(sense.examples ?? []), ...(incoming.examples ?? [])]);
    if (examples.length) sense.examples = examples;
  }
}

export function mergeSourceContent(entry, record, sourceId) {
  let limited = 0;
  for (const incoming of sourceSenses(record)) {
    let sense = entry.senses.find((item) => senseKey(item) === senseKey(incoming));
    if (!sense) {
      const unlabeled = entry.senses.filter((item) => item.partOfSpeech === incoming.partOfSpeech &&
        item.definition === definitionIdentity(item.definition) &&
        definitionIdentity(item.definition) === definitionIdentity(incoming.definition));
      if (unlabeled.length === 1) {
        sense = unlabeled[0];
        sense.definition = incoming.definition;
      }
    }
    if (!sense) {
      if (entry.senses.length === 64) { limited++; continue; }
      sense = { partOfSpeech: incoming.partOfSpeech, definition: incoming.definition, sourceIds: [] };
      entry.senses.push(sense);
    }
    if (!sense.sourceIds.includes(sourceId)) sense.sourceIds.push(sourceId);
    if (!entry.sourceIds.includes(sourceId)) entry.sourceIds.push(sourceId);
    const examples = selectUsageExamples([...(sense.examples ?? []), ...(incoming.examples ?? [])]);
    if (examples.length) sense.examples = examples;
  }
  return limited;
}

function findEntry(dictionary, word) {
  const normalized = word.normalize("NFC");
  const key = Buffer.from(`${normalizeSearch(normalized)}\x1f${normalized}`);
  let low = 0, high = dictionary.entries;
  while (low < high) {
    const mid = (low + high) >>> 1;
    if (Buffer.compare(Buffer.from(dictionary.word(mid)), key) < 0) low = mid + 1;
    else high = mid;
  }
  return low < dictionary.entries && Buffer.from(dictionary.word(low)).equals(key) ? dictionary.entry(low) : null;
}

async function generate() {
  const manifest = JSON.parse(await readFile(join(root, "content", "starter", "manifest.json"), "utf8"));
  const fullManifest = JSON.parse(await readFile(join(root, "content", "manifest.json"), "utf8"));
  const configuration = JSON.parse(await readFile(join(root, "content", "sources.json"), "utf8"));
  const previous = JSON.parse(await readFile(join(root, "content", "supplements", "usage-examples.json"), "utf8"));
  const builderSha256 = hash(Buffer.concat(await Promise.all(
    ["usage-examples.mjs", "lexicon.mjs", "content-quality.mjs"].map((file) => readFile(join(root, "tools", file))))));
  const languages = [];
  for (const pack of manifest.languages) {
    const dictionary = new PackedLexicon(await readFile(join(root, "content", "starter", "packs", pack.file)));
    const fullPack = fullManifest.languages.find((item) => item.language === pack.language);
    const full = new PackedLexicon(await readFile(join(root, "content", "packs", fullPack.file)));
    const coverage = pack.language === "es"
      ? JSON.parse(await readFile(join(root, "content", "supplements", "es-coverage.json"), "utf8")).entries : [];
    const baseline = new Map(coverage.map((entry) => [entry.word, entry]));
    const scope = new Set([...Array.from({ length: dictionary.entries }, (_, i) => dictionary.word(i).split("\x1f")[1]),
      ...(previous.languages.find((item) => item.language === pack.language)?.excludedHeadwords ?? []).map(({ word }) => word)]);
    const entries = new Map([...scope].map((word) => {
      const entry = findEntry(full, word) ?? baseline.get(word);
      if (!entry) throw new Error(`Missing reproducible source entry: ${word}`);
      return [entry.word, { ...entry, senses: entry.senses.flatMap(({ examples, ...sense }) => {
        const definition = cleanDefinition(sense.definition);
        return definition && !/^Terms? relating to .+\.$/u.test(definition) ? [{ ...sense, definition }] : [];
      }) }];
    }));
    for (const word of extraLookups[pack.language] ?? []) {
      const entry = findEntry(full, word);
      if (!entry) throw new Error(`Missing requested example word: ${word}`);
      entries.set(word, { ...entry, senses: entry.senses.flatMap(({ examples, ...sense }) => {
        const definition = cleanDefinition(sense.definition);
        return definition ? [{ ...sense, definition }] : [];
      }) });
    }
    const words = [...entries.keys()].map((word) => JSON.stringify(word).replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    const pattern = new RegExp(`"word"\\s*:\\s*(?:${words.join("|")})(?=\\s*[,}])`);
    const sources = [];
    let meaningsBeyondLimit = 0;
    for (const source of configuration.languages.find((item) => item.language === pack.language).sources) {
      const stem = join(root, ".cache", "lexicon", source.id);
      const metadata = JSON.parse(await readFile(`${stem}.source.json`, "utf8"));
      if (!/^[a-f0-9]{64}$/.test(metadata.sha256)) throw new Error("Missing source checksum");
      let count = 0;
      for await (const line of jsonLines(`${stem}.jsonl.gz`, metadata.sha256)) {
        if (!pattern.test(line)) continue;
        const record = JSON.parse(line);
        const entry = record.lang_code === pack.language ? entries.get(record.word?.normalize("NFC")) : null;
        if (entry) { meaningsBeyondLimit += mergeSourceContent(entry, record, source.id); count++; }
      }
      sources.push({ id: source.id, edition: source.edition, title: source.title, license: source.license,
        url: metadata.url, sha256: metadata.sha256 });
      console.log(`${source.id}: checked the pinned local archive; ${count} relevant source records`);
    }
    const excludedHeadwords = [...entries.values()].filter((entry) => !entry.senses.length).map((entry) => ({
      word: entry.word, reason: "The pinned native source contains no usable lexical definition after removing placeholders or unresolved markup.",
    }));
    excludedHeadwords.forEach(({ word }) => console.warn(`${pack.language}: excluded undefined source placeholder ${word}`));
    const selected = [...entries.values()].filter((entry) => entry.senses.length).map((entry) => {
        const lookupOnly = extraLookups[pack.language]?.includes(entry.word) ?? false;
        return { word: entry.word, pronunciation: entry.pronunciation, related: entry.related,
          sourceIds: entry.sourceIds, ...(lookupOnly ? { lookupOnly } : {}),
          replaceSenses: true, senses: entry.senses };
      }).sort((a, b) => Buffer.compare(Buffer.from(a.word), Buffer.from(b.word)));
    languages.push({ language: pack.language, sources, entries: selected, excludedHeadwords, meaningsBeyondLimit });
    console.log(`${pack.language}: ${selected.length} headwords with ${selected.flatMap((entry) =>
      entry.senses.flatMap((sense) => sense.examples ?? [])).length} source usage examples; ${meaningsBeyondLimit} further contributions beyond the 64-meaning bound`);
  }
  const data = { schemaVersion: 2, builderSha256, license: "CC-BY-SA-4.0",
    policy: "Source-grounded content polish for existing bundled headwords. Retains up to 64 meanings with source usage qualifiers; removes detached numerical footnotes and unreadable wiki markup while preserving quality warnings. Undefined source placeholders are explicitly listed, not turned into invented definitions. Unreferenced usage examples only; embedded URLs, dated citations, attributed quotations and raw templates excluded. Prefers full-sentence examples, at most three complete examples of at most 600 characters per meaning. Nested examples attach only to their leaf sense. No generated examples, new headword identities or runtime network requests. Explicit extra headwords are lookup-only.",
    languages };
  const bytes = `${JSON.stringify(data, null, 2)}\n`;
  await writeFile(join(root, "content", "supplements", "usage-examples.json"), bytes);
  console.log(`Pinned usage supplement: ${Buffer.byteLength(bytes)} bytes; SHA-256 ${hash(bytes)}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv[2] !== "generate") throw new Error("Use usage-examples.mjs generate to refresh from pinned local source archives");
  await generate();
}
