package hackuna.matata.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import hackuna.matata.MainActivity
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Foreground Service, который держит процесс живым, пока идёт обработка.
 * Саму работу выполняет [TranscriptionController]; сервис только показывает прогресс в уведомлении
 * и останавливается, когда контроллер вернулся в Idle.
 * Тип: mediaProcessing на Android 15+, ниже — dataSync.
 */
class TranscriptionService : LifecycleService() {
    private val controller: TranscriptionController by inject()
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannel()
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0 // до Android 10 тип не указывается
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build("Подготовка…", 0), type)
        lifecycleScope.launch {
            controller.state.collect { s ->
                when (s) {
                    is TranscriptionController.State.Running ->
                        nm.notify(NOTIFICATION_ID, build("${s.title.ifEmpty { "Обработка" }}: ${stageName(s.stage.name)}", (s.fraction * 100).toInt()))
                    TranscriptionController.State.Idle -> {
                        ServiceCompat.stopForeground(this@TranscriptionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Нейро-стенографист")
            .setContentText(text)
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private fun ensureChannel() {
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Обработка записей", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun stageName(stage: String) = when (stage) {
        "DECODE" -> "декодирование"
        "PREPROCESS" -> "предобработка"
        "VAD" -> "поиск речи"
        "DIARIZATION" -> "диаризация"
        "ASR" -> "распознавание"
        "MERGE" -> "склейка реплик"
        "LLM" -> "LLM"
        else -> "сохранение"
    }

    private companion object {
        const val CHANNEL_ID = "processing"
        const val NOTIFICATION_ID = 1001
    }
}
