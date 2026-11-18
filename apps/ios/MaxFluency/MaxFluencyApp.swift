import SwiftUI
import UIKit
import UserNotifications
import BackgroundTasks
import os

@main
@MainActor
struct MaxFluencyApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel.shared
    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(\.locale, model.locale)
                .preferredColorScheme(model.settings.theme.colorScheme)
        }
    }
}

@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    static let refreshIdentifier = "com.maxfluency.app.daily-refresh"
    private let logger = Logger(subsystem: "com.maxfluency.app", category: "lifecycle")

    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshIdentifier, using: nil) { task in
            let work = Task { @MainActor in
                let success = await AppModel.shared.backgroundRefresh()
                Self.scheduleRefresh(enabled: AppModel.shared.settings.reminderEnabled)
                task.setTaskCompleted(success: success)
            }
            task.expirationHandler = { work.cancel() }
        }
        if !registered { logger.error("Daily reminder background refresh could not be registered.") }
        return true
    }

    static func scheduleRefresh(enabled: Bool) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: refreshIdentifier)
        guard enabled else { return }
        let request = BGAppRefreshTaskRequest(identifier: refreshIdentifier)
        request.earliestBeginDate = Date().addingTimeInterval(12 * 60 * 60)
        do { try BGTaskScheduler.shared.submit(request) }
        catch {
            Logger(subsystem: "com.maxfluency.app", category: "reminders")
                .notice("iOS did not accept background refresh; the existing offline reminder queue remains available: \(error.localizedDescription, privacy: .private)")
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        do {
            let target = try ReminderTarget(userInfo: response.notification.request.content.userInfo)
            await MainActor.run { NotificationInbox.shared.pending = target }
        } catch {
            await MainActor.run { NotificationInbox.shared.failure = error.localizedDescription }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}
