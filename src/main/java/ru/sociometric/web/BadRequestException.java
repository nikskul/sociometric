package ru.sociometric.web;

/**
 * Ошибка, которую показываем пользователю как понятное сообщение (HTTP 400).
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
