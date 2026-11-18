import Foundation
import SwiftSoup
import os

enum DictionaryHTML {
    private static let logger = Logger(subsystem: "com.maxfluency.app", category: "dictionary-content")

    static func part(_ label: String) -> String? {
        let text = SearchKeys.normalize(label).components(separatedBy: "|")[0].trimmingCharacters(in: .whitespacesAndNewlines)
        if text.contains("synonym") || text.contains("sinonim") { return nil }
        if text.contains("adverb") || text.contains("adverbial") { return "adv" }
        if text.contains("adject") || text.contains("adjetiv") { return "adj" }
        for (fragments, result) in [
            (["noun", "proper noun", "nom", "sustantiv", "forme de nom"], "noun"),
            (["verb", "forme de verbe", "forme verbale", "forma verbal"], "verb"),
            (["article", "articulo"], "article"), (["pron"], "pron"), (["determin"], "det"),
            (["prepos"], "prep"), (["conj"], "conj"), (["interj"], "intj"),
            (["prefix", "prefij"], "prefix"), (["suffix", "sufij"], "suffix"),
            (["phrase", "locution", "locucion"], "phrase"), (["proverb", "refran"], "proverb"),
            (["numer"], "num"), (["particul"], "particle"), (["contract"], "contraction")
        ] {
            if fragments.contains(where: { text.hasPrefix($0) }) { return result }
        }
        if ["symbol", "simbol", "letter", "lettre", "letra"].contains(where: { text.hasPrefix($0) }) { return "other:\(label)" }
        return nil
    }

    static func wiki(language: DictionaryLanguage, word: String, html: String) throws -> WordEntry? {
        let document = try SwiftSoup.parseBodyFragment(html)
        try document.select(".mw-editsection, script, style").remove()
        let heading: String
        switch language { case .english: heading = "english"; case .french: heading = "francais"; case .spanish: heading = "espanol" }
        var active = false
        var currentPart: String?
        var senses: [Sense] = []
        for element in try document.select("h2, h3, h4, h5, h6, ol, dl").array() {
            let tag = element.tagName()
            if tag == "h2" {
                active = SearchKeys.normalize(try element.text()) == heading
                currentPart = nil
            } else if active, tag.hasPrefix("h") {
                currentPart = part(try element.text())
            } else if active, let currentPart, tag == "ol", !ancestors(element).contains(where: { ["ol", "ul"].contains($0.tagName()) }) {
                for item in try element.select("li").array() where item.parent()?.tagName() == "ol" {
                    if let sense = try sense(item, part: currentPart) { senses.append(sense) }
                }
            } else if active, let currentPart, language == .spanish, tag == "dl" {
                let children = element.children().array()
                let numbered = try children.contains { child in
                    guard child.tagName() == "dt" else { return false }
                    return try child.text().first?.isNumber == true
                }
                if numbered {
                    for child in children where child.tagName() == "dd" {
                        if let sense = try sense(child, part: currentPart) { senses.append(sense) }
                    }
                }
            }
        }
        return make(language: language, word: word, senses: senses, downloaded: false)
    }

    static func starDict(language: DictionaryLanguage, word: String, html: String) throws -> WordEntry? {
        let document = try SwiftSoup.parseBodyFragment(html)
        guard let body = document.body() else { throw PlatformFailure.message("Dictionary HTML has no body.") }
        var currentPart: String?
        var synonyms = false
        var senses: [Sense] = []
        var related: [String] = []
        for element in body.children().array() {
            if element.tagName() == "p" {
                let label = try element.select("b").first()?.text() ?? ""
                let normalized = SearchKeys.normalize(label)
                synonyms = normalized.hasPrefix("synonym") || normalized.hasPrefix("sinonim")
                currentPart = label.isEmpty || synonyms ? nil : (part(label) ?? "other:\(label)")
            } else if element.tagName() == "ol" {
                for item in try element.select("li").array() where item.parent()?.tagName() == "ol" {
                    if synonyms {
                        let text = try definition(item)
                        if !text.isEmpty, !related.contains(text) { related.append(text) }
                    } else if let currentPart, let sense = try sense(item, part: currentPart) {
                        senses.append(sense)
                    }
                }
            }
        }
        return make(language: language, word: word, senses: senses, related: Array(related.prefix(16)), downloaded: true)
    }

    static func merge(_ entries: [WordEntry]) -> WordEntry? {
        guard var result = entries.first else { return nil }
        var senses: [Sense] = []
        for sense in entries.flatMap(\.senses) {
            if let index = senses.firstIndex(where: { $0.partOfSpeech == sense.partOfSpeech && $0.definition == sense.definition }) {
                let old = senses[index]
                senses[index] = Sense(partOfSpeech: old.partOfSpeech, definition: old.definition,
                    sourceIds: Array(Set(old.sourceIds + sense.sourceIds)).sorted(),
                    examples: safeExamples(old.examples + sense.examples))
            } else { senses.append(sense) }
        }
        result.senses = senses
        result.related = Array(entries.flatMap(\.related).reduce(into: [String]()) { if !$0.contains($1) { $0.append($1) } }.prefix(16))
        return result
    }

