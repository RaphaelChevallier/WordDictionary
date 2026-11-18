import SwiftUI
import UniformTypeIdentifiers

extension UTType {
    static let ankiPackage = UTType(importedAs: "net.ankiweb.apkg", conformingTo: .zip)
}

private struct AnkiDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.ankiPackage] }
    let url: URL
    init(url: URL) { self.url = url }
    init(configuration: ReadConfiguration) throws {
        throw PlatformFailure.message("Import this deck in Anki, not in MaxFluency.")
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        try FileWrapper(url: url, options: .immediate)
    }
}

struct AnkiExportView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var profile = ""
    @State private var document: AnkiDocument?
    @State private var showExporter = false
    @State private var temporary: URL?
    @State private var sending = false
    let words: [WordEntry]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(model.text("anki_export_description")).readingFont(14)
                    Button(model.text("anki_save_deck")) {
                        Task {
                            guard let url = await model.exportDeck(words) else { return }
                            temporary = url; document = AnkiDocument(url: url); showExporter = true
                        }
                    }.wordActionTarget().disabled(model.exporting || sending)
                    if model.exporting || sending { ProgressView() }
                    if words.count == 1 {
                        Divider()
                        Text(model.text("anki_mobile_setup")).readingFont(14)
                        Text(model.text("anki_not_installed")).readingFont(12).foregroundStyle(AppPalette.secondary)
                        TextField(model.text("anki_profile"), text: $profile)
                            .textFieldStyle(.roundedBorder).textInputAutocapitalization(.never).autocorrectionDisabled()
                        Button(model.text("anki_send_direct")) {
                            sending = true
                            Task {
                                defer { sending = false }
                                do {
                                    try await AnkiMobile.send(words[0], profile: profile)
                                    model.notice = model.text("anki_mobile_opened")
                                } catch { model.report(error, "anki_export_failed") }
                            }
                        }.wordActionTarget().disabled(model.exporting || sending)
                    }
                }.padding(24).frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationTitle(model.plural("anki_export_title", words.count)).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(model.text("cancel")) { dismiss() }.disabled(model.exporting) } }
            .fileExporter(isPresented: $showExporter, document: document, contentType: .ankiPackage, defaultFilename: "MaxFluency.apkg") { result in
                switch result {
                case .success: model.notice = model.text("anki_file_saved")
                case .failure(let error):
                    let failure = error as NSError
                    if !(failure.domain == NSCocoaErrorDomain && failure.code == CocoaError.Code.userCancelled.rawValue) {
                        model.report(error, "anki_export_failed")
                    }
                }
                cleanup()
            }
        }
        .background(AppPalette.surface)
        .preferredColorScheme(model.settings.theme.colorScheme)
        .onDisappear { if !showExporter { cleanup() } }
    }

    private func cleanup() {
        guard let url = temporary else { return }
        let directory = url.deletingLastPathComponent().standardizedFileURL
        if directory.lastPathComponent.hasPrefix("maxfluency-anki-"),
           directory.deletingLastPathComponent() == FileManager.default.temporaryDirectory.standardizedFileURL {
            FileCleanup.remove(directory)
        }
        temporary = nil; document = nil
    }
}
