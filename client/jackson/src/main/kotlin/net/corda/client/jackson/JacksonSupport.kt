package net.corda.client.jackson

import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.PropertyAccessor
import com.fasterxml.jackson.core.*
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import net.corda.core.CordaOID
import net.corda.core.contracts.Amount
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.*
import net.corda.core.identity.*
import net.corda.core.internal.CertRole
import net.corda.core.internal.DigitalSignatureWithCert
import net.corda.core.internal.uncheckedCast
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.services.IdentityService
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.transactions.CoreTransaction
import net.corda.core.transactions.NotaryChangeWireTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.WireTransaction
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.base64ToByteArray
import net.corda.core.utilities.toBase58
import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.X509CertificateHolder
import java.lang.reflect.Modifier
import java.math.BigDecimal
import java.security.PublicKey
import java.security.cert.CertPath
import java.security.cert.X509Certificate
import java.util.*

/**
 * Utilities and serialisers for working with JSON representations of basic types. This adds Jackson support for
 * the java.time API, some core types, and Kotlin data classes.
 *
 * Note that Jackson can also be used to serialise/deserialise other formats such as Yaml and XML.
 */
object JacksonSupport {
    // TODO: This API could use some tidying up - there should really only need to be one kind of mapper.
    // If you change this API please update the docs in the docsite (json.rst)

    interface PartyObjectMapper {
        fun wellKnownPartyFromX500Name(name: CordaX500Name): Party?
        fun partyFromKey(owningKey: PublicKey): Party?
        fun partiesFromName(query: String): Set<Party>
    }

    class RpcObjectMapper(val rpc: CordaRPCOps, factory: JsonFactory, val fuzzyIdentityMatch: Boolean) : PartyObjectMapper, ObjectMapper(factory) {
        override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? = rpc.wellKnownPartyFromX500Name(name)
        override fun partyFromKey(owningKey: PublicKey): Party? = rpc.partyFromKey(owningKey)
        override fun partiesFromName(query: String) = rpc.partiesFromName(query, fuzzyIdentityMatch)
    }

    class IdentityObjectMapper(val identityService: IdentityService, factory: JsonFactory, val fuzzyIdentityMatch: Boolean) : PartyObjectMapper, ObjectMapper(factory) {
        override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? = identityService.wellKnownPartyFromX500Name(name)
        override fun partyFromKey(owningKey: PublicKey): Party? = identityService.partyFromKey(owningKey)
        override fun partiesFromName(query: String) = identityService.partiesFromName(query, fuzzyIdentityMatch)
    }

    class NoPartyObjectMapper(factory: JsonFactory) : PartyObjectMapper, ObjectMapper(factory) {
        override fun wellKnownPartyFromX500Name(name: CordaX500Name): Party? = throw UnsupportedOperationException()
        override fun partyFromKey(owningKey: PublicKey): Party? = throw UnsupportedOperationException()
        override fun partiesFromName(query: String) = throw UnsupportedOperationException()
    }

