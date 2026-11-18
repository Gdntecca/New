// swift-tools-version:5.7
import PackageDescription

let package = Package(
    name: "SDCore",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [.library(name: "SDCore", targets: ["SDCore"])],
    targets: [
        .target(name: "SDCore"),
        .testTarget(name: "SDCoreTests", dependencies: ["SDCore"]),
    ]
)
