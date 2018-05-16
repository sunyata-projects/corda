package net.corda.nodeapi.internal.serialization.amqp

import com.google.common.primitives.Primitives
import com.google.common.reflect.TypeToken
import net.corda.core.internal.*
import net.corda.core.serialization.ClassWhitelist
import net.corda.core.serialization.CordaSerializable
import net.corda.core.serialization.SerializationContext
import org.apache.qpid.proton.codec.Data
import java.io.NotSerializableException
import java.lang.reflect.*
import java.util.*
import kotlin.reflect.KFunction

/**
 * Identifies the properties to be used during serialization by attempting to find those that match the parameters
 * to the deserialization constructor, if the class is concrete.  If it is abstract, or an interface, then use all
 * the properties.
 *
 * Note, you will need any Java classes to be compiled with the `-parameters` option to ensure constructor parameters
 * have names accessible via reflection.
 */
internal fun <T : Any> propertiesForSerialization(
        kotlinConstructor: KFunction<T>?,
        type: Type,
        factory: SerializerFactory): PropertySerializers {
    return PropertySerializers.make(
            if (kotlinConstructor != null) {
                propertiesForSerializationFromConcrete(kotlinConstructor, type, factory)
            } else {
                propertiesForSerializationFromAbstract(type.asClass()!!, type, factory)
            }.sortedWith(PropertyAccessor)
    )
}

/**
 * From a constructor, determine which properties of a class are to be serialized.
 *
 * @param kotlinConstructor The constructor to be used to instantiate instances of the class
 * @param type The class's [Type]
 * @param factory The factory generating the serializer wrapping this function.
 */
internal fun <T : Any> propertiesForSerializationFromConcrete(
        kotlinConstructor: KFunction<T>,
        type: Type,
        factory: SerializerFactory): List<PropertyAccessor> {
    return propertiesForSerializationFromConcrete(kotlinConstructor, type).map { it.toPropertyAccessor(factory, type) }
}

fun PropertyInfo.toPropertyAccessor(factory: SerializerFactory, type: Type): PropertyAccessor {
    return when (this) {
        is PropertyInfo.Getter -> PropertyAccessorConstructor(
                position,
                PropertySerializer.make(name, PublicPropertyReader(getter), returnType, factory)
        )
        is PropertyInfo.Field -> PropertyAccessorConstructor(
                position,
                PropertySerializer.make(name, PrivatePropertyReader(field, type), field.genericType, factory)
        )
        is PropertyInfo.Setter -> PropertyAccessorGetterSetter(
                position,
                PropertySerializer.make(
                        name,
                        PublicPropertyReader(getter),
                        resolveTypeVariables(getter.genericReturnType, type),
                        factory
                ),
                setter
        )
    }
}

private fun propertiesForSerializationFromAbstract(
        clazz: Class<*>,
        type: Type,
        factory: SerializerFactory): List<PropertyAccessor> {
    val properties = clazz.propertyDescriptors()

    return mutableListOf<PropertyAccessorConstructor>().apply {
        properties.toList().withIndex().forEach {
            val getter = it.value.second.getter ?: return@forEach
            if (it.value.second.field == null) return@forEach

            val returnType = resolveTypeVariables(getter.genericReturnType, type)
            this += PropertyAccessorConstructor(
                    it.index,
                    PropertySerializer.make(it.value.first, PublicPropertyReader(getter), returnType, factory)
            )
        }
    }
}

internal fun interfacesForSerialization(type: Type, serializerFactory: SerializerFactory): List<Type> {
    val interfaces = LinkedHashSet<Type>()
    exploreType(type, interfaces, serializerFactory)
    return interfaces.toList()
}

private fun exploreType(type: Type?, interfaces: MutableSet<Type>, serializerFactory: SerializerFactory) {
    val clazz = type?.asClass()
    if (clazz != null) {
        if (clazz.isInterface) {
            if (serializerFactory.whitelist.isNotWhitelisted(clazz)) return // We stop exploring once we reach a branch that has no `CordaSerializable` annotation or whitelisting.
            else interfaces += type
        }
        for (newInterface in clazz.genericInterfaces) {
            if (newInterface !in interfaces) {
                exploreType(resolveTypeVariables(newInterface, type), interfaces, serializerFactory)
            }
        }
        val superClass = clazz.genericSuperclass ?: return
        exploreType(resolveTypeVariables(superClass, type), interfaces, serializerFactory)
    }
}

/**
 * Extension helper for writing described objects.
 */
fun Data.withDescribed(descriptor: Descriptor, block: Data.() -> Unit) {
    // Write described
    putDescribed()
    enter()
    // Write descriptor
    putObject(descriptor.code ?: descriptor.name)
    block()
    exit() // exit described
}

/**
 * Extension helper for writing lists.
 */
fun Data.withList(block: Data.() -> Unit) {
    // Write list
    putList()
    enter()
    block()
    exit() // exit list
}

/**
 * Extension helper for outputting reference to already observed object
 */
