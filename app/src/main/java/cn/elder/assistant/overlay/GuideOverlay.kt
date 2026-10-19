package cn.elder.assistant.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.view.*
import android.widget.*
import cn.elder.assistant.core.Box
import cn.elder.assistant.observation.Coordinates

class GuideOverlay(private val service: AccessibilityService, private val onStop:()->Unit, private val onRetry:()->Unit,
    private val wm:WindowManager=service.getSystemService(WindowManager::class.java)) {
    private class DragArea(context:android.content.Context):TextView(context) {
        override fun performClick():Boolean { super.performClick();return true }
    }
    private var highlight: View?=null
    private var card: View?=null
    private var savedPosition:Pair<Int,Int>?=null
    companion object {
        fun highlightFlags() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        fun controlFlags() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }
    fun show(instruction:String, target:Box?=null, retry:Boolean=false, retryLabel:String="重试", controls:Boolean=true) {
        hide()
        try {
            if(target!=null) {
                val view=object:View(service) {
                    private val dim=Paint().apply { color=0x66000000 }
                    private val hole=Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
                    private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(255,214,64); style=Paint.Style.STROKE; strokeWidth=dp(4).toFloat() }
                    private val origin=IntArray(2)
                    private val rect=RectF()
                    override fun onDraw(canvas:Canvas) {
                        getLocationOnScreen(origin)
                        rect.set((target.left-origin[0]).toFloat(),(target.top-origin[1]).toFloat(),(target.right-origin[0]).toFloat(),(target.bottom-origin[1]).toFloat())
                        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),dim)
                        canvas.drawRoundRect(rect,12f,12f,hole); canvas.drawRoundRect(rect,12f,12f,ring)
                    }
                }.apply { setLayerType(View.LAYER_TYPE_SOFTWARE,null); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                val lp=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,highlightFlags(),PixelFormat.TRANSLUCENT)
                lp.gravity=Gravity.TOP or Gravity.START; lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                wm.addView(view,lp); highlight=view
            }
            val layout=LinearLayout(service).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)); setBackgroundColor(Color.rgb(255,253,242)) }
            val handle=DragArea(service).apply { text="按住这里拖动";textSize=16f;gravity=Gravity.CENTER;minHeight=dp(48);contentDescription="拖动引导栏" }
            layout.addView(handle)
            val message=DragArea(service).apply { text=instruction; textSize=20f; setTextColor(Color.rgb(28,52,41)); maxLines=4 }
            layout.addView(message)
            val row=LinearLayout(service)
            row.addView(Button(service).apply { text="停止"; textSize=18f; minHeight=dp(48); setOnClickListener { hide();onStop() } },LinearLayout.LayoutParams(0,-2,1f))
            if(retry) row.addView(Button(service).apply { text=retryLabel; textSize=18f; minHeight=dp(48); setOnClickListener { onRetry() } },LinearLayout.LayoutParams(0,-2,1f))
            if(controls) layout.addView(row)
            val metrics=wm.currentWindowMetrics
            val screen=metrics.bounds
            val insets=metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val usableWidth=(screen.width()-insets.left-insets.right).coerceAtLeast(1)
            val usableHeight=(screen.height()-insets.top-insets.bottom).coerceAtLeast(1)
            val width=minOf(dp(340),(usableWidth-dp(24)).coerceAtLeast(1))
            layout.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(usableHeight,View.MeasureSpec.AT_MOST))
            val height=layout.measuredHeight.coerceAtMost(usableHeight)
            val lp=WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,controlFlags(),PixelFormat.TRANSLUCENT)
            layout.layoutDirection=View.LAYOUT_DIRECTION_LTR
            lp.gravity=Gravity.TOP or Gravity.START
            val maxX=(usableWidth-width).coerceAtLeast(0)
            val maxY=(usableHeight-height).coerceAtLeast(0)
            val preferred=savedPosition ?: ((maxX/2) to (if(Coordinates.cardAtTop(target,screen.height())) dp(24) else maxY-dp(24)))
            lp.x=preferred.first.coerceIn(0,maxX);lp.y=preferred.second.coerceIn(0,maxY)
            // Keep the stop control available; move the card instead of removing it on collision.
            fun overlaps(x:Int,y:Int)=target?.overlaps(Box(x+insets.left,y+insets.top,x+insets.left+width,y+insets.top+height))==true
            if(overlaps(lp.x,lp.y)) {
                listOf(0 to 0,maxX to 0,0 to maxY,maxX to maxY).firstOrNull { !overlaps(it.first,it.second) }?.let { lp.x=it.first;lp.y=it.second }
            }
            var downX=0f;var downY=0f;var startX=0;var startY=0;var moved=false
            val slop=ViewConfiguration.get(service).scaledTouchSlop
            val drag=View.OnTouchListener { v,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX=event.rawX;downY=event.rawY;startX=lp.x;startY=lp.y;moved=false;true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx=event.rawX-downX;val dy=event.rawY-downY
                        if(moved || kotlin.math.abs(dx)>slop || kotlin.math.abs(dy)>slop) {
                            moved=true
                            lp.x=(startX+dx.toInt()).coerceIn(0,maxX);lp.y=(startY+dy.toInt()).coerceIn(0,maxY)
                            if(card===layout) { wm.updateViewLayout(layout,lp);savedPosition=lp.x to lp.y }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> { if(!moved) v.performClick();true }
                    MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
            handle.setOnTouchListener(drag);message.setOnTouchListener(drag)
            wm.addView(layout,lp);card=layout

        } catch(e:RuntimeException) { hide(); throw e }
    }
    fun hide() {
        val views=listOfNotNull(highlight,card)
        highlight=null;card=null
        // WindowManager can own a view before isAttachedToWindow becomes true.
        // Always remove registered windows, including an immediately stopped pending add.
        views.forEach { view -> try { wm.removeViewImmediate(view) } catch(_:IllegalArgumentException) { /* Already removed by the system. */ } }
    }
    private fun dp(n:Int)=(n*service.resources.displayMetrics.density).toInt()
}
