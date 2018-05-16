package net.corda.core.internal

import com.google.common.reflect.TypeToken
import net.corda.core.serialization.ConstructorForDeserialization
import java.io.NotSerializableException
import java.lang.reflect.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaType

/**
 * Code for finding the constructor we will use for deserialization.
 *
 * If there's only one constructor, it selects that.  If there are two and one is the default, it selects the other.
 * Otherwise it starts with the primary constructor in kotlin, if there is one, and then will override this with any that is
 * annotated with [@ConstructorForDeserialization].  It will report an error if more than one constructor is annotated.
 */
fun constructorForDeserialization(type: Type): KFunction<Any>? {
    val clazz: Class<*> = type.asClass()!!
    if (clazz.isConcreteClass) {
        var preferredCandidate: KFunction<Any>? = clazz.kotlin.primaryConstructor
        var annotatedCount = 0
        val kotlinConstructors = clazz.kotlin.constructors
        val hasDefault = kotlinConstructors.any { it.parameters.isEmpty() }

        for (kotlinConstructor in kotlinConstructors) {
            if (preferredCandidate == null && kotlinConstructors.size == 1) {
                preferredCandidate = kotlinConstructor
            } else if (preferredCandidate == null && kotlinConstructors.size == 2 && hasDefault && kotlinConstructor.parameters.isNotEmpty()) {
                preferredCandidate = kotlinConstructor
            } else if (kotlinConstructor.findAnnotation<ConstructorForDeserialization>() != null) {
                if (annotatedCount++ > 0) {
                    throw NotSerializableException("More than one constructor for $clazz is annotated with @ConstructorForDeserialization.")
                }
                preferredCandidate = kotlinConstructor
            }
        }

        return preferredCandidate?.apply { isAccessible = true }
                ?: throw NotSerializableException("No constructor for deserialization found for $clazz.")
    } else {
        return null
    }
}

/**
 * Encapsulates the property of a class and its potential getter and setter methods.
 *
 * @property field a property of a class.
 * @property setter the method of a class that sets the field. Determined by locating
 * a function called setXyz on the class for the property named in field as xyz.
 * @property getter the method of a class that returns a fields value. Determined by
 * locating a function named getXyz for the property named in field as xyz.
 */
data class PropertyDescriptor(var field: Field?, var setter: Method?, var getter: Method?, var iser: Method?) {
    constructor() : this(null, null, null, null)

    val preferredGetter: Method? get() = getter ?: iser

    override fun toString(): String {
        return StringBuilder("").apply {
            appendln("Property - ${field?.name ?: "null field"}\n")
            appendln("  getter - ${getter?.name ?: "no getter"}")
            appendln("  setter - ${setter?.name ?: "no setter"}")
            appendln("  iser   - ${iser?.name ?: "no isXYZ defined"}")
        }.toString()
    }
}

private object PropertyDescriptorsRegex {
    // match an uppercase letter that also has a corresponding lower case equivalent
    val re = Regex("(?<type>get|set|is)(?<var>\\p{Lu}.*)")
}

/**
 * Collate the properties of a class and match them with their getter and setter
 * methods as per a JavaBean.
 *
 * for a property
 *      exampleProperty
 *
 * We look for methods
 *      setExampleProperty
 *      getExampleProperty
 *      isExampleProperty
 *
 * Where setExampleProperty must return a type compatible with exampleProperty, getExampleProperty must
 * take a single parameter of a type compatible with exampleProperty and isExampleProperty must
 * return a boolean
 */
