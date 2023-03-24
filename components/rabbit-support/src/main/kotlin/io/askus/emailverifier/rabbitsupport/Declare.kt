package io.askus.emailverifier.rabbitsupport

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConnectionFactory
import java.net.URI

/**
 * @author dpeterson modified to reuse channels as per guidance
 * https://www.cloudamqp.com/blog/part1-rabbitmq-best-practice.html
 */
fun buildConnectionFactory(rabbitUrl: URI): ConnectionFactory =
    ConnectionFactory().apply {
        setUri(rabbitUrl)
    }

//fun ConnectionFactory.declareAndBind(exchange: RabbitExchange, queue: RabbitQueue): Unit =
//    useChannel {
//        it.exchangeDeclare(exchange.name, exchange.type, false, false, null)
//        var durable = false;
//        if("quorum" == queue.args["x-queue-type"])
//            durable = true;
//
//        it.queueDeclare(queue.name, durable, false, false, queue.args)
//        it.queueBind(queue.name, exchange.name, exchange.bindingKey)
//    }
fun ConnectionFactory.declareAndBind(exchange: RabbitExchange, queue: RabbitQueue, channel: Channel)
     {
        channel.exchangeDeclare(exchange.name, exchange.type, false, false, null)
        var durable = false;
        if("quorum" == queue.args["x-queue-type"])
            durable = true;

         channel.queueDeclare(queue.name, durable, false, false, queue.args)
         channel.queueBind(queue.name, exchange.name, exchange.bindingKey)
    }

fun <T> ConnectionFactory.useChannel(block: (Channel) -> T): T =

    newConnection().use { connection ->
        connection.createChannel()!!.use(block)
    }
