package com.example.adskipper.rules

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Converts the public SKIP v3 YAML schema into our executable rule model. */
object SkipSubscriptionParser {
    fun parse(text: String): List<AppRule> {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val apps = yaml.load<Any>(text) as? List<*> ?: error("SKIP 订阅根节点必须是应用列表")
        return apps
            .mapNotNull { app ->
                val appMap = app as? Map<*, *> ?: return@mapNotNull null
                val packageName =
                    appMap["packageName"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                val rules = mutableListOf<AppRule>()

                appMap.entries("skipIds").forEach { item ->
                    val id = item.string("id") ?: return@forEach
                    rules.add(
                        AppRule(
                            packageName = packageName,
                            activityName = item.string("activityName"),
                            viewIds = listOf(id),
                            clickAction = item.clickAction(),
                        ),
                    )
                }

                appMap.entries("skipTexts").forEach { item ->
                    val text = item.string("text") ?: return@forEach
                    rules.add(
                        AppRule(
                            packageName = packageName,
                            activityName = item.string("activityName"),
                            textPatterns = listOf(Regex(".*${Regex.escape(text)}.*")),
                            maxTextLength = item.string("length")?.toIntOrNull(),
                            clickAction = item.clickAction(),
                        ),
                    )
                }

                appMap.entries("skipBounds").forEach { item ->
                    val values = item.string("bound").intList(4) ?: return@forEach
                    rules.add(
                        AppRule(
                            packageName = packageName,
                            activityName = item.string("activityName"),
                            bounds = RuleBounds(values[0], values[1], values[2], values[3]),
                            clickAction = item.clickAction(),
                        ),
                    )
                }
                rules
            }.flatten()
            .distinct()
    }

    private fun Map<*, *>.entries(key: String): List<Map<*, *>> = (this[key] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()

    private fun Map<*, *>.string(key: String): String? = this[key]?.toString()?.trim()?.takeIf { it.isNotBlank() }

    private fun Map<*, *>.clickAction(): ClickAction? {
        val values = string("click").floatList(2) ?: return null
        return ClickAction(values[0], values[1])
    }

    private fun String?.intList(count: Int): List<Int>? = floatList(count)?.map { it.toInt() }

    private fun String?.floatList(count: Int): List<Float>? {
        val values = this?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }.orEmpty()
        return values.takeIf { it.size == count }
    }
}
