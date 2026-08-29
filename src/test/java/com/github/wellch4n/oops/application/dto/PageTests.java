package com.github.wellch4n.oops.application.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageTests {

    @Test
    void computesTotalPagesByCeiling() {
        Page<String> page = Page.of(21, List.of("a"), 10);
        assertEquals(3, page.totalPages());
        assertEquals(21, page.total());
        assertEquals(10, page.size());
    }

    @Test
    void exactMultipleHasNoExtraPage() {
        assertEquals(2, Page.of(20, List.of(), 10).totalPages());
    }

    @Test
    void emptyResultHasZeroPages() {
        assertEquals(0, Page.of(0, List.of(), 10).totalPages());
    }

    @Test
    void zeroSizeYieldsZeroPagesWithoutDivideByZero() {
        assertEquals(0, Page.of(5, List.of(), 0).totalPages());
    }
}
