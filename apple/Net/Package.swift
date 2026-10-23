// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TripSplitNet",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "TripSplitNet", targets: ["TripSplitNet"])],
    dependencies: [
        .package(path: "../Core"),
        .package(path: "../Store"),
    ],
    targets: [
        .target(name: "TripSplitNet", dependencies: [
            .product(name: "TripSplitCore", package: "Core"),
            .product(name: "TripSplitStore", package: "Store"),
        ]),
        .testTarget(name: "TripSplitNetTests", dependencies: ["TripSplitNet"]),
    ]
)
