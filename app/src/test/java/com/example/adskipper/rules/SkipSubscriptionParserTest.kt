package com.example.adskipper.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkipSubscriptionParserTest {
    @Test
    fun `converts ids texts bounds activities and click positions`() {
        val yaml =
            """
            - appName: 示例
              packageName: example.app
              skipIds:
                - id: example.app:id/special_skip
                  activityName: example.app.SplashActivity
                  click: 1280,180
              skipTexts:
                - text: 点击跳过
                  length: 8
              skipBounds:
                - bound: 1200,160,1380,300
                  activityName: example.app.SplashActivity
            """.trimIndent()

        val rules = SkipSubscriptionParser.parse(yaml)

        assertEquals(3, rules.size)
        assertEquals(
            MatchKind.CUSTOM_VIEW_ID,
            RuleEngine
                .match(
                    NodeSnapshot(resourceId = "example.app:id/special_skip"),
                    "example.app",
                    "example.app.SplashActivity",
                    rules,
                )?.kind,
        )
        assertEquals(ClickAction(1280f, 180f), rules[0].clickAction)
        assertEquals(8, rules[1].maxTextLength)
        assertEquals(RuleBounds(1200, 160, 1380, 300), rules[2].bounds)
        assertNull(
            RuleEngine.match(
                NodeSnapshot(resourceId = "example.app:id/special_skip"),
                "example.app",
                "example.app.MainActivity",
                rules,
            ),
        )
    }

    @Test
    fun `rejects non list subscriptions`() {
        val error = runCatching { SkipSubscriptionParser.parse("version: 1") }.exceptionOrNull()
        assertEquals("SKIP 订阅根节点必须是应用列表", error?.message)
    }
}
