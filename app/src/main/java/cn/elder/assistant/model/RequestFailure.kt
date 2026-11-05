package cn.elder.assistant.model

import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException

object RequestFailure {
    fun describe(error:Throwable,cancellation:String?):JSONObject {
        val reason=cancellation ?: when(error) {
            is ModelFailure -> when(error.code) {
                "HTTP_401" -> "模型密钥无效或未获授权"
                "HTTP_429" -> "模型服务限流或额度不足"
                else -> "模型服务请求失败：${error.code}"
            }
            is UnknownHostException -> "无法解析模型服务器地址，请检查网络与 API 地址"
            is InterruptedIOException -> "模型请求超时或传输被中断"
            is SocketException -> "网络连接中断（未记录到 App 主动取消），请检查网络后重试"
            else -> "模型请求失败：${error.message ?: error.javaClass.simpleName}"
        }
        return JSONObject().put("error",reason).put("error_code",if(cancellation!=null) "APP_CANCELLED" else if(error is ModelFailure) error.code else "TRANSPORT_ERROR")
            .put("stage",if(cancellation!=null) "cancellation" else if(error is ModelFailure && error.code=="INVALID_ENVELOPE") "response_parse" else "request").put("rule","model_transport_and_envelope").put("expected","请求可达、HTTP 成功且响应结构有效").put("actual",error.message ?: error.javaClass.simpleName)
            .put("exception_type",error.javaClass.simpleName).put("exception_message",error.message ?: JSONObject.NULL)
            .put("cancel_reason",cancellation ?: JSONObject.NULL)
    }
}
