package com.paymenthub.bank.infra.security;

import org.springframework.http.HttpHeaders;

import java.net.URI;

public record SignedWebhook(URI url, HttpHeaders headers, byte[] body) { }
