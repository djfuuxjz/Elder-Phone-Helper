package cn.elder.assistant.guidance

enum class Phase { IDLE, PREPARING, OBSERVING, REQUESTING, WAITING_USER, VERIFYING, NEEDS_ATTENTION, COMPLETED, FAILED, STOPPED }
data class RequestToken(val generation:Int,val version:Int,val step:Int,val attempt:Int)

class SessionFlow {
    var phase=Phase.IDLE; private set
    var generation=0; private set
    var version=0; private set
    var step=0; private set
    var attempt=0; private set
    var fingerprint:String?=null; private set
    var active=false; private set
    private var outstanding:RequestToken?=null
    fun start() { generation++;version=0;step=0;attempt=0;fingerprint=null;outstanding=null;active=true;phase=Phase.PREPARING }
    fun observe(value:String):Boolean {
        if(!active || phase==Phase.NEEDS_ATTENTION || value==fingerprint) return false
        fingerprint=value;version++;step++;attempt=0;outstanding=null;phase=Phase.OBSERVING
        return true
    }
    fun request():RequestToken {
        check(active && fingerprint!=null && phase in setOf(Phase.OBSERVING,Phase.VERIFYING)) { "请求阶段无效：active=$active, snapshot=$fingerprint, phase=$phase" }
        check(attempt<3) { "本步请求已达上限，请重试或重新开始" }
        attempt++;phase=Phase.REQUESTING
        return RequestToken(generation,version,step,attempt).also { outstanding=it }
    }
    fun accepts(token:RequestToken) = active && token==outstanding && token.generation==generation && token.version==version && phase==Phase.REQUESTING
    fun responded(token:RequestToken,next:Phase):Boolean {
        if(!accepts(token)) return false
        require(next in setOf(Phase.OBSERVING,Phase.WAITING_USER,Phase.VERIFYING,Phase.NEEDS_ATTENTION)) { "响应后的阶段无效：$next" }
        outstanding=null;phase=next;return true
    }
    fun pause() { if(active) { version++;outstanding=null;phase=Phase.NEEDS_ATTENTION } }
    fun retry() { check(active) { "会话未运行，不能重试" };version++;step++;attempt=0;outstanding=null;phase=Phase.OBSERVING }
    fun finish(result:Phase) { require(result in setOf(Phase.COMPLETED,Phase.STOPPED,Phase.FAILED)) { "非法结束状态：$result" };active=false;generation++;outstanding=null;phase=result }
}
