import SwiftUI

struct CalendarView: View {
    @Environment(AppModel.self) private var model
    private var calendar: Calendar { var value = Calendar(identifier: .gregorian); value.locale = model.locale; return value }
    private var weekdays: [String] {
        let names = calendar.shortStandaloneWeekdaySymbols
        let offset = calendar.firstWeekday - 1
        return (0..<7).map { names[($0 + offset) % 7] }
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Picker(model.text("dictionary_language"), selection: Binding(get: { model.calendarLanguage }, set: {
                        model.calendarLanguage = $0; model.calendarSelected = nil; model.loadCalendar()
                    })) {
                        ForEach(DictionaryLanguage.allCases) { Text($0.nativeName).tag($0) }
                    }.pickerStyle(.menu).accessibilityIdentifier("calendar-language")
                    Spacer()
                    Picker(model.text("vocabulary_level"), selection: Binding(get: { model.calendarLevel }, set: {
                        model.calendarLevel = $0; model.calendarSelected = nil; model.loadCalendar()
                    })) {
                        ForEach(VocabularyLevel.allCases, id: \.self) { Text(model.text("level_\($0.rawValue.lowercased())")).tag($0) }
                    }.pickerStyle(.menu).accessibilityIdentifier("calendar-level")
                }
                HStack {
                    Button { model.moveCalendar(-1) } label: { Image(systemName: "chevron.left").wordActionTarget() }
                        .accessibilityLabel(model.text("previous_month"))
                        .disabled(calendar.component(.year, from: model.calendarDate) == 1 && calendar.component(.month, from: model.calendarDate) == 1)
                    Spacer()
                    Text(model.calendarDate, format: .dateTime.month(.wide).year().locale(model.locale)).readingFont(20, design: .serif)
                    Spacer()
                    Button { model.moveCalendar(1) } label: { Image(systemName: "chevron.right").wordActionTarget() }
                        .accessibilityLabel(model.text("next_month"))
                        .disabled(LocalDay.month(LocalDay.string(model.calendarDate)) >= LocalDay.month(LocalDay.string()))
                }
                HStack {
                    Spacer()
                    Button(model.text("today")) {
                        model.calendarDate = Date(); model.calendarSelected = LocalDay.string(); model.loadCalendar()
                    }.readingFont(14).wordActionTarget()
                }
                if model.calendarLoading { ProgressView().frame(maxWidth: .infinity) }
                ScrollView(.horizontal) {
                    VStack(spacing: 2) {
                        HStack(spacing: 2) {
                            ForEach(weekdays.indices, id: \.self) { Text(weekdays[$0]).readingFont(12).frame(width: 48).frame(minHeight: 32) }
                        }
                        LazyVGrid(columns: Array(repeating: GridItem(.fixed(48), spacing: 2), count: 7), spacing: 2) {
                            ForEach(model.calendarCells) { cell in
                                if let number = cell.number {
                                    Button {
                                        if let word = cell.word { model.openArchive(word) }
                                    } label: {
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(number.formatted(.number.locale(model.locale))).readingFont(12, weight: .medium)
                                            if let word = cell.word {
                                                Text(word.entry.word).readingFont(14, design: .serif).lineLimit(3)
                                            }
                                            Spacer(minLength: 0)
                                        }
                                        .padding(4).frame(width: 48, height: 84, alignment: .topLeading)
                                        .foregroundStyle(cell.future ? AppPalette.secondary : AppPalette.foreground)
                                        .background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 6))
                                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(cell.id == (model.calendarSelected ?? LocalDay.string()) ? AppPalette.foreground : AppPalette.outline))
                                    }
                                    .buttonStyle(.plain).disabled(cell.future || cell.word == nil || model.calendarLoading)
                                    .accessibilityLabel((cell.date?.formatted(.dateTime.weekday(.wide).day().month(.wide).year().locale(model.locale)) ?? "") + ", " + (cell.word?.entry.word ?? ""))
                                    .accessibilityIdentifier("calendar-day-\(cell.id)")
                                } else { Color.clear.frame(width: 48, height: 84) }
                            }
                        }.frame(width: 348)
                    }
                }
                Text(model.text("calendar_note")).readingFont(12).foregroundStyle(AppPalette.secondary)
            }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
        }.navigationTitle(model.text("word_calendar")).accessibilityIdentifier("calendar-screen")
    }
}
