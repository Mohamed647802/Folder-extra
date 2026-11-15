package com.spacemap.app
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
private object C {
    val BG = 0xFF0B1220.toInt()
    val CARD = 0xFF131C2E.toInt()
    val CARD_SEL = 0xFF12304A.toInt()
    val STROKE = 0xFF22304A.toInt()
    val TRACK = 0xFF243049.toInt()
    val TEXT = 0xFFE5E7EB.toInt()
    val SUB = 0xFF94A3B8.toInt()
    val CYAN = 0xFF22D3EE.toInt()
    val PURPLE = 0xFFA78BFA.toInt()
    val GREEN = 0xFF22C55E.toInt()
    val AMBER = 0xFFF59E0B.toInt()
    val RED = 0xFFEF4444.toInt()
}
class MainActivity : Activity() {
    private val disk = Disk()
    private val ui = Handler(Looper.getMainLooper())
    private val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private var pool: ExecutorService? = null
    private var cur = File("/")
    private var entries = ArrayList<Entry>()
    private val selected = LinkedHashSet<String>()
    private var sortMode = 0
    private var gen = 0
    private var pending = 0
    private var started = false
    private var asking = false
    private var requested = false
    private var scheduled = false
    private var protectedDir = false
    private val docCache = HashMap<String, DocumentFile>()
    private var curDoc: DocumentFile? = null
    private val REQ_SAF = 501
    private lateinit var crumbs: LinearLayout
    private lateinit var crumbScroll: HorizontalScrollView
    private lateinit var stats: LinearLayout
    private lateinit var distBar: LinearLayout
    private lateinit var list: ListView
    private lateinit var emptyView: TextView
    private lateinit var busy: ProgressBar
    private lateinit var selBar: LinearLayout
    private lateinit var selText: TextView
    private lateinit var sortBtn: TextView
    private lateinit var selAllBtn: TextView
    private lateinit var safBtn: TextView
    private lateinit var safTip: TextView
    private val adapter = Adapter()
    private val refreshRun = Runnable {
        scheduled = false
        refresh()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }
    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            if (!started) {
                started = true
                loadSafGrants()
                go(Environment.getExternalStorageDirectory())
            }
        } else if (!asking) {
            askAccess()
        }
    }
    private fun loadSafGrants() {
        for ((path, uri) in Saf.all(this)) {
            try {
                val d = DocumentFile.fromTreeUri(this, uri)
                if (d != null && d.canRead()) docCache[path] = d
            } catch (e: Exception) {
            }
        }
    }
    override fun onDestroy() {
        pool?.shutdownNow()
        super.onDestroy()
    }
    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    private fun askAccess() {
        if (Build.VERSION.SDK_INT < 30 && !requested) {
            requested = true
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
            return
        }
        asking = true
        AlertDialog.Builder(this)
            .setTitle("صلاحية الوصول لكل الملفات")
            .setMessage("لعرض كل المجلدات والملفات وحذفها يجب السماح للتطبيق بالوصول إلى كل الملفات. اضغط «فتح الإعدادات» ثم فعّل الخيار.")
            .setCancelable(false)
            .setPositiveButton("فتح الإعدادات") { _, _ -> openSettings() }
            .setOnDismissListener { asking = false }
            .show()
    }
    private fun openSettings() {
        try {
            val action = if (Build.VERSION.SDK_INT >= 30) Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            else Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            startActivity(Intent(action, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun box(color: Int, r: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun lp(w: Int, h: Int, weight: Float = 0f, mh: Int = 0, mt: Int = 0, mb: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(mh), dp(mt), dp(mh), dp(mb)) }
    private fun chip(text: String, color: Int = C.TEXT, bg: Int = C.CARD, onClick: () -> Unit): TextView =
        tv(text, 13f, color, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = box(bg, 12, C.STROKE)
            setOnClickListener { onClick() }
        }
    private fun stat(title: String, value: String, sub: String, color: Int): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = box(C.CARD, 14, C.STROKE)
        c.setPadding(dp(10), dp(8), dp(10), dp(8))
        c.addView(tv(title, 11f, C.SUB))
        c.addView(tv(value, 16f, color, true))
        c.addView(tv(sub, 10f, C.SUB))
        return c
    }
    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C.BG)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(10), dp(10), dp(10), 0)
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(tv("🗂️ خريطة المساحة", 20f, C.TEXT, true), lp(0, WRAP, 1f))
        head.addView(chip("🔄") {
            disk.cache.clear()
            load()
        }, lp(WRAP, WRAP, 0f, 3))
        head.addView(chip("🗑 المهملات") { showTrash() }, lp(WRAP, WRAP, 0f, 3))
        root.addView(head, lp(MATCH, WRAP, 0f, 0, 0, 8))
        crumbScroll = HorizontalScrollView(this)
        crumbScroll.layoutDirection = View.LAYOUT_DIRECTION_LTR
        crumbScroll.isHorizontalScrollBarEnabled = false
        crumbScroll.background = box(C.CARD, 12, C.STROKE)
        crumbs = LinearLayout(this)
        crumbs.gravity = Gravity.CENTER_VERTICAL
        crumbs.setPadding(dp(6), dp(4), dp(6), dp(4))
        crumbScroll.addView(crumbs)
        val pathRow = LinearLayout(this)
        pathRow.gravity = Gravity.CENTER_VERTICAL
        pathRow.addView(crumbScroll, lp(0, WRAP, 1f))
        pathRow.addView(chip("📋 نسخ") { copyPath() }, lp(WRAP, WRAP, 0f, 4))
        root.addView(pathRow, lp(MATCH, WRAP, 0f, 0, 0, 8))
        stats = LinearLayout(this)
        root.addView(stats, lp(MATCH, WRAP, 0f, 0, 0, 8))
        distBar = LinearLayout(this)
        distBar.background = box(C.TRACK, 6)
        root.addView(distBar, lp(MATCH, dp(14), 0f, 0, 0, 8))
        sortBtn = chip("") {
            sortMode = (sortMode + 1) % 3
            refresh()
        }
        selAllBtn = chip("☑ تحديد الكل") { toggleAll() }
        val tools = LinearLayout(this)
        tools.addView(sortBtn, lp(0, WRAP, 1f, 3))
        tools.addView(selAllBtn, lp(0, WRAP, 1f, 3))
        root.addView(tools, lp(MATCH, WRAP, 0f, 0, 0, 6))
        safBtn = chip("🔓 منح وصول لهذا المجلد", C.BG, C.CYAN) { askSafGrant() }
        safBtn.visibility = View.GONE
        root.addView(safBtn, lp(MATCH, WRAP, 0f, 0, 0, 6))
        safTip = tv("📂 معروض بإذن ممنوح يدوياً — عرض وفتح فقط، بدون تحديد أو حذف حالياً", 11f, C.SUB)
        safTip.visibility = View.GONE
        root.addView(safTip, lp(MATCH, WRAP, 0f, 0, 0, 6))
        busy = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        busy.isIndeterminate = true
        busy.visibility = View.GONE
        root.addView(busy, lp(MATCH, dp(4), 0f, 0, 0, 4))
        list = ListView(this)
        list.divider = null
        list.dividerHeight = 0
        list.setSelector(android.R.color.transparent)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> clickEntry(entries[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            toggle(entries[pos])
            true
        }
        root.addView(list, lp(MATCH, 0, 1f))
        emptyView = tv("", 15f, C.SUB)
        emptyView.gravity = Gravity.CENTER
        emptyView.visibility = View.GONE
        root.addView(emptyView, lp(MATCH, 0, 1f))
        selBar = LinearLayout(this)
        selBar.gravity = Gravity.CENTER_VERTICAL
        selBar.background = box(C.CARD, 14, C.CYAN)
        selBar.setPadding(dp(12), dp(8), dp(12), dp(8))
        selText = tv("", 14f, C.TEXT, true)
        selBar.addView(selText, lp(0, WRAP, 1f))
        selBar.addView(chip("إلغاء") {
            selected.clear()
            updateSel()
            adapter.notifyDataSetChanged()
        }, lp(WRAP, WRAP, 0f, 3))
        selBar.addView(chip("🗑 حذف", C.TEXT, C.RED) { askDelete() }, lp(WRAP, WRAP, 0f, 3))
        selBar.visibility = View.GONE
        root.addView(selBar, lp(MATCH, WRAP, 0f, 0, 4, 8))
        return root
    }
    private fun refreshCrumbs() {
        crumbs.removeAllViews()
        val parts = cur.absolutePath.split("/").filter { it.isNotEmpty() }
        addCrumb("/", File("/"), parts.isEmpty())
        var acc = ""
        for ((i, p) in parts.withIndex()) {
            acc += "/$p"
            crumbs.addView(tv("›", 16f, C.SUB), lp(WRAP, WRAP, 0f, 2))
            addCrumb(p, File(acc), i == parts.lastIndex)
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }
    private fun addCrumb(label: String, target: File, last: Boolean) {
        val t = tv(label, 14f, if (last) C.BG else C.TEXT, true)
        t.setPadding(dp(10), dp(6), dp(10), dp(6))
        t.background = box(if (last) C.CYAN else C.TRACK, 10)
        t.setOnClickListener { go(target) }
        crumbs.addView(t)
    }
    private fun copyPath() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", cur.absolutePath))
        toast("تم نسخ المسار: " + cur.absolutePath)
    }
    private fun go(dir: File) {
        cur = dir
        curDoc = docCache[dir.path]
        selected.clear()
        load()
        list.setSelection(0)
    }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val parent = cur.parentFile
        when {
            selected.isNotEmpty() -> {
                selected.clear()
                updateSel()
                adapter.notifyDataSetChanged()
            }
            started && parent != null -> go(parent)
            else -> super.onBackPressed()
        }
    }
    private fun load() {
        gen++
        val myGen = gen
        pool?.shutdownNow()
        ui.removeCallbacks(refreshRun)
        scheduled = false
        entries = ArrayList()
        val d = curDoc
        if (d != null) {
            protectedDir = false
            val kids = try {
                d.listFiles()
            } catch (e: Exception) {
                null
            }
            kids?.forEach { c ->
                val name = c.name ?: return@forEach
                val cf = File(cur, name)
                docCache[cf.path] = c
                entries.add(disk.entryDoc(c, cf))
            }
        } else {
            val kids = cur.listFiles()
            protectedDir = kids == null ||
                (kids.isEmpty() && (cur.path.endsWith("/Android/data") || cur.path.endsWith("/Android/obb")))
            kids?.forEach { entries.add(disk.entry(it)) }
        }
        refreshCrumbs()
        val todo = entries.filter { it.size < 0 }
        pending = todo.size
        busy.visibility = if (pending > 0) View.VISIBLE else View.GONE
        refresh()
        if (todo.isEmpty()) return
        val p = Executors.newFixedThreadPool(4)
        pool = p
        for (e in todo) {
            p.execute {
                var s = 0L
                try {
                    s = if (e.doc != null) disk.sizeDoc(e.doc, e.file.path) else disk.size(e.file)
                } catch (x: InterruptedException) {
                    return@execute
                } catch (x: Exception) {
                }
                ui.post {
                    if (myGen == gen) {
                        e.size = s
                        pending--
                        if (pending <= 0) {
                            busy.visibility = View.GONE
                            refresh()
                        } else {
                            scheduleRefresh()
                        }
                    }
                }
            }
        }
        p.shutdown()
    }
    private fun scheduleRefresh() {
        if (!scheduled) {
            scheduled = true
            ui.postDelayed(refreshRun, 300)
        }
    }
    private fun refresh() {
        when (sortMode) {
            0 -> entries.sortWith(compareByDescending<Entry> { it.size })
            1 -> entries.sortWith(compareBy<Entry> { if (it.size < 0) Long.MAX_VALUE else it.size })
            else -> entries.sortWith(compareBy<Entry> { it.file.name.lowercase() })
        }
        sortBtn.text = arrayOf("⇅ الأكبر أولاً", "⇅ الأصغر أولاً", "⇅ حسب الاسم")[sortMode]
        val known = entries.filter { it.size > 0 }.sumOf { it.size }
        adapter.total = if (known > 0) known else 1L
        var free = 0L
        var total = 0L
        try {
            val s = StatFs(cur.path)
            free = s.availableBytes
            total = s.totalBytes
        } catch (e: Exception) {
        }
        stats.removeAllViews()
        stats.addView(
            stat("حجم المجلد", Fmt.size(known) + if (pending > 0) " …" else "", "${entries.size} عنصر", C.CYAN),
            lp(0, WRAP, 1f, 3)
        )
        stats.addView(
            stat(
                "مجلدات / ملفات",
                "${entries.count { it.kind == Kind.FOLDER }} / ${entries.count { it.kind != Kind.FOLDER }}",
                "في هذا المسار", C.PURPLE
            ),
            lp(0, WRAP, 1f, 3)
        )
        stats.addView(
            stat("المتاح بالجهاز", if (total > 0) Fmt.size(free) else "—", if (total > 0) "من " + Fmt.size(total) else "", C.GREEN),
            lp(0, WRAP, 1f, 3)
        )
        distBar.removeAllViews()
        if (known > 0) {
            val top = entries.filter { it.size > 0 }.sortedByDescending { it.size }.take(6)
            var used = 0L
            for (e in top) {
                used += e.size
                val seg = View(this)
                seg.background = box(e.kind.color, 4)
                distBar.addView(seg, lp(0, MATCH, e.size.toFloat() / known * 100f, 1))
            }
            if (known - used > 0) {
                val seg = View(this)
                seg.background = box(C.SUB, 4)
                distBar.addView(seg, lp(0, MATCH, (known - used).toFloat() / known * 100f, 1))
            }
        }
        val empty = entries.isEmpty()
        list.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        emptyView.text = if (protectedDir) {
            "🔒\nهذا المجلد محمي من النظام\nجرّب زرار «منح وصول» تحت لو عايز تفتحه"
        } else {
            "📭\nالمجلد فارغ"
        }
        safBtn.visibility = if (protectedDir && curDoc == null) View.VISIBLE else View.GONE
        safTip.visibility = if (curDoc != null) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        updateSel()
    }
    private fun clickEntry(e: Entry) {
        if (e.kind == Kind.FOLDER) go(e.file) else openFile(e)
    }
    private fun openFile(en: Entry) {
        try {
            val d = en.doc
            val uri = d?.uri ?: FileProvider.getUriForFile(this, "$packageName.provider", en.file)
            val mime = d?.type ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(en.file.extension.lowercase()) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            toast("لا يوجد تطبيق يفتح هذا النوع من الملفات")
        }
    }
    private fun askSafGrant() {
        AlertDialog.Builder(this)
            .setTitle("منح وصول لهذا المجلد")
            .setMessage(
                "هتتفتح شاشة اختيار مجلد من النظام.\n" +
                    "ادخل بالظبط على مجلد «" + cur.name + "» (جوه نفس المكان اللي إنت فاتحه دلوقتي) واضغط «استخدام هذا المجلد».\n\n" +
                    "ملحوظة: أندرويد أحياناً بيمنع زرار «استخدام هذا المجلد» من العمل مع مجلدات زي Android/data أو Android/obb حسب إصدار نظام جهازك، فممكن محصلش."
            )
            .setPositiveButton("فتح شاشة الاختيار") { _, _ -> launchSafPicker() }
            .setNegativeButton("إلغاء", null)
            .show()
    }
    private fun launchSafPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Saf.initialAndroidUri())
        } catch (e: Exception) {
        }
        try {
            startActivityForResult(i, REQ_SAF)
        } catch (e: Exception) {
            toast("تعذر فتح شاشة اختيار المجلد")
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SAF) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            toast("اتلغى اختيار المجلد")
            return
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
        }
        val path = Saf.realPath(uri)
        if (path == null) {
            toast("تعذر التأكد من مسار المجلد اللي اخترته")
            return
        }
        if (path != cur.path) {
            toast("اخترت «" + File(path).name + "» مش نفس المجلد اللي إنت فاتحه (" + cur.name + "). جرب تاني واختار بالظبط نفس المجلد.")
            return
        }
        val d = DocumentFile.fromTreeUri(this, uri)
        if (d == null || !d.canRead()) {
            toast("التطبيق مش قادر يقرأ هذا المجلد")
            return
        }
        Saf.save(this, path, uri)
        docCache[path] = d
        curDoc = d
        toast("تم ✅ هتقدر تتصفح المجلد دلوقتي")
        load()
    }
    private fun toggle(e: Entry) {
        if (e.doc != null) {
            toast("التحديد والحذف غير مدعومين هنا حالياً")
            return
        }
        val k = e.file.path
        if (!selected.remove(k)) selected.add(k)
        updateSel()
        adapter.notifyDataSetChanged()
    }
    private fun toggleAll() {
        if (curDoc != null) {
            toast("التحديد والحذف غير مدعومين هنا حالياً")
            return
        }
        if (selected.size == entries.size) {
            selected.clear()
        } else {
            entries.forEach { selected.add(it.file.path) }
        }
        updateSel()
        adapter.notifyDataSetChanged()
    }
    private fun updateSel() {
        val n = selected.size
        selBar.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > 0) {
            val sz = entries.filter { selected.contains(it.file.path) }.sumOf { maxOf(it.size, 0L) }
            selText.text = "محدد: $n • ${Fmt.size(sz)}"
        }
        selAllBtn.text = if (n > 0 && n == entries.size) "☐ إلغاء التحديد" else "☑ تحديد الكل"
    }
    private fun askDelete() {
        val items = entries.filter { selected.contains(it.file.path) }
        if (items.isEmpty()) return
        val sz = items.sumOf { maxOf(it.size, 0L) }
        AlertDialog.Builder(this)
            .setTitle("حذف ${items.size} عنصر")
            .setMessage(
                "الحجم: ${Fmt.size(sz)}\n\n" +
                    "• سلة المهملات: يمكن استعادتها من داخل التطبيق، لكنها تظل تشغل المساحة حتى تحذفها نهائياً من السلة.\n\n" +
                    "• حذف نهائي: يحرر المساحة فوراً ولا يمكن التراجع."
            )
            .setPositiveButton("🗑 سلة المهملات") { _, _ -> doDelete(items, false) }
            .setNegativeButton("⚠ حذف نهائي") { _, _ -> doDelete(items, true) }
            .setNeutralButton("إلغاء", null)
            .show()
    }
    private fun doDelete(items: List<Entry>, permanent: Boolean) {
        pool?.shutdownNow()
        busy.visibility = View.VISIBLE
        Thread {
            var ok = 0
            val media = ArrayList<String>()
            for (e in items) {
                try {
                    val sz = if (e.size >= 0) e.size else disk.size(e.file)
                    val moved: File? = if (permanent) null else Trash.moveIn(e.file)
                    val good = if (permanent) Fs.deleteTree(e.file) else moved != null
                    if (good) {
                        ok++
                        disk.removed(e.file, sz)
                        if (moved != null) disk.adjust(moved.parentFile, sz)
                        if (e.kind != Kind.FOLDER) media.add(e.file.path)
                    } else {
                        disk.cache.clear()
                    }
                } catch (x: Exception) {
                    disk.cache.clear()
                }
            }
            if (media.isNotEmpty()) {
                MediaScannerConnection.scanFile(this@MainActivity, media.toTypedArray(), null, null)
            }
            ui.post {
                toast("تمت المعالجة: $ok من ${items.size}" + if (ok < items.size) " — الباقي محمي أو مستخدم" else "")
                selected.clear()
                load()
            }
        }.start()
    }
    private fun showTrash() {
        val boxes = (Trash.dir().listFiles()?.filter { it.isDirectory } ?: emptyList()).sortedBy { it.name }
        if (boxes.isEmpty()) {
            toast("سلة المهملات فارغة")
            return
        }
        val names = boxes.map { b ->
            val o = Trash.orig(b)
            File(o).name + "\n" + o
        }.toTypedArray()
        val chk = BooleanArray(boxes.size)
        AlertDialog.Builder(this)
            .setTitle("🗑 سلة المهملات (${boxes.size})")
            .setMultiChoiceItems(names, chk) { _, i, c -> chk[i] = c }
            .setPositiveButton("استعادة") { _, _ -> trashAct(boxes, chk, true) }
            .setNegativeButton("حذف نهائي") { _, _ -> trashAct(boxes, chk, false) }
            .setNeutralButton("تفريغ الكل") { _, _ -> trashAct(boxes, BooleanArray(boxes.size) { true }, false) }
            .show()
    }
    private fun trashAct(boxes: List<File>, chk: BooleanArray, restore: Boolean) {
        val sel = boxes.filterIndexed { i, _ -> chk[i] }
        if (sel.isEmpty()) return
        busy.visibility = View.VISIBLE
        Thread {
            var ok = 0
            for (b in sel) {
                val good = if (restore) Trash.restore(b) else Fs.deleteTree(b)
                if (good) ok++
            }
            ui.post {
                disk.cache.clear()
                toast("تمت المعالجة: $ok من ${sel.size}")
                load()
            }
        }.start()
    }
    private inner class Holder {
        val root = FrameLayout(this@MainActivity)
        val card = LinearLayout(this@MainActivity)
        val check = CheckBox(this@MainActivity)
        val icon = TextView(this@MainActivity)
        val name = tv("", 15f, C.TEXT, true)
        val pct = tv("", 13f, C.SUB, true)
        val meta = tv("", 12f, C.SUB)
        val track = LinearLayout(this@MainActivity)
        val fill = View(this@MainActivity)
        val rest = View(this@MainActivity)
        init {
            root.setPadding(0, dp(4), 0, dp(4))
            card.gravity = Gravity.CENTER_VERTICAL
            card.setPadding(dp(10), dp(10), dp(12), dp(10))
            check.isFocusable = false
            check.isFocusableInTouchMode = false
            icon.gravity = Gravity.CENTER
            icon.textSize = 22f
            name.setSingleLine(true)
            name.ellipsize = TextUtils.TruncateAt.MIDDLE
            track.background = box(C.TRACK, 4)
            track.addView(fill)
            track.addView(rest)
            val top = LinearLayout(this@MainActivity)
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(name, lp(0, WRAP, 1f))
            top.addView(pct, lp(WRAP, WRAP, 0f, 4))
            val col = LinearLayout(this@MainActivity)
            col.orientation = LinearLayout.VERTICAL
            col.addView(top, lp(MATCH, WRAP))
            col.addView(meta, lp(MATCH, WRAP, 0f, 0, 2, 4))
            col.addView(track, lp(MATCH, dp(8)))
            card.addView(check, lp(WRAP, WRAP, 0f, 2))
            card.addView(icon, lp(dp(48), dp(48), 0f, 8))
            card.addView(col, lp(0, WRAP, 1f, 4))
            root.addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
            root.tag = this
        }
    }
    private fun bind(h: Holder, e: Entry) {
        val sel = selected.contains(e.file.path)
        h.card.background = box(if (sel) C.CARD_SEL else C.CARD, 14, if (sel) C.CYAN else C.STROKE)
        h.check.isChecked = sel
        h.check.setOnClickListener { toggle(e) }
        h.check.visibility = if (e.doc != null) View.INVISIBLE else View.VISIBLE
        h.check.isEnabled = e.doc == null
        h.icon.text = e.kind.icon
        h.icon.background = box((e.kind.color and 0x00FFFFFF) or 0x33000000, 12)
        h.name.text = if (e.file.name.isEmpty()) e.file.path else e.file.name
        val p = if (e.size < 0) 0.0 else e.size * 100.0 / adapter.total
        val col = if (p < 15) C.GREEN else if (p < 40) C.AMBER else C.RED
        h.meta.text = when {
            e.locked -> "🔒 محمي من النظام"
            e.size < 0 -> "⏳ جارٍ حساب الحجم…"
            e.kind == Kind.FOLDER -> "${Fmt.size(e.size)} • ${e.count} عنصر"
            else -> "${Fmt.size(e.size)} • ${e.kind.label} • ${df.format(Date(e.file.lastModified()))}"
        }
        h.pct.text = if (e.size < 0) "…" else String.format(Locale.US, "%.1f%%", p)
        h.pct.setTextColor(col)
        val w = if (e.size < 0) 0f else maxOf(p.toFloat(), 1.5f)
        h.fill.background = box(col, 4)
        h.fill.layoutParams = LinearLayout.LayoutParams(0, MATCH, w)
        h.rest.layoutParams = LinearLayout.LayoutParams(0, MATCH, maxOf(100f - w, 0.01f))
    }
    private inner class Adapter : BaseAdapter() {
        var total = 1L
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val h = (convertView?.tag as? Holder) ?: Holder()
            bind(h, entries[position])
            return h.root
        }
    }
}
