package dev.aigw.core.provider.codebuddy

import dev.aigw.core.provider.ThinkTag

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.io.InputStream

/**
 * 工具调用形状归一。
 *
 * WorkBuddy 的模型只接受特定形状的工具 schema：**不做这些变换时，国际版会
 * 直接回 400「Invalid request parameters」（业务码 11133）**——DeepSeek 系模型对
 * 下列每一条都敏感，Kimi/GPT/Gemini/GLM 系对其中一部分敏感。
 *
 * 规则逐条来自官方渠道的实测记录（`magpie` 插件的 workbuddy README，
 * MIT，yetone，https://github.com/yetone/magpie）：
 * ① 根级无 `type`／`type` 为空／根级是联合（`anyOf`/`oneOf`/`allOf`）的，一律当对象，
 *    并把联合各分支的字段合并；
 * ② `pattern` 里的 `\0` 要写成 `\u0000`；
 * ③ 元组式 `items`（数组）要合成「任一满足」的单个 schema；
 * ④ 没有 `type` 的 `enum` 取其取值的类型（或该类型与 `"null"`）；取值含多种类型时，
 *    换成「每种类型一个带类型的 enum」的 `anyOf`（任意深度）；空 `enum` 直接去掉；
 * ⑤ schema 位置上的 `true`/`false` 换成 `{}`/`{"not":{}}`（`additionalProperties` 不动）；
 *    `required` 不是数组、`type` 不是 JSON Schema 认得的名字，一律去掉；`"String"` 写成 `"string"`。
 *
 * 只递归已知的 schema 容器，其余关键字（`additionalProperties`、`default`、
 * `description`、`minimum`…）原样保留——它们不是 schema，误改会引入新问题。
 */

private val JSON_SCHEMA_TYPES =
    setOf("string", "number", "integer", "object", "array", "boolean", "null")

/** 值本身是 schema 的容器：`键 -> 值`（每个值都是 schema）。 */
private val SCHEMA_MAP_KEYS = listOf("properties", "patternProperties", "definitions", "\$defs")

/** 值本身是 schema 列表的容器。 */
private val SCHEMA_LIST_KEYS = listOf("anyOf", "oneOf", "allOf")

/** 归一化工具 schema 的根。 */
internal fun normalizeToolParameters(parameters: JsonElement): JsonElement =
    if (parameters.isJsonObject) normalizeRoot(parameters.asJsonObject) else parameters

/**
 * 工具名的合法字符是字母、数字、`_`、`-`；其余（`.`/`:`/`/`/空格/CJK）一律换成 `_`。
 * 模型回传调用时要把名字换回原样（见 [ToolNameRestoringStream]）。
 */
internal fun sanitizeToolName(name: String): String =
    name.map { ch -> if (isLegalToolNameChar(ch)) ch else '_' }.joinToString("")

private fun isLegalToolNameChar(ch: Char): Boolean =
    ch == '_' || ch == '-' || (ch.code < 128 && ch.isLetterOrDigit())

private fun normalizeRoot(src: JsonObject): JsonObject {
    val branches = ArrayList<JsonObject>().apply {
        add(src)
        SCHEMA_LIST_KEYS.forEach { key ->
            src.arrayOrNull(key)?.forEach { if (it.isJsonObject) add(it.asJsonObject) }
        }
    }
    val declared = normalizedTypeElement(src)
    // 已经是合法对象、且根级没有联合：按对象正常递归
    if (declared?.isJsonPrimitive == true && declared.asString == "object" && branches.size == 1) {
        return normalizeSchemaObject(src)
    }
    if (declared?.isJsonPrimitive == true && declared.asString != "object") {
        return normalizeSchemaObject(src)
    }
    // 无 type / type 为空 / 根级是联合：当对象，并合并各分支的字段
    val out = JsonObject()
    out.addProperty("type", "object")
    val properties = JsonObject()
    val required = LinkedHashSet<String>()
    for (branch in branches) {
        branch.objOrNull("properties")?.entrySet()?.forEach { (name, schema) ->
            properties.add(name, normalizeSchema(schema))
        }
        branch.arrayOrNull("required")?.forEach { item ->
            if (item.isJsonPrimitive && item.asJsonPrimitive.isString) required.add(item.asString)
        }
    }
    if (properties.size() > 0) out.add("properties", properties)
    if (required.isNotEmpty()) {
        out.add("required", JsonArray().apply { required.forEach { add(JsonPrimitive(it)) } })
    }
    // 其它关键字原样保留（它们不是 schema，且丢掉会改变语义）
    for ((key, value) in src.entrySet()) {
        if (key == "properties" || key == "required" || key in SCHEMA_LIST_KEYS) continue
        out.add(key, value)
    }
    return out
}

