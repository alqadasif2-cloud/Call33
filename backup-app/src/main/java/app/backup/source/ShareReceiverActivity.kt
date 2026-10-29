package app.backup.source

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.backup.core.Net
import kotlinx.coroutines.launch

/** استقبال نص عبر «مشاركة» أو من قائمة تحديد النص المباشرة (PROCESS_TEXT) في أي تطبيق. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val text = extractSharedText(intent)
        lifecycleScope.launch {
            val res = ClipSaver.save(applicationContext, text, scheduleUpload = true)
            val msg = clipMessage(res, applicationContext)
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun extractSharedText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
    }
}

fun clipMessage(r: ClipResult, ctx: Context? = null): String = when (r) {
    ClipResult.SAVED -> {
        val online = ctx?.let { Net.isOnline(it) } ?: true
        if (online && !Prefs.paused.value) {
            "تم حفظ النص محليًا وبدأت مزامنته تلقائيًا مع السحابة"
        } else {
            "تم حفظ النص محليًا في قائمة الانتظار وسيُرفع تلقائيًا عند توفر الاتصال"
        }
    }
    ClipResult.DUPLICATE -> "هذا النص محفوظ مسبقًا ولن يتكرر"
    ClipResult.SENSITIVE -> "لم يُحفظ: النص يبدو كلمة مرور أو رمز تحقق أو رقم بطاقة"
    ClipResult.EMPTY -> "لا يوجد نص صالح لحفظه"
    ClipResult.TOO_LONG -> "النص أطول من الحد المسموح (100 ألف حرف)"
    ClipResult.DISABLED -> "نسخ «الحافظة» غير مفعّل حاليًا في إعدادات التطبيق"
}
