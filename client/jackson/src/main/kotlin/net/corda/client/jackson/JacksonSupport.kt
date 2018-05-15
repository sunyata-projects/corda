package net.corda.client.jackson

import com.fasterxml.jackson.annotation.*
import com.fasterxml.jackson.core.*
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.util.JSONPObject
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.convertValue
import net.corda.client.jackson.internal.jsonObject
import net.corda.client.jackson.internal.readValueAs
import net.corda.core.CordaInternal
import net.corda.core.CordaOID
import net.corda.core.DoNotImplement
import net.corda.core.contracts.*
import net.corda.core.crypto.*
import net.corda.core.crypto.TransactionSignature
import net.corda.core.identity.*
import net.corda.core.internal.CertRole
import net.corda.core.internal.DigitalSignatureWithCert
import net.corda.core.internal.VisibleForTesting
import net.corda.core.internal.uncheckedCast
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.NodeInfo
import net.corda.core.node.services.IdentityService
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.transactions.*
import net.corda.core.utilities.*
import org.bouncycastle.asn1.x509.KeyPurposeId
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.security.PublicKey
import java.security.cert.CertPath
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*
import javax.security.auth.x500.X500Principal

/**
 * Utilities and serialisers for working with JSON representations of basic types. This adds Jackson support for
 * the java.time API, some core types, and Kotlin data classes.
 *
 * Note that Jackson can also be used to serialise/deserialise other formats such as Yaml and XML.
 */
@Suppress("DEPRECATION")
object JacksonSupport {
    // If you change this API please update the docs in the docsite (json.rst)

    @DoNotImplement
    interface PartyObjectMapper {
        fun wellKnownPartyFromX500Name(name: CordaX500Name): Party?
        fun partyFromKey(owningKey: PublicKey): Party?
        fun partiesFromName(query: String): Set<Party>
        fun nodeInfoFromParty(party: AbstractParty): NodeInfo?
    }

    @Deprecated("This is an internal class, do not use", replaceWith = ReplaceWith("JacksonSupport.createDefaultMapper"))
    class RpcObjectMapper(val rpc: CordaRPCOps,
                          factory: JsonFactory,
                          val fuzzyIdentityMatch: Boolean) : PartyObjectMapper, ObjectMapper(factory) {
        override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? = rpc.wellKnownPartyFromX500Name(name)
        override fun partyFromKey(owningKey: PublicKey): Party? = rpc.partyFromKey(owningKey)
        override fun partiesFromName(query: String) = rpc.partiesFromName(query, fuzzyIdentityMatch)
        override fun nodeInfoFromParty(party: AbstractParty): NodeInfo? = rpc.nodeInfoFromParty(party)
    }

    @Deprecated("This is an internal class, do not use")
    class IdentityObjectMapper(val identityService: IdentityService,
                               factory: JsonFactory,
                               val fuzzyIdentityMatch: Boolean) : PartyObjectMapper, ObjectMapper(factory) {
        override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? = identityService.wellKnownPartyFromX500Name(name)
        override fun partyFromKey(owningKey: PublicKey): Party? = identityService.partyFromKey(owningKey)
        override fun partiesFromName(query: String) = identityService.partiesFromName(query, fuzzyIdentityMatch)
        override fun nodeInfoFromParty(party: AbstractParty): NodeInfo? = null
    }

    @Deprecated("This is an internal class, do not use", replaceWith = ReplaceWith("JacksonSupport.createNonRpcMapper"))
    class NoPartyObjectMapper(factory: JsonFactory) : PartyObjectMapper, ObjectMapper(factory) {
        override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? = null
        override fun partyFromKey(owningKey: PublicKey): Party? = null
        override fun partiesFromName(query: String): Set<Party> = emptySet()
        override fun nodeInfoFromParty(party: AbstractParty): NodeInfo? = null
    }

