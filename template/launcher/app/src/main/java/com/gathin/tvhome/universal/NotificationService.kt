package com.gathin.tvhome.universal

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

data class NotifItem(val title: String, val text: String)

object NotificationState {
    val items = MutableStateFlow<List<NotifItem>>(emptyList())
}

/** Читает уведомления системы. Доступ выдаётся вручную (см. подсказку в окне уведомлений). */
class NotificationService : NotificationListenerService() {
    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val list = try {
            activeNotifications.orEmpty()
                .filter { !it.isOngoing && it.packageName != packageName }
                .map {
                    val e = it.notification.extras
                    NotifItem(
                        title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                            .ifBlank { it.packageName },
                        text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                    )
                }
        } catch (_: Exception) {
            emptyList()
        }
        NotificationState.items.value = list
    }
}
