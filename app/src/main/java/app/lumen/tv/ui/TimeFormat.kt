package app.lumen.tv.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm", Locale.ITALY)

fun hhmm(millis: Long): String = synchronized(clock) { clock.format(Date(millis)) }
