// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TripSplitCore",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "TripSplitCore", targets: ["TripSplitCore"])],
    targets: [
        .target(name: "TripSplitCore"),
        .testTarget(name: "TripSplitCoreTests", dependencies: ["TripSplitCore"]),
    ]
)
