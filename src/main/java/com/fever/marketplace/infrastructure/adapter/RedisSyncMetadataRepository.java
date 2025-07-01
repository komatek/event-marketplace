package com.fever.marketplace.infrastructure.adapter;

import com.fever.marketplace.domain.port.out.SyncMetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Repository
public class RedisSyncMetadataRepository implements SyncMetadataService {

    private static final Logger logger = LoggerFactory.getLogger(RedisSyncMetadataRepository.class);

    private static final String LAST_SYNC_KEY = "fever:sync:last_sync";
    private static final String SYNC_STATUS_KEY = "fever:sync:status";
    private static final String EVENT_COUNT_KEY = "fever:sync:event_count";
    private static final long METADATA_TTL_HOURS = 24;

    private final RedisTemplate<String, Object> redisTemplate;

    public RedisSyncMetadataRepository(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void updateSyncStatus(String status) {
        try {
            redisTemplate.opsForValue().set(SYNC_STATUS_KEY, status, METADATA_TTL_HOURS, TimeUnit.HOURS);
            logger.debug("Updated sync status: {}", status);
        } catch (Exception e) {
            logger.error("Failed to update sync status", e);
        }
    }

    @Override
    public void updateLastSyncTime(LocalDateTime syncTime) {
        try {
            redisTemplate.opsForValue().set(LAST_SYNC_KEY, syncTime.toString(), METADATA_TTL_HOURS, TimeUnit.HOURS);
            logger.debug("Updated last sync time: {}", syncTime);
        } catch (Exception e) {
            logger.error("Failed to update last sync time", e);
        }
    }

    @Override
    public void updateEventCount(int count) {
        try {
            redisTemplate.opsForValue().set(EVENT_COUNT_KEY, count, METADATA_TTL_HOURS, TimeUnit.HOURS);
            logger.debug("Updated event count: {}", count);
        } catch (Exception e) {
            logger.error("Failed to update event count", e);
        }
    }

    @Override
    public String getSyncStatus() {
        try {
            Object status = redisTemplate.opsForValue().get(SYNC_STATUS_KEY);
            return status != null ? status.toString() : "UNKNOWN";
        } catch (Exception e) {
            logger.error("Failed to get sync status", e);
            return "ERROR";
        }
    }

    @Override
    public LocalDateTime getLastSyncTime() {
        try {
            Object lastSync = redisTemplate.opsForValue().get(LAST_SYNC_KEY);
            if (lastSync != null) {
                return LocalDateTime.parse(lastSync.toString());
            }
        } catch (Exception e) {
            logger.error("Failed to get last sync time", e);
        }
        return null;
    }

    @Override
    public int getEventCount() {
        try {
            Object count = redisTemplate.opsForValue().get(EVENT_COUNT_KEY);
            if (count != null) {
                if (count instanceof Integer) {
                    return (Integer) count;
                }
                return Integer.parseInt(count.toString());
            }
        } catch (Exception e) {
            logger.error("Failed to get event count", e);
        }
        return 0;
    }
}