    val cordaModule: Module by lazy {
        SimpleModule("core").apply {
            addSerializer(AnonymousParty::class.java, AnonymousPartySerializer)
            addDeserializer(AnonymousParty::class.java, AnonymousPartyDeserializer)
            addSerializer(Party::class.java, PartySerializer)
            addDeserializer(Party::class.java, PartyDeserializer)
            addDeserializer(AbstractParty::class.java, PartyDeserializer)
            addSerializer(BigDecimal::class.java, ToStringSerializer)
            addDeserializer(BigDecimal::class.java, NumberDeserializers.BigDecimalDeserializer())
            addSerializer(SecureHash::class.java, SecureHashSerializer)
            addSerializer(SecureHash.SHA256::class.java, SecureHashSerializer)
            addDeserializer(SecureHash::class.java, SecureHashDeserializer())
            addDeserializer(SecureHash.SHA256::class.java, SecureHashDeserializer())
            addSerializer(NetworkHostAndPort::class.java, ToStringSerializer)
            addDeserializer(NetworkHostAndPort::class.java, NetworkHostAndPortDeserializer)

            // Public key types
            addSerializer(PublicKey::class.java, PublicKeySerializer)
            addDeserializer(PublicKey::class.java, PublicKeyDeserializer)

            // For NodeInfo
            // TODO this tunnels the Kryo representation as a Base58 encoded string. Replace when RPC supports this.
//            addSerializer(NodeInfo::class.java, NodeInfoSerializer)
//            addDeserializer(NodeInfo::class.java, NodeInfoDeserializer)

            addSerializerFor<PartyAndCertificate> {
                customObject {
                    writeObjectField("name", it.name)
                    writeObjectField("owningKey", it.owningKey)
                    writeObjectField("certPath", it.certPath)
                }
            }

            // For Amount
            addSerializer(Amount::class.java, AmountSerializer)
            addDeserializer(Amount::class.java, AmountDeserializer)

            // For OpaqueBytes
            addDeserializer(OpaqueBytes::class.java, OpaqueBytesDeserializer)
            addSerializer(OpaqueBytes::class.java, OpaqueBytesSerializer)

            addSerializer(SerializedBytesSerializer)

            // For X.500 distinguished names
            addDeserializer(CordaX500Name::class.java, CordaX500NameDeserializer)
            addSerializer(CordaX500Name::class.java, CordaX500NameSerializer)

            addSerializerFor<DigitalSignatureWithCert> {
                customObject {
                    writeObjectField("by", it.by)
                    writeObjectField("bytes", it.bytes)
                }
            }

            addSerializerFor<ByteArray> { writeString(it.toBase58()) }
            addSerializerFor<Date> { writeObject(it.toInstant()) }
            addSerializerFor<CertPath> { writeObject(it.certificates) }
            addSerializerFor<X509Certificate> {
                val holder = X509CertificateHolder(it.encoded)
                val asn1 = holder.toASN1Structure()
                customObject {
                    writeObjectField("version", asn1.version)
                    writeObjectField("serialNumber", asn1.serialNumber)
                    writeObjectField("issuer", asn1.issuer)
                    customObject("validity") {
                        writeObjectField("notBefore", asn1.startDate)
                        writeObjectField("notAfter", asn1.endDate)
                    }
                    writeObjectField("subject", asn1.subject)
                    writeObjectField("subjectPublicKey", asn1.subjectPublicKeyInfo)
                    writeObjectField("issuerUniqueID", asn1.tbsCertificate.issuerUniqueId)
                    writeObjectField("subjectUniqueID", asn1.tbsCertificate.subjectUniqueId)
                    writeObjectField("extensions", holder.extensions)
                    writeObjectField("signatureAlgorithm", holder.signatureAlgorithm)
                    writeObjectField("signatureValue", holder.signature)
                }
            }
            addSerializer(AlgorithmIdentifierSerializer)
            addSerializerFor<Extensions> { writeObject(it.toASN1Primitive()) }
            addSerializer(ExtensionSerializer)
            addSerializerFor<ASN1Null> { writeNull() }
            addSerializerFor<ASN1Boolean> { writeBoolean(it.isTrue) }
            addSerializerFor<ASN1Integer> { writeNumber(it.value) }
            addSerializerFor<ASN1Enumerated> { writeNumber(it.value) }
            addSerializer(ASN1ObjectIdentifier::class.java, ToStringSerializer)
            addSerializerFor<ASN1UTCTime> { writeObject(it.adjustedDate) }
            addSerializerFor<ASN1GeneralizedTime> { writeObject(it.date) }
            addSerializerFor<ASN1OctetString> { writeObject(it.octets) }
            addSerializerFor<ASN1BitString> { writeObject(it.bytes) }
            addSerializerFor<ASN1Sequence> { writeObject(it.toArray()) }
            addSerializerFor<ASN1Set> { writeObject(it.toArray()) }
            addSerializerFor<Time> { writeObject(it.date) }
            addSerializer(X500Name::class.java, ToStringSerializer)
            addSerializerFor<SubjectPublicKeyInfo> { writeObject(Crypto.toSupportedPublicKey(it)) }
            addSerializerFor<AuthorityKeyIdentifier> {
                customObject {
                    writeObjectField("keyIdentifier", it.keyIdentifier)
                    writeObjectField("authorityCertIssuer", it.authorityCertIssuer)
                    writeObjectField("authorityCertSerialNumber", it.authorityCertSerialNumber)
                }
            }
            addSerializerFor<GeneralName> { writeObject(it.name) }

            // Mixins for transaction types to prevent some properties from being serialized
            setMixInAnnotation(SignedTransaction::class.java, SignedTransactionMixin::class.java)
            setMixInAnnotation(WireTransaction::class.java, WireTransactionMixin::class.java)
        }
    }

