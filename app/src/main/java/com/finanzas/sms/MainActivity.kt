package com.finanzas.sms

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Telephony
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

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
        store = TransactionStore(this)
        buildUi()
        ensurePermissions()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun buildUi() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        summary = TextView(this).apply { textSize = 16f }
        root.addView(summary)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val importBtn = Button(this).apply {
            text = "Importar SMS"
            setOnClickListener { importOldSms() }
        }
        val addBtn = Button(this).apply {
            text = "Agregar"
            setOnClickListener { addManual() }
        }
        val transfersBtn = Button(this).apply {
            text = "Transferencias"
            setOnClickListener { startActivity(Intent(this@MainActivity, TransfersActivity::class.java)) }
        }
        buttons.addView(importBtn, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(addBtn, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(transfersBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)

        list = ListView(this)
        list.setOnItemLongClickListener { _, _, pos, _ ->
            val t = items[pos]
            AlertDialog.Builder(this)
                .setTitle("Eliminar registro")
                .setMessage("${t.merchant} · ${money.format(t.amount)}")
                .setPositiveButton("Eliminar") { _, _ -> store.delete(t.id); refresh() }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun refresh() {
        // las transferencias tienen su propia pantalla
        items = store.all().filter { !it.isTransfer }

        // resumen del mes en curso, por cuenta
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val month = items.filter { it.kind == Kind.GASTO && it.date >= cal.timeInMillis }
        val perAccount = month.groupBy { it.account }.mapValues { e -> e.value.sumOf { it.amount } }
        val sb = StringBuilder("Gastos del mes: ${money.format(month.sumOf { it.amount })}")
        perAccount.forEach { (acc, total) -> sb.append("\n  $acc: ${money.format(total)}") }
        if (items.isEmpty()) sb.append("\n\nAún no hay transacciones. Cuando llegue un SMS del banco aparecerá aquí.")
        summary.text = sb.toString()

        val rows = items.map {
            val sign = if (it.isExpense) "−" else "+"
            val warn = if (it.identified) "" else "  (revisar)"
            "$sign${money.format(it.amount)}  ·  ${it.merchant}$warn\n${it.account}  ·  ${dateFmt.format(Date(it.date))}"
        }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    // ---------- permisos ----------

    private fun hasSmsPermissions() =
        checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        if (!hasSmsPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 1)
        }
    }

    // ---------- importar historial ----------

    private fun importOldSms() {
        if (!hasSmsPermissions()) {
            ensurePermissions()
            Toast.makeText(this, "Acepta el permiso de SMS y vuelve a intentar", Toast.LENGTH_LONG).show()
            return
        }
        var added = 0
        contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "date DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val body = c.getString(0) ?: continue
                val parsed = SmsParser.parse(body) ?: continue
                if (store.insert(parsed, body, c.getLong(1))) added++
            }
        }
        Toast.makeText(this, "Se importaron $added transacciones", Toast.LENGTH_LONG).show()
        refresh()
    }

    // ---------- registro manual (para lo que no llegue por SMS) ----------

    private fun addManual() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val value = EditText(this).apply {
            hint = "Valor (ej. 25000)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val account = EditText(this).apply { hint = "Cuenta (ej. Bancolombia *1234)" }
        val merchant = EditText(this).apply { hint = "Establecimiento" }
        form.addView(value); form.addView(account); form.addView(merchant)

        AlertDialog.Builder(this)
            .setTitle("Agregar gasto")
            .setView(form)
            .setPositiveButton("Guardar") { _, _ ->
                val amount = value.text.toString().toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(this, "Valor inválido", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val p = ParsedTransaction(
                    amount = amount,
                    kind = Kind.GASTO,
                    bank = account.text.toString().ifBlank { "Manual" },
                    account = account.text.toString().ifBlank { "Manual" },
                    merchant = merchant.text.toString().ifBlank { "Sin identificar" },
                    identified = true
                )
                store.insert(p, "manual-${System.nanoTime()}", System.currentTimeMillis())
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refresh()
    }
}
