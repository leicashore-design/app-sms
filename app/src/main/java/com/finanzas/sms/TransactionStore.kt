package com.finanzas.sms

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Transaction(
    val id: Long,
    val amount: Double,
    val kind: Kind,
    val account: String,
    val merchant: String,
    val identified: Boolean,
    val counterparty: String?,
    val raw: String,
    val date: Long
) {
    val isExpense: Boolean get() = kind == Kind.GASTO || kind == Kind.TRANSFER_OUT
    val isTransfer: Boolean get() = kind == Kind.TRANSFER_OUT || kind == Kind.TRANSFER_IN
}

/** Base de datos local (SQLite). Todo queda en el telefono. */
class TransactionStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "finanzas.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE transactions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount REAL NOT NULL,
                expense INTEGER NOT NULL,
                account TEXT NOT NULL,
                merchant TEXT NOT NULL,
                identified INTEGER NOT NULL,
                raw TEXT NOT NULL,
                date INTEGER NOT NULL,
                kind TEXT NOT NULL DEFAULT 'GASTO',
                counterparty TEXT,
                UNIQUE(raw, date)
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE transactions ADD COLUMN kind TEXT NOT NULL DEFAULT 'GASTO'")
            db.execSQL("ALTER TABLE transactions ADD COLUMN counterparty TEXT")
            db.execSQL("UPDATE transactions SET kind = 'INGRESO' WHERE expense = 0")
        }
    }

    /** Devuelve true si se guardo (false si ya existia). */
    fun insert(p: ParsedTransaction, raw: String, date: Long): Boolean {
        val v = ContentValues().apply {
            put("amount", p.amount)
            put("expense", if (p.isExpense) 1 else 0)
            put("account", p.account)
            put("merchant", p.merchant)
            put("identified", if (p.identified) 1 else 0)
            put("raw", raw)
            put("date", date)
            put("kind", p.kind.name)
            put("counterparty", p.counterparty)
        }
        return writableDatabase.insertWithOnConflict(
            "transactions", null, v, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun all(): List<Transaction> {
        val out = mutableListOf<Transaction>()
        readableDatabase.rawQuery(
            "SELECT id, amount, kind, account, merchant, identified, counterparty, raw, date " +
                "FROM transactions ORDER BY date DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out += Transaction(
                    id = c.getLong(0),
                    amount = c.getDouble(1),
                    kind = runCatching { Kind.valueOf(c.getString(2)) }.getOrDefault(Kind.GASTO),
                    account = c.getString(3),
                    merchant = c.getString(4),
                    identified = c.getInt(5) == 1,
                    counterparty = c.getString(6),
                    raw = c.getString(7),
                    date = c.getLong(8)
                )
            }
        }
        return out
    }

    fun delete(id: Long) {
        writableDatabase.delete("transactions", "id = ?", arrayOf(id.toString()))
    }
}
