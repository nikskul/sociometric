package ru.sociometric.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.sociometric.domain.Survey;

import java.util.List;

public interface SurveyRepository extends JpaRepository<Survey, Long> {

    List<Survey> findAllByOrderByIdDesc();
}