fun Data.writeReferencedObject(refObject: ReferencedObject) {
    // Write described
    putDescribed()
    enter()
    // Write descriptor
    putObject(refObject.descriptor)
    putUnsignedInteger(refObject.described)
    exit() // exit described
}

internal fun Type.asArray(): Type? {
    return when {
        this is Class<*> -> this.arrayClass()
        this is ParameterizedType -> DeserializedGenericArrayType(this)
        else -> null
    }
}

internal fun Type.isArray(): Boolean = (this is Class<*> && this.isArray) || (this is GenericArrayType)

internal fun Type.componentType(): Type {
    check(this.isArray()) { "$this is not an array type." }
    return (this as? Class<*>)?.componentType ?: (this as GenericArrayType).genericComponentType
}

internal fun Class<*>.asParameterizedType(): ParameterizedType {
    return DeserializedParameterizedType(this, this.typeParameters)
}

internal fun Type.asParameterizedType(): ParameterizedType {
    return when (this) {
        is Class<*> -> this.asParameterizedType()
        is ParameterizedType -> this
        else -> throw NotSerializableException("Don't know how to convert to ParameterizedType")
    }
}

internal fun Type.isSubClassOf(type: Type): Boolean {
    return TypeToken.of(this).isSubtypeOf(TypeToken.of(type).rawType)
}

// ByteArrays, primitives and boxed primitives are not stored in the object history
internal fun suitableForObjectReference(type: Type): Boolean {
    val clazz = type.asClass()
    return type != ByteArray::class.java && (clazz != null && !clazz.isPrimitive && !Primitives.unwrap(clazz).isPrimitive)
}

/**
 * Common properties that are to be used in the [SerializationContext.properties] to alter serialization behavior/content
 */
internal enum class CommonPropertyNames {
    IncludeInternalInfo,
}

/**
 * Utility function which helps tracking the path in the object graph when exceptions are thrown.
 * Since there might be a chain of nested calls it is useful to record which part of the graph caused an issue.
 * Path information is added to the message of the exception being thrown.
 */
internal inline fun <T> ifThrowsAppend(strToAppendFn: () -> String, block: () -> T): T {
    try {
        return block()
    } catch (th: Throwable) {
        th.setMessage("${strToAppendFn()} -> ${th.message}")
        throw th
    }
}

/**
 * Not a public property so will have to use reflection
 */
private fun Throwable.setMessage(newMsg: String) {
    val detailMessageField = Throwable::class.java.getDeclaredField("detailMessage")
    detailMessageField.isAccessible = true
    detailMessageField.set(this, newMsg)
}

fun ClassWhitelist.requireWhitelisted(type: Type) {
    if (!this.isWhitelisted(type.asClass()!!)) {
        throw NotSerializableException("Class $type is not on the whitelist or annotated with @CordaSerializable.")
    }
}

fun ClassWhitelist.isWhitelisted(clazz: Class<*>) = (hasListed(clazz) || hasAnnotationInHierarchy(clazz))
fun ClassWhitelist.isNotWhitelisted(clazz: Class<*>) = !(this.isWhitelisted(clazz))

// Recursively check the class, interfaces and superclasses for our annotation.
fun ClassWhitelist.hasAnnotationInHierarchy(type: Class<*>): Boolean {
    return type.isAnnotationPresent(CordaSerializable::class.java)
            || type.interfaces.any { hasAnnotationInHierarchy(it) }
            || (type.superclass != null && hasAnnotationInHierarchy(type.superclass))
}

/**
 * By default use Kotlin reflection and grab the objectInstance. However, that doesn't play nicely with nested
 * private objects. Even setting the accessibility override (setAccessible) still causes an
 * [IllegalAccessException] when attempting to retrieve the value of the INSTANCE field.
 *
 * Whichever reference to the class Kotlin reflection uses, override (set from setAccessible) on that field
 * isn't set even when it was explicitly set as accessible before calling into the kotlin reflection routines.
 *
 * For example
 *
 * clazz.getDeclaredField("INSTANCE")?.apply {
 *     isAccessible = true
 *     kotlin.objectInstance // This throws as the INSTANCE field isn't accessible
 * }
 *
 * Therefore default back to good old java reflection and simply look for the INSTANCE field as we are never going
 * to serialize a companion object.
 *
 * As such, if objectInstance fails access, revert to Java reflection and try that
 */
fun Class<*>.objectInstance() =
        try {
            this.kotlin.objectInstance
        } catch (e: IllegalAccessException) {
            // Check it really is an object (i.e. it has no constructor)
            if (constructors.isNotEmpty()) null
            else {
                try {
                    this.getDeclaredField("INSTANCE")?.let { field ->
                        // and must be marked as both static and final (>0 means they're set)
                        if (modifiers and Modifier.STATIC == 0 || modifiers and Modifier.FINAL == 0) null
                        else {
                            val accessibility = field.isAccessible
                            field.isAccessible = true
                            val obj = field.get(null)
                            field.isAccessible = accessibility
                            obj
                        }
                    }
                } catch (e: NoSuchFieldException) {
                    null
                }
            }
        }
