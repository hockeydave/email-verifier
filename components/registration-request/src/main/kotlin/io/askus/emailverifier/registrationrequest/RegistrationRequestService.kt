package io.askus.emailverifier.registrationrequest

import io.askus.emailverifier.rabbitsupport.PublishAction
import io.askus.serializationsupport.UUIDSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.*

typealias UuidProvider = () -> UUID

class RegistrationRequestService(
    private val gateway: RegistrationRequestDataGateway,
    private val publishNotification: PublishAction,
    private val uuidProvider: UuidProvider,
) {
    private val logger = LoggerFactory.getLogger(RegistrationRequestService::class.java)

    fun generateCodeAndPublish(email: String) {
        val confirmationCode = uuidProvider()
        gateway.save(email, confirmationCode)

        val message = Json.encodeToString(ConfirmationMessage(email, confirmationCode))

        logger.debug("publishing notification request {}", message)
        publishNotification(message)
    }
}

@Serializable
data class ConfirmationMessage(
    val email: String,
    @Serializable(with = UUIDSerializer::class)
    val confirmationCode: UUID,
)
