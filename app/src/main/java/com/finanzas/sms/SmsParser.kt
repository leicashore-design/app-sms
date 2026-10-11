package com.finanzas.sms

import java.text.Normalizer

enum class Kind { GASTO, TRANSFER_OUT, TRANSFER_IN, INGRESO }

/** Resultado de interpretar un SMS bancario. */
data class ParsedTransaction(
    val amount: Double,
    val kind: Kind,
    val bank: String,
    val account: String,          // cuenta propia, ej. "Bancolombia *1234"
    val merchant: String,         // establecimiento, o la persona/cuenta en una transferencia
    val identified: Boolean,      // false si no se pudo leer el establecimiento o la contraparte
    val counterparty: String? = null  // en transferencias: a quien/de donde, ej. "Davivienda *5678"
) {
    /** true si el dinero sale de tu cuenta. */
    val isExpense: Boolean get() = kind == Kind.GASTO || kind == Kind.TRANSFER_OUT
}

/**
 * Interpreta SMS de bancos colombianos. No depende de Android para poder probarse en la JVM.
 *
 * Solo procesa mensajes que mencionen un banco de [BANKS] y que parezcan una transaccion
 * (monto + palabra clave). El resto de SMS personales se ignoran.
 */
object SmsParser {

    /** palabra a buscar (sin tildes, minuscula) -> nombre a mostrar. Agrega tus bancos aqui. */
    val BANKS = listOf(
        "bancolombia" to "Bancolombia",
        "davivienda" to "Davivienda",
        "daviplata" to "Daviplata",
        "nequi" to "Nequi",
        "bbva" to "BBVA",
        "banco de bogota" to "Banco de Bogotá",
        "av villas" to "AV Villas",
        "banco popular" to "Banco Popular",
        "scotiabank" to "Scotiabank Colpatria",
        "colpatria" to "Scotiabank Colpatria",
        "itau" to "Itaú",
        "caja social" to "Banco Caja Social",
        "banco falabella" to "Banco Falabella",
        "lulo bank" to "Lulo Bank",
        "nubank" to "Nu"
    )

    private val OUT_WORDS = listOf("transferiste", "enviaste", "giraste")
    private val IN_TRANSFER_WORDS = listOf("te transfirieron", "te enviaron", "te giraron", "recibiste")
    private val INCOME_WORDS = listOf("abono", "consignacion", "deposito", "ingreso", "te pagaron")
    private val EXPENSE_WORDS = listOf(
        "compra", "compraste", "pagaste", "pago ", "retiro", "retiraste", "avance", "debito", "cargo"
    )

    /** Mensajes que NO son una transaccion realizada. */
    private val REJECT_WORDS = listOf(
        "codigo", "clave dinamica", "otp", "token", "contrasena",
        "rechazada", "rechazado", "no fue aprobada", "declinada", "fallida", "no se pudo"
    )

    private val AMOUNT_REGEX = Regex("""(?:\$|COP)\s*([0-9][0-9.,]*)""", RegexOption.IGNORE_CASE)
    private val ACCOUNT_REGEX = Regex("""(?:\*+\s?|(?:terminad[ao]|termina)\s+en\s+\*?)(\d{4})""", RegexOption.IGNORE_CASE)

    /** Cuenta de la otra parte: "a la cuenta *5678", "de la cuenta *4455", "hacia la cuenta ...". */
    private val OTHER_ACCOUNT_CONTEXT =
        Regex("""\b(?:a|hacia|para|de)\s+(?:la\s+|el\s+)?(?:cuenta|cta|llave)[^*]{0,25}$""")

    private const val STOP =
        """(?=\s+(?:con|el|la|en|a\s+(?:las|la|el|tu|su)|desde|usando|por|tarjeta|tu|su|hoy|ref)\b|\s+T\.|[,;]|\.(?:\s|$)|\s+\d{1,2}[/-]\d{1,2}|$)"""
    private val AFTER_EN = Regex("""\ben\s+(.+?)$STOP""", RegexOption.IGNORE_CASE)
    private val AFTER_A = Regex("""\ba\s+([A-ZÁÉÍÓÚÑ0-9][^,;]*?)$STOP""")
    private val AFTER_DE = Regex("""\bde\s+([A-ZÁÉÍÓÚÑ0-9][^,;]*?)$STOP""")

    private val INVALID_START = Regex("""^(tu|su|la|el|los|las|cuenta|tarjeta|linea|colombia|\d)\b""", RegexOption.IGNORE_CASE)

