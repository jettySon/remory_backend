package com.remory.backend.account;

public record Account(String id, String email, boolean hasPassword, boolean googleLinked) {}
