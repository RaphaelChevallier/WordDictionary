import SwiftUI
import Translation
import Network
import Observation

struct TranslatedSense: Equatable {
    let definition: String
    let examples: [String]
}

struct TranslatedWord: Equatable {
    let word: String
    let senses: [TranslatedSense]
}

enum TranslationPayload {
    static func segments(_ entry: WordEntry) -> [(id: String, text: String)] {
        var result = [("word", entry.word)]
        for (index, sense) in entry.senses.enumerated() {
            result.append(("definition:\(index)", sense.definition))
            for (example, text) in sense.examples.enumerated() { result.append(("example:\(index):\(example)", text)) }
        }
        return result
    }

    static func assemble(entry: WordEntry, results: [String: String]) throws -> TranslatedWord {
        let expected = Set(segments(entry).map(\.id))
        guard Set(results.keys) == expected, results.values.allSatisfy({ !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }),
              let word = results["word"] else { throw PlatformFailure.message("Translation returned an incomplete or mismatched result.") }
        var senses: [TranslatedSense] = []
        for (index, source) in entry.senses.enumerated() {
            guard let definition = results["definition:\(index)"] else { throw PlatformFailure.message("A translated meaning is missing.") }
            var examples: [String] = []
            for example in source.examples.indices {
                guard let text = results["example:\(index):\(example)"] else { throw PlatformFailure.message("A translated example is missing.") }
                examples.append(text)
            }
            senses.append(TranslatedSense(definition: definition, examples: examples))
        }
        return TranslatedWord(word: word, senses: senses)
    }
}

@MainActor @Observable
private final class TranslationNetwork {
    var wifi = false
    private let monitor = NWPathMonitor()
    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let wifi = path.status == .satisfied && path.usesInterfaceType(.wifi)
            Task { @MainActor in self?.wifi = wifi }
        }
        monitor.start(queue: DispatchQueue(label: "com.maxfluency.translation-network"))
    }
    deinit { monitor.cancel() }
}

@MainActor
struct DefinitionTranslationView: View {
    @Environment(AppModel.self) private var model
    @State private var network = TranslationNetwork()
    @State private var configuration: TranslationSession.Configuration?
    @State private var translated: TranslatedWord?
    @State private var busy = false
    @State private var confirmDownload = false
    @State private var downloadAllowed = false
    @State private var generation = UUID()
    let word: WordEntry
    @Binding var expanded: Bool

    private var target: String? {
        guard let tag = model.systemLanguageTag, let code = Locale.Language(identifier: tag).languageCode?.identifier,
              code != word.language.rawValue else { return nil }
        return tag
    }
    private var targetName: String {
        target.flatMap { model.locale.localizedString(forIdentifier: $0) } ?? model.text("system_language")
    }

    var body: some View {
        if target != nil {
            VStack(alignment: .leading, spacing: 12) {
                Button {
                    if translated != nil { expanded.toggle() }
                    else { begin() }
                } label: {
                    HStack(spacing: 8) {
                        if busy { ProgressView().controlSize(.small) }
                        Text(model.format(busy ? "preparing_definition_translation" : expanded ? "hide_definition_translation" : "show_definition_translation", targetName))
                            .readingFont(14, weight: .medium).multilineTextAlignment(.leading)
                    }.wordActionTarget()
                }.buttonStyle(.plain).disabled(busy).accessibilityIdentifier("definition-translation")
                if expanded, let translated {
                    AppSectionTitle(title: model.format("translated_definition_title", targetName))
                    Text(translated.word).readingFont(27, design: .serif).textSelection(.enabled)
                    ForEach(translated.senses.indices, id: \.self) { index in
                        let sense = translated.senses[index]
                        VStack(alignment: .leading, spacing: 6) {
                            Text("\(index + 1). \(sense.definition)").readingFont().textSelection(.enabled)
                            ForEach(sense.examples.indices, id: \.self) { example in
                                Text(sense.examples[example]).readingFont(16).italic().textSelection(.enabled).padding(.leading, 12)
                            }
                        }
                    }
                    Text(model.text("translation_provider")).readingFont(12).foregroundStyle(AppPalette.secondary)
                    Text(model.text("machine_translation_note")).readingFont(12).foregroundStyle(AppPalette.secondary)
                }
            }
            .confirmationDialog(model.text("translation_download_title"), isPresented: $confirmDownload, titleVisibility: .visible) {
                Button(model.text("download_action")) {
                    guard network.wifi else { model.problem = model.text("translation_wifi"); return }
                    downloadAllowed = true; configure()
                }
                Button(model.text("cancel"), role: .cancel) {}
            } message: { Text(model.text("translation_download_message")) }
            .translationTask(configuration) { session in await translate(using: session) }
            .onChange(of: word) { _, _ in reset() }
            .onChange(of: model.systemLanguageTag) { _, _ in reset() }
        }
    }

    private func reset() {
        generation = UUID(); configuration = nil; translated = nil
        busy = false; expanded = false; downloadAllowed = false
    }

    private func begin() {
        guard !busy, let target else { return }
        busy = true
        let token = generation
        Task {
            let status = await LanguageAvailability().status(from: Locale.Language(identifier: word.language.rawValue),
                                                              to: Locale.Language(identifier: target))
            guard token == generation, !Task.isCancelled else { return }
            switch status {
            case .installed: downloadAllowed = false; configure()
            case .supported: busy = false; confirmDownload = true
            case .unsupported: busy = false; model.problem = model.format("translation_unsupported", targetName)
            @unknown default:
                busy = false
                model.report(PlatformFailure.message("The system returned an unknown translation availability state."), "translation_failed")
            }
        }
    }

    private func configure() {
        guard let target else { return }
        busy = true
        if configuration == nil {
            configuration = TranslationSession.Configuration(source: Locale.Language(identifier: word.language.rawValue),
                                                              target: Locale.Language(identifier: target))
        } else { configuration?.invalidate() }
    }

    private func translate(using session: TranslationSession) async {
        let token = generation
        do {
            guard let target else { throw CancellationError() }
            if !downloadAllowed {
                let status = await LanguageAvailability().status(from: Locale.Language(identifier: word.language.rawValue),
                                                                  to: Locale.Language(identifier: target))
                guard status == .installed else {
                    throw PlatformFailure.message("Offline languages changed. Request translation again to confirm any required download.")
                }
            } else {
                guard network.wifi else { throw PlatformFailure.message(model.text("translation_wifi")) }
                try await session.prepareTranslation()
            }
            let segments = TranslationPayload.segments(word)
            var results: [String: String] = [:]
            for start in stride(from: 0, to: segments.count, by: 32) {
                try Task.checkCancellation()
                let batch = segments[start..<min(start + 32, segments.count)]
                let responses = try await session.translations(from: batch.map {
                    TranslationSession.Request(sourceText: $0.text, clientIdentifier: $0.id)
                })
                guard responses.count == batch.count else { throw PlatformFailure.message("Translation segment count does not match.") }
                for response in responses {
                    guard let id = response.clientIdentifier, results[id] == nil else {
                        throw PlatformFailure.message("Translation returned duplicate or unidentified segments.")
                    }
                    results[id] = response.targetText
                }
            }
            let complete = try TranslationPayload.assemble(entry: word, results: results)
            guard token == generation else { return }
            translated = complete; expanded = true; busy = false
        } catch is CancellationError {
            if token == generation { busy = false }
        } catch {
            guard token == generation else { return }
            busy = false; model.report(error, "translation_failed")
        }
    }
}
