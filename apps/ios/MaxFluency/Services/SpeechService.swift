import AVFoundation
import Foundation
import os

@MainActor
final class SpeechService: NSObject, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private let logger = Logger(subsystem: "com.maxfluency.app", category: "offline-speech")

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func speak(_ entry: WordEntry) throws {
        let voices = AVSpeechSynthesisVoice.speechVoices().filter {
            $0.language.split(separator: "-").first.map(String.init) == entry.language.rawValue &&
                !$0.voiceTraits.contains(.isPersonalVoice) && !$0.voiceTraits.contains(.isNoveltyVoice)
        }
        let preferred = Locale.preferredLanguages.first { $0.split(separator: "-").first.map(String.init) == entry.language.rawValue }
        guard let voice = voices.first(where: { $0.language == preferred }) ?? voices.first else {
            throw PlatformFailure.message("No installed offline voice is available for \(entry.language.nativeName). Download a voice in iOS Accessibility settings.")
        }
        stop()
        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try audio.setActive(true)
        let utterance = AVSpeechUtterance(string: entry.word)
        utterance.voice = voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.85
        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        deactivate()
    }

    private func deactivate() {
        do { try AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation]) }
        catch { logger.error("Speech audio session could not be released: \(error.localizedDescription, privacy: .private)") }
    }

    private func finishIfIdle() {
        if !synthesizer.isSpeaking && !synthesizer.isPaused { deactivate() }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.finishIfIdle() }
    }
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.finishIfIdle() }
    }
}
