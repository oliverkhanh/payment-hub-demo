package com.paymenthub.merchant.api;

import com.paymenthub.merchant.api.dto.ProductResponse;
import com.paymenthub.merchant.domain.CatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/products")
public class ProductController {

    private final CatalogService catalog;

    public ProductController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<ProductResponse> list() {
        return catalog.listActive().stream().map(ProductResponse::from).toList();
    }
}
