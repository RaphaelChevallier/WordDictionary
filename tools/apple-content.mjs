import { createHash, randomUUID } from "node:crypto";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { isDeepStrictEqual } from "node:util";
import { deflateSync, inflateSync } from "node:zlib";
import { PackedLexicon } from "./packed-lexicon.mjs";
import { normalizeSearch } from "./lexicon.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const languages = ["en", "fr", "es"];
const levels = ["BEGINNER", "INTERMEDIATE", "ADVANCED"];
const magic = Buffer.from("MFAP0001");
const maximumIndex = 64 * 1024 * 1024;
const maximumRecord = 16 * 1024 * 1024;
const utf8 = new TextDecoder("utf-8", { fatal: true });
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const integer = (value, min, max) => Number.isSafeInteger(value) && value >= min && value <= max;
const text = (value) => typeof value === "string" && value.trim().length > 0;
const sourceValid = (source) => source && ["id", "edition", "title", "license"].every((key) => text(source[key]))
  && /^[a-z][a-z-]{1,23}$/.test(source.edition);
const level = (ranks, word) => {
  const rank = ranks.get(normalizeSearch(word)) ?? Infinity;
  return rank <= 2000 ? levels[0] : rank <= 10000 ? levels[1] : levels[2];
};

function completeEntry(packed, ordinal) {
  const entry = packed.entry(ordinal);
  const sources = packed.metadata.sources.filter((source) => entry.sourceIds.includes(source.id));
  if (!sources.length) throw new Error("Invalid pinned entry sources");
  return {
    id: `${packed.metadata.language}:${entry.word}`, language: packed.metadata.language, word: entry.word,
    pronunciation: entry.pronunciation,
    senses: entry.senses.map((sense) => ({ ...sense, examples: sense.examples ?? [] })),
    related: entry.related, sourceEdition: sources[0].edition, sources, pronunciationGuide: "",
  };
}

function validateEntry(entry, row, language, sources) {
  if (!entry || entry.id !== `${language}:${row.word}` || entry.language !== language || entry.word !== row.word) {
    throw new Error("Apple dictionary record identity mismatch");
  }
  if (typeof entry.pronunciation !== "string" || typeof entry.pronunciationGuide !== "string"
      || !Array.isArray(entry.senses) || !integer(entry.senses.length, 1, 64)
      || !Array.isArray(entry.related) || entry.related.length > 64 || entry.related.some((word) => !text(word))
      || !Array.isArray(entry.sources) || !integer(entry.sources.length, 1, 16)
      || new Set(entry.sources.map((source) => source.id)).size !== entry.sources.length
      || entry.sources.some((source) => !sources.some((known) => isDeepStrictEqual(known, source)))
      || entry.sourceEdition !== entry.sources[0].edition) throw new Error("Invalid Apple dictionary record");
  const known = new Set(entry.sources.map((source) => source.id));
  for (const sense of entry.senses) {
    if (!sense || !text(sense.partOfSpeech) || !text(sense.definition)
        || !Array.isArray(sense.sourceIds) || !sense.sourceIds.length
        || sense.sourceIds.some((id) => !known.has(id))
        || !Array.isArray(sense.examples) || sense.examples.length > 3
        || sense.examples.some((example) => !text(example) || example.length > 600)) {
      throw new Error("Invalid Apple dictionary meaning or examples");
    }
  }
}

// Index offsets are relative to the compressed blob; dailyOrdinals retains the source's selection order.
export function buildApplePack(packed, ranks) {
  const rows = [];
  const frames = [];
  const daily = new Set(packed.daily);
  let offset = 0;
  for (let ordinal = 0; ordinal < packed.entries; ordinal++) {
    const entry = completeEntry(packed, ordinal);
    const decoded = Buffer.from(JSON.stringify(entry), "utf8");
    if (decoded.length > maximumRecord) throw new Error("Apple dictionary entry exceeds its safety limit");
    const frame = deflateSync(decoded, { level: 9 });
    const row = { word: entry.word, offset, bytes: frame.length, decoded: decoded.length,
      daily: daily.has(ordinal), level: level(ranks, entry.word) };
    validateEntry(entry, row, packed.metadata.language, packed.metadata.sources);
    rows.push(row);
    frames.push(frame);
    offset += frame.length;
  }
  const index = Buffer.from(JSON.stringify({
    schemaVersion: 1, language: packed.metadata.language, sources: packed.metadata.sources,
    dailyOrdinals: [...packed.daily], rows,
  }), "utf8");
  if (index.length > maximumIndex || offset > 512 * 1024 * 1024) throw new Error("Apple dictionary pack exceeds its safety limit");
  const header = Buffer.alloc(12);
  magic.copy(header);
  header.writeUInt32LE(index.length, 8);
  return Buffer.concat([header, index, ...frames]);
}

