package io.initialcapacity.emailverifier.notificationserver

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import io.askus.emailverifier.notification.Emailer
import io.askus.emailverifier.notification.NotificationDataGateway
import io.askus.emailverifier.notification.Notifier
import io.askus.emailverifier.rabbitsupport.*
import io.initialcapacity.emailverifier.rabbitsupport.*
import io.askus.serializationsupport.UUIDSerializer
import io.ktor.client.*
import io.ktor.client.engine.java.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URL
import java.util.*

class App

private val logger = LoggerFactory.getLogger(App::class.java)

fun main() = runBlocking {
    val rabbitUrl = System.getenv("RABBIT_URL")?.let(::URI)
        ?: throw RuntimeException("Please set the RABBIT_URL environment variable")
    val sendgridUrl = System.getenv("SENDGRID_URL")?.let(::URL)
        ?: throw RuntimeException("Please set the SENDGRID_URL environment variable")
    val sendgridApiKey = System.getenv("SENDGRID_API_KEY")
        ?: throw RuntimeException("Please set the SENDGRID_API_KEY environment variable")
    val fromAddress = System.getenv("FROM_ADDRESS")
        ?: throw RuntimeException("Please set the FROM_ADDRESS environment variable")
    val databaseUrl = System.getenv("DATABASE_URL")
        ?: throw RuntimeException("Please set the DATABASE_URL environment variable")
    val connectionFactory = buildConnectionFactory(rabbitUrl)

    val dbConfig = DatabaseConfiguration(databaseUrl)
    val args: Map<String, String> = mapOf("x-queue-type" to "quorum")
    //val args = emptyMap<String, String>()

    start(
        sendgridUrl = sendgridUrl,
        sendgridApiKey = sendgridApiKey,
        fromAddress = fromAddress,
        connectionFactory = connectionFactory,
        registrationNotificationExchange = RabbitExchange(
            name = "registration-notification-exchange",
            type = "direct",
            routingKeyGenerator = { _: String -> "42" },
            bindingKey = "42",
        ),

        registrationNotificationQueue = RabbitQueue("registration-notification", args),
        dbConfig = dbConfig,
    )
}

suspend fun start(
    sendgridUrl: URL,
    sendgridApiKey: String,
    fromAddress: String,
    connectionFactory: ConnectionFactory,
    registrationNotificationExchange: RabbitExchange,
    registrationNotificationQueue: RabbitQueue,
    dbConfig: DatabaseConfiguration,
) {
    val notifier = createNotifier(sendgridUrl, sendgridApiKey, fromAddress, dbConfig)
    val channel = connectionFactory.newConnection().createChannel()
    connectionFactory.declareAndBind(
        exchange = registrationNotificationExchange,
        queue = registrationNotificationQueue,
        channel
    )

    logger.info("listening for registration notifications")
    listenForNotificationRequests(connectionFactory, notifier, registrationNotificationQueue, channel)
}

private fun createNotifier(
    sendgridUrl: URL,
    sendgridApiKey: String,
    fromAddress: String,
    dbConfig: DatabaseConfiguration,
): Notifier {
    val emailer = Emailer(
        client = HttpClient(Java) { expectSuccess = false },
        sendgridUrl = sendgridUrl,
        sendgridApiKey = sendgridApiKey,
        fromAddress = fromAddress,
    )
    val gateway = NotificationDataGateway(dbConfig.db)
    return Notifier(gateway, emailer)
}

private suspend fun listenForNotificationRequests(
    connectionFactory: ConnectionFactory,
    notifier: Notifier,
    registrationNotificationQueue: RabbitQueue,
    channel: Channel
) {


    listen(queue = registrationNotificationQueue, channel = channel) {
        val message = Json.decodeFromString<NotificationMessage>(it)
        logger.debug("received registration notification {}", message)
        notifier.notify(message.email, message.confirmationCode)
    }
}

@Serializable
private data class NotificationMessage(
    val email: String,
    @Serializable(with = UUIDSerializer::class)
    val confirmationCode: UUID,
)
