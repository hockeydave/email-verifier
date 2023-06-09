package test.initialcapacity.emailverifier.registrationserver

import io.askus.emailverifier.rabbitsupport.RabbitExchange
import io.askus.emailverifier.rabbitsupport.buildConnectionFactory
import io.askus.emailverifier.registration.RegistrationDataGateway
import io.askus.emailverifier.registrationrequest.RegistrationRequestDataGateway
import io.initialcapacity.emailverifier.registrationserver.registrationServer
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import java.net.URI
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * @author dpeterson modified to reuse channels as per guidance
 * https://www.cloudamqp.com/blog/part1-rabbitmq-best-practice.html
 */
class RegisterTest {
    private val db by lazy {
        Database.connect(
            url = "jdbc:postgresql://localhost:5555/registration_test?user=emailverifier&password=emailverifier"
        )
    }
    private val requestGateway = RegistrationRequestDataGateway(db)
    private val registrationGateway = RegistrationDataGateway(db)


    private val connectionFactory = buildConnectionFactory(URI("amqp://localhost:5672"))
    val connection = connectionFactory.newConnection()
    val channel = connection.createChannel()
    private val requestExchange = RabbitExchange(
        name = "test-request-exchange",
        type = "direct",
        routingKeyGenerator = { _: String -> "42" },
        bindingKey = "42",
    )
    private val confirmationCode = "cccccccc-1d21-442e-8fc0-a2259ec09190"

    private val regServer = registrationServer(
        port = 9120,
        registrationRequestGateway = requestGateway,
        registrationGateway = registrationGateway,
        connectionFactory = connectionFactory,
        registrationRequestExchange = requestExchange,
        channel = channel
    )

    private val client = HttpClient(Java) {
        expectSuccess = false
    }

    @Before
    fun setUp() {
        transaction(db) {
            exec("delete from registration_requests")
            exec("delete from registrations")
        }
        requestGateway.save("pickles@example.com", UUID.fromString(confirmationCode))
        regServer.start(wait = false)
    }

    @After
    fun tearDown() {
        regServer.stop(50, 50)
    }

    @Test
    fun testRegister(): Unit = runBlocking {
        val status = client.post("http://localhost:9120/register") {
            headers {
                contentType(ContentType.Application.Json)
                setBody("""{"email": "pickles@example.com", "confirmationCode": "$confirmationCode"}""")
            }
        }.status

        assertTrue(status.isSuccess())

        val storedEmail = transaction(db) {
            exec("select email from registrations where email = 'pickles@example.com'") {
                it.next()
                it.getString("email")
            }
        }

        assertEquals("pickles@example.com", storedEmail)
    }

    @Test
    fun testConfirmationWrongCode(): Unit = runBlocking {
        val status = client.post("http://localhost:9120/confirmation") {
            headers {
                contentType(ContentType.Application.Json)
                setBody(
                    """{"email": "pickles@example.com", "confirmationCode": "00000000-1d21-442e-8fc0-a2259ec09190"}"""
                )
            }
        }.status

        assertFalse(status.isSuccess())

        val resultExists = transaction(db) {
            exec("select email from registrations where email = 'pickles@example.com'") {
                it.next()
            }
        }

        assertFalse(resultExists!!)
    }
}
