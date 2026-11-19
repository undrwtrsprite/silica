import XCTest
@testable import BetaVulkanLauncher

final class VersionCatalogTests: XCTestCase {
    func testParseCatalogFiltersDeduplicatesAndOrdersDescending() throws {
        let json = """
        [
          {"id":"b1.9-pre5","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"b1.8.1","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"c0.30-c-1900","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"in-20100223","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"inf-20100618","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"a1.0.4","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"b1.5_01","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"a1.2.6","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"b1.7.3","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"b1.7_01","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"b1.6.6","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"b1.6-tb3","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"b1.6","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"b1.2_02","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"a1.1.2_01","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"a1.1.2","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"a1.0.14-1659","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"a1.0.14-1659","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"a1.0.14-1603","type":"old_alpha","url":"https://u","resources":"https://r"}
        ]
        """
        let parsed = try XCTUnwrap(VersionCatalog.parseCatalog(Data(json.utf8)))
        XCTAssertEqual(parsed.map(\.id), [
            "b1.7.3",
            "b1.7_01",
            "b1.6.6",
            "b1.6",
            "b1.6-tb3",
            "b1.5_01",
            "b1.2_02",
            "a1.2.6",
            "a1.1.2_01",
            "a1.1.2",
            "a1.0.14-1659",
            "a1.0.14-1603",
            "a1.0.4",
        ])
    }

    func testParseCatalogIncludesSupportedExperimentalWhenEnabled() throws {
        let json = """
        [
          {"id":"b1.9-pre5","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"b1.8.1","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"b1.8","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"b1.7.3","type":"old_beta","url":"https://u","resources":"https://r"},
          {"id":"a1.2.6","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"inf-20100630-1835","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"inf-20100630-1340","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"inf-20100618","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"c0.30-c-1900","type":"old_alpha","url":"https://u","resources":"https://r"},
          {"id":"in-20100223","type":"old_alpha","url":"https://u","resources":"https://r"}
        ]
        """
        let parsed = try XCTUnwrap(VersionCatalog.parseCatalog(Data(json.utf8), includeExperimental: true))
        XCTAssertEqual(parsed.map(\.id), [
            "b1.8.1",
            "b1.8",
            "b1.7.3",
            "a1.2.6",
            "inf-20100630-1835",
            "inf-20100630-1340",
        ])
        XCTAssertEqual(parsed.filter(\.isExperimental).map(\.id), [
            "b1.8.1",
            "b1.8",
            "inf-20100630-1835",
            "inf-20100630-1340",
        ])
    }

    @MainActor
    func testLegacyInstallMigrationMovesRootClientAndResourcesIntoVersionDir() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        try Data("client".utf8).write(to: tmp.appendingPathComponent("client.jar"))
        try Data("readable".utf8).write(to: tmp.appendingPathComponent("readable.jar"))
        let res = tmp.appendingPathComponent("resources", isDirectory: true)
        try FileManager.default.createDirectory(at: res, withIntermediateDirectories: true)
        try Data("png".utf8).write(to: res.appendingPathComponent("terrain.png"))

        XCTAssertTrue(InstallManager.migrateLegacyFiles(in: tmp))
        let verDir = tmp.appendingPathComponent("versions/b1.7.3", isDirectory: true)
        XCTAssertTrue(FileManager.default.fileExists(atPath: verDir.appendingPathComponent("client.jar").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: verDir.appendingPathComponent("readable.jar").path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: verDir.appendingPathComponent("resources/terrain.png").path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: tmp.appendingPathComponent("client.jar").path))
        XCTAssertFalse(InstallManager.migrateLegacyFiles(in: tmp))
    }
}
