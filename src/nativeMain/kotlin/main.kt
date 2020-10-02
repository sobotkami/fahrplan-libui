import io.ktor.client.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.features.json.serializer.*
import io.ktor.client.features.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import libui.ktx.*

@Serializable
data class Location(var id: Long, var name: String, var lon: Double, var lat: Double)

@Serializable
data class Departure(
    var name: String, var type: String, var boardId: Long, var stopId: Long, var stopName: String,
    @Serializable(with = LocalDateTimeSerializer::class)
    var dateTime: LocalDateTime, var track: String? = "", var detailsId: String
)

// As of 02.10.2020 there is no Serializer for LocalDateTime
// https://github.com/Kotlin/kotlinx-datetime/issues/37
object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder) = LocalDateTime.parse(decoder.decodeString())
}

// As of 02.10.2020 there is no LocalDateTime.format()
// https://github.com/Kotlin/kotlinx-datetime/issues/38
fun LocalDateTime.timeString(): String {
    val minute = minute.toString().padStart(2, '0')
    val hour = hour.toString().padStart(2, '0')

    return "$hour:$minute"
}

// As of 02.10.2020 there is no LocalDateTime.format()
// https://github.com/Kotlin/kotlinx-datetime/issues/38
fun LocalDateTime.dateString(): String {
    val day = dayOfMonth.toString().padStart(2, '0')
    val month = monthNumber.toString().padStart(2, '0')
    val year = year.toString().padStart(4, '0')

    return "$day.$month.$year"
}

val ApplicationDispatcher: CoroutineDispatcher = Dispatchers.Default

class ApplicationApi {
    private val client = HttpClient()

    var address = Url("https://api.deutschebahn.com/freeplan/v1/location/stuttgart")

    fun about(callback: (String) -> Unit) {
        GlobalScope.apply {
            launch(ApplicationDispatcher) {
                val result: String = client.get {
                    url(this@ApplicationApi.address.toString())
                }

                callback(result)
            }
        }
    }
}

fun createHttpClient() = HttpClient {
    install(JsonFeature) {
        serializer = KotlinxSerializer()
    }

    install(Logging) {
        level = LogLevel.INFO
    }
}

class FahrplanApi(private val client: HttpClient) {
    private val serverUrl = "https://api.deutschebahn.com/freeplan/v1"

    suspend fun locations(name: String) = client.get<List<Location>>("$serverUrl/location/$name")

    suspend fun departureBoard(id: Long, date: String) =
        client.get<List<Departure>>("$serverUrl/departureBoard/$id?date=${date}")

    suspend fun departureBoardDateTime(id: Long, date: String) =
        client.get<Departure>("$serverUrl/departureBoard/$id?date=$date")
}

suspend fun <T> fahrplan(callback: suspend (FahrplanApi) -> T): T {
    val client = createHttpClient()
    val api = FahrplanApi(client)
    val result = callback(api)
    try {
        // Close and wait for 3 seconds.
        withTimeout(3000) {
            client.close()
        }
    } catch (timeout: TimeoutCancellationException) {
        // Cancel after timeout
        client.cancel()
    }
    return result
}

fun serverErrorCatch(statusLabel: Label, callback: () -> Unit) {
    try {
        statusLabel.text = ""
        callback()
    } catch (e: ServerResponseException) {
        statusLabel.text = e.message.toString()
    }
}

private suspend fun TableView.searchStations(name: String) {
    fahrplan { api ->
        val stations = table.data as MutableList<Location> // TODO

        repeat(stations.size) {
            table.rowDeleted(0)
        }

        stations.clear()

        api.locations(name).forEachIndexed { index, location ->
            stations.add(index, location)
            table.rowInserted(index)
        }
    }
}

private suspend fun TableView.searchDepartures(id: Long, time: String) {
    fahrplan { api ->
        val departures = table.data as MutableList<Departure> // TODO

        repeat(departures.size) {
            table.rowDeleted(0)
        }

        departures.clear()

        api.departureBoard(id, time).forEachIndexed { index, departure ->
            departures.add(index, departure)
            table.rowInserted(index)
        }
    }
}

fun runUI() = appWindow(
    title = "Fahrplan",
    width = 1000,
    height = 600
) {
    var time = ""
    lateinit var stationsTable: TableView
    lateinit var departuresTable: TableView
    lateinit var statusLabel: Label
    val stations = mutableListOf<Location>()
    val departures = mutableListOf<Departure>()

    vbox {
        group("Search") { }.vbox {
            hbox {
                val testLabel = label("Async Test")
                button("Async Test") {
                    action {
                        val api = ApplicationApi()

                        api.about {
                            GlobalScope.apply {
                                launch(Dispatchers.Main) {
                                    testLabel.text = it
                                }
                            }
                        }
                    }
                }
            }
            hbox {
                stretchy = true

                searchfield {
                    action {
                        serverErrorCatch(statusLabel) {
                            runBlocking {
                                stationsTable.searchStations(value)
                            }
                        }
                    }
                }

                datetimepicker {
                    fun updateTime() {
                        time = textValue("%Y-%m-%dT%H:%M:%S")
                    }

                    value = Clock.System.now().toEpochMilliseconds()

                    updateTime()

                    action {
                        updateTime()
                    }
                }
            }
        }

        stretchy = true

        hbox {
            group("Stations") { stretchy = true }.vbox {
                stretchy = true

                stationsTable = tableview(stations) {
                    column("Name") {
                        button({ data[it].name }) {
                            serverErrorCatch(statusLabel) {
                                runBlocking {
                                    departuresTable.searchDepartures(data[it].id, time)
                                }
                            }
                        }
                    }

                    column("Id") {
                        label { data[it].id.toString() }
                    }

                    column("Location") {
                        label { "${data[it].lat}, ${data[it].lon}" }
                    }
                }
            }

            group("Departures") { stretchy = true }.vbox {
                stretchy = true

                departuresTable = tableview(departures) {
                    column("Date") {
                        label { data[it].dateTime.dateString() }
                    }

                    column("Time") {
                        label { data[it].dateTime.timeString() }
                    }

                    column("Type") {
                        label(Departure::type)
                    }

                    column("Track") {
                        label { data[it].track ?: "-" }
                    }

                    column("Name") {
                        label(Departure::name)
                    }
                }
            }
        }

        statusLabel = label("")
    }
}

fun main() {
    runUI()
}