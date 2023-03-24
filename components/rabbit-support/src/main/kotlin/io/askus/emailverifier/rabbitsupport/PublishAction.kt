package io.askus.emailverifier.rabbitsupport

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.MessageProperties

typealias PublishAction = (String) -> Unit
data class RabbitExchange(
    val name: String,
    val type: String,
    val routingKeyGenerator: (String) -> String,
    val bindingKey: String,
)

/** TODO Performance improvements
 * @author dpeterson
 * https://www.cloudamqp.com/blog/part2-rabbitmq-best-practice-for-high-performance.html
 * Use transient messages
 *
 * 1. Persistent messages are written to disk as soon as they reach the queue, which affects throughput.
 * Use transient messages for the fastest throughput.
 * Use MESSAGE_PROPERTIES.BASIC instead of PERSISTENT_BASIC

 *  2.  modified to reuse channels as per guidance
 *  * https://www.cloudamqp.com/blog/part1-rabbitmq-best-practice.html

 */
fun publish(factory: ConnectionFactory, exchange: RabbitExchange, channel: Channel): PublishAction = fun(message: String) =
        channel.basicPublish(exchange.name, exchange.routingKeyGenerator(message), MessageProperties.BASIC, message.toByteArray())