    private object AlgorithmIdentifierSerializer : JsonSerializer<AlgorithmIdentifier>() {
        val signatureSchemes = Crypto.supportedSignatureSchemes().associateBy { it.signatureOID }

        override fun serialize(value: AlgorithmIdentifier, gen: JsonGenerator, serializers: SerializerProvider) {
            val sigScheme = signatureSchemes[value]
            if (sigScheme != null) {
                gen.writeString(sigScheme.schemeCodeName)
            } else {
                gen.customObject {
                    writeObjectField("algorithm", value.algorithm)
                    writeObjectField("parameters", value.parameters)
                }
            }
        }

        override fun handledType(): Class<AlgorithmIdentifier> = AlgorithmIdentifier::class.java
    }

    private object ExtensionSerializer : JsonSerializer<Extension>() {
        val knownExtensions = mapOf(
                "2.5.29.14" to Ext("subjectKeyIdentifier") { SubjectKeyIdentifier.getInstance(it).keyIdentifier },
                "2.5.29.15" to Ext("keyUsage", ::keyUsageList),
                "2.5.29.19" to Ext("basicConstraints", BasicConstraints::getInstance),
                "2.5.29.30" to Ext("nameConstraints", NameConstraints::getInstance),
                "2.5.29.35" to Ext("authorityKeyIdentifier", AuthorityKeyIdentifier::getInstance),
                "2.5.29.37" to Ext("extKeyUsage", ::extKeyUsageList),
                CordaOID.X509_EXTENSION_CORDA_ROLE to Ext("cordaCertRole", CertRole.Companion::getInstance)
        )

        override fun serialize(value: Extension, gen: JsonGenerator, serializers: SerializerProvider) {
            val ext = knownExtensions[value.extnId.id]
            gen.customObject {
                if (ext != null) {
                    writeObjectField(ext.fieldName, ext.serializer(value.extnValue.octets))
                } else {
                    writeObjectField("extnID", value.extnId)
                    writeBooleanField("critical", value.isCritical)
                    writeObjectField("extnValue", value.extnValue)
                }
            }
        }

        override fun handledType(): Class<Extension> = Extension::class.java

        private val keyUsages = KeyUsage::class.java
                .fields
                .filter { Modifier.isStatic(it.modifiers) && it.type == Integer.TYPE }
                .map { it.get(null) as Int to it.name }

        private fun keyUsageList(bytes: ByteArray): List<String> {
            val keyUsage = KeyUsage.getInstance(bytes)
            return keyUsages.mapNotNull { (bit, name) -> if (keyUsage.hasUsages(bit)) name else null }
        }

        private val keyPurposeIds = KeyPurposeId::class.java
                .fields
                .filter { Modifier.isStatic(it.modifiers) && it.type == KeyPurposeId::class.java }
                .map { it.get(null) as KeyPurposeId to it.name }

        private fun extKeyUsageList(bytes: ByteArray): List<String> {
            val extKeyUsage = ExtendedKeyUsage.getInstance(bytes)
            return keyPurposeIds.mapNotNull { (id, name) -> if (extKeyUsage.hasKeyPurposeId(id)) name else null }
        }

        open class Ext(val fieldName: String, val serializer: (ByteArray) -> Any)
    }

    private inline fun <reified T> SimpleModule.addSerializerFor(crossinline serializer: JsonGenerator.(T) -> Unit) {
        addSerializer(T::class.java, object : JsonSerializer<T>() {
            override fun serialize(value: T, gen: JsonGenerator, serializers: SerializerProvider) = gen.serializer(value)
        })
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
    @JvmStatic
    @JvmOverloads
    fun createInMemoryMapper(identityService: IdentityService,
                             factory: JsonFactory = JsonFactory(),
                             fuzzyIdentityMatch: Boolean = false): ObjectMapper {
        return configureMapper(IdentityObjectMapper(identityService, factory, fuzzyIdentityMatch))
    }

    private fun configureMapper(mapper: ObjectMapper): ObjectMapper {
        return mapper.apply {
            setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
            setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            enable(SerializationFeature.INDENT_OUTPUT)
            enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

            registerModule(JavaTimeModule())
            registerModule(cordaModule)
            registerModule(KotlinModule())
        }
    }

    object NetworkHostAndPortDeserializer : JsonDeserializer<NetworkHostAndPort>() {
        override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): NetworkHostAndPort {
            return NetworkHostAndPort.parse(parser.text)
        }
    }

