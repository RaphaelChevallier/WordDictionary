import { createHash } from "node:crypto";
import { createReadStream, createWriteStream } from "node:fs";
import { access, copyFile, mkdir, mkdtemp, open, readFile, readdir, rename, rm, stat, statfs, unlink, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import { createGunzip, createZstdDecompress } from "node:zlib";
import { PackedLexicon, writePackedLexicon } from "./packed-lexicon.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const content = join(root, "content");
const cache = join(root, ".cache", "lexicon");
const manifestPath = join(content, "manifest.json");
const allowedParts = new Set([
  "noun", "verb", "adj", "adv", "pron", "det", "prep", "conj", "intj", "num",
  "phrase", "proverb", "contraction", "prefix", "suffix", "affix", "particle", "article",
]);
const unsuitableDailyTags = new Set([
  "archaic", "obsolete", "rare", "vulgar", "offensive", "derogatory", "slur", "slang",
  "alt-of", "form-of", "abbreviation", "dated", "nonstandard",
]);

export function normalizeSearch(value) {
  return value.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase()
    .replaceAll("\u0153", "oe").replaceAll("\u00e6", "ae").replaceAll("\u00df", "ss")
    .replaceAll("\u2019", "'").trim().replace(/\s+/g, " ");
}

export function anagramKey(value) {
  return [...normalizeSearch(value).replace(/[^\p{L}]/gu, "")].sort().join("");
}

function hash(value) {
  return createHash("sha256").update(value).digest("hex");
}

async function hashFile(path) {
  const digest = createHash("sha256");
  for await (const chunk of createReadStream(path)) digest.update(chunk);
  return digest.digest("hex");
}

function clean(value) {
  return typeof value === "string" ? value.replace(/\s+/g, " ").trim() : "";
}

function sourceId(language, word) {
  return `${language}:${word.normalize("NFC")}`;
}

function definitions(record) {
  return (record.senses ?? []).flatMap((sense) => {
    if (sense.form_of?.length || sense.tags?.includes("form-of")) return [];
    return (sense.glosses ?? []).map(clean).filter(Boolean).map((definition) => ({
      partOfSpeech: record.pos,
      definition,
    }));
  });
}

function relatedWords(record) {
  const senses = record.senses ?? [];
  return [...new Set([
    ...(record.synonyms ?? []),
    ...senses.flatMap((sense) => sense.synonyms ?? []),
  ].map((item) => clean(item.word)).filter(Boolean))].slice(0, 16);
}

async function* jsonLines(path) {
  const source = createReadStream(path);
  const gzip = createGunzip();
  source.on("error", (error) => gzip.destroy(error));
  source.pipe(gzip);
  gzip.setEncoding("utf8");
  let pending = "";
  try {
    // JSONL uses LF; readline also splits valid U+2028/U+2029 inside JSON strings.
    for await (const chunk of gzip) {
      const text = pending + chunk;
      let start = 0;
      let end;
      while ((end = text.indexOf("\n", start)) !== -1) {
        yield text.slice(start, end);
        start = end + 1;
      }
      pending = text.slice(start);
      if (pending.length > 64 * 1024 * 1024) throw new Error("Source JSONL record exceeds the 64 MB safety limit");
    }
    if (pending) yield pending;
  } finally {
    source.destroy();
    gzip.destroy();
  }
}

function compactStorage(db) {
  db.exec(`
    ALTER TABLE entries RENAME TO source_entries;
    CREATE TABLE entries (
      lookup_key TEXT PRIMARY KEY NOT NULL,
      anagram_key TEXT NOT NULL,
      pronunciation TEXT NOT NULL,
      senses TEXT NOT NULL,
      related TEXT NOT NULL,
      sources TEXT NOT NULL
    ) WITHOUT ROWID;
    INSERT INTO entries
      SELECT search_key || char(31) || word, anagram_key, pronunciation, senses, related, sources
      FROM source_entries ORDER BY search_key, word;
    CREATE INDEX idx_anagram ON entries(anagram_key);
    CREATE TABLE daily_words (position INTEGER PRIMARY KEY, entry_key TEXT NOT NULL);
    INSERT INTO daily_words
      SELECT row_number() OVER (ORDER BY daily_rank, id) - 1, search_key || char(31) || word
      FROM source_entries WHERE daily_eligible = 1;
    DROP TABLE source_entries;
    ANALYZE;
    VACUUM;
  `);
}

export async function buildPack({ input, output, source, inputs, release, maximumSenses = 8 }) {
  const contributions = (inputs ?? [{ input, source }]).map(({ input, source }) => ({
    input, source: { ...source, id: source.id ?? `${source.edition}-wiktionary`, priority: source.priority ?? 10 },
  })).sort((a, b) => a.source.priority - b.source.priority || a.source.id.localeCompare(b.source.id, "en"));
  const language = contributions[0]?.source.language;
  if (!language || contributions.some(({ source }) => source.language !== language)
    || new Set(contributions.map(({ source }) => source.id)).size !== contributions.length) {
    throw new Error("A language build requires unique, same-language sources");
  }
  const firstSource = contributions[0].source;
  await mkdir(dirname(output), { recursive: true });
  const db = new DatabaseSync(output);
  let lineNumber = 0;
  try {
    db.exec(`
      PRAGMA journal_mode = OFF;
      PRAGMA synchronous = OFF;
      PRAGMA user_version = 2;
      CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
      CREATE TABLE entries (
        id TEXT PRIMARY KEY NOT NULL,
        word TEXT NOT NULL,
        search_key TEXT NOT NULL,
        anagram_key TEXT NOT NULL,
        pronunciation TEXT NOT NULL,
        senses TEXT NOT NULL,
        related TEXT NOT NULL,
        sources TEXT NOT NULL,
        daily_eligible INTEGER NOT NULL,
        daily_rank INTEGER NOT NULL
      );
      CREATE TABLE word_sources(source_id TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(source_id,id)) WITHOUT ROWID;
    `);
    db.prepare("INSERT INTO metadata VALUES (?, ?)").run("language", language);
    db.prepare("INSERT INTO metadata VALUES (?, ?)").run("edition", firstSource.edition);
    const find = db.prepare("SELECT senses, related, pronunciation, sources, daily_eligible FROM entries WHERE id = ?");
    const insert = db.prepare("INSERT OR REPLACE INTO entries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
    const trackSource = db.prepare("INSERT OR IGNORE INTO word_sources VALUES (?, ?)");
    const languagePattern = new RegExp(`"lang_code"\\s*:\\s*"${language}"`);
    db.exec("BEGIN");
    const sourceStats = [];
    for (const contribution of contributions) {
      const { source } = contribution;
      lineNumber = 0;
      for await (const line of jsonLines(contribution.input)) {
      lineNumber++;
      if (!line.trim()) continue;
      // Avoid parsing other languages' very large records in multilingual dumps.
      if (line.includes('"lang_code"') && !languagePattern.test(line)) continue;
      let record;
      try {
        record = JSON.parse(line);
      } catch (error) {
        if (!(error instanceof SyntaxError)) throw error;
        throw new SyntaxError(`Invalid source JSON at line ${lineNumber}: ${error.message}`);
      }
      if (record.lang_code !== source.language) continue;
      if (typeof record.word !== "string" || typeof record.pos !== "string" || !Array.isArray(record.senses)) {
        throw new TypeError(`Invalid ${source.language} dictionary schema at line ${lineNumber}`);
      }
      const word = record.word.normalize("NFC").trim();
      if (!allowedParts.has(record.pos) || word.length > 100 || !word || /[\u0000-\u001f]/u.test(word)) continue;
      const incomingSenses = definitions(record);
      if (!incomingSenses.length) continue;
      const id = sourceId(source.language, word);
      const previous = find.get(id);
      const senses = previous ? JSON.parse(previous.senses) : [];
      for (const sense of incomingSenses) {
        const definition = sense.definition.normalize("NFC");
        const existing = senses.find((item) =>
          item.partOfSpeech === sense.partOfSpeech && item.definition.normalize("NFC") === definition);
        if (existing) {
          if (!existing.sourceIds.includes(source.id)) existing.sourceIds.push(source.id);
        } else if (senses.length < maximumSenses) {
          senses.push({ ...sense, sourceIds: [source.id] });
        }
      }
      const related = [...new Set([
        ...(previous ? JSON.parse(previous.related) : []), ...relatedWords(record),
      ])].filter((term) => term !== word).slice(0, 16);
      const pronunciation = previous?.pronunciation || clean(record.sounds?.find((sound) => sound.ipa)?.ipa);
      const sourceIds = previous ? JSON.parse(previous.sources) : [];
      if (!sourceIds.includes(source.id)) sourceIds.push(source.id);
      const tags = [...(record.tags ?? []), ...record.senses.flatMap((sense) => sense.tags ?? [])];
      const eligible = /^[\p{Ll}][\p{L}'-]{2,19}$/u.test(word)
        && !tags.some((tag) => unsuitableDailyTags.has(tag))
        && !record.senses.some((sense) => sense.alt_of?.length || sense.form_of?.length);
      const dailyEligible = Number(eligible && (previous ? previous.daily_eligible === 1 : true));
      const sensesJson = JSON.stringify(senses);
      const relatedJson = JSON.stringify(related);
      const dailyRank = createHash("sha256").update(id).digest().readUInt32BE(0) & 0x7fffffff;
      insert.run(id, word, normalizeSearch(word), anagramKey(word), pronunciation,
        sensesJson, relatedJson, JSON.stringify(sourceIds), dailyEligible, dailyRank);
      trackSource.run(source.id, id);
      if (lineNumber % 50000 === 0) {
        db.exec("COMMIT; BEGIN");
        console.log(`${source.language}: read ${lineNumber.toLocaleString("en")} source lines`);
      }
      }
      const count = db.prepare("SELECT count(*) AS count FROM word_sources WHERE source_id = ?").get(source.id).count;
      if (count < source.minimumEntries) {
        throw new Error(`${source.id}: source minimum coverage not met (${count} headwords)`);
      }
      sourceStats.push({ id: source.id, entries: count });
    }
    db.exec("COMMIT");
    const entries = db.prepare("SELECT count(*) AS count FROM entries").get().count;
    const dailyEntries = db.prepare("SELECT count(*) AS count FROM entries WHERE daily_eligible = 1").get().count;
    const minimumEntries = Math.max(...contributions.map(({ source }) => source.minimumEntries));
    if (entries < minimumEntries || dailyEntries < Math.min(21, minimumEntries)) {
      throw new Error(`${language}: minimum dictionary coverage not met (${entries} words, ${dailyEntries} daily candidates)`);
    }
    db.exec("DROP TABLE word_sources");
    compactStorage(db);
    if (db.prepare("PRAGMA integrity_check").get().integrity_check !== "ok") {
      throw new Error(`${language}: generated SQLite integrity check failed`);
    }
    return {
      language,
      edition: firstSource.edition,
      releaseVersion: release,
      entries,
      dailyEntries,
      unpackedBytes: (await stat(output)).size,
      sha256: await hashFile(output),
      sourceStats,
      mergedWordContributions: sourceStats.reduce((sum, item) => sum + item.entries, 0) - entries,
    };
  } finally {
    db.close();
  }
}

export function validateChanges(changes, maximumRemovalFraction) {
  const previousCount = changes.removed + changes.changed + changes.unchanged;
  if (previousCount && changes.removed / previousCount > maximumRemovalFraction) {
    throw new Error(`Removal safety gate: ${changes.removed}/${previousCount} words would be removed. Inspect the source/change report; use --allow-large-removals only after review.`);
  }
}

async function unpack(input, output, metadata) {
  let size = 0;
  const digest = createHash("sha256");
  await pipeline(createReadStream(input), createZstdDecompress(), new Transform({
    transform(chunk, _encoding, callback) {
      size += chunk.length;
      if (size > metadata.unpackedBytes) {
        callback(new Error("Dictionary unpacked length exceeds manifest"));
        return;
      }
      digest.update(chunk);
      callback(null, chunk);
    },
  }), createWriteStream(output, { flags: "wx" }));
  if (size !== metadata.unpackedBytes || digest.digest("hex") !== metadata.sha256) {
    throw new Error("Dictionary hash or unpacked length does not match manifest");
  }
}

export async function verifyPack(path, metadata, release) {
  if (metadata.releaseVersion !== release) throw new Error("Dictionary release version mismatch");
  if (path.endsWith(".mflx")) {
    const bytes = await readFile(path);
    if (bytes.length !== metadata.compressedBytes || hash(bytes) !== metadata.sha256) {
      throw new Error("Packed dictionary hash/length mismatch");
    }
    const pack = new PackedLexicon(bytes);
    if (pack.entries !== metadata.entries || pack.daily.length !== metadata.dailyEntries
      || pack.metadata.language !== metadata.language) throw new Error("Packed dictionary metadata mismatch");
    const daily = new Set(pack.daily);
    const excludedParts = new Set(metadata.discovery?.excludedPartsOfSpeech ?? []);
    const excludedWords = new Set((metadata.discovery?.excludedWords?.[metadata.language] ?? [])
      .map((word) => word.normalize("NFC").toLowerCase()));
    let previous;
    for (let i = 0; i < pack.entries; i++) {
      const entry = pack.entry(i);
      if (daily.has(i) && (excludedWords.has(entry.word.normalize("NFC").toLowerCase())
        || !entry.senses.some((sense) => !excludedParts.has(sense.partOfSpeech)))) {
        throw new Error("Daily discovery index contains an excluded word");
      }
      if (previous && Buffer.compare(Buffer.from(previous), Buffer.from(entry.key)) >= 0) {
        throw new Error("Packed dictionary word order is invalid");
      }
      previous = entry.key;
    }
    return;
  }
  const directory = await mkdtemp(join(dirname(path), ".verify-"));
  try {
    const output = join(directory, "dictionary.sqlite");
    await unpack(path, output, metadata);
    const db = new DatabaseSync(output, { readOnly: true });
    try {
      const language = db.prepare("SELECT value FROM metadata WHERE key = 'language'").get()?.value;
      if (language !== metadata.language || db.prepare("PRAGMA user_version").get().user_version !== 2) {
        throw new Error("Dictionary schema/language mismatch");
      }
      if (db.prepare("SELECT count(*) AS count FROM entries").get().count !== metadata.entries
        || db.prepare("SELECT count(*) AS count FROM daily_words").get().count !== metadata.dailyEntries) {
        throw new Error("Dictionary entry counts do not match manifest");
      }
      if (db.prepare("PRAGMA quick_check").get().quick_check !== "ok") {
        throw new Error("Dictionary integrity check failed");
      }
    } finally {
      db.close();
    }
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

async function exists(path) {
  try {
    await access(path);
    return true;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
    return false;
  }
}

export async function downloadSource(source, cacheDirectory = cache) {
  const path = join(cacheDirectory, `${source.id}.jsonl.gz`);
  const metadataPath = join(cacheDirectory, `${source.id}.source.json`);
  const legacyMetadata = join(cacheDirectory, `${source.language}.source.json`);
  const legacyPath = join(cacheDirectory, `${source.language}.jsonl.gz`);
  if (!await exists(path) && await exists(legacyMetadata) && await exists(legacyPath)
    && JSON.parse(await readFile(legacyMetadata, "utf8")).url === source.url) {
    await rename(legacyPath, path);
    await rename(legacyMetadata, metadataPath);
  }
  const previous = await exists(metadataPath) ? JSON.parse(await readFile(metadataPath, "utf8")) : undefined;
  const hasCache = previous?.url === source.url && await exists(path);
  const headers = { "User-Agent": "MaxFluency/0.1 (offline dictionary build; https://kaikki.org)" };
  console.log(`${source.id}: checking ${source.url}`);
  if (hasCache) {
    const head = await fetch(source.url, { method: "HEAD", headers, signal: AbortSignal.timeout(30000) });
    if (!head.ok) throw new Error(`${source.id}: source check failed (HTTP ${head.status})`);
    if (previous.etag && head.headers.get("etag") === previous.etag
      && Number(head.headers.get("content-length")) === previous.bytes) {
      if (await hashFile(path) !== previous.sha256) throw new Error(`${source.id}: cached source hash mismatch`);
      return { path, metadata: previous };
    }
  }
  const response = await fetch(source.url, { headers, signal: AbortSignal.timeout(45 * 60 * 1000) });
  if (!response.ok || !response.body) {
    throw new Error(`${source.language}: source download failed (HTTP ${response.status}); existing packs were not replaced`);
  }
  const temporary = `${path}.partial`;
  await pipeline(Readable.fromWeb(response.body), createWriteStream(temporary));
  const bytes = (await stat(temporary)).size;
  const advertised = response.headers.get("content-length");
  if (advertised && bytes !== Number(advertised)) throw new Error(`${source.language}: incomplete source download`);
  const metadata = {
    url: source.url,
    etag: response.headers.get("etag"),
    lastModified: response.headers.get("last-modified"),
    sha256: await hashFile(temporary),
    bytes,
    fetchedAt: new Date().toISOString(),
  };
  await rename(temporary, path);
  await writeFile(metadataPath, `${JSON.stringify(metadata, null, 2)}\n`);
  return { path, metadata };
}

export async function comparePack(previousPath, metadata, output, temporaryDirectory, currentPackedPath) {
  if (!previousPath) {
    const db = new DatabaseSync(output, { readOnly: true });
    try {
      return { added: db.prepare("SELECT count(*) AS count FROM entries").get().count, removed: 0, changed: 0, unchanged: 0 };
    } finally {
      db.close();
    }
  }
  const oldDatabase = join(temporaryDirectory, `${metadata.language}-previous.sqlite`);
  const packed = previousPath.endsWith(".mflx")
    ? new PackedLexicon(await readFile(previousPath)) : undefined;
  const currentPacked = currentPackedPath ? new PackedLexicon(await readFile(currentPackedPath)) : undefined;
  if (!packed) await unpack(previousPath, oldDatabase, metadata);
  const db = new DatabaseSync(output, { readOnly: true });
  try {
    db.exec("CREATE TEMP TABLE old_diff(key TEXT PRIMARY KEY,digest TEXT); CREATE TEMP TABLE new_diff(key TEXT PRIMARY KEY,digest TEXT)");
    function digest(pronunciation, senses, related, daily) {
      return hash(JSON.stringify([pronunciation,
        senses.map(({ partOfSpeech, definition }) => [partOfSpeech, definition]), related, daily]));
    }
    const insertOld = db.prepare("INSERT INTO old_diff VALUES (?,?)");
    const insertNew = db.prepare("INSERT INTO new_diff VALUES (?,?)");
    const insertPacked = (reader, insert) => {
      const eligible = new Set(reader.daily);
      for (let i = 0; i < reader.entries; i++) {
        const entry = reader.entry(i);
        insert.run(entry.key, digest(entry.pronunciation, entry.senses, entry.related, eligible.has(i)));
      }
    };
    db.exec("BEGIN");
    if (packed) {
      insertPacked(packed, insertOld);
    } else {
      db.prepare("ATTACH DATABASE ? AS previous").run(oldDatabase);
      for (const row of db.prepare(`SELECT e.*,d.entry_key IS NOT NULL AS daily FROM previous.entries e
        LEFT JOIN previous.daily_words d ON d.entry_key=e.lookup_key`).iterate()) {
        insertOld.run(row.lookup_key, digest(row.pronunciation, JSON.parse(row.senses), JSON.parse(row.related), Boolean(row.daily)));
      }
    }
    if (currentPacked) {
      insertPacked(currentPacked, insertNew);
    } else {
      for (const row of db.prepare(`SELECT e.*,d.entry_key IS NOT NULL AS daily FROM main.entries e
        LEFT JOIN main.daily_words d ON d.entry_key=e.lookup_key`).iterate()) {
        insertNew.run(row.lookup_key, digest(row.pronunciation, JSON.parse(row.senses), JSON.parse(row.related), Boolean(row.daily)));
      }
    }
    db.exec("COMMIT");
    const common = db.prepare(`SELECT count(*) AS count,coalesce(sum(n.digest<>p.digest),0) AS changed
      FROM new_diff n JOIN old_diff p ON p.key=n.key`).get();
    return {
      added: db.prepare("SELECT count(*) AS count FROM new_diff n LEFT JOIN old_diff p ON p.key=n.key WHERE p.key IS NULL").get().count,
      removed: db.prepare("SELECT count(*) AS count FROM old_diff p LEFT JOIN new_diff n ON p.key=n.key WHERE n.key IS NULL").get().count,
      changed: common.changed,
      unchanged: common.count - common.changed,
    };
  } finally {
    db.close();
  }
}

function validateManifest(manifest, release) {
  if (manifest.schemaVersion === 3) {
    if (manifest.compression !== "zstd-blocks" || manifest.releaseVersion !== release || !manifest.languages?.length) {
      throw new Error("Invalid compressed-block dictionary manifest");
    }
    for (const pack of manifest.languages) {
      if (!/^[a-z]{2,3}$/.test(pack.language) || !new RegExp(`^${pack.language}-[a-f0-9]{16}\\.mflx$`).test(pack.file)
        || !/^[a-f0-9]{64}$/.test(pack.sha256) || pack.sha256 !== pack.compressedSha256
        || pack.releaseVersion !== release || !Number.isSafeInteger(pack.entries) || pack.entries < 1
        || !Number.isSafeInteger(pack.compressedBytes) || pack.compressedBytes < 64 || pack.compressedBytes > 536870912) {
        throw new Error("Invalid compressed-block dictionary pack metadata");
      }
    }
    return;
  }
  if (manifest.schemaVersion !== 2 || manifest.compression !== "zstd" || manifest.releaseVersion !== release || !Array.isArray(manifest.languages)
    || !manifest.languages.length) throw new Error("Missing/incompatible dictionary manifest or release version");
  for (const pack of manifest.languages) {
    if (!/^[a-z]{2,3}$/.test(pack.language) || !new RegExp(`^${pack.language}-[a-f0-9]{16}\\.sqlite\\.zst$`).test(pack.file)
      || !/^[a-f0-9]{64}$/.test(pack.sha256) || !/^[a-f0-9]{64}$/.test(pack.compressedSha256)
      || pack.releaseVersion !== release || !Number.isSafeInteger(pack.entries) || pack.entries < 1
      || !Number.isSafeInteger(pack.unpackedBytes) || pack.unpackedBytes < 1 || pack.unpackedBytes > 536870912) {
      throw new Error("Invalid dictionary pack metadata");
    }
  }
}

async function verify(release) {
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
  validateManifest(manifest, release);
  const configuration = JSON.parse(await readFile(join(content, "sources.json"), "utf8"));
  if (JSON.stringify(manifest.discovery ?? null) !== JSON.stringify(configuration.discovery ?? null)) {
    throw new Error("Bundled daily discovery policy differs from configuration; run npm run content:sync");
  }
  const expected = configuration.languages.map((source) => source.language).sort().join(",");
  if (manifest.languages.map((pack) => pack.language).sort().join(",") !== expected) {
    throw new Error("Bundled languages do not match content/sources.json");
  }
  let bytes = 0;
  for (const pack of manifest.languages) {
    const path = join(content, "packs", pack.file);
    if ((await stat(path)).size !== pack.compressedBytes || await hashFile(path) !== pack.compressedSha256) {
      throw new Error(`${pack.language}: compressed pack hash/length mismatch`);
    }
    await verifyPack(path, { ...pack, discovery: manifest.discovery }, release);
    bytes += pack.compressedBytes;
    console.log(`${pack.language}: verified ${pack.entries.toLocaleString("en")} words, ${(pack.compressedBytes / 1e6).toFixed(1)} MB packed`);
  }
  if (bytes > configuration.maximumCompressedBytes) throw new Error("Combined dictionary packs exceed the configured size budget");
  return manifest;
}

async function sync(release, allowLargeRemovals) {
  await mkdir(cache, { recursive: true });
  const disk = await statfs(root);
  if (disk.bavail * disk.bsize < 8 * 1024 ** 3) throw new Error("At least 8 GB of free build-disk space is required for raw source caches");
  const lockPath = join(cache, "sync.lock");
  const lock = await open(lockPath, "wx");
  const staging = await mkdtemp(join(cache, "staging-"));
  try {
    const configuration = JSON.parse(await readFile(join(content, "sources.json"), "utf8"));
    const builderHash = await hashFile(fileURLToPath(import.meta.url));
    const formatHash = await hashFile(fileURLToPath(new URL("./packed-lexicon.mjs", import.meta.url)));
    const compiledDirectory = join(cache, "compiled");
    await mkdir(compiledDirectory, { recursive: true });
    const previous = await exists(manifestPath) ? JSON.parse(await readFile(manifestPath, "utf8")) : undefined;
    if (previous) validateManifest(previous, previous.releaseVersion);
    const languages = [];
    const allSourceIds = new Set();
    for (const language of configuration.languages) {
      if (!/^[a-z]{2,3}$/.test(language.language) || !language.sources?.length || language.sources.length > 16) {
        throw new Error("Each language must declare 1-16 approved sources");
      }
      const inputs = [];
      const sources = [...language.sources].sort((a, b) => a.priority - b.priority || a.id.localeCompare(b.id, "en"));
      for (const source of sources) {
        if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(source.id) || allSourceIds.has(source.id)
          || !/^[a-z]{2,12}$/.test(source.edition) || !source.title || source.license !== "CC-BY-SA-4.0"
          || source.format !== "wiktextract-jsonl-gzip" || new URL(source.url).protocol !== "https:"
          || !Number.isSafeInteger(source.minimumEntries) || source.minimumEntries < 1
          || !Number.isSafeInteger(source.priority)) {
          throw new Error(`Invalid/unlicensed dictionary source: ${source.id}`);
        }
        allSourceIds.add(source.id);
        const downloaded = await downloadSource({ ...source, language: language.language });
        inputs.push({ input: downloaded.path, source: { ...source, language: language.language }, provenance: downloaded.metadata });
      }
      const fingerprint = hash(JSON.stringify([inputs.map(({ source, provenance }) => [source, provenance.sha256]),
        builderHash, configuration.maximumSensesPerWord]));
      const corpus = join(compiledDirectory, `${language.language}-${fingerprint}.sqlite`);
      const corpusMetadata = `${corpus}.json`;
      let corpusInfo;
      if (await exists(corpus) && await exists(corpusMetadata)) {
        corpusInfo = JSON.parse(await readFile(corpusMetadata, "utf8"));
        if (await hashFile(corpus) !== corpusInfo.sha256) throw new Error("Build-corpus cache hash mismatch");
        console.log(`${language.language}: reused verified build-only corpus`);
      } else {
        const temporaryCorpus = join(staging, `${language.language}.sqlite`);
        corpusInfo = await buildPack({
          inputs, output: temporaryCorpus, release, maximumSenses: configuration.maximumSensesPerWord,
        });
        if (corpusInfo.entries < language.minimumEntries) throw new Error("Merged dictionary minimum coverage not met");
        await rename(temporaryCorpus, corpus);
        await writeFile(corpusMetadata, `${JSON.stringify(corpusInfo, null, 2)}\n`);
      }
      const credits = sources.map(({ id, edition, title, license }) => ({ id, edition, title, license }));
      const packFingerprint = hash(JSON.stringify([fingerprint, formatHash, configuration.discovery]));
      const compressed = join(staging, `${language.language}.mflx`);
      const cachedPack = join(compiledDirectory, `${language.language}-${packFingerprint}.mflx`);
      const cachedMetadata = `${cachedPack}.json`;
      let metadata;
      if (await exists(cachedPack) && await exists(cachedMetadata)) {
        metadata = JSON.parse(await readFile(cachedMetadata, "utf8"));
        if (await hashFile(cachedPack) !== metadata.sha256) throw new Error("Compiled dictionary cache hash mismatch");
        await copyFile(cachedPack, compressed);
      } else {
        const db = new DatabaseSync(corpus, { readOnly: true });
        try {
          metadata = await writePackedLexicon(db, compressed, { language: language.language, sources: credits },
            { discovery: configuration.discovery });
        } finally { db.close(); }
        await copyFile(compressed, `${cachedPack}.partial`);
        await rename(`${cachedPack}.partial`, cachedPack);
        await writeFile(cachedMetadata, `${JSON.stringify(metadata, null, 2)}\n`);
      }
      metadata = { ...metadata, language: language.language, edition: language.language, releaseVersion: release };
      if (metadata.dailyEntries < 21) throw new Error(`${language.language}: daily discovery needs at least 21 eligible words`);
      console.log(`${language.language}: ${(metadata.compressedBytes / 1e6).toFixed(1)} MB, ${metadata.entries.toLocaleString("en")} unique headwords`);
      const old = previous?.languages.find((pack) => pack.language === language.language);
      const changes = await comparePack(old ? join(content, "packs", old.file) : undefined, old, corpus, staging, compressed);
      console.log(`${language.language}: ${JSON.stringify(changes)}`);
      await writeFile(join(cache, `${language.language}-changes.json`), `${JSON.stringify(changes, null, 2)}\n`);
      if (!allowLargeRemovals) validateChanges(changes, configuration.maximumRemovalFraction);
      languages.push({
        ...metadata,
        compressedSha256: metadata.sha256,
        file: `${language.language}-${metadata.sha256.slice(0, 16)}.mflx`,
        sources: inputs.map(({ source, provenance }) => ({
          ...source, ...provenance, entries: corpusInfo.sourceStats.find((item) => item.id === source.id).entries,
        })),
        mergedWordContributions: corpusInfo.mergedWordContributions,
        changes,
      });
    }
    if (languages.reduce((sum, pack) => sum + pack.compressedBytes, 0) > configuration.maximumCompressedBytes) {
      throw new Error("Dictionary packs exceed the content budget; existing packs were not replaced. Content is never silently truncated to reach the size target.");
    }
    const manifest = {
      schemaVersion: 3,
      compression: "zstd-blocks",
      releaseVersion: release,
      builtAt: new Date().toISOString(),
      license: "CC-BY-SA-4.0",
      attribution: "Wiktionary and Simple English Wiktionary contributors; machine-readable extraction by Kaikki.org / Wiktextract.",
      modifications: "Native-language lexical entries selected; form-only entries and proper names omitted; source union deduplicated by language and NFC spelling; identical same-part-of-speech definitions merged with source attribution; up to 8 senses and 16 related terms retained; front-coded word index and independently compressed binary blocks added. Quotations, audio and images are not redistributed.",
      sizeTargetBytes: configuration.targetReleaseApkBytes,
      discovery: configuration.discovery,
      languages,
    };
    validateManifest(manifest, release);
    const packsDirectory = join(content, "packs");
    await mkdir(packsDirectory, { recursive: true });
    for (const pack of languages) {
      await verifyPack(join(staging, `${pack.language}.mflx`), { ...pack, discovery: manifest.discovery }, release);
      const destination = join(packsDirectory, pack.file);
      if (!await exists(destination)) await rename(join(staging, `${pack.language}.mflx`), destination);
    }
    const stagedManifest = join(content, "manifest.json.partial");
    await writeFile(stagedManifest, `${JSON.stringify(manifest, null, 2)}\n`);
    // Hash-named packs are immutable; the manifest is the single publication point.
    await rename(stagedManifest, manifestPath);
    const active = new Set(languages.map((pack) => pack.file));
    for (const name of await readdir(packsDirectory)) {
      if (/^[a-z]{2,3}-[a-f0-9]{16}(?:\.sqlite\.zst|\.mflx)$/.test(name) && !active.has(name)) {
        await unlink(join(packsDirectory, name));
      }
      await writeFile(join(content, "changes.json"), `${JSON.stringify({
        releaseVersion: release,
        previousReleaseVersion: previous?.releaseVersion ?? null,
        discovery: configuration.discovery,
        languages: languages.map(({ language, entries, dailyEntries, sources, mergedWordContributions, changes }) =>
          ({ language, entries, dailyEntries, sources: sources.map(({ id, entries }) => ({ id, entries })), mergedWordContributions, ...changes })),
      }, null, 2)}\n`);
    }
    await verify(release);
  } finally {
    await lock.close();
    await unlink(lockPath);
    await rm(staging, { recursive: true, force: true });
  }
}

async function main() {
  const command = process.argv[2];
  const arguments_ = process.argv.slice(3);
  const versionIndex = arguments_.indexOf("--version");
  const release = versionIndex >= 0 ? arguments_[versionIndex + 1]
    : (await readFile(join(root, "version.properties"), "utf8")).match(/^versionName=(.+)$/m)?.[1]?.trim();
  if (!release || !/^\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?$/.test(release)) throw new Error("A valid release version is required");
  if (command === "verify") await verify(release);
  else if (command === "sync") await sync(release, arguments_.includes("--allow-large-removals"));
  else if (command === "prepare-release") {
    const current = await exists(manifestPath) ? JSON.parse(await readFile(manifestPath, "utf8")) : undefined;
    if (current?.releaseVersion !== release) await sync(release, false);
    else {
      console.log(`Release ${release} uses its pinned dictionary snapshot; bump version.properties for a source refresh.`);
      await verify(release);
    }
  } else throw new Error("Usage: node tools/lexicon.mjs sync|verify|prepare-release [--version x.y.z] [--allow-large-removals]");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
