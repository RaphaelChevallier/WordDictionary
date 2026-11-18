import SwiftUI

struct WordCardView: View {
    @Environment(AppModel.self) private var model
    @State private var translated = false
    var body: some View {
        Group {
            if let word = model.selectedEntry, !model.entryLoading {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 20) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(word.language.nativeName).readingFont(14, weight: .medium).foregroundStyle(AppPalette.secondary)
                            if let label = model.entryDateLabel { Text(label).readingFont(12).foregroundStyle(AppPalette.secondary) }
                            if model.entryBackfilled { Text(model.text("calendar_assigned_day")).readingFont(12).foregroundStyle(AppPalette.secondary) }
                        }
                        Text(word.word).readingFont(42, design: .serif).textSelection(.enabled).accessibilityIdentifier("entry-word")
                        PronunciationView(word: word)
                        HStack(spacing: 4) {
                            Button { model.toggleFavorite(word) } label: {
                                Image(systemName: model.favoriteIDs.contains(word.id) ? "star.fill" : "star").wordActionTarget()
                            }
                            .accessibilityLabel(model.text(model.favoriteIDs.contains(word.id) ? "unfavorite" : "favorite"))
                            .accessibilityIdentifier("word-favorite")
                            Button(model.text("listen")) { model.speak(word) }
                                .readingFont(14, weight: .medium).lineLimit(1).minimumScaleFactor(0.85).wordActionTarget().padding(.horizontal, 4)
                                .accessibilityIdentifier("word-listen")
                            Button(model.text("copy")) { model.copy(word) }
                                .readingFont(14, weight: .medium).lineLimit(1).minimumScaleFactor(0.85).wordActionTarget().padding(.horizontal, 4)
                                .accessibilityIdentifier("word-copy")
                            Menu {
                                Button(model.text("anki_export")) { model.exportEntries = [word] }
                                    .disabled(model.exporting).accessibilityIdentifier("anki-export")
                            } label: { Image(systemName: "ellipsis").rotationEffect(.degrees(90)).wordActionTarget() }
                                .accessibilityLabel(model.text("more_word_actions")).accessibilityIdentifier("word-more-actions")
                                .layoutPriority(1)
                        }.buttonStyle(.plain)
                        if model.entryArchived { Text(model.text("archived_note")).readingFont(14).foregroundStyle(AppPalette.secondary) }
                        DefinitionTranslationView(word: word, expanded: $translated).id("\(word.id):\(model.systemLanguageTag ?? "")")
                        if translated { AppSectionTitle(title: model.text("original_definition")) }
                        else if word.senses.count > 1 { AppSectionTitle(title: model.plural("meaning_count", word.senses.count)) }
                        Divider().overlay(AppPalette.outline)
                        ForEach(word.senses.indices, id: \.self) { index in
                            let sense = word.senses[index]
                            VStack(alignment: .leading, spacing: 8) {
                                let part = sense.partOfSpeech.hasPrefix("other:") ? String(sense.partOfSpeech.dropFirst(6)) : model.text("pos_\(sense.partOfSpeech)")
                                Text("\(index + 1). \(part)").readingFont(14, weight: .medium)
                                Text(sense.definition).readingFont().textSelection(.enabled)
                                if !sense.examples.isEmpty {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(model.text("usage_examples")).readingFont(13, weight: .medium).foregroundStyle(AppPalette.secondary)
                                        ForEach(Array(sense.examples.enumerated()), id: \.offset) { _, example in
                                            Text(example).readingFont(16).italic().textSelection(.enabled)
                                        }
                                    }.padding(.leading, 12)
                                }
                            }.accessibilityIdentifier("original-sense-\(index)")
                        }
                        if !word.related.isEmpty {
                            AppSectionTitle(title: model.text("related_words"))
                            ForEach(word.related, id: \.self) { term in
                                Button(term) { model.related(term, language: word.language) }.readingFont().wordActionTarget()
                            }
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(24)
                }.accessibilityIdentifier("entry-screen")
            } else if model.entryLoading { ProgressView(model.text("loading_dictionary")) }
            else { Text(model.text("error_entry")).readingFont().padding(24) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(AppPalette.surface.ignoresSafeArea())
        .navigationTitle(model.text("app_name"))
        .onChange(of: model.systemLanguageTag) { _, _ in translated = false }
        .onDisappear { model.speech.stop() }
    }
}

struct PronunciationView: View {
    @Environment(AppModel.self) private var model
    let word: WordEntry
    var body: some View {
        if word.language == .english && !word.pronunciationGuide.isEmpty {
            Text(model.format("pronunciation_guide", word.pronunciationGuide)).readingFont(16).foregroundStyle(AppPalette.secondary)
        }
    }
}
