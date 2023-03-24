package io.initialcapacity.emailverifier.registrationserver

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import io.askus.emailverifier.rabbitsupport.*
import io.initialcapacity.emailverifier.rabbitsupport.*
import io.askus.emailverifier.registration.RegistrationConfirmationService
import io.askus.emailverifier.registration.RegistrationDataGateway
import io.askus.emailverifier.registration.register
import io.askus.emailverifier.registrationrequest.RegistrationRequestDataGateway
import io.askus.emailverifier.registrationrequest.RegistrationRequestService
import io.askus.emailverifier.registrationrequest.UuidProvider
import io.askus.emailverifier.registrationrequest.registrationRequest
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.*

/**
 * @author dpeterson modified to reuse channels as per guidance
 * https://www.cloudamqp.com/blog/part1-rabbitmq-best-practice.html
 * 2.  Use Quorum queue type
 */

class App

private val logger = LoggerFactory.getLogger(App::class.java)

fun main(): Unit = runBlocking {
    val port = System.getenv("PORT")?.toInt() ?: 8081
    val rabbitUrl = System.getenv("RABBIT_URL")?.let(::URI)
        ?: throw RuntimeException("Please set the RABBIT_URL environment variable")
    val databaseUrl = System.getenv("DATABASE_URL")
        ?: throw RuntimeException("Please set the DATABASE_URL environment variable")

    val dbConfig = DatabaseConfiguration(databaseUrl)

    val connectionFactory = buildConnectionFactory(rabbitUrl)
    val registrationRequestGateway = RegistrationRequestDataGateway(dbConfig.db)
    val registrationGateway = RegistrationDataGateway(dbConfig.db)

    val registrationNotificationExchange = RabbitExchange(
        name = "registration-notification-exchange",
        type = "direct",
        routingKeyGenerator = { _: String -> "42" },
        bindingKey = "42",
    )
    val args: Map<String, String> = mapOf("x-queue-type" to "quorum")
    //val args = emptyMap<String, String>()
    //val args: Map<String, String> = mapOf("queue-mode" to "lazy")
    val registrationNotificationQueue = RabbitQueue("registration-notification", args)

    /**
     * Weights
     *
     * When a queue is bound to a Consistent Hash exchange, the binding key is a number-as-a-string which indicates the
     * binding weight: the number of buckets (sections of the range) that will be associated with the target queue.
     *
     * In most environments, using one bucket per binding (and thus queue) is highly recommended as it is the simplest
     * way to achieve reasonably even balancing.
     */
    val registrationRequestExchange = RabbitExchange(
        // TODO - rename the request exchange (since you've already declared a direct exchange under the current name)
        name = "registration-CH-request-exchange",
        // TODO - use a consistent hash exchange (x-consistent-hash)
        type = "x-consistent-hash",
        // TODO - calculate a routing key based on message content
        routingKeyGenerator = @Suppress("UNUSED_ANONYMOUS_PARAMETER") { message: String -> randomID() },
        // TODO - read the binding key from the environment
        //bindingKey = "42",
        bindingKey = System.getenv("BINDING_KEY") ?: "1"
    )

    // TODO - read the queue name from the environment
    val queueName = System.getenv("QUEUE_NAME") ?: "registration-CH_request"

    val registrationRequestQueue = RabbitQueue(queueName, args)

    val connection = connectionFactory.newConnection()
    val channel = connection.createChannel()

    connectionFactory.declareAndBind(
        exchange = registrationNotificationExchange,
        queue = registrationNotificationQueue,
        channel = channel
    )
    connectionFactory.declareAndBind(
        exchange = registrationRequestExchange,
        queue = registrationRequestQueue,
        channel = channel
    )


    listenForRegistrationRequests(
        connectionFactory,
        registrationRequestGateway,
        registrationNotificationExchange,
        registrationRequestQueue,
        channel
    )
    registrationServer(
        port,
        registrationRequestGateway,
        registrationGateway,
        connectionFactory,
        registrationRequestExchange,
        channel
    ).start()
}

private fun randomID(): String = List(16) {
    Random().nextInt(1000)
}.joinToString("")

fun registrationServer(
    port: Int,
    registrationRequestGateway: RegistrationRequestDataGateway,
    registrationGateway: RegistrationDataGateway,
    connectionFactory: ConnectionFactory,
    registrationRequestExchange: RabbitExchange,
    channel: Channel
) = embeddedServer(
    factory = Jetty,
    port = port,
    module = {
        module(
            registrationRequestGateway,
            registrationGateway,
            connectionFactory,
            registrationRequestExchange,
            channel
        )
    }
)

fun Application.module(
    registrationRequestGateway: RegistrationRequestDataGateway,
    registrationGateway: RegistrationDataGateway,
    connectionFactory: ConnectionFactory,
    registrationRequestExchange: RabbitExchange,
    channel: Channel,
) {
    install(Resources)
    install(CallLogging)
    install(AutoHeadResponse)
    install(ContentNegotiation) {
        json()
    }

    val publishRequest = publish(connectionFactory, registrationRequestExchange, channel)

    install(Routing) {
        info()
        registrationRequest(publishRequest)
        register(RegistrationConfirmationService(registrationRequestGateway, registrationGateway))
    }
}

fun CoroutineScope.listenForRegistrationRequests(
    connectionFactory: ConnectionFactory,
    registrationRequestDataGateway: RegistrationRequestDataGateway,
    registrationNotificationExchange: RabbitExchange,
    registrationRequestQueue: RabbitQueue,
    channel: Channel,
    uuidProvider: UuidProvider = { UUID.randomUUID() }
) {
    val publishNotification = publish(connectionFactory, registrationNotificationExchange, channel)

    val registrationRequestService = RegistrationRequestService(
        gateway = registrationRequestDataGateway,
        publishNotification = publishNotification,
        uuidProvider = uuidProvider,
    )

    launch {
        logger.info("listening for registration requests")
        //val channel = connectionFactory.newConnection().createChannel()
        listen(queue = registrationRequestQueue, channel = channel) { email ->
            logger.debug("received registration request for {}", email)
            registrationRequestService.generateCodeAndPublish(email)
        }
    }
}