    val cordaModule: Module by lazy {
        SimpleModule("core").apply {
            addDeserializer(ComponentGroup::class.java, ComponentGroupDeserializer())
            setMixInAnnotation(BigDecimal::class.java, BigDecimalMixin::class.java)
            setMixInAnnotation(X500Principal::class.java, X500PrincipalMixin::class.java)
            setMixInAnnotation(X509Certificate::class.java, X509CertificateMixin::class.java)
            setMixInAnnotation(PartyAndCertificate::class.java, PartyAndCertificateSerializerMixin::class.java)
            setMixInAnnotation(NetworkHostAndPort::class.java, NetworkHostAndPortMixin::class.java)
            setMixInAnnotation(CordaX500Name::class.java, CordaX500NameMixin::class.java)
            setMixInAnnotation(Amount::class.java, AmountMixin::class.java)
            setMixInAnnotation(AbstractParty::class.java, AbstractPartyMixin::class.java)
            setMixInAnnotation(AnonymousParty::class.java, AnonymousPartyMixin::class.java)
            setMixInAnnotation(Party::class.java, PartyMixin::class.java)
            setMixInAnnotation(PublicKey::class.java, PublicKeyMixin::class.java)
            setMixInAnnotation(ByteSequence::class.java, ByteSequenceMixin::class.java)
            setMixInAnnotation(SecureHash.SHA256::class.java, SecureHashSHA256Mixin::class.java)
            setMixInAnnotation(SerializedBytes::class.java, SerializedBytesMixin::class.java)
            setMixInAnnotation(DigitalSignature.WithKey::class.java, DigitalSignatureWithKeyMixin::class.java)
            setMixInAnnotation(DigitalSignatureWithCert::class.java, DigitalSignatureWithCertMixin::class.java)
            setMixInAnnotation(TransactionSignature::class.java, TransactionSignatureMixin::class.java)
            setMixInAnnotation(SignedTransaction::class.java, SignedTransactionMixin2::class.java)
            setMixInAnnotation(WireTransaction::class.java, WireTransactionMixin2::class.java)
            setMixInAnnotation(TransactionState::class.java, TransactionStateMixin::class.java)
            setMixInAnnotation(Command::class.java, CommandMixin::class.java)
            setMixInAnnotation(CertPath::class.java, CertPathMixin::class.java)
            setMixInAnnotation(NodeInfo::class.java, NodeInfoMixin::class.java)
        }
    }

    /**
     * Creates a Jackson ObjectMapper that uses RPC to deserialise parties from string names.
     *
     * If [fuzzyIdentityMatch] is false, fields mapped to [Party] objects must be in X.500 name form and precisely
     * match an identity known from the network map. If true, the name is matched more leniently but if the match
     * is ambiguous a [JsonParseException] is thrown.
     */
    @JvmStatic
    @JvmOverloads
    fun createDefaultMapper(rpc: CordaRPCOps,
                            factory: JsonFactory = JsonFactory(),
                            fuzzyIdentityMatch: Boolean = false): ObjectMapper {
        return configureMapper(RpcObjectMapper(rpc, factory, fuzzyIdentityMatch))
    }

    /** For testing or situations where deserialising parties is not required */
    @JvmStatic
    @JvmOverloads
    fun createNonRpcMapper(factory: JsonFactory = JsonFactory()): ObjectMapper = configureMapper(NoPartyObjectMapper(factory))

    /**
     * Creates a Jackson ObjectMapper that uses an [IdentityService] directly inside the node to deserialise parties from string names.
     *
     * If [fuzzyIdentityMatch] is false, fields mapped to [Party] objects must be in X.500 name form and precisely
     * match an identity known from the network map. If true, the name is matched more leniently but if the match
     * is ambiguous a [JsonParseException] is thrown.
     */
    @Deprecated("This is an internal method, do not use")
    @JvmStatic
    @JvmOverloads
    fun createInMemoryMapper(identityService: IdentityService,
                             factory: JsonFactory = JsonFactory(),
                             fuzzyIdentityMatch: Boolean = false): ObjectMapper {
        return configureMapper(IdentityObjectMapper(identityService, factory, fuzzyIdentityMatch))
    }

