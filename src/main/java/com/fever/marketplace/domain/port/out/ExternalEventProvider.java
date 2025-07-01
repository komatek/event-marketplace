package com.fever.marketplace.domain.port.out;

import com.fever.marketplace.domain.model.Event;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ExternalEventProvider {

    CompletableFuture<List<Event>> fetchOnlineEvents();

}
