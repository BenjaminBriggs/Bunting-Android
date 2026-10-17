package com.bunting.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Routes `BuntingConfiguration.serializer()` through [ArtifactDecoder], so every
 * decode follows the v1 wire contract and throws [ArtifactRejectedException] only for
 * a bad header. Encoding writes the wire format back out; placeholders and
 * unrecognized conditions are written as `"unrecognized"` entries.
 */
internal object BuntingConfigurationSerializer : KSerializer<BuntingConfiguration> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.bunting.model.BuntingConfiguration")

    override fun deserialize(decoder: Decoder): BuntingConfiguration {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("BuntingConfiguration is only decodable from JSON")
        return ArtifactDecoder.decode(json.decodeJsonElement()).configuration
    }

    override fun serialize(encoder: Encoder, value: BuntingConfiguration) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("BuntingConfiguration is only encodable to JSON")
        json.encodeJsonElement(WireEncoding.configuration(value))
    }
}

/** The wire (config.json) rendering of decoded models. */
internal object WireEncoding {
    fun configuration(config: BuntingConfiguration): JsonObject = JsonObject(
        linkedMapOf(
            "schema_version" to JsonPrimitive(config.schemaVersion),
            "config_version" to JsonPrimitive(config.configVersion),
            "published_at" to JsonPrimitive(config.publishedAt),
            "app_identifier" to JsonPrimitive(config.appIdentifier),
            "flags" to JsonObject(config.flags.mapValues { (_, flag) -> flag(flag) }),
            "tests" to JsonObject(config.tests.mapValues { (_, test) -> test(test) }),
            "rollouts" to JsonObject(config.rollouts.mapValues { (_, rollout) -> rollout(rollout) }),
        )
    )

    fun value(value: FlagValue): JsonPrimitive = when (value) {
        is FlagValue.BoolValue -> JsonPrimitive(value.value)
        is FlagValue.IntValue -> JsonPrimitive(value.value)
        is FlagValue.DoubleValue -> JsonPrimitive(value.value)
        is FlagValue.StringValue -> JsonPrimitive(value.value)
        is FlagValue.DateValue -> JsonPrimitive(value.value.toString())
        is FlagValue.JsonValue -> JsonPrimitive(value.value)
    }

    private fun flag(flag: Flag): JsonObject {
        val fields = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(flag.type.wireName))
        flag.description?.let { fields["description"] = JsonPrimitive(it) }
        if (flag.deprecated) fields["deprecated"] = JsonPrimitive(true)
        for (environment in BuntingEnvironment.entries) {
            val config = flag.config(environment)
            fields[environment.wireName] = JsonObject(
                linkedMapOf(
                    "default" to value(config.defaultValue),
                    "variants" to JsonArray(config.variants.map { variant(it) }),
                )
            )
        }
        return JsonObject(fields)
    }

    private fun variant(variant: Variant): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive(variant.type.wireName),
            "order" to JsonPrimitive(variant.order),
        )
        if (variant.type == VariantType.UNRECOGNIZED) return JsonObject(fields)
        variant.value?.let { fields["value"] = value(it) }
        variant.values?.let { values -> fields["values"] = JsonObject(values.mapValues { (_, v) -> value(v) }) }
        variant.conditions?.let { fields["conditions"] = conditions(it) }
        variant.test?.let { fields["test"] = JsonPrimitive(it) }
        variant.rollout?.let { fields["rollout"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun conditions(conditions: List<Condition>): JsonArray = JsonArray(
        conditions.map { condition ->
            JsonObject(
                linkedMapOf(
                    "type" to JsonPrimitive(condition.type.wireName),
                    "operator" to JsonPrimitive(condition.operator.wireName),
                    "values" to JsonArray(condition.values.map { JsonPrimitive(it) }),
                )
            )
        }
    )

    private fun test(test: Test): JsonObject {
        val fields = linkedMapOf<String, JsonElement>("name" to JsonPrimitive(test.name))
        test.description?.let { fields["description"] = JsonPrimitive(it) }
        fields["type"] = JsonPrimitive(test.type)
        fields["salt"] = JsonPrimitive(test.salt)
        fields["conditions"] = conditions(test.conditions)
        test.groups?.let { groups ->
            fields["groups"] = JsonArray(
                groups.map { JsonObject(linkedMapOf("name" to JsonPrimitive(it.name), "percentage" to JsonPrimitive(it.percentage))) }
            )
        }
        return JsonObject(fields)
    }

    private fun rollout(rollout: Rollout): JsonObject {
        val fields = linkedMapOf<String, JsonElement>("name" to JsonPrimitive(rollout.name))
        rollout.description?.let { fields["description"] = JsonPrimitive(it) }
        fields["type"] = JsonPrimitive(rollout.type)
        fields["salt"] = JsonPrimitive(rollout.salt)
        fields["conditions"] = conditions(rollout.conditions)
        fields["percentage"] = JsonPrimitive(rollout.percentage)
        return JsonObject(fields)
    }
}