    fun parse(body: String): ParsedTransaction? {
        val text = body.replace(Regex("\\s+"), " ").trim()
        val norm = normalize(text)

        // bancos mencionados, en orden de aparicion: el primero es el tuyo, el segundo (si hay) la otra parte
        val mentioned = BANKS
            .mapNotNull { (key, name) -> norm.indexOf(key).takeIf { it >= 0 }?.let { it to name } }
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
        val bank = mentioned.firstOrNull() ?: return null
        val otherBank = mentioned.getOrNull(1)

        if (REJECT_WORDS.any { norm.contains(it) }) return null

        val amount = AMOUNT_REGEX.find(text)?.groupValues?.get(1)?.let(::parseAmount) ?: return null

        // gana la palabra clave que aparece primero en el mensaje
        val hits = mutableListOf<Pair<Int, Kind>>()
        fun scan(words: List<String>, kind: Kind) =
            words.forEach { w -> norm.indexOf(w).takeIf { it >= 0 }?.let { hits += it to kind } }
        scan(OUT_WORDS, Kind.TRANSFER_OUT)
        scan(IN_TRANSFER_WORDS, Kind.TRANSFER_IN)
        scan(INCOME_WORDS, Kind.INGRESO)
        scan(EXPENSE_WORDS, Kind.GASTO)
        val kind = hits.minByOrNull { it.first }?.second ?: return null

        // cuentas con "*1234": la tuya y, en transferencias, la de la otra parte
        var ownLast4: String? = null
        var otherLast4: String? = null
        for (m in ACCOUNT_REGEX.findAll(text)) {
            val digits = m.groupValues[1]
            val before = text.substring(0, m.range.first).lowercase()
            if (OTHER_ACCOUNT_CONTEXT.containsMatchIn(before)) {
                if (otherLast4 == null) otherLast4 = digits
            } else if (ownLast4 == null) ownLast4 = digits
        }
        val account = if (ownLast4 != null) "$bank *$ownLast4" else bank

        if (kind == Kind.TRANSFER_OUT || kind == Kind.TRANSFER_IN) {
            val name = findName(text, if (kind == Kind.TRANSFER_OUT) AFTER_A else AFTER_DE)
            val bankPart = otherBank ?: if (otherLast4 != null) bank else null
            val accountPart = listOfNotNull(bankPart, otherLast4?.let { "*$it" }).joinToString(" ")
            val counterparty = listOfNotNull(name, accountPart.ifBlank { null })
                .joinToString(" · ").ifBlank { null }
            return ParsedTransaction(
                amount = amount,
                kind = kind,
                bank = bank,
                account = account,
                merchant = counterparty ?: "Sin identificar",
                identified = counterparty != null,
                counterparty = counterparty
            )
        }

        val merchant = findMerchant(text, kind == Kind.GASTO)
        return ParsedTransaction(
            amount = amount,
            kind = kind,
            bank = bank,
            account = account,
            merchant = merchant ?: "Sin identificar",
            identified = merchant != null
        )
    }

    private fun findMerchant(text: String, expense: Boolean): String? {
        val candidates = sequence {
            yieldAll(AFTER_EN.findAll(text))
            yieldAll((if (expense) AFTER_A else AFTER_DE).findAll(text))
        }
        for (m in candidates) {
            val c = m.groupValues[1].trim().trimEnd('.', ' ')
            if (c.length < 2 || !c.any { it.isLetter() }) continue
            if (INVALID_START.containsMatchIn(c)) continue
            return prettify(c)
        }
        return null
    }

    /** Nombre de la persona en una transferencia; descarta nombres de bancos. */
    private fun findName(text: String, regex: Regex): String? {
        for (m in regex.findAll(text)) {
            val c = m.groupValues[1].trim().trimEnd('.', ' ')
            if (c.length < 2 || !c.any { it.isLetter() }) continue
            if (INVALID_START.containsMatchIn(c)) continue
            val n = normalize(c)
            if (BANKS.any { n.startsWith(it.first) }) continue
            return prettify(c)
        }
        return null
    }

    private fun prettify(s: String): String =
        if (s == s.uppercase()) {
            s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        } else s

    /** "50.000" -> 50000.0 ; "1.250.000,50" -> 1250000.5 ; "1,250.50" -> 1250.5 */
    fun parseAmount(raw: String): Double? {
        var s = raw.trim().trimEnd('.', ',')
        if (s.isEmpty()) return null
        val dot = s.lastIndexOf('.')
        val comma = s.lastIndexOf(',')
        s = when {
            dot >= 0 && comma >= 0 ->
                if (comma > dot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            dot >= 0 ->
                if (Regex("""^\d{1,3}(\.\d{3})+$""").matches(s)) s.replace(".", "") else s
            comma >= 0 ->
                if (Regex("""^\d{1,3}(,\d{3})+$""").matches(s)) s.replace(",", "") else s.replace(',', '.')
            else -> s
        }
        return s.toDoubleOrNull()
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
}
