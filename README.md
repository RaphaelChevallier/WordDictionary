# MaxFluency

Native Android (Kotlin/Jetpack Compose) and iOS 18+ (SwiftUI) vocabulary clients for
English, French, and Spanish. Both install a small starter bank, use public dictionaries
for explicitly requested online lookups, and offer optional full offline downloads.

Android APKs are processor-specific so a phone does not download the translation engine four
times. Locally signed optimized builds measure **13.97-15.26 MB per APK**
(**ARM64: 14.76 MB**), with a build-enforced **19 MB per-device limit**.
Most modern phones use **arm64-v8a**.
Full dictionaries and optional translation models download separately after installation.

## Repository layout

- [apps/android](apps/android): the complete Android Gradle project, including its JVM core.
- [apps/ios](apps/ios): native SwiftUI app, Xcode project, unit/UI tests and signing template.
- [content](content): shared pinned dictionaries, pronunciation data, ranks and licences.
  The Apple zlib packs are reproducible derivatives of the same words, not an edited fork.
- [tools](tools): shared build/content tools. Run the existing Android npm commands from
  the repository root; open `apps/android` when importing into Android Studio.
- [version.properties](version.properties): the common app/content release version.

The initial iOS source port includes the current screens and integrations. Its Apple
SDK build, simulator UI and real-device services must still be checked on a Mac;
Windows content/project/syntax checks are **not** an Xcode build.
See the [iOS setup and platform notes](apps/ios/README.md).

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
A successfully refreshed set settles in with a subtle 220-ms fade-up over 4 dp. Existing
words stay steady while replacements load. Opening definitions, returning to a tab and
changing the day, language or level do not replay this manual-refresh animation.

### Opening and responsiveness

The Android launch screen stays in place until the selected words and their full
definitions are ready, rather than opening onto an empty garden or loading indicator.
Discovery ranks the compact headword index and decodes only the chosen definitions,
including words from downloaded dictionaries. No network warm-up is required.

Daily definitions stay pinned in memory; other recently prepared definitions use a small,
bounded cache. Garden, daily-card, search-result, and saved-word taps show their available
definition immediately, before history and learning-progress writes. Screen navigation
does not add a timed fade. Definition pages cover the whole learning pane with an opaque
background, so the previous page's language and search controls cannot show through.
Dictionary and storage errors remain visible and recoverable.

### Daily word calendar

Tap the **calendar icon in the top bar** from Today or the alternate learning tab.
The monthly calendar shows a date and daily word in each tile. Browse earlier months,
return to **Today**, or tap a date to open its full word card with definitions, examples,
Listen, favourites and optional translation. Back returns to the same calendar month.
Future days are disabled and their words are not revealed.

The calendar has its own language and vocabulary-level browsing controls; they do not
change the dictionary or learning level selected in Settings. Daily choices are kept
per **local date, language and level**, matching the app's personalized daily selections.
Existing saved selections are preserved. For a date without a saved selection, opening
its month assigns a word once from the bundled offline starter bank for that level.
These cards say **Assigned offline for this date**: they are new assignments, not a
claim to reconstruct an unsaved historical word. Only the requested month is filled,
up to the current date; no download or background scheduling is needed.

Archived snapshots keep their meanings and examples even when garden rounds are pruned,
recent-word history rolls over, or a full dictionary is removed. Browsing a calendar
does not reshuffle Today, replace pinned definitions, or count unvisited dates as study.
Opening a dated definition records a normal word visit. An already assigned word also
stays fixed if that language/level's Today view is opened later.

The nondestructive personal-database **schema 4** migration preserves existing favourites,
learning progress and garden rounds, and archives the daily selections still stored by
older versions. The old seven-day garden cache cannot recover dates it had already
discarded; those dates receive the offline assignments described above. Calendar
snapshots are retained separately, and opening them does not require the word to remain
in the current dictionary or recent-word list.

### Daily reminders

