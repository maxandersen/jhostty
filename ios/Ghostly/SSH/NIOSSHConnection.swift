import Foundation
import Crypto
import NIOCore
import NIOPosix
import NIOSSH

/// swift-nio-ssh backed implementation of `SSHConnection`.
///
/// One TCP connection carries an `NIOSSHHandler`; each command or shell runs on
/// its own SSH child channel. Password auth is fully supported; private-key auth
/// currently accepts ECDSA P-256 PEM keys (see `SSHClientAuthDelegate`).
final class NIOSSHConnection: SSHConnection {
    private let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
    private var channel: Channel?
    private var sshHandler: NIOSSHHandler?

    deinit { try? group.syncShutdownGracefully() }

    func connect(host: Host, credentials: SSHCredentials) async throws {
        let authDelegate = try SSHClientAuthDelegate(credentials: credentials, method: host.authMethod)
        let bootstrap = ClientBootstrap(group: group)
            .channelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
            .channelInitializer { channel in
                let handler = NIOSSHHandler(
                    role: .client(.init(
                        userAuthDelegate: authDelegate,
                        serverAuthDelegate: AcceptAllHostKeys()
                    )),
                    allocator: channel.allocator,
                    inboundChildChannelInitializer: nil
                )
                return channel.pipeline.addHandlers([handler, ErrorLoggingHandler()])
            }

        do {
            let channel = try await bootstrap.connect(host: host.hostname, port: host.port).get()
            let handler = try await channel.pipeline.handler(type: NIOSSHHandler.self).get()
            self.channel = channel
            self.sshHandler = handler
        } catch {
            // NIOSSH surfaces auth failure as the connection closing during setup,
            // so distinguish it from a transport error via the auth delegate.
            if authDelegate.exhausted {
                throw SSHConnectionError.authenticationFailed
            }
            throw SSHConnectionError.connectionFailed(error.localizedDescription)
        }
    }

    func run(_ command: String) async throws -> String {
        guard let sshHandler else { throw SSHConnectionError.notConnected }
        let promise = group.next().makePromise(of: ByteBuffer.self)
        let childChannel = try await createChildChannel { channel in
            channel.pipeline.addHandler(ExecHandler(command: command, completion: promise))
        }
        _ = childChannel
        let buffer = try await promise.futureResult.get()
        return String(buffer: buffer)
    }

    func openShell(cols: Int, rows: Int, command: String?) async throws -> SSHShell {
        guard sshHandler != nil else { throw SSHConnectionError.notConnected }
        let shell = NIOSSHShell(eventLoop: group.next())
        _ = try await createChildChannel { channel in
            channel.pipeline.addHandler(shell.makeHandler(cols: cols, rows: rows, command: command))
        }
        return shell
    }

    func disconnect() async {
        try? await channel?.close().get()
        channel = nil
        sshHandler = nil
    }

    /// Create an SSH `.session` child channel and run `initializer` on it.
    private func createChildChannel(
        _ initializer: @escaping (Channel) -> EventLoopFuture<Void>
    ) async throws -> Channel {
        guard let sshHandler else { throw SSHConnectionError.notConnected }
        let promise = group.next().makePromise(of: Channel.self)
        sshHandler.createChannel(promise, channelType: .session) { channel, _ in
            initializer(channel)
        }
        return try await promise.futureResult.get()
    }
}

// MARK: - Host key validation

/// Trust-on-connect host-key policy. A production build should pin the host key
/// on first use and warn on change; this scaffold accepts any key so the flow
/// works out of the box. Marked clearly so it is easy to find and replace.
private struct AcceptAllHostKeys: NIOSSHClientServerAuthenticationDelegate {
    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        validationCompletePromise.succeed(())
    }
}

// MARK: - User authentication

/// Offers the configured credential once. NIOSSH calls this repeatedly until it
/// runs out of offers; we track `exhausted` so the connection layer can report
/// an authentication failure distinctly from a network error.
private final class SSHClientAuthDelegate: NIOSSHClientUserAuthenticationDelegate {
    private let username: String
    private let offer: NIOSSHUserAuthenticationOffer.Offer
    private var used = false
    private(set) var exhausted = false

    init(credentials: SSHCredentials, method: AuthMethod) throws {
        self.username = credentials.username
        switch method {
        case .password:
            self.offer = .password(.init(password: credentials.password ?? ""))
        case .privateKey:
            guard let pem = credentials.privateKeyPEM,
                  let p256 = try? P256.Signing.PrivateKey(pemRepresentation: pem)
            else { throw SSHConnectionError.unsupportedKey }
            self.offer = .privateKey(.init(privateKey: NIOSSHPrivateKey(p256Key: p256)))
        }
    }

    func nextAuthenticationType(
        availableMethods: NIOSSHAvailableUserAuthenticationMethods,
        nextChallengePromise: EventLoopPromise<NIOSSHUserAuthenticationOffer?>
    ) {
        guard !used else {
            exhausted = true
            nextChallengePromise.succeed(nil)
            return
        }
        used = true
        nextChallengePromise.succeed(
            NIOSSHUserAuthenticationOffer(username: username, serviceName: "", offer: offer)
        )
    }
}

// MARK: - Exec channel

/// Runs a single command, buffers stdout, and completes when the channel ends.
private final class ExecHandler: ChannelDuplexHandler {
    typealias InboundIn = SSHChannelData
    typealias InboundOut = ByteBuffer
    typealias OutboundIn = Never
    typealias OutboundOut = SSHChannelData

    private let command: String
    private let completion: EventLoopPromise<ByteBuffer>
    private var buffer: ByteBuffer?

    init(command: String, completion: EventLoopPromise<ByteBuffer>) {
        self.command = command
        self.completion = completion
    }

    func handlerAdded(context: ChannelHandlerContext) {
        buffer = context.channel.allocator.buffer(capacity: 0)
    }

    func channelActive(context: ChannelHandlerContext) {
        let request = SSHChannelRequestEvent.ExecRequest(command: command, wantReply: false)
        context.triggerUserOutboundEvent(request, promise: nil)
        context.fireChannelActive()
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let channelData = unwrapInboundIn(data)
        guard case .byteBuffer(var bytes) = channelData.data else { return }
        // Collect stdout; ignore stderr for the probe use case.
        if channelData.type == .channel { buffer?.writeBuffer(&bytes) }
    }

    func channelInactive(context: ChannelHandlerContext) {
        completion.succeed(buffer ?? context.channel.allocator.buffer(capacity: 0))
        context.fireChannelInactive()
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        completion.fail(error)
        context.close(promise: nil)
    }
}

// MARK: - Error logging

private final class ErrorLoggingHandler: ChannelInboundHandler {
    typealias InboundIn = Any
    func errorCaught(context: ChannelHandlerContext, error: Error) {
        NSLog("[Ghostly SSH] channel error: \(error)")
        context.fireErrorCaught(error)
    }
}
