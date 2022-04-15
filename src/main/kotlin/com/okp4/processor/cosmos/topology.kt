package com.okp4.processor.cosmos

import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.kstream.Branched
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Named
import org.apache.kafka.streams.kstream.Produced
import org.slf4j.LoggerFactory
import tendermint.types.BlockOuterClass
import java.util.*

/**
 * Simple Kafka Stream Processor that consumes a block on a topic and returns his transactions on another.
 */
fun topology(props: Properties): Topology {
    val logger = LoggerFactory.getLogger("com.okp4.processor.cosmos.topology")
    val topicIn = requireNotNull(props.getProperty("topic.in")) {
        "Option 'topic.in' was not specified."
    }
    val topicOut = requireNotNull(props.getProperty("topic.out")) {
        "Option 'topic.out' was not specified."
    }
    val topicError = requireNotNull(props.getProperty("topic.error")) {
        "Option 'topic.error' was not specified."
    }

    return StreamsBuilder().apply {
        stream(topicIn, Consumed.with(Serdes.String(), Serdes.ByteArray()).withName("input")).mapValues(
            { v ->
                Pair(v, kotlin.runCatching { BlockOuterClass.Block.parseFrom(v) })
            }, Named.`as`("block-deserialization")
            ).split().branch(
                { _, v -> v.second.isFailure },
                Branched.withConsumer { ks ->
                    ks.peek(
                        { k, v ->
                            v.second.onFailure {
                                logger.warn("Deserialization failed for block with key <$k> (block will be sent to topic $topicError): ${it.message}", it)
                            }
                        },
                        Named.`as`("log-deserialization-failure")
                    )
                        .mapValues({ pair -> pair.first }, Named.`as`("extract-original-bytearray")).to(
                            topicError, Produced.with(Serdes.String(), Serdes.ByteArray()).withName("error")
                        )
                    }
                ).defaultBranch(
                    Branched.withConsumer { ks ->
                        ks.mapValues(
                            { v ->
                                v.second.getOrThrow()
                            }, Named.`as`("extract-block")
                            ).peek(
                                { _, block -> logger.debug("→ block ${block.header.height} (${block.data.txsCount} txs)") },
                                Named.`as`("log-block-extraction")
                            ).flatMapValues(
                                { block ->
                                    block.data.txsList
                                }, Named.`as`("extract-transactions")
                                ).mapValues(
                                    { tx ->
                                        tx.toByteArray()
                                    }, Named.`as`("convert-transactions-to-bytearray")
                                    ).to(
                                        topicOut, Produced.with(Serdes.String(), Serdes.ByteArray()).withName("output")
                                    )
                                }
                            )
                        }.build()
                    }
                    