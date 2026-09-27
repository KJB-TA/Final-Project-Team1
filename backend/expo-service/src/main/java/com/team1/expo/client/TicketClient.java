package com.team1.expo.client;

import java.util.Map;

public interface TicketClient {

    /** GET /internal/v1/tickets/checkin-summary?expoId= — 회차별 입장 인원. 조회 실패면 null(부분 실패 허용). */
    Map<Long, Long> checkedInByRound(Long expoId);
}
