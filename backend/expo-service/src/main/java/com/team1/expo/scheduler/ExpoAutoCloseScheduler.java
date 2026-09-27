package com.team1.expo.scheduler;

import com.team1.expo.client.RoundClient;
import com.team1.expo.domain.expo.ExpoRepository;
import com.team1.expo.domain.expo.ExpoStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ExpoAutoCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpoAutoCloseScheduler.class);

    private final RoundClient roundClient;
    private final ExpoRepository expoRepository;
    private final Clock clock;

    @Value("${scheduler.auto-close.limit:500}")
    private int limit;

    @Scheduled(cron = "${scheduler.auto-close.cron}")
    @Transactional
    public void closeFinishedExpos() {
        Instant now = clock.instant();
        LocalDateTime closedAt = LocalDateTime.ofInstant(now, clock.getZone());

        // 이미 CLOSED 인 박람회도 계속 돌아오므로, 한 번에 앞쪽 limit 개만 보면 그 뒤는 영영 마감되지 않는다.
        // 마지막 id 를 커서로 넘기며 끝까지 훑는다.
        long afterExpoId = 0L;
        int scanned = 0;
        int updated = 0;
        while (true) {
            List<Long> expoIds;
            try {
                expoIds = roundClient.finishedExpoIds(now, afterExpoId, limit);
            } catch (Exception e) {
                log.warn("[AutoClose] finishedExpoIds 호출 실패, 이번 주기 나머지 건너뜀 afterExpoId={}", afterExpoId, e);
                break;
            }
            if (expoIds.isEmpty()) {
                break;
            }

            updated += expoRepository.closeByIds(expoIds, ExpoStatus.PUBLISHED, ExpoStatus.CLOSED, closedAt);
            scanned += expoIds.size();

            if (expoIds.size() < limit) {
                break;
            }
            afterExpoId = expoIds.get(expoIds.size() - 1);
        }

        if (scanned > 0) {
            log.info("[AutoClose] 마감 처리 완료 대상={} 실제변경={} limit={}", scanned, updated, limit);
        }
    }
}
