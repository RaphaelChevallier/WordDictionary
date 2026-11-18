import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { open, unlink } from "node:fs/promises";
import { constants, zstdCompressSync, zstdDecompressSync } from "node:zlib";

const magic = Buffer.from("MFLX0003");
const headerBytes = 64;
const pageSize = 128;
const maximumDecodedBytes = 64 * 1024 * 1024;
const maximumBlockBytes = 16 * 1024 * 1024;
const parts = ["noun", "verb", "adj", "adv", "pron", "det", "prep", "conj", "intj",
  "num", "phrase", "proverb", "contraction", "prefix", "suffix", "affix", "particle", "article"];
const compression = { params: {
  [constants.ZSTD_c_compressionLevel]: 19,
  [constants.ZSTD_c_windowLog]: 22,
  [constants.ZSTD_c_checksumFlag]: 1,
} };

class Writer {
  buffer = Buffer.alloc(4096);
  length = 0;
  bytes(value) {
    if (this.length + value.length > this.buffer.length) {
      const next = Buffer.alloc(Math.max(this.buffer.length * 2, this.length + value.length));
      this.buffer.copy(next, 0, 0, this.length);
      this.buffer = next;
    }
    value.copy(this.buffer, this.length);
    this.length += value.length;
  }
  u32(value) {
    if (!Number.isSafeInteger(value) || value < 0 || value > 0xffffffff) throw new Error("Pack integer is out of range");
    const bytes = Buffer.allocUnsafe(4);
    bytes.writeUInt32LE(value);
    this.bytes(bytes);
  }
  varint(value) {
    if (!Number.isSafeInteger(value) || value < 0 || value > 0x7fffffff) throw new Error("Pack varint is out of range");
    do {
      const byte = value & 0x7f;
      value >>>= 7;
      this.bytes(Buffer.from([byte | (value ? 0x80 : 0)]));
    } while (value);
  }
  string(value) {
    const bytes = Buffer.from(value, "utf8");
    this.varint(bytes.length);
    this.bytes(bytes);
  }
  result() { return this.buffer.subarray(0, this.length); }
}

class Reader {
  offset = 0;
  constructor(bytes) { this.bytes = bytes; }
  take(length) {
    if (!Number.isInteger(length) || length < 0 || this.offset + length > this.bytes.length) throw new Error("Truncated pack record");
    const result = this.bytes.subarray(this.offset, this.offset + length);
    this.offset += length;
    return result;
  }
  u32() { return this.take(4).readUInt32LE(); }
  varint() {
    let value = 0;
    for (let shift = 0; shift <= 28; shift += 7) {
      const byte = this.take(1)[0];
      if (shift === 28 && (byte & 0xf8)) throw new Error("Pack varint exceeds limit");
      value += (byte & 0x7f) * 2 ** shift;
      if (!(byte & 0x80)) return value;
    }
    throw new Error("Invalid pack varint");
  }
  string() { return this.take(this.varint()).toString("utf8"); }
  end() { if (this.offset !== this.bytes.length) throw new Error("Unexpected trailing pack record bytes"); }
}

function sourceMask(ids, sources) {
  let mask = 0;
  for (const id of ids) {
    const index = sources.findIndex((source) => source.id === id);
    if (index < 0) throw new Error(`Unknown dictionary source: ${id}`);
    mask |= 1 << index;
  }
  if (!mask) throw new Error("Every dictionary entry and sense needs a source");
  return mask;
}

