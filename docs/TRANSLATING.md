# Translating eikon

eikon is available in **27 languages**: English (the original, `res/values`) and 26 translations. They are the other 23 official languages of the European Union (Bulgarian, Croatian, Czech, Danish, Dutch, Estonian, Finnish, French, German, Greek, Hungarian, Irish, Italian, Latvian, Lithuanian, Maltese,
Polish, Portuguese, Romanian, Slovak, Slovenian, Spanish, Swedish), Chinese, Russian and Japanese. The phone's language picks one; from Android 13 the system settings also offer a language for eikon alone (`res/xml/locales_config.xml`).

## Read this before trusting them

**The translations were written by an AI model and have not been reviewed by native speakers.** Italian is the exception in kind (it was written and used from the start of the project) but has not had a formal review either. Expect stiff phrasing, wrong choices of word for a technical term
(backup, index, hide, merge, analysis) and the odd error, above all in the smaller languages (Irish, Maltese, Latvian, Lithuanian, Estonian). What was checked by machine and what was not:

- Checked (`TranslationsTest`, `app/src/test/…/TranslationsTest.kt`): every language has exactly the strings and plurals of the English original, every `%1$s`/`%d` placeholder of the original is in the translation, each plural has the forms its language needs (the CLDR categories: for example Polish `one/few/many/other`, Latvian `zero/one/other`, Irish and Maltese five forms,
  Chinese and Japanese `other` only) and no others, quotes and apostrophes are escaped so they reach the phone as written, no string is empty or left in English by accident, only the known escapes are used, and the picker offers exactly the languages that have a translation.
- **Not checked**: that the words are right, that a line fits its button (long languages such as Finnish and German may wrap or cut some labels), that right-to-left layout is right (no right-to-left language is offered), or anything on a phone.

Conventions: **Chinese is Simplified** (`zh-rCN`), **Portuguese is European** (`pt`, not Brazilian), and the app's name, `eikon`, and brand names (Immich, Nextcloud, WebDAV, Lightning) are never translated.

## What is not translated

Search reads **English and Italian** (the date words, "photos"/"videos", the words that introduce a place or a person, and the words the image model is asked about). Typing in another language finds file names, captions, text in photos and names of people, but the date words and the "what photos show" phrases are not understood. Adding a
language to the search means a lexicon for its dates and words (`domain/search`) and tests for it; it is a separate piece of work from a translation, and not done. Also in English: what a server sends back when the backup fails, the licenses screen (`NOTICE.md`), and the developer documentation.

## Correcting or adding a language

1. Copy `app/src/main/res/values/strings.xml` to `app/src/main/res/values-<code>/strings.xml` (`values-zh-rCN` for Simplified Chinese, `values-pt-rBR` for Brazilian Portuguese would be a new folder next to `values-pt`) and translate the text of each `<string>` and `<item>`; keep names, placeholders and `<xliff>`-free markup as they are.
2. Give each plural the quantities your language needs: <https://cldr.unicode.org/index/cldr-spec/plural-rules> (the test has the table it expects: `pluralForms` in `TranslationsTest`; add the language there).
3. Escape an apostrophe as `\'` (or use the typographic `’`) and a straight double quote as `\"`; a `%` that is not a placeholder is written `%%`.
4. Add `<locale android:name="<code>" />` to `res/xml/locales_config.xml`, and the code to `androidResources { localeFilters }` in `app/build.gradle.kts` (the release APK keeps only the listed languages).
5. `./gradlew :app:testDebugUnitTest --tests '*TranslationsTest*'` must pass; then `./gradlew :app:lintDebug` (lint reports a missing translation as an error).

A correction to an existing language is a plain edit to its `strings.xml`, a pull request is welcome, and a native speaker's review is the most useful contribution the project can get for this.
