@file:JvmName("AMQPSerializationScheme")

package net.corda.nodeapi.internal.serialization.amqp

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import net.corda.core.cordapp.Cordapp
import net.corda.core.internal.objectOrNewInstance
import net.corda.core.serialization.*
import net.corda.core.utilities.ByteSequence
import net.corda.nodeapi.internal.serialization.CordaSerializationMagic
import net.corda.nodeapi.internal.serialization.DefaultWhitelist
import net.corda.nodeapi.internal.serialization.kryo.MutableClassWhitelist
import net.corda.nodeapi.internal.serialization.SerializationScheme
import java.lang.reflect.Modifier
import java.security.PublicKey
import java.util.*
import java.util.concurrent.ConcurrentHashMap

val AMQP_ENABLED get() = SerializationDefaults.P2P_CONTEXT.preferredSerializationVersion == amqpMagic

fun SerializerFactory.addToWhitelist(vararg types: Class<*>) {
    require(types.toSet().size == types.size) {
        val duplicates = types.toMutableList()
        types.toSet().forEach { duplicates -= it }
        "Cannot add duplicate classes to the whitelist ($duplicates)."
    }
    for (type in types) {
        (this.whitelist as? MutableClassWhitelist)?.add(type)
    }
}

// Allow us to create a SerializerFactory with a different ClassCarpenter implementation.
interface SerializerFactoryFactory {
    fun make(context: SerializationContext): SerializerFactory
}

abstract class AbstractAMQPSerializationScheme(
    private val cordappCustomSerializers: Set<SerializationCustomSerializer<*,*>>,
    private val serializerFactoriesForContexts: MutableMap<Pair<ClassWhitelist, ClassLoader>, SerializerFactory>,
    val sff: SerializerFactoryFactory = createSerializerFactoryFactory()
) : SerializationScheme {
    constructor(cordapps: List<Cordapp>) : this(cordapps.customSerializers, ConcurrentHashMap())

    // TODO: This method of initialisation for the Whitelist and plugin serializers will have to change
    // when we have per-cordapp contexts and dynamic app reloading but for now it's the easiest way
    companion object {

        const val SCAN_SPEC_PROP_NAME = "amqp.custom.serialization.scanSpec"

        private val serializationWhitelists: List<SerializationWhitelist> by lazy {
            ServiceLoader.load(SerializationWhitelist::class.java, this::class.java.classLoader).toList() + DefaultWhitelist
        }

        private val customSerializers: List<SerializationCustomSerializer<*, *>> by lazy {

            val scanSpec: String? = System.getProperty(SCAN_SPEC_PROP_NAME)

            if (scanSpec == null) {
                emptyList()
            } else {
                FastClasspathScanner(scanSpec).addClassLoader(this::class.java.classLoader).scan()
                        .getNamesOfClassesImplementing(SerializationCustomSerializer::class.java)
                        .mapNotNull { this::class.java.classLoader.loadClass(it).asSubclass(SerializationCustomSerializer::class.java) }
                        .filterNot { Modifier.isAbstract(it.modifiers) }
                        .map { it.kotlin.objectOrNewInstance() }
            }
        }

        val List<Cordapp>.customSerializers get() = flatMap { it.serializationCustomSerializers }.toSet()
    }

    // Parameter "context" is unused directy but passed in by reflection. Removing it will cause failures.
    private fun registerCustomSerializers(context: SerializationContext, factory: SerializerFactory) {
        with(factory) {
            register(publicKeySerializer)
            register(net.corda.nodeapi.internal.serialization.amqp.custom.PrivateKeySerializer)
            register(net.corda.nodeapi.internal.serialization.amqp.custom.ThrowableSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.BigDecimalSerializer)
            register(net.corda.nodeapi.internal.serialization.amqp.custom.BigIntegerSerializer)
            register(net.corda.nodeapi.internal.serialization.amqp.custom.CurrencySerializer)
            register(net.corda.nodeapi.internal.serialization.amqp.custom.OpaqueBytesSubSequenceSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.InstantSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.DurationSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.LocalDateSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.LocalDateTimeSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.LocalTimeSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.ZonedDateTimeSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.ZoneIdSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.OffsetTimeSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.OffsetDateTimeSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.YearSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.YearMonthSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.MonthDaySerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.PeriodSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.ClassSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.X509CertificateSerializer)
            register(net.corda.nodeapi.internal.serialization.amqp.custom.X509CRLSerializer)
            register(net.corda.nodeapi.internal.serialization.amqp.custom.CertPathSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.StringBufferSerializer)
            register(net.corda.nodeapi.internal.serialization.amqp.custom.InputStreamSerializer)
            register(net.corda.nodeapi.internal.serialization.amqp.custom.BitSetSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.EnumSetSerializer(this))
            register(net.corda.nodeapi.internal.serialization.amqp.custom.ContractAttachmentSerializer(this))
            registerNonDeterministicSerializers(factory)
        }
        for (whitelistProvider in serializationWhitelists) {
            factory.addToWhitelist(*whitelistProvider.whitelist.toTypedArray())
        }

        // If we're passed in an external list we trust that, otherwise revert to looking at the scan of the
        // classpath to find custom serializers.
        if (cordappCustomSerializers.isEmpty()) {
            for (customSerializer in customSerializers) {
                factory.registerExternal(CorDappCustomSerializer(customSerializer, factory))
            }
        } else {
            cordappCustomSerializers.forEach { customSerializer ->
                factory.registerExternal(CorDappCustomSerializer(customSerializer, factory))
            }
        }
    }

    /*
     * Register the serializers which will be excluded from the DJVM.
     */
    private fun registerNonDeterministicSerializers(factory: SerializerFactory) {
        with(factory) {
            register(net.corda.nodeapi.internal.serialization.amqp.custom.SimpleStringSerializer)
        }
    }

    protected abstract fun rpcClientSerializerFactory(context: SerializationContext): SerializerFactory
    protected abstract fun rpcServerSerializerFactory(context: SerializationContext): SerializerFactory
    protected open val publicKeySerializer: CustomSerializer.Implements<PublicKey> = net.corda.nodeapi.internal.serialization.amqp.custom.PublicKeySerializer

    private fun getSerializerFactory(context: SerializationContext): SerializerFactory {
        return serializerFactoriesForContexts.computeIfAbsent(Pair(context.whitelist, context.deserializationClassLoader)) {
            when (context.useCase) {
                SerializationContext.UseCase.Checkpoint ->
                    throw IllegalStateException("AMQP should not be used for checkpoint serialization.")
                SerializationContext.UseCase.RPCClient ->
                    rpcClientSerializerFactory(context)
                SerializationContext.UseCase.RPCServer ->
                    rpcServerSerializerFactory(context)
                else -> sff.make(context)
            }.also {
                registerCustomSerializers(context, it)
            }
        }
    }

    override fun <T : Any> deserialize(byteSequence: ByteSequence, clazz: Class<T>, context: SerializationContext): T {
        val serializerFactory = getSerializerFactory(context)
        return DeserializationInput(serializerFactory).deserialize(byteSequence, clazz, context)
    }

    override fun <T : Any> serialize(obj: T, context: SerializationContext): SerializedBytes<T> {
        val serializerFactory = getSerializerFactory(context)

        return SerializationOutput(serializerFactory).serialize(obj, context)
    }

    protected fun canDeserializeVersion(magic: CordaSerializationMagic) = magic == amqpMagic
}

