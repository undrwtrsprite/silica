// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "BetaVulkanLauncher",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "BetaVulkanLauncher", path: "Sources/BetaVulkanLauncher"),
        .testTarget(name: "BetaVulkanLauncherTests", dependencies: ["BetaVulkanLauncher"]),
    ]
)
