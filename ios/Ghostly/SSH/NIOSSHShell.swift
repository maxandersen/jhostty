import Foundation
import NIOCore
import NIOSSH

/// An interactive PTY shell over an SSH child channel. Bytes read from the
/// remote are delivered on `onOutput`; `send` writes keystrokes back; `resize`
/// issues a window-change so the remote re-flows.
final class NIOSSHShell: SSHShell {
    var onOutput: ((Data) -> Void)?
    var onClose: ((Int32?) -> Void)?

    private let eventLoop: EventLoop
    private weak var channel: Channel?

    init(eventLoop: EventLoop) {
        self.eventLoop = eventLoop
    }

    func makeHandler(cols: Int, rows: Int, command: String?) -> ChannelHandler {
        ShellHandler(cols: cols, rows: rows, command: command, owner: self)
    }

    fileprivate func attach(channel: Channel) { self.channel = channel }

    fileprivate func emit(_ data: Data) {
        // Hop to the main actor so SwiftTerm feed happens on the UI thread.
        DispatchQueue.main.async { [weak self] in self?.onOutput?(data) }
    }

    fileprivate func emitClose(_ code: Int32?) {
        DispatchQueue.main.async { [weak self] in self?.onClose?(code) }
    }

    func send(_ data: Data) {
        guard let channel else { return }
        eventLoop.execute {
            var buffer = channel.allocator.buffer(capacity: data.count)
            buffer.writeBytes(data)
            let channelData = SSHChannelData(type: .channel, data: .byteBuffer(buffer))
            channel.writeAndFlush(channelData, promise: nil)
        }
    }

    func resize(cols: Int, rows: Int) {
        guard let channel else { return }
        eventLoop.execute {
            let event = SSHChannelRequestEvent.WindowChangeRequest(
                terminalCharacterWidth: cols,
                terminalRowHeight: rows,
                terminalPixelWidth: 0,
                terminalPixelHeight: 0
            )
            channel.triggerUserOutboundEvent(event, promise: nil)
        }
    }

    func close() {
        channel?.close(promise: nil)
    }
}

/// Child-channel handler that negotiates the PTY + shell and pumps bytes.
private final class ShellHandler: ChannelDuplexHandler {
    typealias InboundIn = SSHChannelData
    typealias InboundOut = ByteBuffer
    typealias OutboundIn = ByteBuffer
    typealias OutboundOut = SSHChannelData

    private let cols: Int
    private let rows: Int
    private let command: String?
    private unowned let owner: NIOSSHShell

    init(cols: Int, rows: Int, command: String?, owner: NIOSSHShell) {
        self.cols = cols
        self.rows = rows
        self.command = command
        self.owner = owner
    }

    func handlerAdded(context: ChannelHandlerContext) {
        owner.attach(channel: context.channel)
    }

    func channelActive(context: ChannelHandlerContext) {
        // 1. Ask for a PTY.
        let pty = SSHChannelRequestEvent.PseudoTerminalRequest(
            wantReply: true,
            term: "xterm-256color",
            terminalCharacterWidth: cols,
            terminalRowHeight: rows,
            terminalPixelWidth: 0,
            terminalPixelHeight: 0,
            terminalModes: SSHTerminalModes([.ECHO: 1])
        )
        context.triggerUserOutboundEvent(pty, promise: nil)

        // 2. Start a login shell, or exec a specific command (e.g. `zmx attach`).
        if let command {
            let exec = SSHChannelRequestEvent.ExecRequest(command: command, wantReply: true)
            context.triggerUserOutboundEvent(exec, promise: nil)
        } else {
            let shell = SSHChannelRequestEvent.ShellRequest(wantReply: true)
            context.triggerUserOutboundEvent(shell, promise: nil)
        }
        context.fireChannelActive()
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let channelData = unwrapInboundIn(data)
        guard case .byteBuffer(let bytes) = channelData.data else { return }
        // Forward both stdout and stderr to the terminal.
        if let raw = bytes.getBytes(at: bytes.readerIndex, length: bytes.readableBytes) {
            owner.emit(Data(raw))
        }
    }

    func channelInactive(context: ChannelHandlerContext) {
        owner.emitClose(nil)
        context.fireChannelInactive()
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if let status = event as? SSHChannelRequestEvent.ExitStatus {
            owner.emitClose(Int32(status.exitStatus))
        }
        context.fireUserInboundEventTriggered(event)
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        NSLog("[Ghostly SSH] shell error: \(error)")
        context.close(promise: nil)
    }
}
