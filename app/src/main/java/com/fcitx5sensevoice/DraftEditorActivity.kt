package com.fcitx5sensevoice

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

internal object DraftEditStore {
    private const val PREFS = "eg_private_draft_editor"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun begin(context: Context, source: String): String {
        val token = UUID.randomUUID().toString()
        check(prefs(context).edit().clear().putString("token", token)
            .putString("source", source).commit()) { "无法保存草稿，请稍后重试" }
        return token
    }
    fun source(context: Context, token: String): String? {
        val p = prefs(context)
        return if (p.getString("token", null) == token) p.getString("source", "") else null
    }
    fun save(context: Context, token: String, text: String): Boolean {
        val p = prefs(context)
        if (p.getString("token", null) != token) return false
        return p.edit().putString("result", text).putBoolean("ready", true).commit()
    }
    fun takeResult(context: Context): String? {
        val p = prefs(context)
        if (!p.getBoolean("ready", false)) return null
        val result = p.getString("result", "") ?: ""
        return if (p.edit().clear().commit()) result else null
    }
    fun invalidate(context: Context) {
        val p = prefs(context)
        if (p.contains("token")) p.edit().clear().apply()
    }
}

class DraftEditorActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var notice: TextView
    private var token = ""
    private var original = ""
    private var waitingForVoice = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra("draft_token") ?: ""
        val source = DraftEditStore.source(this, token)
        if (source == null) { finish(); return }
        original = source
        waitingForVoice = savedInstanceState?.getBoolean("waiting") ?: false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        notice = TextView(this).apply {
            text = "编辑草稿：先选择手机已有的打字键盘。保存后切回语音输入法，再点上屏草稿；这里不会发送聊天消息。"
            textSize = 16f
        }
        panel.addView(notice)
        panel.addView(Button(this).apply {
            text = "选择打字键盘 / 切回语音输入法"
            setOnClickListener { keyboardPicker() }
        })
        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setSingleLine(false)
            setText(savedInstanceState?.getString("text") ?: source)
            setSelection(text.length)
            isEnabled = !waitingForVoice
        }
        panel.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(Button(this).apply {
            text = "保存草稿并返回"
            setOnClickListener { completeEditing() }
        })
        panel.addView(Button(this).apply {
            text = "取消修改"
            setOnClickListener { cancelEditing() }
        })
        setContentView(panel)
        if (waitingForVoice) notice.text = "草稿已保存。请选择本语音输入法以返回聊天；不会自动发送。"
    }

    private fun keyboardPicker() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun voiceSelected(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        val component = ComponentName.unflattenFromString(id) ?: return false
        return component == ComponentName(this, VoiceInputMethodService::class.java)
    }

    private fun completeEditing() {
        if (!DraftEditStore.save(this, token, editor.text.toString())) {
            Toast.makeText(this, "草稿已变化或保存失败，请复制文字后返回，避免丢失修改", Toast.LENGTH_LONG).show()
            return
        }
        if (voiceSelected()) { finish(); return }
        waitingForVoice = true
        editor.isEnabled = false
        notice.text = "草稿已保存。请选择本语音输入法以返回聊天；不会自动发送。"
        keyboardPicker()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && waitingForVoice && voiceSelected()) finish()
    }

    private fun cancelEditing() {
        fun discard() {
            DraftEditStore.save(this, token, original)
            finish()
        }
        if (editor.text.toString() == original) { discard(); return }
        AlertDialog.Builder(this).setMessage("放弃本次修改，保留原草稿？")
            .setNegativeButton("继续编辑", null)
            .setPositiveButton("放弃修改") { _, _ -> discard() }.show()
    }

    @Deprecated("Uses the Activity back fallback")
    override fun onBackPressed() { if (::editor.isInitialized) cancelEditing() else super.onBackPressed() }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::editor.isInitialized) outState.putString("text", editor.text.toString())
        outState.putBoolean("waiting", waitingForVoice)
        super.onSaveInstanceState(outState)
    }
}
