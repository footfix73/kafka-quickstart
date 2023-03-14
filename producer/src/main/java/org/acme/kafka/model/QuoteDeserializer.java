package org.acme.kafka.model;

import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.acme.kafka.model.Quote;

public class QuoteDeserializer extends ObjectMapperDeserializer<Quote> {
    public QuoteDeserializer() {
        super(Quote.class);
    }
}
