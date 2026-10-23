import SwiftUI
import TripSplitCore
import VisionKit

/// Camera scanner for the invite QR code (VisionKit). It reports the first code that is a valid invite.
struct ScanInviteView: View {
    let onInvite: (Invite) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                    QRScannerView { text in
                        if let invite = Invite.parse(text) {
                            onInvite(invite)
                            dismiss()
                        }
                    }
                    .ignoresSafeArea(edges: .bottom)
                } else {
                    ContentUnavailableView("The camera is not available", systemImage: "camera.fill",
                                           description: Text("Allow the camera for TripSplit in Settings, or paste the invite link instead."))
                }
            }
            .navigationTitle("Scan QR code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }
}

struct QRScannerView: UIViewControllerRepresentable {
    let onFound: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFound: onFound) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced,
            recognizesMultipleItems: false, isHighFrameRateTrackingEnabled: false, isPinchToZoomEnabled: true,
            isGuidanceEnabled: true, isHighlightingEnabled: true)
        controller.delegate = context.coordinator
        try? controller.startScanning()
        return controller
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onFound: (String) -> Void
        init(onFound: @escaping (String) -> Void) { self.onFound = onFound }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for case let .barcode(code) in addedItems {
                if let text = code.payloadStringValue { onFound(text) }
            }
        }
    }
}
