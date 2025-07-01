package com.fever.marketplace.domain.port.out;

import java.time.LocalDateTime;

public interface SyncMetadataService {

    void updateSyncStatus(String status);

    void updateLastSyncTime(LocalDateTime syncTime);

    void updateEventCount(int count);

    String getSyncStatus();

    LocalDateTime getLastSyncTime();

    int getEventCount();
}
