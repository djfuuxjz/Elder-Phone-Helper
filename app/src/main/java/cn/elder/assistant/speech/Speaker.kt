package cn.elder.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class Speaker(context:Context, private val onError:(String)->Unit) {
    private var ready=false
    private var queued:String?=null
    private var last:String?=null
    private var tts:TextToSpeech?=null
    init {
        tts=TextToSpeech(context) { status ->
            if(status==TextToSpeech.SUCCESS) {
                val language=tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                ready=language!=null && language>=0
            }
            if(ready) queued?.let { speak(it) } else onError("语音播报不可用，请查看文字提示")
        }
    }
    fun speak(text:String) { queued=text; if(ready && text!=last) { last=text; tts?.speak(text,TextToSpeech.QUEUE_FLUSH,null,"guide") } }
    fun cancel() { queued=null; last=null; tts?.stop() }
    fun close() { cancel(); ready=false; tts?.shutdown(); tts=null }
}
