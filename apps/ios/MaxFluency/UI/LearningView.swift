import SwiftUI

struct LearningView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var dynamicType
    @FocusState private var focused: Bool
    let home: HomeView

    var body: some View {
        @Bindable var model = model
        GeometryReader { geometry in
            VStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 12) {
                        Menu {
                            ForEach(DictionaryLanguage.allCases) { language in
                                Button(language.nativeName) { model.updateSettings { $0.language = language } }
                            }
                        } label: {
                            HStack(spacing: 8) {
                                Text(focused || !model.query.isEmpty ? model.settings.language.rawValue.uppercased() : model.settings.language.nativeName)
                                    .readingFont(15, weight: .medium).lineLimit(1)
                                Image(systemName: "chevron.down").font(.system(size: 10, weight: .semibold))
                            }.padding(.horizontal, 12).frame(minHeight: 48)
                        }
                        .buttonStyle(.plain).disabled(model.savingSettings || model.loadingDaily)
                        .background(RoundedRectangle(cornerRadius: 14).stroke(AppPalette.outline))
                        .accessibilityIdentifier("dictionary-language")
                        HStack(spacing: 6) {
                            Image(systemName: "magnifyingglass").foregroundStyle(AppPalette.secondary)
                            TextField(model.text("search_hint"), text: $model.query)
                                .readingFont(15).focused($focused).textInputAutocapitalization(.never).autocorrectionDisabled()
                                .submitLabel(.search).accessibilityIdentifier("search-field")
                                .onSubmit { if model.mode == .words { model.lookupOnline() } else { focused = false } }
                            if !model.query.isEmpty {
                                Button { model.query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(AppPalette.secondary) }
                                    .wordActionTarget().accessibilityLabel(model.text("clear_search"))
                            }
                        }
                        .padding(.leading, 10).frame(minHeight: 48)
                        .background(RoundedRectangle(cornerRadius: 14).stroke(focused ? AppPalette.foreground : AppPalette.outline))
                    }
                    if focused || !model.query.isEmpty || model.mode == .anagrams {
                        HStack(spacing: 8) {
                            ForEach(SearchMode.allCases, id: \.self) { mode in
                                Button(model.text(mode == .words ? "search_words" : "search_anagrams")) {
                                    model.mode = mode
                                }.readingFont(14).padding(.horizontal, 12).frame(minHeight: 40)
                                    .background(model.mode == mode ? AppPalette.muted : .clear, in: RoundedRectangle(cornerRadius: 10))
                                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(AppPalette.outline))
                            }
                        }
                        Text(model.text(model.mode == .words ? "search_help" : "anagram_help"))
                            .readingFont(12).foregroundStyle(AppPalette.secondary)
                    }
                    if !model.query.isEmpty && model.mode == .words {
                        Button(model.text("online_lookup")) { focused = false; model.lookupOnline() }
                            .readingFont(14).wordActionTarget().disabled(model.onlineSearching)
                    }
                }.padding(.top, 8)
                ScrollView {
                    if !model.query.isEmpty {
                        searchResults
                    } else if let daily = model.daily {
                        if home == .garden {
                            let height = max(120, geometry.size.height - 128)
                            let scale: CGFloat = dynamicType.isAccessibilitySize ? 1.8 : CGFloat(model.settings.readingSize.scale)
                            let count = gridCount(width: geometry.size.width, height: height, scale: scale)
                            let words = Array(daily.grid.prefix(count))
                            WordGardenView(words: words, viewportHeight: height, viewportWidth: geometry.size.width - 40, round: daily.round)
                                .id("\(daily.date):\(model.settings.language.rawValue):\(model.currentLevel.rawValue):\(model.loadingDaily)")
                            Button {
                                focused = false
                                model.refreshGarden(visible: Set(words.map(\.id)))
                            } label: {
                                HStack(spacing: 8) {
                                    if model.refreshingGarden { ProgressView().controlSize(.small) }
                                    else { Image(systemName: "arrow.clockwise") }
                                    Text(model.text(model.refreshingGarden ? "garden_refreshing" : "garden_refresh")).readingFont(14, weight: .medium)
                                }.wordActionTarget()
                            }.buttonStyle(.plain).disabled(model.refreshingGarden || model.loadingDaily || words.isEmpty)
                                .accessibilityIdentifier("refresh-garden")
                        } else { FeaturedWordView(daily: daily).padding(.top, 2) }
                    } else { ProgressView(model.text("loading_dictionary")).padding(24) }
                }
                .scrollDismissesKeyboard(.interactively)
                .accessibilityIdentifier("today-screen")
            }.padding(.horizontal, 20)
        }
        .onChange(of: model.query) { _, _ in model.searchChanged() }
        .onChange(of: model.mode) { _, _ in model.searchChanged() }
    }

    private func gridCount(width: CGFloat, height: CGFloat, scale: CGFloat) -> Int {
        if height / max(1, scale) < 260 { return 8 }
        if height / max(1, scale) < 440 { return 12 }
        if width / max(1, scale) >= 600 { return 20 }
        if width / max(1, scale) >= 400 { return 18 }
        return 15
    }

    private var searchResults: some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            if model.mode == .anagrams && SearchKeys.anagram(model.query).isEmpty {
                Text(model.text("no_letters")).readingFont().padding(.vertical, 16)
            } else if model.searching || model.onlineSearching { ProgressView().padding(24) }
            else if model.matches.isEmpty { Text(model.text("no_results")).readingFont().padding(.vertical, 16) }
            else {
                ForEach(model.matches) { word in
                    WordListRow(word: word, favorite: nil)
                    Divider().overlay(AppPalette.outline)
                }
                if model.matches.count == 60 { Text(model.text("search_limit")).readingFont(12).padding(.vertical, 16) }
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct FeaturedWordView: View {
    @Environment(AppModel.self) private var model
    let daily: DailyWords
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ViewThatFits(in: .horizontal) {
                HStack { heading; Spacer(); date }
                VStack(alignment: .leading, spacing: 4) { heading; date }
            }
            Text(daily.featured.word).readingFont(36, design: .serif)
            PronunciationView(word: daily.featured)
            Button(model.text("listen_device_voice")) { model.speak(daily.featured) }
                .readingFont(14, weight: .medium).wordActionTarget().accessibilityIdentifier("daily-listen")
            if let sense = daily.featured.senses.first { Text(sense.definition).readingFont().lineLimit(4) }
            if daily.featuredMeaningCount > 1 {
                HStack(spacing: 4) {
                    Text(model.plural("more_meanings", daily.featuredMeaningCount - 1)).readingFont(12, weight: .medium)
                    Image(systemName: "chevron.right").font(.system(size: 10, weight: .medium))
                }
                .foregroundStyle(AppPalette.secondary)
                .accessibilityIdentifier("more-meanings")
            }
        }
        .padding(20).frame(maxWidth: .infinity, alignment: .leading)
        .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(AppPalette.outline))
        .contentShape(RoundedRectangle(cornerRadius: 20))
        .onTapGesture { model.openWord(id: daily.featured.id) }
        .accessibilityAction(named: Text(model.text("home_word_of_day"))) { model.openWord(id: daily.featured.id) }
        .accessibilityIdentifier("word-of-day")
    }
    private var heading: some View { Text(model.text("word_of_day")).readingFont(12, weight: .medium).tracking(1.4) }
    private var date: some View {
        Text(model.featuredDate, format: .dateTime.day().month(.wide).year().locale(model.locale)).readingFont(12)
    }
}

struct WordListRow: View {
    @Environment(AppModel.self) private var model
    let word: WordEntry
    let favorite: Bool?
    var body: some View {
        HStack(spacing: 8) {
            Button { model.openWord(id: word.id) } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(word.word).readingFont(23, design: .serif).foregroundStyle(AppPalette.foreground)
                    Text(word.language.nativeName).readingFont(12).foregroundStyle(AppPalette.secondary)
                }.frame(maxWidth: .infinity, minHeight: 64, alignment: .leading).contentShape(Rectangle())
            }.buttonStyle(.plain)
            if let favorite {
                Button { model.toggleFavorite(word) } label: {
                    Image(systemName: favorite ? "star.fill" : "star").wordActionTarget()
                }.buttonStyle(.plain).accessibilityLabel(model.text(favorite ? "unfavorite" : "favorite"))
            }
        }.padding(.vertical, 4).accessibilityIdentifier("word-row-\(word.id)")
    }
}
