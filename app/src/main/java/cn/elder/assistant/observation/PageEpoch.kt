package cn.elder.assistant.observation

/** Visual-only pages may change without exposing a different accessibility tree. */
class PageEpoch {
    var version=0; private set
    var trackingVisual=false
    fun pageEvent() { if(trackingVisual) version++ }
    fun key(fingerprint:String)="$fingerprint|$version"
    fun reset() { version=0;trackingVisual=false }
}
