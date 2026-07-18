package com.dony.api.common.money;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CurrencyRepository extends JpaRepository<CurrencyEntity, String> {
    List<CurrencyEntity> findByEnabledTrue();
}