function encodeEntry(row, sources) {
  const ids = row.sources ? JSON.parse(row.sources) : [sources[0].id];
  const senses = JSON.parse(row.senses);
  if (!senses.length || senses.length > 64) throw new Error("A packed word needs 1-64 definitions");
  const definitions = senses.map((sense) => {
    const part = parts.indexOf(sense.partOfSpeech);
    if (part < 0) throw new Error(`Unsupported part of speech: ${sense.partOfSpeech}`);
    const result = [part, sourceMask(sense.sourceIds ?? ids, sources), sense.definition];
    const examples = sense.examples ?? [];
    if (!Array.isArray(examples) || examples.length > 3 ||
        examples.some((text) => typeof text !== "string" || !text.trim() || text.length > 600)) {
      throw new Error("Invalid dictionary usage examples");
    }
    if (examples.length) result.push(examples);
    return result;
  });
  const related = JSON.parse(row.related);
  if (related.length > 64) throw new Error("Too many related terms");
  return Buffer.from(JSON.stringify([sourceMask(ids, sources), row.pronunciation, definitions, related]), "utf8");
}

function encodeBlock(records) {
  const offsets = new Writer();
  let length = 0;
  for (const record of records) {
    offsets.u32(length);
    length += record.length;
  }
  offsets.u32(length);
  return Buffer.concat([offsets.result(), ...records]);
}

function prepareDailySelection(db, language, discovery) {
  db.exec(`
    DROP TABLE IF EXISTS temp.pack_daily;
    CREATE TEMP TABLE pack_daily(position INTEGER PRIMARY KEY,entry_key TEXT NOT NULL);
  `);
  if (!discovery) {
    db.exec("INSERT INTO pack_daily SELECT position,entry_key FROM daily_words");
    return;
  }
  const excludedParts = discovery.excludedPartsOfSpeech;
  const excludedWords = discovery.excludedWords?.[language];
  if (!Array.isArray(excludedParts) || excludedParts.some((part) => !parts.includes(part))
    || !Array.isArray(excludedWords) || excludedWords.some((word) => typeof word !== "string" || !word.trim())) {
    throw new Error(`Invalid daily discovery policy for ${language}`);
  }
  db.exec("DROP TABLE IF EXISTS temp.pack_basic_words; CREATE TEMP TABLE pack_basic_words(word TEXT PRIMARY KEY)");
  const insert = db.prepare("INSERT OR IGNORE INTO pack_basic_words VALUES (?)");
  for (const word of excludedWords) insert.run(word.normalize("NFC").toLowerCase());
  db.function("discovery_word", { deterministic: true }, (word) => word.normalize("NFC").toLowerCase());
  const allowedPart = excludedParts.length
    ? `json_extract(s.value,'$.partOfSpeech') NOT IN (${excludedParts.map(() => "?").join(",")})` : "1";
  db.prepare(`
    INSERT INTO pack_daily
    SELECT row_number() OVER(ORDER BY d.position)-1,d.entry_key
      FROM daily_words d JOIN entries e ON e.lookup_key=d.entry_key
      WHERE discovery_word(substr(e.lookup_key,instr(e.lookup_key,char(31))+1))
        NOT IN (SELECT word FROM pack_basic_words)
        AND EXISTS(SELECT 1 FROM json_each(e.senses) s WHERE ${allowedPart})
      ORDER BY d.position
  `).run(...excludedParts);
  db.exec("DROP TABLE pack_basic_words");
}

