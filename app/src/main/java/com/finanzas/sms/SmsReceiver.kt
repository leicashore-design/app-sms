package com.finanzas.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Se ejecuta cada vez que llega un SMS; guarda solo los que son transacciones bancarias. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return

        // un SMS largo llega en varias partes: se unen
        val body = parts.joinToString("") { it.messageBody ?: "" }
        val date = parts.first().timestampMillis

        val parsed = SmsParser.parse(body) ?: return
        TransactionStore(context).insert(parsed, body, date)
    }
}
