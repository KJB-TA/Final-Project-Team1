package com.team1.reservation.calendar.service;

import com.team1.reservation.calendar.dto.ScheduleConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemini 가 실패했을 때 쓰는 폴백이다. 여기서 시각을 틀리면 시간 조건이 사실상 무시된다. */
class RegexConstraintParserTest {

    @ParameterizedTest(name = "\"{0}\" → {1}시")
    @CsvSource({
            "오전 1시 이후, 1",
            "오전 11시 이후, 11",
            "오전 12시 이후, 0",
            "오후 1시 이후, 13",
            "오후 3시 이후, 15",
            "오후 11시 이후, 23",
            "오후 12시 이후, 12",
    })
    @DisplayName("오전·오후 N시 이후를 24시간제 시작 시각으로 바꾼다")
    void convertsToHour24(String constraint, int expectedHour) {
        ScheduleConstraint parsed = RegexConstraintParser.tryParse(constraint);

        assertThat(parsed).isNotNull();
        assertThat(parsed.mainStartHourKst()).isEqualTo(expectedHour);
    }

    @Test
    @DisplayName("문장 중간에 있어도 찾고, 범위 밖 시각이나 형식이 다르면 해석하지 않는다")
    void findsInSentenceAndRejectsOthers() {
        assertThat(RegexConstraintParser.tryParse("주말 오후 2시 이후로 보고 싶어요").mainStartHourKst()).isEqualTo(14);
        assertThat(RegexConstraintParser.tryParse("오후 13시 이후")).isNull();
        assertThat(RegexConstraintParser.tryParse("3시 이후")).isNull();
        assertThat(RegexConstraintParser.tryParse("   ")).isNull();
    }
}
