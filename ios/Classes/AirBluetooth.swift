import AirGoldenGate
import CoreBluetooth
import Foundation

struct AirFailure: LocalizedError {
    let code: String
    let message: String
    var errorDescription: String? { message }

    init(_ code: String, _ message: String) {
        self.code = code
        self.message = message
    }
}

// One session at a time. CoreBluetooth delegates and the Golden Gate loop use
// the main queue so packet callbacks and transport teardown cannot race.
@MainActor
final class AirBluetooth: NSObject,
    @preconcurrency CBCentralManagerDelegate, @preconcurrency CBPeripheralDelegate {
    static let shared = AirBluetooth()

    private static let service = CBUUID(string: "ABBAFF00-E56A-484C-B832-8B17CF6CBFE8")
    private static let txID = CBUUID(string: "ABBAFF01-E56A-484C-B832-8B17CF6CBFE8")
    private static let rxID = CBUUID(string: "ABBAFF02-E56A-484C-B832-8B17CF6CBFE8")
    private static let probe: [UInt8] = [10, 1, 1]

    private lazy var central = CBCentralManager(delegate: self, queue: nil)
    private var peripheral: CBPeripheral?
    private var tx: CBCharacteristic?
    private var subscribed = false
    private var lastTrackerPacketAt: TimeInterval = 0
    private var failure: AirFailure?
    private var busy = false
    private var session: OpaquePointer?

    func tap(intensity: Int?) async throws -> (previous: Int, current: Int) {
        guard !busy else { throw AirFailure("BUSY", "A Fitbit Air command is running.") }
        guard intensity == nil || intensity == 2 || intensity == 3 else {
            throw AirFailure("INVALID_ARGUMENT", "Choose low or high vibration strength.")
        }
        busy = true
        defer { cleanUp() }
        _ = central
        try await wait(seconds: 3, error: AirFailure("BLUETOOTH_UNAVAILABLE", "Bluetooth is not ready.")) {
            if self.central.state == .unknown || self.central.state == .resetting { return false }
            if self.central.state == .unauthorized {
                throw AirFailure("BLUETOOTH_PERMISSION", "Allow Bluetooth access for this app.")
            }
            guard self.central.state == .poweredOn else {
                throw AirFailure("BLUETOOTH_UNAVAILABLE", "Turn on Bluetooth.")
            }
            return true
        }

        // iOS doesn't expose Android's bonded-device list. Use only a single
        // named tracker already on the system link, such as during Health sync.
        let connected = central.retrieveConnectedPeripherals(withServices: [Self.service])
            .filter { $0.name?.localizedCaseInsensitiveContains("Fitbit Air") == true }
        guard connected.count == 1, let device = connected.first else {
            throw AirFailure("AIR_NOT_FOUND", "Sync one Fitbit Air in Google Health, then try again.")
        }
        peripheral = device
        device.delegate = self
        if device.state == .connected {
            device.discoverServices([Self.service])
        } else {
            central.connect(device)
        }
        try await wait(seconds: 8, error: AirFailure("GATT_CONNECT", "The Fitbit Air did not connect.")) {
            self.subscribed && self.tx != nil
        }

        // Observe incoming tracker traffic briefly before opening our session.
        // Another client may already be transferring over the shared channel.
        let observationStarted = ProcessInfo.processInfo.systemUptime
        try await wait(seconds: 5, error: AirFailure("BUSY", "Wait for Google Health sync to finish.")) {
            ProcessInfo.processInfo.systemUptime - max(observationStarted, self.lastTrackerPacketAt) >= 0.8
        }
        try openSession(handoff: false)
        let result: Result<(previous: Int, current: Int), Error>
        do {
            try await wait(seconds: 8, error: AirFailure("HANDSHAKE_TIMEOUT", "The Air did not open a secure session.")) {
                guard let session = self.session else { return false }
                return air_session_ready(session)
            }
            let original = try await readIntensity()
            let target = intensity ?? original
            let write = target == original
                ? entry(original == 2 ? 3 : 2) + entry(original)
                : entry(target)

            // An unanswered write may have executed. Read back before choosing
            // whether to restore, and never retry the tap itself.
            let acknowledged = (try? await request(write: true, payload: write)) != nil
            let verified = try? await readIntensity()
            guard verified == target, acknowledged || target != original else {
                if verified != original && target != original {
                    _ = try? await request(write: true, payload: entry(original))
                    guard (try? await readIntensity()) == original else {
                        throw AirFailure("HAPTIC_TAP_FAILED", "Could not verify restoration of the original strength.")
                    }
                }
                throw AirFailure("HAPTIC_TAP_FAILED", "The Air did not confirm the requested strength.")
            }
            // A strength-preserving tap requires both a write acknowledgement
            // and read-back, since the intensity by itself cannot prove a tap ran.
            result = .success((original, target))
        } catch {
            result = .failure(error)
        }

        // Finish the handoff before letting CoreBluetooth release our link.
        closeSession()
        if failure == nil {
            try openSession(handoff: true)
            try await wait(seconds: 8, error: AirFailure("HANDOFF_TIMEOUT", "Google Health handoff was not confirmed.")) {
                guard let session = self.session else { return false }
                return air_session_reset_observed(session)
            }
        }
        return try result.get()
    }

    private func openSession(handoff: Bool) throws {
        session = air_session_open(Unmanaged.passUnretained(self).toOpaque(), { context, bytes, count in
            guard let context, let bytes else { return false }
            return MainActor.assumeIsolated {
                let owner = Unmanaged<AirBluetooth>.fromOpaque(context).takeUnretainedValue()
                return owner.send(Data(bytes: bytes, count: count))
            }
        }, handoff)
        guard session != nil else {
            throw AirFailure("GOLDENGATE", "Could not initialize the Fitbit transport.")
        }
    }

    private func closeSession() {
        let current = session
        session = nil
        if let current { air_session_close(current) }
    }

    private func cleanUp() {
        closeSession()
        if let peripheral {
            peripheral.delegate = nil
            central.cancelPeripheralConnection(peripheral)
        }
        peripheral = nil
        tx = nil
        subscribed = false
        lastTrackerPacketAt = 0
        failure = nil
        busy = false
    }

    private func wait(seconds: TimeInterval, error: AirFailure, until: () throws -> Bool) async throws {
        let deadline = ProcessInfo.processInfo.systemUptime + seconds
        repeat {
            if let failure { throw failure }
            if let session { air_session_poll(session) }
            if try until() { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        } while ProcessInfo.processInfo.systemUptime < deadline
        throw error
    }

    private func request(write: Bool, payload: [UInt8]) async throws -> [UInt8] {
        guard let session else { throw AirFailure("GOLDENGATE", "No active Fitbit session.") }
        let sent = payload.withUnsafeBufferPointer {
            air_session_request(session, write, $0.baseAddress, $0.count)
        }
        let error = AirFailure("HAPTIC_TAP_FAILED", "The Air did not accept the haptic command.")
        guard sent == 0 else { throw error }
        defer { air_session_cancel_request(session) }
        var body = [UInt8](repeating: 0, count: 32)
        var size = 0
        try await wait(seconds: 3, error: error) {
            size = body.count
            let response = body.withUnsafeMutableBufferPointer {
                air_session_response(session, $0.baseAddress, &size)
            }
            if response == 0 { return false }
            guard response > 0 && response >> 5 == 2 else { throw error }
            return true
        }
        return Array(body.prefix(size))
    }

    private func entry(_ intensity: Int) -> [UInt8] { [10, 4, 10, 2, 8, UInt8(intensity)] }

    private func readIntensity() async throws -> Int {
        let body = try await request(write: false, payload: Self.probe)
        for intensity in [2, 3] where body == entry(intensity) { return intensity }
        throw AirFailure("HAPTIC_READ_FAILED", "The Air returned an unsupported vibration setting.")
    }

    private func send(_ bytes: Data) -> Bool {
        guard let peripheral, let tx, peripheral.canSendWriteWithoutResponse,
              bytes.count <= peripheral.maximumWriteValueLength(for: .withoutResponse) else { return false }
        peripheral.writeValue(bytes, for: tx, type: .withoutResponse)
        return true
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if peripheral != nil && central.state != .poweredOn {
            failure = AirFailure("BLUETOOTH_UNAVAILABLE", "Bluetooth became unavailable.")
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard peripheral === self.peripheral else { return }
        peripheral.discoverServices([Self.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral else { return }
        failure = AirFailure("GATT_CONNECT", "The Fitbit Air connection failed.")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard peripheral === self.peripheral else { return }
        failure = AirFailure("GATT_DISCONNECTED", "The Fitbit Air disconnected.")
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard peripheral === self.peripheral else { return }
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.service }) else {
            failure = AirFailure("GATT_CONNECT", "The Fitbit service is unavailable.")
            return
        }
        peripheral.discoverCharacteristics([Self.txID, Self.rxID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peripheral === self.peripheral else { return }
        guard error == nil, let tx = service.characteristics?.first(where: { $0.uuid == Self.txID }),
              tx.properties.contains(.writeWithoutResponse),
              let rx = service.characteristics?.first(where: { $0.uuid == Self.rxID }) else {
            failure = AirFailure("GATT_CONNECT", "The Fitbit service has unsupported characteristics.")
            return
        }
        self.tx = tx
        peripheral.setNotifyValue(true, for: rx)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === self.peripheral, characteristic.uuid == Self.rxID else { return }
        subscribed = error == nil && characteristic.isNotifying
        if !subscribed { failure = AirFailure("GATT_CONNECT", "Could not subscribe to the Fitbit Air.") }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peripheral === self.peripheral, error == nil, characteristic.uuid == Self.rxID,
              let packet = characteristic.value else { return }
        lastTrackerPacketAt = ProcessInfo.processInfo.systemUptime
        guard let session else { return }
        packet.withUnsafeBytes {
            air_session_receive(session, $0.bindMemory(to: UInt8.self).baseAddress, $0.count)
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard peripheral === self.peripheral, let session else { return }
        air_session_can_send(session)
    }
}
