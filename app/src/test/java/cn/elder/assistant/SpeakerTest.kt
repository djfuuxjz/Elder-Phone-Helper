package cn.elder.assistant

import android.speech.tts.TextToSpeech
import cn.elder.assistant.speech.Speaker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class SpeakerTest {
    private fun ready():Pair<Speaker,ShadowTextToSpeech> {
        ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE)
        val speaker=Speaker(RuntimeEnvironment.getApplication()) { fail(it) }
        val shadow=shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        shadow.onInitListener.onInit(TextToSpeech.SUCCESS)
        return speaker to shadow
    }
    @Test fun repeatedIdenticalInstructionSpeaksOnce() { val (speaker,shadow)=ready();speaker.speak("请点击微信");speaker.speak("请点击微信");assertEquals(listOf("请点击微信"),shadow.spokenTextList);speaker.close() }
    @Test fun newPageCancelsOldSpeechAndAllowsNewPrompt() { val (speaker,shadow)=ready();speaker.speak("旧页面");speaker.cancel();assertTrue(shadow.isStopped);speaker.speak("新页面");assertEquals("新页面",shadow.lastSpokenText);assertEquals(TextToSpeech.QUEUE_FLUSH,shadow.queueMode);speaker.close() }
    @Test fun stopReleasesSpeechEngine() { val (speaker,shadow)=ready();speaker.speak("提示");speaker.close();assertTrue(shadow.isShutdown);assertTrue(shadow.isStopped) }
    @Test fun failedEngineReportsTextFallback() {
        var error:String?=null
        val speaker=Speaker(RuntimeEnvironment.getApplication()) { error=it }
        shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.ERROR)
        assertTrue(error?.contains("文字提示")==true);speaker.close()
    }
}
