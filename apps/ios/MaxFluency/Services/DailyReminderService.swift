import Foundation
import UserNotifications
import Observation
import os

struct ReminderTarget: Equatable, Sendable {
    let id: String
    let date: String
    let level: VocabularyLevel

    init(userInfo: [AnyHashable: Any]) throws {
        guard let id = userInfo["word"] as? String, let date = userInfo["date"] as? String,
              let code = id.split(separator: ":", maxSplits: 1).first.map(String.init),
              DictionaryLanguage(rawValue: code) != nil,
              let value = userInfo["level"] as? String, let level = VocabularyLevel(rawValue: value) else {
            throw PlatformFailure.message("The daily reminder has an invalid word target.")
        }
        _ = try LocalDay.date(date)
        self.id = id
        self.date = date
        self.level = level
    }
}

@MainActor @Observable
final class NotificationInbox {
    static let shared = NotificationInbox()
    var pending: ReminderTarget?
    var failure: String?
}

actor DailyReminderService {
    private let center = UNUserNotificationCenter.current()
    private let defaults = UserDefaults.standard
    private let prefix = "maxfluency.daily."

    func authorize() async throws {
        guard try await center.requestAuthorization(options: [.alert, .sound]) else {
            throw PlatformFailure.message("Notifications were not allowed. Enable MaxFluency notifications in iOS Settings.")
        }
    }

    func cancel() async {
        if let first = defaults.object(forKey: "daily-reminder-first-fire") as? Date, first <= Date() {
            let day = LocalDay.string(first)
            let old = defaults.string(forKey: "daily-reminder-last-assumed-day")
            if old == nil || day > (old ?? "") { defaults.set(day, forKey: "daily-reminder-last-assumed-day") }
        }
        defaults.removeObject(forKey: "daily-reminder-first-fire")
        let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix(prefix) }
        center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier))
        let delivered = await center.deliveredNotifications().filter { $0.request.identifier.hasPrefix(prefix) }
        center.removeDeliveredNotifications(withIdentifiers: delivered.map { $0.request.identifier })
    }

    func reschedule(settings: AppSettings, repository: LexiconRepository, now: Date = Date()) async throws {
        guard settings.reminderEnabled else { await cancel(); return }
        let permissions = await center.notificationSettings()
        guard [.authorized, .provisional, .ephemeral].contains(permissions.authorizationStatus) else {
            throw PlatformFailure.message("Daily reminders are blocked in iOS notification settings.")
        }
        guard (0..<1_440).contains(settings.reminderMinute) else { throw PlatformFailure.message("Invalid daily reminder time.") }
        let pending = await center.pendingNotificationRequests().filter { $0.identifier.hasPrefix(prefix) }
        let today = LocalDay.string(now)
        var lastDay = defaults.string(forKey: "daily-reminder-last-assumed-day")
        for request in pending {
            if let trigger = request.trigger as? UNCalendarNotificationTrigger,
               let date = trigger.nextTriggerDate(), date <= now {
                let day = String(request.identifier.dropFirst(prefix.count))
                if lastDay == nil || day > (lastDay ?? "") { lastDay = day }
            }
        }
        if let previous = defaults.object(forKey: "daily-reminder-first-fire") as? Date, previous <= now {
            let day = LocalDay.string(previous)
            if lastDay == nil || day > (lastDay ?? "") { lastDay = day }
        }
        if let lastDay { defaults.set(lastDay, forKey: "daily-reminder-last-assumed-day") }
        let manifest = await repository.manifest()
        var requests: [UNNotificationRequest] = []
        let calendar = Calendar(identifier: .gregorian)
        for offset in 0..<61 {
            try Task.checkCancellation()
            let day = try LocalDay.adding(offset, to: today)
            if let lastDay, day <= lastDay { continue }
            let civil = try LocalDay.date(day)
            var components = calendar.dateComponents([.year, .month, .day], from: civil)
            components.hour = settings.reminderMinute / 60
            components.minute = settings.reminderMinute % 60
            guard let fire = calendar.date(from: components) else { throw PlatformFailure.message("Daily reminder date is invalid.") }
            if fire <= now { continue }
            let archived = try await repository.planArchivedWord(date: day, language: settings.language,
                                                                level: settings.level(for: settings.language))
            let preview = discoveryPreview(archived.entry, policy: manifest.discovery) ?? archived.entry
            guard let definition = preview.senses.first?.definition else {
                throw PlatformFailure.message("The daily reminder word has no definition.")
            }
            let content = UNMutableNotificationContent()
            content.title = archived.entry.word
            content.body = definition
            content.sound = .default
            content.userInfo = ["word": archived.entry.id, "date": day, "level": archived.level.rawValue]
            requests.append(UNNotificationRequest(identifier: prefix + day, content: content,
                                                  trigger: UNCalendarNotificationTrigger(dateMatching: components, repeats: false)))
            if requests.count == 60 { break }
        }
        try Task.checkCancellation()
        center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier))
        do {
            for request in requests {
                try Task.checkCancellation()
                try await center.add(request)
            }
        } catch {
            center.removePendingNotificationRequests(withIdentifiers: requests.map(\.identifier))
            throw error
        }
        if let first = requests.first?.trigger as? UNCalendarNotificationTrigger, let fire = first.nextTriggerDate() {
            defaults.set(fire, forKey: "daily-reminder-first-fire")
        }
    }
}
