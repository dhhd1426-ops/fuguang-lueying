package com.fuguang.lueying

import org.junit.Test
import java.io.File

/** 生成几份样例 PPT / Word / 预览页，CI 在 check 分支上把它们传上来，好在电脑上打开核对。 */
class OfficeSampleTest {
    @Test
    fun samples() {
        val out = File("build/office-samples").apply { mkdirs() }
        val md = """
            ## 为什么要早起
            早起的人有**更多安静的时间**，也更容易*专注*。

            - 头脑清醒
              - 没人打扰
            - 能看日出

            1. 定闹钟
            2. 早点睡

            > 一日之计在于晨。

            | 时间 | 做什么 |
            |---|---|
            | 6:00 | 起床 |
            | 6:30 | 跑步 & 早饭 <ok> |

            ---
            ### 小结
            坚持 `21` 天。
        """.trimIndent()
        File(out, "sample.docx").writeBytes(Docx.build("早起计划", md))
        File(out, "doc.html").writeText(Preview.doc("早起计划", md))
        val sl = listOf(
            Slide("早起的力量", listOf("一份给自己的作息提案", "2026 年 10 月")),
            Slide("为什么早起", listOf("清晨的时间**不被打扰**", "  没有消息", "  没有会议", "身体节律更稳")),
            Slide("怎么开始", listOf("每天提前 15 分钟", "睡前不看手机 & 早点关灯")),
        )
        File(out, "sample.pptx").writeBytes(Pptx.build("早起的力量", sl))
        File(out, "slides.html").writeText(Preview.slides("早起的力量", sl))
        File(out, "page.html").writeText(Preview.page("<canvas id=c></canvas><script>1</script>"))
    }
}
