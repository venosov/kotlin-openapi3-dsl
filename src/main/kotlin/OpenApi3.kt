
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

private val mapper = ObjectMapper()

data class OpenApi3Info(
        var title: String = "",
        var version: String = ""
)

interface OpenApi3MediaType {
    val schema: String
}

data class OpenApi3TypedMediaType<T>(
        @field:JsonIgnore
        val clazz: Class<T>
) : OpenApi3MediaType {
    override val schema: String
    @field:JsonIgnore
    val schemaJson: JSONObject

    init {
        val schemaGen = JsonSchemaGenerator(mapper)
        val s = schemaGen.generateSchema(clazz)
        val jsonSchema = JSONObject(mapper.writeValueAsString(s))
        jsonSchema.remove("id")
        schemaJson = JSONObject(mapOf("schema" to jsonSchema))
        schema = "#/components/schemas/${clazz.simpleName}"
    }

}

class OpenApi3MediaTypeSerializer(mt: Class<OpenApi3MediaType>? = null) : StdSerializer<OpenApi3MediaType>(mt) {
    override fun serialize(value: OpenApi3MediaType, gen: JsonGenerator, provider: SerializerProvider?) {
        gen.writeRawValue(JSONObject(mapOf("schema" to mapOf("\$ref" to value.schema))).toString())
    }
}

class OpenApi3ComponentsSerializer(mt: Class<OpenApi3Components>? = null) : StdSerializer<OpenApi3Components>(mt) {
    override fun serialize(value: OpenApi3Components, gen: JsonGenerator, provider: SerializerProvider?) {
        gen.writeRawValue(JSONObject().put("schemas", value.schemas).toString())
    }
}

data class OpenApi3Response(
        var description: String = ""
) {
    val content = HashMap<String, OpenApi3TypedMediaType<*>>()
    inline fun <reified T> response(mediaType: String) {
        val apiMediaType = OpenApi3TypedMediaType(T::class.java)
        content.put(mediaType, apiMediaType)
    }
}

data class OpenApi3RequestBodies(
        var description: String = "",
        private val requests: MutableMap<String, OpenApi3TypedMediaType<*>> = HashMap()
) : MutableMap<String, OpenApi3TypedMediaType<*>> by requests {
    inline fun <reified T> request(mediaType: String) {
        val apiMediaType = OpenApi3TypedMediaType(T::class.java)
        put(mediaType, apiMediaType)
    }
}

data class OpenApi3Responses(
        private val responses: MutableMap<String, OpenApi3Response> = HashMap()
) : MutableMap<String, OpenApi3Response> by responses

data class OpenApi3Path(
        var description: String = "",
        var operationId: String = ""
) {
    val responses = OpenApi3Responses()
    var requestBody: OpenApi3RequestBodies? = null
    fun code(code: String, init: OpenApi3Response.() -> Unit) {
        val response = OpenApi3Response()
        response.init()
        responses.put(code, response)
    }

    fun requestBody(init: OpenApi3RequestBodies.() -> Unit) {
        if (requestBody == null) {
            requestBody = OpenApi3RequestBodies()
            requestBody!!.init()
        }
    }
}


open class OpenApi3MethodPath(
        @field:JsonIgnore
        val path: OpenApi3Path,
        @field:JsonIgnore
        val jsonKey: String
)

data class OpenApi3GetPath(val get: OpenApi3Path) : OpenApi3MethodPath(get, "get")
data class OpenApi3PostPath(val post: OpenApi3Path) : OpenApi3MethodPath(post, "post")
data class OpenApi3PutPath(val put: OpenApi3Path) : OpenApi3MethodPath(put, "put")
data class OpenApi3DeletePath(val delete: OpenApi3Path) : OpenApi3MethodPath(delete, "delete")
data class OpenApi3PatchPath(val patch: OpenApi3Path) : OpenApi3MethodPath(patch, "patch")
data class OpenApi3HeadPath(val head: OpenApi3Path) : OpenApi3MethodPath(head, "head")
data class OpenApi3OptionsPath(val options: OpenApi3Path) : OpenApi3MethodPath(options, "options")

