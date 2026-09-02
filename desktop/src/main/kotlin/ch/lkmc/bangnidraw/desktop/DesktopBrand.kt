package ch.lkmc.bangnidraw.desktop

/** Reads the product name from Android's canonical strings file. */
internal object DesktopBrand {
    val displayName: String by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream(RESOURCE_PATH)) {
            "desktop brand resource is missing"
        }.bufferedReader().use { it.readText() }

        parseDisplayName(text)
    }

    internal fun parseDisplayName(xml: String): String {
        val encoded = APP_NAME_PATTERN.find(xml)?.groupValues?.get(1)?.trim()
        check(!encoded.isNullOrEmpty()) { "app_name is missing from the desktop brand resource" }

        return decodeXmlText(encoded)
    }

    fun exportFileStem(value: String): String {
        val sanitized = UNSAFE_FILE_CHARACTERS.replace(value.trim(), "_")
            .trimEnd('.', ' ')

        return sanitized.ifEmpty { DEFAULT_EXPORT_FILE_STEM }
    }

    private fun decodeXmlText(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private const val RESOURCE_PATH = "/brand/android-strings.xml"
    private const val DEFAULT_EXPORT_FILE_STEM = "drawing"
    private val UNSAFE_FILE_CHARACTERS = Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]+")
    private val APP_NAME_PATTERN = Regex(
        """<string\b[^>]*\bname\s*=\s*["']app_name["'][^>]*>([^<]*)</string>""",
    )
}
