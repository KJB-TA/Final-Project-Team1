package com.team1.recommendation.preference.repository;

import com.team1.recommendation.preference.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    /**
     * 벌크 삭제다. 파생 삭제(deleteAllByUserId)는 삭제를 flush 까지 미루는데, IDENTITY 저장은
     * 즉시 INSERT 되므로 같은 값을 다시 저장하면 옛 행이 남은 채로 들어가 UNIQUE 에 걸린다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from UserPreference p where p.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    List<UserPreference> findByUserId(Long userId);
}
