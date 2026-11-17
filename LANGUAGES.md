# Languages

Bundled translations: French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`), Russian (`ru`), alongside the existing languages. Translated tutorials are included when the plugin has an English tutorial.

With Vystorm Core, select a language in Settings ? Language or use `/language fr`, `/language pt_br`, `/language it`, `/language ru`. `/language auto` follows the Minecraft client. Regional locales fall back to the base language; Portuguese from Portugal falls back to Brazilian Portuguese.

The first load copies missing bundles to `plugins/<plugin>/lang/`. Administrators can edit these UTF-8 YAML files. Existing custom translations are preserved. Keep keys, `{placeholders}`, format specifiers, markup tags and command identifiers unchanged.

The new translations are machine-assisted with terminology corrections and sample review. Automated checks cover resource availability, key coverage, list lengths and runtime tokens; they do not replace a complete native-speaker language review.
