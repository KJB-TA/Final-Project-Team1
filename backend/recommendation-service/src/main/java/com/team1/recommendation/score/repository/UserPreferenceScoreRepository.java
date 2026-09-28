package com.team1.recommendation.score.repository;

import com.team1.recommendation.score.entity.ScoreSource;
import com.team1.recommendation.score.entity.UserPreferenceScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserPreferenceScoreRepository extends JpaRepository<UserPreferenceScore, Long> {

    List<UserPreferenceScore> findByUserId(Long userId);

    Optional<UserPreferenceScore> findByUserIdAndTagValueAndSource(Long userId, String tagValue, ScoreSource source);

    /** 벌크 삭제다. 이유는 UserPreferenceRepository.deleteAllByUserId 와 같다 - 곧바로 같은 태그를 다시 저장한다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserPreferenceScore s where s.userId = :userId and s.source = :source")
    void deleteByUserIdAndSource(@Param("userId") Long userId, @Param("source") ScoreSource source);

    List<UserPreferenceScore> findByTagValueAndScoreGreaterThan(String tagValue, double minScore);
}
