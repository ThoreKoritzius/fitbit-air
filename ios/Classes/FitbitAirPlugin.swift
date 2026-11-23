import Flutter
import UIKit

public final class FitbitAirPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "fitbit_air", binaryMessenger: registrar.messenger())
        registrar.addMethodCallDelegate(FitbitAirPlugin(), channel: channel)
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "iosNotificationAutomation":
            if #available(iOS 27.0, *) { result(true) } else { result(false) }
            return
        case "openIosShortcuts":
            Task { @MainActor in
                guard let url = URL(string: "shortcuts://") else { result(false); return }
                result(await UIApplication.shared.open(url))
            }
            return
        case "tap":
            break
        default:
            result(FlutterMethodNotImplemented)
            return
        }
        let intensity = (call.arguments as? [String: Any])?["intensity"] as? Int
        Task { @MainActor in
            do {
                let tap = try await AirBluetooth.shared.tap(intensity: intensity)
                result([
                    "intensity": tap.current,
                    "previousIntensity": tap.previous,
                    "changed": tap.current != tap.previous,
                    "verified": true,
                ])
            } catch let error as AirFailure {
                result(FlutterError(code: error.code, message: error.message, details: nil))
            } catch {
                result(FlutterError(code: "HAPTIC_TAP_FAILED", message: error.localizedDescription, details: nil))
            }
        }
    }
}

/// Shared by the Flutter channel and the prototype host's Shortcuts action.
@MainActor
public enum FitbitAirIOS {
    public static func tap() async throws {
        _ = try await AirBluetooth.shared.tap(intensity: nil)
    }
}
