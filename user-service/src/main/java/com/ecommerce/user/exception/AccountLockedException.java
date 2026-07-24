package com.ecommerce.user.exception;

public class AccountLockedException extends RuntimeException {
    public AccountLockedException() {
        super("Account temporarily locked due to too many failed login attempts. Try again later.");
    }
}
