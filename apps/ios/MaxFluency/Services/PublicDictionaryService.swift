import Foundation

actor PublicDictionaryService {
    private let session: URLSession
    private var active = false
    private var lastRequest = Date.distantPast

    init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        configuration.timeoutIntervalForResource = 35
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        session = URLSession(configuration: configuration)
    }

    func lookup(language: DictionaryLanguage, query: String) async throws -> WordEntry? {
        let word = query.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCanonicalMapping
        guard !word.isEmpty, word.count <= 100,
              !word.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }),
              !word.contains("*"), !word.contains("?") else {
            throw PlatformFailure.message("Online lookup needs one exact word of 1–100 printable characters.")
        }
        guard !active else { throw PlatformFailure.message("Please wait for the current online lookup.") }
        active = true
        defer { active = false }
        let delay = 0.75 - Date().timeIntervalSince(lastRequest)
        if delay > 0 { try await Task.sleep(for: .seconds(delay)) }
        try Task.checkCancellation()
        var components = URLComponents()
        components.scheme = "https"
        components.host = "\(language.rawValue).wiktionary.org"
        components.path = "/w/api.php"
        components.queryItems = [
            URLQueryItem(name: "action", value: "parse"), URLQueryItem(name: "page", value: word),
            URLQueryItem(name: "prop", value: "text"), URLQueryItem(name: "format", value: "json"),
            URLQueryItem(name: "formatversion", value: "2"), URLQueryItem(name: "redirects", value: "1")
        ]
        guard let url = components.url else { throw PlatformFailure.message("Dictionary lookup address is invalid.") }
        var request = URLRequest(url: url)
        request.setValue("MaxFluency (iOS; https://github.com/RaphaelChevallier/WordDictionary)", forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        lastRequest = Date()
        let (stream, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse, http.url?.scheme == "https" else {
            throw PlatformFailure.message("The public dictionary returned an invalid response.")
        }
        if http.statusCode == 404 { return nil }
        guard http.statusCode == 200 else { throw PlatformFailure.message("Dictionary lookup failed (HTTP \(http.statusCode)).") }
        var data = Data()
        for try await byte in stream {
            if data.count >= 4_194_304 { throw PlatformFailure.message("Dictionary response exceeds the 4 MiB safety limit.") }
            data.append(byte)
        }
        let result = try JSONDecoder().decode(WikiResponse.self, from: data)
        if let error = result.error {
            if error.code == "missingtitle" { return nil }
            throw PlatformFailure.message("The public dictionary rejected the lookup: \(error.code).")
        }
        guard let html = result.parse?.text else { throw PlatformFailure.message("The public dictionary returned no definition HTML.") }
        return try DictionaryHTML.wiki(language: language, word: word, html: html)
    }

    private struct WikiResponse: Decodable {
        struct Page: Decodable { let text: String }
        struct Problem: Decodable { let code: String }
        let parse: Page?
        let error: Problem?
    }
}

final class DictionaryDownload: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let destination: URL
    private let progress: @Sendable (Double) -> Void
    private var continuation: CheckedContinuation<URL, Error>?
    private var task: URLSessionDownloadTask?
    private var session: URLSession?
    private var cancelled = false
    private var sizeExceeded = false
    private var finished = false

    init(destination: URL, progress: @escaping @Sendable (Double) -> Void) {
        self.destination = destination
        self.progress = progress
    }

    func run(url: URL) async throws -> URL {
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                let configuration = URLSessionConfiguration.ephemeral
                configuration.timeoutIntervalForRequest = 30
                configuration.timeoutIntervalForResource = 900
                configuration.httpCookieStorage = nil
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                var request = URLRequest(url: url)
                request.setValue("MaxFluency (iOS; https://github.com/RaphaelChevallier/WordDictionary)", forHTTPHeaderField: "User-Agent")
                let task = session.downloadTask(with: request)
                lock.lock()
                self.continuation = continuation
                self.session = session
                self.task = task
                let cancelled = self.cancelled
                lock.unlock()
                if cancelled { task.cancel() }
                task.resume()
            }
        }, onCancel: { self.cancel() })
    }

    private func cancel() {
        lock.lock()
        cancelled = true
        let task = task
        lock.unlock()
        task?.cancel()
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesWritten > 100_000_000 || totalBytesExpectedToWrite > 100_000_000 {
            lock.lock(); sizeExceeded = true; lock.unlock()
            downloadTask.cancel()
        } else if totalBytesExpectedToWrite > 0 {
            progress(min(1, Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)))
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        do {
            guard let http = downloadTask.response as? HTTPURLResponse, http.statusCode == 200, http.url?.scheme == "https" else {
                throw PlatformFailure.message("The dictionary download returned an invalid HTTPS response.")
            }
            lock.lock(); let cancelled = cancelled; lock.unlock()
            if cancelled { throw CancellationError() }
            try FileManager.default.moveItem(at: location, to: destination)
            finish(.success(destination))
        } catch { finish(.failure(error)) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error else { return }
        lock.lock()
        let exceeded = sizeExceeded
        let cancelled = cancelled
        lock.unlock()
        if exceeded { finish(.failure(PlatformFailure.message("Dictionary download exceeds the 100 MB safety limit."))) }
        else if cancelled { finish(.failure(CancellationError())) }
        else { finish(.failure(error)) }
    }

    private func finish(_ result: Result<URL, Error>) {
        lock.lock()
        guard !finished else { lock.unlock(); return }
        finished = true
        let continuation = continuation
        self.continuation = nil
        let session = session
        self.session = nil
        lock.unlock()
        session?.finishTasksAndInvalidate()
        continuation?.resume(with: result)
    }
}