export async function writePackedLexicon(db, output, metadata, { blockEntries = 32768, discovery } = {}) {
  if (!Number.isInteger(blockEntries) || blockEntries < 64 || blockEntries > 32768
    || (blockEntries & (blockEntries - 1))) throw new Error("Invalid dictionary block size");
  if (!/^[a-z]{2,3}$/.test(metadata.language) || !metadata.sources.length || metadata.sources.length > 16
    || new Set(metadata.sources.map((source) => source.id)).size !== metadata.sources.length) {
    throw new Error("A pack needs one language and 1-16 unique sources");
  }
  db.exec(`
    DROP TABLE IF EXISTS temp.pack_ordinals;
    CREATE TEMP TABLE pack_ordinals(ordinal INTEGER PRIMARY KEY,lookup_key TEXT UNIQUE);
    INSERT INTO pack_ordinals SELECT row_number() OVER(ORDER BY lookup_key)-1,lookup_key FROM entries ORDER BY lookup_key;
  `);
  prepareDailySelection(db, metadata.language, discovery);
  const hasSources = db.prepare("PRAGMA table_info(entries)").all().some((column) => column.name === "sources");
  const entries = db.prepare("SELECT count(*) AS count FROM entries").get().count;
  const dailyEntries = db.prepare("SELECT count(*) AS count FROM pack_daily").get().count;
  if (!entries || !dailyEntries) throw new Error("Empty dictionary or daily selection");
  const words = new Writer();
  const checkpoints = [];
  const blocks = [];
  const temporary = `${output}.data.partial`;
  const payload = await open(temporary, "wx");
  let decodedDataBytes = 0;
  let payloadBytes = 0;
  try {
    let records = [];
    let previous = Buffer.alloc(0);
    let ordinal = 0;
    const rows = db.prepare(`SELECT lookup_key,pronunciation,senses,related,${hasSources ? "sources" : "NULL AS sources"}
      FROM entries ORDER BY lookup_key`).iterate();
    for (const row of rows) {
      const key = Buffer.from(row.lookup_key, "utf8");
      if (ordinal % pageSize === 0) {
        checkpoints.push(words.length);
        previous = Buffer.alloc(0);
      }
      let common = 0;
      while (common < Math.min(previous.length, key.length) && previous[common] === key[common]) common++;
      words.varint(common);
      words.varint(key.length - common);
      words.bytes(key.subarray(common));
      previous = key;
      records.push(encodeEntry(row, metadata.sources));
      ordinal++;
      if (records.length === blockEntries || ordinal === entries) {
        const decoded = encodeBlock(records);
        if (decoded.length > maximumBlockBytes) throw new Error("Dictionary block exceeds 16 MB decoded size");
        const encoded = zstdCompressSync(decoded, compression);
        blocks.push({ offset: payloadBytes, bytes: encoded.length, decoded: decoded.length });
        await payload.writeFile(encoded);
        payloadBytes += encoded.length;
        decodedDataBytes += decoded.length;
        records = [];
      }
    }
    const index = new Writer();
    index.u32(pageSize);
    index.u32(checkpoints.length);
    checkpoints.forEach((offset) => index.u32(offset));
    index.u32(words.length);
    index.bytes(words.result());
    index.u32(entries);
    for (const row of db.prepare(`SELECT p.ordinal FROM entries e JOIN pack_ordinals p ON p.lookup_key=e.lookup_key
      ORDER BY e.anagram_key,e.lookup_key`).iterate()) index.u32(row.ordinal);
    index.u32(dailyEntries);
    for (const row of db.prepare(`SELECT p.ordinal FROM pack_daily d JOIN pack_ordinals p ON p.lookup_key=d.entry_key
      ORDER BY d.position`).iterate()) index.u32(row.ordinal);
    if (index.length > maximumDecodedBytes) throw new Error("Dictionary index exceeds 64 MB decoded size");
    const encodedIndex = zstdCompressSync(index.result(), compression);
    const description = Buffer.from(JSON.stringify(metadata), "utf8");
    const directory = new Writer();
    for (const block of blocks) {
      directory.u32(block.offset);
      directory.u32(block.bytes);
      directory.u32(block.decoded);
    }
    const header = Buffer.alloc(headerBytes);
    magic.copy(header);
    const directoryOffset = headerBytes + description.length + encodedIndex.length;
    const dataOffset = directoryOffset + directory.length;
    [description.length, entries, dailyEntries, blockEntries, blocks.length, index.length, encodedIndex.length,
      directoryOffset, dataOffset, payloadBytes, maximumBlockBytes].forEach((value, i) => header.writeUInt32LE(value, 8 + i * 4));
    await payload.close();
    const file = await open(output, "wx");
    try {
      await file.writeFile(Buffer.concat([header, description, encodedIndex, directory.result()]));
      for await (const chunk of createReadStream(temporary)) await file.writeFile(chunk);
    } finally { await file.close(); }
    const digest = createHash("sha256");
    for await (const chunk of createReadStream(output)) digest.update(chunk);
    return {
      entries, dailyEntries,
      compressedBytes: dataOffset + payloadBytes,
      unpackedBytes: headerBytes + description.length + index.length + directory.length + decodedDataBytes,
      sha256: digest.digest("hex"),
      indexBytes: index.length,
      maximumBlockBytes,
    };
  } finally {
    await payload.close();
    await unlink(temporary);
    db.exec("DROP TABLE IF EXISTS temp.pack_ordinals; DROP TABLE IF EXISTS temp.pack_daily");
  }
}