export class AppleLexicon {
  constructor(bytes) {
    if (!Buffer.isBuffer(bytes) || bytes.length < 12 || !bytes.subarray(0, 8).equals(magic)) {
      throw new Error("Invalid Apple dictionary header");
    }
    const length = bytes.readUInt32LE(8);
    if (!integer(length, 1, maximumIndex) || length > bytes.length - 12) throw new Error("Truncated Apple dictionary index");
    let index;
    try { index = JSON.parse(utf8.decode(bytes.subarray(12, 12 + length))); }
    catch (error) { throw new Error("Invalid Apple dictionary UTF-8/JSON index", { cause: error }); }
    if (index.schemaVersion !== 1 || !languages.includes(index.language)
        || !Array.isArray(index.sources) || !integer(index.sources.length, 1, 16)
        || index.sources.some((source) => !sourceValid(source))
        || new Set(index.sources.map((source) => source.id)).size !== index.sources.length
        || !Array.isArray(index.rows) || !integer(index.rows.length, 1, 500000)
        || !Array.isArray(index.dailyOrdinals) || !integer(index.dailyOrdinals.length, 1, index.rows.length)) {
      throw new Error("Invalid Apple dictionary index metadata");
    }
    this.language = index.language;
    this.sources = index.sources;
    this.rows = index.rows;
    this.dailyOrdinals = index.dailyOrdinals;
    this.bytes = bytes;
    this.dataOffset = 12 + length;
    let end = 0;
    const words = new Set();
    for (const row of this.rows) {
      if (!row || !text(row.word) || row.word !== row.word.normalize("NFC") || words.has(row.word)
          || /[\x00-\x1f\x7f]/u.test(row.word) || row.word.length > 1024
          || !integer(row.offset, 0, bytes.length) || row.offset !== end
          || !integer(row.bytes, 1, maximumRecord) || !integer(row.decoded, 1, maximumRecord)
          || typeof row.daily !== "boolean" || !levels.includes(row.level)) {
        throw new Error("Invalid Apple dictionary row offsets or metadata");
      }
      words.add(row.word);
      end += row.bytes;
    }
    if (this.dataOffset + end !== bytes.length) throw new Error("Truncated Apple dictionary payload length");
    const daily = new Set();
    for (const ordinal of this.dailyOrdinals) {
      if (!integer(ordinal, 0, this.rows.length - 1) || daily.has(ordinal) || !this.rows[ordinal].daily) {
        throw new Error("Invalid Apple dictionary daily ordinal mismatch");
      }
      daily.add(ordinal);
    }
    if (this.rows.some((row, i) => row.daily !== daily.has(i))) throw new Error("Invalid Apple dictionary daily row mismatch");
  }

  entry(ordinal) {
    if (!integer(ordinal, 0, this.rows.length - 1)) throw new RangeError("Invalid Apple dictionary ordinal");
    const row = this.rows[ordinal];
    const start = this.dataOffset + row.offset;
    let inflated;
    try {
      inflated = inflateSync(this.bytes.subarray(start, start + row.bytes), {
        maxOutputLength: maximumRecord, info: true,
      });
    } catch (error) { throw new Error("Corrupt Apple dictionary deflate frame", { cause: error }); }
    if (inflated.engine.bytesWritten !== row.bytes) throw new Error("Trailing Apple dictionary deflate bytes");
    if (inflated.buffer.length !== row.decoded) throw new Error("Apple dictionary decoded length mismatch");
    let entry;
    try { entry = JSON.parse(utf8.decode(inflated.buffer)); }
    catch (error) { throw new Error("Invalid Apple dictionary UTF-8/JSON record", { cause: error }); }
    validateEntry(entry, row, this.language, this.sources);
    return entry;
  }
}

