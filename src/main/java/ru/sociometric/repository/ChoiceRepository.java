package ru.sociometric.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.sociometric.domain.Choice;
import ru.sociometric.domain.ChoiceKind;

import java.util.List;

public interface ChoiceRepository extends JpaRepository<Choice, Long> {

    List<Choice> findBySurveyIdOrderById(Long surveyId);

    List<Choice> findBySurveyIdAndKindOrderById(Long surveyId, ChoiceKind kind);

    long countBySurveyId(Long surveyId);

    long countBySurveyIdAndKind(Long surveyId, ChoiceKind kind);

    long countBySurveyIdAndRespondent(Long surveyId, String respondent);

    /** Все пары «кто — кого» конкретного вида, уже сохранённые в опросе. */
    @Query("select c.respondent || '>' || c.target from Choice c"
            + " where c.surveyId = :surveyId and c.kind = :kind")
    List<String> listPairs(Long surveyId, @Param("kind") ChoiceKind kind);

    @Modifying
    @Query("delete from Choice c where c.surveyId = :surveyId")
    void deleteBySurveyId(@Param("surveyId") Long surveyId);

    @Modifying
    @Query("delete from Choice c where c.surveyId = :surveyId and c.kind = :kind")
    void deleteBySurveyIdAndKind(@Param("surveyId") Long surveyId, @Param("kind") ChoiceKind kind);

    @Modifying
    @Query("delete from Choice c where c.surveyId = :surveyId and c.respondent = :respondent")
    void deleteByRespondent(@Param("surveyId") Long surveyId, @Param("respondent") String respondent);
}
