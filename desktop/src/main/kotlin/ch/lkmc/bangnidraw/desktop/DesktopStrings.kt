package ch.lkmc.bangnidraw.desktop

import java.util.Locale

/**
 * The desktop shell's user-visible text, read from the same `strings.xml`
 * files the Android app ships.
 *
 * There is no second copy to translate and no second list to keep in step:
 * a string this shell needs is added to `values/strings.xml` like any other,
 * and lint's `MissingTranslation` — a hard CI gate — makes the second locale
 * mandatory in the same change. Both files are staged into the desktop jar by
 * `processResources`.
 *
 * What this does *not* implement is the rest of Android's resource system:
 * no qualifiers beyond the language, no string arrays, and a plural rule
 * simplified to the two languages the app actually ships (English picks
 * `one` at exactly 1; Chinese has only `other`). Adding a language with a
 * richer plural rule means teaching [plural] that rule.
 */
internal object DesktopStrings {

    /** The catalogue for the JVM's default locale, with English underneath. */
    private val catalogue: DesktopCatalogue by lazy { catalogueFor(Locale.getDefault()) }

    /** The string named [name], with `%1$s`-style arguments substituted. */
    fun get(name: String, vararg args: Any?): String = catalogue.get(name, *args)

    /** The plural named [name] for [count], which is also its first argument. */
    fun plural(name: String, count: Int, vararg args: Any?): String =
        catalogue.plural(name, count, *args)

    /** Testable seam: the object itself can only ever read the default locale. */
    fun catalogueFor(locale: Locale): DesktopCatalogue = DesktopCatalogue(
        locale = locale,
        strings = load(locale),
        plurals = loadPlurals(locale),
    )

    private fun load(locale: Locale): Map<String, String> {
        val fallback = parse(read(DEFAULT_RESOURCE), STRING_PATTERN)
        val localized = resourceFor(locale)?.let { parse(read(it), STRING_PATTERN) }.orEmpty()
        return fallback + localized
    }

    private fun loadPlurals(locale: Locale): Map<String, Map<String, String>> {
        val fallback = parsePlurals(read(DEFAULT_RESOURCE))
        val localized = resourceFor(locale)?.let { parsePlurals(read(it)) }.orEmpty()
        return fallback + localized
    }

    /**
     * Which staged file serves [locale]. Only the languages the app ships
     * have one; everything else reads the default catalogue, which is what
     * Android does for an unlisted locale too.
     */
    private fun resourceFor(locale: Locale): String? = when {
        locale.language == "zh" -> ZH_HANS_RESOURCE
        else -> null
    }

    private fun read(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }.orEmpty()

    /** Visible for the test that pins which elements [STRING_PATTERN] claims. */
    fun parseStrings(xml: String): Map<String, String> = parse(xml, STRING_PATTERN)

    private fun parse(xml: String, pattern: Regex): Map<String, String> =
        pattern.findAll(xml).associate { match ->
            match.groupValues[1] to decode(match.groupValues[2])
        }

    private fun parsePlurals(xml: String): Map<String, Map<String, String>> =
        PLURALS_PATTERN.findAll(xml).associate { match ->
            match.groupValues[1] to parse(match.groupValues[2], ITEM_PATTERN)
        }

    /**
     * Android's own text escaping: XML entities, the backslash escapes `\'`,
     * `\"` and `\n` that `strings.xml` uses for characters XML would otherwise
     * let through unchanged, and `\uXXXX` — which aapt decodes and a plain XML
     * parser does not, so an em dash written that way must not reach a label
     * as six literal characters.
     */
    private fun decode(value: String): String {
        // Android's whitespace rule: a value wrapped in double quotes keeps
        // its spaces and loses the quotes; anything else is trimmed. That is
        // what makes `" copy"` a suffix with a leading space rather than the
        // word alone — and what would otherwise put the quotes on screen.
        val trimmed = value.trim()
        val quoted = trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')
        val text = if (quoted) trimmed.substring(1, trimmed.length - 1) else trimmed
        val unescaped = UNICODE_ESCAPE.replace(text) { match ->
            match.groupValues[1].toInt(radix = 16).toChar().toString()
        }
        return unescaped
            .replace("\\n", "\n")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private const val DEFAULT_RESOURCE = "/strings/values.xml"
    private const val ZH_HANS_RESOURCE = "/strings/zh-Hans.xml"

    private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
    private val STRING_PATTERN = Regex(
        // Lookahead, not `\b`: there is a word boundary between "string"
        // and the "-" of `<string-array>`, so `\b` matches one — and since a
        // `</string-array>` holds no `</string>`, the lazy body would then run
        // past it and swallow the next real string whole.
        """<string(?=[\s>])[^>]*\bname\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</string>""",
    )
    private val PLURALS_PATTERN = Regex(
        """<plurals\b[^>]*\bname\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</plurals>""",
    )
    private val ITEM_PATTERN = Regex(
        """<item\b[^>]*\bquantity\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</item>""",
    )
}

/** One locale's text. [DesktopStrings] holds the default one; tests build others. */
internal class DesktopCatalogue(
    private val locale: Locale,
    private val strings: Map<String, String>,
    private val plurals: Map<String, Map<String, String>>,
) {
    fun get(name: String, vararg args: Any?): String {
        val template = strings[name] ?: return name
        if (args.isEmpty()) return template

        return try {
            String.format(locale, template, *args)
        } catch (_: java.util.IllegalFormatException) {
            // A translation with the wrong placeholders must not crash a
            // panel; the untouched template still says roughly the right
            // thing, and the raw text is the report a translator needs.
            template
        }
    }

    fun plural(name: String, count: Int, vararg args: Any?): String {
        val quantities = plurals[name] ?: return name
        val template = quantities[quantityFor(count)] ?: quantities[OTHER] ?: return name
        val all = if (args.isEmpty()) arrayOf<Any?>(count) else args

        return try {
            String.format(locale, template, *all)
        } catch (_: java.util.IllegalFormatException) {
            template
        }
    }

    /**
     * English distinguishes one from everything else; Chinese does not.
     * Anything else falls back to `other`, the form every language in
     * Android's rules defines.
     */
    private fun quantityFor(count: Int): String =
        if (locale.language == "en" && count == 1) ONE else OTHER

    private companion object {
        const val ONE = "one"
        const val OTHER = "other"
    }
}
