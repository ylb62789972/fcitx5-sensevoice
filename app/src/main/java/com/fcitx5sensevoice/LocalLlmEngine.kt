package com.fcitx5sensevoice

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

class LocalLlmEngine(private val context: Context) {
    private var litertEngine: Engine? = null
    private var loadedModelPath: String? = null

    fun findModelFile(): File? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_MODEL_PATH, null)
        if (!savedPath.isNullOrBlank()) {
            val savedFile = File(savedPath)
            if (savedFile.isFile && savedFile.length() > 1024 * 1024) return savedFile
        }

        val internalDir = File(context.filesDir, "llm_models")
        if (internalDir.isDirectory) {
            internalDir.listFiles()?.firstOrNull { isModelFile(it) }?.let { return it }
        }

        val extDir = context.getExternalFilesDir(null)
        if (extDir != null && extDir.isDirectory) {
            extDir.listFiles()?.firstOrNull { isModelFile(it) }?.let { return it }
        }

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir != null && downloadDir.isDirectory) {
            downloadDir.listFiles()?.firstOrNull { isModelFile(it) }?.let { return it }
        }

        return null
    }

    private fun isModelFile(file: File): Boolean {
        if (!file.isFile || file.length() < 1024 * 1024) return false
        val name = file.name.lowercase()
        return name.endsWith(".litertlm")
    }

    @Synchronized
    fun ensureInitialized(): File {
        val modelFile = findModelFile()
            ?: throw IllegalStateException("未找到本地大模型文件，请点左下角[📂选模型]选择已下载的 .litertlm 模型")

        if (loadedModelPath == modelFile.absolutePath && litertEngine != null) {
            return modelFile
        }

        release()
        val path = modelFile.absolutePath
        Log.i(TAG, "Initializing local LLM from $path")

        val config = EngineConfig(
            modelPath = path,
            backend = Backend.CPU(),
            cacheDir = context.cacheDir.path,
        )
        val engine = Engine(config)
        engine.initialize()
        litertEngine = engine
        loadedModelPath = path
        return modelFile
    }

    @Synchronized
    fun translatePropertyMessage(sourceText: String, targetLanguageName: String): String {
        ensureInitialized()
        val systemRule = """
            You are a professional property management manager representing European Gate PC in Athens.
            Task: Remove spoken Chinese filler words (such as 呃, 那个, 就是说, 跟他说一下, 告诉租客) and translate the message into accurate, polite, concise $targetLanguageName.
            Strict Rules:
            1. Keep all apartment numbers (e.g. 3B, A2), amounts (e.g. €42.50), dates, times, and IBAN bank accounts 100% accurate and unchanged.
            2. Do NOT promise refunds or discounts.
            3. Output ONLY the final translated text in $targetLanguageName without any explanation, quotes, or prefixes.
        """.trimIndent()

        val userPrompt = "Translate into $targetLanguageName:\n$sourceText"
        return runGeneration(systemRule, userPrompt)
    }

    @Synchronized
    fun translateIncomingToChinese(foreignText: String): String {
        ensureInitialized()
        val systemRule = """
            你是专业的物业管理翻译助手。请将以下租客发来的外语消息（可能是希腊语、英语、阿拉伯语或土耳其语）准确翻译成简体中文。
            涉及房号、金额、日期、银行账号必须100%准确保留。只输出中文译文，不要输出多余解释。
        """.trimIndent()
        val userPrompt = "请翻译成简体中文：\n$foreignText"
        return runGeneration(systemRule, userPrompt)
    }

    private fun runGeneration(systemPrompt: String, userPrompt: String): String {
        val engine = litertEngine ?: throw IllegalStateException("本地大模型尚未就绪")
        val convConfig = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
        )
        engine.createConversation(convConfig).use { conversation ->
            val reply = conversation.sendMessage(userPrompt)
            return cleanModelOutput(reply.toString())
        }
    }

    private fun cleanModelOutput(raw: String): String {
        return raw
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .trim()
            .removeSurrounding("\"")
            .trim()
    }

    @Synchronized
    fun release() {
        runCatching { litertEngine?.close() }
        litertEngine = null
        loadedModelPath = null
    }

    companion object {
        private const val TAG = "LocalLlmEngine"
        const val PREFS_NAME = "eg_local_llm_prefs"
        const val KEY_MODEL_PATH = "local_model_path"
        const val KEY_CUSTOM_SNIPPET = "custom_snippet"
        const val DEFAULT_SNIPPET = "European Gate PC"
    }
}
