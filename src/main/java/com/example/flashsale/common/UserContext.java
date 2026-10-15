package com.example.flashsale.common;

public final class UserContext {

    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        USER_ID.set(userId);
    }

    public static Long getRequiredUserId() {
        Long userId = USER_ID.get();
        if (userId == null) {
            throw new IllegalStateException("Authenticated user is missing");
        }
        return userId;
    }

    public static void clear() {
        USER_ID.remove();
    }
}