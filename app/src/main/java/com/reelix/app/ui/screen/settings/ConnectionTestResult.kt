package com.reelix.app.ui.screen.settings

/** 连接测试结果；保留独立文件，避免依赖已删除的旧 Compose 设置页。 */
data class ConnectionTestResult(val success: Boolean, val message: String)