export class PackedLexicon {
  constructor(bytes) {
    if (bytes.length < headerBytes || !bytes.subarray(0, 8).equals(magic)) throw new Error("Invalid dictionary header");
    const values = Array.from({ length: 11 }, (_, i) => bytes.readUInt32LE(8 + i * 4));
    const [descriptionBytes, entries, dailyEntries, entryBlockSize, blockCount, indexBytes, compressedIndexBytes,
      directoryOffset, dataOffset, payloadBytes, blockLimit] = values;
    if (!entries || entries > 5000000 || !dailyEntries || dailyEntries > entries || entryBlockSize < 64 || entryBlockSize > 32768
      || (entryBlockSize & (entryBlockSize - 1)) || blockCount !== Math.ceil(entries / entryBlockSize) || indexBytes > maximumDecodedBytes
      || directoryOffset !== headerBytes + descriptionBytes + compressedIndexBytes
      || dataOffset !== directoryOffset + blockCount * 12 || bytes.length !== dataOffset + payloadBytes
      || blockLimit !== maximumBlockBytes || descriptionBytes > 65536) {
      throw new Error("Invalid dictionary index size, offsets, or file length");
    }
    this.bytes = bytes;
    this.entries = entries;
    this.blockSize = entryBlockSize;
    this.dataOffset = dataOffset;
    this.directoryOffset = directoryOffset;
    this.metadata = JSON.parse(bytes.subarray(headerBytes, headerBytes + descriptionBytes).toString("utf8"));
    if (!this.metadata.sources?.length || this.metadata.sources.length > 16) throw new Error("Invalid dictionary sources");
    const decoded = zstdDecompressSync(bytes.subarray(headerBytes + descriptionBytes, directoryOffset),
      { maxOutputLength: maximumDecodedBytes });
    if (decoded.length !== indexBytes) throw new Error("Dictionary index length mismatch");
    const index = new Reader(decoded);
    if (index.u32() !== pageSize || index.u32() !== Math.ceil(entries / pageSize)) throw new Error("Invalid word pages");
    this.checkpoints = Array.from({ length: Math.ceil(entries / pageSize) }, () => index.u32());
    this.words = index.take(index.u32());
    if (index.u32() !== entries) throw new Error("Invalid anagram index length");
    this.anagrams = Array.from({ length: entries }, () => index.u32());
    if (index.u32() !== dailyEntries) throw new Error("Invalid daily index length");
    this.daily = Array.from({ length: dailyEntries }, () => index.u32());
    index.end();
    if (this.checkpoints.some((offset, i) => offset >= this.words.length || (i && offset <= this.checkpoints[i - 1]))
      || this.daily.some((value) => value >= entries) || new Set(this.daily).size !== dailyEntries
      || this.anagrams.some((value) => value >= entries) || new Set(this.anagrams).size !== entries) {
      throw new Error("Dictionary index contains invalid positions");
    }
    let end = 0;
    for (let i = 0; i < blockCount; i++) {
      const offset = directoryOffset + i * 12;
      const start = bytes.readUInt32LE(offset);
      const length = bytes.readUInt32LE(offset + 4);
      const decodedLength = bytes.readUInt32LE(offset + 8);
      if (start !== end || !length || decodedLength > blockLimit) throw new Error("Invalid block directory");
      end += length;
    }
    if (end !== payloadBytes) throw new Error("Truncated block data");
    this.cachedPage = -1;
    this.cachedBlock = -1;
  }

