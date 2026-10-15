package com.example.flashsale.common;

import org.springframework.dao.DuplicateKeyException;

import java.util.Locale;

public final class DuplicateKeyResolver {

    private DuplicateKeyResolver() {
    }

    public static DuplicateKey resolve(DuplicateKeyException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return DuplicateKey.UNKNOWN;
        }

        String normalizedMessage = message.toLowerCase(Locale.ROOT);
        for (DuplicateKey key : DuplicateKey.values()) {
            if (key != DuplicateKey.UNKNOWN && key.matches(normalizedMessage)) {
                return key;
            }
        }
        return DuplicateKey.UNKNOWN;
    }

    public enum DuplicateKey {
        ACTIVITY_USER("uk_activity_user", "activity_id, user_id"),
        ORDER_NO("uk_order_no", "order_no"),
        UNKNOWN();

        private final String[] markers;

        DuplicateKey(String... markers) {
            this.markers = markers;
        }

        private boolean matches(String message) {
            for (String marker : markers) {
                if (message.contains(marker)) {
                    return true;
                }
            }
            return false;
        }
    }
}