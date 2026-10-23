// The HTTP client of the internet relay (SPEC §10.4.3).

import Foundation
import TripSplitCore

public enum RelayConfig {
    /// The relay URL from the build settings. Nil means the relay sync stays off: the text is empty,
    /// it is not an https URL, or its host is under `.invalid` (the default in `project.yml`).
    public static func baseURL(_ text: String?) -> URL? {
        guard let text = text?.trimmingCharacters(in: .whitespaces), !text.isEmpty,
              let url = URL(string: text), url.scheme == "https",
              let host = url.host()?.lowercased(), !host.isEmpty, host != "invalid", !host.hasSuffix(".invalid")
        else { return nil }
        return url
    }
}

public struct RelayHTTPClient: RelayAPI {
    public let baseURL: URL
    public let mailboxId: String
    private let token: String
    private let session: URLSession

    public init(baseURL: URL, groupKey: [UInt8], session: URLSession = .shared) {
        self.baseURL = baseURL
        mailboxId = RelayCrypto.mailboxId(groupKey: groupKey)
        token = RelayCrypto.relayToken(groupKey: groupKey)
        self.session = session
    }

    private func request(_ path: String, query: [URLQueryItem] = [], method: String) -> URLRequest {
        var parts = URLComponents(url: baseURL.appending(path: "v1/m/\(mailboxId)/\(path)"), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { parts.queryItems = query }
        var req = URLRequest(url: parts.url!, timeoutInterval: 30)
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return req
    }

    /// Maps a status code to the error of SPEC §10.4.3. Nil means success.
    static func error(forStatus code: Int, expected: Int) -> RelayError? {
        if code == expected { return nil }
        switch code {
        case 403: return .forbidden
        case 507: return .full
        case 429, 500...599: return .retryLater
        default: return .rejected(code)
        }
    }

    private func send(_ req: URLRequest, body: Data? = nil) async throws -> (Data, Int) {
        do {
            let (data, response) = body == nil ? try await session.data(for: req) : try await session.upload(for: req, from: body!)
            guard let http = response as? HTTPURLResponse else { throw RelayError.retryLater }
            return (data, http.statusCode)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as RelayError {
            throw error
        } catch {
            throw RelayError.retryLater
        }
    }

    public func pull(after cursor: Int64, limit: Int) async throws -> RelayPage {
        let req = request("batches", query: [URLQueryItem(name: "after", value: String(cursor)),
                                             URLQueryItem(name: "limit", value: String(limit))], method: "GET")
        let (data, code) = try await send(req)
        if let failure = Self.error(forStatus: code, expected: 200) { throw failure }
        guard let json = try? JSONParser.parse(Array(data)), let list = json["batches"]?.arrayValue,
              case let .bool(more)? = json["more"]
        else { throw RelayError.retryLater }
        var batches: [RelayPage.Batch] = []
        for entry in list {
            // A blob that is not base64url cannot decrypt. Keep its cursor so the sync moves past it.
            guard let cursor = entry["cursor"]?.intValue else { throw RelayError.retryLater }
            batches.append(.init(cursor: cursor, blob: entry["blob"]?.stringValue.flatMap(Base64URL.decode) ?? []))
        }
        // `epoch` is a string, or null for a mailbox that does not exist.
        return RelayPage(batches: batches, more: more, epoch: json["epoch"]?.stringValue)
    }

    public func push(_ blob: [UInt8]) async throws -> RelayPushAck {
        var req = request("batches", method: "POST")
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let (data, code) = try await send(req, body: Data(blob))
        if let failure = Self.error(forStatus: code, expected: 201) { throw failure }
        let json = try? JSONParser.parse(Array(data))
        guard let cursor = json?["cursor"]?.intValue else { throw RelayError.retryLater }
        return RelayPushAck(cursor: cursor, epoch: json?["epoch"]?.stringValue)
    }
}
