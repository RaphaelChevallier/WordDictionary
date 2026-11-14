# MaxFluency

A native Kotlin/Jetpack Compose vocabulary app for English, French, and Spanish.
The app installs a small starter bank, uses public dictionaries for requested online
lookups, and encourages optional full offline language downloads.

The measured optimized release is **6.42 MB**; the installable debug test build is
**18.88 MB**. Full dictionaries are downloaded separately after installation.

## Daily interface

Choose **Word garden** or **Word of the day** under **Settings > Home screen**.
The garden is the default: a typographic cloud with varied word sizes and a few sideways
words. All words remain tappable, with separate touch targets. The chosen view appears
on **Today**; the other view is always available as the second bottom tab.
Choosing Word garden for home adds a Word of the day tab, and vice versa.
Both use the same prepared daily words, and switching tabs never changes the home
preference or the day's selection. Opening either learning tab dismisses an active search
so its garden or daily card is visible; returning from a definition keeps the current tab.

A compact search field sits beside the language selector. Focusing search smoothly expands
the field and shrinks the language button to EN, FR, or ES. It stays expanded while a query
is present; the idle header restores the full name. The menu and accessibility labels
always retain full language names. Anagram controls and search help appear when search
is in use. The bottom navigation shares the garden's background,
without an elevated panel or filled selection pill. Optional accent colours still identify
the selected tab.

The home screen contains no offline/online promotional footer or dictionary-download
controls. Full dictionary downloads, progress, cancellation, and removal live in Settings.

The garden adapts to the available width, height, and reading scale, with no more than
20 words at once and fewer on short or large-text screens. Larger screens enlarge the
typography instead of adding an unbounded number of words. Compact header/footer spacing
and vertically distributed words avoid large empty margins around the cloud.
**New words** is always available for a loaded garden; there is no requirement to open
any words first. Opening definitions never shuffles the set or fades the words, so they
remain available for repeat reviews. Sets and rounds are saved per day, language, and level.
Only explicitly choosing **New words** replaces the garden. Refresh never replaces the
featured word of the day or deletes saved words/history, and does not trigger API scraping.
If a fresh local set is unavailable, the current garden is retained and a message is shown.

### Opening and responsiveness

The Android launch screen stays in place until the selected words and their full
definitions are ready, rather than opening onto an empty garden or loading indicator.
Discovery ranks the compact headword index and decodes only the chosen definitions,
including words from downloaded dictionaries. No network warm-up is required.

Daily definitions stay pinned in memory; other recently prepared definitions use a small,
bounded cache. Garden, daily-card, search-result, and saved-word taps show their available
definition immediately, before history and learning-progress writes. Screen navigation
does not add a timed fade. Dictionary and storage errors remain visible and recoverable.

## Simple offline downloads

Choose a language in Settings and tap **Download full language dictionary**.
The cards and confirmation show only the approximate transfer size and a Wi-Fi recommendation.
Downloads show progress and a cancel button. Android WorkManager keeps an explicitly
requested download running when the app leaves the foreground.

| Language | Public download | Verified indexed words/variants |
|---|---:|---:|
| English | About 50 MB | 1,449,820 |
| French | About 32 MB | 2,056,773 |
| Spanish | About 9.5 MB | 710,357 |

These are real-pack measurements from development, not permanent size guarantees.
Duplicate spellings/variant references are indexed without duplicating lookup results.
The publisher can update its files. **Full downloaded dictionaries are not subject to the
500,000-word temporary-cache limit.** Dictionaries are optional and are not inside the APK.
Device storage is checked automatically before preparing the dictionary and its search index.

Full packs download directly from the public, free StarDict links at **reader.dict**.
No owner-hosted static site, backend, API key, account, or subscription is required.
The standard etymology-free packs include definitions and variants; this app does not
access the publisher's paid formats or claim every possible word is in a particular snapshot.

