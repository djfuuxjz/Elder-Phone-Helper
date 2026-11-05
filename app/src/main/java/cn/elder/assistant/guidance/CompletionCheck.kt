package cn.elder.assistant.guidance

import cn.elder.assistant.core.*

object CompletionCheck {
    fun modelConfirms(d:Decision):Boolean=d.completed && d.action==Action.COMPLETE
    // Only an exact, single-purpose launch goal is locally verifiable. Composite goals must use the model.
    fun openingGoal(task:TaskGoal,apps:List<InstalledApp>):InstalledApp? {
        val goal=task.goal.trim().removePrefix("请").removeSuffix("。").trim()
        return apps.singleOrNull { goal=="打开${it.label}" || goal=="启动${it.label}" }
    }
    fun local(task:TaskGoal,s:Snapshot,apps:List<InstalledApp>):Boolean = !task.fixed && openingGoal(task,apps)?.packageName==s.packageName
    fun evidence(d:Decision,s:Snapshot,hasImage:Boolean):Boolean {
        if(!modelConfirms(d) || d.evidence.none { it.isNotBlank() }) return false
        val visible=s.nodes.flatMap { listOf(it.text,it.description) }.filter { it.isNotBlank() }
        // Images permit visual evidence; text-only completions require a literal current-page observation.
        return hasImage || d.evidence.any { evidence -> visible.any { it.length>=2 && evidence.contains(it) } }
    }
}