private fun normalizeSchemaObject(src: JsonObject): JsonObject {
    val out = JsonObject()
    normalizedTypeElement(src)?.let { out.add("type", it) }

    src.arrayOrNull("required")?.let { required ->
        val names = required.filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            .map { it.asString }
        if (names.isNotEmpty()) {
            out.add("required", JsonArray().apply { names.forEach { add(JsonPrimitive(it)) } })
        }
    }

    (src.get("pattern") as? JsonPrimitive)?.takeIf { it.isString }?.let { pattern ->
        // JSON 里写不出裸的 \0；上游引擎认的是转义后的 \u0000
        out.addProperty("pattern", pattern.asString.replace("\\0", "\\u0000"))
    }

    src.get("items")?.let { items -> out.add("items", normalizeItems(items)) }

    SCHEMA_MAP_KEYS.forEach { key ->
        src.objOrNull(key)?.let { children ->
            out.add(
                key,
                JsonObject().apply { children.entrySet().forEach { (name, s) -> add(name, normalizeSchema(s)) } },
            )
        }
    }
    SCHEMA_LIST_KEYS.forEach { key ->
        src.arrayOrNull(key)?.let { list ->
            out.add(key, JsonArray().apply { list.forEach { add(normalizeSchema(it)) } })
        }
    }

    normalizeEnum(src, out)

    // 其余关键字原样保留（不递归）：它们不是 schema
    val handled = buildSet {
        add("type"); add("required"); add("pattern"); add("items"); add("enum")
        addAll(SCHEMA_MAP_KEYS); addAll(SCHEMA_LIST_KEYS)
    }
    for ((key, value) in src.entrySet()) {
        if (key !in handled) out.add(key, value)
    }
    return out
}

/** 元组式 `items`（数组）→「任一满足」的单个 schema。 */
private fun normalizeItems(items: JsonElement): JsonElement = if (items.isJsonArray) {
    JsonObject().apply {
        add("anyOf", JsonArray().apply { items.asJsonArray.forEach { add(normalizeSchema(it)) } })
    }
} else {
    normalizeSchema(items)
}

/** schema 位置上的布尔：`true` 任何都满足、`false` 任何都不满足。 */
private fun normalizeSchema(node: JsonElement): JsonElement {
    if (node.isJsonPrimitive && node.asJsonPrimitive.isBoolean) {
        return if (node.asBoolean) JsonObject() else JsonObject().apply { add("not", JsonObject()) }
    }
    if (!node.isJsonObject) return node
    return normalizeSchemaObject(node.asJsonObject)
}

/**
 * 无 `type` 的 `enum`：取值只有一种类型就补上该类型；
 * 多种类型则换成「每种类型一个带类型的 enum」的 `anyOf`；空 `enum` 去掉。
 */
private fun normalizeEnum(src: JsonObject, out: JsonObject) {
    val values = src.arrayOrNull("enum") ?: return
    if (values.size() == 0) return
    if (normalizedTypeElement(src) != null) {
        out.add("enum", values)
        return
    }
    val groups = LinkedHashMap<String, JsonArray>()
    for (value in values) groups.getOrPut(jsonTypeOf(value)) { JsonArray() }.add(value)
    val nonNull = groups.keys.filter { it != "null" }
    when {
        nonNull.isEmpty() -> out.add("enum", values)
        nonNull.size == 1 && groups.size == 1 -> {
            out.addProperty("type", nonNull[0])
            out.add("enum", values)
        }
        nonNull.size == 1 -> {
            out.add(
                "type",
                JsonArray().apply {
                    add(JsonPrimitive(nonNull[0]))
                    add(JsonPrimitive("null"))
                },
            )
            out.add("enum", values)
        }
        else -> out.add(
            "anyOf",
            JsonArray().apply {
                groups.forEach { (type, typedValues) ->
                    add(
                        JsonObject().apply {
                            addProperty("type", type)
                            add("enum", typedValues)
                        },
                    )
                }
            },
        )
    }
}

/**
 * 归一 `type`：`"String"` → `"string"`；不是 JSON Schema 认得的名字就去掉；
 * `type` 是数组时逐项归一（全被去掉则整项去掉）。
 */
private fun normalizedTypeElement(src: JsonObject): JsonElement? {
    val raw = src.get("type") ?: return null
    if (raw.isJsonPrimitive && raw.asJsonPrimitive.isString) {
        return JsonPrimitive(raw.asString.lowercase()).takeIf { it.asString in JSON_SCHEMA_TYPES }
    }
    if (raw.isJsonArray) {
        val kept = raw.asJsonArray
            .filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            .map { it.asString.lowercase() }
            .filter { it in JSON_SCHEMA_TYPES }
        if (kept.isEmpty()) return null
        return JsonArray().apply { kept.forEach { add(JsonPrimitive(it)) } }
    }
    return null
}

