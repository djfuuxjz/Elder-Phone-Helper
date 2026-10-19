package cn.elder.assistant.observation

import cn.elder.assistant.core.Box

class StablePage(private val quietMs: Long = 500, private val maxMs: Long = 2000) {
    private var fingerprint: String? = null
    private var since = 0L
    private var changed = 0L
    fun offer(value: String, now: Long): Boolean {
        if(fingerprint==null) since=now
        if(fingerprint!=value) { fingerprint=value; changed=now }
        return now-changed >= quietMs || now-since >= maxMs
    }
    fun reset() { fingerprint=null; since=0; changed=0 }
}

object Coordinates {
    fun fromImage(box: Box, imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): Box {
        require(imageWidth>0 && imageHeight>0 && screenWidth>0 && screenHeight>0)
        require(box.valid(imageWidth,imageHeight))
        return Box((box.left.toLong()*screenWidth/imageWidth).toInt(),(box.top.toLong()*screenHeight/imageHeight).toInt(),
            (box.right.toLong()*screenWidth/imageWidth).toInt(),(box.bottom.toLong()*screenHeight/imageHeight).toInt()).also { require(it.valid(screenWidth,screenHeight)) }
    }
    fun local(box: Box, originX: Int, originY: Int) = Box(box.left-originX,box.top-originY,box.right-originX,box.bottom-originY)
    fun cardAtTop(target: Box?, height: Int) = target != null && target.top >= height/2
}
