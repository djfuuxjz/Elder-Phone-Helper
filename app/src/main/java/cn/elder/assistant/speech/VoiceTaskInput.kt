package cn.elder.assistant.speech

import android.content.Intent
import android.speech.RecognizerIntent
import cn.elder.assistant.core.TaskGoal

/** Delegates recording to the installed recognizer; this app never records/stores audio. */
object VoiceTaskInput {
    const val REQUEST=301
    fun intent():Intent=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN")
        .putExtra(RecognizerIntent.EXTRA_PROMPT,"请说您想在手机上做什么")
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)
    fun result(data:Intent?):String {
        val text=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }
            ?: error("没有听清，请重试或直接输入文字")
        return TaskGoal.custom(text).goal
    }
}
