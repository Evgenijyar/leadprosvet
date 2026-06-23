package ru.abs7.leadprosvet.service.bitrix;

public class BitrixRestException extends RuntimeException {

    public BitrixRestException(String message) {
        super(message);
    }

    public BitrixRestException(String message, Throwable cause) {
        super(message, cause);
    }
}
