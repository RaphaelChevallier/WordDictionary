import { createHash } from "node:crypto";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const target = join(root, "content", "pronunciations");
const revision = "74790861f652b15e4ac49015a90074ad62a27690";
const sourceHash = "81917843c7f44ce2b094ac63873c2c7a4cf802040792c455ba3ca406891c3d22";
const licenseHash = "bd4ce8e44170a5f9f481310ca85c51de3c4f851a65e679b40e603b143bd3542a";
const sourceBase = `https://raw.githubusercontent.com/cmusphinx/cmudict/${revision}`;
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const vowels = new Set("AA AE AH AO AW AY EH ER EY IH IY OW OY UH UW".split(" "));
const consonants = {
  B: "b", CH: "ch", D: "d", DH: "th", F: "f", G: "g", HH: "h", JH: "j",
  K: "k", L: "l", M: "m", N: "n", NG: "ng", P: "p", R: "r", S: "s",
  SH: "sh", T: "t", TH: "th", V: "v", W: "w", Y: "y", Z: "z", ZH: "zh",
};
const checked = new Set(["AE", "EH", "IH", "AH", "UH"]);
const onsets = new Set([
  ...Object.keys(consonants).filter((phone) => phone !== "NG"),
  "P R", "B R", "T R", "D R", "K R", "G R", "F R", "TH R", "SH R",
  "P L", "B L", "K L", "G L", "F L", "S L", "S M", "S N", "S P", "S T",
  "S K", "S F", "S W", "T W", "D W", "K W", "G W", "HH W",
  "S P R", "S T R", "S K R", "S P L", "S K L", "S K W",
  ...["P", "B", "T", "D", "K", "G", "F", "V", "TH", "M", "N", "HH", "L"].map((p) => `${p} Y`),
]);

export function respellPronunciation(value) {
  const phones = value.trim().split(/\s+/).map((text) => {
    const match = /^([A-Z]+)([012])?$/.exec(text);
    if (!match || (vowels.has(match[1]) ? match[2] === undefined
      : consonants[match[1]] === undefined || match[2] !== undefined)) {
      throw new Error(`Unsupported CMU phoneme: ${text}`);
    }
    return { phone: match[1], stress: match[2] };
  });
  if (phones.length > 128) throw new Error("CMU pronunciation exceeds the safety limit");
  const positions = phones.flatMap((phone, index) => vowels.has(phone.phone) ? [index] : []);
  if (!positions.length) return phones.map(({ phone }) => consonants[phone]).join("");
  const syllables = positions.map((position) => ({ onset: [], vowel: phones[position], coda: [] }));
  syllables[0].onset = phones.slice(0, positions[0]);
  for (let i = 0; i < positions.length - 1; i++) {
    const cluster = phones.slice(positions[i] + 1, positions[i + 1]);
    let onset = 0;
    for (let length = 1; length <= Math.min(cluster.length, 3); length++) {
      if (onsets.has(cluster.slice(-length).map(({ phone }) => phone).join(" "))) onset = length;
    }
    // Stressed short vowels keep a closing consonant rather than creating an open syllable.
    if (checked.has(syllables[i].vowel.phone) && syllables[i].vowel.stress !== "0" && cluster.length) {
      onset = Math.min(onset, cluster.length - 1);
    }
    syllables[i].coda = cluster.slice(0, cluster.length - onset);
    syllables[i + 1].onset = cluster.slice(cluster.length - onset);
  }
  syllables.at(-1).coda = phones.slice(positions.at(-1) + 1);
  return syllables.map(({ onset, vowel, coda }, index) => {
    const r = coda[0]?.phone === "R";
    const sounds = {
      AA: r ? "a" : "ah", AE: "a", AH: vowel.stress === "0" || !coda.length ? "uh" : "u",
      AO: r ? "o" : "aw", AW: "ow", AY: onset.length ? "y" : "eye",
      EH: r ? "ai" : coda.length ? "e" : "eh", ER: vowel.stress === "0" ? "er" : "ur",
      EY: "ay", IH: coda.length ? "i" : "ih", IY: "ee", OW: "oh", OY: "oy",
      UH: r ? "oo" : "uu", UW: "oo",
    };
    let ending = coda.map(({ phone }) => consonants[phone]).join("");
    const silentE = vowel.phone === "AY" && onset.length && coda.length === 1 &&
      new Set(["B", "D", "F", "K", "L", "M", "N", "P", "S", "T", "V", "Z"]).has(coda[0].phone);
    if (silentE && coda[0].phone === "S") ending = "c";
    else if (index === syllables.length - 1 && coda.length === 1 && coda[0].phone === "S") ending = "ss";
    const text = onset.map(({ phone }) => consonants[phone]).join("") + sounds[vowel.phone] + ending + (silentE ? "e" : "");
    return vowel.stress === "1" ? text.toUpperCase() : text;
  }).join("-");
}

