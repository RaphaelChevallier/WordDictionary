import SwiftUI

struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @State private var history = false
    private var words: [SavedWord] { history ? Array(model.saved.prefix(100)) : model.saved.filter(\.favorite) }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                tab(model.text("favorites"), selected: !history) { history = false }
                tab(model.text("history"), selected: history) { history = true }
            }
            if !history && !words.isEmpty {
                Button(model.text("anki_export_saved")) { model.exportEntries = words.map(\.entry) }
                    .readingFont(14).wordActionTarget().disabled(model.exporting).padding(.horizontal, 20)
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if words.isEmpty {
                        Text(model.text(history ? "empty_history" : "empty_favorites")).readingFont().padding(.vertical, 24)
                    }
                    ForEach(words) { saved in
                        WordListRow(word: saved.entry, favorite: saved.favorite)
                        Divider().overlay(AppPalette.outline)
                    }
                }.padding(.horizontal, 20).padding(.vertical, 16)
            }
        }.accessibilityIdentifier("library-screen")
    }

    private func tab(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).readingFont(14, weight: selected ? .semibold : .regular).frame(maxWidth: .infinity, minHeight: 48)
                .foregroundStyle(selected ? AppPalette.foreground : AppPalette.secondary)
                .overlay(alignment: .bottom) { if selected { Rectangle().fill(AppPalette.foreground).frame(height: 2) } }
        }.buttonStyle(.plain)
    }
}
