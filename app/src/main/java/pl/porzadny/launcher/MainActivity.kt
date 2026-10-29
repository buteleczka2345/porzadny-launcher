package pl.porzadny.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.os.Process
import android.os.UserHandle
import android.provider.MediaStore
import android.text.Editable
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
            hint = "🔍 Szukaj aplikacji"
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
            return
        }
        val byKey = apps.associateBy { it.key }
        val dsk = desk.mapNotNull { byKey[it] }
        content.addView(header("🏠 Pulpit"))
        if (dsk.isEmpty()) content.addView(text("Przytrzymaj palec na ikonie → „Skrót na pulpit”", 14f))
        else content.addView(grid(dsk) { desk = it; render() })

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

    /** [reorder] podany: przytrzymaj i przeciągnij ikonę na inną, żeby zmienić kolejność; puszczenie w miejscu otwiera menu. */
    private fun grid(list: List<App>, reorder: ((List<String>) -> Unit)? = null): GridLayout {
        val cols = if (big) 4 else 5
        val g = GridLayout(this).apply { columnCount = cols }
        for (a in list) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
                addView(ImageView(context).apply { setImageDrawable(a.icon) }, LinearLayout.LayoutParams(dp(if (big) 64 else 48), dp(if (big) 64 else 48)))
                addView(text(a.label, if (big) 16f else 12f).apply { gravity = Gravity.CENTER; maxLines = 2 })
                setOnClickListener { launcher.startMainActivity(a.component, a.user, null, null) }
                if (reorder == null) setOnLongClickListener { menu(a); true }
                else {
                    setOnLongClickListener { startDragAndDrop(null, View.DragShadowBuilder(this), a.key, 0); true }
                    setOnDragListener { v, e ->
                        when (e.action) {
                            DragEvent.ACTION_DRAG_ENTERED -> v.setBackgroundColor(0x33FFFFFF)
                            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> v.background = null
                            DragEvent.ACTION_DROP -> {
                                val from = e.localState as String
                                if (from == a.key) menu(a)
                                else {
                                    val keys = list.map { it.key }.toMutableList()
                                    val to = keys.indexOf(a.key)
                                    keys.remove(from)
                                    keys.add(to, from)
                                    reorder(keys)
                                }
                            }
                        }
                        true
                    }
                }
            }
            g.addView(cell, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0 })
        }
        // pusta komórka, żeby jedna ikona w rzędzie nie rozciągała się na całą szerokość
        repeat((cols - list.size % cols) % cols) {
            g.addView(View(this), GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply { width = 0; height = 0 })
        }
        return g
    }

    private fun menu(a: App) {
        val isFav = a.key in favorites
        val onDesk = a.key in desk
        val items = arrayOf(if (isFav) "Usuń z ulubionych" else "⭐ Do ulubionych", "📁 Przenieś do folderu…", if (onDesk) "Usuń z pulpitu" else "📲 Skrót na pulpit", "ℹ Informacje o aplikacji")
        AlertDialog.Builder(this).setTitle(a.label).setItems(items) { _, i ->
            when (i) {
                0 -> { favorites = if (isFav) favorites - a.key else favorites + a.key; render() }
                1 -> moveDialog(a)
                2 -> { desk = if (onDesk) desk - a.key else desk + a.key; render() }
                3 -> launcher.startAppDetailsActivity(a.component, a.user, null, null)
            }
        }.show()
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