Turn on **Daily word reminder** in Settings. It defaults to **9:00 AM local time**;
the time is editable and the switch turns it off. Android 13+ asks for notification
permission only when enabling it. If Android blocks the app or its reminder channel,
Settings explains this and links to Android's notification controls.

The notification uses the **daily word as its title and its first eligible definition
as the text underneath**. Expand it to read the definition; tap it to open the matching
dated word card, including after the app has closed. It follows the selected dictionary
language and level, not the calendar's independent browsing controls.

Reminders use persisted, unique WorkManager jobs and do not need a connection, exact-alarm
permission or a foreground service. Time and timezone changes reschedule the local time;
Android handles persisted work after reboot. Delivery is approximate: battery-saving
restrictions or a powered-off/force-stopped app can delay or prevent it. Missed past days
are not replayed. A persisted daily guard prevents duplicate notifications, including
after retries, settings changes or reopening the app. Disabling reminders cancels pending
work and the current daily notification.

## Export to Anki

Use the **three-dot menu > Export to Anki** on a word card, or **Library > Saved words > Export saved words to
Anki**. Each export lets the user choose:

- **Save deck file (.apkg)**: an offline, text-only deck package for Anki-compatible clients,
  without installing AnkiDroid or granting its database permission.
- **Send to AnkiDroid**: uses the installed Android app's
  [documented local ContentProvider API](https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API).
  Install/open AnkiDroid first and explicitly grant Android's AnkiDroid database-access
  permission. The optional direct action is disabled when AnkiDroid is not installed.
  AnkiDroid API access must also be enabled in AnkiDroid.

Cards show the word/language on the front and **all original meanings with their own
examples on the back**, retaining Wiktionary source links and CC BY-SA credits.
Translations, audio, user study history and CMU pronunciation assets are not exported.
Exports contain one forward card per distinct language/spelling, with a MaxFluency note
type and deck. Only the chosen word or saved favourites are exported, not entire downloaded
dictionaries.

Neither method calls a hosted Anki/AnkiWeb service, needs an API key, triggers a sync or
sends vocabulary to our server. The save destination is user-chosen; a cloud document
provider may upload the file according to its own settings. The Anki apps control their
own import and sync behavior.

Safeguards include one active export, a five-second repeat-action cooldown, a 5,000-word
per-export limit, HTML-escaped source text and bounded note sizes. Direct sending uses
at most **20 notes / 200 KB per batch**, with a **500-ms pause between batches**. It checks
MaxFluency note keys before sending, skips existing notes and verifies that the notes
were retained. Partial errors are shown explicitly; retrying skips previously accepted
notes and does not change Anki review progress.

Deck files use stable GUIDs so re-importing the same export does not duplicate its notes.
Anki controls import/update options; mixing file imports with earlier direct sends, or
changing note types/identities in Anki, may need manual duplicate review. The exported
legacy-compatible package format has been imported, rendered and re-imported using
the official Anki engine, including a check that existing review progress survives.
Direct sending is tested against the documented local provider contract; a physical
AnkiDroid install is still needed for device-level verification.

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
An audit against the current native Spanish Kaikki extraction found substantial overlap,
not another dictionary containing hundreds of thousands of missing Spanish lemmas.
A small bundled supplement adds **324 missing lookup entries**: 32 lexical spellings,
274 expressions and 18 affixes. These are not 324 independent new words. Pure form
references, explicitly imprecise definitions, citation-needed entries and malformed glosses
were excluded. Uncertain overlapping sense differences were not automatically merged.
The additions are lookup-only; existing daily discovery stays unchanged.

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
Dictionary requests happen for explicit lookups; full-pack network traffic starts only after
the user requests a download. Requests to Wiktionary are serialized and have timeouts.
Wildcards and anagrams use local data, including downloaded full dictionaries.
Optional translation has separate model-download and SDK-diagnostic traffic, described below.

Public services can be unavailable or change their responses. Their terms and attribution
requirements still apply. Before a public store release, supply a real project contact
in the descriptive API User-Agent and recheck the public-source integration tests.

