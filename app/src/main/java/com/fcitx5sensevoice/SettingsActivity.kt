package com.fcitx5sensevoice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {
    private val settingsStore by lazy { AppSettings(this) }
    private val copyExecutor = Executors.newSingleThreadExecutor()
    private lateinit var uiLanguageSpinner: Spinner
    private lateinit var uiThemeSpinner: Spinner
    private lateinit var languageSpinner: Spinner
    private lateinit var itnSwitch: MaterialSwitch
    private lateinit var vadSensitivitySpinner: Spinner
    private lateinit var endpointSilenceSpinner: Spinner
    private lateinit var imeStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var permissionAction: Button
    private lateinit var imeStatusIcon: ImageView
    private lateinit var permissionStatusIcon: ImageView
    private lateinit var advancedContent: View
    private lateinit var advancedChevron: View
    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { renderPermissionState() }

    private val modelFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            importSelectedModelFile(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(settingsStore.loadUiTheme().appCompatMode)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        configureEdgeToEdge(findViewById(R.id.settings_scroll))
        bindViews()
        populateSpinners()
        showSettings(settingsStore.load())
        findViewById<Button>(R.id.reset_defaults).setOnClickListener {
            showSettings(AsrSettings.DEFAULT)
            applyUiPreferences(UiLanguage.SYSTEM, UiTheme.SYSTEM)
        }
        permissionAction.setOnClickListener { requestMicrophonePermission() }
        findViewById<Button>(R.id.open_ime_settings).setOnClickListener { openInputMethodSettings() }
        bindAboutAndLegal()
        findViewById<View>(R.id.settings_back).setOnClickListener { finish() }
        findViewById<View>(R.id.advanced_toggle).setOnClickListener { toggleAdvancedSettings() }

        if (savedInstanceState == null) {
            if (intent.getBooleanExtra(EXTRA_REQUEST_MICROPHONE_PERMISSION, false) && !hasPermission()) {
                requestMicrophonePermission()
            }
            if (intent.getBooleanExtra(EXTRA_IMPORT_LLM_MODEL, false)) {
                Toast.makeText(this, "请选择手机中已下载的 .litertlm 或 .task 本地模型文件", Toast.LENGTH_LONG).show()
                modelFilePicker.launch(arrayOf("*/*"))
            }
        }
    }

    private fun importSelectedModelFile(uri: Uri) {
        Toast.makeText(this, "正在导入本地大模型到键盘专用目录，请稍候...", Toast.LENGTH_LONG).show()
        copyExecutor.execute {
            try {
                val fileName = queryDisplayName(uri) ?: "model.litertlm"
                val targetDir = File(filesDir, "llm_models").apply { mkdirs() }
                targetDir.listFiles()?.forEach { it.delete() }
                val targetFile = File(targetDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                } ?: error("无法读取所选文件")

                getSharedPreferences(LocalLlmEngine.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(LocalLlmEngine.KEY_MODEL_PATH, targetFile.absolutePath)
                    .apply()

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "本地模型已成功导入：${targetFile.name}",
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "导入模型失败：${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    override fun onResume() {
        super.onResume()
        renderImeState()
        renderPermissionState()
    }

    override fun onPause() {
        settingsStore.save(readSettings())
        super.onPause()
    }

    override fun onDestroy() {
        copyExecutor.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        uiLanguageSpinner = findViewById(R.id.ui_language)
        uiThemeSpinner = findViewById(R.id.ui_theme)
        languageSpinner = findViewById(R.id.recognition_language)
        itnSwitch = findViewById(R.id.use_itn)
        vadSensitivitySpinner = findViewById(R.id.vad_sensitivity)
        endpointSilenceSpinner = findViewById(R.id.endpoint_silence)
        imeStatus = findViewById(R.id.ime_status)
        permissionStatus = findViewById(R.id.permission_status)
        permissionAction = findViewById(R.id.grant_permission)
        imeStatusIcon = findViewById(R.id.ime_status_icon)
        permissionStatusIcon = findViewById(R.id.permission_status_icon)
        advancedContent = findViewById(R.id.advanced_content)
        advancedChevron = findViewById(R.id.advanced_chevron)
    }

    private fun populateSpinners() {
        uiLanguageSpinner.setEntries(R.array.ui_language_labels)
        uiThemeSpinner.setEntries(R.array.ui_theme_labels)
        languageSpinner.setEntries(R.array.recognition_language_labels)
        vadSensitivitySpinner.setEntries(R.array.vad_sensitivity_labels)
        endpointSilenceSpinner.setEntries(R.array.endpoint_silence_labels)
        uiLanguageSpinner.setSelection(UiLanguage.entries.indexOf(currentUiLanguage(this)))
        uiThemeSpinner.setSelection(UiTheme.entries.indexOf(settingsStore.loadUiTheme()))
        uiLanguageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {\n            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {\n                applyUiPreferences(\n                    language = UiLanguage.entries[position.coerceIn(UiLanguage.entries.indices)],\n                    theme = settingsStore.loadUiTheme(),\n                )\n            }\n\n            override fun onNothingSelected(parent: AdapterView<*>?) = Unit\n        }
        uiThemeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyUiPreferences(
                    language = currentUiLanguage(this@SettingsActivity),
                    theme = UiTheme.entries[position.coerceIn(UiTheme.entries.indices)],
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun Spinner.setEntries(arrayResource: Int) {
        adapter = ArrayAdapter.createFromResource(
            this@SettingsActivity,
            arrayResource,
            android.R.layout.simple_spinner_item,
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun showSettings(settings: AsrSettings) {
        languageSpinner.setSelection(RecognitionLanguage.entries.indexOf(settings.language))
        itnSwitch.isChecked = settings.useInverseTextNormalization
        vadSensitivitySpinner.setSelection(VAD_THRESHOLDS.nearestIndex(settings.vadThreshold))
        endpointSilenceSpinner.setSelection(ENDPOINT_SILENCE_SECONDS.nearestIndex(settings.vadMinSilenceSeconds))
    }

    private fun readSettings(): AsrSettings = AsrSettings(
        language = RecognitionLanguage.entries[languageSpinner.selectedItemPosition.coerceIn(RecognitionLanguage.entries.indices)],
        useInverseTextNormalization = itnSwitch.isChecked,
        vadThreshold = VAD_THRESHOLDS[vadSensitivitySpinner.selectedItemPosition.coerceIn(VAD_THRESHOLDS.indices)],
        vadMinSilenceSeconds = ENDPOINT_SILENCE_SECONDS[
            endpointSilenceSpinner.selectedItemPosition.coerceIn(ENDPOINT_SILENCE_SECONDS.indices)
        ],
    )

    private fun applyUiPreferences(language: UiLanguage, theme: UiTheme) {
        if (language != currentUiLanguage(this)) AppCompatDelegate.setApplicationLocales(language.toLocaleList())
        if (theme != settingsStore.loadUiTheme()) {
            settingsStore.saveUiTheme(theme)
            AppCompatDelegate.setDefaultNightMode(theme.appCompatMode)
        }
    }

    private fun FloatArray.nearestIndex(value: Float): Int {
        var result = 0
        for (index in 1 until size) {
            if (kotlin.math.abs(this[index] - value) < kotlin.math.abs(this[result] - value)) result = index
        }
        return result
    }

    private fun renderImeState() {
        val enabled = isImeEnabled()
        renderStatus(imeStatusIcon, imeStatus, enabled, if (enabled) R.string.ime_enabled else R.string.ime_disabled)
    }

    private fun renderStatus(icon: ImageView, label: TextView, ok: Boolean, textRes: Int) {
        val color = getColor(if (ok) R.color.settings_status_ok else R.color.settings_status_warn)
        label.setText(textRes)
        label.setTextColor(color)
        icon.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_warning_circle)
        icon.imageTintList = ColorStateList.valueOf(color)
    }

    private fun toggleAdvancedSettings() {
        val expand = advancedContent.visibility != View.VISIBLE
        advancedContent.visibility = if (expand) View.VISIBLE else View.GONE
        advancedChevron.animate().rotation(if (expand) 180f else 0f).setDuration(200L).start()
    }

    private fun isImeEnabled(): Boolean {
        val manager = getSystemService(InputMethodManager::class.java)
        return manager.enabledInputMethodList.any { method -> method.packageName == packageName }
    }

    private fun openInputMethodSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    private fun renderPermissionState() {
        val granted = hasPermission()
        renderStatus(
            permissionStatusIcon,
            permissionStatus,
            granted,
            if (granted) R.string.permission_granted else R.string.permission_explanation,
        )
        permissionAction.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun requestMicrophonePermission() {
        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun bindAboutAndLegal() {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        findViewById<TextView>(R.id.about_version).text = getString(
            R.string.about_version,
            packageInfo.versionName,
            packageInfo.longVersionCode,
        )
        findViewById<Button>(R.id.open_github_repository).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPOSITORY_URL)))
        }
        findViewById<TextView>(R.id.open_privacy_policy).setOnClickListener {
            openLegalDocument(LegalDocumentActivity.DOCUMENT_PRIVACY)
        }
        findViewById<TextView>(R.id.open_third_party_notices).setOnClickListener {
            openLegalDocument(LegalDocumentActivity.DOCUMENT_THIRD_PARTY)
        }
        findViewById<TextView>(R.id.open_model_attribution).setOnClickListener {
            openLegalDocument(LegalDocumentActivity.DOCUMENT_MODEL)
        }
        findViewById<TextView>(R.id.open_onnx_notices).setOnClickListener {
            openLegalDocument(LegalDocumentActivity.DOCUMENT_ONNX_NOTICES)
        }
    }

    private fun openLegalDocument(document: String) {
        startActivity(
            Intent(this, LegalDocumentActivity::class.java).putExtra(
                LegalDocumentActivity.EXTRA_DOCUMENT,
                document,
            ),
        )
    }

    companion object {
        const val EXTRA_REQUEST_MICROPHONE_PERMISSION = "request_microphone_permission"
        const val EXTRA_IMPORT_LLM_MODEL = "import_llm_model"
        private const val GITHUB_REPOSITORY_URL = "https://github.com/ylb62789972/fcitx5-sensevoice"
        private val VAD_THRESHOLDS = floatArrayOf(0.15f, AsrSettings.DEFAULT_VAD_THRESHOLD, 0.4f)
        private val ENDPOINT_SILENCE_SECONDS =
            floatArrayOf(0.5f, AsrSettings.DEFAULT_VAD_MIN_SILENCE_SECONDS, 1f, 1.5f)
    }
}
