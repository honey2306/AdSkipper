package com.example.adskipper.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleEngineTest {
    private val packageName = "example.target"

    @Test
    fun `matches common Chinese and English skip labels`() {
        val labels = listOf("跳过", "跳过广告", "跳过 3", "3 秒 跳过", "Skip", "skip ad")

        labels.forEach { label ->
            val match = RuleEngine.match(NodeSnapshot(text = label), packageName, null)
            assertEquals("Should match: $label", MatchKind.TEXT, match?.kind)
        }
    }

    @Test
    fun `matches content description`() {
        val match =
            RuleEngine.match(
                NodeSnapshot(contentDescription = "关闭广告按钮"),
                packageName,
                null,
            )
        assertEquals(MatchKind.CONTENT_DESCRIPTION, match?.kind)
    }

    @Test
    fun `rejects body text and unrelated buttons`() {
        val labels =
            listOf(
                "这是一篇介绍如何跳过广告的长文章",
                "跳过广告教程",
                "开通会员可跳过广告",
                "确定",
                "关闭",
            )

        labels.forEach { label ->
            assertNull("Should not match: $label", RuleEngine.match(NodeSnapshot(text = label), packageName, null))
        }
    }

    @Test
    fun `matches known resource id without text`() {
        val match =
            RuleEngine.match(
                NodeSnapshot(resourceId = "example.target:id/tt_splash_skip_btn"),
                packageName,
                null,
            )
        assertEquals(MatchKind.VIEW_ID, match?.kind)
    }

    @Test
    fun `rejects invisible nodes and edit texts`() {
        assertNull(RuleEngine.match(NodeSnapshot(text = "跳过", visibleToUser = false), packageName, null))
        assertNull(
            RuleEngine.match(
                NodeSnapshot(text = "跳过", className = "android.widget.EditText"),
                packageName,
                null,
            ),
        )
    }

    @Test
    fun `custom rule respects package and activity`() {
        val rule =
            AppRule(
                packageName = packageName,
                activityName = "example.target.SplashActivity",
                viewIds = listOf("example.target:id/circular_close"),
            )
        val node = NodeSnapshot(resourceId = "example.target:id/circular_close")

        assertEquals(
            MatchKind.CUSTOM_VIEW_ID,
            RuleEngine.match(node, packageName, "example.target.SplashActivity", listOf(rule))?.kind,
        )
        assertNull(RuleEngine.match(node, packageName, "example.target.MainActivity", listOf(rule)))
        assertNull(RuleEngine.match(node, "other.app", "example.target.SplashActivity", listOf(rule)))
    }

    @Test
    fun `custom text rule has priority over generic matching`() {
        val rule = AppRule(packageName = packageName, textPatterns = listOf(Regex("忽略本次推广")))
        val match =
            RuleEngine.match(
                NodeSnapshot(text = "忽略本次推广"),
                packageName,
                null,
                listOf(rule),
            )
        assertEquals(MatchKind.CUSTOM_TEXT, match?.kind)
    }
}
