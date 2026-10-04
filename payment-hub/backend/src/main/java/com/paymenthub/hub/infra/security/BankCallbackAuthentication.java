package com.paymenthub.hub.infra.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

public class BankCallbackAuthentication extends AbstractAuthenticationToken {

    private final String bankCode;

    public BankCallbackAuthentication(String bankCode) {
        super(AuthorityUtils.createAuthorityList("ROLE_BANK"));
        this.bankCode = bankCode;
        setAuthenticated(true);
    }

    @Override public Object getPrincipal()  { return bankCode; }

    @Override public Object getCredentials() { return null; }
    @Override public String getName()        { return bankCode; }

    public String bankCode() { return bankCode; }
}