export async function loadPinnedSources(directory = root) {
  const starterPath = join(directory, "content", "starter");
  const manifestBytes = await readFile(join(starterPath, "manifest.json"));
  const manifest = JSON.parse(utf8.decode(manifestBytes));
  const version = (await readFile(join(directory, "version.properties"), "utf8")).match(/^versionName=(.+)$/m)?.[1].trim();
  if (manifest.schemaVersion !== 3 || manifest.releaseVersion !== version
      || !Array.isArray(manifest.languages) || manifest.languages.length !== languages.length
      || new Set(manifest.languages.map((pack) => pack.language)).size !== languages.length
      || !manifest.discovery || !Array.isArray(manifest.discovery.excludedPartsOfSpeech)
      || languages.some((language) => !Array.isArray(manifest.discovery.excludedWords?.[language]))) {
    throw new Error("Pinned starter manifest is incompatible or stale; prepare shared content separately");
  }
  const result = [];
  for (const language of languages) {
    const info = manifest.languages.find((pack) => pack.language === language);
    if (!info || !new RegExp(`^${language}-[a-f0-9]{16}\\.mflx$`).test(info.file)) throw new Error("Invalid pinned pack filename");
    const bytes = await readFile(join(starterPath, "packs", info.file));
    const ranksBytes = await readFile(join(starterPath, "ranks", `${language}.txt`));
    if (hash(bytes) !== info.sha256 || bytes.length !== info.compressedBytes || hash(ranksBytes) !== manifest.rankHashes?.[language]) {
      throw new Error(`Pinned ${language} source integrity/hash mismatch; no sources were modified`);
    }
    const packed = new PackedLexicon(bytes);
    if (packed.metadata.language !== language || packed.entries !== info.entries || packed.daily.length !== info.dailyEntries) {
      throw new Error(`Pinned ${language} source metadata mismatch`);
    }
    const lines = utf8.decode(ranksBytes).split(/\r?\n/);
    if (lines.at(-1) === "") lines.pop();
    if (lines.length < 40000 || lines.some((word) => !text(word))) throw new Error("Invalid pinned frequency rank asset");
    const ranks = new Map(lines.map((word, i) => [normalizeSearch(word), i + 1]));
    result.push({ language, info, packed, ranks, ranksBytes });
  }
  const pronunciationPath = join(directory, "content", "pronunciations");
  const guideManifestBytes = await readFile(join(pronunciationPath, "manifest.json"));
  const guide = JSON.parse(utf8.decode(guideManifestBytes));
  if (guide.schemaVersion !== 1 || guide.language !== "en" || !/^[a-f0-9]{64}$/.test(guide.sha256)
      || guide.file !== `en-${guide.sha256.slice(0, 16)}.mfpr`) throw new Error("Invalid pinned pronunciation metadata");
  const guideBytes = await readFile(join(pronunciationPath, guide.file));
  if (hash(guideBytes) !== guide.sha256 || guideBytes.length !== guide.bytes) throw new Error("Pinned pronunciation integrity mismatch");
  const builderSha256 = hash(Buffer.concat(await Promise.all(
    ["apple-content.mjs", "packed-lexicon.mjs", "lexicon.mjs"].map((file) => readFile(join(directory, "tools", file))))));
  return { manifest, manifestBytes, languages: result, builderSha256, guideManifestBytes };
}

function provenance(pinned) {
  return {
    schemaVersion: 1, compression: "zlib-frames", builderSha256: pinned.builderSha256,
    sourceManifestSha256: hash(pinned.manifestBytes), pronunciationManifestSha256: hash(pinned.guideManifestBytes),
    sourcePackHashes: Object.fromEntries(pinned.languages.map((item) => [item.language, item.info.sha256])),
    rankHashes: pinned.manifest.rankHashes,
    releaseVersion: pinned.manifest.releaseVersion, attribution: pinned.manifest.attribution,
    modifications: pinned.manifest.modifications, discovery: pinned.manifest.discovery,
  };
}

