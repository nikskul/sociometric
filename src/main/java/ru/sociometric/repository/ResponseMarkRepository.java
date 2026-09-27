package ru.sociometric.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.sociometric.domain.ResponseMark;

import java.util.List;

public interface ResponseMarkRepository extends JpaRepository<ResponseMark, Long> {

    List<ResponseMark> findBySurveyId(Long surveyId);

    List<ResponseMark> findBySurveyIdAndRespondent(Long surveyId, String respondent);

    long countBySurveyId(Long surveyId);

    @Modifying
    @Query("delete from ResponseMark r where r.surveyId = :surveyId")
    void deleteBySurveyId(@Param("surveyId") Long surveyId);

    @Modifying
    @Query("delete from ResponseMark r where r.surveyId = :surveyId and r.respondent = :respondent")
    void deleteByRespondent(@Param("surveyId") Long surveyId, @Param("respondent") String respondent);
}