fun Class<out Any?>.propertyDescriptors(): Map<String, PropertyDescriptor> {
    val classProperties = mutableMapOf<String, PropertyDescriptor>()

    var clazz: Class<out Any?>? = this

    do {
        clazz!!.declaredFields.forEach { property ->
            classProperties.computeIfAbsent(property.name) {
                PropertyDescriptor()
            }.apply {
                this.field = property
            }
        }
        clazz = clazz.superclass
    } while (clazz != null)

    //
    // Running as two loops rather than one as we need to ensure we have captured all of the properties
    // before looking for interacting methods and need to cope with the class hierarchy introducing
    // new  properties / methods
    //
    clazz = this
    do {
        // Note: It is possible for a class to have multiple instances of a function where the types
        // differ. For example:
        //      interface I<out T> { val a: T }
        //      class D(override val a: String) : I<String>
        // instances of D will have both
        //      getA - returning a String (java.lang.String) and
        //      getA - returning an Object (java.lang.Object)
        // In this instance we take the most derived object
        //
        // In addition, only getters that take zero parameters and setters that take a single
        // parameter will be considered
        clazz!!.declaredMethods?.map { func ->
            if (!Modifier.isPublic(func.modifiers)) return@map
            if (func.name == "getClass") return@map

            PropertyDescriptorsRegex.re.find(func.name)?.apply {
                // matching means we have an func getX where the property could be x or X
                // so having pre-loaded all of the properties we try to match to either case. If that
                // fails the getter doesn't refer to a property directly, but may refer to a constructor
                // parameter that shadows a property
                val properties =
                        classProperties[groups[2]!!.value] ?: classProperties[groups[2]!!.value.decapitalize()] ?:
                        // take into account those constructor properties that don't directly map to a named
                        // property which are, by default, already added to the map
                        classProperties.computeIfAbsent(groups[2]!!.value) { PropertyDescriptor() }

                properties.apply {
                    when (groups[1]!!.value) {
                        "set" -> {
                            if (func.parameterCount == 1) {
                                if (setter == null) setter = func
                                else if (TypeToken.of(setter!!.genericReturnType).isSupertypeOf(func.genericReturnType)) {
                                    setter = func
                                }
                            }
                        }
                        "get" -> {
                            if (func.parameterCount == 0) {
                                if (getter == null) getter = func
                                else if (TypeToken.of(getter!!.genericReturnType).isSupertypeOf(func.genericReturnType)) {
                                    getter = func
                                }
                            }
                        }
                        "is" -> {
                            if (func.parameterCount == 0) {
                                val rtnType = TypeToken.of(func.genericReturnType)
                                if ((rtnType == TypeToken.of(Boolean::class.java))
                                        || (rtnType == TypeToken.of(Boolean::class.javaObjectType))) {
                                    if (iser == null) iser = func
                                }
                            }
                        }
                    }
                }
            }
        }
        clazz = clazz.superclass
    } while (clazz != null)

    return classProperties
}

fun propertiesForSerializationFromConcrete(kotlinConstructor: KFunction<*>, type: Type): List<PropertyInfo> {
    val clazz = (kotlinConstructor.returnType.classifier as KClass<*>).javaObjectType
    val classProperties = clazz.propertyDescriptors()

    // Annoyingly there isn't a better way to ascertain that the constructor for the class
    // has a synthetic parameter inserted to capture the reference to the outer class. You'd
    // think you could inspect the parameter and check the isSynthetic flag but that is always
    // false so given the naming convention is specified by the standard we can just check for
    // this
    val javaConstructor = kotlinConstructor.javaConstructor
    if (javaConstructor?.parameterCount ?: 0 > 0 && javaConstructor?.parameters?.get(0)?.name == "this$0") {
        throw SyntheticParameterException(type)
    }

    return if (classProperties.isNotEmpty() && kotlinConstructor.parameters.isEmpty()) {
        propertiesForSerializationFromSetters(classProperties)
    } else {
        propertiesForSerializationFromConstructor(kotlinConstructor, classProperties, clazz, type)
    }
}

private fun propertiesForSerializationFromConstructor(
        kotlinConstructor: KFunction<*>,
        classProperties: Map<String, PropertyDescriptor>,
        clazz: Class<out Any>,
        type: Type): List<PropertyInfo> {
    return kotlinConstructor.parameters.mapIndexed { index, param ->
        // name cannot be null, if it is then this is a synthetic field and we will have bailed
        // out prior to this
        val name = param.name!!

        // We will already have disambiguated getA for property A or a but we still need to cope
        // with the case we don't know the case of A when the parameter doesn't match a property
        // but has a getter
        val matchingProperty = classProperties[name] ?: classProperties[name.capitalize()]
        ?: throw NotSerializableException(
                "Constructor parameter - \"$name\" -  doesn't refer to a property of \"$clazz\"")

        // If the property has a getter we'll use that to retrieve it's value from the instance, if it doesn't
        // *for *know* we switch to a reflection based method
        if (matchingProperty.getter != null) {
            val getter = matchingProperty.getter ?: throw NotSerializableException(
                    "Property has no getter method for - \"$name\" - of \"$clazz\". If using Java and the parameter name"
                            + "looks anonymous, check that you have the -parameters option specified in the "
                            + "Java compiler. Alternately, provide a proxy serializer "
                            + "(SerializationCustomSerializer) if recompiling isn't an option.")

            val returnType = resolveTypeVariables(getter.genericReturnType, type)
            if (!constructorParamTakesReturnTypeOfGetter(returnType, getter.genericReturnType, param)) {
                throw NotSerializableException(
                        "Property - \"$name\" - has type \"$returnType\" on \"$clazz\" but differs from constructor " +
                                "parameter type \"${param.type.javaType}\"")
            }
            PropertyInfo.Getter(index, name, getter, returnType)
        } else {
            val field = classProperties[name]!!.field
                    ?: throw NotSerializableException("No property matching constructor parameter named - \"$name\" - " +
                            "of \"$clazz\". If using Java, check that you have the -parameters option specified " +
                            "in the Java compiler. Alternately, provide a proxy serializer " +
                            "(SerializationCustomSerializer) if recompiling isn't an option")
            PropertyInfo.Field(index, name, field)
        }
    }
}

