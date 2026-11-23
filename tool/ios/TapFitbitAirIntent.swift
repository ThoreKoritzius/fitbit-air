import AppIntents
import fitbit_air

@available(iOS 27.0, *)
struct TapFitbitAirIntent: AppIntent {
    static let title: LocalizedStringResource = "Tap Fitbit Air"
    static let description = IntentDescription("Send a single vibration to your connected Fitbit Air.")
    static let openAppWhenRun = false

    func perform() async throws -> some IntentResult {
        try await FitbitAirIOS.tap()
        return .result()
    }
}

@available(iOS 27.0, *)
struct FitbitAirProbeShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: TapFitbitAirIntent(),
            phrases: ["Tap my Fitbit Air with \(.applicationName)"],
            shortTitle: "Tap Fitbit Air",
            systemImageName: "waveform"
        )
    }
}
