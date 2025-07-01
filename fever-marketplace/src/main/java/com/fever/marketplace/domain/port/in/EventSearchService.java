package com.fever.marketplace.domain.port.in;

import com.fever.marketplace.domain.model.Event;
import java.time.LocalDateTime;
import java.util.List;

public interface EventSearchService {

    List<Event> searchEvents(LocalDateTime startsAt, LocalDateTime endsAt);
}
