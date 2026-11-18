import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { PackedLexicon } from "./packed-lexicon.mjs";
import { selectSpanishGains } from "./spanish-supplement.mjs";

const word = (word, definition) => ({
  word, pronunciation: "", senses: [{ partOfSpeech: "noun", definition, sourceIds: ["es-wiktionary"] }], related: [],
});

test("Spanish gains exclude form-only and explicitly imprecise source entries", () => {
  const entries = selectSpanishGains([
    word("aeromozo", "Persona que atiende a los pasajeros en un avión."),
    word("formas", "Forma del plural de forma."),
    word("verbo", "Infinitivo de un verbo."),
    word("ambiguo", "Cierto objeto. ^([definición imprecisa])"),
    word("sintexto", "^([cita requerida])"),
    word("obsoleto", "Grafía obsoleta de otra forma."),
    word("enclitico", "Deletreando (gerundio de deletrear), con un pronombre."),
    word("resguardarme", "Resguardar, con el enclítico me (pronombre)."),
    word("escuchame", "Escuchá (vos), con el enclítico me (pronombre)."),
  ]);
  assert.deepEqual(entries.map((entry) => entry.word), ["aeromozo"]);
  assert.equal(entries[0].senses[0].definition, "Persona que atiende a los pasajeros en un avión.");
});

test("Spanish gains do not duplicate bundled spellings or normalized source records", () => {
  const entries = selectSpanishGains([
    word("ya", "Un significado existente."),
    word("café", "Una definición nueva."),
    word("cafe\u0301", "Una definición nueva."),
  ], new Set(["ya"]));
  assert.equal(entries.length, 1);
  assert.equal(entries[0].word, "café");
});

test("the pinned Spanish delta is bundled once and does not change daily discovery", async () => {
  const root = fileURLToPath(new URL("../", import.meta.url));
  const supplement = JSON.parse(await readFile(join(root, "content", "supplements", "es-coverage.json"), "utf8"));
  const manifest = JSON.parse(await readFile(join(root, "content", "starter", "manifest.json"), "utf8"));
  const pack = manifest.languages.find((item) => item.language === "es");
  const dictionary = new PackedLexicon(await readFile(join(root, "content", "starter", "packs", pack.file)));
  const words = Array.from({ length: dictionary.entries }, (_, i) => dictionary.word(i).split("\x1f")[1]);
  const discovery = new Set(dictionary.daily.map((ordinal) => words[ordinal]));
  for (const entry of supplement.entries) {
    assert.equal(words.filter((word) => word === entry.word).length, 1, entry.word);
    assert.equal(discovery.has(entry.word), false, `${entry.word} needs separate daily curation`);
  }
  const aeromozo = dictionary.entry(words.indexOf("aeromozo"));
  assert.equal(aeromozo.senses[0].definition, "Persona que atiende a los pasajeros en un avión.");
  assert.deepEqual(aeromozo.sourceIds, ["es-wiktionary"]);
});
