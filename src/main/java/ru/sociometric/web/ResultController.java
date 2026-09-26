package ru.sociometric.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.sociometric.dto.ResultResponse;
import ru.sociometric.service.ResultService;

/**
 * Расчёт результатов социометрии.
 */
@RestController
@RequestMapping("/api/surveys/{id}")
public class ResultController {

    private final ResultService results;

    public ResultController(ResultService results) {
        this.results = results;
    }

    @GetMapping("/result")
    public ResultResponse result(@PathVariable Long id) {
        return results.calculate(id);
    }
}
