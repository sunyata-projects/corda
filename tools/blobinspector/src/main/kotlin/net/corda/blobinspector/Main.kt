package net.corda.blobinspector

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import net.corda.client.jackson.JacksonSupport
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.internal.SerializationEnvironmentImpl
import net.corda.core.serialization.internal._contextSerializationEnv
import net.corda.nodeapi.internal.serialization.AMQP_P2P_CONTEXT
import net.corda.nodeapi.internal.serialization.SerializationFactoryImpl
import net.corda.nodeapi.internal.serialization.amqp.AMQPServerSerializationScheme
import picocli.CommandLine
import picocli.CommandLine.*
import java.net.URL
import java.nio.file.InvalidPathException
import java.nio.file.Paths

@Command(
        name = "Blob Inspector",
        version = ["Print version here ..."],
        mixinStandardHelpOptions = true, // add --help and --version options,
        showDefaultValues = true,
        description = ["Inspect AMQP serialised binary blobs"]
)
class MyApp : Runnable {
    @Parameters(index = "0", paramLabel = "SOURCE", description = ["URL or file path to the blob"], converter = [SourceConverter::class])
    private var source: URL? = null

    @Option(names = ["--format"], paramLabel = "type", description = ["Output format. Possible values: [YAML, JSON]"])
    private var formatType: FormatType = FormatType.YAML

    @Option(names = ["--verbose"], description = ["Enable debug output"])
    private var verbose: Boolean = false

    @Option(names = ["--schema"], description = ["Print the blob's schema"])
    private var schema: Boolean = false

    @Option(names = ["--transforms"], description = ["Print the blob's transforms schema"])
    private var transforms: Boolean = false

    override fun run() {
        initialiseSerialization()
        val factory = when (formatType) {
            FormatType.YAML -> YAMLFactory()
            FormatType.JSON -> JsonFactory()
        }
        val mapper = JacksonSupport.createNonRpcMapper(factory)
        val a = source!!.readBytes().deserialize<Any>()
        println(a.javaClass.name)
        mapper.writeValue(System.out, a)
//        val config = Config(verbose, schema, transforms, true)
//        inspectBlob(config, source!!.readBytes())
    }

    private fun initialiseSerialization() {
        _contextSerializationEnv.set(SerializationEnvironmentImpl(
                SerializationFactoryImpl().apply {
                    registerScheme(AMQPServerSerializationScheme())
                },
                AMQP_P2P_CONTEXT)
        )
    }
}

private class SourceConverter : ITypeConverter<URL> {
    override fun convert(value: String): URL {
        return try {
            Paths.get(value).toUri().toURL()
        } catch (e: InvalidPathException) {
            URL(value)
        }
    }
}

private enum class FormatType { YAML, JSON }

fun main(args: Array<String>) = CommandLine.run(MyApp(), *args)
