package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    override fun name0() = app.getString(R.string.backup)

    var content = ""
    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    try {
                        requireActivity().contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(content)
                        }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.resetSettings.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    DataStore.configurationStore.reset()
                    triggerFullRestart(requireContext())
                }
                .show()
        }

        binding.actionExport.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                onMainDispatcher {
                    startFilesForResult(
                        exportSettings, "nekobox_backup_${Date().toLocaleString()}.json"
                    )
                }
            }
        }

        binding.actionShare.setOnClickListener {
            runOnDefaultDispatcher {
                content = doBackup(
                    binding.backupConfigurations.isChecked,
                    binding.backupRules.isChecked,
                    binding.backupSettings.isChecked
                )
                app.cacheDir.mkdirs()
                val cacheFile = File(
                    app.cacheDir, "nekobox_backup_${Date().toLocaleString()}.json"
                )
                cacheFile.writeText(content)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }

        // NekoBox++: WebDAV backup upload / download.
        val webdavPrefs = requireContext().getSharedPreferences(
            "nbpp_webdav", android.content.Context.MODE_PRIVATE
        )
        binding.webdavUrl.setText(webdavPrefs.getString("url", ""))
        binding.webdavUser.setText(webdavPrefs.getString("user", ""))
        binding.webdavPass.setText(webdavPrefs.getString("pass", ""))

        // The WebDAV form stays folded away until its
        // header is tapped, so credentials are not sitting
        // on the page in plain sight.
        binding.webdavHeader.setOnClickListener {
            val open = binding.webdavBody.visibility == android.view.View.VISIBLE
            binding.webdavBody.visibility =
                if (open) android.view.View.GONE else android.view.View.VISIBLE
            binding.webdavHeader.text = if (open) "WebDAV 备份 ▸" else "WebDAV 备份 ▾"
        }

        // The folder holding the backups. Uploads are
        // stored per day (nekoboxpp_backup_<date>.json);
        // an explicitly typed .json file name resolves to
        // its containing folder too.
        fun webdavFolder(): String? {
            val raw = binding.webdavUrl.text.toString().trim()
            if (raw.isBlank()) return null
            webdavPrefs.edit()
                .putString("url", raw)
                .putString("user", binding.webdavUser.text.toString())
                .putString("pass", binding.webdavPass.text.toString())
                .apply()
            var base = raw
            // Jianguoyun (Nutstore) refuses file writes at the /dav/
            // root; use a NekoBox++ subfolder there instead.
            val uri = runCatching { java.net.URI(raw) }.getOrNull()
            if (uri?.host == "dav.jianguoyun.com" &&
                (uri.path == "/dav" || uri.path == "/dav/")
            ) {
                base = raw.trimEnd('/') + "/NekoBox++/"
            }
            if (base.endsWith(".json")) {
                base = base.substringBeforeLast('/') + "/"
            }
            return if (base.endsWith("/")) base else base + "/"
        }

        binding.actionWebdavUpload.setOnClickListener {
            val folder = webdavFolder()
            if (folder == null) {
                snackbar("先填 WebDAV 地址").show()
                return@setOnClickListener
            }
            val user = binding.webdavUser.text.toString()
            val pass = binding.webdavPass.text.toString()
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(Date())
            val fileName = "nekoboxpp_backup_$date.json"
            runOnDefaultDispatcher {
                try {
                    val data = doBackup(
                        binding.backupConfigurations.isChecked,
                        binding.backupRules.isChecked,
                        binding.backupSettings.isChecked
                    )
                    WebDavBackup.mkcol(folder, user, pass)
                    WebDavBackup.put(folder + fileName, user, pass, data)
                    onMainDispatcher {
                        snackbar("WebDAV 上传完成：$fileName").show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar("WebDAV 上传失败：" + e.readableMessage).show()
                    }
                }
            }
        }

        binding.actionWebdavDelete.setOnClickListener {
            val folder = webdavFolder()
            if (folder == null) {
                snackbar("先填 WebDAV 地址").show()
                return@setOnClickListener
            }
            val user = binding.webdavUser.text.toString()
            val pass = binding.webdavPass.text.toString()
            runOnDefaultDispatcher {
                try {
                    val names = WebDavBackup.list(folder, user, pass)
                        .filter {
                            it.startsWith("nekoboxpp_backup") && it.endsWith(".json")
                        }
                        .sortedDescending()
                    if (names.isEmpty()) {
                        onMainDispatcher {
                            snackbar("服务器上没有备份文件").show()
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher {
                        val checked = BooleanArray(names.size)
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(
                            requireContext()
                        )
                            .setTitle("选择要删除的备份")
                            .setMultiChoiceItems(
                                names.toTypedArray(), checked
                            ) { _, which, isChecked ->
                                checked[which] = isChecked
                            }
                            .setPositiveButton("删除") { _, _ ->
                                val chosen = names.filterIndexed { i, _ -> checked[i] }
                                if (chosen.isEmpty()) return@setPositiveButton
                                runOnDefaultDispatcher {
                                    try {
                                        chosen.forEach {
                                            WebDavBackup.delete(folder + it, user, pass)
                                        }
                                        onMainDispatcher {
                                            snackbar("已删除 ${chosen.size} 个备份").show()
                                        }
                                    } catch (e: Exception) {
                                        Logs.w(e)
                                        onMainDispatcher {
                                            snackbar(
                                                "删除失败：" + e.readableMessage
                                            ).show()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar("读取备份列表失败：" + e.readableMessage).show()
                    }
                }
            }
        }

        binding.actionWebdavDownload.setOnClickListener {
            val folder = webdavFolder()
            if (folder == null) {
                snackbar("先填 WebDAV 地址").show()
                return@setOnClickListener
            }
            val user = binding.webdavUser.text.toString()
            val pass = binding.webdavPass.text.toString()
            runOnDefaultDispatcher {
                try {
                    val names = WebDavBackup.list(folder, user, pass)
                        .filter {
                            it.startsWith("nekoboxpp_backup") && it.endsWith(".json")
                        }
                        .sortedDescending()
                    if (names.isEmpty()) {
                        onMainDispatcher {
                            snackbar("服务器上没有备份文件").show()
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(
                            requireContext()
                        )
                            .setTitle("选择要恢复的备份")
                            .setItems(names.toTypedArray()) { _, which ->
                                val chosen = names[which]
                                runOnDefaultDispatcher {
                                    try {
                                        val raw = WebDavBackup.get(
                                            folder + chosen, user, pass
                                        )
                                        importFromRaw(raw)
                                    } catch (e: Exception) {
                                        Logs.w(e)
                                        onMainDispatcher {
                                            snackbar(
                                                "WebDAV 下载失败：" + e.readableMessage
                                            ).show()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar("WebDAV 下载失败：" + e.readableMessage).show()
                    }
                }
            }
        }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put("profiles", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(it.toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }
        return out.toStringPretty()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val fileName = requireContext().contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json")) {
            onMainDispatcher {
                snackbar(getString(R.string.backup_not_file, fileName)).show()
            }
            return
        }

        suspend fun invalid() = onMainDispatcher {
            onMainDispatcher {
                snackbar(getString(R.string.invalid_backup_file)).show()
            }
        }

        val content = try {
            JSONObject((requireContext().contentResolver.openInputStream(file) ?: return).use {
                it.bufferedReader().readText()
            })
        } catch (e: Exception) {
            Logs.w(e)
            invalid()
            return
        }
        val version = content.optInt("version", 0)
        if (version < 1 || version > 1) {
            invalid()
            return
        }

        confirmImport(content)
    }

    suspend fun importFromRaw(raw: String) {
        val content = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                snackbar(getString(R.string.invalid_backup_file)).show()
            }
            return
        }
        val version = content.optInt("version", 0)
        if (version < 1 || version > 1) {
            onMainDispatcher {
                snackbar(getString(R.string.invalid_backup_file)).show()
            }
            return
        }
        confirmImport(content)
    }

    suspend fun confirmImport(content: JSONObject) {
        onMainDispatcher {
            val import = LayoutImportBinding.inflate(layoutInflater)
            if (!content.has("profiles")) {
                import.backupConfigurations.isVisible = false
            }
            if (!content.has("rules")) {
                import.backupRules.isVisible = false
            }
            if (!content.has("settings")) {
                import.backupSettings.isVisible = false
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    SagerNet.stopService()

                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    runOnDefaultDispatcher {
                        runCatching {
                            finishImport(
                                content,
                                import.backupConfigurations.isChecked,
                                import.backupRules.isChecked,
                                import.backupSettings.isChecked
                            )
                            triggerFullRestart(requireContext())
                        }.onFailure {
                            Logs.w(it)
                            onMainDispatcher {
                                alert(it.readableMessage).tryToShow()
                            }
                        }

                        onMainDispatcher {
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        if (profile && content.has("profiles")) {
            val profiles = mutableListOf<ProxyEntity>()
            val jsonProfiles = content.getJSONArray("profiles")
            for (i in 0 until jsonProfiles.length()) {
                val data = Util.b64Decode(jsonProfiles[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                profiles.add(ProxyEntity.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            SagerDatabase.proxyDao.reset()
            SagerDatabase.proxyDao.insert(profiles)

            val groups = mutableListOf<ProxyGroup>()
            val jsonGroups = content.getJSONArray("groups")
            for (i in 0 until jsonGroups.length()) {
                val data = Util.b64Decode(jsonGroups[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                groups.add(ProxyGroup.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            SagerDatabase.groupDao.reset()
            SagerDatabase.groupDao.insert(groups)
        }
        if (rule && content.has("rules")) {
            val rules = mutableListOf<RuleEntity>()
            val jsonRules = content.getJSONArray("rules")
            for (i in 0 until jsonRules.length()) {
                val data = Util.b64Decode(jsonRules[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                rules.add(ParcelizeBridge.createRule(parcel))
                parcel.recycle()
            }
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(rules)
        }
        if (setting && content.has("settings")) {
            val settings = mutableListOf<KeyValuePair>()
            val jsonSettings = content.getJSONArray("settings")
            for (i in 0 until jsonSettings.length()) {
                val data = Util.b64Decode(jsonSettings[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                settings.add(KeyValuePair.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            PublicDatabase.kvPairDao.reset()
            PublicDatabase.kvPairDao.insert(settings)
        }
    }

}