    private static func make(language: DictionaryLanguage, word: String, senses: [Sense], related: [String] = [], downloaded: Bool) -> WordEntry? {
        guard !senses.isEmpty else { return nil }
        let source = DictionarySource(id: downloaded ? "reader-dict" : "wiktionary", edition: language.rawValue,
                                      title: downloaded ? "Wiktionary / reader.dict" : "Wiktionary", license: "CC-BY-SA-4.0")
        let entry = WordEntry(id: "\(language.rawValue):\(word)", language: language, word: word,
                              pronunciation: "", senses: senses, related: related, sourceEdition: language.rawValue,
                              sources: [source], pronunciationGuide: "")
        return merge([entry])
    }

    private static func sense(_ element: Element, part: String) throws -> Sense? {
        let text = try definition(element)
        guard !text.isEmpty else { return nil }
        return Sense(partOfSpeech: part, definition: text, sourceIds: [], examples: try examples(element))
    }

    private static func ancestors(_ element: Element) -> [Element] {
        var result: [Element] = []
        var parent = element.parent()
        while let current = parent { result.append(current); parent = current.parent() }
        return result
    }

    private static func definition(_ element: Element) throws -> String {
        let copy = try SwiftSoup.parseBodyFragment(element.html())
        try copy.select("ol, ul, dl, table, script, style, .reference, .citation, .h-quotation, .mw-editsection, .example, .usage-example, .h-usage-example, .e-example").remove()
        return cleanDefinition(try copy.text())
    }

    static func plainText(_ html: String) throws -> String {
        try SwiftSoup.parseBodyFragment(html).text()
    }

    private static func exampleLabel(_ element: Element) throws -> Element? {
        guard let first = element.children().first(), ["b", "strong"].contains(first.tagName()),
              ["ejemplo:", "example:", "exemple :", "exemple:"].contains(SearchKeys.normalize(try first.text())) else { return nil }
        return first
    }

    private static func examples(_ element: Element) throws -> [String] {
        let classes = Set(["example", "usage-example", "h-usage-example", "e-example"])
        var result: [String] = []
        for example in try element.select(".example, .usage-example, .h-usage-example, .e-example, li").array() {
            let ownClasses = Set(try example.attr("class").split(separator: " ").map(String.init))
            if example.tagName() == "li", ownClasses.isDisjoint(with: classes), try exampleLabel(example) == nil { continue }
            var excluded = false
            for parent in ancestors(example) {
                if parent === element { break }
                let names = Set(try parent.attr("class").split(separator: " ").map(String.init))
                let label = try exampleLabel(parent)
                if ["ol", "blockquote"].contains(parent.tagName()) ||
                    !names.isDisjoint(with: classes.union(["h-quotation", "citation"])) || label != nil { excluded = true; break }
            }
            if excluded { continue }
            if try !example.select(".sources, .source, .reference, cite, .citation, .h-quotation, blockquote, .cita, .ref").isEmpty() { continue }
            let selected = try example.select(".e-example").first() ?? example
            let copy = try SwiftSoup.parseBodyFragment(selected.outerHtml())
            if let body = copy.body(), let root = body.children().first(), let label = try exampleLabel(root) { try label.remove() }
            try copy.select(".e-translation, .translation, rt, script, style, .mw-editsection").remove()
            result.append(try copy.text())
        }
        return safeExamples(result)
    }

    static func cleanDefinition(_ value: String) -> String {
        var text = value.precomposedStringWithCanonicalMapping
        for (pattern, replacement) in [
            ("\\s*\\^\\(\\[(?:\\d+(?:[\\s,;-]+\\d+)*|note\\s+\\d+)]\\)", ""),
            ("\\s*\\^\\(\\[([^]]+)]\\)", " ($1)"),
            ("\\[\\[([^\\[\\]|]+)\\|([^\\[\\]]+)]]", "$2"),
            ("\\[\\[([^\\[\\]|]+)]]", "$1"),
            ("\\[\\[([^\\[\\]|]+)](?!])", "$1"),
            ("\\s+", " "), ("\\s+\\.(?!\\.)", ".")
        ] { text = text.replacingOccurrences(of: pattern, with: replacement, options: .regularExpression) }
        text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard text.range(of: "\\{\\{|\\}\\}|\\[\\[|]]|\\uFFFD|</?[a-z][^>]*>", options: [.regularExpression, .caseInsensitive]) == nil,
              text.unicodeScalars.contains(where: { CharacterSet.alphanumerics.contains($0) }) else {
            logger.warning("Omitting a malformed or punctuation-only dictionary gloss.")
            return ""
        }
        return text
    }

    static func safeExamples(_ values: [String]) -> [String] {
        var seen = Set<String>()
        let valid = values.map { $0.precomposedStringWithCanonicalMapping.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && $0.count <= 600 && seen.insert($0).inserted &&
                $0.range(of: "\\{\\{|\\}\\}|\\[\\[|]]|\\uFFFD|https?://|www\\.|^\\d{3,4}(?:\\s+[\\p{L}-]+)?\\s*[,:\\u2014]|^(?:\\d{1,2}\\s+)?(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+(?:\\d{1,2},?\\s+)?\\d{4}\\s*[,:\\u2014]",
                         options: [.regularExpression, .caseInsensitive]) == nil }
        let sentences = valid.filter { $0.split(separator: " ").count >= 3 && $0.range(of: "[.!?…][”\"')»\\]]?$", options: .regularExpression) != nil }
        return Array((sentences + valid.filter { !sentences.contains($0) }).prefix(3))
    }
}
