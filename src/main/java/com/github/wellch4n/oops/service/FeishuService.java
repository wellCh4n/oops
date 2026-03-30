package com.github.wellch4n.oops.service;

import com.github.wellch4n.oops.config.FeishuConfig;
import com.github.wellch4n.oops.data.ExternalAccount;
import com.github.wellch4n.oops.data.ExternalAccountRepository;
import com.github.wellch4n.oops.data.User;
import com.github.wellch4n.oops.enums.ExternalAccountProvider;
import com.github.wellch4n.oops.enums.UserRole;
import com.github.wellch4n.oops.utils.JwtUtils;
import com.lark.oapi.Client;
import com.lark.oapi.core.request.RequestOptions;
import com.lark.oapi.service.authen.v1.model.CreateAccessTokenReq;
import com.lark.oapi.service.authen.v1.model.CreateAccessTokenReqBody;
import com.lark.oapi.service.authen.v1.model.CreateAccessTokenResp;
import com.lark.oapi.service.authen.v1.model.GetUserInfoResp;
import com.lark.oapi.service.contact.v3.model.GetUserResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Service
public class FeishuService {

    private static final Logger log = LoggerFactory.getLogger(FeishuService.class);

    private final ExternalAccountRepository externalAccountRepository;
    private final UserService userService;
    private final JwtUtils jwtUtils;
    private final Client client;
    private final FeishuConfig feishuConfig;

    public FeishuService(ExternalAccountRepository externalAccountRepository,
                         UserService userService,
                         JwtUtils jwtUtils,
                         FeishuConfig feishuConfig) {
        this.externalAccountRepository = externalAccountRepository;
        this.userService = userService;
        this.jwtUtils = jwtUtils;
        this.feishuConfig = feishuConfig;
        this.client = Client.newBuilder(feishuConfig.getAppId(), feishuConfig.getAppSecret()).build();
    }

    public String getLoginUrl() {
        return "https://open.feishu.cn/open-apis/authen/v1/authorize"
                + "?app_id=" + feishuConfig.getAppId()
                + "&redirect_uri=" + URLEncoder.encode(feishuConfig.getRedirectUri(), StandardCharsets.UTF_8)
                + "&state=feishu";
    }

    public String authenticate(String code) throws IOException {
        CreateAccessTokenResp tokenResp;
        try {
            tokenResp = client.authen().v1().accessToken()
                    .create(CreateAccessTokenReq.newBuilder()
                            .createAccessTokenReqBody(CreateAccessTokenReqBody.newBuilder()
                                    .grantType("authorization_code")
                                    .code(code)
                                    .build())
                            .build());
        } catch (Exception e) {
            throw new IOException("Feishu API error: " + e.getMessage(), e);
        }
        if (!tokenResp.success()) {
            throw new IOException("Failed to get access token: " + tokenResp.getMsg() + ", code: " + tokenResp.getCode());
        }
        String accessToken = tokenResp.getData().getAccessToken();

        GetUserInfoResp userInfoResp;
        try {
            userInfoResp = client.authen().v1().userInfo()
                    .get(RequestOptions.newBuilder()
                            .userAccessToken(accessToken)
                            .build());
        } catch (Exception e) {
            throw new IOException("Feishu API error: " + e.getMessage(), e);
        }
        if (!userInfoResp.success()) {
            throw new IOException("Failed to get user info: " + userInfoResp.getMsg() + ", code: " + userInfoResp.getCode());
        }

        String providerUserId = userInfoResp.getData().getUserId();
        String userEmail = userInfoResp.getData().getEmail();
        String enterpriseEmail = userInfoResp.getData().getEnterpriseEmail();

        String email = enterpriseEmail != null ? enterpriseEmail : userEmail;

        // 查找或创建用户
        Optional<ExternalAccount> existingAccount = externalAccountRepository
                .findByProviderAndProviderUserId(ExternalAccountProvider.FEISHU, providerUserId);

        User user;
        if (existingAccount.isPresent()) {
            user = userService.findById(existingAccount.get().getUserId()).orElse(null);
            if (user == null) {
                user = findOrCreateUser(userInfoResp.getData().getName(), email);
                existingAccount.get().setUserId(user.getId());
                existingAccount.get().setEmail(email);
                externalAccountRepository.save(existingAccount.get());
            }
        } else {
            user = findOrCreateUser(userInfoResp.getData().getName(), email);
            ExternalAccount account = new ExternalAccount();
            account.setProvider(ExternalAccountProvider.FEISHU);
            account.setProviderUserId(providerUserId);
            account.setUserId(user.getId());
            account.setEmail(email);
            externalAccountRepository.save(account);
        }

        return jwtUtils.generateToken(user.getUsername(), user.getRole().name());
    }

    private User findOrCreateUser(String name, String email) {
        Optional<User> byEmail = userService.findByEmail(email);
        if (byEmail.isPresent()) {
            return byEmail.get();
        }
        String username = (name != null && !name.isBlank())
                ? name
                : "feishu_" + System.currentTimeMillis();
        return userService.createUser(username, email, null, UserRole.USER);
    }
}