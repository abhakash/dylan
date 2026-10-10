package dylan.util

/** Platform stderr sink — JVM has System.err; Native routes through NSLog/fprintf. */
internal expect fun logErr(msg: String)
