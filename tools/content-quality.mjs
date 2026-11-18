const numericReference = /\s*\^\(\[(?:\d+(?:[\s,;-]+\d+)*|note\s+\d+)\]\)/giu;
const annotation = /\s*\^\(\[([^\]]+)\]\)/gu;
const brokenMarkup = /\{\{|\}\}|\[\[|\]\]|\uFFFD|<\/?[a-z][^>]*>/iu;
const citationPrefix = /^(?:\d{3,4}(?:\s+[\p{L}-]+)?\s*[,:\u2014]|(?:\d{1,2}\s+)?(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+(?:\d{1,2},?\s+)?\d{4}\s*[,:\u2014])/iu;
const qualifiers = {
  en: {
    archaic: "archaic", obsolete: "obsolete", outdated: "dated", dated: "dated", rare: "rare",
    colloquial: "colloquial", familiar: "informal", informal: "informal", slang: "slang",
    vulgar: "vulgar", offensive: "offensive", derogatory: "derogatory", pejorative: "derogatory",
    figuratively: "figurative", figurative: "figurative", historical: "historical",
    nonstandard: "nonstandard", literary: "literary", poetic: "poetic", dialectal: "dialectal",
  },
  fr: {
    archaic: "archaïque", obsolete: "désuet", outdated: "vieilli", dated: "vieilli", rare: "rare",
    colloquial: "familier", familiar: "familier", informal: "familier", slang: "argot",
    vulgar: "vulgaire", offensive: "offensant", derogatory: "péjoratif", pejorative: "péjoratif",
    figuratively: "sens figuré", figurative: "sens figuré", historical: "historique",
    nonstandard: "non standard", literary: "littéraire", poetic: "poétique", dialectal: "dialectal",
  },
  es: {
    archaic: "arcaico", obsolete: "desusado", outdated: "anticuado", dated: "anticuado", rare: "raro",
    colloquial: "coloquial", familiar: "coloquial", informal: "coloquial", slang: "jerga",
    vulgar: "vulgar", offensive: "ofensivo", derogatory: "despectivo", pejorative: "despectivo",
    figuratively: "sentido figurado", figurative: "sentido figurado", historical: "histórico",
    nonstandard: "no estándar", literary: "literario", poetic: "poético", dialectal: "dialectal",
  },
};
const regions = {
  US: ["US", "États-Unis", "EE. UU."], UK: ["UK", "Royaume-Uni", "Reino Unido"],
  British: ["UK", "Royaume-Uni", "Reino Unido"], Canada: ["Canada", "Canada", "Canadá"],
  Australia: ["Australia", "Australie", "Australia"], Ireland: ["Ireland", "Irlande", "Irlanda"],
  France: ["France", "France", "Francia"], Quebec: ["Quebec", "Québec", "Quebec"],
  Spain: ["Spain", "Espagne", "España"], Mexico: ["Mexico", "Mexique", "México"],
  Argentina: ["Argentina", "Argentine", "Argentina"], Chile: ["Chile", "Chili", "Chile"],
  Colombia: ["Colombia", "Colombie", "Colombia"], Venezuela: ["Venezuela", "Venezuela", "Venezuela"],
  Cuba: ["Cuba", "Cuba", "Cuba"], Uruguay: ["Uruguay", "Uruguay", "Uruguay"],
  Bolivia: ["Bolivia", "Bolivie", "Bolivia"], Peru: ["Peru", "Pérou", "Perú"],
  Ecuador: ["Ecuador", "Équateur", "Ecuador"], Paraguay: ["Paraguay", "Paraguay", "Paraguay"],
  Honduras: ["Honduras", "Honduras", "Honduras"], Nicaragua: ["Nicaragua", "Nicaragua", "Nicaragua"],
  "Costa-Rica": ["Costa Rica", "Costa Rica", "Costa Rica"],
  "Puerto-Rico": ["Puerto Rico", "Porto Rico", "Puerto Rico"],
  "El-Salvador": ["El Salvador", "Salvador", "El Salvador"],
  "Río-de-la-Plata": ["Río de la Plata", "Río de la Plata", "Río de la Plata"],
};

export function cleanDefinition(value) {
  if (typeof value !== "string") return "";
  const text = value.normalize("NFC").replace(numericReference, "")
    .replace(annotation, " ($1)")
    .replace(/\[\[([^\[\]|]+)\|([^\[\]]+)\]\]/gu, "$2")
    .replace(/\[\[([^\[\]|]+)\]\]/gu, "$1")
    .replace(/\[\[([^\[\]|]+)\](?!\])/gu, "$1")
    .replace(/\s+/g, " ").replace(/\s+\.(?!\.)/g, ".").trim();
  return brokenMarkup.test(text) || !/[\p{L}\p{N}]/u.test(text) ? "" : text;
}

export function sourceLabels(tags, language) {
  const index = ["en", "fr", "es"].indexOf(language);
  return [...new Set((tags ?? []).map((tag) => qualifiers[language]?.[tag] ?? regions[tag]?.[index]).filter(Boolean))];
}

const knownLabels = new Set([...Object.values(qualifiers).flatMap((labels) => Object.values(labels)),
  ...Object.values(regions).flat()]);

export function definitionIdentity(value) {
  let text = cleanDefinition(value);
  while (true) {
    const label = /^\(([^()]+)\)\s*/u.exec(text);
    if (!label || !label[1].split(", ").every((item) => knownLabels.has(item))) return text;
    text = text.slice(label[0].length);
  }
}

export function selectUsageExamples(values) {
  return [...new Set(values.filter((value) => typeof value === "string")
    .map((value) => value.normalize("NFC").replace(/\s+/g, " ").trim())
    .filter((text) => text.length > 0 && text.length <= 600 &&
      !brokenMarkup.test(text) && !/https?:\/\/|www\./iu.test(text) && !citationPrefix.test(text)))]
    .map((text, index) => ({ text, index, sentence: /[.!?…][”"')»\]]?$/u.test(text) && text.split(/\s+/u).length >= 3 }))
    .sort((a, b) => Number(b.sentence) - Number(a.sentence) || a.index - b.index)
    .slice(0, 3).map(({ text }) => text);
}
