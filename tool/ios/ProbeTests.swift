import AirGoldenGate
import fitbit_air
import XCTest

final class RunnerTests: XCTestCase {
    @MainActor
    func testNativeTransportLifecycle() throws {
        for handoff in [false, true] {
            let session = try XCTUnwrap(air_session_open(nil, { _, _, _ in true }, handoff))
            air_session_poll(session)
            XCTAssertFalse(air_session_ready(session))
            air_session_close(session)
        }
    }

    @MainActor
    func testTapReportsUnavailableWithoutHardware() async {
        do {
            try await FitbitAirIOS.tap()
            XCTFail("The simulator has no Fitbit Air")
        } catch {
            XCTAssertTrue(
                error.localizedDescription.contains("Bluetooth") ||
                error.localizedDescription.contains("Sync one Fitbit Air"),
                error.localizedDescription
            )
        }
    }
}
