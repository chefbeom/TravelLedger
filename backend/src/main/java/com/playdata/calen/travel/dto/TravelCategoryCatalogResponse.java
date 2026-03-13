package com.playdata.calen.travel.dto;

import java.util.List;

public record TravelCategoryCatalogResponse(
        List<String> planStatuses,
        List<String> budgetCategories,
        List<String> expenseCategories,
        List<String> memoryCategories,
        List<String> countries,
        List<String> regions,
        List<String> places
) {
}
