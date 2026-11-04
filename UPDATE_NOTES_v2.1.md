# Saara update – Phase 1 (source update)

This is a partial source-code update based on the supplied Saara-v2.zip. It is NOT a claim that every requested feature is complete.

## Implemented in this phase
- Added a dedicated **Tasks** bottom tab and moved the existing scheduled-task list out of Features.
- Added editable learning records: users can edit the text and change the category (move between categories).
- Added category creation and category renaming in Learning.
- Added message date/time display in the chat bubbles.
- Renamed the former Shop tab to **Knowledge** and clearly marked Google Sheets sync as not yet active.
- Added SQLite schema migration for category records while retaining existing learned facts.

## Not implemented / not verified yet
- Live camera preview on the user's screen (camera streaming still does not show a local preview).
- Google Sheets OAuth/API sync, sheet selection, refresh and link-wise data.
- General command confirmation/approval gate and separate trusted automation execution policy.
- MacroDroid-equivalent event triggers (location, incoming call, SMS, notification, sensors, app launch, screen content, photo capture, settings changes, etc.).
- Editable assistant invocation name, wake-word engine/toggle, speaker enrollment/voice match.
- Incoming-call wakeup with spoken yes/no flow.
- Robust screenshot capture and WhatsApp document-attach interaction fixes.
- Floating activity/emotion indicator. Any future indicator should represent app status, not claim to detect real AI emotions.

## Build status
The source was edited and whitespace/syntax structure was reviewed, but an Android SDK/Gradle build was not run in this environment. Build and test on a real Android device before installing. Do not treat this ZIP as a production-ready APK.
