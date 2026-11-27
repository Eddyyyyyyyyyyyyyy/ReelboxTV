package com.reelix.app.data.image

data class ArtworkBatchStatus(val running: Boolean = false, val processed: Int = 0, val total: Int = 0,
    val missing: Int = 0, val error: String? = null)
