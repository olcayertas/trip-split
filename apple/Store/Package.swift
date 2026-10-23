// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "TripSplitStore",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [.library(name: "TripSplitStore", targets: ["TripSplitStore"])],
    dependencies: [
        .package(path: "../Core"),
        .package(url: "https://github.com/groue/GRDB.swift.git", exact: "7.11.1"),
    ],
    targets: [
        .target(name: "TripSplitStore", dependencies: [
            .product(name: "TripSplitCore", package: "Core"),
            .product(name: "GRDB", package: "GRDB.swift"),
        ]),
        .testTarget(name: "TripSplitStoreTests", dependencies: ["TripSplitStore"]),
    ]
)