private fun jsonTypeOf(value: JsonElement): String = when {
    value.isJsonNull -> "null"
    value.isJsonArray -> "array"
    value.isJsonObject -> "object"
    value.isJsonPrimitive -> {
        val primitive = value.asJsonPrimitive
        when {
            primitive.isBoolean -> "boolean"
            primitive.isNumber -> "number"
            else -> "string"
        }
    }
    else -> "string"
}

/**
 * 把响应里的工具名换回客户端原本的名字。
 *
 * 请求侧把非法字符换成 `_`（见 [sanitizeToolName]），模型回传的调用名是改写后的，
 * 必须还原，否则客户端按名字找不到自己的工具。
 */
internal fun restoreToolNames(json: String, restore: Map<String, String>): String {
    if (restore.isEmpty()) return json
    val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return json
    return if (rewriteToolCallNames(obj, restore)) obj.toString() else json
}

private fun rewriteToolCallNames(obj: JsonObject, restore: Map<String, String>): Boolean {
    var changed = false
    obj.arrayOrNull("choices")?.forEach { element ->
        val choice = element as? JsonObject ?: return@forEach
        for (key in listOf("message", "delta")) {
            val container = choice.objOrNull(key) ?: continue
            container.arrayOrNull("tool_calls")?.forEach { callElement ->
                val call = callElement as? JsonObject ?: return@forEach
                val function = call.objOrNull("function") ?: return@forEach
                val name = function.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
                restore[name]?.let {
                    function.addProperty("name", it)
                    changed = true
                }
            }
        }
    }
    return changed
}

/**
 * 逐行还原工具名的流包装。
 *
 * 与网关的读法一致：按行处理（SSE 每行是 `data: <json>`），只改 `data:` 行里的工具名，
 * 行数与分隔结构保持不变，因此不改变 SSE 帧边界。
 */
internal class ToolNameRestoringStream(
    source: InputStream,
    private val restore: Map<String, String>,
    private val thinkTag: ThinkTag? = null,
) : InputStream() {
    private val reader = source.bufferedReader(Charsets.UTF_8)
    private var buffer = ByteArray(0)
    private var offset = 0
    private var eof = false

    override fun read(): Int {
        if (!fill()) return -1
        return buffer[offset++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val count = minOf(len, buffer.size - offset)
        System.arraycopy(buffer, offset, b, off, count)
        offset += count
        return count
    }

    override fun close() {
        reader.close()
    }

    private fun fill(): Boolean {
        while (offset >= buffer.size) {
            if (eof) return false
            val line = reader.readLine()
            if (line == null) {
                eof = true
                return false
            }
            buffer = transform(line).toByteArray(Charsets.UTF_8)
            offset = 0
        }
        return true
    }

    private fun transform(line: String): String {
        if (!line.startsWith("data:")) return line + "\n"
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return line + "\n"
        val restored = restoreToolNames(payload, restore)
        // 上游 reasoning parser 偶发把整段 CoT 当 content 下发（usage.reasoning_tokens=0，
        // 正文首尾带 <think:SUF> 控制token）：在 delta.content 上做跨片拆分，
        // 思考进 reasoning_content，别把控制 token 当正文发出去。
        val cleaned = if (thinkTag != null) splitThinkInFrame(restored, thinkTag!!) else restored
        return if (cleaned == payload) line + "\n" else "data: $cleaned\n"
    }
}

/**
 * 把一帧 OpenAI chunk 里 `choices[].delta.content` 的内联 `<think:SUF>` 摘出来。
 *
 * 思考片段并进同帧的 `reasoning_content`（没有就新建），正文留在 content。
 * 其它字段原样保留；帧里没有 content 时不做任何事。
 */
internal fun splitThinkInFrame(frame: String, tag: ThinkTag): String {
    val obj = runCatching { JsonParser.parseString(frame).asJsonObject }.getOrNull() ?: return frame
    val choices = obj.arrayOrNull("choices") ?: return frame
    var changed = false
    for (item in choices) {
        val choice = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
        val delta = choice.objOrNull("delta") ?: continue
        val content = delta.stringOrNull("content") ?: continue
        if (content.isEmpty()) continue
        val (think, body) = tag.feed(content)
        if (think.isEmpty() && body == content) continue
        changed = true
        if (body.isEmpty() && think.isNotEmpty()) {
            delta.remove("content")
        } else {
            delta.addProperty("content", body)
        }
        if (think.isNotEmpty()) {
            val prev = delta.stringOrNull("reasoning_content").orEmpty()
            delta.addProperty("reasoning_content", prev + think)
        }
    }
    if (!changed) return frame
    return obj.toString()
}
