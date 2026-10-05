package com.scott.shiftledger

import androidx.sqlite.db.SupportSQLiteDatabase

object SupportMessages {
    const val ORDER_TOKEN = "{order}"
    const val SUNRUN_TITLE = "Sunrun · driver score exclusion"
    const val SUNRUN_BODY = "Requesting to have this Sunrun trash route, order #{order}, excluded from my driver score. All pickups have the same time window."
    fun isStarter(template: MessageTemplate) = template.id == "starter-sunrun-score" && template.title == SUNRUN_TITLE && template.body == SUNRUN_BODY
    fun seed(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO message_templates (id,title,body,updatedAt) VALUES (?,?,?,?)",
            arrayOf<Any>("starter-sunrun-score", SUNRUN_TITLE, SUNRUN_BODY, System.currentTimeMillis()))
    }
    fun render(body: String, orderNumber: String): String = body.replace(ORDER_TOKEN, orderNumber.trim().removePrefix("#"))
    fun canCopy(body: String, orderNumber: String): Boolean = body.isNotBlank() &&
        (!body.contains(ORDER_TOKEN) || orderNumber.trim().removePrefix("#").let { it.isNotBlank() && it.all(Char::isDigit) })
    fun validate(title: String, body: String) {
        require(title.isNotBlank() && title.length <= 100) { "Enter a title up to 100 characters." }
        require(body.isNotBlank() && body.length <= 10_000) { "Enter a message up to 10,000 characters." }
    }
}
