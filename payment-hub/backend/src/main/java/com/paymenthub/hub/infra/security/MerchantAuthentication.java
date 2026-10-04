package com.paymenthub.hub.infra.security;

import com.paymenthub.hub.domain.Merchant;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

public class MerchantAuthentication extends AbstractAuthenticationToken {
    private final Merchant merchant;

    public MerchantAuthentication(Merchant merchant) {
        super(AuthorityUtils.createAuthorityList("ROLE_MERCHANT"));
        this.merchant = merchant;
        setAuthenticated(true);
    }

    @Override public Object getPrincipal()   { return merchant; }

    @Override public Object getCredentials()  { return null; }
    @Override public String getName()         { return merchant.getMerchantRef(); }

    public Merchant merchant() { return merchant; }
}
