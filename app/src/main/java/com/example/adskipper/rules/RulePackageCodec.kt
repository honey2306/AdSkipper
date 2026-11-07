package com.example.adskipper.rules

import org.json.JSONArray
import org.json.JSONObject

object RulePackageCodec {
    fun parse(json: String): List<AppRule> {
        val root = JSONObject(json)
        val items = root.optJSONArray("rules") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    AppRule(
                        packageName = item.getString("packageName"),
                        activityName = item.optString("activityName").ifBlank { null },
                        textPatterns = item.optJSONArray("textPatterns").toRegexList(),
                        maxTextLength = if (item.has("maxTextLength")) item.getInt("maxTextLength") else null,
                        viewIds = item.optJSONArray("viewIds").toStringList(),
                        viewIdPatterns = item.optJSONArray("viewIdPatterns").toRegexList(),
                        excludeTextPatterns = item.optJSONArray("excludeTextPatterns").toRegexList(),
                        excludeViewIds = item.optJSONArray("excludeViewIds").toStringList(),
                        region =
                            runCatching {
                                RuleRegion.valueOf(item.optString("region", RuleRegion.ANY.name))
                            }.getOrDefault(RuleRegion.ANY),
                        bounds =
                            item.optJSONObject("bounds")?.let { bounds ->
                                RuleBounds(
                                    left = bounds.getInt("left"),
                                    top = bounds.getInt("top"),
                                    right = bounds.getInt("right"),
                                    bottom = bounds.getInt("bottom"),
                                )
                            },
                        clickAction =
                            item.optJSONObject("clickAction")?.let { click ->
                                ClickAction(
                                    x = click.getDouble("x").toFloat(),
                                    y = click.getDouble("y").toFloat(),
                                    normalized = click.optBoolean("normalized"),
                                )
                            },
                    ),
                )
            }
        }
    }

    fun encode(rules: List<AppRule>): String {
        val items = JSONArray()
        rules.forEach { rule ->
            val item =
                JSONObject()
                    .put("packageName", rule.packageName)
                    .put("activityName", rule.activityName ?: "")
                    .put("textPatterns", rule.textPatterns.toPatternJson())
                    .put("viewIds", JSONArray(rule.viewIds))
                    .put("viewIdPatterns", rule.viewIdPatterns.toPatternJson())
                    .put("excludeTextPatterns", rule.excludeTextPatterns.toPatternJson())
                    .put("excludeViewIds", JSONArray(rule.excludeViewIds))
                    .put("region", rule.region.name)
            rule.maxTextLength?.let { item.put("maxTextLength", it) }
            rule.bounds?.let { bounds ->
                item.put(
                    "bounds",
                    JSONObject()
                        .put("left", bounds.left)
                        .put("top", bounds.top)
                        .put("right", bounds.right)
                        .put("bottom", bounds.bottom),
                )
            }
            rule.clickAction?.let { click ->
                item.put(
                    "clickAction",
                    JSONObject()
                        .put("x", click.x.toDouble())
                        .put("y", click.y.toDouble())
                        .put("normalized", click.normalized),
                )
            }
            items.put(item)
        }
        return JSONObject().put("version", 2).put("rules", items).toString(2)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) add(getString(index))
        }
    }

    private fun JSONArray?.toRegexList(): List<Regex> = toStringList().mapNotNull { pattern -> runCatching { Regex(pattern) }.getOrNull() }

    private fun List<Regex>.toPatternJson(): JSONArray {
        val json = JSONArray()
        forEach { json.put(it.pattern) }
        return json
    }
}
