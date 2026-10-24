# Module: felex (language-learning tutor)

- Gradle: `modules/felex/build.gradle` · package `me.aap.fermata.addon.felex` · dynamic-feature.
- Source files (13).

## Role
A vocabulary / dictation **language-learning tutor**: reads dictionaries of words with translations
and examples, then quizzes the user using TTS + speech recognition, tracking per-word progress.

## Key files
| File | Role |
|---|---|
| `FelexAddon.java` | Addon entry (`MediaLibAddon` + fragment); defines dictionary directory and prefs. |
| `dict/DictMgr.java` | Dictionary manager — lists/loads/creates dictionaries from the VFS. |
| `dict/Dict.java` (26 KB) | A dictionary: parses/serializes the word list + progress, random word selection, persistence to a file in the dict dir. |
| `dict/DictInfo.java` | Dictionary header (source/target language, name). |
| `dict/Word.java` | A word with `Translation`s, `Example`s, and a learning-progress value. |
| `dict/Translation.java` / `Example.java` | Value types. |
| `dict/WordMatcher.java` | Fuzzy comparison of a spoken/typed answer against expected translations. |
| `tutor/DictTutor.java` (13 KB) | Quiz state machine: speaks a word (TTS), listens (STT), scores via `WordMatcher`, advances progress. |
| `media/FelexMediaEngine.java` | A `MediaEngine` so a tutoring session integrates with the media session/transport controls. |
| `media/FelexItem.java` | `PlayableItem` representing a dictionary session. |
| `view/FelexFragment.java` (21 KB) / `view/FelexListView.java` (26 KB) | UI for browsing/editing dictionaries and running sessions. |

## Grounding notes
- Uses `me.aap.utils.voice.TextToSpeech` and the app's `SpeechRecognitionService` path for dictation.
- Dictionaries are plain files stored via the VFS (local or network root), not a database.