### Multiple meanings and examples

The full word card lists separate numbered meanings; the daily card is intentionally a
short first-meaning preview. When more meanings are available, a quiet **1 more meaning**
or **2 more meanings** hint with a chevron appears below it; tap the hint or the card
itself to open every meaning. The count comes from the already-prepared full entry,
including grammatical senses omitted from daily previews, with no extra lookup.
Single-meaning words show no hint. For example, French **abjurer** retains both the
religious and figurative meanings, including when the full French dictionary is installed.

Word cards show usage examples under the meaning they illustrate. **20,990 examples**
are bundled for **3,822 headwords** across English, French and Spanish. These are source
usage examples, not generated sentences. Up to three complete examples per meaning are
retained; attributed literary quotations are excluded. Not every word or meaning has a
usable example, so the app does not invent one. Existing full-pack definitions remain
authoritative: bundled examples are attached only to an unambiguous matching meaning,
not guessed from the word alone. Fresh explicit online lookups also retain supported
usage-example markup.

The polished bank contains **40,753 meanings**, recovering useful source senses that
the original eight-meaning import had omitted. Source usage labels such as figurative,
archaic, informal and regional qualifiers remain visible in the dictionary's language.
Sentence examples are preferred over short phrases when both are available, without
inventing a sentence when only a useful phrase exists. Nested-sense examples belong to
their precise meaning, not every ancestor definition.

Detached numerical footnotes and raw wiki formatting are cleaned. Source warnings such
as **cita requerida** and **definición imprecisa** are retained rather than erased to make
uncertain data look authoritative. Examples containing embedded citation URLs, dated
attributions or unfinished templates are excluded. The undefined Spanish source entry
**funeraria**, whose native definition is only `...`, is explicitly omitted from the
starter/discovery bank instead of being assigned a fabricated meaning. Existing saved
and calendar snapshots remain untouched.

Examples open offline with the prepared word, survive saved snapshots and are included
when copying the original card with its source links and licence. No extra network
request is made to fetch examples when opening a word.

### Pronunciation

Daily-word and definition cards keep **Listen** and do not display specialist IPA.
For covered English words they also show a **Pronunciation:** guide such as
**disaster: dih-ZAS-ter** or **beautiful: BYOO-tuh-fuhl**. Capitals mark the main stress.
The guides are approximate North American English respellings derived from a pinned,
freely licensed CMUdict snapshot, not publisher-supplied respelling text or a claim that
every regional accent is identical. Up to two distinct source-listed pronunciations can
appear for words with alternatives, such as **read: RED / REED**.

French, Spanish, unmatched spellings, and unsupported all-capital acronyms remain
Listen-only. No French historical dictionary has been added. The English guide index
contains about 126,000 spellings and is independent of the definition/cache word limit.
It is read directly from the APK with a compact offset index, not unpacked or parsed in
full at startup. Normal builds verify local pinned data and do not download CMUdict.
English source credits, the guide key, and the complete CMU copyright/license notice
are available under **Settings > Offline data & licenses**.

Dictionary transcriptions remain in the stored source metadata for compatibility and
future review. They come from Wiktionary/Kaikki or downloaded dictionaries and may represent
different regional accents; they are not a single official pronunciation.

Playback uses an installed, non-network device text-to-speech voice for the dictionary's
language. It is not labelled as a publisher recording. Some Wiktionary pages have recorded
audio too, but this version does not download, bundle, or stream those recordings.
Playback does not require a supplied transcription. Stored inflected-alias metadata does
not borrow a differently spelled base word's transcription merely because both share a
definition record.

The readable English guide is prepared before a garden word is displayed and is retained
in saved snapshots. Its spelling and accent can differ from the installed device voice;
use Listen for audio. Updating pronunciation data explicitly uses
`npm run pronunciation:generate`; `npm run pronunciation:verify` checks the pinned asset.

## Credits and licenses

