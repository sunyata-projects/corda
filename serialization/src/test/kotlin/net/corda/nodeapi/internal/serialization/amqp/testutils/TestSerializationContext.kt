package net.corda.nodeapi.internal.serialization.amqp.testutils

import net.corda.core.serialization.SerializationContext
import net.corda.nodeapi.internal.serialization.kryo.AllWhitelist
import net.corda.nodeapi.internal.serialization.SerializationContextImpl
import net.corda.nodeapi.internal.serialization.amqp.amqpMagic

val serializationProperties: MutableMap<Any, Any> = mutableMapOf()

val testSerializationContext = SerializationContextImpl(
        preferredSerializationVersion = amqpMagic,
        deserializationClassLoader = ClassLoader.getSystemClassLoader(),
        whitelist = AllWhitelist,
        properties = serializationProperties,
        objectReferencesEnabled = false,
        useCase = SerializationContext.UseCase.Testing,
        encoding = null)