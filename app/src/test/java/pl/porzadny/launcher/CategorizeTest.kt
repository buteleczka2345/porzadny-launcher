package pl.porzadny.launcher

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class CategorizeTest {
    private val none = ApplicationInfo.CATEGORY_UNDEFINED

    @Test fun reczneWygrywa() =
        assertEquals("Gry", categorize("com.android.chrome", "Gry", "Przeglądarki", none))

    @Test fun nieznanyRecznyFolderIgnorowany() =
        assertEquals("Przeglądarki", categorize("x.y", "Nie ma takiego", "Przeglądarki", none))

    @Test fun rolaPrzedSlowami() =
        assertEquals("Przeglądarki", categorize("com.sec.android.app.sbrowser", null, "Przeglądarki", none))

    @Test fun slowaKluczowe() {
        assertEquals("Przeglądarki", categorize("org.mozilla.firefox", null, null, none))
        assertEquals("Komunikatory", categorize("com.facebook.orca", null, null, none))
        assertEquals("Społeczności", categorize("com.facebook.katana", null, null, none))
        assertEquals("Muzyka i radio", categorize("com.google.android.apps.youtube.music", null, null, none))
        assertEquals("Wideo", categorize("com.google.android.youtube", null, null, none))
        assertEquals("Finanse", categorize("pl.ing.mojeing", null, null, none))
        assertEquals("Zakupy", categorize("pl.allegro", null, null, none))
    }

    @Test fun krotkieSlowoTylkoCalyCzlon() =
        // "ing" w "messaging" nie może dać Finansów
        assertEquals("Telefon i SMS", categorize("com.google.android.apps.messaging", null, null, none))

    @Test fun kategoriaAndroidaGdyBrakSlow() =
        assertEquals("Gry", categorize("com.supercell.clashroyale", null, null, ApplicationInfo.CATEGORY_GAME))

    @Test fun resztaInne() =
        assertEquals("Inne", categorize("com.example.cos", null, null, none))
}
