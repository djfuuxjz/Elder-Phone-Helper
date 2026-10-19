package cn.elder.assistant.guidance

/** Bounded task-only summaries, never persisted as a user history feature. */
class TaskHistory {
    private val records=mutableListOf<String>()
    fun clear()=records.clear()
    fun add(step:Int,event:String,detail:String) {
        records.add("第 $step 步 [$event] ${detail.take(400)}")
        if(records.size>40) records.removeAt(0)
    }
    fun snapshot():List<String> = records.toList()
}