export async function verifyAppleContent(directory = root, { manifest: supplied } = {}) {
  const pinned = await loadPinnedSources(directory);
  const target = join(directory, "content", "apple");
  const manifest = supplied ?? JSON.parse(utf8.decode(await readFile(join(target, "manifest.json"))));
  for (const [key, value] of Object.entries(provenance(pinned))) {
    if (!isDeepStrictEqual(manifest[key], value)) throw new Error(`Apple content is stale (${key}); run node tools\\apple-content.mjs prepare`);
  }
  if (!Array.isArray(manifest.languages) || manifest.languages.length !== languages.length) throw new Error("Invalid Apple pack manifest");
  for (const source of pinned.languages) {
    const info = manifest.languages.find((pack) => pack.language === source.language);
    if (!info || info.file !== `${source.language}.mfap` || info.entries !== source.info.entries
        || info.dailyEntries !== source.info.dailyEntries) throw new Error("Apple pack metadata is stale or invalid");
    const bytes = await readFile(join(target, info.file));
    if (bytes.length !== info.compressedBytes || hash(bytes) !== info.sha256) throw new Error("Apple pack integrity/hash mismatch");
    const packed = new AppleLexicon(bytes);
    if (packed.language !== source.language || packed.rows.length !== info.entries
        || !isDeepStrictEqual(packed.dailyOrdinals, source.packed.daily)
        || !isDeepStrictEqual(packed.sources, source.packed.metadata.sources)) throw new Error("Apple pack source metadata mismatch");
    for (let i = 0; i < packed.rows.length; i++) {
      if (packed.rows[i].level !== level(source.ranks, packed.rows[i].word)
          || !isDeepStrictEqual(packed.entry(i), completeEntry(source.packed, i))) {
        throw new Error(`Apple pack source record mismatch: ${source.language} ordinal ${i}`);
      }
    }
    const ranks = await readFile(join(target, "ranks", `${source.language}.txt`));
    if (!ranks.equals(source.ranksBytes)) throw new Error("Apple frequency ranks are stale or corrupt");
  }
  return manifest;
}

async function atomicWrite(path, bytes) {
  const staging = `${path}.${randomUUID()}.partial`;
  try {
    await writeFile(staging, bytes, { flag: "wx" });
    await rename(staging, path);
  } finally { await rm(staging, { force: true }); }
}

export async function generateAppleContent(directory = root) {
  const pinned = await loadPinnedSources(directory);
  const target = join(directory, "content", "apple");
  await mkdir(join(target, "ranks"), { recursive: true });
  const packs = [];
  for (const source of pinned.languages) {
    const bytes = buildApplePack(source.packed, source.ranks);
    const info = { language: source.language, file: `${source.language}.mfap`, entries: source.info.entries,
      dailyEntries: source.info.dailyEntries, compressedBytes: bytes.length, sha256: hash(bytes) };
    await atomicWrite(join(target, info.file), bytes);
    await atomicWrite(join(target, "ranks", `${source.language}.txt`), source.ranksBytes);
    packs.push(info);
  }
  const manifest = { ...provenance(pinned), languages: packs };
  await atomicWrite(join(target, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  return manifest;
}

export async function prepareAppleContent(directory = root) {
  // Source corruption is never repaired here. Only the Apple derivatives may be regenerated.
  await loadPinnedSources(directory);
  try { return await verifyAppleContent(directory); }
  catch (error) {
    if (error.code !== "ENOENT" && !(error instanceof SyntaxError)
        && !/Apple|UTF-8|deflate/i.test(error.message)) throw error;
    return generateAppleContent(directory);
  }
}

async function main() {
  const action = process.argv[2] ?? "prepare";
  if (!["prepare", "generate", "verify"].includes(action)) throw new Error("Usage: node tools\\apple-content.mjs [prepare|generate|verify]");
  const manifest = await ({ prepare: prepareAppleContent, generate: generateAppleContent, verify: verifyAppleContent })[action]();
  for (const pack of manifest.languages) console.log(`${pack.language}: ${pack.entries} complete words, ${pack.dailyEntries} daily, ${pack.compressedBytes} bytes`);
  console.log(`${action === "generate" ? "Generated" : "Verified/prepared"} pinned ${manifest.releaseVersion} Apple content.`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
