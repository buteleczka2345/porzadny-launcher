package pl.porzadny.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.os.Process
import android.os.UserHandle
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.Collator
import java.util.Locale

private const val DIR = "dir:"

class App(val key: String, val pkg: String, val label: String, val icon: Drawable, val component: ComponentName, val user: UserHandle, val category: Int)

class MainActivity : Activity() {
    private lateinit var launcher: LauncherApps
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var search: EditText
    private var apps: List<App> = emptyList()
    private var roles: Map<String, String> = emptyMap()
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    // --- ustawienia: ulubione (kolejność ma znaczenie), ręczne przeniesienia, rozwinięte foldery, duże ikony ---
    private var favorites: List<String>
        get() = prefs.getString("fav", "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("fav", v.joinToString("\n")).apply()
    private var desk: List<String>
        get() = prefs.getString("desk", "")!!.split('\n').filter { it.isNotEmpty() }
        set(v) = prefs.edit().putString("desk", v.joinToString("\n")).apply()
    private var moves: Map<String, String>
        get() = prefs.getString("moves", "")!!.split('\n').mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        set(v) = prefs.edit().putString("moves", v.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
    private var expanded: Set<String>
        get() = prefs.getStringSet("open", emptySet())!!
        set(v) = prefs.edit().putStringSet("open", v).apply()
    private var big: Boolean
        get() = prefs.getBoolean("big", false)
        set(v) = prefs.edit().putBoolean("big", v).apply()
    // Aplikacje widziane już wcześniej; trzymane w pamięci telefonu, więc nowe łapiemy nawet po restarcie launchera.
    private var known: Set<String>?
        get() = prefs.getStringSet("known", null)
        set(v) = prefs.edit().putStringSet("known", v).apply()

    private val pendingNew = ArrayDeque<String>()
    private var resumed = false
    private var asking = false

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String, u: UserHandle) = reload()
        override fun onPackageAdded(p: String, u: UserHandle) = reload()
        override fun onPackageChanged(p: String, u: UserHandle) = reload()
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = getSystemService(LauncherApps::class.java)

        search = EditText(this).apply {
            hint = "🔍 Szukaj aplikacji i kontaktów"
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(0xAAFFFFFF.toInt())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = render()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val bigBtn = Button(this).apply {
            text = "A+"
            setOnClickListener { big = !big; render() }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(search, LinearLayout.LayoutParams(0, -2, 1f))
            addView(bigBtn)
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), dp(24)) }
        scroll = ScrollView(this).apply { addView(content) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true // targetSdk 35 rysuje pod paskiem stanu
            setBackgroundColor(Color.BLACK)
            addView(top)
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        })

        launcher.registerCallback(callback, main)
        reload()

        try { startForegroundService(Intent(this, KeepAliveService::class.java)) } catch (e: Exception) { /* system zabronił w tej chwili */ }
        // Raz poproś o wyłączenie oszczędzania baterii — inaczej Cubot i tak potrafi wyrzucić launcher z pamięci.
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("askedBattery", false)) {
            prefs.edit().putBoolean("askedBattery", true).apply()
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (e: Exception) { /* telefon nie ma takiego okienka */ }
        }
    }

    override fun onDestroy() {
        launcher.unregisterCallback(callback)
        super.onDestroy()
    }

    // Przycisk Home, gdy już jesteśmy na ekranie głównym: wróć na górę.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        search.setText("")
        scroll.smoothScrollTo(0, 0)
    }

    @Deprecated("Launcher nie może się zamykać przyciskiem Wstecz")
    override fun onBackPressed() {
        search.setText("")
        scroll.smoothScrollTo(0, 0)
    }

    private fun reload() = Thread {
        val me = packageName
        // shortcut: tylko główny profil użytkownika (bez profilu służbowego), dopisać pętlę po launcher.profiles gdy trzeba
        val list = launcher.getActivityList(null, Process.myUserHandle())
            .filter { it.applicationInfo.packageName != me }
            .map { App(it.componentName.flattenToString(), it.applicationInfo.packageName, it.label.toString(), it.getIcon(0),
                it.componentName, it.user, it.applicationInfo.category) }
            .sortedWith(compareBy(Collator.getInstance(Locale("pl"))) { it.label })
        val r = findRoles()
        main.post {
            apps = list; roles = r
            val keys = list.map { it.key }.toSet()
            val old = known
            if (old != null) pendingNew.addAll(keys - old - pendingNew.toSet()) // pierwsze uruchomienie: nic nie pytamy
            known = keys
            render()
            askNext()
        }
    }.start()

    override fun onResume() {
        super.onResume()
        resumed = true
        askNext()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) = render()

    /** Wyniki z kontaktów pod aplikacjami: 📞 otwiera wybieranie numeru, 💬 nowego SMS-a. */
    private fun contacts(q: String) {
        content.addView(header("👤 Kontakty"))
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            content.addView(text("Dotknij tutaj, żeby szukać też w kontaktach", 14f).apply {
                setPadding(dp(4), dp(8), dp(4), dp(8))
                setOnClickListener { requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 1) }
            })
            return
        }
        // shortcut: zapytanie na głównym wątku, max 20 wyników; przenieść do wątku, gdyby przy tysiącach kontaktów cięło przy pisaniu
        val seen = HashSet<String>()
        contentResolver.query(Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(q)),
            arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, Phone.DISPLAY_NAME)?.use { c ->
            while (seen.size < 20 && c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (seen.add(name + num.filter { it.isDigit() })) content.addView(contactRow(name, num)) // ten sam numer zapisany różnie
            }
        }
        if (seen.isEmpty()) content.addView(text("Brak pasujących kontaktów", 14f))
    }

    private fun contactRow(name: String, num: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), 0, dp(4))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(name, if (big) 20f else 16f).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
            addView(text(num, if (big) 16f else 13f))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Button(context).apply { text = "📞"; setOnClickListener { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", num, null))) } })
        addView(Button(context).apply { text = "💬"; setOnClickListener { startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", num, null))) } })
    }

    /** Nowo zainstalowana aplikacja: pokaż proponowany folder i daj go zaakceptować albo zmienić. */
    private fun askNext() {
        if (!resumed || asking) return
        val a = generateSequence { pendingNew.removeFirstOrNull() }.firstNotNullOfOrNull { k -> apps.find { it.key == k } } ?: return
        val folder = folderOf(a, moves)
        asking = true
        AlertDialog.Builder(this)
            .setTitle("Nowa aplikacja: ${a.label}")
            .setIcon(a.icon)
            .setMessage("Wrzucam do folderu:\n📁 $folder")
            .setPositiveButton("OK") { _, _ -> expanded = expanded + folder; render() }
            .setNeutralButton("Inny folder…") { _, _ -> moveDialog(a) }
            .setNegativeButton("⭐ Ulubione") { _, _ -> favorites = favorites + a.key; render() }
            .setOnDismissListener { asking = false; askNext() }
            .show()
    }

    /** Kto w systemie potrafi otworzyć stronę, zadzwonić, wysłać SMS, zrobić zdjęcie, wysłać maila. */
    private fun findRoles(): Map<String, String> {
        val pm = packageManager
        val m = HashMap<String, String>()
        fun add(i: Intent, folder: String) = pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
            .forEach { m.putIfAbsent(it.activityInfo.packageName, folder) }
        add(Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE), "Przeglądarki")
        add(Intent(Intent.ACTION_DIAL), "Telefon i SMS")
        add(Intent(Intent.ACTION_SENDTO, Uri.parse("sms:")), "Telefon i SMS")
        add(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), "Poczta")
        add(Intent(MediaStore.ACTION_IMAGE_CAPTURE), "Zdjęcia i aparat")
        return m
    }

    private fun folderOf(a: App, mv: Map<String, String>) = categorize(a.pkg, mv[a.pkg], roles[a.pkg], a.category)

    private fun render() {
        content.removeAllViews()
        val q = search.text.toString().trim()
        if (q.isNotEmpty()) {
            content.addView(grid(apps.filter { it.label.contains(q, ignoreCase = true) }))
            contacts(q)
            return
        }
        val byKey = apps.associateBy { it.key }
        content.addView(header("🏠 Pulpit"))
        if (desk.isEmpty()) content.addView(text("Przytrzymaj palec na ikonie → „Skrót na pulpit”", 14f))
        else content.addView(deskGrid(byKey))

        val fav = favorites.mapNotNull { byKey[it] }
        content.addView(header("⭐ Ulubione"))
        if (fav.isEmpty()) content.addView(text("Przytrzymaj palec na ikonie → „Do ulubionych”", 14f))
        else content.addView(grid(fav))

        val mv = moves
        val open = expanded
        val groups = apps.groupBy { folderOf(it, mv) }
        for (f in FOLDERS) {
            val list = groups[f] ?: continue
            val isOpen = f in open
            content.addView(header("${if (isOpen) "▾" else "▸"}  📁 $f (${list.size})").apply {
                setOnClickListener { expanded = if (isOpen) open - f else open + f; render() }
            })
            if (isOpen) content.addView(grid(list))
        }
    }

    private fun grid(list: List<App>) = cells(list.map { a ->
        cell(a.icon, a.label).apply {
            setOnClickListener { launcher.startMainActivity(a.component, a.user, null, null) }
            setOnLongClickListener { menu(a); true }
        }
    })

    /** Pulpit: aplikacje i foldery. Przytrzymaj i przeciągnij na inną ikonę = zmiana kolejności, na folder = wrzuć do folderu; puszczenie w miejscu otwiera menu. */
    private fun deskGrid(byKey: Map<String, App>): GridLayout {
        val shown = desk.filter { it.startsWith(DIR) || it in byKey }
        return cells(shown.map { k ->
            val a = byKey[k]
            val name = k.removePrefix(DIR)
            (if (a != null) cell(a.icon, a.label) else cell(folderIcon(dir(name).mapNotNull { byKey[it] }), name)).apply {
                setOnClickListener { if (a != null) launcher.startMainActivity(a.component, a.user, null, null) else openDir(name) }
                setOnLongClickListener { startDragAndDrop(null, View.DragShadowBuilder(this), k, 0); true }
                setOnDragListener { v, e ->
                    when (e.action) {
                        DragEvent.ACTION_DRAG_ENTERED -> v.setBackgroundColor(0x33FFFFFF)
                        DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> v.background = null
                        DragEvent.ACTION_DROP -> {
                            val from = e.localState as String
                            when {
                                from == k -> if (a != null) menu(a) else dirMenu(name)
                                a == null && !from.startsWith(DIR) -> putInDir(from, name)
                                else -> {
                                    val l = shown.toMutableList()
                                    val to = l.indexOf(k)
                                    l.remove(from)
                                    l.add(to, from)
                                    desk = l
                                    render()
                                }
                            }
                        }
                    }
                    true
                }
            }
        })
    }

    private fun cell(icon: Drawable, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, dp(2), 0, dp(2))
        addView(ImageView(context).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(if (big) 64 else 48), dp(if (big) 64 else 48)))
        addView(text(label, if (big) 16f else 12f).apply { gravity = Gravity.CENTER; setSingleLine(); ellipsize = TextUtils.TruncateAt.END })
    }

    private fun cells(views: List<View>, cols: Int = if (big) 5 else 6): GridLayout {
        val g = GridLayout(this).apply { columnCount = cols }
        for (v in views) g.addView(v, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        // pusta komórka, żeby jedna ikona w rzędzie nie rozciągała się na całą szerokość
        repeat((cols - views.size % cols) % cols) {
            g.addView(View(this), GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = 0 })
        }
        return g
    }

    // --- foldery na pulpicie: na pulpicie wpis "dir:Nazwa", zawartość pod kluczem "dir:Nazwa" ---
    private fun dir(n: String) = prefs.getString(DIR + n, "")!!.split('\n').filter { it.isNotEmpty() }
    private fun setDir(n: String, v: List<String>) {
        if (v.isNotEmpty()) prefs.edit().putString(DIR + n, v.joinToString("\n")).apply()
        else { prefs.edit().remove(DIR + n).apply(); desk = desk - (DIR + n) } // pusty folder znika
    }

    /** Aplikacja z pulpitu do folderu; nowy folder zajmuje jej miejsce. */
    private fun putInDir(key: String, n: String) {
        val d = DIR + n
        desk = if (d in desk) desk - key else desk.map { if (it == key) d else it }
        setDir(n, dir(n) - key + key)
        render()
    }

    private fun openDir(n: String) {
        val byKey = apps.associateBy { it.key }
        lateinit var d: AlertDialog
        val g = cells(dir(n).mapNotNull { byKey[it] }.map { a ->
            cell(a.icon, a.label).apply {
                setOnClickListener { d.dismiss(); launcher.startMainActivity(a.component, a.user, null, null) }
                setOnLongClickListener { d.dismiss(); menu(a, n); true }
            }
        }, if (big) 3 else 4)
        d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("📂 $n")
            .setView(ScrollView(this).apply { setPadding(dp(8), dp(8), dp(8), dp(8)); addView(g) })
            .show()
    }

    private fun pickDir(a: App) {
        val dirs = desk.filter { it.startsWith(DIR) }.map { it.removePrefix(DIR) }
        val choices = (listOf("➕ Nowy folder…") + dirs.map { "📂 $it" }).toTypedArray()
        AlertDialog.Builder(this).setTitle("${a.label} → folder na pulpicie").setItems(choices) { _, i ->
            if (i == 0) askName("Nowy folder", "") { putInDir(a.key, it) } else putInDir(a.key, dirs[i - 1])
        }.show()
    }

    private fun dirMenu(n: String) {
        AlertDialog.Builder(this).setTitle("📂 $n").setItems(arrayOf("✏ Zmień nazwę", "🗑 Usuń folder (aplikacje wracają na pulpit)")) { _, i ->
            if (i == 0) askName("Zmień nazwę", n) { new ->
                if (new == n) return@askName
                val inside = dir(n)
                prefs.edit().remove(DIR + n).apply()
                desk = if (DIR + new in desk) desk - (DIR + n) else desk.map { if (it == DIR + n) DIR + new else it }
                setDir(new, (dir(new) + inside).distinct())
            } else {
                val l = desk.toMutableList()
                val at = l.indexOf(DIR + n)
                l.removeAt(at)
                l.addAll(at, dir(n).filter { it !in l })
                prefs.edit().remove(DIR + n).apply()
                desk = l
            }
            render()
        }.show()
    }

    private fun askName(title: String, start: String, done: (String) -> Unit) {
        val input = EditText(this).apply { setText(start); setSingleLine() }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { done(it); render() } }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    /** Miniaturka folderu: do 4 ikon w kratce 2×2. */
    private fun folderIcon(inside: List<App>): Drawable {
        val s = dp(64)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        GradientDrawable().apply { cornerRadius = s / 5f; setColor(0x44FFFFFF); setBounds(0, 0, s, s) }.draw(c)
        val h = s / 2
        val p = s / 16
        inside.take(4).forEachIndexed { i, a ->
            val x = (i % 2) * h
            val y = (i / 2) * h
            (a.icon.constantState?.newDrawable() ?: a.icon).apply { setBounds(x + p, y + p, x + h - p, y + h - p); draw(c) } // kopia: nie ruszamy granic ikony w siatce
        }
        return BitmapDrawable(resources, bmp)
    }

    private fun menu(a: App, inDir: String? = null) {
        val isFav = a.key in favorites
        val onDesk = a.key in desk
        val items = mutableListOf<Pair<String, () -> Unit>>(
            (if (isFav) "Usuń z ulubionych" else "⭐ Do ulubionych") to { favorites = if (isFav) favorites - a.key else favorites + a.key; render() },
            "📁 Przenieś do folderu…" to { moveDialog(a) })
        when {
            inDir != null -> items += "↩ Wyjmij z folderu na pulpit" to { setDir(inDir, dir(inDir) - a.key); if (a.key !in desk) desk = desk + a.key; render() }
            onDesk -> {
                items += "📂 Do folderu na pulpicie…" to { pickDir(a) }
                items += "Usuń z pulpitu" to { desk = desk - a.key; render() }
            }
            else -> items += "📲 Skrót na pulpit" to { desk = desk + a.key; render() }
        }
        items += "ℹ Informacje o aplikacji" to { launcher.startAppDetailsActivity(a.component, a.user, null, null) }
        AlertDialog.Builder(this).setTitle(a.label).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun moveDialog(a: App) {
        val choices = arrayOf("↩ Automatycznie") + FOLDERS
        AlertDialog.Builder(this).setTitle("Przenieś: ${a.label}").setItems(choices) { _, i ->
            moves = if (i == 0) moves - a.pkg else moves + (a.pkg to choices[i])
            render()
        }.show()
    }

    private fun header(s: String) = text(s, if (big) 22f else 18f).apply {
        setPadding(dp(4), dp(14), dp(4), dp(8))
    }

    private fun text(s: String, sp: Float) = TextView(this).apply {
        text = s
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
