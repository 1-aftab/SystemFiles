package com.example.systemfiles

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Minimal file browser that works on plain java.io.File once the app has
 * "All files access", so any folder on the device can be browsed and modified.
 */
class MainActivity : Activity() {

    private enum class ClipMode { COPY, MOVE }

    private data class Clipboard(val mode: ClipMode, val files: List<File>)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

    private var currentDir: File? = null
    private var entries: List<File> = emptyList()
    private val selected = LinkedHashSet<File>()
    private var clipboard: Clipboard? = null
    private var showHidden = false
    private var busy = false
    private var accessPrompted = false

    private lateinit var adapter: EntryAdapter
    private lateinit var pathText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var selectionBar: HorizontalScrollView
    private lateinit var selectionText: TextView
    private lateinit var copyButton: Button
    private lateinit var cutButton: Button
    private lateinit var renameButton: Button
    private lateinit var deleteButton: Button
    private lateinit var upButton: Button
    private lateinit var newFolderButton: Button
    private lateinit var pasteButton: Button
    private lateinit var listView: ListView
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        adapter = EntryAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val file = entries.getOrNull(position) ?: return@setOnItemClickListener
            if (selected.isEmpty()) openItem(file) else toggleSelection(file)
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val file = entries.getOrNull(position) ?: return@setOnItemLongClickListener true
            toggleSelection(file)
            true
        }
        updateActionBars()
    }

    override fun onResume() {
        super.onResume()
        if (!hasStorageAccess()) {
            renderAccessRequired()
            if (!accessPrompted) {
                accessPrompted = true
                requestAccess()
            }
            return
        }
        if (currentDir == null) {
            openDirectory(defaultDirectory())
        } else {
            refresh()
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        when {
            selected.isNotEmpty() -> clearSelection()
            currentDir?.parentFile != null -> goUp()
            else -> super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_STORAGE) return
        if (hasStorageAccess()) {
            openDirectory(defaultDirectory())
        } else {
            renderAccessRequired()
            toast("Storage permission is required to manage files")
        }
    }

    // ---------------------------------------------------------------- access

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Allow all files access")
                .setMessage(
                    "System Files needs \"All files access\" so it can browse every folder " +
                        "and move, copy or delete files there.\n\n" +
                        "On the next screen enable \"Allow management of all files\"."
                )
                .setPositiveButton("Open settings") { _, _ -> openAllFilesSettings() }
                .setNegativeButton("Not now", null)
                .show()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQUEST_STORAGE
            )
        }
    }

    private fun openAllFilesSettings() {
        val appIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(appIntent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: ActivityNotFoundException) {
                toast("Open Settings > Apps > System Files > Permissions")
            }
        }
    }

    private fun renderAccessRequired() {
        currentDir = null
        selected.clear()
        entries = emptyList()
        adapter.notifyDataSetChanged()
        pathText.text = "Storage access required"
        emptyText.text =
            "System Files needs \"All files access\" so you can browse every folder and " +
                "move, copy or delete files.\n\nTap here to grant access."
        emptyText.visibility = View.VISIBLE
        updateActionBars()
    }

    // -------------------------------------------------------------- browsing

    private fun defaultDirectory(): File {
        Environment.getExternalStorageDirectory()?.let { external ->
            if (external.exists()) return external
        }
        val storage = File("/storage")
        if (storage.exists()) return storage
        return File("/")
    }

    private fun openDirectory(directory: File) {
        currentDir = directory
        selected.clear()
        refresh()
    }

    private fun refresh() {
        val dir = currentDir ?: return
        pathText.text = dir.absolutePath

        val files = dir.listFiles()
        entries = if (files == null) {
            emptyList()
        } else {
            files
                .asSequence()
                .filter { showHidden || !it.name.startsWith(".") }
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                .toList()
        }
        adapter.notifyDataSetChanged()

        when {
            files == null -> {
                emptyText.text = "This folder cannot be read.\nGrant all files access or pick another folder."
                emptyText.visibility = View.VISIBLE
            }
            entries.isEmpty() -> {
                emptyText.text = "This folder is empty"
                emptyText.visibility = View.VISIBLE
            }
            else -> emptyText.visibility = View.GONE
        }
        updateActionBars()
    }

    private fun openItem(file: File) {
        if (file.isDirectory) openDirectory(file) else showFileDetails(file)
    }

    private fun goUp() {
        val dir = currentDir ?: return
        clearSelection()
        dir.parentFile?.let { openDirectory(it) }
    }

    private fun showFileDetails(file: File) {
        val size = if (file.isDirectory) {
            "${file.listFiles()?.size ?: 0} item(s)"
        } else {
            Formatter.formatFileSize(this, file.length())
        }
        val message = buildString {
            append(file.absolutePath)
            append("\n\nSize: ").append(size)
            append("\nModified: ").append(dateFormat.format(Date(file.lastModified())))
        }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage(message)
            .setItems(arrayOf("Copy", "Move", "Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> {
                        clipboard = Clipboard(ClipMode.COPY, listOf(file))
                        updateActionBars()
                        toast("\"${file.name}\" ready to paste")
                    }
                    1 -> {
                        clipboard = Clipboard(ClipMode.MOVE, listOf(file))
                        updateActionBars()
                        toast("\"${file.name}\" ready to move")
                    }
                    2 -> askRename(file)
                    3 -> confirmDelete(listOf(file))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------- selection

    private fun toggleSelection(file: File) {
        if (!selected.remove(file)) selected.add(file)
        adapter.notifyDataSetChanged()
        updateActionBars()
    }

    private fun clearSelection() {
        selected.clear()
        adapter.notifyDataSetChanged()
        updateActionBars()
    }

    private fun updateActionBars() {
        val count = selected.size
        selectionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        selectionText.text = "$count selected"
        copyButton.isEnabled = !busy
        cutButton.isEnabled = !busy
        renameButton.isEnabled = !busy && count == 1
        deleteButton.isEnabled = !busy

        val clip = clipboard
        pasteButton.visibility = if (clip == null) View.GONE else View.VISIBLE
        pasteButton.text = if (clip == null) "Paste" else "Paste ${clip.files.size}"
        pasteButton.isEnabled = !busy && clip != null && currentDir != null
        upButton.isEnabled = !busy && currentDir?.parentFile != null
        newFolderButton.isEnabled = !busy && currentDir != null
    }

    // ------------------------------------------------------------ clipboard

    private fun copySelection() {
        if (selected.isEmpty()) return
        clipboard = Clipboard(ClipMode.COPY, selected.toList())
        toast("${selected.size} item(s) ready to paste")
        clearSelection()
    }

    private fun cutSelection() {
        if (selected.isEmpty()) return
        clipboard = Clipboard(ClipMode.MOVE, selected.toList())
        toast("${selected.size} item(s) ready to move")
        clearSelection()
    }

    private fun pasteClipboard() {
        val clip = clipboard ?: return
        val destination = currentDir ?: return
        if (busy) return

        busy = true
        updateActionBars()
        setProgressVisible(true)
        val moving = clip.mode == ClipMode.MOVE

        io.execute {
            var succeeded = 0
            val failures = ArrayList<String>()
            for (source in clip.files) {
                try {
                    if (!source.exists()) throw IOException("no longer exists")
                    if (moving) {
                        FileOperations.move(source, destination)
                    } else {
                        FileOperations.copy(source, destination)
                    }
                    succeeded++
                } catch (e: Exception) {
                    failures.add("${source.name}: ${e.message ?: "failed"}")
                }
            }
            main.post {
                busy = false
                setProgressVisible(false)
                if (moving && failures.isEmpty()) clipboard = null
                refresh()
                toast(describe("Pasted", succeeded, failures.size))
                if (failures.isNotEmpty()) showFailures(failures)
            }
        }
    }

    // ------------------------------------------------------------- mutations

    private fun confirmDelete(files: List<File>) {
        if (files.isEmpty() || busy) return
        val preview = files.take(3).joinToString("\n") { it.name } +
            if (files.size > 3) "\n…and ${files.size - 3} more" else ""
        AlertDialog.Builder(this)
            .setTitle("Delete ${files.size} item(s)?")
            .setMessage("$preview\n\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteFiles(files) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteFiles(files: List<File>) {
        val dir = currentDir ?: return
        busy = true
        updateActionBars()
        setProgressVisible(true)
        val snapshot = files.toList()

        io.execute {
            var succeeded = 0
            val failures = ArrayList<String>()
            for (file in snapshot) {
                try {
                    if (file.exists() && !file.deleteRecursively() && file.exists()) {
                        throw IOException("permission denied")
                    }
                    succeeded++
                } catch (e: Exception) {
                    failures.add("${file.name}: ${e.message ?: "failed"}")
                }
            }
            main.post {
                busy = false
                setProgressVisible(false)
                clearSelection()
                if (dir.exists()) refresh() else openDirectory(dir.parentFile ?: defaultDirectory())
                toast(describe("Deleted", succeeded, failures.size))
                if (failures.isNotEmpty()) showFailures(failures)
            }
        }
    }

    private fun renameSelection() {
        selected.firstOrNull()?.let { askRename(it) }
    }

    private fun askRename(file: File) {
        val input = EditText(this).apply {
            setText(file.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(padded(input))
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName.contains('/')) {
                    toast("Enter a valid name")
                } else if (newName == file.name) {
                    // Nothing to do.
                } else {
                    val parent = file.parentFile
                    if (parent == null) {
                        toast("This item cannot be renamed")
                        return@setPositiveButton
                    }
                    val target = File(parent, newName)
                    if (target.exists()) {
                        toast("\"$newName\" already exists")
                    } else if (file.renameTo(target)) {
                        clearSelection()
                        refresh()
                        toast("Renamed")
                    } else {
                        toast("Rename failed — check all files access")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askNewFolder() {
        val dir = currentDir ?: return
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle("New folder")
            .setView(padded(input))
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/')) {
                    toast("Enter a valid name")
                } else {
                    val target = File(dir, name)
                    if (target.exists()) {
                        toast("\"$name\" already exists")
                    } else if (target.mkdirs()) {
                        refresh()
                        toast("Created \"$name\"")
                    } else {
                        toast("Could not create folder — check all files access")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ----------------------------------------------------------------- menu

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_ACCESS, 0, "All files access settings")
        popup.menu.add(0, MENU_STORAGE, 1, "Internal storage")
        popup.menu.add(0, MENU_ROOT, 2, "Device root")
        popup.menu.add(0, MENU_HIDDEN, 3, if (showHidden) "Hide hidden files" else "Show hidden files")
        popup.menu.add(0, MENU_PICKER, 4, "System file picker")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ACCESS -> if (hasStorageAccess()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) openAllFilesSettings() else requestAccess()
                } else {
                    requestAccess()
                }
                MENU_STORAGE -> if (hasStorageAccess()) openDirectory(defaultDirectory())
                MENU_ROOT -> if (hasStorageAccess()) openDirectory(File("/"))
                MENU_HIDDEN -> {
                    showHidden = !showHidden
                    refresh()
                }
                MENU_PICKER -> launchSystemPicker()
            }
            true
        }
        popup.show()
    }

    private fun launchSystemPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQUEST_PICKER)
        } catch (e: ActivityNotFoundException) {
            toast("No system file picker available")
        }
    }

    // ------------------------------------------------------------------ ui

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(4), dp(6))
            setBackgroundColor(COLOR_SURFACE)
        }
        pathText = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setTypeface(typeface, Typeface.BOLD)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
        }
        val overflow = makeButton("⋮") { showOverflowMenu(it) }

        toolbar.addView(
            pathText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        )
        toolbar.addView(
            progressBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        )
        toolbar.addView(
            overflow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val selectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        selectionText = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, Typeface.BOLD)
        }
        copyButton = makeButton("Copy") { copySelection() }
        cutButton = makeButton("Move") { cutSelection() }
        renameButton = makeButton("Rename") { renameSelection() }
        deleteButton = makeButton("Delete") { confirmDelete(selected.toList()) }
        val clearButton = makeButton("✕") { clearSelection() }

        selectionRow.addView(
            selectionText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        )
        for (button in listOf(copyButton, cutButton, renameButton, deleteButton, clearButton)) {
            selectionRow.addView(button)
        }
        selectionBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(COLOR_SELECTION)
            visibility = View.GONE
            addView(
                selectionRow,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        listView = ListView(this).apply {
            dividerHeight = 0
            setBackgroundColor(COLOR_BACKGROUND)
        }
        emptyText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(COLOR_SUBTLE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setOnClickListener {
                if (hasStorageAccess()) refresh() else requestAccess()
            }
        }
        val content = FrameLayout(this).apply {
            addView(
                listView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                emptyText,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(COLOR_SURFACE)
        }
        upButton = makeButton("Up") { goUp() }
        newFolderButton = makeButton("New folder") { askNewFolder() }
        pasteButton = makeButton("Paste") { pasteClipboard() }
        for (button in listOf(upButton, newFolderButton, pasteButton)) {
            bottomBar.addView(
                button,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            selectionBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            bottomBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }

    private fun makeButton(label: String, onClick: (View) -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { view -> onClick(view) }
        }
    }

    private fun padded(view: View): View {
        return FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(view)
        }
    }

    private fun setProgressVisible(visible: Boolean) {
        progressBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun describe(verb: String, succeeded: Int, failed: Int): String {
        return when {
            succeeded == 0 && failed == 0 -> "$verb nothing"
            failed == 0 -> "$verb $succeeded item(s)"
            succeeded == 0 -> "$verb 0 item(s), $failed failed"
            else -> "$verb $succeeded item(s), $failed failed"
        }
    }

    private fun showFailures(failures: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Some items failed")
            .setMessage(failures.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun formatDate(time: Long): String = dateFormat.format(Date(time))

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics)
            .toInt()

    private inner class EntryAdapter : BaseAdapter() {

        override fun getCount(): Int = entries.size

        override fun getItem(position: Int): File = entries[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: buildRow()
            val file = entries[position]

            val name = row.getChildAt(0) as TextView
            val meta = row.getChildAt(1) as TextView
            name.text = (if (file.isDirectory) "📁  " else "📄  ") + file.name
            meta.text = if (file.isDirectory) {
                "Folder · ${formatDate(file.lastModified())}"
            } else {
                "${Formatter.formatFileSize(this@MainActivity, file.length())} · ${formatDate(file.lastModified())}"
            }
            row.setBackgroundColor(if (selected.contains(file)) COLOR_SELECTED else Color.TRANSPARENT)
            return row
        }

        private fun buildRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val name = TextView(this@MainActivity).apply {
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextColor(COLOR_TEXT)
            }
            val meta = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(COLOR_SUBTLE)
            }
            row.addView(
                name,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            row.addView(
                meta,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return row
        }
    }

    private companion object {
        const val REQUEST_STORAGE = 1
        const val REQUEST_PICKER = 100

        const val MENU_ACCESS = 1
        const val MENU_STORAGE = 2
        const val MENU_ROOT = 3
        const val MENU_HIDDEN = 4
        const val MENU_PICKER = 5

        val COLOR_BACKGROUND = 0xFFF7F6FA.toInt()
        val COLOR_SURFACE = 0xFFFFFFFF.toInt()
        val COLOR_SELECTION = 0xFFEFE7FF.toInt()
        val COLOR_SELECTED = 0x336750A4
        val COLOR_TEXT = 0xFF1C1B1F.toInt()
        val COLOR_SUBTLE = 0xFF5F5F66.toInt()
    }
}
