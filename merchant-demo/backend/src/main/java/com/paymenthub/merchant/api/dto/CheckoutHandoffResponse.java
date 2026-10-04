package com.paymenthub.merchant.api.dto;

public record CheckoutHandoffResponse(String orderCode, String paymentRef,
                                      String checkoutUrl, boolean reused) { }
