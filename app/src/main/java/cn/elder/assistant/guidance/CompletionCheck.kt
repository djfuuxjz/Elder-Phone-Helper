package cn.elder.assistant.guidance

import cn.elder.assistant.core.*

/** The model owns task success; App checks only the action/flag contract. */
object CompletionCheck {
    fun modelConfirms(d:Decision):Boolean = d.completed && d.action==Action.COMPLETE
}
