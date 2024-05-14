// swift-tools-version:5.5
import PackageDescription

let package = Package(
    name: "MyProject",
    dependencies: [
        .package(url: "https://github.com/Alamofire/Alamofire.git", from: "5.5.1")
    ],
    targets: [
        .target(
            name: "MyProject",
            dependencies: ["Alamofire"]),
    ]
)