  word(ordinal) {
    if (!Number.isInteger(ordinal) || ordinal < 0 || ordinal >= this.entries) throw new RangeError("Invalid word ordinal");
    const page = Math.floor(ordinal / pageSize);
    if (this.cachedPage !== page) {
      const reader = new Reader(this.words.subarray(this.checkpoints[page],
        this.checkpoints[page + 1] ?? this.words.length));
      let previous = Buffer.alloc(0);
      this.page = [];
      for (let i = page * pageSize; i < Math.min(this.entries, (page + 1) * pageSize); i++) {
        const prefix = reader.varint();
        if (prefix > previous.length) throw new Error("Invalid front-coded word prefix");
        const suffix = reader.take(reader.varint());
        previous = Buffer.concat([previous.subarray(0, prefix), suffix]);
        const key = previous.toString("utf8");
        if (key.indexOf("\x1f") < 0) throw new Error("Invalid word lookup key");
        this.page.push(key);
      }
      reader.end();
      this.cachedPage = page;
    }
    return this.page[ordinal % pageSize];
  }

  entry(ordinal) {
    const key = this.word(ordinal);
    const block = Math.floor(ordinal / this.blockSize);
    if (this.cachedBlock !== block) {
      const pointer = this.directoryOffset + block * 12;
      const start = this.dataOffset + this.bytes.readUInt32LE(pointer);
      const length = this.bytes.readUInt32LE(pointer + 4);
      const expected = this.bytes.readUInt32LE(pointer + 8);
      this.block = zstdDecompressSync(this.bytes.subarray(start, start + length), { maxOutputLength: maximumBlockBytes });
      if (this.block.length !== expected) throw new Error("Dictionary block length mismatch");
      this.cachedBlock = block;
    }
    const count = Math.min(this.blockSize, this.entries - block * this.blockSize);
    const index = ordinal % this.blockSize;
    const start = this.block.readUInt32LE(index * 4);
    const end = this.block.readUInt32LE((index + 1) * 4);
    const base = (count + 1) * 4;
    if (end < start || base + end > this.block.length) throw new Error("Invalid definition offsets");
    const record = JSON.parse(this.block.subarray(base + start, base + end).toString("utf8"));
    if (!Array.isArray(record) || record.length !== 4 || typeof record[1] !== "string"
      || !Array.isArray(record[2]) || !record[2].length || record[2].length > 64
      || !Array.isArray(record[3]) || record[3].length > 64 || record[3].some((word) => typeof word !== "string")) {
      throw new Error("Invalid dictionary record");
    }
    const decodeSources = (mask) => {
      if (!Number.isInteger(mask) || mask <= 0 || mask >= 2 ** this.metadata.sources.length) throw new Error("Invalid dictionary source mask");
      return this.metadata.sources.filter((_, i) => mask & (1 << i)).map((source) => source.id);
    };
    const sourceIds = decodeSources(record[0]);
    const pronunciation = record[1];
    const senses = record[2].map((sense) => {
      if (!Array.isArray(sense) || (sense.length !== 3 && sense.length !== 4) ||
          typeof sense[2] !== "string") throw new Error("Invalid dictionary sense");
      const part = parts[sense[0]];
      if (!part || !Number.isInteger(sense[0])) throw new Error("Invalid dictionary part of speech");
      const result = { partOfSpeech: part, sourceIds: decodeSources(sense[1]), definition: sense[2] };
      if (sense.length === 4) {
        if (!Array.isArray(sense[3]) || sense[3].length > 3 ||
            sense[3].some((text) => typeof text !== "string" || !text.trim() || text.length > 600)) {
          throw new Error("Invalid dictionary usage examples");
        }
        result.examples = sense[3];
      }
      return result;
    });
    const related = record[3];
    return { key, word: key.substring(key.indexOf("\x1f") + 1), pronunciation, senses, related, sourceIds };
  }
}