    @CordaInternal
    @VisibleForTesting
    internal fun createPartyObjectMapper(partyObjectMapper: PartyObjectMapper, factory: JsonFactory = JsonFactory()): ObjectMapper {
        val mapper = object : ObjectMapper(factory), PartyObjectMapper by partyObjectMapper {}
        return configureMapper(mapper)
    }

    private fun configureMapper(mapper: ObjectMapper): ObjectMapper {
        return mapper.apply {
            enable(SerializationFeature.INDENT_OUTPUT)
            enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

            registerModule(JavaTimeModule().apply {
                addSerializer(Date::class.java, DateSerializer)
            })
            registerModule(cordaModule)
            registerModule(KotlinModule())
        }
    }

    /**
     * For sub-types of [ByteSequence] which have additional properties. The byte content is put into the "bytes" property.
     */
    @JacksonAnnotationsInside
    @JsonIgnoreProperties("offset", "size")
    @JsonSerialize
    @JsonDeserialize
    private annotation class ByteSequenceWithBytesProperty

    @JacksonAnnotationsInside
    @JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer::class)
    private annotation class ToStringSerialize

    @ToStringSerialize
    @JsonDeserialize(using = NumberDeserializers.BigDecimalDeserializer::class)
    private interface BigDecimalMixin

    private object DateSerializer : JsonSerializer<Date>() {
        override fun serialize(value: Date, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeObject(value.toInstant())
        }
    }

    @ToStringSerialize
    @JsonDeserialize(using = NetworkHostAndPortDeserializer::class)
    private interface NetworkHostAndPortMixin

    private class NetworkHostAndPortDeserializer : JsonDeserializer<NetworkHostAndPort>() {
        override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): NetworkHostAndPort {
            return NetworkHostAndPort.parse(parser.text)
        }
    }

    @JsonSerialize(using = PartyAndCertificateSerializer::class)
    // TODO Add deserialization which follows the same lookup logic as Party
    private interface PartyAndCertificateSerializerMixin

    private class PartyAndCertificateSerializer : JsonSerializer<PartyAndCertificate>() {
        override fun serialize(value: PartyAndCertificate, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.jsonObject {
                writeObjectField("name", value.name)
                writeObjectField("owningKey", value.owningKey)
                // TODO Add configurable option to output the certPath
            }
        }
    }

    @ByteSequenceWithBytesProperty
    private interface DigitalSignatureWithKeyMixin

    @ByteSequenceWithBytesProperty
    private interface DigitalSignatureWithCertMixin

    @JsonSerialize(using = SignedTransactionSerializer::class)
    @JsonDeserialize(using = SignedTransactionDeserializer::class)
    private interface SignedTransactionMixin2

    private class SignedTransactionSerializer : JsonSerializer<SignedTransaction>() {
        override fun serialize(value: SignedTransaction, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeObject(SignedTxWrapper(value.coreTransaction, value.sigs))
        }
    }

    private class SignedTransactionDeserializer : JsonDeserializer<SignedTransaction>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): SignedTransaction {
            val wrapper = parser.readValueAs<SignedTxWrapper>()
            return SignedTransaction(wrapper.core, wrapper.signatures)
        }
    }

    private data class SignedTxWrapper(
            @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
            val core: CoreTransaction,
            val signatures: List<TransactionSignature>
    )

    @JsonIgnoreProperties(
//            "componentGroups",
            "requiredSigningKeys",
            "inputs",
            "outputs",
            "commands",
            "attachments",
            "notary",
            "timeWindow",
            "id",
            "availableComponentGroups",
            "merkleTree",
            "outputStates",
            "groupHashes\$core_main",
            "groupsMerkleRoots\$core_main",
            "availableComponentNonces\$core_main",
            "availableComponentHashes\$core_main"
    )
    private interface WireTransactionMixin2

    private class ComponentGroupDeserializer : JsonDeserializer<ComponentGroup>() {
        override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): ComponentGroup {
            val mapper = parser.codec as ObjectMapper
            val tree = parser.readValueAsTree<ObjectNode>()
            val groupIndex = tree["groupIndex"].intValue()
            val components = tree["components"].elements().asSequence().map { mapper.convertValue<SerializedBytes<Any>>(it) }.toList()
            return ComponentGroup(groupIndex, components)
        }
    }

    @Suppress("unused")
    private interface TransactionStateMixin {
        @get:JsonTypeInfo(use = JsonTypeInfo.Id.CLASS) val data: Any
        @get:JsonTypeInfo(use = JsonTypeInfo.Id.CLASS) val constraint: Any
    }

    @Suppress("unused")
    private interface CommandMixin {
        @get:JsonTypeInfo(use = JsonTypeInfo.Id.CLASS) val value: Any
    }

    @ByteSequenceWithBytesProperty
    private interface TransactionSignatureMixin

    @JsonSerialize(using = SerializedBytesSerializer::class)
    @JsonDeserialize(using = SerializedBytesDeserializer::class)
    private class SerializedBytesMixin

    private class SerializedBytesSerializer : JsonSerializer<SerializedBytes<*>>() {
        override fun serialize(value: SerializedBytes<*>, gen: JsonGenerator, serializers: SerializerProvider) {
            val deserialized = value.deserialize<Any>()
            gen.jsonObject {
                writeStringField("class", deserialized.javaClass.name)
                writeObjectField("deserialized", deserialized)
            }
        }
    }

    private class SerializedBytesDeserializer : JsonDeserializer<SerializedBytes<*>>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): SerializedBytes<Any> {
            return if (parser.currentToken == JsonToken.START_OBJECT) {
                val mapper = parser.codec as ObjectMapper
                val json = parser.readValueAsTree<ObjectNode>()
                val clazz = context.findClass(json["class"].textValue())
                val pojo = mapper.convertValue(json["deserialized"], clazz)
                pojo.serialize()
            } else {
                SerializedBytes(parser.binaryValue)
            }
        }
    }

    @ToStringSerialize
    private interface X500PrincipalMixin

    @JsonSerialize(using = X509CertificateSerializer::class)
    @JsonDeserialize(using = X509CertificateDeserializer::class)
    private interface X509CertificateMixin

    private object X509CertificateSerializer : JsonSerializer<X509Certificate>() {
        val keyUsages = arrayOf(
                "digitalSignature",
                "nonRepudiation",
                "keyEncipherment",
                "dataEncipherment",
                "keyAgreement",
                "keyCertSign",
                "cRLSign",
                "encipherOnly",
                "decipherOnly"
        )

        val keyPurposeIds = KeyPurposeId::class.java
                .fields
                .filter { Modifier.isStatic(it.modifiers) && it.type == KeyPurposeId::class.java }
                .associateBy({ (it.get(null) as KeyPurposeId).id }, { it.name })

        val knownExtensions = setOf("2.5.29.15", "2.5.29.37", "2.5.29.19", "2.5.29.17", "2.5.29.18", CordaOID.X509_EXTENSION_CORDA_ROLE)

        override fun serialize(value: X509Certificate, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.jsonObject {
                writeNumberField("version", value.version)
                writeObjectField("serialNumber", value.serialNumber)
                writeObjectField("subject", value.subjectX500Principal)
                writeObjectField("publicKey", value.publicKey)
                writeObjectField("issuer", value.issuerX500Principal)
                writeObjectField("notBefore", value.notBefore)
                writeObjectField("notAfter", value.notAfter)
                writeObjectField("issuerUniqueID", value.issuerUniqueID)
                writeObjectField("subjectUniqueID", value.subjectUniqueID)
                writeObjectField("keyUsage", value.keyUsage?.asList()?.mapIndexedNotNull { i, flag -> if (flag) keyUsages[i] else null })
                writeObjectField("extendedKeyUsage", value.extendedKeyUsage.map { keyPurposeIds.getOrDefault(it, it) })
                jsonObject("basicConstraints") {
                    writeBooleanField("isCA", value.basicConstraints != -1)
                    writeObjectField("pathLength", value.basicConstraints.let { if (it != Int.MAX_VALUE) it else null })
                }
                writeObjectField("subjectAlternativeNames", value.subjectAlternativeNames)
                writeObjectField("issuerAlternativeNames", value.issuerAlternativeNames)
                writeObjectField("cordaCertRole", CertRole.extract(value))
                writeObjectField("otherCriticalExtensions", value.criticalExtensionOIDs - knownExtensions)
                writeObjectField("otherNonCriticalExtensions", value.nonCriticalExtensionOIDs - knownExtensions)
                writeBinaryField("encoded", value.encoded)
            }
        }
    }

    private class X509CertificateDeserializer : JsonDeserializer<X509Certificate>() {
        private val certFactory = CertificateFactory.getInstance("X.509")
        override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): X509Certificate {
            val encoded = parser.readValueAsTree<ObjectNode>()["encoded"]
            return certFactory.generateCertificate(encoded.binaryValue().inputStream()) as X509Certificate
        }
    }

    @JsonIgnoreProperties("encodings", "encoded")
    @JsonDeserialize(using = CertPathDeserializer::class)
    private interface CertPathMixin

    private class CertPathDeserializer : JsonDeserializer<CertPath>() {
        private val certFactory = CertificateFactory.getInstance("X.509")
        override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): CertPath {
            val mapper = parser.codec as ObjectMapper
            val json = parser.readValueAsTree<ObjectNode>()
            require(json["type"].textValue() == "X.509") { "Only X.509 cert paths are supported" }
            val certificates = json["certificates"].elements().asSequence().map { mapper.convertValue<X509Certificate>(it) }.toList()
            return certFactory.generateCertPath(certificates)
        }
    }

    @JsonDeserialize(`as` = Party::class)
    private interface AbstractPartyMixin

    @JsonSerialize(using = AnonymousPartySerializer::class)
    @JsonDeserialize(using = AnonymousPartyDeserializer::class)
    private interface AnonymousPartyMixin

    @Deprecated("This is an internal class, do not use")
    object AnonymousPartySerializer : JsonSerializer<AnonymousParty>() {
        override fun serialize(value: AnonymousParty, generator: JsonGenerator, provider: SerializerProvider) {
            generator.writeObject(value.owningKey)
        }
    }

    @Deprecated("This is an internal class, do not use")
    object AnonymousPartyDeserializer : JsonDeserializer<AnonymousParty>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): AnonymousParty {
            return AnonymousParty(parser.readValueAs(PublicKey::class.java))
        }
    }

    @JsonSerialize()
    private interface PartyMixin

    @Deprecated("This is an internal class, do not use")
    object PartySerializer : JsonSerializer<Party>() {
        override fun serialize(value: Party, generator: JsonGenerator, provider: SerializerProvider) {
            // TODO Add configurable option to output this as an object which includes the owningKey
            generator.writeObject(value.name)
        }
    }

    @Deprecated("This is an internal class, do not use")
    object PartyDeserializer : JsonDeserializer<Party>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): Party {
            val mapper = parser.codec as PartyObjectMapper
            // The comma character is invalid in Base58, and required as a separator for X.500 names. As Corda
            // X.500 names all involve at least three attributes (organisation, locality, country), they must
            // include a comma. As such we can use it as a distinguisher between the two types.
            return if ("," in parser.text) {
                val principal = CordaX500Name.parse(parser.text)
                mapper.wellKnownPartyFromX500Name(principal) ?: throw JsonParseException(parser, "Could not find a Party with name $principal")
            } else {
                val nameMatches = mapper.partiesFromName(parser.text)
                when {
                    nameMatches.isEmpty() -> {
                        val publicKey = parser.readValueAs<PublicKey>()
                        mapper.partyFromKey(publicKey)
                                ?: throw JsonParseException(parser, "Could not find a Party with key ${publicKey.toStringShort()}")
                    }
                    nameMatches.size == 1 -> nameMatches.first()
                    else -> throw JsonParseException(parser, "Ambiguous name match '${parser.text}': could be any of " +
                            nameMatches.map { it.name }.joinToString(" ... or ... "))
                }
            }
        }
    }

    @ToStringSerialize
    @JsonDeserialize(using = CordaX500NameDeserializer::class)
    private class CordaX500NameMixin

    @Deprecated("This is an internal class, do not use")
    object CordaX500NameDeserializer : JsonDeserializer<CordaX500Name>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): CordaX500Name {
            return try {
                CordaX500Name.parse(parser.text)
            } catch (e: IllegalArgumentException) {
                throw JsonParseException(parser, "Invalid Corda X.500 name ${parser.text}: ${e.message}", e)
            }
        }
    }

    @JsonIgnoreProperties("legalIdentities")  // This is already covered by legalIdentitiesAndCerts
    @JsonDeserialize(using = NodeInfoDeserializer::class)
    private interface NodeInfoMixin

    @Deprecated("This is an internal class, do not use")
    object NodeInfoDeserializer : JsonDeserializer<NodeInfo>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): NodeInfo {
            val mapper = parser.codec as PartyObjectMapper
            val party = parser.readValueAs<AbstractParty>()
            return mapper.nodeInfoFromParty(party) ?: throw JsonParseException(parser, "Cannot find node with $party")
        }
    }

    @ToStringSerialize
    @JsonDeserialize(using = SecureHashDeserializer::class)
    private interface SecureHashSHA256Mixin

    @Deprecated("This is an internal class, do not use")
    class SecureHashDeserializer<T : SecureHash> : JsonDeserializer<T>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): T {
            try {
                return uncheckedCast(SecureHash.parse(parser.text))
            } catch (e: Exception) {
                throw JsonParseException(parser, "Invalid hash ${parser.text}: ${e.message}")
            }
        }
    }

    @JsonSerialize(using = PublicKeySerializer::class)
    @JsonDeserialize(using = PublicKeyDeserializer::class)
    private interface PublicKeyMixin

    @Deprecated("This is an internal class, do not use")
    object PublicKeySerializer : JsonSerializer<PublicKey>() {
        override fun serialize(value: PublicKey, generator: JsonGenerator, provider: SerializerProvider) {
            generator.writeString(value.toBase58String())
        }
    }

    @Deprecated("This is an internal class, do not use")
    object PublicKeyDeserializer : JsonDeserializer<PublicKey>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): PublicKey {
            return try {
                parsePublicKeyBase58(parser.text)
            } catch (e: Exception) {
                throw JsonParseException(parser, "Invalid public key ${parser.text}: ${e.message}")
            }
        }
    }

    @ToStringSerialize
    @JsonDeserialize(using = AmountDeserializer::class)
    private interface AmountMixin

    @Deprecated("This is an internal class, do not use")
    object AmountDeserializer : JsonDeserializer<Amount<*>>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): Amount<*> {
            return if (parser.currentToken == JsonToken.VALUE_STRING) {
                Amount.parseCurrency(parser.text)
            } else {
                try {
                    val tree = parser.readValueAsTree<ObjectNode>()
                    val quantity = tree["quantity"].apply { require(canConvertToLong()) }
                    val token = tree["token"]
                    // Attempt parsing as a currency token. TODO: This needs thought about how to extend to other token types.
                    val currency = (parser.codec as ObjectMapper).convertValue<Currency>(token)
                    Amount(quantity.longValue(), currency)
                } catch (e: Exception) {
                    throw JsonParseException(parser, "Invalid amount", e)
                }
            }
        }
    }

    @JsonSerialize(using = ByteSequenceSerializer::class)
    @JsonDeserialize(using = OpaqueBytesDeserializer::class)
    private interface ByteSequenceMixin

    private class ByteSequenceSerializer : JsonSerializer<ByteSequence>() {
        override fun serialize(value: ByteSequence, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeBinary(value.bytes, value.offset, value.size)
        }
    }

    @Deprecated("This is an internal class, do not use")
    object OpaqueBytesDeserializer : JsonDeserializer<OpaqueBytes>() {
        override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): OpaqueBytes {
            return OpaqueBytes(parser.binaryValue)
        }
    }


    //
    // Everything below this point is no longer used but can't be deleted as they leaked into the public API
    //

    @Deprecated("No longer used as jackson already has a toString serializer",
            replaceWith = ReplaceWith("com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance"))
    object ToStringSerializer : JsonSerializer<Any>() {
        override fun serialize(obj: Any, generator: JsonGenerator, provider: SerializerProvider) {
            generator.writeString(obj.toString())
        }
    }

    @Deprecated("This is an internal class, do not use")
    object CordaX500NameSerializer : JsonSerializer<CordaX500Name>() {
        override fun serialize(obj: CordaX500Name, generator: JsonGenerator, provider: SerializerProvider) {
            generator.writeString(obj.toString())
        }
    }

    @Deprecated("This is an internal class, do not use")
    object NodeInfoSerializer : JsonSerializer<NodeInfo>() {
        override fun serialize(value: NodeInfo, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(Base58.encode(value.serialize().bytes))
        }
    }

    @Deprecated("This is an internal class, do not use")
    object SecureHashSerializer : JsonSerializer<SecureHash>() {
        override fun serialize(obj: SecureHash, generator: JsonGenerator, provider: SerializerProvider) {
            generator.writeString(obj.toString())
        }
    }

    @Deprecated("This is an internal class, do not use")
    object AmountSerializer : JsonSerializer<Amount<*>>() {
        override fun serialize(value: Amount<*>, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(value.toString())
        }
    }

    @Deprecated("This is an internal class, do not use")
    object OpaqueBytesSerializer : JsonSerializer<OpaqueBytes>() {
        override fun serialize(value: OpaqueBytes, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeBinary(value.bytes)
        }
    }

    @Deprecated("This is an internal class, do not use")
    @Suppress("unused")
    abstract class SignedTransactionMixin {
        @JsonIgnore abstract fun getTxBits(): SerializedBytes<CoreTransaction>
        @JsonProperty("signatures") protected abstract fun getSigs(): List<TransactionSignature>
        @JsonProperty protected abstract fun getTransaction(): CoreTransaction
        @JsonIgnore abstract fun getTx(): WireTransaction
        @JsonIgnore abstract fun getNotaryChangeTx(): NotaryChangeWireTransaction
        @JsonIgnore abstract fun getInputs(): List<StateRef>
        @JsonIgnore abstract fun getNotary(): Party?
        @JsonIgnore abstract fun getId(): SecureHash
        @JsonIgnore abstract fun getRequiredSigningKeys(): Set<PublicKey>
    }

    @Deprecated("This is an internal class, do not use")
    @Suppress("unused")
    abstract class WireTransactionMixin {
        @JsonIgnore abstract fun getMerkleTree(): MerkleTree
        @JsonIgnore abstract fun getAvailableComponents(): List<Any>
        @JsonIgnore abstract fun getAvailableComponentHashes(): List<SecureHash>
        @JsonIgnore abstract fun getOutputStates(): List<ContractState>
    }
}
