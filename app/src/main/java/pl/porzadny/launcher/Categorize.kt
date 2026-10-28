package pl.porzadny.launcher

import android.content.pm.ApplicationInfo

/** Kolejność folderów na ekranie; "Inne" zawsze na końcu. */
val FOLDERS = listOf(
    "Przeglądarki", "Telefon i SMS", "Komunikatory", "Społeczności", "Poczta",
    "Zdjęcia i aparat", "Wideo", "Muzyka i radio", "Gry", "Mapy i transport",
    "Finanse", "Zakupy", "Praca i biuro", "System i narzędzia", "Inne",
)

// Pierwsza pasująca reguła wygrywa. Słowa < 4 liter muszą być całym członem pakietu (np. "ing" w pl.ing.mojeing).
private val KEYWORDS = listOf(
    "Przeglądarki" to listOf("browser", "chrome", "firefox", "opera", "brave", "duckduckgo", "vivaldi", "emmx"),
    "Telefon i SMS" to listOf("dialer", "contacts", "messaging", "mms"),
    "Komunikatory" to listOf("whatsapp", "telegram", "orca", "signal", "viber", "discord", "skype", "teams", "messenger"),
    "Społeczności" to listOf("facebook", "instagram", "tiktok", "musically", "twitter", "snapchat", "reddit", "pinterest", "linkedin", "threads"),
    "Poczta" to listOf("mail", "outlook"),
    "Zdjęcia i aparat" to listOf("camera", "gallery", "photos", "snapseed"),
    "Muzyka i radio" to listOf("spotify", "music", "tidal", "deezer", "radio", "podcast", "soundcloud", "audiobook"),
    "Wideo" to listOf("youtube", "netflix", "vlc", "player", "disney", "primevideo", "hbo", "tvn", "polsat", "cda", "max", "twitch"),
    "Gry" to listOf("game", "games"),
    "Mapy i transport" to listOf("maps", "waze", "navi", "osmand", "uber", "mtakso", "jakdojade", "pkp", "flight"),
    "Finanse" to listOf("bank", "wallet", "pko", "mbank", "ing", "santander", "revolut", "blik", "millennium", "pekao", "alior", "paypal", "pay"),
    "Zakupy" to listOf("shop", "allegro", "amazon", "aliexpress", "temu", "olx", "vinted", "lidl", "biedronka", "zabka", "ceneo", "shein"),
    "Praca i biuro" to listOf("office", "docs", "sheets", "slides", "drive", "word", "excel", "notes", "keep", "calendar", "pdf", "translate"),
    "System i narzędzia" to listOf("settings", "calculator", "clock", "weather", "files", "filemanager", "flashlight", "compass",
        "recorder", "scanner", "cubot", "mediatek", "vending", "packageinstaller"),
)

private fun keywordFolder(pkg: String): String? {
    val p = pkg.lowercase()
    val parts = p.split('.', '_')
    return KEYWORDS.firstOrNull { (_, words) ->
        words.any { w -> if (w.length < 4) w in parts else p.contains(w) }
    }?.first
}

private fun categoryFolder(category: Int): String? = when (category) {
    ApplicationInfo.CATEGORY_GAME -> "Gry"
    ApplicationInfo.CATEGORY_AUDIO -> "Muzyka i radio"
    ApplicationInfo.CATEGORY_VIDEO -> "Wideo"
    ApplicationInfo.CATEGORY_IMAGE -> "Zdjęcia i aparat"
    ApplicationInfo.CATEGORY_SOCIAL -> "Społeczności"
    ApplicationInfo.CATEGORY_NEWS -> "Inne"
    ApplicationInfo.CATEGORY_MAPS -> "Mapy i transport"
    ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Praca i biuro"
    ApplicationInfo.CATEGORY_ACCESSIBILITY -> "System i narzędzia"
    else -> null
}

/**
 * Do jakiego folderu trafia aplikacja:
 * ręczne przeniesienie > rola w systemie (przeglądarka, telefon…) > nazwa pakietu > kategoria z Androida > "Inne".
 */
fun categorize(pkg: String, manual: String?, role: String?, category: Int): String =
    manual?.takeIf { it in FOLDERS }
        ?: role
        ?: keywordFolder(pkg)
        ?: categoryFolder(category)
        ?: "Inne"