data class OpenApi3Paths(
        private val paths: MutableMap<String, MutableMap<String, OpenApi3Path>> = HashMap()
) : MutableMap<String, MutableMap<String, OpenApi3Path>> by paths {
    private fun initOpenApi3Path(init: OpenApi3Path.() -> Unit): OpenApi3Path {
        val apiPath = OpenApi3Path()
        apiPath.init()
        return apiPath
    }

    private fun putPath(path: String, methodPath: OpenApi3MethodPath) {
        if (containsKey(path)) {
            get(path)?.put(methodPath.jsonKey, methodPath.path)
        } else {
            put(path, mutableMapOf(methodPath.jsonKey to methodPath.path))
        }
    }

    fun get(path: String, init: OpenApi3Path.() -> Unit) {
        putPath(path, OpenApi3GetPath(initOpenApi3Path(init)))
    }

    fun put(path: String, init: OpenApi3Path.() -> Unit) {
        putPath(path, OpenApi3PutPath(initOpenApi3Path(init)))
    }

    fun post(path: String, init: OpenApi3Path.() -> Unit) {
        putPath(path, OpenApi3PostPath(initOpenApi3Path(init)))
    }

    fun delete(path: String, init: OpenApi3Path.() -> Unit) {
        putPath(path, OpenApi3DeletePath(initOpenApi3Path(init)))
    }

    fun patch(path: String, init: OpenApi3Path.() -> Unit) {
        putPath(path, OpenApi3PatchPath(initOpenApi3Path(init)))
    }

    fun head(path: String, init: OpenApi3Path.() -> Unit) {
        putPath(path, OpenApi3HeadPath(initOpenApi3Path(init)))
    }

    fun options(path: String, init: OpenApi3Path.() -> Unit) {
        putPath(path, OpenApi3OptionsPath(initOpenApi3Path(init)))
    }
}

data class OpenApi3Components(
        val schemas: Map<String, Any> = HashMap()
)

data class OpenApi3(
        var openapi: String = "3.0.0",
        var info: OpenApi3Info = OpenApi3Info(),
        var paths: OpenApi3Paths = OpenApi3Paths()
) {
    init {
        val module = SimpleModule()
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY)
        module.addSerializer(OpenApi3MediaType::class.java, OpenApi3MediaTypeSerializer())
        module.addSerializer(OpenApi3Components::class.java, OpenApi3ComponentsSerializer())
        mapper.registerModule(module)
    }

    val components: OpenApi3Components
        get() {
            val responseSchemas: Map<String, Any> = paths.values
                    .flatMap { it.values }
                    .flatMap { it.responses.values }
                    .flatMap { it.content.values }
                    .fold(mutableMapOf()) { m, o ->
                        m.put(o.clazz.simpleName, o.schemaJson.getJSONObject("schema"))
                        m
                    }

            val requestSchemas: Map<String, Any> = paths.values
                    .flatMap { it.values }
                    .mapNotNull { it.requestBody }
                    .flatMap { it.values }
                    .fold(mutableMapOf()) { m, o ->
                        m.put(o.clazz.simpleName, o.schemaJson.getJSONObject("schema"))
                        m
                    }

            return OpenApi3Components(responseSchemas.plus(requestSchemas))
        }

    fun info(init: OpenApi3Info.() -> Unit) {
        info.init()
    }

    fun paths(init: OpenApi3Paths.() -> Unit) {
        paths.init()
    }

    fun asJson(): JSONObject {
        val writeValueAsString = mapper.writeValueAsString(this)
        return JSONObject(writeValueAsString)
    }

    fun asFile(): File {
        val file = Files.createTempFile("openapi-", ".json").toFile()
        file.writeText(asJson().toString())
        file.deleteOnExit()
        return file
    }
}

fun openapi3(init: OpenApi3.() -> Unit): OpenApi3 {
    val openapi3 = OpenApi3()
    openapi3.init()
    return openapi3
}