    object ToStringSerializer : JsonSerializer<Any>() {
        override fun serialize(obj: Any, generator: JsonGenerator, provider: SerializerProvider) {
            generator.writeString(obj.toString())
        }
    }

    object AnonymousPartySerializer : JsonSerializer<AnonymousParty>() {
        override fun serialize(obj: AnonymousParty, generator: JsonGenerator, provider: SerializerProvider) {
            PublicKeySerializer.serialize(obj.owningKey, generator, provider)
        }
    }

    object AnonymousPartyDeserializer : JsonDeserializer<AnonymousParty>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): AnonymousParty {
            if (parser.currentToken == JsonToken.FIELD_NAME) {
                parser.nextToken()
            }

            val key = PublicKeyDeserializer.deserialize(parser, context)
            return AnonymousParty(key)
        }
    }

    object PartySerializer : JsonSerializer<Party>() {
        override fun serialize(obj: Party, generator: JsonGenerator, provider: SerializerProvider) {
//            generator.writeObject(obj.name)
            generator.customObject {
                writeObjectField("name", obj.name)
                writeObjectField("owningKey", obj.owningKey)
            }
        }
    }

    object PartyDeserializer : JsonDeserializer<Party>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): Party {
            if (parser.currentToken == JsonToken.FIELD_NAME) {
                parser.nextToken()
            }

            val mapper = parser.codec as PartyObjectMapper
            // The comma character is invalid in base64, and required as a separator for X.500 names. As Corda
            // X.500 names all involve at least three attributes (organisation, locality, country), they must
            // include a comma. As such we can use it as a distinguisher between the two types.
            return if ("," in parser.text) {
                val principal = CordaX500Name.parse(parser.text)
                mapper.wellKnownPartyFromX500Name(principal) ?: throw JsonParseException(parser, "Could not find a Party with name $principal")
            } else {
                val nameMatches = mapper.partiesFromName(parser.text)
                if (nameMatches.isEmpty()) {
                    val derBytes = try {
                        parser.text.base64ToByteArray()
                    } catch (e: AddressFormatException) {
                        throw JsonParseException(parser, "Could not find a matching party for '${parser.text}' and is not a base64 encoded public key: " + e.message)
                    }
                    val key = try {
                        Crypto.decodePublicKey(derBytes)
                    } catch (e: Exception) {
                        throw JsonParseException(parser, "Could not find a matching party for '${parser.text}' and is not a valid public key: " + e.message)
                    }
                    mapper.partyFromKey(key) ?: throw JsonParseException(parser, "Could not find a Party with key ${key.toStringShort()}")
                } else if (nameMatches.size == 1) {
                    nameMatches.first()
                } else {
                    throw JsonParseException(parser, "Ambiguous name match '${parser.text}': could be any of " + nameMatches.map { it.name }.joinToString(" ... or ..."))
                }
            }
        }
    }

    object CordaX500NameSerializer : JsonSerializer<CordaX500Name>() {
        override fun serialize(obj: CordaX500Name, generator: JsonGenerator, provider: SerializerProvider) {
            generator.writeString(obj.toString())
        }
    }

    object CordaX500NameDeserializer : JsonDeserializer<CordaX500Name>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): CordaX500Name {
            if (parser.currentToken == JsonToken.FIELD_NAME) {
                parser.nextToken()
            }

            return try {
                CordaX500Name.parse(parser.text)
            } catch (ex: IllegalArgumentException) {
                throw JsonParseException(parser, "Invalid Corda X.500 name ${parser.text}: ${ex.message}", ex)
            }
        }
    }

