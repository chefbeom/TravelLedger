package com.playdata.calen.ledger.application.port.out;

import com.playdata.calen.ledger.domain.model.LedgerPreparationData.Rate;
import java.time.LocalDate;

public interface LedgerExchangeRatePort { Rate requiredRate(String currency, LocalDate date); }
