package com.paymenthub.merchant.domain;

import com.paymenthub.merchant.infra.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CatalogService {
    private final ProductRepository products;

    public CatalogService(ProductRepository products) {
        this.products = products;
    }

    @Transactional(readOnly = true)
    public List<Product> listActive() {
        return products.findByActiveTrueOrderById();
    }
}
