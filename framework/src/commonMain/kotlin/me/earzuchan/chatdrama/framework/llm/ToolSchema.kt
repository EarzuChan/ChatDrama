package me.earzuchan.chatdrama.framework.llm

import kotlinx.serialization.json.*

internal fun ToolDefinition.inputSchema() = toolObjectSchema(args)

private fun toolObjectSchema(args: List<ToolArg>) = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { args.forEach { put(it.name, it.schema()) } })
    args.filter { it.required }.map { it.name }.takeIf { it.isNotEmpty() }?.let { required -> put("required", buildJsonArray { required.forEach { add(it) } }) }
}

private fun ToolArg.schema(): JsonObject {
    val schema = type.schema()
    if (description == null) return schema
    return buildJsonObject {
        schema.forEach { (key, value) -> put(key, value) }
        put("description", description)
    }
}

private fun ToolArgType.schema(): JsonObject = when (this) {
    ToolArgType.StringType -> buildJsonObject { put("type", "string") }
    ToolArgType.IntegerType -> buildJsonObject { put("type", "integer") }
    ToolArgType.NumberType -> buildJsonObject { put("type", "number") }
    ToolArgType.BooleanType -> buildJsonObject { put("type", "boolean") }
    is ToolArgType.EnumType -> buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach { add(it) } })
    }

    is ToolArgType.ArrayType -> buildJsonObject {
        put("type", "array")
        put("items", item.schema())
    }

    is ToolArgType.ObjectType -> toolObjectSchema(args)
}
