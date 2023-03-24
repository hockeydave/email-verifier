package io.askus.emailverifier.rabbitsupport

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Delivery
import kotlinx.coroutines.runBlocking

/**
 * TODO-performance improvement
 * @author dpeterson
 * Change queue from default classic to quorum
 */
data class RabbitQueue(val name: String, val args: Map<String, String>)


fun listen(channel: Channel, queue: RabbitQueue, handler: suspend (String) -> Unit): String {
    val delivery = { _: String, message: Delivery -> runBlocking { handler(message.body.decodeToString()) } }
    val cancel = { _: String -> }

    return channel.basicConsume(queue.name, true, delivery, cancel)
}