export function parseCmuDictionary(text) {
  const variants = new Map();
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.split("#", 1)[0].trim();
    if (!line) continue;
    const match = /^(\S+)\s+(.+)$/.exec(line);
    if (!match) throw new Error("Invalid CMU dictionary entry");
    const word = match[1].replace(/\(\d+\)$/, "").toLowerCase();
    if (!/^[a-z][a-z'.-]{0,127}$/.test(word)) continue;
    const guide = respellPronunciation(match[2]);
    const current = variants.get(word) ?? [];
    if (!current.includes(guide)) current.push(guide);
    variants.set(word, current);
  }
  return new Map([...variants].map(([word, guides]) => [word, guides.slice(0, 2).join(" / ")]));
}

export function buildGuideAsset(entries) {
  if (!entries.size || entries.size > 250_000) throw new Error("Invalid pronunciation entry count");
  const ordered = [...entries].sort(([a], [b]) => Buffer.compare(Buffer.from(a), Buffer.from(b)));
  const records = ordered.map(([word, guide]) => {
    if (!/^[a-z][a-z'.-]{0,127}$/.test(word) || !/^[a-zA-Z -]+(?: \/ [a-zA-Z -]+)?$/.test(guide)) {
      throw new Error("Invalid pronunciation guide record");
    }
    const record = Buffer.from(`${word}\t${guide}`, "utf8");
    if (record.length > 1024) throw new Error("Pronunciation record exceeds the safety limit");
    return record;
  });
  const header = Buffer.alloc(24);
  header.write("MFPR0001", "ascii");
  header.writeUInt32LE(records.length, 8);
  header.writeUInt32LE((records.length + 1) * 4, 12);
  header.writeUInt32LE(records.reduce((sum, record) => sum + record.length, 0), 16);
  header.writeUInt32LE(1024, 20);
  const offsets = Buffer.alloc((records.length + 1) * 4);
  let offset = 0;
  records.forEach((record, i) => { offsets.writeUInt32LE(offset, i * 4); offset += record.length; });
  offsets.writeUInt32LE(offset, records.length * 4);
  return Buffer.concat([header, offsets, ...records]);
}

async function cachedSource(name, expected) {
  const path = join(root, ".cache", "pronunciations", `cmudict-${revision}.${name === "cmudict.dict" ? "dict" : "LICENSE"}`);
  let data;
  try { data = await readFile(path); }
  catch (error) {
    if (error.code !== "ENOENT") throw error;
    const response = await fetch(`${sourceBase}/${name}`, { signal: AbortSignal.timeout(30_000) });
    if (!response.ok) throw new Error(`CMUdict source failed: ${response.status}`);
    data = Buffer.from(await response.arrayBuffer());
    if (data.length > 16_777_216) throw new Error("CMUdict source exceeds its size limit");
    await mkdir(join(root, ".cache", "pronunciations"), { recursive: true });
    await writeFile(path, data);
  }
  if (hash(data) !== expected) throw new Error(`Pinned CMUdict ${name} hash mismatch`);
  return data;
}

async function main() {
  const action = process.argv[2] ?? "prepare";
  const builderSha256 = hash(await readFile(fileURLToPath(import.meta.url)));
  if (action === "generate") {
    const source = await cachedSource("cmudict.dict", sourceHash);
    const license = await cachedSource("LICENSE", licenseHash);
    const entries = parseCmuDictionary(source.toString("utf8"));
    const bytes = buildGuideAsset(entries);
    const sha256 = hash(bytes);
    const manifest = {
      schemaVersion: 1, language: "en", file: `en-${sha256.slice(0, 16)}.mfpr`,
      entries: entries.size, bytes: bytes.length, sha256, builderSha256,
      source: { name: "CMUdict", revision, url: `${sourceBase}/cmudict.dict`, sha256: sourceHash,
        license: "CMUDICT-LICENSE.txt", licenseSha256: licenseHash },
      modifications: "Approximate North American English respellings; primary stress capitalized; up to two source-listed variants.",
    };
    await mkdir(target, { recursive: true });
    await writeFile(join(target, manifest.file), bytes);
    await writeFile(join(root, "content", "licenses", manifest.source.license), license);
    let old;
    try { old = JSON.parse(await readFile(join(target, "manifest.json"), "utf8")); }
    catch (error) { if (error.code !== "ENOENT") throw error; }
    await writeFile(join(target, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
    if (old && old.file !== manifest.file && /^en-[a-f0-9]{16}\.mfpr$/.test(old.file)) await rm(join(target, old.file));
    console.log(`Generated ${entries.size} English pronunciation guides (${bytes.length} bytes).`);
    for (const word of ["disaster", "beautiful", "night", "read", "colonel", "choir", "queue", "curiosity"]) {
      console.log(`${word}: ${entries.get(word) ?? "Listen only"}`);
    }
    return;
  }
  if (action !== "prepare") throw new Error("Use pronunciation.mjs prepare or generate");
  const manifest = JSON.parse(await readFile(join(target, "manifest.json"), "utf8"));
  if (manifest.schemaVersion !== 1 || manifest.language !== "en" || manifest.builderSha256 !== builderSha256 ||
      manifest.source.revision !== revision || !/^en-[a-f0-9]{16}\.mfpr$/.test(manifest.file)) {
    throw new Error("Pronunciation guides need regeneration: node tools/pronunciation.mjs generate");
  }
  const bytes = await readFile(join(target, manifest.file));
  if (bytes.length !== manifest.bytes || hash(bytes) !== manifest.sha256 ||
      hash(await readFile(join(root, "content", "licenses", manifest.source.license))) !== licenseHash) {
    throw new Error("Pinned pronunciation asset or license hash mismatch");
  }
  console.log(`Verified ${manifest.entries} pinned English pronunciation guides.`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
