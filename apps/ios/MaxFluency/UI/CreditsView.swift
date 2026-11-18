import SwiftUI

struct CreditsView: View {
    @Environment(AppModel.self) private var model
    @State private var licences: String?
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                AppSectionTitle(title: model.text("credits"))
                Text(model.text("source_note")).readingFont(14)
                Button(model.text("cc_by_sa_license")) { model.openExternal("https://creativecommons.org/licenses/by-sa/4.0/") }.wordActionTarget()
                ForEach(creditedWords) { word in
                    AppSectionTitle(title: model.format("word_sources", word.word))
                    ForEach(word.sourceLinks) { source in
                        Button("\(model.text("source")) · \(source.title)") { model.openExternal(source.url.absoluteString) }
                            .readingFont(14).wordActionTarget()
                    }
                }
                if let manifest = model.manifest {
                    Text(model.format("data_version", manifest.releaseVersion)).readingFont()
                    Text(manifest.attribution).readingFont(14)
                    Text(manifest.modifications).readingFont(14)
                    ForEach(manifest.languages, id: \.language) { pack in
                        Text(model.format("pack_info", DictionaryLanguage(rawValue: pack.language)?.nativeName ?? pack.language,
                                          model.number(pack.entries), String(format: "%.1f", locale: model.locale, Double(pack.compressedBytes) / 1e6))).readingFont(14)
                    }
                }
                Text(model.text("pronunciation_guide_credit")).readingFont(14)
                Button(model.text("cmudict_source")) { model.openExternal("https://github.com/cmusphinx/cmudict") }.wordActionTarget()
                Text(model.text("translation_credit")).readingFont(14)
                Button("SwiftSoup · MIT") { model.openExternal("https://github.com/scinfu/SwiftSoup") }.wordActionTarget()
                AppSectionTitle(title: model.text("license_title"))
                if let licences { Text(licences).readingFont(12).textSelection(.enabled) }
                else { ProgressView() }
            }.padding(24).frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityIdentifier("credits-screen")
        .task {
            do {
                guard let resources = Bundle.main.resourceURL else { throw PlatformFailure.message("Licence resource folder is missing.") }
                let shared = resources.appendingPathComponent("licenses", isDirectory: true)
                var sections: [String] = []
                for name in ["CC-BY-SA-4.0.txt", "CMUDICT-LICENSE.txt"] {
                    sections.append(name + "\n\n" + (try String(contentsOf: shared.appendingPathComponent(name), encoding: .utf8)))
                }
                sections.append("SwiftSoup\n\n" + (try String(contentsOf: resources.appendingPathComponent("SwiftSoup-LICENSE.txt"), encoding: .utf8)))
                licences = sections.joined(separator: "\n\n")
            } catch { model.report(error, "error_dictionary") }
        }
    }
    private var creditedWords: [WordEntry] {
        var result: [WordEntry] = []
        if let latest = model.selectedEntry ?? model.saved.first?.entry { result.append(latest) }
        if let featured = model.daily?.featured, !result.contains(where: { $0.id == featured.id }) { result.append(featured) }
        return result
    }
}
