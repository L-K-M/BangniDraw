package ch.lkmc.bangnidraw.desktop

/** Reads the product name from Android's canonical strings file. */
internal object DesktopBrand {
    val displayName: String by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream(RESOURCE_PATH)) {
            "desktop brand resource is missing"
        }.bufferedReader().use { it.readText() }

        val value = APP_NAME_PATTERN.find(text)?.groupValues?.get(1)
        check(!value.isNullOrBlank()) { "app_name is missing from the desktop brand resource" }
        value
    }

    private const val RESOURCE_PATH = "/brand/android-strings.xml"
    private val APP_NAME_PATTERN = Regex(
        """<string\s+name=[\"']app_name[\"'][^>]*>([^<]+)</string>""",
    )
}
