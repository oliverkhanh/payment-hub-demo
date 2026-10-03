package com.paymenthub.hub.infra;

import com.paymenthub.hub.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {
    Optional<Merchant> findByApiKey(String apiKey);
}
