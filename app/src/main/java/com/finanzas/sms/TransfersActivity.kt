package com.finanzas.sms

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Modulo de transferencias: lo que envias y lo que te envian, con monto, banco y cuenta. */
class TransfersActivity : Activity() {

    private lateinit var store: TransactionStore
    private lateinit var summary: TextView
    private lateinit var list: ListView
    private var items: List<Transaction> = emptyList()

    private val money = NumberFormat.getCurrencyInstance(Locale("es", "CO")).apply {
        maximumFractionDigits = 0
    }
    private val dateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es", "CO"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Transferencias"
        store = TransactionStore(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        summary = TextView(this).apply { textSize = 16f }
        root.addView(summary)

        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ ->
            // muestra el SMS original para verificar que se leyó bien
            AlertDialog.Builder(this)
                .setTitle("SMS original")
                .setMessage(items[pos].raw)
                .setPositiveButton("Cerrar", null)
                .show()
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val t = items[pos]
            AlertDialog.Builder(this)
                .setTitle("Eliminar registro")
                .setMessage(money.format(t.amount))
                .setPositiveButton("Eliminar") { _, _ -> store.delete(t.id); refresh() }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = store.all().filter { it.isTransfer }

        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val month = items.filter { it.date >= cal.timeInMillis }
        val sent = month.filter { it.kind == Kind.TRANSFER_OUT }.sumOf { it.amount }
        val received = month.filter { it.kind == Kind.TRANSFER_IN }.sumOf { it.amount }
        summary.text = buildString {
            append("Este mes\n")
            append("  Enviado: ${money.format(sent)}\n")
            append("  Recibido: ${money.format(received)}")
            if (items.isEmpty()) append("\n\nAún no hay transferencias registradas.")
            else append("\n\nToca un registro para ver el SMS original.")
        }

        val rows = items.map {
            val whenText = dateFmt.format(Date(it.date))
            if (it.kind == Kind.TRANSFER_OUT) {
                "↑ Enviaste ${money.format(it.amount)}\n" +
                    "A: ${it.counterparty ?: "destino sin identificar"}\n" +
                    "Desde: ${it.account}  ·  $whenText"
            } else {
                "↓ Recibiste ${money.format(it.amount)}\n" +
                    "De: ${it.counterparty ?: "origen sin identificar"}\n" +
                    "En: ${it.account}  ·  $whenText"
            }
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }
}