/**
 * If we determine a class has a constructor that takes no parameters then check for pairs of getters / setters
 * and use those
 */
fun propertiesForSerializationFromSetters(properties: Map<String, PropertyDescriptor>): List<PropertyInfo.Setter> {
    var idx = 0

    return properties.mapNotNull { (name, descriptor) ->
        val getter: Method? = descriptor.preferredGetter
        val setter: Method? = descriptor.setter

        if (getter == null || setter == null) return@mapNotNull null

        val field = descriptor.field

        if (setter.parameterCount != 1) {
            throw NotSerializableException("Defined setter for parameter ${field?.name} takes too many arguments")
        }

        val setterType = setter.genericParameterTypes[0]!!

        if (field != null && !(TypeToken.of(field.genericType!!).isSupertypeOf(setterType))) {
            throw NotSerializableException("Defined setter for parameter ${field.name} " +
                    "takes parameter of type $setterType yet underlying type is " +
                    "${field.genericType!!}")
        }

        // Make sure the getter returns the same type (within inheritance bounds) the setter accepts.
        if (!(TypeToken.of(getter.genericReturnType).isSupertypeOf(setterType))) {
            throw NotSerializableException("Defined setter for parameter ${field?.name} " +
                    "takes parameter of type $setterType yet the defined getter returns a value of type " +
                    "${getter.returnType} [${getter.genericReturnType}]")
        }

        PropertyInfo.Setter(idx++, name, getter, setter)
    }
}

private fun constructorParamTakesReturnTypeOfGetter(
        getterReturnType: Type,
        rawGetterReturnType: Type,
        param: KParameter): Boolean {
    val paramToken = TypeToken.of(param.type.javaType)
    val rawParamType = TypeToken.of(paramToken.rawType)

    return paramToken.isSupertypeOf(getterReturnType)
            || paramToken.isSupertypeOf(rawGetterReturnType)
            // cope with the case where the constructor parameter is a generic type (T etc) but we
            // can discover it's raw type. When bounded this wil be the bounding type, unbounded
            // generics this will be object
            || rawParamType.isSupertypeOf(getterReturnType)
            || rawParamType.isSupertypeOf(rawGetterReturnType)
}

fun resolveTypeVariables(actualType: Type, contextType: Type?): Type {
    val resolvedType = if (contextType != null) TypeToken.of(contextType).resolveType(actualType).type else actualType
    // TODO: surely we check it is concrete at this point with no TypeVariables
    return if (resolvedType is TypeVariable<*>) {
        val bounds = resolvedType.bounds
        return when (bounds.size) {
            0 -> AnyType
            1 -> resolveTypeVariables(bounds[0], contextType)
            else -> throw NotSerializableException("Got bounded type $actualType but only support single bound.")
        }
    } else {
        resolvedType
    }
}

class SyntheticParameterException(val type: Type) : NotSerializableException("Type '${type.typeName} has synthetic "
        + "fields and is likely a nested inner class. This is not support by the Corda AMQP serialization framework")

sealed class PropertyInfo {
    abstract val name: String

    data class Getter(val position: Int, override val name: String, val getter: Method, val returnType: Type) : PropertyInfo()
    data class Field(val position: Int, override val name: String, val field: java.lang.reflect.Field) : PropertyInfo()
    data class Setter(val position: Int, override val name: String, val getter: Method, val setter: Method) : PropertyInfo()
}

object AnyType : WildcardType {
    override fun getUpperBounds(): Array<Type> = arrayOf(Object::class.java)
    override fun getLowerBounds(): Array<Type> = emptyArray()
    override fun toString(): String = "?"
}

fun Type.asClass(): Class<*>? {
    return when {
        this is Class<*> -> this
        this is ParameterizedType -> this.rawType.asClass()
        this is GenericArrayType -> this.genericComponentType.asClass()?.arrayClass()
        this is TypeVariable<*> -> this.bounds.first().asClass()
        this is WildcardType -> this.upperBounds.first().asClass()
        else -> null
    }
}

fun Class<*>.arrayClass(): Class<*> = java.lang.reflect.Array.newInstance(this, 0).javaClass
