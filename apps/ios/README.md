# MaxFluency for iOS

Native SwiftUI, minimum **iOS/iPadOS 18**, using the same pinned English, French and
Spanish content as Android. There is no JavaScript UI/runtime, owner server or API key.

## Status and validation boundary

The full source port is implemented: garden/daily views, complete meanings and examples,
the additional-meaning hint, search/wildcards/anagrams, saved/history, dated calendar,
appearance/UI/dictionary language settings, assessment, optional dictionaries/online
lookup, native translation, speech, reminders and Anki export.

**It has not been compiled or run with the Apple SDK in the Windows development
workspace.** Shared content round-trips, generated project wiring and local Swift
syntax checks do not prove an Xcode build or runtime feature parity.
Run the Mac checks below before treating this as a verified iOS app.

## Open and run

1. Transfer the repository with these changes to a Mac. Keep the root `content` folder;
   copying only `apps/ios` will leave the shared Xcode resource references unresolved.
   The ignored Android SDK, Gradle caches and multi-GB source dumps are not needed.
2. Install/select full Xcode 16+ with an iOS 18+ simulator.
3. Open `MaxFluency.xcodeproj`, choose the **MaxFluency** scheme and a simulator.
   Xcode resolves the exact pinned SwiftSoup package. No CocoaPods or XcodeGen is needed.
4. Build/run, or run the native unit and UI tests with Product > Test.

For repeatable checks from the repository root:

```sh
npm ci
npm run ios:prepare
npm run build:ios
npm run test:ios
```

The test helper chooses an available iOS 18+ simulator. Set
`MAXFLUENCY_IOS_DEVICE_UDID` to an installed simulator UUID to choose explicitly.
Build/test output is under `apps/ios/build`.

For an iPhone, copy `Config/Signing.xcconfig.example` to `Config/Signing.xcconfig`
and set your Apple team ID. That local file is ignored. You may set a unique
`MAXFLUENCY_BUNDLE_IDENTIFIER` there if needed. No certificate, profile, account,
production signing identity or App Store publication is configured by this port.

## Content and project generation

- `content/starter` is the authoritative pinned source bank.
- `content/apple` contains deterministic indexed per-word zlib frames. Original
  IDs, all meanings, per-meaning examples, source metadata and daily eligibility
  are checked against the Android transport.
- The original compact CMU pronunciation index remains shared under
  `content/pronunciations`; French/Spanish remain Listen-only.
- `tools/apple-content.mjs` prepares/verifies derivatives without scraping source sites.
- `tools/ios-resources.mjs` derives English/French/Spanish resources from the existing
  labels with explicit Apple-platform wording and reuses the same M icon geometry.
- `tools/ios-project.mjs` reproducibly generates the committed project/scheme. Run
  `npm run ios:prepare` after adding/removing native sources or changing shared
  content, localisation or release version. Keep local signing in the separate config.

`npm run verify:ios` can run on Windows. It checks data, resources, project wiring and
Swift syntax **only**; it does not resolve/type-check Apple APIs or run XCTest.
`CoreTests` and `ServiceTests` cover native decoding/state/search/export/contracts;
`NativeFlowTests` exercise real SwiftUI navigation when run on your Mac.

## Native platform differences

| Feature | iOS implementation |
|---|---|
| Pronunciation | CMU English guide plus installed AVSpeechSynthesizer voices; no guessed French/Spanish respelling. |
| Translation | iOS 18 Translation framework, explicit action, ordered headword/meaning/example results and originals retained. Downloads require app confirmation/Wi-Fi and Apple's consent. Apple owns shared language packs; manage them in Translate, not by deleting app-private Google models. |
| Reminders | Off by default, 09:00 local, permission only on enable, word title plus definition and exact archived-word navigation. Up to 60 days are queued offline, renewed on app use and permitted background refresh. iOS controls delivery and may decline background work; open the app within 60 days to renew if background refresh is unavailable. |
| Full dictionaries | Same public reader.dict StarDict/dictzip archives, validated staging, disk search index, progress/cancel/remove. Keep the app open during download/preparation; an interrupted operation can be retried without replacing the installed dictionary. |
| Online lookup | Exact explicit Wiktionary query only; no request per keystroke or garden refresh. HTTPS, bounded responses and serialized/rate-limited requests. |
| Anki file | Local `.apkg`, stable note IDs/model/deck shared with Android, all original meanings/examples and licence credits. No hosted API or automatic sync. |
| Direct Anki | Optional single-note AnkiMobile URL handoff, not Android's ContentProvider/batch API. Import one MaxFluency deck first to create its model/deck. The companion app is sold separately; free file export works without it. Confirmation/duplicate handling occurs in AnkiMobile, not a falsely reported background write. |
| Personal data | Separate iOS sandbox with atomic snapshots, per-language levels/rounds and immutable archives. No automatic transfer from Android or new cloud-sync service. |

Use an actual iPhone to check installed voices, translation/language-download UI,
notification delivery/cold tapping/time changes and AnkiMobile handoff. The simulator
does not establish these device/OEM behaviors.

An iOS release/IPA size has **not** been measured. Shared content stays compressed and
system translation avoids bundling a translation engine, but signed archive/App Store
size and performance must be measured on the Mac/device before release.
