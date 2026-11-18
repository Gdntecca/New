import SwiftUI
import UIKit
import UserNotifications

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        Credits.touch()
        Paths.ensureFolders()
        Notifier.registerCategories()
        UNUserNotificationCenter.current().delegate = self
        Engine.shared.settingsChanged(SettingsStore.shared.value)
        return true
    }

    /// The system relaunched us to deliver background transfer events.
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        if identifier == Engine.backgroundIdentifier {
            Engine.shared.backgroundCompletion = completionHandler
            _ = Engine.shared.bgSession
        } else {
            completionHandler()
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let info = response.notification.request.content.userInfo
        if let s = info["id"] as? String, let id = UUID(uuidString: s) {
            Task { @MainActor in
                if response.actionIdentifier == Notifier.changeLinkAction { Store.shared.changeLinkFor = id }
                else { Store.shared.openDetail = id }
            }
        }
        completionHandler()
    }
}

@main
struct SuperDownloaderApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var store = Store.shared
    @StateObject private var settings = SettingsStore.shared
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(settings)
                .preferredColorScheme(settings.value.colorScheme)
                .tint(Theme.red)
                .onOpenURL { store.handleIncoming(url: $0) }
                .onChange(of: settings.value) { Engine.shared.settingsChanged($0) }
        }
        .onChange(of: phase) { p in
            switch p {
            case .active:
                Engine.shared.willEnterForeground()
                store.importSharedLinks()
                store.offerClipboard()
            case .background:
                Engine.shared.didEnterBackground()
            default:
                break
            }
        }
    }
}

/// Author credit kept in the binary (not shown anywhere but the Credits screen).
enum Credits {
    static let author = "Created by Tez"
    @inline(never) static func touch() { _ = author.utf8.count }
}
