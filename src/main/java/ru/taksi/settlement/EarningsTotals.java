package ru.taksi.settlement;

import java.math.BigDecimal;

/** Итоги водителя за период по одной валюте (результат агрегатного запроса). */
public record EarningsTotals(String currency, long rides, BigDecimal revenue, BigDecimal serviceFees,
                             BigDecimal parkFees, BigDecimal incomeTax, BigDecimal socialFees,
                             BigDecimal fuelCost, BigDecimal driverNet) {
}