//    object NodeInfoSerializer : JsonSerializer<NodeInfo>() {
//        override fun serialize(value: NodeInfo, gen: JsonGenerator, serializers: SerializerProvider) {
//            gen.writeString(Base58.encode(value.serialize().bytes))
//        }
//    }
//
//    object NodeInfoDeserializer : JsonDeserializer<NodeInfo>() {
//        override fun deserialize(parser: JsonParser, context: DeserializationContext): NodeInfo {
//            if (parser.currentToken == JsonToken.FIELD_NAME) {
//                parser.nextToken()
//            }
//            try {
//                return Base58.decode(parser.text).deserialize()
//            } catch (e: Exception) {
//                throw JsonParseException(parser, "Invalid NodeInfo ${parser.text}: ${e.message}")
//            }
//        }
//    }

    object SecureHashSerializer : JsonSerializer<SecureHash>() {
        override fun serialize(obj: SecureHash, generator: JsonGenerator, provider: SerializerProvider) {
            generator.writeString(obj.toString())
        }
    }

    /**
     * Implemented as a class so that we can instantiate for T.
     */
    class SecureHashDeserializer<T : SecureHash> : JsonDeserializer<T>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): T {
            if (parser.currentToken == JsonToken.FIELD_NAME) {
                parser.nextToken()
            }
            try {
                return uncheckedCast(SecureHash.parse(parser.text))
            } catch (e: Exception) {
                throw JsonParseException(parser, "Invalid hash ${parser.text}: ${e.message}")
            }
        }
    }

    object PublicKeySerializer : JsonSerializer<PublicKey>() {
        override fun serialize(obj: PublicKey, generator: JsonGenerator, provider: SerializerProvider) {
            generator.writeObject(obj.encoded)
        }
    }

    object PublicKeyDeserializer : JsonDeserializer<PublicKey>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): PublicKey {
            return try {
                val derBytes = parser.text.base64ToByteArray()
                Crypto.decodePublicKey(derBytes)
            } catch (e: Exception) {
                throw JsonParseException(parser, "Invalid public key ${parser.text}: ${e.message}")
            }
        }
    }

    object AmountSerializer : JsonSerializer<Amount<*>>() {
        override fun serialize(value: Amount<*>, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(value.toString())
        }
    }

    object AmountDeserializer : JsonDeserializer<Amount<*>>() {
        override fun deserialize(parser: JsonParser, context: DeserializationContext): Amount<*> {
            return try {
                Amount.parseCurrency(parser.text)
            } catch (e: Exception) {
                try {
                    val tree = parser.readValueAsTree<JsonNode>()
                    require(tree["quantity"].canConvertToLong() && tree["token"].asText().isNotBlank())
                    val quantity = tree["quantity"].asLong()
                    val token = tree["token"].asText()
                    // Attempt parsing as a currency token. TODO: This needs thought about how to extend to other token types.
                    val currency = Currency.getInstance(token)
                    Amount(quantity, currency)
                } catch (e2: Exception) {
                    throw JsonParseException(parser, "Invalid amount ${parser.text}", e2)
                }
            }
        }
    }

    object OpaqueBytesDeserializer : JsonDeserializer<OpaqueBytes>() {
        override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): OpaqueBytes {
            return OpaqueBytes(parser.binaryValue)
        }
    }

    object OpaqueBytesSerializer : JsonSerializer<OpaqueBytes>() {
        override fun serialize(value: OpaqueBytes, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeObject(value.bytes)
        }
    }

    object SerializedBytesSerializer : JsonSerializer<SerializedBytes<Any>>() {
        override fun serialize(value: SerializedBytes<Any>, gen: JsonGenerator, serializers: SerializerProvider) {
            val deserialize = value.deserialize()
            if (gen.canWriteTypeId()) {
                gen.writeTypeId(deserialize.javaClass.name)
            }
            gen.writeObject(deserialize)
        }
        override fun handledType(): Class<SerializedBytes<Any>> = uncheckedCast(SerializedBytes::class.java)
    }

    private inline fun JsonGenerator.customObject(fieldName: String? = null, gen: JsonGenerator.() -> Unit) {
        fieldName?.let { writeFieldName(it) }
        writeStartObject()
        gen()
        writeEndObject()
    }

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

    abstract class WireTransactionMixin {
        @JsonIgnore abstract fun getMerkleTree(): MerkleTree
        @JsonIgnore abstract fun getAvailableComponents(): List<Any>
        @JsonIgnore abstract fun getAvailableComponentHashes(): List<SecureHash>
        @JsonIgnore abstract fun getOutputStates(): List<ContractState>
    }
}
