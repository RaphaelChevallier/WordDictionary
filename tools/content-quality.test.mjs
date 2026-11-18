import assert from "node:assert/strict";
import test from "node:test";
import { cleanDefinition, selectUsageExamples } from "./content-quality.mjs";
import { sourceSenses } from "./lexicon.mjs";

test("definition formatting removes detached footnote markers but preserves source-quality warnings", () => {
  assert.equal(cleanDefinition("Une définition ^([2])."), "Une définition.");
  assert.equal(cleanDefinition("Un sens ^([note 1])."), "Un sens.");
  assert.equal(cleanDefinition("Una acepción.^([cita requerida])"), "Una acepción. (cita requerida)");
  assert.equal(cleanDefinition("Un pez. ^([definición imprecisa])"), "Un pez. (definición imprecisa)");
  assert.equal(cleanDefinition("Bigger than [[normal]."), "Bigger than normal.");
  assert.equal(cleanDefinition("A [[target|display]] and [[link]]."), "A display and link.");
  assert.equal(cleanDefinition("A mouth that is [[|bit]]."), "");
  assert.equal(cleanDefinition("."), "");
  assert.equal(cleanDefinition("(colloquial) ...because it is known."), "(colloquial) ...because it is known.");
});

test("usage selection rejects embedded citations and raw markup and prefers full sentences over phrases", () => {
  assert.deepEqual(selectUsageExamples([
    "a useful phrase",
    "2014, An Author, A Published Work. A quoted passage.",
    "July 18, 2012, An Author, A quoted passage.",
    "A quotation. https://example.org/source",
    "{{exemple|A broken template",
    "This is a full example.",
    "  This is a full example. ",
    "Another complete example is retained.",
    "A third example stays visible.",
  ]), ["This is a full example.", "Another complete example is retained.", "A third example stays visible."]);
  assert.deepEqual(selectUsageExamples(["¡Qué calor!", "abandoned building"]), ["¡Qué calor!", "abandoned building"]);
});

test("nested source examples belong only to their leaf meaning and usage qualifiers remain visible", () => {
  const senses = sourceSenses({ lang_code: "fr", pos: "verb", senses: [{
    glosses: ["Un sens général.", "Un sens plus précis."], tags: ["figuratively"],
    examples: [{ text: "Cet exemple illustre le sens précis." }],
  }] });
  assert.equal(senses[0].definition, "(sens figuré) Un sens général.");
  assert.equal(senses[0].examples, undefined);
  assert.deepEqual(senses[1].examples, ["Cet exemple illustre le sens précis."]);
  const english = sourceSenses({ lang_code: "en", pos: "noun", senses: [{
    glosses: ["Terms relating to animals.", "A mammal."], examples: [{ text: "A mammal is here." }],
  }] });
  assert.equal(english.length, 1);
  assert.equal(english[0].definition, "A mammal.");
  assert.equal(sourceSenses({ lang_code: "es", pos: "verb", senses: [{
    glosses: ["Un significado regional."], tags: ["colloquial", "Chile"],
  }] })[0].definition, "(coloquial, Chile) Un significado regional.");
});
