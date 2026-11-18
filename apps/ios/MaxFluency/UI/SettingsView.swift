import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var downloadLanguage: DictionaryLanguage?
    @State private var removeLanguage: DictionaryLanguage?
    private var reminderDate: Binding<Date> {
        Binding(get: { Date(timeIntervalSince1970: TimeInterval(model.settings.reminderMinute * 60)) }, set: { date in
            var calendar = Calendar(identifier: .gregorian); calendar.timeZone = .gmt
            let minute = calendar.component(.hour, from: date) * 60 + calendar.component(.minute, from: date)
            model.updateSettings { $0.reminderMinute = minute }
        })
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                choice(model.text("home_view")) {
                    Picker(model.text("home_view"), selection: setting(\.homeView)) {
                        Text(model.text("home_word_garden")).tag(HomeView.garden)
                        Text(model.text("home_word_of_day")).tag(HomeView.wordOfDay)
                    }
                }
                AppSectionTitle(title: model.text("languages"))
                choice(model.text("dictionary_language")) {
                    Picker(model.text("dictionary_language"), selection: setting(\.language)) {
                        ForEach(DictionaryLanguage.allCases) { Text($0.nativeName).tag($0) }
                    }
                }
                choice(model.text("interface_language")) {
                    Picker(model.text("interface_language"), selection: setting(\.interfaceLanguage)) {
                        Text(model.text("system_language")).tag("")
                        ForEach(DictionaryLanguage.allCases) { Text($0.nativeName).tag($0.rawValue) }
                    }
                }
                Text(model.text("language_independence")).readingFont(14).foregroundStyle(AppPalette.secondary)
                choice(model.text("vocabulary_level")) {
                    Picker(model.text("vocabulary_level"), selection: Binding(get: { model.currentLevel }, set: { level in
                        model.updateSettings { $0.levels[$0.language.rawValue] = level }
                    })) {
                        ForEach(VocabularyLevel.allCases, id: \.self) { Text(model.text("level_\($0.rawValue.lowercased())")).tag($0) }
                    }
                }
                Button(model.text("assessment_title")) { model.startAssessment() }.readingFont(14).wordActionTarget()
                AppSectionTitle(title: model.text("offline_dictionaries"))
                Text(model.text("keep_dictionary_open")).readingFont(12).foregroundStyle(AppPalette.secondary)
                ForEach(DictionaryLanguage.allCases) { language in dictionary(language) }
                AppSectionTitle(title: model.text("appearance"))
                choice(model.text("theme")) {
                    Picker(model.text("theme"), selection: setting(\.theme)) {
                        ForEach(AppTheme.allCases, id: \.self) { Text(model.text("theme_\($0.rawValue)")).tag($0) }
                    }
                }
                choice(model.text("accent")) {
                    Picker(model.text("accent"), selection: setting(\.accent)) {
                        ForEach(AppAccent.allCases, id: \.self) { value in
                            Text(model.text(value == .monochrome ? "accent_mono" : "accent_\(value.rawValue)")).tag(value)
                        }
                    }
                }
                choice(model.text("text_size")) {
                    Picker(model.text("text_size"), selection: setting(\.readingSize)) {
                        ForEach(ReadingSize.allCases, id: \.self) { Text(model.text("text_\($0.rawValue)")).tag($0) }
                    }
                }
                Button(model.text("data_licenses")) { model.push(.credits) }.readingFont(14).wordActionTarget()
                Text(model.text("translation_management")).readingFont(14).foregroundStyle(AppPalette.secondary)
                Text(model.text("privacy_note")).readingFont(14).foregroundStyle(AppPalette.secondary)
                VStack(alignment: .leading, spacing: 12) {
                    Toggle(model.text("daily_reminder"), isOn: Binding(get: { model.settings.reminderEnabled }, set: { model.enableReminders($0) }))
                        .readingFont().disabled(model.remindersBusy).accessibilityIdentifier("daily-reminder-toggle")
                    if model.settings.reminderEnabled {
                        DatePicker(model.text("daily_reminder"), selection: reminderDate, displayedComponents: .hourAndMinute)
                            .datePickerStyle(.compact).environment(\.timeZone, .gmt)
                        if model.remindersBusy { ProgressView() }
                        Button(model.text("reminder_system_settings")) { model.openSystemSettings() }.readingFont(14).wordActionTarget()
                    }
                    Text(model.text("reminder_note")).readingFont(12).foregroundStyle(AppPalette.secondary)
                    Text(model.text("background_refresh_note")).readingFont(12).foregroundStyle(AppPalette.secondary)
                }
            }
            .padding(24).frame(maxWidth: .infinity, alignment: .leading)
            .disabled(model.savingSettings || !model.storageReady)
        }
        .accessibilityIdentifier("settings-screen")
        .confirmationDialog(downloadLanguage.map { model.format("confirm_download", $0.nativeName) } ?? "",
                            isPresented: Binding(get: { downloadLanguage != nil }, set: { if !$0 { downloadLanguage = nil } }),
                            titleVisibility: .visible, presenting: downloadLanguage) { language in
            Button(model.text("download_action")) { model.download(language); downloadLanguage = nil }
            Button(model.text("cancel"), role: .cancel) { downloadLanguage = nil }
        } message: { language in
            Text(model.format("download_size", String(format: "%.1f", locale: model.locale, Double(PublicDictionaries.estimatedBytes(language)) / 1e6)))
        }
        .confirmationDialog(model.text("remove_dictionary"),
                            isPresented: Binding(get: { removeLanguage != nil }, set: { if !$0 { removeLanguage = nil } }),
                            titleVisibility: .visible, presenting: removeLanguage) { language in
            Button(model.text("remove_dictionary"), role: .destructive) { model.removeDownload(language); removeLanguage = nil }
            Button(model.text("cancel"), role: .cancel) { removeLanguage = nil }
        } message: { _ in Text(model.text("remove_dictionary_note")) }
    }

    private func setting<T>(_ path: WritableKeyPath<AppSettings, T>) -> Binding<T> {
        Binding(get: { model.settings[keyPath: path] }, set: { value in model.updateSettings { $0[keyPath: path] = value } })
    }

    private func choice<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) { Text(title).readingFont(); content().pickerStyle(.menu).labelsHidden() }
    }

    private func dictionary(_ language: DictionaryLanguage) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(language.nativeName).readingFont(22, design: .serif)
            if let progress = model.downloadProgress[language] {
                ProgressView(value: progress)
                Text(model.format(model.preparingDownloads.contains(language) ? "preparing_dictionary" : "downloading_dictionary", Int(progress * 100)))
                    .readingFont(12).foregroundStyle(AppPalette.secondary)
                Button(model.text("cancel_download")) { model.cancelDownload(language) }.readingFont(14).wordActionTarget()
            } else if let failure = model.packFailures[language] {
                Text(failure).readingFont(14).foregroundStyle(.red)
                Button(model.text("remove_dictionary")) { removeLanguage = language }.readingFont(14).wordActionTarget()
            } else if let pack = model.packs[language] {
                Text(model.format("dictionary_installed", model.number(pack.entries))).readingFont(14).foregroundStyle(AppPalette.secondary)
                Button(model.text("remove_dictionary")) { removeLanguage = language }.readingFont(14).wordActionTarget()
            } else {
                Text(model.format("download_size", String(format: "%.1f", locale: model.locale, Double(PublicDictionaries.estimatedBytes(language)) / 1e6)))
                    .readingFont(12).foregroundStyle(AppPalette.secondary)
                Button(model.text("download_dictionary")) { downloadLanguage = language }.readingFont(14).wordActionTarget()
            }
        }.padding(.vertical, 4)
    }
}
