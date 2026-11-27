package com.reelix.app.player

enum class DecoderMode {
    AUTO, HARDWARE, SOFTWARE;

    fun accepts(hardware: Boolean, software: Boolean): Boolean = when (this) {
        AUTO -> true
        HARDWARE -> hardware
        SOFTWARE -> software
    }

    companion object {
        fun fromStored(value: String?): DecoderMode = entries.firstOrNull { it.name == value } ?: AUTO
    }
}
