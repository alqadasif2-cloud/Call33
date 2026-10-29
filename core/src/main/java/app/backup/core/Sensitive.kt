package app.backup.core

/** يمنع حفظ نصوص تبدو كلمات مرور أو أكواد تحقق أو أرقام بطاقات. */
object Sensitive {
    private val keywords = Regex(
        "(?i)(password|passcode|otp|verification code|one-time|رمز التحقق|كود التحقق|رمز التفعيل|كلمة المرور|كلمة السر|الرقم السري)"
    )

    fun looksSensitive(text: String): Boolean {
        val t = text.trim()
        if (keywords.containsMatchIn(t)) return true
        if (t.isNotEmpty() && t.all { it.isDigit() || it == ' ' || it == '-' }) {
            val digits = t.filter { it.isDigit() }
            if (digits.length in 4..8) return true
            if (digits.length in 13..19 && luhn(digits)) return true
        }
        return false
    }

    fun luhn(d: String): Boolean {
        var sum = 0
        var alt = false
        for (i in d.length - 1 downTo 0) {
            var n = Character.digit(d[i], 10)
            if (n < 0) return false
            if (alt) { n *= 2; if (n > 9) n -= 9 }
            sum += n
            alt = !alt
        }
        return sum % 10 == 0
    }
}
