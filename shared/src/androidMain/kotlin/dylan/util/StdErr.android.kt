package dylan.util

internal actual fun logErr(msg: String) {
    android.util.Log.e("dylan", msg)
}
