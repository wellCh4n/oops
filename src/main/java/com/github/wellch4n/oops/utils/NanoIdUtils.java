package com.github.wellch4n.oops.utils;

import java.security.SecureRandom;

public class NanoIdUtils {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final int DEFAULT_SIZE = 24;

    public static String generate() {
        return com.aventrix.jnanoid.jnanoid.NanoIdUtils.randomNanoId(RANDOM, ALPHABET, DEFAULT_SIZE);
    }
}
