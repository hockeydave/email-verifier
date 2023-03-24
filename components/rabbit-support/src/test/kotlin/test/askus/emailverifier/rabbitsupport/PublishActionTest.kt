package test.askus.emailverifier.rabbitsupport

import io.askus.emailverifier.rabbitsupport.*
import kotlinx.coroutines.runBlocking
import io.initialcapacity.emailverifier.rabbitsupport.*
import org.junit.After
import org.junit.Before
import test.askus.emailverifier.testsupport.assertMessageReceived
import java.net.URI
import kotlin.test.Test

/**
 * @author dpeterson modified to reuse channels as per guidance
 * https://www.cloudamqp.com/blog/part1-rabbitmq-best-practice.html
 */
class TestPublishAction {
    private val testQueue = RabbitQueue("test-queue", emptyMap<String, String>())
    private val testExchange = RabbitExchange(
        name = "test-exchange",
        type = "direct",
        routingKeyGenerator = { _: String -> "42" },
        bindingKey = "42",
    )
    private val factory = buildConnectionFactory(URI("amqp://localhost:5672"))
    private val channel = factory.newConnection().createChannel()

    @Before
    fun setUp() {
        factory.declareAndBind(testExchange, testQueue, channel)
    }

    @After
    fun tearDown() {
            channel.queueDelete(testQueue.name)
            channel.exchangeDelete(testExchange.name)
    }

    @Test
    fun testPublish() = runBlocking {
        val publishAction = publish(factory, testExchange, channel)

        publishAction("""{"some": "message"}""")

        factory.assertMessageReceived(testQueue, """{"some": "message"}""")
    }
}
