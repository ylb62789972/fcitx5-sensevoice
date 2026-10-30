from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
base = root / 'app/src/main'
a = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')

def replace_method(source, name, replacement):
    pattern = r'(?ms)^    private fun ' + re.escape(name) + r'\([^\n]*\)[^\n]*\{.*?(?=^    (?:private fun |override fun |companion object)|\Z)'
    found = list(re.finditer(pattern, source))
    assert len(found) == 1, f'Method {name} changed; manual review required'
    match = found[0]
    assert match.group().rstrip().endswith('}'), f'Unexpected method boundary for {name}'
    return source[:match.start()] + replacement + source[match.end():]

p = base / 'res/layout/input_view.xml'
tree = ET.parse(p)
panel = tree.getroot()
buttons = [n for n in panel if n.get(a+'id') == '@+id/btn_polish_zh']
assert len(buttons) == 1, 'Expected exactly one old polish button'
panel.remove(buttons[0])
preview = next(n for n in panel if n.get(a+'id') == '@+id/draft_preview')
preview.set(a+'hint', '录音结束自动润色；点击这里编辑草稿，核对后点上屏草稿')
preview.set(a+'clickable', 'true')
preview.set(a+'focusable', 'true')
preview.set(a+'textIsSelectable', 'false')
preview.set(a+'contentDescription', '草稿，点击进入可编辑页面')
preview.set(a+'layout_height', '112dp')
tree.write(p, encoding='utf-8', xml_declaration=True)

p = base / 'AndroidManifest.xml'
tree = ET.parse(p)
application = tree.getroot().find('application')
assert application is not None
assert not any(n.get(a+'name') == '.DraftEditorActivity' for n in application)
ET.SubElement(application, 'activity', {
    a+'name': '.DraftEditorActivity', a+'exported': 'false',
    a+'theme': '@style/Theme.Fcitx5SenseVoice.Settings',
    a+'label': '编辑草稿', a+'taskAffinity': '', a+'excludeFromRecents': 'true',
    a+'windowSoftInputMode': 'adjustResize|stateAlwaysHidden',
})
tree.write(p, encoding='utf-8', xml_declaration=True)

p = base / 'java/com/fcitx5sensevoice/VoiceInputMethodService.kt'
s = p.read_text()
assert 'private var draftRevision' not in s
old_listener = '''            view.findViewById<View>(R.id.btn_polish_zh).setOnClickListener {
                translateAndCommit("Simplified Chinese: polish the original Chinese, remove filler and repetition, improve grammar without changing meaning")
            }'''
