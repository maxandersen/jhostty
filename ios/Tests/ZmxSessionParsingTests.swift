import XCTest
@testable import Ghostly

final class ZmxSessionParsingTests: XCTestCase {

    func testParsesTabSeparatedList() {
        let output = """
        → name=dotfiles\tpid=4321\tclients=1\tstart_dir=/home/max\tcwd=/home/max/.config\tcmd=zsh
          name=build\tpid=4400\tclients=0\tstart_dir=/srv\tcwd=/srv/app\tcmd=make
        """
        let sessions = ZmxSession.parseList(output)
        XCTAssertEqual(sessions.count, 2)

        let dotfiles = sessions[0]
        XCTAssertEqual(dotfiles.name, "dotfiles")
        XCTAssertEqual(dotfiles.pid, 4321)
        XCTAssertEqual(dotfiles.clients, 1)
        XCTAssertTrue(dotfiles.isAttached)
        XCTAssertFalse(dotfiles.ended)

        let build = sessions[1]
        XCTAssertEqual(build.name, "build")
        XCTAssertEqual(build.clients, 0)
        XCTAssertFalse(build.isAttached)
        XCTAssertEqual(build.cmd, "make")
    }

    func testEndedFlagAndExitCode() {
        let output = "name=old\tpid=1\tclients=0\tended\texit_code=137"
        let sessions = ZmxSession.parseList(output)
        XCTAssertEqual(sessions.count, 1)
        XCTAssertTrue(sessions[0].ended)
        XCTAssertEqual(sessions[0].exitCode, 137)
    }

    func testGeneratedNameShowsDirectory() {
        let output = "name=b1f2c3d4-1234-abcd-ef00-000000000000\tpid=9\tclients=0\tstart_dir=/srv\tcwd=/srv/app\tcmd=zsh"
        let session = ZmxSession.parseList(output)[0]
        XCTAssertTrue(session.isGeneratedName)
        XCTAssertEqual(session.friendlyName, "srv/app")
    }

    func testEmptyAndBlankInput() {
        XCTAssertTrue(ZmxSession.parseList("").isEmpty)
        XCTAssertTrue(ZmxSession.parseList("   \n  \n").isEmpty)
    }

    func testDisplayLabel() {
        let attached = ZmxSession.parseList("name=web\tpid=1\tclients=2\tcmd=zsh")[0]
        XCTAssertEqual(attached.displayLabel, "web (2)")

        let ended = ZmxSession.parseList("name=gone\tpid=1\tclients=0\tended")[0]
        XCTAssertEqual(ended.displayLabel, "gone ✘")
    }

    func testAttachCommandUpsertsAndQuotes() {
        XCTAssertEqual(ZmxController.attachCommand(session: "my proj"), "zmx attach 'my proj'")
        XCTAssertTrue(ZmxController.attachCommand(session: nil).hasPrefix("zmx attach 'ios-"))
    }

    func testShellQuoteEscapesSingleQuotes() {
        XCTAssertEqual(ZmxController.shellQuote("a'b"), "'a'\\''b'")
    }
}