- [English public dictionary](https://www.reader-dict.com/download/en)
- [French public dictionary](https://www.reader-dict.com/download/fr)
- [Spanish public dictionary](https://www.reader-dict.com/download/es)

Removing a downloaded dictionary does not delete favourites, history, or learning progress.
Incomplete or cancelled downloads never replace an existing installed dictionary.
Installed packs do not update or re-download automatically.

## Online use without our own server

Local dictionaries and cached definitions are searched first. Press the keyboard's search
action or **Look up online** for an exact word that is missing locally:

- English, French, and Spanish use their native Wiktionary MediaWiki APIs. One response
  provides definitions and available pronunciation metadata. The app extracts only the
  selected language's meanings and phonetics, not unrelated language sections.
- Successful lookups are stored for later offline use.
- Internet errors are shown explicitly. A missing local definition is not presented as
  proof that the word does not exist.

There are **no continuous background API calls and no request on each keystroke**.
Online requests happen for explicit lookups; full-pack network traffic starts only after
the user requests a download. Requests to Wiktionary are serialized and have timeouts.
Wildcards and anagrams use local data, including downloaded full dictionaries.

Public services can be unavailable or change their responses. Their terms and attribution
requirements still apply. Before a public store release, supply a real project contact
in the descriptive API User-Agent and recheck the public-source integration tests.

### Pronunciation

The Word of the Day card shows the available dictionary phonetic transcription and a
**Listen (device voice)** button; full definition pages also have pronunciation controls.
The transcriptions come from Wiktionary/Kaikki or the downloaded public dictionary, depending
on the entry. They are community-contributed and can represent different regional accents,
not a single official pronunciation.

Playback uses an installed, non-network device text-to-speech voice for the dictionary's
language. It is not labelled as a publisher recording. Some Wiktionary pages have recorded
audio too, but this version does not download, bundle, or stream those recordings.
An entry can have no supplied transcription; the offline voice button still remains available.
Inflected aliases do not borrow the base word's transcription: for example, a plural must
not be shown with a singular IPA merely because both share the same definition record.

## Credits and licenses

Contributor credits, adaptation notices, licenses, and original-word links live in
**Settings > Offline data & licenses**, not on definition pages. The credits page
provides original URLs for the latest viewed and featured words. Copied definitions
retain their source URLs and the CC BY-SA license URL.

## Local storage and learning

- The bundled starter dictionaries contain roughly 8,000 entries across all three languages.
- Starter words plus the rotating online-result cache share a ceiling of **500,000 entries
  across all languages**. This is a ceiling, not a bulk-download target.
- The result cache evicts repeatedly known/viewed entries before unused entries, with older
  access as a tie-breaker. Favourites and the current daily selections are protected.
- Full user-requested offline dictionaries have separate storage and are not trimmed to
  fit that limit.
- Only the requested definitions and small dictionary blocks are loaded into RAM.
- Existing saved words survive the migration from the original fully bundled version.

Choose a vocabulary level independently for each language, or take the six-question
vocabulary check in Settings. Difficulty bands use licensed word-frequency data and the
estimate can be changed manually. This is **word familiarity, not a certified CEFR or
overall language-proficiency assessment**. Questionnaire answers are never uploaded.

Daily selection prefers the chosen level and less-familiar words. Repeated exposures and
correct answers lower a word's novelty priority. Each language/level/day selection is
persisted so reopening the app does not reshuffle it.

The Word of the Day and daily garden skip adverb/article/determiner-only entries and a
small configured list of basic function words. Mixed-meaning previews show an eligible
meaning, while opening the definition restores the full entry. Lookup definitions are not
deleted merely to curate daily learning.

## Build locally

Requirements: JDK 17-24 (21 recommended), Android SDK platform 36, build-tools 35.0.0,
and Node.js 24+. Node and the large build-source snapshots never ship as app code.

```powershell
npm test
npm run test:android
npm run build
npm run build:release
```

Outputs:

- `app\build\outputs\apk\debug\app-debug.apk` - installable, debug-signed test build.
- `app\build\outputs\apk\release\app-release-unsigned.apk` - optimized release, requiring
  an owner-provided production signing configuration.

The build prints the actual APK sizes. The whole optimized-app goal remains 19 MB;
large dictionaries are downloaded separately rather than hidden in the APK.
No signing key, credentials, or publishing configuration is included.
Use a locally signed optimized release for phone performance testing: debug builds include
development tooling and are not representative of release speed.

The helper recognizes installed Windows JDKs, `JAVA_HOME`, `MAXFLUENCY_JAVA_HOME`,
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, and the ignored project-local SDK. It changes only
its child process environment.

### Starter content

[tools/starter.mjs](tools/starter.mjs) generates a small, frequency-banded starter bank
from the pinned, licensed full-source snapshots, keeping common lookup-only words too.
It preserves the original definition attribution and excludes only daily candidates,
not lookup meanings. `content/starter` is the application's bundled content:

```powershell
npm run content:prepare
npm run content:verify
```

Normal builds verify and use the pinned starter files without contacting dictionary APIs.
On a new app version, the small starter bank is regenerated from the existing source
snapshots; full dictionaries are still public publisher downloads.

The older [tools/lexicon.mjs](tools/lexicon.mjs) importer and [content/sources.json](content/sources.json)
remain maintainer tools for refreshing licensed source snapshots and the discovery policy.
`npm run content:sync` can download several GB of raw build-only data. Those full
`content/packs` snapshots are **not** bundled in the Android app.

## Validation

Normal native tests run without live dictionary services. Explicit public-source tests
verify all three native-language lookup adapters and import the actual full packs, asserting
that each exceeds 500,000 words and still works after network access is no longer involved.

Prepare the public fixtures, then run:

```powershell
New-Item -ItemType Directory -Force .cache\public-dictionaries
foreach ($lang in @('en', 'fr', 'es')) {
    curl.exe --fail --location --output ".cache\public-dictionaries\$lang.zip" "https://www.reader-dict.com/file/$lang/dict-$lang-$lang-noetym.zip"
}
node .\tools\android.mjs :core:test :app:testDebugUnitTest -PpublicDictionaryIntegration=true
```

The explicit full-pack tests need extra temporary disk space. They also check accent-insensitive
offline search and that removing a full dictionary preserves saved definitions.
Real Android/Fire device testing and production signing are still needed before store publication.

## Formats, privacy, and licensing

- Starter dictionaries use the portable `MFLX0003` format: front-coded word pages,
  ordinal indexes, and bounded, independently Zstandard-compressed definition blocks.
- Full public packs use StarDict indexes plus dictzip data. The installer validates archive
  CRCs, metadata, word/variant counts, offsets and SQLite integrity before atomic publication.
  Definitions stay compressed; a bounded random-access dictzip reader decodes requested chunks.
- A mutable SQLite cache stores online results; a separate persistent database stores user data.
  No vector database or embedding model is needed.
- Downloaded HTML is parsed into text, never executed or displayed in a web view.
- There are no ads, trackers, accounts, profile uploads, or automatic cloud backups.
  Public providers necessarily receive the requested word/IP address for online requests.
- Uninstalling the app removes local data; personal export/sync is not implemented.

Definitions are adapted from **Wiktionary contributors**, with extraction by
**Kaikki.org / Wiktextract** for starter content and conversion by **reader.dict** for full packs.
Word-frequency content is from **FrequencyWords / OpenSubtitles**. Data is used under
**CC BY-SA 4.0**, with source links on definitions and the full license available offline.
Parser/background-worker/native-decoder licenses also ship in the app.

- [FrequencyWords data license](https://github.com/hermitdave/FrequencyWords#license)
- [Wiktionary API etiquette](https://www.mediawiki.org/wiki/API:Etiquette)
- [Kaikki source data](https://kaikki.org/dictionary/rawdata.html)
- [Offline licenses](content/licenses)

This is an original implementation, not Flow Simulation's code, branding, assets, or
proprietary dictionary. An application-code distribution license has not yet been selected.
Native iOS and e-ink Kindle clients remain future work; compatible Fire tablets can use the
Android build without Google Play Services.