assert s.count(old_listener) == 1, 'Old polish listener changed'
s = s.replace(old_listener, '            draftPreview.setOnClickListener { openDraftEditor() }')
anchor = '        inputViewActive = true'
assert s.count(anchor) == 1
s = s.replace(anchor, anchor + '\n        restoreEditedDraft()')
anchor = '    private fun updateDraftView() {'
assert s.count(anchor) == 1
s = s.replace(anchor, anchor + '\n        draftRevision++\n        DraftEditStore.invalidate(this)')
anchor = '    private fun startRecording() {'
assert s.count(anchor) == 1
s = s.replace(anchor, anchor + '\n        draftRequestId++\n        DraftEditStore.invalidate(this)')
s = replace_method(s, 'finishRecognition', '''    private fun finishRecognition() {
        val engine = asrEngine
        if (engine == null) {
            voiceState = VoiceState.ERROR
            showStatus("语音模型未就绪", "原草稿已保留")
            setMicrophoneAppearance(recording = false, enabled = true)
            return
        }
        val session = inputSession
        val generation = recordingGeneration
        voiceState = VoiceState.PROCESSING
        showStatus(text(R.string.status_processing), busy = true)
        setMicrophoneAppearance(recording = false, enabled = false)
        executeAsr {
            try {
                val segments = engine.finish()
                postToMain {
                    if (destroyed || !inputViewActive || session != inputSession || generation != recordingGeneration) return@postToMain
                    segments.forEach { commitSegment(it, session, generation) }
                    voiceState = VoiceState.IDLE
                    setMicrophoneAppearance(recording = false, enabled = true)
                    if (recognizedSegments > 0 && draftBuffer.isNotBlank()) autoPolishDraft() else renderIdleState()
                }
            } catch (error: Throwable) {
                onAsrFailure(error, generation)
            }
        }
    }

''')
s = replace_method(s, 'translateAndCommit', '''    private fun translateAndCommit(targetLanguage: String) {
        transformDraft("正在离线译为 $targetLanguage…") { source ->
            localLlmEngine.translatePropertyMessage(source, targetLanguage)
        }
    }

''')
s = replace_method(s, 'pasteAndTranslateToChinese', '''    private fun pasteAndTranslateToChinese() {
        val source = readClipboardText()
        if (source == null) { showStatus("剪贴板为空", "请先复制需要翻译的文字"); return }
        draftBuffer.clear()
        draftBuffer.append(source)
        updateDraftView()
        transformDraft("正在离线译为中文…") { localLlmEngine.translateIncomingToChinese(it) }
    }

''')
s = replace_method(s, 'commitDraftDirectly', '''    private fun commitDraftDirectly() {
        val content = draftBuffer.toString()
        if (content.isBlank()) { showStatus("草稿为空", "请先录音或编辑草稿"); return }
        if (currentInputEditorInfo?.packageName == packageName) {
            showStatus("请先返回聊天输入框", "保存修改后再上屏草稿")
            return
        }
        if (currentInputConnection?.commitText(content, 1) == true) {
            draftBuffer.clear()
            updateDraftView()
            showStatus("草稿已填入输入框", "未自动发送，请在聊天软件中确认发送")
        } else {
            showStatus("无法写入当前输入框", "草稿已保留，请先点击聊天输入框")
        }
    }

''')
assert s.count('    private fun readClipboardText()') == 1
s = s.replace('    private fun readClipboardText()', '''    private var draftRevision = 0L
    private var draftRequestId = 0L

    private fun draftRequest() = DraftRequest(draftRequestId, inputSession, draftRevision, recordingGeneration)

    private fun openDraftEditor() {
        if (recordingSessionActive || voiceState == VoiceState.PROCESSING) {
            showStatus("请先结束录音并等待识别完成", "然后点击草稿修改")
            return
        }
        draftRequestId++
        try {
            val token = DraftEditStore.begin(this, draftBuffer.toString())
            startActivity(Intent(this, DraftEditorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("draft_token", token)
            })
        } catch (error: Exception) {
            DraftEditStore.invalidate(this)
            showStatus("无法打开编辑页", "原草稿已保留，请稍后重试")
        }
    }

    private fun restoreEditedDraft() {
        if (currentInputEditorInfo?.packageName == packageName) return
        val edited = DraftEditStore.takeResult(this) ?: return
        draftRequestId++
        draftBuffer.clear()
        draftBuffer.append(edited)
        updateDraftView()
        showStatus("修改已保存", "核对后点上屏草稿；不会重新润色你的修改")
    }

    private fun transformDraft(label: String, operation: (String) -> String) {
        val source = draftBuffer.toString()
        if (source.isBlank()) { showStatus("草稿为空", "请先录音或粘贴文字"); return }
        if (recordingSessionActive) { showStatus("请先结束录音", "识别完成后再处理草稿"); return }
        draftRequestId++
        val request = draftRequest()
        showStatus(label, "本地处理；可点草稿编辑，编辑后旧结果不会覆盖修改", busy = true)
        fun stillCurrent() = !destroyed && request.canApply(draftRequest(), inputViewActive, recordingSessionActive)
        try {
            llmExecutor.execute {
                try {
                    if (destroyed) return@execute
                    val result = operation(source)
                    postToMain {
                        if (!stillCurrent()) return@postToMain
                        if (result.isBlank()) {
                            showStatus("模型未返回有效文字", "原草稿已保留，可编辑后上屏")
                            return@postToMain
                        }
                        draftBuffer.clear()
                        draftBuffer.append(result)
                        updateDraftView()
                        showStatus("草稿已处理", "点击草稿可修改，核对后点上屏草稿")
                    }
                } catch (error: Exception) {
                    postToMain {
                        if (stillCurrent()) showStatus("本地模型处理失败", "原草稿已保留；可编辑后上屏或检查模型")
                    }
                }
            }
        } catch (error: RejectedExecutionException) {
            if (stillCurrent()) showStatus("模型任务暂不可用", "原草稿已保留，请重新打开输入法")
        }
    }

    private fun autoPolishDraft() {
        transformDraft("识别完成，正在自动润色…") { source ->
            localLlmEngine.translatePropertyMessage(source,
                "Simplified Chinese: polish the original Chinese, remove filler and repetition, improve grammar without changing meaning")
        }
    }

''' + '    private fun readClipboardText()', 1)
assert 'R.id.btn_polish_zh' not in s
assert 'currentInputConnection?.commitText(translated, 1)' not in s
p.write_text(s, encoding='utf-8')
p = root / 'app/build.gradle.kts'
s = p.read_text()
assert s.count('versionCode = 12') == 1
assert s.count('versionName = "0.3.0-eg-preview"') == 1
s = s.replace('versionCode = 12', 'versionCode = 13').replace('versionName = "0.3.0-eg-preview"', 'versionName = "0.3.1-eg-autoedit"')
p.write_text(s, encoding='utf-8')
print('Applied automatic polish and separate draft editor; Android build and device tests still required.')
