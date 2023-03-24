package io.askus.emailverifier.registration

import io.askus.emailverifier.registrationrequest.RegistrationRequestDataGateway
import java.util.*

class RegistrationConfirmationService(
    private val requestGateway: RegistrationRequestDataGateway,
    private val registrationGateway: RegistrationDataGateway,
) {
    fun confirm(email: String, confirmationCode: UUID): Boolean {
        val storedCode = requestGateway.find(email)

        val success = storedCode == confirmationCode

        if (success) {
            registrationGateway.save(email)
        }

        return success
    }
}
