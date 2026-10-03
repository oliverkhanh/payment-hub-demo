package com.paymenthub.hub.infra.security;

import com.paymenthub.hub.domain.Merchant;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

/**
 * Danh tính merchant sau khi verify HMAC.
 * Cùng khuôn mẫu OAuth2ClientAuthenticationToken của Inc 1: filter đặt token này vào
 * SecurityContextHolder, tầng dưới lấy bằng @AuthenticationPrincipal — KHÔNG đọc lại header.
 */
public class MerchantAuthentication extends AbstractAuthenticationToken {

    private final Merchant merchant;

    public MerchantAuthentication(Merchant merchant) {
        super(AuthorityUtils.createAuthorityList("ROLE_MERCHANT"));
        this.merchant = merchant;
        setAuthenticated(true);
    }

    @Override public Object getPrincipal()   { return merchant; }
    /** Secret KHÔNG được giữ trong Authentication — tránh lọt vào log/heap dump. */
    @Override public Object getCredentials()  { return null; }
    @Override public String getName()         { return merchant.getMerchantRef(); }

    public Merchant merchant() { return merchant; }
}
