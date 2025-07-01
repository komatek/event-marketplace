package com.fever.marketplace.application;

import com.fever.marketplace.domain.model.Event;
import java.time.LocalDateTime;
import java.util.List;

public interface FindEvents {

    List<Event> execute(LocalDateTime startsAt, LocalDateTime endsAt);

}
