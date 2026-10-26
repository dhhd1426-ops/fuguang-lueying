package com.fuguang.lueying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 自动沉浸：停手 3 分钟不碰屏幕，界面慢慢退开、背景变清楚、状态栏收起；轻点任意处回来（这一下不点到下面的按钮）。
 * 她在回答、输入框有字、键盘开着、侧栏 / 卡片 / 作品 / 画板开着时不进。
 */
object Zen {
    const val IDLE_MS = 180_000L
    /** 进之前最后几秒：界面先淡一点，提醒一下。 */
    const val WARN_MS = 2_000L
    /** 最后一次碰屏幕的时间。 */
    var touchedAt by mutableLongStateOf(System.currentTimeMillis())
    /** 聊天页那边有东西开着（侧栏、作品、画板、看图、思考、选模型、长按菜单、输入框有字），不进沉浸。 */
    var held by mutableStateOf(false)
    fun touch() { touchedAt = System.currentTimeMillis() }
}
