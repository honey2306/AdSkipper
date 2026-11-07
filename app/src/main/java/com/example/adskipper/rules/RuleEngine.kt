package com.example.adskipper.rules

import kotlin.math.abs

/** A small, Android-free representation of an accessibility node, used by unit tests. */
data class NodeSnapshot(
    val text: String? = null,
    val contentDescription: String? = null,
    val combinedChildText: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val visibleToUser: Boolean = true,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 100,
    val bottom: Int = 100,
)

enum class RuleRegion {
    ANY,
    TOP_RIGHT,
    TOP_LEFT,
    TOP,
    BOTTOM,
}

data class RuleBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

data class ClickAction(
    val x: Float,
    val y: Float,
    val normalized: Boolean = false,
) {
    fun resolve(
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Float, Float> = if (normalized) x * screenWidth to y * screenHeight else x to y
}

data class AppRule(
    val packageName: String,
    val activityName: String? = null,
    val textPatterns: List<Regex> = emptyList(),
    val maxTextLength: Int? = null,
    val viewIds: List<String> = emptyList(),
    val viewIdPatterns: List<Regex> = emptyList(),
    val excludeTextPatterns: List<Regex> = emptyList(),
    val excludeViewIds: List<String> = emptyList(),
    val region: RuleRegion = RuleRegion.ANY,
    val bounds: RuleBounds? = null,
    val clickAction: ClickAction? = null,
)

enum class MatchKind(
    val priority: Int,
) {
    CUSTOM_BOUNDS(150),
    CUSTOM_VIEW_ID(140),
    CUSTOM_TEXT(130),
    VIEW_ID(120),
    TEXT(110),
    CONTENT_DESCRIPTION(100),
}

data class RuleMatch(
    val kind: MatchKind,
    val clickAction: ClickAction? = null,
)

object RuleEngine {
    private val whitespace = Regex("\\s+")

    private val textPatterns =
        listOf(
            Regex("^(点击)?(跳过(广告)?(按钮)?|关闭广告(按钮)?)$"),
            Regex("^跳过 ?[0-9]+ ?(秒|s)?$", RegexOption.IGNORE_CASE),
            Regex("^[0-9]+ ?(秒|s)? ?跳过$", RegexOption.IGNORE_CASE),
            Regex("^skip( ads?| now)?$", RegexOption.IGNORE_CASE),
        )

    private val exactResourceNames =
        setOf(
            "skip",
            "ad_skip",
            "skip_ad",
            "btn_skip",
            "skip_btn",
            "tv_skip",
            "txt_skip",
            "skip_button",
            "splash_skip",
            "splash_skip_btn",
            "btn_splash_skip",
            "tt_splash_skip_btn",
            "tt_skip_ad",
            "tv_ad_skip",
            "skip_ad_button",
            "jump_btn",
            "mjumpbtn",
            "btn_jump",
            "ad_close",
            "close_ad",
            "btn_close_ad",
            "splash_ad_close",
            "splash_close_btn",
        )

    fun match(
        node: NodeSnapshot,
        packageName: String,
        activityName: String?,
        customRules: List<AppRule> = emptyList(),
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
    ): RuleMatch? {
        if (!node.visibleToUser || node.className == "android.widget.EditText") return null
        if (textCandidates(node).any { highRiskText.matches(normalize(it)) }) return null

        customRules.forEach { rule ->
            if (rule.packageName != "*" && rule.packageName != packageName) return@forEach
            if (rule.activityName != null && rule.activityName != activityName) return@forEach
            if (!regionMatches(node, rule.region, screenWidth, screenHeight)) return@forEach
            if (!boundsMatch(node, rule.bounds)) return@forEach

            val resourceName = resourceName(node.resourceId)
            if (rule.excludeViewIds.any { it == node.resourceId || it.equals(resourceName, true) }) {
                return@forEach
            }
            if (rule.excludeTextPatterns.any { pattern ->
                    textCandidates(node).any { candidate -> pattern.matches(normalize(candidate)) }
                }
            ) {
                return@forEach
            }

            if (rule.bounds != null) {
                return RuleMatch(MatchKind.CUSTOM_BOUNDS, rule.clickAction)
            }

            val idMatched =
                rule.viewIds.any { it == node.resourceId || it.equals(resourceName, true) } ||
                    rule.viewIdPatterns.any { pattern ->
                        node.resourceId?.let(pattern::matches) == true
                    }
            if (idMatched) return RuleMatch(MatchKind.CUSTOM_VIEW_ID, rule.clickAction)

            val textMatched =
                rule.textPatterns.isNotEmpty() &&
                    textCandidates(node).any { candidate ->
                        val normalized = normalize(candidate)
                        (rule.maxTextLength == null || normalized.length <= rule.maxTextLength) &&
                            rule.textPatterns.any { pattern -> pattern.matches(normalized) }
                    }
            if (textMatched) return RuleMatch(MatchKind.CUSTOM_TEXT, rule.clickAction)
        }

        resourceName(node.resourceId)?.let { name ->
            val sdkResource =
                node.resourceId?.substringBefore("/")?.let { owner ->
                    owner.contains("pangle") || owner.contains("bytedance") ||
                        owner.contains("gdt") || owner.contains("kwad") ||
                        owner.contains("sigmob") || owner.contains("anythink") ||
                        owner.contains("mintegral") || owner.contains("tanx")
                } == true
            val adResource = sdkResource || name.contains("ad") || name.contains("splash")
            val skipToken = adResource && name.split('_').any { it == "skip" || it == "jump" }
            if ((name in exactResourceNames || skipToken) && !name.contains("help")) {
                return RuleMatch(MatchKind.VIEW_ID)
            }
        }

        node.text?.let { text ->
            if (matchesGenericText(text)) return RuleMatch(MatchKind.TEXT)
        }
        node.contentDescription?.let { description ->
            if (matchesGenericText(description)) return RuleMatch(MatchKind.CONTENT_DESCRIPTION)
        }
        node.combinedChildText?.let { combined ->
            if (node.clickable && matchesGenericText(combined)) return RuleMatch(MatchKind.TEXT)
        }

        return null
    }

    fun normalize(value: String): String =
        value
            .replace('過', '过')
            .replace('廣', '广')
            .replace('點', '点')
            .replace('擊', '击')
            .replace(Regex("[|｜·•:：]"), " ")
            .trim()
            .replace(whitespace, " ")

    private fun matchesGenericText(value: String): Boolean {
        val normalized = normalize(value)
        if (normalized.length > 16) return false
        return textPatterns.any { it.matches(normalized) }
    }

    private fun textCandidates(node: NodeSnapshot): List<String> = listOfNotNull(node.text, node.contentDescription, node.combinedChildText)

    private val highRiskText =
        Regex(".*(会员|视频|片头|片尾|教程|剧情|新手|引导|帮助|取消|支付|付款|下载|安装|青少年).*")

    private fun resourceName(resourceId: String?): String? = resourceId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    private fun boundsMatch(
        node: NodeSnapshot,
        bounds: RuleBounds?,
    ): Boolean {
        bounds ?: return true
        return abs(node.left - bounds.left) <= 2 &&
            abs(node.top - bounds.top) <= 2 &&
            abs(node.right - bounds.right) <= 2 &&
            abs(node.bottom - bounds.bottom) <= 2
    }

    private fun regionMatches(
        node: NodeSnapshot,
        region: RuleRegion,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        val centerX = (node.left + node.right) / 2f
        val centerY = (node.top + node.bottom) / 2f
        return when (region) {
            RuleRegion.ANY -> true
            RuleRegion.TOP_RIGHT -> centerX >= screenWidth * 0.55f && centerY <= screenHeight * 0.35f
            RuleRegion.TOP_LEFT -> centerX <= screenWidth * 0.45f && centerY <= screenHeight * 0.35f
            RuleRegion.TOP -> centerY <= screenHeight * 0.35f
            RuleRegion.BOTTOM -> centerY >= screenHeight * 0.65f
        }
    }
}
