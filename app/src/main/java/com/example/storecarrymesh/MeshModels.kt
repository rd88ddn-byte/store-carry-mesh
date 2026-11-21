package com.example.storecarrymesh

import org.json.JSONObject
import java.util.UUID

data class MeshPacket(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val body: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val ttl: Int = 8,
    val hopCount: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("senderId", senderId)
        put("body", body)
        put("createdAtMs", createdAtMs)
        put("ttl", ttl)
        put("hopCount", hopCount)
    }.toString()

    fun forwardedCopy(): MeshPacket = copy(
        ttl = (ttl - 1).coerceAtLeast(0),
        hopCount = hopCount + 1,
    )

    companion object {
        fun fromJson(raw: String): MeshPacket? = try {
            val obj = JSONObject(raw)
            MeshPacket(
                id = obj.getString("id"),
                senderId = obj.getString("senderId"),
                body = obj.getString("body"),
                createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                ttl = obj.optInt("ttl", 8),
                hopCount = obj.optInt("hopCount", 0),
            )
        } catch (_: Exception) {
            null
        }
    }
}