Dictionary contributor credits, adaptation notices, licenses, and original-word links live in
**Settings > Offline data & licenses**, not on definition pages. The credits page
provides original URLs for the latest viewed and featured words. Copied definitions
retain their source URLs and the CC BY-SA license URL.

## Optional definition translation

When a word's language differs from the **phone's system language**, its full word card
offers **Show word and meanings in [language]**. The same action translates the
**headword, every displayed meaning and every displayed example**, keeping each example
with its meaning. The target does not follow the app's interface
language or the selected dictionary. The original meanings always remain available,
including when translation is unavailable or fails. Opening a word does not translate it
or download a model, and translation availability checks never delay the original card.

- Android 12+ first checks for a ready, installed system translation service and language
  pair. This service is optional and is not supplied on every phone.
- Otherwise the explicitly labelled **Translate with Google** action uses
  [Google Translate](https://translate.google.com/) through
  [ML Kit on-device translation](https://developers.google.com/ml-kit/language/translation).
  If models are missing, a separate confirmation offers downloads of about **30 MB per
  required language**, over Wi-Fi. Downloads never start merely from opening a word.
- Downloaded models are reused, and translation subsequently works without a connection.
  Models installed privately by another translation app cannot automatically be reused.
- **Hide** keeps the result for showing it again while that card is open. Changing the word, meanings, examples or phone language
  cannot attach an older translation to the new definition.
- **Settings > Remove translation downloads** removes this app's downloaded models without
  deleting dictionaries, favourites or progress.

Results are marked as machine translations; Google results also carry the supplied Google
attribution. No definition text or translated result is sent to Google's servers. However,
**the Google SDK sends device, installation, usage and performance diagnostics, including
configured languages**, and may fetch SDK configuration information. This is disclosed in
Settings and the model confirmation; see
[ML Kit privacy](https://developers.google.com/ml-kit/terms) and
[Android data disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
The full translation disclaimer is available with the offline licences.
Machine translation may be inaccurate, especially when non-English pairs use English as an
intermediate language. A standalone word translation is contextual and is not an exhaustive
list of dictionary equivalents; compare the translated meanings and examples with the originals.

## Local storage and learning

- The bundled dictionaries contain **8,235 entries**: EN 2,465; FR 2,692; ES 3,078.
- Starter words plus the rotating online-result cache share a ceiling of **500,000 entries
  across all languages**. This is a ceiling, not a bulk-download target.
- The result cache evicts repeatedly known/viewed entries before unused entries, with older
  access as a tie-breaker. Favourites and the current daily selections are protected.
- Full user-requested offline dictionaries have separate storage and are not trimmed to
  fit that limit.
- Dated daily-word snapshots are retained separately from the seven-day garden cache
  and the rolling recent-word list. Missing calendar dates are assigned only on demand.
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

## Build Android locally

Requirements: JDK 17-24 (21 recommended), Android SDK platform 36, build-tools 35.0.0,
and Node.js 24+. Node and the large build-source snapshots never ship as app code.
The development-only Swift syntax checker requires the root npm dependencies.

```powershell
npm ci
npm test
npm run test:android
npm run build
npm run build:release
```

Outputs:

- `apps\android\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk` - installable ARM64 development build.
- `apps\android\app\build\outputs\apk\release\app-arm64-v8a-release-unsigned.apk` - optimized ARM64
  release, requiring an owner-provided production signing configuration.
- Equivalent APKs are generated for **armeabi-v7a** (32-bit ARM), **x86**, and **x86_64**.
  Install only the APK matching the device, not all four. `adb shell getprop ro.product.cpu.abi`
  identifies the processor when a phone is connected.

The build reads Android's output metadata, prints every APK's actual size and enforces the
19 MB optimized-app goal for each. Native libraries use standard compressed APK packaging
and Android extracts the matching libraries during installation, not when opening a word.
No language, meaning, pronunciation asset or CPU architecture was removed to meet the goal.
For Play Store delivery, an Android App Bundle lets the store choose the device's architecture.
Large dictionaries and translation models download separately rather than hiding in the APK.
No signing key, credentials, or publishing configuration is included.
Use a locally signed optimized release for phone performance testing: debug builds include
development tooling and are not representative of release speed.

The helper recognizes installed Windows JDKs, `JAVA_HOME`, `MAXFLUENCY_JAVA_HOME`,
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, and the ignored project-local SDK. It changes only
its child process environment.

## Build iOS on a Mac

Transfer a checkout **containing these local changes**, including the shared root
content, to your Mac. Open `apps/ios/MaxFluency.xcodeproj` in Xcode 16+ and choose the
MaxFluency scheme. No Android SDK is needed for the iOS app.

From that Mac checkout:

```sh
npm ci
npm run ios:prepare
npm run test:ios
```

`npm run build:ios` performs an unsigned simulator build; `test:ios` selects an installed
iOS 18+ simulator and runs the native unit/UI test targets. On Windows,
`npm run verify:ios` verifies exact shared assets, project wiring and Swift syntax only.
Physical-iPhone translation, notification delivery, installed voices and AnkiMobile
handoff require separate device checks. Apple language packs are system-managed;
AnkiMobile is optional and sold separately, while `.apkg` export requires no Anki purchase.

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
The [Spanish coverage supplement](content/supplements/es-coverage.json) records the source
and comparison-archive hashes and the exact reviewed lookup delta. The starter generator
merges it without duplicate headword identities; the manifest pins its checksum.
The [usage example supplement](content/supplements/usage-examples.json) separately pins
the exact source archives for polished meanings and matching sense-level examples, plus
an explicit list of undefined source exclusions. Ordinary builds verify this
local data; `npm run examples:generate` explicitly refreshes it from existing verified
build-source caches, with no source downloads. Original example URLs follow the containing
word's native Wiktionary source links. Imports retain up to 64 senses instead of the former
eight-sense limit; additional contributions beyond this bound are reported, not silently
represented as complete coverage. Generation starts from pinned source baselines, not
its own polished output, and keeps excluded placeholders excluded on repeated runs.
The builder fingerprint detects changed cleanup rules and requires explicit regeneration;
ordinary app builds never scan or download the large raw source archives.

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
Focused translation tests cover real native-service capability selection, regional system
language tags, first-use consent, original/translated sense ordering, failures, cached
reopening, model removal and late-result isolation. Translation-flow tests use a controlled
engine, not real downloaded models. Actual system/Google translation quality and model
downloads still require an Android device; no device or emulator was connected for these checks.
They also check headword/example translation alignment, old sense JSON compatibility,
packed examples, attributed-quotation rejection, saved examples and the actual downloaded
French dictionary's two **abjurer** meanings alongside the bundled examples.
Real Android/Fire device testing and production signing are still needed before store publication.

## Formats, privacy, and licensing

- Starter dictionaries use the portable `MFLX0003` format: front-coded word pages,
  ordinal indexes, and bounded, independently Zstandard-compressed definition blocks.
  Readers accept old three-field sense tuples and an optional fourth field containing
  bounded usage examples. Old saved JSON senses default to no examples. Calendar history
  uses the nondestructive schema-4 migration; no personal database reset is required.
- Full public packs use StarDict indexes plus dictzip data. The installer validates archive
  CRCs, metadata, word/variant counts, offsets and SQLite integrity before atomic publication.
  Definitions stay compressed; a bounded random-access dictzip reader decodes requested chunks.
- A mutable SQLite cache stores online results; a separate persistent database stores user data.
  No vector database or embedding model is needed.
- Downloaded HTML is parsed into text, never executed or displayed in a web view.
- There are no ads, accounts, owner-run analytics, profile uploads or automatic cloud backups.
  Public dictionary providers receive the requested word/IP address for online requests.
  The optional Google translation SDK collects its own diagnostics as disclosed above;
  its input text and translated outputs stay on-device.
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
dictionary features without Google Play Services. Optional translation support and model
downloads need separate validation on Fire devices.
