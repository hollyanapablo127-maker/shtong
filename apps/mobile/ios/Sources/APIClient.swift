import Foundation

struct SessionInvalidatedError: Error {}

actor APIClient {
    private struct RequestContext {
        let requestID: String
        let idempotencyKey: String?
    }

    private let baseURL: URL
    private let keychain: any TokenStore
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let session: URLSession
    private let trustedDownloadHosts: Set<String>
    private var refreshTask: Task<MobileTokens, Error>?

    init(keychain: any TokenStore, baseURL: URL? = nil, session: URLSession = .shared, trustedDownloadHosts: Set<String>? = nil) {
        let configured = Bundle.main.object(forInfoDictionaryKey: "APIBaseURL") as? String
        let fallback = "http://127.0.0.1:8080/v1/"
        self.baseURL = baseURL ?? URL(string: configured ?? fallback)!
        self.keychain = keychain
        self.session = session
        let configuredHosts = Bundle.main.object(forInfoDictionaryKey: "TrustedDownloadHosts") as? [String] ?? []
        self.trustedDownloadHosts = trustedDownloadHosts ?? Set(configuredHosts.compactMap { $0.lowercased() })
    }

    func get<T: Decodable>(_ path: String, query: [URLQueryItem] = []) async throws -> T {
        try await request(path, method: "GET", query: query, body: nil)
    }

    func send<T: Decodable, B: Encodable>(_ path: String, method: String = "POST", body: B) async throws -> T {
        try await request(path, method: method, query: [], body: try encoder.encode(body))
    }

    func send<T: Decodable>(_ path: String, method: String = "POST") async throws -> T {
        try await request(path, method: method, query: [], body: Data())
    }

    func issueMedicalInsuranceTicket() async throws -> MedicalInsuranceTicket {
        var ticket: MedicalInsuranceTicket = try await send("modules/medical-insurance/sso/ticket")
        ticket.redirectUrl = try normalizeMedicalRedirect(absoluteRedirect(ticket.redirectUrl)).absoluteString
        return ticket
    }


    func switchContext(workspace: String) async throws -> SessionEstablished {
        try await send("session/context", body: ContextRequest(workspace: workspace))
    }

    func upload(_ path: String, data: Data, fileName: String) async throws -> JobAccepted {
        let boundary = "shtong-\(UUID().uuidString)"
        var body = Data()
        body.append("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(fileName)\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n".data(using: .utf8)!)
        body.append(data)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        return try await request(path, method: "POST", query: [], body: body, contentType: "multipart/form-data; boundary=\(boundary)")
    }

    func download(_ path: String) async throws -> Data {
        let url = try trustedDownloadEndpoint(path)
        let sameOrigin = isSameOrigin(url)
        let context = RequestContext(requestID: UUID().uuidString, idempotencyKey: nil)
        var request = downloadRequest(url: url, context: context, includeAuthorization: sameOrigin)
        var (data, response) = try await session.data(for: request)
        if sameOrigin, shouldRefresh(response: response, data: data) {
            try await refreshTokens()
            request = downloadRequest(url: url, context: context, includeAuthorization: true)
            (data, response) = try await session.data(for: request)
        }
        try validate(response, data: data)
        return data
    }

    private func request<T: Decodable>(
        _ path: String,
        method: String,
        query: [URLQueryItem],
        body: Data?,
        contentType: String = "application/json",
        canRefresh: Bool = true,
        context: RequestContext? = nil
    ) async throws -> T {
        let requestContext = context ?? RequestContext(
            requestID: UUID().uuidString,
            idempotencyKey: method == "GET" || method == "HEAD" ? nil : UUID().uuidString
        )
        var request = try makeRequest(path: path, method: method, query: query, body: body, contentType: contentType, context: requestContext)
        var (data, response) = try await session.data(for: request)
        if canRefresh, !isAuthenticationPath(path), shouldRefresh(response: response, data: data) {
            try await refreshTokens()
            request = try makeRequest(path: path, method: method, query: query, body: body, contentType: contentType, context: requestContext)
            (data, response) = try await session.data(for: request)
        }
        try validate(response, data: data)
        if T.self == EmptyResponse.self { return EmptyResponse() as! T }
        return try decoder.decode(T.self, from: data)
    }

    private func refreshTokens() async throws {
        if let refreshTask {
            do {
                let tokens = try await refreshTask.value
                try keychain.save(access: tokens.accessToken, refresh: tokens.refreshToken)
                return
            } catch {
                keychain.clear()
                throw SessionInvalidatedError()
            }
        }
        guard let refresh = keychain.refresh() else { throw SessionInvalidatedError() }
        let task = Task { try await self.performRefresh(refresh) }
        refreshTask = task
        defer { refreshTask = nil }
        do {
            let tokens = try await task.value
            try keychain.save(access: tokens.accessToken, refresh: tokens.refreshToken)
        } catch {
            keychain.clear()
            throw SessionInvalidatedError()
        }
    }

    private func performRefresh(_ refresh: String) async throws -> MobileTokens {
        let body = try encoder.encode(RefreshRequest(refreshToken: refresh))
        let context = RequestContext(requestID: UUID().uuidString, idempotencyKey: UUID().uuidString)
        let established: SessionEstablished = try await request("auth/token/refresh", method: "POST", query: [], body: body, canRefresh: false, context: context)
        guard let tokens = established.mobileTokens else { throw SessionInvalidatedError() }
        return tokens
    }

    private func makeRequest(path: String, method: String, query: [URLQueryItem], body: Data?, contentType: String, context: RequestContext) throws -> URLRequest {
        var request = URLRequest(url: try endpoint(path, query: query))
        request.httpMethod = method
        request.httpBody = body
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("IOS", forHTTPHeaderField: "X-Channel")
        request.setValue(context.requestID, forHTTPHeaderField: "X-Request-Id")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let idempotencyKey = context.idempotencyKey {
            request.setValue(idempotencyKey, forHTTPHeaderField: "Idempotency-Key")
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        }
        keychain.access().map { request.setValue("Bearer \($0)", forHTTPHeaderField: "Authorization") }
        return request
    }

    private func downloadRequest(url: URL, context: RequestContext, includeAuthorization: Bool) -> URLRequest {
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("IOS", forHTTPHeaderField: "X-Channel")
        request.setValue(context.requestID, forHTTPHeaderField: "X-Request-Id")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if includeAuthorization { keychain.access().map { request.setValue("Bearer \($0)", forHTTPHeaderField: "Authorization") } }
        return request
    }

    private func endpoint(_ path: String, query: [URLQueryItem]) throws -> URL {
        let url: URL
        if let absolute = URL(string: path), absolute.scheme != nil { url = absolute }
        else if path.hasPrefix("/v1/") {
            guard var origin = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else { throw URLError(.badURL) }
            origin.path = path; origin.query = nil
            guard let built = origin.url else { throw URLError(.badURL) }; url = built
        } else {
            let relative = path.hasPrefix("/") ? String(path.dropFirst()) : path
            url = baseURL.appendingPathComponent(relative)
        }
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { throw URLError(.badURL) }
        if !query.isEmpty { components.queryItems = query }
        guard let result = components.url else { throw URLError(.badURL) }
        return result
    }

    private func trustedDownloadEndpoint(_ path: String) throws -> URL {
        let url = try endpoint(path, query: [])
        if isSameOrigin(url) { return url }
        guard url.scheme?.lowercased() == "https", let host = url.host?.lowercased(), trustedDownloadHosts.contains(host) else { throw URLError(.secureConnectionFailed) }
        return url
    }

    private func absoluteRedirect(_ value: String) throws -> URL {
        guard let parsed = URL(string: value) else { throw URLError(.badURL) }
        if parsed.scheme != nil { return parsed }
        guard let origin = URL(string: "/", relativeTo: baseURL)?.absoluteURL,
              let resolved = URL(string: value, relativeTo: origin)?.absoluteURL else { throw URLError(.badURL) }
        return resolved
    }

    private func normalizeMedicalRedirect(_ value: URL) throws -> URL {
        guard var components = URLComponents(url: value, resolvingAgainstBaseURL: false) else { throw URLError(.badURL) }
        if let host = components.host?.lowercased(), (host == "localhost" || host == "127.0.0.1"), let apiHost = baseURL.host {
            components.host = apiHost
            if components.port == nil { components.port = baseURL.port }
        }
        var query = components.queryItems ?? []
        if !query.contains(where: { $0.name == "embedded" }) { query.append(URLQueryItem(name: "embedded", value: "1")) }
        components.queryItems = query
        guard let result = components.url else { throw URLError(.badURL) }
        return result
    }

    private func isSameOrigin(_ url: URL) -> Bool {
        func port(_ value: URL) -> Int? { value.port ?? (value.scheme == "https" ? 443 : 80) }
        return url.scheme?.lowercased() == baseURL.scheme?.lowercased() && url.host?.lowercased() == baseURL.host?.lowercased() && port(url) == port(baseURL)
    }

    private func isAuthenticationPath(_ path: String) -> Bool { path.contains("auth/login") || path.contains("auth/token/refresh") }
    private func shouldRefresh(response: URLResponse, data: Data) -> Bool {
        guard let http = response as? HTTPURLResponse else { return false }
        if http.statusCode == 401 { return true }
        return (try? decoder.decode(APIErrorBody.self, from: data).code) == "AUTHORIZATION_VERSION_STALE"
    }

    private func validate(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        guard 200..<300 ~= http.statusCode else {
            let body = (try? decoder.decode(APIErrorBody.self, from: data)) ?? APIErrorBody(code: "NETWORK_ERROR", messageKey: nil, requestId: nil)
            if http.statusCode == 401 || body.code == "AUTHORIZATION_VERSION_STALE" { throw SessionInvalidatedError() }
            throw APITransportError(status: http.statusCode, body: body)
        }
    }
}
