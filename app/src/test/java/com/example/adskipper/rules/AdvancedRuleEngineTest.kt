package com.example.adskipper.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdvancedRuleEngineTest {
    @Test
    fun `region limits candidate position`() {
        val rule = AppRule("example.app", viewIds = listOf("close"), region = RuleRegion.TOP_RIGHT)
        val topRight = NodeSnapshot(resourceId = "example.app:id/close", left = 900, right = 1000, top = 100, bottom = 200)
        val bottom = topRight.copy(top = 1800, bottom = 1900)

        assertEquals(MatchKind.CUSTOM_VIEW_ID, RuleEngine.match(topRight, "example.app", null, listOf(rule))?.kind)
        assertNull(RuleEngine.match(bottom, "example.app", null, listOf(rule)))
    }

    @Test
    fun `exclusions prevent otherwise valid matches`() {
        val rule =
            AppRule(
                "example.app",
                textPatterns = listOf(Regex(".*跳过.*")),
                excludeTextPatterns = listOf(Regex(".*会员.*")),
            )
        val node = NodeSnapshot(text = "开通会员跳过广告")
        assertNull(RuleEngine.match(node, "example.app", null, listOf(rule)))
    }

    @Test
    fun `regex id and exact bounds are supported`() {
        val idRule = AppRule("example.app", viewIdPatterns = listOf(Regex(".*/circle_skip_[0-9]+")))
        val boundsRule = AppRule("example.app", bounds = RuleBounds(100, 200, 300, 400))

        assertEquals(
            MatchKind.CUSTOM_VIEW_ID,
            RuleEngine.match(NodeSnapshot(resourceId = "example.app:id/circle_skip_2"), "example.app", null, listOf(idRule))?.kind,
        )
        assertEquals(
            MatchKind.CUSTOM_BOUNDS,
            RuleEngine.match(NodeSnapshot(left = 102, top = 199, right = 301, bottom = 400), "example.app", null, listOf(boundsRule))?.kind,
        )
    }

    @Test
    fun `normalized click action resolves against current screen`() {
        val action = ClickAction(0.9f, 0.08f, normalized = true)
        assertEquals(990f to 192f, action.resolve(1100, 2400))
    }

    @Test
    fun `split child text can form one skip label`() {
        val node = NodeSnapshot(combinedChildText = "3 秒 | 跳过", clickable = true)
        assertEquals(MatchKind.TEXT, RuleEngine.match(node, "example.app", null)?.kind)
    }

    @Test
    fun `traditional and click prefix are supported`() {
        assertEquals(MatchKind.TEXT, RuleEngine.match(NodeSnapshot(text = "點擊跳過廣告"), "example.app", null)?.kind)
        assertEquals(MatchKind.TEXT, RuleEngine.match(NodeSnapshot(text = "点击跳过广告"), "example.app", null)?.kind)
    }

    @Test
    fun `high risk action is rejected even with ad resource id`() {
        val node = NodeSnapshot(text = "跳过会员广告", resourceId = "example.app:id/ad_skip_button")
        assertNull(RuleEngine.match(node, "example.app", null))
    }
}
