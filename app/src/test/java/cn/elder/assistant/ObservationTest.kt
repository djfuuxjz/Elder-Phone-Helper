package cn.elder.assistant

import cn.elder.assistant.core.Box
import cn.elder.assistant.core.Snapshot
import cn.elder.assistant.observation.*
import cn.elder.assistant.overlay.GuideOverlay
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test

class ObservationTest {
    @Test fun coordinateScaleMatchesActualScreen() { assertEquals(Box(200,400,400,600),Coordinates.fromImage(Box(100,200,200,300),540,1200,1080,2400)) }
    @Test fun windowOriginIsSubtracted() { assertEquals(Box(10,0,100,60),Coordinates.local(Box(10,40,100,100),0,40)) }
    @Test(expected=IllegalArgumentException::class) fun outOfScreenIsRejected() { Coordinates.fromImage(Box(-1,0,10,20),100,100,200,200) }
    @Test(expected=IllegalArgumentException::class) fun zeroAreaIsRejected() { Coordinates.fromImage(Box(0,0,0,20),100,100,200,200) }
    @Test(expected=IllegalArgumentException::class) fun invalidDimensionsRejected() { Coordinates.fromImage(Box(0,0,10,20),0,100,200,200) }
    @Test fun highlighterCannotCaptureTouches() { assertTrue(GuideOverlay.highlightFlags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE !=0) }
    @Test fun controlsDoNotStealOutsideTouches() { assertTrue(GuideOverlay.controlFlags() and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL !=0); assertEquals(0,GuideOverlay.controlFlags() and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) }
    @Test fun cardIsPlacedAwayFromBottomTarget() { assertTrue(Coordinates.cardAtTop(Box(0,800,100,900),1000)); assertFalse(Coordinates.cardAtTop(Box(0,50,100,100),1000)) }
    @Test fun stablePageRequiresQuietWindow() { val g=StablePage(); assertFalse(g.offer("a",0)); assertFalse(g.offer("a",499)); assertTrue(g.offer("a",500)) }
    @Test fun changedPageResetsQuietWindow() { val g=StablePage();g.offer("a",0);assertFalse(g.offer("b",499));assertFalse(g.offer("b",500));assertTrue(g.offer("b",1000)) }
    @Test fun endlessAnimationHasBoundedWait() { val g=StablePage();for(i in 0..19) assertFalse(g.offer(i.toString(),i*100L));assertTrue(g.offer("20",2000)) }
    @Test fun resetDoesNotReusePreviousPage() { val g=StablePage();g.offer("a",0);g.reset();assertFalse(g.offer("a",1000)) }
    @Test fun overlapIncludesPartialCoverage() { assertTrue(Box(0,0,100,100).overlaps(Box(90,90,200,200)));assertFalse(Box(0,0,100,100).overlaps(Box(100,100,200,200))) }
    @Test fun screenshotCannotUseDifferentWindowOrDimensions() {
        val s=Snapshot("a","wechat",1,1080,2400,emptyList(),0)
        assertFalse(SnapshotConsistency.matches(s,s.copy(windowId=2)))
        assertFalse(SnapshotConsistency.matches(s,s.copy(width=2400,height=1080)))
        assertFalse(SnapshotConsistency.matches(s,null))
        assertTrue(SnapshotConsistency.matches(s,s.copy(id="b",capturedAt=1)))
    }
    @Test fun visualOnlyPagesInvalidateEvenWithIdenticalTree() { val e=PageEpoch();val first=e.key("same-tree");e.trackingVisual=true;e.pageEvent();assertNotEquals(first,e.key("same-tree")) }
    @Test fun ordinaryDuplicateEventsDoNotInvalidateTreeBasedPage() { val e=PageEpoch();val first=e.key("tree");repeat(20) { e.pageEvent() };assertEquals(first,e.key("tree")) }
}
