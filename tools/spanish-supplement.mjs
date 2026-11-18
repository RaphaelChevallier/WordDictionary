import { createHash } from "node:crypto";
import { readFile, mkdir, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { PackedLexicon } from "./packed-lexicon.mjs";

const root = fileURLToPath(new URL("../", import.meta.url));
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");
const classifierText = (text) => text.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().trim();

export function usableSpanishSense(text) {
  const plain = classifierText(text);
  if (/imprecisa|falta (?:la )?definicion|sin definicion|cita requerida|https?:\/\/|¦/.test(plain) ||
      !/\p{L}/u.test(plain.replace(/\^\(\[[^\]]+\]\)/g, ""))) return false;
  return !/^(?:forma (?:de |del |femenin|masculin|plural|alternativa|flexiva)|(?:femenino|masculino)(?: singular| plural)? de |plural de |infinitivo de |grafia |uso pronominal de |(?:participio|gerundio)(?: pasado| irregular)? de |(?:primera|segunda|tercera) persona)/.test(plain) &&
    !/\((?:gerundio|participio|forma verbal) de |con (?:el |los )?enclitico/.test(plain);
}

export function selectSpanishGains(candidates, bundled = new Set()) {
  const result = [];
  const seen = new Set(bundled);
  for (const candidate of candidates) {
    const word = candidate.word.normalize("NFC").trim();
    if (!word || word.length > 100 || /\p{C}/u.test(word) || seen.has(word)) continue;
    const senses = candidate.senses.filter(({ definition }) => usableSpanishSense(definition));
    if (!senses.length) continue;
    const unique = [...new Map(senses.map((sense) =>
      [`${sense.partOfSpeech}\0${sense.definition.normalize("NFC")}`, sense])).values()];
    if (unique.length > 64) throw new Error("Spanish supplement exceeds the supported sense count");
    seen.add(word);
    result.push({
      word, pronunciation: candidate.pronunciation, senses: unique, related: candidate.related,
      sourceIds: ["es-wiktionary"],
    });
  }
  return result.sort((a, b) => Buffer.compare(Buffer.from(a.word), Buffer.from(b.word)));
}

async function main() {
  const audit = process.argv[2];
  if (!audit) throw new Error("Pass the completed native-parser Spanish coverage audit directory");
  const report = JSON.parse(await readFile(join(audit, "coverage-final.json"), "utf8"));
  const candidates = JSON.parse(await readFile(join(audit, "verified-headword-gains.json"), "utf8"));
  if (report.source.url !== "https://kaikki.org/eswiktionary/raw-wiktextract-data.jsonl.gz" ||
      candidates.length !== report.verifiedNewDefinedHeadwords) throw new Error("Unexpected Spanish audit inputs");
  const publisher = await readFile(join(root, ".cache", "public-dictionaries", "es.zip"));
  if (hash(publisher) !== report.readerArchiveSha256) throw new Error("The audited Spanish base archive changed");
  const manifest = JSON.parse(await readFile(join(root, "content", "starter", "manifest.json"), "utf8"));
  const pack = manifest.languages.find((item) => item.language === "es");
  const dictionary = new PackedLexicon(await readFile(join(root, "content", "starter", "packs", pack.file)));
  const bundled = new Set(Array.from({ length: dictionary.entries }, (_, i) => dictionary.word(i).split("\x1f")[1]));
  const previous = JSON.parse(await readFile(join(root, "content", "supplements", "es-coverage.json"), "utf8")
    .catch((error) => { if (error.code !== "ENOENT") throw error; return '{"entries":[]}'; }));
  previous.entries.forEach(({ word }) => bundled.delete(word));
  const entries = selectSpanishGains(candidates, bundled);
  const data = {
    schemaVersion: 1, language: "es", sourceEdition: "es", sourceId: "es-wiktionary",
    sourceUrl: report.source.url, sourceSha256: report.source.sha256,
    license: "CC-BY-SA-4.0", publisherArchiveSha256: report.readerArchiveSha256,
    methodology: "Native Spanish lookup meanings missing from every publisher spelling/alias. Excludes form-only references, explicitly imprecise or citation-needed definitions, malformed glosses, control-character spellings, and bundled duplicates. Includes lexical words, expressions and affixes, not all independent words. Uncertain sense-only differences are not automatically merged. Lookup only: daily discovery is unchanged pending separate curation.",
    auditCandidateHeadwords: candidates.length, entries,
  };
  await mkdir(join(root, "content", "supplements"), { recursive: true });
  const bytes = `${JSON.stringify(data, null, 2)}\n`;
  await writeFile(join(root, "content", "supplements", "es-coverage.json"), bytes);
  console.log(JSON.stringify({ entries: entries.length, bytes: Buffer.byteLength(bytes),
    lexicalSpellings: entries.filter((entry) => !entry.word.includes(" ") && !entry.word.startsWith("-")).length,
    affixes: entries.filter((entry) => entry.word.startsWith("-")).length,
    expressions: entries.filter((entry) => entry.word.includes(" ")).length,
    examples: entries.filter((entry) => /^[\p{Ll}][\p{L}'-]{2,19}$/u.test(entry.word)).slice(0, 20)
      .map((entry) => ({ word: entry.word, definition: entry.senses[0].definition })) }, null, 2));
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
