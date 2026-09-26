package ru.sociometric.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.sociometric.domain.ExpertRating;

import java.util.List;

public interface ExpertRatingRepository extends JpaRepository<ExpertRating, Long> {

    List<ExpertRating> findBySurveyIdOrderByExpertAscIdAsc(Long surveyId);

    long countBySurveyId(Long surveyId);

    @Modifying
    @Query("delete from ExpertRating e where e.surveyId = :surveyId")
    void deleteBySurveyId(@Param("surveyId") Long surveyId);
}
