import SwiftUI
import UIKit

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var phase
    @Environment(\.colorScheme) private var scheme
    @State private var inbox = NotificationInbox.shared

    var body: some View {
        @Bindable var model = model
        NavigationStack(path: $model.routes) {
            Group {
                if model.loading {
                    ProgressView(model.text("loading_dictionary")).frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if model.startupFailed && model.tab != .settings {
                    VStack(spacing: 20) {
                        Text(model.text("error_dictionary")).readingFont()
                        Button(model.text("retry")) { model.retry() }.wordActionTarget()
                        Button(model.text("settings")) { model.tab = .settings }.wordActionTarget()
                    }.padding(24).frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    switch model.tab {
                    case .today, .alternate: LearningView(home: model.displayedHome).id(model.tab)
                    case .library: LibraryView()
                    case .settings: SettingsView()
                    }
                }
            }
            .frame(maxWidth: 840, maxHeight: .infinity)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(AppPalette.surface)
            .navigationTitle(model.text("app_name"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if model.tab == .today || model.tab == .alternate {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { model.openCalendar() } label: { Image(systemName: "calendar").wordActionTarget() }
                            .accessibilityLabel(model.text("word_calendar")).accessibilityIdentifier("open-word-calendar")
                            .disabled(model.daily == nil)
                    }
                }
            }
            .navigationDestination(for: AppRoute.self) { route in
                Group {
                    switch route {
                    case .word: WordCardView()
                    case .calendar: CalendarView()
                    case .credits: CreditsView()
                    case .assessment: AssessmentView()
                    }
                }
                .frame(maxWidth: 840, maxHeight: .infinity)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(AppPalette.surface)
                .navigationBarTitleDisplayMode(.inline)
            }
        }
        .tint(AppPalette.accent(model.settings.accent, dark: scheme == .dark))
        .toolbarBackground(AppPalette.surface, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if model.routes.isEmpty && !model.loading { AppNavigationBar() }
        }
        .background(AppPalette.surface.ignoresSafeArea())
        .alert(model.text("error_title"), isPresented: Binding(get: { model.problem != nil }, set: { if !$0 { model.problem = nil } })) {
            Button(model.text("ok"), role: .cancel) { model.problem = nil }
        } message: { Text(model.problem ?? "") }
        .overlay(alignment: .bottom) {
            if let message = model.notice {
                Text(message).readingFont(14).padding(14).background(AppPalette.surface, in: RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(AppPalette.outline))
                    .padding(.horizontal, 20).padding(.bottom, model.routes.isEmpty ? 76 : 16)
                    .task(id: message) {
                        do {
                            try await Task.sleep(for: .seconds(3))
                            if model.notice == message { model.notice = nil }
                        } catch is CancellationError {}
                        catch { model.report(error, "error_dictionary") }
                    }
            }
        }
        .sheet(isPresented: Binding(get: { model.exportEntries != nil }, set: { if !$0 { model.exportEntries = nil } })) {
            if let words = model.exportEntries { AnkiExportView(words: words).environment(model) }
        }
        .task { await model.start(); await model.consumeNotification() }
        .task(id: phase) {
            guard phase == .active else { model.speech.stop(); return }
            await model.start()
            model.refreshDay()
            model.queueReminderRefresh()
            AppDelegate.scheduleRefresh(enabled: model.settings.reminderEnabled)
            do {
                while !Task.isCancelled {
                    let calendar = Calendar(identifier: .gregorian)
                    guard let next = calendar.nextDate(after: Date(), matching: DateComponents(hour: 0, minute: 0), matchingPolicy: .nextTime) else {
                        throw PlatformFailure.message("The next local day could not be determined.")
                    }
                    try await Task.sleep(for: .seconds(max(1, next.timeIntervalSinceNow)))
                    model.refreshDay()
                }
            } catch is CancellationError {}
            catch { model.report(error, "error_dictionary") }
        }
        .onChange(of: inbox.pending) { _, _ in Task { await model.consumeNotification() } }
        .onChange(of: inbox.failure) { _, failure in if let failure { model.problem = failure; inbox.failure = nil } }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
            model.refreshDay(); model.queueReminderRefresh()
        }
        .onOpenURL { url in
            if url.scheme == "maxfluency", url.host == "anki-return" { model.notice = model.text("anki_mobile_opened") }
            else { model.report(PlatformFailure.message("This app link is not supported."), "error_entry") }
        }
    }
}

private struct AppNavigationBar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    private func title(_ tab: AppTab) -> String {
        switch tab {
        case .today: return model.text("today")
        case .alternate: return model.text(model.alternateView == .garden ? "home_word_garden" : "home_word_of_day")
        case .library: return model.text("library")
        case .settings: return model.text("settings")
        }
    }
    private func icon(_ tab: AppTab) -> String {
        switch tab {
        case .today: return "house"
        case .alternate: return model.alternateView == .garden ? "rectangle.grid.3x2" : "calendar"
        case .library: return "star"
        case .settings: return "gearshape"
        }
    }
    var body: some View {
        HStack(spacing: 0) {
            ForEach(AppTab.allCases, id: \.self) { tab in
                Button { model.selectTab(tab) } label: {
                    VStack(spacing: 5) {
                        Image(systemName: icon(tab)).font(.system(size: 21, weight: model.tab == tab ? .semibold : .regular))
                        Text(title(tab)).readingFont(11, weight: model.tab == tab ? .semibold : .regular).lineLimit(2).multilineTextAlignment(.center)
                    }.frame(maxWidth: .infinity, minHeight: 58).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(model.tab == tab ? AppPalette.accent(model.settings.accent, dark: scheme == .dark) : AppPalette.secondary)
                .accessibilityIdentifier("nav-\(tab.rawValue)")
                .accessibilityAddTraits(model.tab == tab ? .isSelected : [])
            }
        }
        .padding(.horizontal, 8).padding(.top, 6)
        .background(AppPalette.surface)
        .overlay(alignment: .top) { Rectangle().fill(AppPalette.outline).frame(height: 0.5) }
    }
}
