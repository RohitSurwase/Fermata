# Module: chat (OpenAI ChatGPT)

- Gradle: `modules/chat/build.gradle` · package `me.aap.fermata.addon.chat` · dynamic-feature.
- Source files (4).

## Role
A ChatGPT conversation panel backed by the **OpenAI API**.

## Key files
| File | Role |
|---|---|
| `ChatAddon.java` | Addon entry (`FermataFragmentAddon`). Prefs: `OPENAI_KEY` (string, default `""`), `MODEL` (int index into `{gpt-4o, chatgpt-4o-latest, gpt-4o-mini, o1, o1-mini, o1-preview}`), `MODEL_OTHER` (free-text override), `CHAT_LANG` (default device language tag). `contributeSettings` builds the settings UI and links to `https://platform.openai.com/api-keys`. |
| `ChatFragment.java` | Chat UI fragment. |
| Request/response helpers | Build the OpenAI chat-completions request using the stored key + model and render replies (HTML via `HtmlCompat`). |

## Security-relevant grounding (see 04_critical_findings §Security)
- The OpenAI API key is entered by the user and **stored in plaintext in the Fermata
  `SharedPreferences`** (`ChatAddon.getOpenaiKey()` → `getStringPref(OPENAI_KEY)`), not in the
  Android Keystore/EncryptedSharedPreferences.
- Requests go to OpenAI's servers; this is user-initiated and expected for the feature.