// TODO: This will eventually cover server RPC as well and move to node module, but for now this is not implemented
class AMQPServerSerializationScheme(
    cordappCustomSerializers: Set<SerializationCustomSerializer<*, *>>,
    serializerFactoriesForContexts: MutableMap<Pair<ClassWhitelist, ClassLoader>, SerializerFactory>
) : AbstractAMQPSerializationScheme(cordappCustomSerializers, serializerFactoriesForContexts) {
    constructor(cordapps: List<Cordapp>) : this(cordapps.customSerializers, ConcurrentHashMap())

    constructor() : this(emptySet(), ConcurrentHashMap())

    override fun rpcClientSerializerFactory(context: SerializationContext): SerializerFactory {
        throw UnsupportedOperationException()
    }

    override fun rpcServerSerializerFactory(context: SerializationContext): SerializerFactory {
        throw UnsupportedOperationException()
    }

    override fun canDeserializeVersion(magic: CordaSerializationMagic, target: SerializationContext.UseCase): Boolean {
        return canDeserializeVersion(magic) &&
                (target == SerializationContext.UseCase.P2P || target == SerializationContext.UseCase.Storage)
    }

}

// TODO: This will eventually cover client RPC as well and move to client module, but for now this is not implemented
class AMQPClientSerializationScheme(
    cordappCustomSerializers: Set<SerializationCustomSerializer<*,*>>,
    serializerFactoriesForContexts: MutableMap<Pair<ClassWhitelist, ClassLoader>, SerializerFactory>
) : AbstractAMQPSerializationScheme(cordappCustomSerializers, serializerFactoriesForContexts) {
    constructor(cordapps: List<Cordapp>) : this(cordapps.customSerializers, ConcurrentHashMap())

    constructor() : this(emptySet(), ConcurrentHashMap())

    override fun rpcClientSerializerFactory(context: SerializationContext): SerializerFactory {
        throw UnsupportedOperationException()
    }

    override fun rpcServerSerializerFactory(context: SerializationContext): SerializerFactory {
        throw UnsupportedOperationException()
    }

    override fun canDeserializeVersion(magic: CordaSerializationMagic, target: SerializationContext.UseCase): Boolean {
        return canDeserializeVersion(magic) &&
                (target == SerializationContext.UseCase.P2P || target == SerializationContext.UseCase.Storage)
    }

}

