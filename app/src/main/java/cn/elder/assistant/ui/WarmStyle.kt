package cn.elder.assistant.ui

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import android.widget.Button

/** Presentation only: shared palette and decorative companion. */
object WarmStyle {
    const val background = 0xFFFFF8ED.toInt()
    const val ink = 0xFF382A20.toInt()
    const val accent = 0xFFFFD58A.toInt()
    fun dp(c:Context,n:Int)=(n*c.resources.displayMetrics.density).toInt()
    fun surface(c:Context,color:Int=0xFFFFEFDA.toInt(),radius:Int=20)=GradientDrawable().apply {
        setColor(color);cornerRadius=dp(c,radius).toFloat()
        setStroke(dp(c,1),0xFFE8CDA9.toInt())
    }
    fun text(v:TextView) { v.setTextColor(ink);v.setLineSpacing(dp(v.context,3).toFloat(),1.08f) }
    fun button(v:Button,primary:Boolean=false) {
        text(v);v.textSize=20f;v.isAllCaps=false;v.minHeight=dp(v.context,60)
        v.setPadding(dp(v.context,16),dp(v.context,12),dp(v.context,16),dp(v.context,12))
        v.background=RippleDrawable(ColorStateList.valueOf(0x227B4B1F),surface(v.context,if(primary) accent else 0xFFFFEFDA.toInt()),null)
        v.layoutParams=android.widget.LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(v.context,8);bottomMargin=dp(v.context,8) }
    }
}

/** Decorative bitmap only; shares the existing size, layout and accessibility behavior. */
class CatView(context:Context):View(context) {
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isFocusable=false }
    private val cat=requireNotNull(context.getDrawable(cn.elder.assistant.R.drawable.cat_orange))
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        val scale=minOf(w.toFloat()/cat.intrinsicWidth,h.toFloat()/cat.intrinsicHeight)
        val drawnWidth=(cat.intrinsicWidth*scale).toInt()
        val drawnHeight=(cat.intrinsicHeight*scale).toInt()
        val left=(w-drawnWidth)/2;val top=(h-drawnHeight)/2
        cat.setBounds(left,top,left+drawnWidth,top+drawnHeight)
    }
    override fun onDraw(canvas:Canvas) { super.onDraw(canvas);cat.draw(canvas) }
}
